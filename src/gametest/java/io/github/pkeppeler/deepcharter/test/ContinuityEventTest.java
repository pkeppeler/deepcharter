package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.mojang.serialization.Codec;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.continuity.ContinuityEventPayload;
import io.github.pkeppeler.deepcharter.continuity.ContinuityEvents;
import io.github.pkeppeler.deepcharter.continuity.ContinuityRecord;
import io.github.pkeppeler.deepcharter.continuity.ContinuityRegistry;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1337's Continuity Event: the per-person count (its pinned forms, one per death, kept across a respawn and a save,
 * and an unreadable record that neither throws nor counts), the chat line that replaces vanilla's death message (a wreck's kill, a burn,
 * the {@code showDeathMessages} game rule), the employee number, and what the crew hear: the lamp going out, never vanilla's death cry.
 */
public class ContinuityEventTest {
    private static final String CHAT_KEY = "deepcharter.continuity.chat";
    private static final int FLOOR_Y = 1; // fixed-pos: a row of the test's own structure, relative to it, not a world Y

    /** The saved form of the record, its version and its clamps, by literal values; a future version reads as unreadable. */
    @GameTest
    public void theRecordsSavedFormIsPinned(GameTestHelper helper) {
        Codec<Versioned<ContinuityRecord>> disk = Versioned.codec(ContinuityRecord.VERSION, ContinuityRecord.BODY);
        expect(helper, ContinuityRecord.VERSION == 1, "the first version is 1");
        CompoundTag saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.putInt("events", 7);
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, saved).getOrThrow().equals(Versioned.of(new ContinuityRecord(7))),
                "the saved form should load as seven events");
        expect(
                helper,
                disk.encodeStart(NbtOps.INSTANCE, Versioned.of(new ContinuityRecord(7)))
                        .getOrThrow()
                        .equals(saved),
                "seven events should write as the saved form");
        CompoundTag bare = new CompoundTag();
        bare.putInt("version", 1);
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, bare).getOrThrow().equals(Versioned.of(ContinuityRecord.NONE)),
                "a missing count reads 0");
        CompoundTag negative = new CompoundTag();
        negative.putInt("version", 1);
        negative.putInt("events", -3);
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, negative).getOrThrow().equals(Versioned.of(ContinuityRecord.NONE)),
                "a negative count reads 0");
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, UnreadableChecks.futureData()).getOrThrow()
                        instanceof Versioned.Unreadable<ContinuityRecord>,
                "a version of the future reads as unreadable, never as a failure");
        CompoundTag old = new CompoundTag();
        old.putInt("version", 0);
        old.putInt("events", 2);
        expect(
                helper,
                disk.parse(NbtOps.INSTANCE, old).getOrThrow() instanceof Versioned.Unreadable<ContinuityRecord>,
                "a version this build never wrote reads as unreadable");
        helper.succeed();
    }

    /** The employee number is permanent: these UUIDs always give these numbers, and the payload's wire form is two var ints. */
    @GameTest
    public void theEmployeeNumberAndThePayloadArePinned(GameTestHelper helper) {
        Map<String, Integer> pinned = Map.of(
                "00000000-0000-0000-0000-000000000000", 1000,
                "123e4567-e89b-12d3-a456-426614174000", 4726,
                "f81d4fae-7dec-11d0-a765-00a0c91e6bf6", 4892);
        pinned.forEach((id, number) -> expect(
                helper,
                ContinuityEvents.employeeNumber(UUID.fromString(id)) == number,
                "%s should be employee %s, is %s",
                id,
                number,
                ContinuityEvents.employeeNumber(UUID.fromString(id))));
        ByteBuf wire = Unpooled.buffer();
        ContinuityEventPayload.CODEC.encode(wire, new ContinuityEventPayload(7, 4726));
        expect(
                helper,
                ContinuityEventPayload.CODEC.decode(wire).equals(new ContinuityEventPayload(7, 4726)),
                "round trip");
        ByteBuf bad = Unpooled.buffer();
        ContinuityEventPayload.CODEC.encode(bad, new ContinuityEventPayload(1, 1000));
        bad.setByte(1, 3); // employee 3: no employee number has fewer than four digits
        boolean thrown;
        try {
            ContinuityEventPayload.CODEC.decode(bad);
            thrown = false;
        } catch (IllegalArgumentException refused) {
            thrown = true;
        }
        expect(helper, thrown, "a wire employee number of three must be refused, not passed on");
        helper.succeed();
    }

    /** Each death counts once; the count is kept across the respawn and the next death is the next number; the dying client is told it. */
    @GameTest
    public void eachDeathCountsOnceAndTheCountSurvivesTheRespawn(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "continuity-count");
        mock.announceChannel(ContinuityEventPayload.TYPE);
        ServerPlayer first = mock.player();
        first.setGameMode(GameType.SURVIVAL);
        ServerPlayer[] second = new ServerPlayer[1];
        helper.startSequence()
                .thenExecute(() -> {
                    first.kill(helper.getLevel());
                    expect(helper, events(first) == 1, "one death counts one, it reads %s", events(first));
                    first.connection.handleClientCommand(
                            new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    second[0] = first.connection.getPlayer();
                    expect(helper, second[0] != first, "the person should have come back");
                    expect(
                            helper,
                            events(second[0]) == 1,
                            "the count is kept across the respawn, it reads %s",
                            events(second[0]));
                    second[0].setGameMode(GameType.SURVIVAL);
                    // A respawned person takes no damage until their client has loaded.
                    mock.markLoaded();
                    second[0].kill(helper.getLevel());
                    expect(helper, second[0].isDeadOrDying(), "the second kill should have killed the person");
                    expect(
                            helper,
                            events(second[0]) == 2,
                            "the second death is the second event, it reads %s",
                            events(second[0]));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    int employee = ContinuityEvents.employeeNumber(first.getUUID());
                    expect(
                            helper,
                            mock.payloadsOf(ContinuityEventPayload.class)
                                    .equals(List.of(
                                            new ContinuityEventPayload(1, employee),
                                            new ContinuityEventPayload(2, employee))),
                            "the dying client is told numbers 1 and 2 of employee %s, it was told %s",
                            employee,
                            mock.payloadsOf(ContinuityEventPayload.class));
                    second[0].setGameMode(GameType.SPECTATOR);
                })
                .thenSucceed();
    }

    /** The count is saved with the person and comes back on a login. */
    @GameTest
    public void theCountIsSavedWithThePerson(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "continuity-saved");
        ServerPlayer player = mock.player();
        ServerLevel level = helper.getLevel();
        player.setAttached(ContinuityRegistry.RECORD, Versioned.of(new ContinuityRecord(12)));
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        player.saveWithoutId(output);
        CompoundTag saved = output.buildResult();
        player.setAttached(ContinuityRegistry.RECORD, Versioned.of(ContinuityRecord.NONE));
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        expect(
                helper,
                events(player) == 12,
                "the saved person should come back with 12 events, read %s",
                events(player));
        helper.succeed();
    }

    /** An unreadable record neither throws nor counts: the death goes ahead, the record is kept as it was, and the line's number is unknown. */
    @GameTest
    public void anUnreadableRecordNeitherThrowsNorCounts(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "continuity-unreadable");
        ServerPlayer player = mock.player();
        player.setGameMode(GameType.SURVIVAL);
        UnreadableChecks.makeUnreadable(player, ContinuityRegistry.RECORD);
        UnreadableChecks.assertNoThrow(
                helper,
                "continuity",
                Map.of(
                        "died", () -> ContinuityEvents.died(player),
                        "message",
                                () -> ContinuityEvents.message(
                                        player,
                                        helper.getLevel().damageSources().lava())));
        TranslatableContents line = (TranslatableContents) ContinuityEvents.message(
                        player, helper.getLevel().damageSources().lava())
                .getContents();
        expect(
                helper,
                key(line.getArgs()[1]).equals(Optional.of("deepcharter.continuity.unknown_number")),
                "an unreadable record's number reads as unknown, the line was %s",
                line);
        player.kill(helper.getLevel());
        expect(helper, player.isDeadOrDying(), "the person still dies");
        expect(
                helper,
                player.getAttached(ContinuityRegistry.RECORD) instanceof Versioned.Unreadable<ContinuityRecord>,
                "the unreadable record is kept as it was");
        player.setGameMode(GameType.SPECTATOR);
        helper.succeed();
    }

    /**
     * A wreck's kill is filed as a HULL LOSS at the person's depth, by the real path (the pod's hull to 0, the crew's fate); the crewmate
     * beside the pod hears the lamp go out and never vanilla's death cry, and the person themselves hears neither.
     */
    @GameTest
    public void aWrecksKillIsAHullLossAndTheCrewHearTheLampGoOut(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.STONE);
            }
        }
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(2.5, FLOOR_Y + 1, 2.5));
        MockPlayer pilot = MockPlayers.join(helper, "continuity-pilot");
        MockPlayer crewmate = MockPlayers.join(helper, "continuity-crewmate");
        pilot.player().setGameMode(GameType.SURVIVAL);
        Vec3[] died = new Vec3[1];
        helper.startSequence()
                .thenExecute(() -> {
                    if (!pilot.player().startRiding(pod, true, false)) {
                        throw failure(helper, "the pilot could not board the pod");
                    }
                    pod.damageHull(pod.maxHull());
                    expect(helper, pilot.player().isDeadOrDying(), "the wreck should have killed its pilot");
                    died[0] = new Vec3(
                            pilot.player().getX(),
                            pilot.player().getEyeY(),
                            pilot.player().getZ());
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    TranslatableContents line = lineFor(helper, crewmate, "continuity-pilot");
                    expect(
                            helper,
                            key(line.getArgs()[2]).equals(Optional.of("deepcharter.continuity.cause.hull_loss")),
                            "a wreck's kill is a hull loss, the line was %s",
                            line);
                    expect(
                            helper,
                            ((Component) line.getArgs()[1]).getString().equals("1"),
                            "it is the pilot's first event, the line was %s",
                            line);
                    expect(
                            helper,
                            key(line.getArgs()[3]).equals(Optional.of("deepcharter.breach.altimeter")),
                            "the line ends in the depth as the altimeter reads it, the line was %s",
                            line);
                    // Tests run side by side, and another test's death is heard too: only what was played where the
                    // pilot died counts.
                    List<Identifier> crewHeard = heardAt(crewmate, died[0]);
                    expect(
                            helper,
                            crewHeard.contains(
                                    DeepSound.CONTINUITY_LAMP_OUT.event().location()),
                            "the crewmate should hear the lamp go out, heard %s",
                            crewHeard);
                    expect(
                            helper,
                            !crewHeard.contains(SoundEvents.PLAYER_DEATH.location()),
                            "no vanilla death cry, heard %s",
                            crewHeard);
                    expect(
                            helper,
                            !heardAt(pilot, died[0])
                                    .contains(DeepSound.CONTINUITY_LAMP_OUT
                                            .event()
                                            .location()),
                            "the person whose lamp it was hears nothing of it, heard %s",
                            heardAt(pilot, died[0]));
                    pod.discard();
                    pilot.leave();
                    crewmate.leave();
                })
                .thenSucceed();
    }

    /** Another cause has its own word: a death in lava is filed as BURNED. */
    @GameTest
    public void aDeathInLavaIsFiledAsBurned(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "continuity-burned");
        ServerPlayer player = mock.player();
        player.setGameMode(GameType.SURVIVAL);
        helper.startSequence()
                .thenExecute(() -> player.hurtServer(
                        helper.getLevel(), helper.getLevel().damageSources().lava(), 1000f))
                .thenIdle(2)
                .thenExecute(() -> {
                    TranslatableContents line = lineFor(helper, mock, "continuity-burned");
                    expect(
                            helper,
                            key(line.getArgs()[2]).equals(Optional.of("deepcharter.continuity.cause.burned")),
                            "a death in lava is BURNED, the line was %s",
                            line);
                    expect(
                            helper,
                            ((Component) line.getArgs()[0]).getString().equals("continuity-burned"),
                            "the line names the person, it was %s",
                            line);
                    player.setGameMode(GameType.SPECTATOR);
                })
                .thenSucceed();
    }

    /** With {@code showDeathMessages} off there is no line at all, and the death still counts. */
    @GameTest
    public void noLineWhenDeathMessagesAreOff(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "continuity-quiet");
        ServerPlayer player = mock.player();
        player.setGameMode(GameType.SURVIVAL);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence()
                .thenExecute(() -> {
                    boolean was = server.getGameRules().get(GameRules.SHOW_DEATH_MESSAGES);
                    server.getGameRules().set(GameRules.SHOW_DEATH_MESSAGES, false, server);
                    try {
                        player.kill(helper.getLevel());
                    } finally {
                        server.getGameRules().set(GameRules.SHOW_DEATH_MESSAGES, was, server);
                    }
                    expect(helper, events(player) == 1, "the death still counts, it reads %s", events(player));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    expect(
                            helper,
                            linesFor(mock, "continuity-quiet").isEmpty(),
                            "no line with death messages off, the chat was %s",
                            mock.chatMessages());
                    player.setGameMode(GameType.SPECTATOR);
                })
                .thenSucceed();
    }

    /** The sounds {@code listener} was sent that were played within a block of {@code at}. */
    private static List<Identifier> heardAt(MockPlayer listener, Vec3 at) {
        return listener.soundsHeardAtPlaces().stream()
                .filter(sound -> sound.at().distanceTo(at) < 1.0)
                .map(MockPlayer.PlacedSound::id)
                .toList();
    }

    private static int events(ServerPlayer player) {
        return Versioned.readable(player, ContinuityRegistry.RECORD)
                .map(ContinuityRecord::events)
                .orElse(-1);
    }

    private static Optional<String> key(Object component) {
        return component instanceof Component c && c.getContents() instanceof TranslatableContents t
                ? Optional.of(t.getKey())
                : Optional.empty();
    }

    /** The Continuity Event lines about {@code name} in what {@code listener} was sent. */
    private static List<TranslatableContents> linesFor(MockPlayer listener, String name) {
        return listener.chatMessages().stream()
                .filter(message -> message.getContents() instanceof TranslatableContents t
                        && t.getKey().equals(CHAT_KEY)
                        && t.getArgs()[0] instanceof Component person
                        && person.getString().equals(name))
                .map(message -> (TranslatableContents) message.getContents())
                .toList();
    }

    private static TranslatableContents lineFor(GameTestHelper helper, MockPlayer listener, String name) {
        List<TranslatableContents> lines = linesFor(listener, name);
        if (lines.size() != 1) {
            throw failure(
                    helper,
                    "%s should have been sent one line about %s, got %s",
                    listener.player().getName().getString(),
                    name,
                    listener.chatMessages());
        }
        return lines.getFirst();
    }
}
