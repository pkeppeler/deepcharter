package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import com.mojang.serialization.Codec;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitShop;
import io.github.pkeppeler.deepcharter.suit.SuitState;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import io.github.pkeppeler.deepcharter.suit.SuitTicker;
import io.github.pkeppeler.deepcharter.suit.SuitTrack;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
import io.github.pkeppeler.deepcharter.suit.SuitTuning;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Pocket;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1064's suit tracks (ADR 0091): the attachment's pinned forms, the tier table, the shop at the repair station (an
 * exact debit, every refusal before the spend, the air buy that fills the suit), what a tier keeps across a death and a login, and what the
 * tiers do to the drain and a hall's leak.
 */
public class SuitTracksTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 600;
    private static final float EPSILON = 0.001f;
    private static final long FUNDS = 20_000;

    private static boolean near(float actual, float expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    private static CompoundTag buy(String track, int tier) {
        CompoundTag args = new CompoundTag();
        args.putString(SuitShop.TRACK_KEY, track);
        args.putInt(SuitShop.TIER_KEY, tier);
        return args;
    }

    /** A working repair station, and a person on a charter with {@link #FUNDS} standing at it. */
    private record Station(BlockPos pos, MockPlayer buyer, Charter charter) {
        ServerPlayer player() {
            return buyer.player();
        }

        Optional<TerminalRefusal> act(CompoundTag args) {
            return Terminals.act(player(), pos, SuitShop.SUIT_BUY, args);
        }

        long account(MinecraftServer server) {
            return Charters.findOrThrow(server, charter.id()).orElseThrow().account();
        }
    }

    private static void withStation(GameTestHelper helper, Consumer<Station> body) {
        MinecraftServer server = helper.getLevel().getServer();
        RepairState fresh = new RepairState();
        WorldData.with(server, RepairState.TYPE, fresh, () -> {
            for (TerminalType type : List.of(
                    TerminalTypes.FUEL_PUMP,
                    TerminalTypes.ORE_PROCESSOR,
                    TerminalTypes.UPGRADE_TERMINAL,
                    TerminalTypes.REPAIR_STATION)) {
                type.parts()
                        .forEach(part -> fresh.insert(type, part).ifPresent(refusal -> {
                            throw helper.assertionException("repairing %s: %s", type.id(), refusal);
                        }));
            }
            BlockPos relative = new BlockPos(0, 1, 0);
            helper.setBlock(relative, TerminalTypes.REPAIR_STATION.block().defaultBlockState());
            BlockPos pos = helper.absolutePos(relative);
            MockPlayer buyer = MockPlayers.join(helper, "SuitBuyer");
            buyer.player().setGameMode(GameType.SURVIVAL);
            String name = "Suit " + UUID.randomUUID().toString().substring(0, 8);
            if (Charters.found(server, buyer.player().getUUID(), name).isPresent()) {
                throw helper.assertionException("founding the charter should succeed");
            }
            Charter charter =
                    Charters.charterOfOrThrow(server, buyer.player().getUUID()).orElseThrow();
            buyer.teleportTo(
                    helper.getLevel(),
                    Vec3.atCenterOf(pos).add(2, -buyer.player().getEyeHeight(), 0),
                    0,
                    0);
            if (Charters.deposit(server, charter.id(), FUNDS).isPresent()) {
                throw helper.assertionException("funding the account should succeed");
            }
            body.accept(new Station(pos, buyer, charter));
            buyer.player().setGameMode(GameType.SPECTATOR);
        });
    }

    /** The saved form, the sent form and the clamps of the tracks, by literal values. */
    @GameTest
    public void theTracksSavedAndSentFormsArePinned(GameTestHelper helper) {
        Codec<Versioned<SuitTracks>> disk = Versioned.codec(SuitTracks.VERSION, SuitTracks.BODY);
        expect(helper, SuitTracks.VERSION == 1, "the first version is 1");

        CompoundTag saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.putInt("heat", 2);
        saved.putInt("pressure", 1);
        saved.putInt("air", 3);
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, saved).getOrThrow().equals(Versioned.of(new SuitTracks(2, 1, 3))),
                "the saved form should load as tiers 2, 1 and 3");
        expect(
                helper,
                disk.encodeStart(NbtOps.INSTANCE, Versioned.of(new SuitTracks(2, 1, 3)))
                        .getOrThrow()
                        .equals(saved),
                "tiers 2, 1 and 3 should write as the saved form");

        CompoundTag bare = new CompoundTag();
        bare.putInt("version", 1);
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, bare).getOrThrow().equals(Versioned.of(SuitTracks.STOCK)),
                "a missing tier reads 0");

        CompoundTag odd = new CompoundTag();
        odd.putInt("version", 1);
        odd.putInt("heat", 9);
        odd.putInt("pressure", -4);
        odd.putInt("air", 4);
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, odd).getOrThrow().equals(Versioned.of(new SuitTracks(3, 0, 3))),
                "a tier out of range reads clamped to 0 to 3");

        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, UnreadableChecks.futureData()).getOrThrow()
                        instanceof Versioned.Unreadable<SuitTracks>,
                "a version of the future reads as unreadable, never as a failure");

        ByteBuf wire = Unpooled.buffer();
        SuitTracks.STREAM.encode(wire, new SuitTracks(1, 2, 3));
        expect(
                helper,
                wire.readableBytes() == 3 && wire.getByte(0) == 1 && wire.getByte(1) == 2 && wire.getByte(2) == 3,
                "the wire body is three var ints, heat, pressure, air, it was %s bytes",
                wire.readableBytes());
        expect(helper, SuitTracks.STREAM.decode(wire).equals(new SuitTracks(1, 2, 3)), "the wire body round-trips");
        // The wire refuses what its encoder cannot produce, where the disk clamps: a tier of 9 or a negative one
        // throws.
        for (int bad : new int[] {9, 4, -1}) {
            ByteBuf wide = Unpooled.buffer();
            ByteBufCodecs.VAR_INT.encode(wide, bad);
            wide.writeByte(0).writeByte(0);
            boolean thrown;
            try {
                SuitTracks.STREAM.decode(wide);
                thrown = false;
            } catch (IllegalArgumentException outOfRange) {
                thrown = true;
            }
            expect(helper, thrown, "a wire tier of %s must be refused, not clamped", bad);
        }

        StreamCodec<ByteBuf, Versioned<SuitTracks>> sync = Versioned.streamCodec(SuitTracks.VERSION, SuitTracks.STREAM);
        ByteBuf other = Unpooled.buffer();
        other.writeByte(2).writeByte(0).writeByte(0).writeByte(0);
        boolean refused;
        try {
            sync.decode(other);
            refused = false;
        } catch (IllegalStateException expected) {
            refused = true;
        }
        expect(helper, refused, "a wire value of another version throws, as the rule says");
        helper.succeed();
    }

    /** What each tier is worth, by literal values: ratings, capacity and price. */
    @GameTest
    public void theTierTableIsPinned(GameTestHelper helper) {
        SuitTuning tuning = SuitTuning.DEFAULT;
        expect(
                helper,
                tuning.capacities().equals(List.of(180f, 240f, 300f, 360f)) && tuning.maxCapacity() == 360f,
                "the capacities are 180, 240, 300 and 360, they are %s",
                tuning.capacities());
        for (int tier = 0; tier <= 3; tier++) {
            SuitTracks tracks = new SuitTracks(tier, 0, tier);
            expect(
                    helper,
                    tracks.ratings().equals(new Ratings(tier, 0, tier, 0))
                            && tracks.capacity(tuning) == tuning.capacities().get(tier),
                    "heat and air tier %s rate %s and hold %s",
                    tier,
                    tracks.ratings(),
                    tracks.capacity(tuning));
        }
        expect(
                helper,
                new SuitTracks(0, 2, 0).ratings().equals(new Ratings(0, 2, 0, 0)),
                "the pressure tier is the pressure rating, and quake is never rated");
        long[][] prices = {{450, 1_100, 2_500}, {450, 1_100, 2_500}, {550, 1_400, 3_000}};
        for (SuitTrack track : SuitTrack.values()) {
            expect(helper, track.maxTier() == 3, "%s has three tiers", track);
            for (int tier = 1; tier <= 3; tier++) {
                expect(
                        helper,
                        track.price(tier) == prices[track.ordinal()][tier - 1],
                        "%s tier %s costs $%s, not $%s",
                        track,
                        tier,
                        prices[track.ordinal()][tier - 1],
                        track.price(tier));
            }
        }
        helper.succeed();
    }

    /** A new person has the stock suit: no tier, 180 suit-seconds. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aNewPlayerHasTheStockSuit(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "suit-new");
        expect(
                helper,
                SuitScenes.tracks(mock.player()).equals(SuitTracks.STOCK),
                "a new player has no tier, they have %s",
                SuitScenes.tracks(mock.player()));
        expect(
                helper,
                SuitScenes.state(mock.player()).reserve() == 180f,
                "a new player's suit holds 180 s, it holds %s",
                SuitScenes.state(mock.player()).reserve());
        helper.succeed();
    }

    /** Each buy takes exactly its price from the account, lifts one track one tier, and says so. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aBuyDebitsTheExactPriceAndFitsTheNextTier(GameTestHelper helper) {
        withStation(helper, station -> {
            MinecraftServer server = helper.getLevel().getServer();
            long account = station.account(server);
            SuitTracks expected = SuitTracks.STOCK;
            for (SuitTrack track : SuitTrack.values()) {
                for (int tier = 1; tier <= track.maxTier(); tier++) {
                    Optional<TerminalRefusal> refusal = station.act(buy(track.id(), tier));
                    expect(
                            helper,
                            refusal.isEmpty(),
                            "%s tier %s should sell, it was refused: %s",
                            track,
                            tier,
                            refusal);
                    account -= track.price(tier);
                    expected = expected.with(track, tier);
                    expect(
                            helper,
                            station.account(server) == account,
                            "%s tier %s should debit exactly $%s, the account is $%s, not $%s",
                            track,
                            tier,
                            track.price(tier),
                            station.account(server),
                            account);
                    expect(
                            helper,
                            SuitScenes.tracks(station.player()).equals(expected),
                            "the tracks should read %s, they read %s",
                            expected,
                            SuitScenes.tracks(station.player()));
                }
            }
            expect(helper, account == FUNDS - 4_050 * 2 - 4_950, "the whole suit costs $13,050, from $%s", FUNDS);
            helper.succeed();
        });
    }

    /** Every refusal comes before the spend: it changes neither the tracks nor the suit, and it charges nothing. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void everyRefusalChangesNothingAndChargesNothing(GameTestHelper helper) {
        withStation(helper, station -> {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = station.player();
            SuitScenes.setTracks(player, new SuitTracks(1, 3, 0));
            SuitScenes.setReserve(player, 77f, true);
            SuitTracks tracks = SuitScenes.tracks(player);
            SuitState suit = SuitScenes.state(player);
            long account = station.account(server);
            CompoundTag noTier = new CompoundTag();
            noTier.putString(SuitShop.TRACK_KEY, "heat");
            CompoundTag noTrack = new CompoundTag();
            noTrack.putInt(SuitShop.TIER_KEY, 1);
            CompoundTag textTier = new CompoundTag();
            textTier.putString(SuitShop.TRACK_KEY, "heat");
            textTier.putString(SuitShop.TIER_KEY, "2");
            String[] what = {
                "an unknown track",
                "toxins, which is only a name",
                "no tier",
                "no track",
                "a tier that is text",
                "a tier already held",
                "a tier two ahead",
                "tier 0",
                "a negative tier",
                "the top tier again (already fitted)",
                "past the top tier",
                "air tier 3 from none",
            };
            List<CompoundTag> attempts = List.of(
                    buy("lamp", 1),
                    buy("toxins", 1),
                    noTier,
                    noTrack,
                    textTier,
                    buy("heat", 1),
                    buy("heat", 3),
                    buy("heat", 0),
                    buy("heat", -1),
                    buy("pressure", 3),
                    buy("pressure", 4),
                    buy("air", 3));
            for (int i = 0; i < attempts.size(); i++) {
                Optional<TerminalRefusal> refusal = station.act(attempts.get(i));
                expect(
                        helper,
                        refusal.equals(Optional.of(TerminalRefusal.ACTION_REFUSED)),
                        "%s should be refused by the action, it was %s",
                        what[i],
                        refusal);
                expect(
                        helper,
                        SuitScenes.tracks(player).equals(tracks)
                                && SuitScenes.state(player).equals(suit)
                                && station.account(server) == account,
                        "%s must change nothing and charge nothing",
                        what[i]);
            }
            // An account too small refuses too, and changes nothing.
            if (Charters.spend(server, station.charter().id(), station.account(server) - 100)
                    .isPresent()) {
                throw helper.assertionException("draining the account should succeed");
            }
            long poor = station.account(server);
            expect(
                    helper,
                    station.act(buy("heat", 2)).equals(Optional.of(TerminalRefusal.ACTION_REFUSED))
                            && SuitScenes.tracks(player).equals(tracks)
                            && station.account(server) == poor,
                    "a buy the account cannot pay is refused and changes nothing");
            // Unreadable data sells nothing: no tier is fitted over what this build cannot read.
            UnreadableChecks.makeUnreadable(player, SuitRegistry.TRACKS);
            expect(
                    helper,
                    station.act(buy("heat", 1)).equals(Optional.of(TerminalRefusal.ACTION_REFUSED))
                            && player.getAttached(SuitRegistry.TRACKS) instanceof Versioned.Unreadable<SuitTracks>
                            && station.account(server) == poor,
                    "unreadable tracks sell nothing and are kept as they were");
            SuitScenes.setTracks(player, tracks);
            UnreadableChecks.makeUnreadable(player, SuitRegistry.SUIT);
            expect(
                    helper,
                    station.act(buy("air", 1)).equals(Optional.of(TerminalRefusal.ACTION_REFUSED))
                            && player.getAttached(SuitRegistry.SUIT) instanceof Versioned.Unreadable<SuitState>
                            && SuitScenes.tracks(player).equals(tracks)
                            && station.account(server) == poor,
                    "an unreadable suit log sells nothing and charges nothing");
            helper.succeed();
        });
    }

    /**
     * A second click on the row just bought (the screen had not moved on) says the tier is already fitted, in the refusal's own words, and
     * charges nothing. The refusal reaches the action bar a tick later.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aSecondClickOnAFittedTierSaysSoAndChargesNothing(GameTestHelper helper) {
        MockPlayer[] buyer = new MockPlayer[1];
        long[] account = new long[2];
        helper.startSequence()
                .thenExecute(() -> withStation(helper, station -> {
                    buyer[0] = station.buyer();
                    MinecraftServer server = helper.getLevel().getServer();
                    expect(helper, station.act(buy("heat", 1)).isEmpty(), "heat tier 1 should sell");
                    account[0] = station.account(server);
                    expect(
                            helper,
                            station.act(buy("heat", 1)).equals(Optional.of(TerminalRefusal.ACTION_REFUSED)),
                            "a second buy of the same tier should be refused");
                    account[1] = station.account(server);
                    expect(helper, SuitScenes.tracks(station.player()).heat() == 1, "the tier stays 1");
                }))
                .thenIdle(2)
                .thenExecute(() -> {
                    expect(helper, account[0] == account[1], "the second click charged $%s", account[0] - account[1]);
                    List<String> keys = buyer[0].actionBarMessages().stream()
                            .map(message -> message.getContents() instanceof TranslatableContents contents
                                    ? contents.getKey()
                                    : "")
                            .toList();
                    expect(
                            helper,
                            keys.getLast().equals("message.deepcharter.suit.already_fitted"),
                            "the second click says already fitted, the bar said %s",
                            keys);
                })
                .thenSucceed();
    }

    /** An air buy fills the suit to its new capacity; a heat buy leaves the reserve as it is. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void anAirBuyFillsTheSuitToTheNewCapacity(GameTestHelper helper) {
        withStation(helper, station -> {
            ServerPlayer player = station.player();
            SuitScenes.setReserve(player, 50f, true);
            expect(helper, station.act(buy("heat", 1)).isEmpty(), "heat tier 1 should sell");
            expect(
                    helper,
                    SuitScenes.state(player).reserve() == 50f,
                    "a heat buy does not refill the suit, it reads %s",
                    SuitScenes.state(player).reserve());
            float[] capacity = {240f, 300f, 360f};
            for (int tier = 1; tier <= 3; tier++) {
                expect(helper, station.act(buy("air", tier)).isEmpty(), "air tier %s should sell", tier);
                SuitState state = SuitScenes.state(player);
                expect(
                        helper,
                        state.reserve() == capacity[tier - 1] && state.explained(),
                        "air tier %s fills the suit to %s and keeps what it was told, it reads %s",
                        tier,
                        capacity[tier - 1],
                        state);
                SuitScenes.setReserve(player, 50f, true);
            }
            helper.succeed();
        });
    }

    /** Tiers are the person's own: a death keeps them and the next life fills to the suit's own capacity. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void tiersSurviveDeathAndARespawnFillsToTheOwnCapacity(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "suit-tracks-respawn");
        ServerPlayer dead = mock.player();
        dead.setGameMode(GameType.SURVIVAL);
        SuitScenes.setTracks(dead, new SuitTracks(1, 2, 2));
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
                    ServerPlayer alive = dead.connection.getPlayer();
                    expect(
                            helper,
                            SuitScenes.tracks(alive).equals(new SuitTracks(1, 2, 2)),
                            "the tiers are kept on death, they read %s",
                            SuitScenes.tracks(alive));
                    expect(
                            helper,
                            SuitScenes.state(alive).reserve() == 300f,
                            "a new life fills an air tier 2 suit to 300, it reads %s",
                            SuitScenes.state(alive).reserve());
                    alive.setGameMode(GameType.SPECTATOR);
                })
                .thenSucceed();
    }

    /** Tiers are saved with the player and come back on a login. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void tiersAreSavedWithThePlayerAndComeBackOnLogin(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "suit-tracks-login");
        ServerPlayer player = mock.player();
        ServerLevel level = helper.getLevel();
        SuitScenes.setTracks(player, new SuitTracks(3, 1, 2));
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        player.saveWithoutId(output);
        CompoundTag saved = output.buildResult();
        SuitScenes.setTracks(player, SuitTracks.STOCK);
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        expect(
                helper,
                SuitScenes.tracks(player).equals(new SuitTracks(3, 1, 2)),
                "the saved player should bring back tiers 3, 1 and 2, it read %s",
                SuitScenes.tracks(player));
        helper.succeed();
    }

    /** The drain reads the person's own tiers: in the unbuilt depth's (5,5,5), heat tier 3 costs 4 a second, not 5.5. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theDrainReadsTheTiers(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 7600);
        MockPlayer mock = MockPlayers.join(helper, "suit-tracks-drain");
        SuitScenes.whenReady(helper, pocket, () -> {
            ServerPlayer player = mock.player();
            SuitScenes.setTracks(player, new SuitTracks(3, 0, 0));
            SuitScenes.setReserve(player, 180f, true);
            SuitScenes.onFootIn(mock, pocket, () -> {
                SuitScenes.seconds(player, 1);
                SuitState state = SuitScenes.state(player);
                expect(
                        helper,
                        near(state.rate(), 4f)
                                && state.shortBy(RatingAxis.HEAT) == 2
                                && state.shortBy(RatingAxis.PRESSURE) == 5
                                && state.shortBy(RatingAxis.FLOODING) == 0
                                && state.axes()
                                        == (SuitState.bit(RatingAxis.HEAT) | SuitState.bit(RatingAxis.PRESSURE)),
                        "heat tier 3 against (5,5,5) costs 4 a second and is short 2 and 5, it reads %s",
                        state);
                SuitScenes.setTracks(player, new SuitTracks(0, 0, 3));
                SuitScenes.setReserve(player, 360f, true);
                SuitScenes.seconds(player, 1);
                expect(
                        helper,
                        near(SuitScenes.state(player).rate(), 5.5f)
                                && near(SuitScenes.state(player).reserve(), 360f - 5.5f),
                        "an air tier 3 suit holds 360 and pays 5.5 dry, it reads %s",
                        SuitScenes.state(player));
            });
            helper.succeed();
        });
    }

    /** A reserve above the suit's own capacity (a saved 300 on a suit without the air tier) is cut to it, not left at 300 or at 180 by a load. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aReserveAboveTheOwnCapacityClampsToIt(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 7650);
        MockPlayer mock = MockPlayers.join(helper, "suit-tracks-clamp");
        SuitScenes.whenReady(helper, pocket, () -> {
            ServerPlayer player = mock.player();
            SuitScenes.setReserve(player, 300f, true);
            SuitScenes.seconds(player, 1);
            expect(
                    helper,
                    SuitScenes.state(player).reserve() == 180f,
                    "a reserve of 300 at air tier 0 clamps to 180, it reads %s",
                    SuitScenes.state(player).reserve());
            SuitScenes.setTracks(player, new SuitTracks(0, 0, 2));
            SuitScenes.setReserve(player, 300f, true);
            SuitScenes.seconds(player, 1);
            expect(
                    helper,
                    SuitScenes.state(player).reserve() == 300f,
                    "the same 300 at air tier 2 stays whole, it reads %s",
                    SuitScenes.state(player).reserve());
            helper.succeed();
        });
    }

    /**
     * A hall judged against a row of (2,2,0,0), a suit with heat and pressure at tier 2 is short on nothing: the hall still leaks (it is
     * unrated) but the line names no axis and the cost is the base alone, halved.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aSuitThatMeetsAHallsRowLeaksOnNoAxis(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pocket pocket = SuitScenes.unbuilt(level, 7700);
        MockPlayer mock = MockPlayers.join(helper, "suit-tracks-hall");
        Outpost hall = new Outpost(new CharterId(UUID.randomUUID()), 1, level.dimension(), pocket.floor());
        SuitScenes.whenReady(helper, pocket, () -> {
            WorldData.with(level.getServer(), OutpostData.TYPE, SuitScenes.dataWith(hall), () -> {
                ServerPlayer player = mock.player();
                SuitScenes.setTracks(player, new SuitTracks(2, 2, 0));
                SuitScenes.setReserve(player, 100f, true);
                SuitScenes.onFootIn(mock, pocket, () -> {
                    SuitTicker.second(player, (inLevel, judged) -> Optional.of(new Ratings(2, 2, 0, 0)));
                    SuitState state = SuitScenes.state(player);
                    expect(
                            helper,
                            state.mode() == Mode.LEAKING
                                    && state.axes() == 0
                                    && state.shortfall() == 0
                                    && near(state.rate(), 0.25f),
                            "a heat 2 pressure 2 suit in a leaking hall of (2,2,0,0) is short on nothing, it reads %s",
                            state);
                });
            });
            helper.succeed();
        });
    }

    /** Unreadable tracks stop the suit as an unreadable suit does: no drain, no harm, nothing thrown. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void unreadableTracksLeaveTheSuitAlone(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 7750);
        MockPlayer mock = MockPlayers.join(helper, "suit-tracks-unreadable");
        ServerPlayer player = mock.player();
        float[] health = new float[1];
        SuitScenes.sequence(helper, pocket)
                .thenExecute(() -> {
                    health[0] = player.getHealth();
                    SuitScenes.setReserve(player, 0f, true);
                    mock.teleportTo(pocket.level(), pocket.stand(), 0f, 0f);
                    player.setGameMode(GameType.SURVIVAL);
                    UnreadableChecks.makeUnreadable(player, SuitRegistry.TRACKS);
                    UnreadableChecks.assertNoThrow(
                            helper, "suit tracks", java.util.Map.of("second", () -> SuitTicker.second(player)));
                })
                // The real tick runs on the unreadable tracks for two seconds and a breach cadence, over a spent suit.
                .thenIdle(85)
                .thenExecute(() -> {
                    player.setGameMode(GameType.SPECTATOR);
                    expect(
                            helper,
                            player.getAttached(SuitRegistry.TRACKS) instanceof Versioned.Unreadable<SuitTracks>,
                            "the unreadable tracks are kept as they were");
                    expect(
                            helper,
                            SuitScenes.state(player).reserve() == 0f && player.getHealth() == health[0],
                            "unreadable tracks must neither drain nor hurt, health is %s",
                            player.getHealth());
                })
                .thenSucceed();
    }
}
