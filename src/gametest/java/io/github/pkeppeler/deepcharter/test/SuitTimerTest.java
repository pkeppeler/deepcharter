package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import com.mojang.serialization.Codec;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitState;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import io.github.pkeppeler.deepcharter.suit.SuitTicker;
import io.github.pkeppeler.deepcharter.suit.SuitTuning;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.Findings;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Pocket;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Server GameTests for #993's suit timer (ADR 0083): where it runs, what it drains and refills, the warnings before the harm, the harm,
 * and what it keeps across a death. The unbuilt depth of the campaign world is the one depth that asks something today, so it is where
 * the suit drains. A body that counts seconds calls {@link SuitTicker#second} on one tick ({@link SuitScenes}); the tests that let the
 * real tick run say so.
 */
public class SuitTimerTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 600;
    private static final float EPSILON = 0.001f;
    private static final float CAPACITY = SuitTuning.DEFAULT.capacities().getFirst();
    private static final float MAX_CAPACITY = SuitTuning.DEFAULT.maxCapacity();
    /** What a stock suit pays each second in the unbuilt depth, dry: 0.5 + 0.5 * 5 + 0.5 * 5. */
    private static final float DRY_RATE = 5.5f;
    /** As above while wet: the flooding term adds 1.0 * 5. */
    private static final float WET_RATE = 10.5f;

    private static boolean near(float actual, float expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "";
    }

    private static List<String> keys(MockPlayer mock) {
        return mock.actionBarMessages().stream().map(SuitTimerTest::key).toList();
    }

    private static int mask(RatingAxis... axes) {
        int mask = 0;
        for (RatingAxis axis : axes) {
            mask |= SuitState.bit(axis);
        }
        return mask;
    }

    /** The surface and the first two layers ask nothing, so the suit does not move there, whatever the person does. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theSuitIsInertOnTheSurfaceAndInLayersOneAndTwo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<CampaignBands.Band> bands = List.of(CampaignBands.SURFACE, CampaignBands.LAYER_1, CampaignBands.LAYER_2);
        List<Pocket> pockets = new java.util.ArrayList<>();
        for (int i = 0; i < bands.size(); i++) {
            pockets.add(SuitScenes.inBand(level, bands.get(i), 6400 + 48 * i));
        }
        MockPlayer mock = MockPlayers.join(helper, "suit-inert");
        SuitScenes.whenReady(helper, pockets, () -> {
            for (int i = 0; i < bands.size(); i++) {
                SuitScenes.setReserve(mock.player(), 90f, false);
                int at = i;
                SuitScenes.onFootIn(mock, pockets.get(i), () -> SuitScenes.seconds(mock.player(), 20));
                SuitState state = SuitScenes.state(mock.player());
                expect(
                        helper,
                        near(state.reserve(), 90f) && state.mode() == Mode.INERT && !state.explained(),
                        "the %s asks nothing, so the suit should not move, it reads %s",
                        bands.get(at).name(),
                        state);
            }
            helper.succeed();
        });
    }

    /** Where the depth has no row the suit reads the uncharted one: 5.5 a second, heat and pressure, until the reserve is spent. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theSuitDrainsInTheUnbuiltDepthAndBreachesAtZero(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 6600);
        MockPlayer mock = MockPlayers.join(helper, "suit-drain");
        SuitScenes.whenReady(helper, pocket, () -> {
            ServerPlayer player = mock.player();
            SuitScenes.setReserve(player, CAPACITY, false);
            SuitScenes.onFootIn(mock, pocket, () -> {
                SuitScenes.seconds(player, 1);
                SuitState first = SuitScenes.state(player);
                expect(
                        helper,
                        near(first.reserve(), CAPACITY - DRY_RATE)
                                && first.mode() == Mode.EXPOSED
                                && first.axes() == mask(RatingAxis.HEAT, RatingAxis.PRESSURE)
                                && near(first.rate(), DRY_RATE)
                                && first.explained(),
                        "one second on foot in unbuilt depth costs %s and says why, it reads %s",
                        DRY_RATE,
                        first);
                SuitScenes.seconds(player, 31);
                SuitState nearEnd = SuitScenes.state(player);
                expect(
                        helper,
                        nearEnd.mode() == Mode.EXPOSED && nearEnd.reserve() > 0f,
                        "32 seconds of 5.5 leave some reserve, it reads %s",
                        nearEnd);
                SuitScenes.seconds(player, 1);
                SuitState spent = SuitScenes.state(player);
                expect(
                        helper,
                        spent.reserve() == 0f && spent.mode() == Mode.BREACHED && spent.explained(),
                        "the 33rd second spends the reserve and breaches the suit, it reads %s",
                        spent);
            });
            helper.succeed();
        });
    }

    /** Seated in a pod the suit takes its reserve back when the pod has power, and keeps it when it has none. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aPoweredPodSeatRefillsTheSuitAndAnUnpoweredOneHoldsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pocket pocket = SuitScenes.unbuilt(level, 6700);
        MockPlayer mock = MockPlayers.join(helper, "suit-pod");
        SuitScenes.whenReady(helper, pocket, () -> {
            ServerPlayer player = mock.player();
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(pocket.stand());
            level.addFreshEntity(pod);
            try {
                SuitScenes.onFootIn(mock, pocket, () -> {
                    if (!player.startRiding(pod, true, false)) {
                        throw helper.assertionException("the player could not mount the pod");
                    }
                    SuitScenes.setReserve(player, 60f, true);
                    SuitScenes.seconds(player, 4);
                    SuitState refilled = SuitScenes.state(player);
                    expect(
                            helper,
                            near(refilled.reserve(), 120f) && refilled.mode() == Mode.POD_REFILL,
                            "four seconds in a powered seat give back 4 * 15, it reads %s",
                            refilled);
                    SuitScenes.seconds(player, 10);
                    expect(
                            helper,
                            near(SuitScenes.state(player).reserve(), CAPACITY),
                            "the reserve stops at the capacity, it reads %s",
                            SuitScenes.state(player));
                    pod.setStranded(true);
                    SuitScenes.setReserve(player, 60f, true);
                    SuitScenes.seconds(player, 10);
                    SuitState held = SuitScenes.state(player);
                    expect(
                            helper,
                            near(held.reserve(), 60f) && held.mode() == Mode.POD_HOLD,
                            "a pod with no power holds the reserve, it reads %s",
                            held);
                });
            } finally {
                pod.discard();
            }
            helper.succeed();
        });
    }

    /** The suit is a matter for a person on foot who plays the depth: not for a creative player or a spectator. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void creativeAndSpectatorPlayersAreSkipped(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 6800);
        MockPlayer mock = MockPlayers.join(helper, "suit-skipped");
        SuitScenes.whenReady(helper, pocket, () -> {
            ServerPlayer player = mock.player();
            mock.teleportTo(pocket.level(), pocket.stand(), 0f, 0f);
            for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
                SuitScenes.setReserve(player, 100f, false);
                player.setGameMode(mode);
                SuitScenes.seconds(player, 10);
                SuitState state = SuitScenes.state(player);
                expect(
                        helper,
                        near(state.reserve(), 100f) && state.mode() == Mode.INERT && !state.explained(),
                        "a %s player's suit should not move, it reads %s",
                        mode,
                        state);
            }
            player.setGameMode(GameType.SPECTATOR);
            helper.succeed();
        });
    }

    /**
     * The amber line, the red line and the breach come in that order and before any harm; once breached, the suit hurts every forty
     * ticks. The warnings are read off the mock's action bar, which lags the server by a tick, so the test runs across ticks.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theWarningsComeBeforeTheDamageAndTheBreachHurtsEveryFortyTicks(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 6900);
        MockPlayer mock = MockPlayers.join(helper, "suit-warn");
        ServerPlayer player = mock.player();
        Findings found = new Findings();
        long[] hits = {-1, -1};
        float[] health = new float[1];
        SuitScenes.sequence(helper, pocket)
                .thenExecute(() -> {
                    health[0] = player.getHealth();
                    // Too hungry to heal, so a hit stays a hit.
                    player.getFoodData().setFoodLevel(10);
                    // 169 suit-seconds at 5.5 a second: 30.7 s left, so the next second crosses the amber line.
                    SuitScenes.setReserve(player, 169f, true);
                    SuitScenes.onFootIn(mock, pocket, () -> SuitScenes.seconds(player, 1));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    if (!keys(mock).equals(List.of("message.deepcharter.suit.low"))) {
                        found.note("the amber line should be the only one so far, the bar said " + keys(mock));
                    }
                    if (player.getHealth() != health[0]) {
                        found.note("a warning must not cost health, it is " + player.getHealth());
                    }
                    // 60 at 5.5: 10.9 s left, so the next second is under ten.
                    SuitScenes.setReserve(player, 60f, true);
                    SuitScenes.onFootIn(mock, pocket, () -> SuitScenes.seconds(player, 1));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    if (!keys(mock)
                            .equals(List.of("message.deepcharter.suit.low", "message.deepcharter.suit.critical"))) {
                        found.note("the red line should follow the amber one, the bar said " + keys(mock));
                    }
                    if (player.getHealth() != health[0]) {
                        found.note("a warning must not cost health, it is " + player.getHealth());
                    }
                    SuitScenes.setReserve(player, 5f, true);
                    SuitScenes.onFootIn(mock, pocket, () -> SuitScenes.seconds(player, 1));
                    // Survival again so the real tick hurts: the suit is breached and the person stays on foot.
                    player.setGameMode(GameType.SURVIVAL);
                })
                .thenExecute(() -> found.ensure(helper))
                .thenWaitUntil(() -> {
                    found.ensure(helper);
                    if (!keys(mock).contains("message.deepcharter.suit.breached")) {
                        throw helper.assertionException("waiting for the breach line, the bar said %s", keys(mock));
                    }
                    if (player.getHealth() >= health[0]) {
                        throw helper.assertionException("waiting for the first breach hit");
                    }
                    hits[0] = helper.getTick();
                })
                .thenWaitUntil(() -> {
                    if (player.getHealth() > health[0] - 2 * SuitTuning.DEFAULT.breachDamage() + EPSILON) {
                        throw helper.assertionException("waiting for the second breach hit");
                    }
                    hits[1] = helper.getTick();
                })
                .thenExecute(() -> {
                    expect(
                            helper,
                            near(health[0] - player.getHealth(), 2 * SuitTuning.DEFAULT.breachDamage()),
                            "two hits of %s, health went from %s to %s",
                            SuitTuning.DEFAULT.breachDamage(),
                            health[0],
                            player.getHealth());
                    expect(
                            helper,
                            hits[1] - hits[0] == SuitTuning.DEFAULT.breachTicks(),
                            "hits come every %s ticks, these came %s apart",
                            SuitTuning.DEFAULT.breachTicks(),
                            hits[1] - hits[0]);
                    player.setGameMode(GameType.SPECTATOR);
                })
                .thenSucceed();
    }

    /** The real tick pays the second out every twentieth tick: sixty ticks in the depth cost three seconds. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theRealTickRunsASecondEveryTwentyTicks(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 7000);
        MockPlayer mock = MockPlayers.join(helper, "suit-cadence");
        SuitScenes.sequence(helper, pocket)
                .thenExecute(() -> {
                    SuitScenes.setReserve(mock.player(), CAPACITY, true);
                    mock.teleportTo(pocket.level(), pocket.stand(), 0f, 0f);
                    mock.player().setGameMode(GameType.SURVIVAL);
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    float spent = CAPACITY - SuitScenes.state(mock.player()).reserve();
                    mock.player().setGameMode(GameType.SPECTATOR);
                    expect(
                            helper,
                            near(spent, 3 * DRY_RATE),
                            "sixty ticks should cost three seconds, 16.5, it cost %s",
                            spent);
                })
                .thenSucceed();
    }

    /** Under water with reserve left the person does not drown: the air holds. Spent, vanilla drowning resumes. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void underWaterTheAirHoldsWhileTheReserveLastsAndDrowningResumesWhenItIsSpent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pocket pocket = SuitScenes.unbuilt(level, 7100);
        MockPlayer mock = MockPlayers.join(helper, "suit-water");
        ServerPlayer player = mock.player();
        SuitScenes.sequence(helper, pocket)
                .thenExecute(() -> {
                    BlockPos from = pocket.floor().offset(-2, 1, -2);
                    BlockPos to = pocket.floor().offset(2, 3, 2);
                    for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
                        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                    }
                    SuitScenes.setReserve(player, CAPACITY, true);
                    mock.teleportTo(level, pocket.stand(), 0f, 0f);
                    player.setGameMode(GameType.SURVIVAL);
                })
                .thenWaitUntil(() -> {
                    if (!player.isUnderWater()) {
                        throw helper.assertionException("waiting for the player to be under water");
                    }
                })
                .thenExecute(() -> SuitTicker.second(player))
                .thenIdle(30)
                .thenExecute(() -> {
                    SuitState wet = SuitScenes.state(player);
                    expect(
                            helper,
                            wet.mode() == Mode.EXPOSED && near(wet.rate(), WET_RATE),
                            "under water the flooding term joins the rate, it reads %s",
                            wet);
                    expect(
                            helper,
                            wet.axes() == mask(RatingAxis.HEAT, RatingAxis.PRESSURE, RatingAxis.FLOODING),
                            "under water flooding drains too, it reads %s",
                            wet);
                    // The tick takes one breath before the suit puts it back, so the air reads one under at most;
                    // vanilla would be 30 under.
                    expect(
                            helper,
                            player.getMaxAirSupply() - player.getAirSupply() <= 1,
                            "with reserve left the air holds at %s, it is %s",
                            player.getMaxAirSupply(),
                            player.getAirSupply());
                    SuitScenes.setReserve(player, 0f, true);
                    SuitTicker.second(player);
                })
                .thenWaitUntil(() -> {
                    if (player.getAirSupply() >= player.getMaxAirSupply()) {
                        throw helper.assertionException("waiting for the air to run out once the reserve is spent");
                    }
                })
                .thenExecute(() -> player.setGameMode(GameType.SPECTATOR))
                .thenSucceed();
    }

    /** A death refills the suit by the next tick and keeps what the person was told: the flag is theirs, the reserve is a new life's. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aRespawnRefillsTheSuitAndTheExplainedFlagSurvivesIt(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "suit-respawn");
        ServerPlayer dead = mock.player();
        dead.setGameMode(GameType.SURVIVAL);
        SuitScenes.setReserve(dead, 12f, true);
        helper.startSequence()
                .thenExecute(() -> {
                    dead.kill(helper.getLevel());
                    dead.connection.handleClientCommand(
                            new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    expect(helper, dead.connection.getPlayer() != dead, "the player should have respawned");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    SuitState state = SuitScenes.state(dead.connection.getPlayer());
                    expect(
                            helper,
                            near(state.reserve(), CAPACITY) && state.explained() && state.mode() == Mode.INERT,
                            "a new life starts with a full suit that remembers it was explained, it reads %s",
                            state);
                })
                .thenSucceed();
    }

    /** Leaving the End respawns the person alive: that is no new life, and the suit keeps the reserve it had. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aRespawnAliveOutOfTheEndDoesNotRefillTheSuit(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "suit-end-exit");
        ServerPlayer before = mock.player();
        SuitScenes.setReserve(before, 12f, true);
        helper.startSequence()
                .thenExecute(() -> {
                    // The flag the credits leave: the respawn command then takes the alive path out of the End.
                    before.wonGame = true;
                    before.connection.handleClientCommand(
                            new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    expect(helper, before.connection.getPlayer() != before, "the player should have respawned");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    SuitState state = SuitScenes.state(before.connection.getPlayer());
                    expect(
                            helper,
                            near(state.reserve(), 12f) && state.explained(),
                            "a respawn alive is no death and must not refill the suit, it reads %s",
                            state);
                })
                .thenSucceed();
    }

    /** What is saved, and what goes to the client, is pinned by literal values. */
    @GameTest
    public void theSuitsSavedAndSentFormsArePinned(GameTestHelper helper) {
        Codec<Versioned<SuitState>> disk = SuitState.DISK;
        expect(helper, SuitState.VERSION == 2, "the wire grew a shortfall in version 2");

        // A v1 save, from before the wire carried a shortfall, still loads: the saved body did not change.
        CompoundTag saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.putFloat("reserve", 42.5f);
        saved.putBoolean("explained", true);
        Versioned<SuitState> loaded = disk.parse(NbtOps.INSTANCE, saved).getOrThrow();
        expect(
                helper,
                loaded.equals(Versioned.of(new SuitState(42.5f, true, Mode.INERT, 0f, 0))),
                "a v1 save should load as a reserve and a flag, it loaded %s",
                loaded);
        CompoundTag savedV2 = saved.copy();
        savedV2.putInt("version", 2);
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, savedV2).getOrThrow().equals(loaded),
                "a v2 save of the same body should load the same");
        expect(
                helper,
                disk.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow().equals(savedV2),
                "a loaded v1 save is written back as v2, the same body");

        Tag written = disk.encodeStart(
                        NbtOps.INSTANCE, Versioned.of(new SuitState(10f, false, Mode.LEAKING, 2.75f, 0x11)))
                .getOrThrow();
        CompoundTag expected = new CompoundTag();
        expected.putFloat("reserve", 10f);
        expected.putInt("version", 2);
        expect(
                helper,
                written.equals(expected),
                "a mode, axes and rate are never saved, only the reserve and, once set, the flag, it wrote %s",
                written);
        Tag explainedWritten = disk.encodeStart(
                        NbtOps.INSTANCE, Versioned.of(new SuitState(10f, true, Mode.INERT, 0f, 0)))
                .getOrThrow();
        expected.putBoolean("explained", true);
        expect(
                helper,
                explainedWritten.equals(expected),
                "the flag is written once set, it wrote %s",
                explainedWritten);

        // A reserve out of range, or not a number, reads as a sane one: NaN as a full suit, the rest clamped to the
        // largest
        // capacity any suit has (the suit's own capacity clamps it further each second). 300 s, an air tier 2 suit's,
        // is no
        // longer cut to a stock suit's 180.
        Map<Float, Float> sane = new LinkedHashMap<>();
        sane.put(Float.NaN, MAX_CAPACITY);
        sane.put(-5f, 0f);
        sane.put(Float.POSITIVE_INFINITY, MAX_CAPACITY);
        sane.put(CAPACITY + 1f, CAPACITY + 1f);
        sane.put(300f, 300f);
        sane.put(MAX_CAPACITY, MAX_CAPACITY);
        sane.put(MAX_CAPACITY + 1f, MAX_CAPACITY);
        for (Map.Entry<Float, Float> entry : sane.entrySet()) {
            CompoundTag odd = new CompoundTag();
            odd.putInt("version", 2);
            odd.putFloat("reserve", entry.getKey());
            Versioned<SuitState> read = disk.parse(NbtOps.INSTANCE, odd).getOrThrow();
            expect(
                    helper,
                    read instanceof Versioned.Readable<SuitState> readable
                            && readable.value().reserve() == entry.getValue(),
                    "a saved reserve of %s should read as %s, it read %s",
                    entry.getKey(),
                    entry.getValue(),
                    read);
        }

        CompoundTag future = UnreadableChecks.futureData();
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, future).getOrThrow() instanceof Versioned.Unreadable<SuitState>,
                "a version of the future reads as unreadable, never as a failure");

        expect(
                helper,
                List.of(Mode.values())
                        .equals(List.of(
                                Mode.INERT,
                                Mode.EXPOSED,
                                Mode.LEAKING,
                                Mode.SHELTERED,
                                Mode.POD_REFILL,
                                Mode.POD_HOLD,
                                Mode.BREACHED)),
                "the modes are on the wire in this order, add at the end only");

        ByteBuf wire = Unpooled.buffer();
        // heat short by 2 (bits 0 to 3), pressure by 3 (bits 4 to 7): 0x32
        SuitState sent = new SuitState(100.25f, true, Mode.EXPOSED, 0.5f, 0x32);
        SuitState.STREAM.encode(wire, sent);
        // reserve (float), explained (boolean), mode (var int), rate (float), shortfall (var int); the axes are derived
        // from it
        byte[] bytes = new byte[wire.readableBytes()];
        wire.getBytes(0, bytes);
        expect(
                helper,
                bytes.length == 4 + 1 + 1 + 4 + 1
                        && bytes[4] == 1
                        && bytes[5] == (byte) Mode.EXPOSED.ordinal()
                        && bytes[10] == 0x32,
                "the wire body is reserve, flag, mode, rate, shortfall, it was %s bytes: %s",
                bytes.length,
                java.util.Arrays.toString(bytes));
        SuitState received = SuitState.STREAM.decode(wire);
        expect(helper, received.equals(sent), "the wire body should round-trip");
        expect(
                helper,
                received.axes() == mask(RatingAxis.HEAT, RatingAxis.PRESSURE),
                "the axes are derived from the shortfall, heat and pressure, they read %s",
                received.axes());

        // The attachment's wire form: the version, then the body. A build that reads another version throws, which
        // disconnects
        // its client (the rule of m2-rules.md), and an unreadable value goes as a marker.
        StreamCodec<ByteBuf, Versioned<SuitState>> sync = Versioned.streamCodec(SuitState.VERSION, SuitState.STREAM);
        ByteBuf versioned = Unpooled.buffer();
        sync.encode(versioned, Versioned.of(sent));
        expect(
                helper,
                versioned.getByte(0) == 2,
                "the wire carries version 2 first, it carried %s",
                versioned.getByte(0));
        expect(helper, sync.decode(versioned).equals(Versioned.of(sent)), "the synced suit should round-trip");
        ByteBuf old = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(old, 1);
        SuitState.STREAM.encode(old, new SuitState(100.25f, true, Mode.SHELTERED, 0f, 0));
        boolean refused;
        try {
            sync.decode(old);
            refused = false;
        } catch (IllegalStateException mismatch) {
            refused = true;
        }
        expect(helper, refused, "a v1 wire value must throw on a v2 client, not be read as a v2 body");
        ByteBuf marker = Unpooled.buffer();
        sync.encode(marker, new Versioned.Unreadable<>(new CompoundTag()));
        expect(
                helper,
                sync.decode(marker) instanceof Versioned.Unreadable<SuitState>,
                "an unreadable suit goes over as a marker and arrives unreadable");

        expect(
                helper,
                SuitTuning.DEFAULT.leakShare() == 0.5f
                        && SuitTuning.DEFAULT.capacities().equals(List.of(180f, 240f, 300f, 360f)),
                "the docs say a hall leaks at half and the suit holds 180, 240, 300 or 360 s");
        helper.succeed();
    }

    /** The first drain's line names the axes that are short; where none is (a row that asks only flooding, dry), it stands alone. */
    @GameTest
    public void theFirstDrainLineNamesTheShortAxesOrStandsAlone(GameTestHelper helper) {
        Component named = new SuitState(100f, false, Mode.EXPOSED, 1.5f, 0x11).exceededLine();
        expect(
                helper,
                key(named).equals("message.deepcharter.suit.exceeded")
                        && ((TranslatableContents) named.getContents()).getArgs().length == 1,
                "a drain on heat and pressure names them, it said %s",
                named);
        Component bare = new SuitState(100f, false, Mode.EXPOSED, 0.5f, 0).exceededLine();
        expect(
                helper,
                key(bare).equals("message.deepcharter.suit.draining")
                        && ((TranslatableContents) bare.getContents()).getArgs().length == 0,
                "a drain on no axis has a line of its own with nothing dangling, it said %s",
                bare);
        helper.succeed();
    }

    /** Unreadable saved suit data stops the suit: no drain, no damage, nothing thrown, however often the tick runs. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void unreadableSuitDataNeverThrowsAndNeverDrainsOrHurts(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 7200);
        MockPlayer mock = MockPlayers.join(helper, "suit-unreadable");
        ServerPlayer player = mock.player();
        float[] health = new float[1];
        SuitScenes.sequence(helper, pocket)
                .thenExecute(() -> {
                    health[0] = player.getHealth();
                    mock.teleportTo(pocket.level(), pocket.stand(), 0f, 0f);
                    player.setGameMode(GameType.SURVIVAL);
                    UnreadableChecks.makeUnreadable(player, SuitRegistry.SUIT);
                    Map<String, Runnable> paths = new LinkedHashMap<>();
                    paths.put("second", () -> SuitTicker.second(player));
                    UnreadableChecks.assertNoThrow(helper, "suit", paths);
                })
                // The real tick runs on the unreadable data for two seconds and a breach cadence.
                .thenIdle(85)
                .thenExecute(() -> {
                    player.setGameMode(GameType.SPECTATOR);
                    expect(
                            helper,
                            player.getAttached(SuitRegistry.SUIT) instanceof Versioned.Unreadable<SuitState>,
                            "the unreadable suit data is kept as it was");
                    expect(helper, player.getHealth() == health[0], "an unreadable suit must not hurt");
                })
                .thenSucceed();
    }
}
