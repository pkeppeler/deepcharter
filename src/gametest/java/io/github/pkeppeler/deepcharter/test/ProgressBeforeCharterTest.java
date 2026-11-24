package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.Directives;
import io.github.pkeppeler.deepcharter.handbook.FindResult;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.HandbookSyncPayload;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.handbook.NotesData;
import io.github.pkeppeler.deepcharter.handbook.PersonalHandbookData;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #843: what a player did before they had a charter is theirs, is kept, and carries into a charter they found
 * (directives and Notes) or into one they join (Notes only: a joiner takes the charter's directive progress).
 */
public class ProgressBeforeCharterTest {
    private static final Identifier CUSTOM_DIRECTIVE = directive("sample/custom");
    private static final Identifier VANILLA_DIRECTIVE = directive("sample/sleep");
    private static final Identifier FIRST = Notes.id(1);
    private static final Identifier SECOND = Notes.id(2);

    private static Identifier directive(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/" + path);
    }

    private static void expectDone(GameTestHelper helper, Optional<CharterRefusal> refusal, String what) {
        if (refusal.isPresent()) {
            throw failure(helper, "%s should succeed, was refused: %s", what, refusal.get());
        }
    }

    private static CharterId found(GameTestHelper helper, MinecraftServer server, ServerPlayer director) {
        expectDone(
                helper,
                Charters.found(
                        server,
                        director.getUUID(),
                        "Carry " + UUID.randomUUID().toString().substring(0, 8)),
                "founding");
        Charter charter = Charters.charterOfOrThrow(server, director.getUUID()).orElseThrow();
        return charter.id();
    }

    private static void join(
            GameTestHelper helper, MinecraftServer server, CharterId id, ServerPlayer director, ServerPlayer member) {
        expectDone(helper, Charters.apply(server, member.getUUID(), id), "applying");
        expectDone(helper, Charters.approve(server, director.getUUID(), member.getUUID()), "approving");
    }

    private static void expectComplete(
            GameTestHelper helper,
            MinecraftServer server,
            ServerPlayer player,
            Identifier directive,
            boolean expected) {
        if (HandbookProgress.completedFor(server, player.getUUID()).contains(directive) != expected) {
            throw failure(
                    helper,
                    "%s should %sbe complete for %s",
                    directive,
                    expected ? "" : "not ",
                    player.getGameProfile().name());
        }
    }

    private static void expectAdvancement(
            GameTestHelper helper,
            MinecraftServer server,
            ServerPlayer player,
            Identifier directive,
            boolean expected) {
        AdvancementHolder advancement = server.getAdvancements().get(directive);
        if (advancement == null) {
            throw failure(helper, "directive %s has no advancement", directive);
        }
        if (player.getAdvancements().getOrStartProgress(advancement).isDone() != expected) {
            throw failure(
                    helper,
                    "the advancement %s should %sbe done for %s",
                    directive,
                    expected ? "" : "not ",
                    player.getGameProfile().name());
        }
    }

    private static void expectNotes(
            GameTestHelper helper, MinecraftServer server, ServerPlayer player, List<Identifier> expected) {
        List<Identifier> actual = Notes.foundFor(server, player.getUUID());
        if (!actual.equals(expected)) {
            throw failure(
                    helper,
                    "%s should have the notes %s, has %s",
                    player.getGameProfile().name(),
                    expected,
                    actual);
        }
    }

    @GameTest
    public void aPlayerOnNoCharterMakesDirectiveProgressOfTheirOwn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer loner = MockPlayers.join(helper, "Loner").player();
        ServerPlayer other = MockPlayers.join(helper, "Other").player();

        Directives.fire(loner, CUSTOM_DIRECTIVE);

        expectComplete(helper, server, loner, CUSTOM_DIRECTIVE, true);
        expectComplete(helper, server, other, CUSTOM_DIRECTIVE, false);
        expectAdvancement(helper, server, loner, CUSTOM_DIRECTIVE, true);
        helper.succeed();
    }

    @GameTest
    public void aNoteFoundOnNoCharterIsRememberedAndTheLineSaysSo(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer loner = MockPlayers.join(helper, "Loner").player();

        FindResult result = Notes.find(loner, FIRST);

        if (result != FindResult.NO_CHARTER) {
            throw failure(helper, "a Note found on no charter reports NO_CHARTER, was %s", result);
        }
        expectNotes(helper, server, loner, List.of(FIRST));
        helper.succeed();
    }

    @GameTest
    public void foundingACharterCarriesTheFoundersProgressAndNotes(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer founder = MockPlayers.join(helper, "Founder").player();
        Directives.fire(founder, CUSTOM_DIRECTIVE);
        // A vanilla criterion met before the poll has run still carries.
        CriteriaTriggers.SLEPT_IN_BED.trigger(founder);
        Notes.find(founder, FIRST);

        CharterId id = found(helper, server, founder);

        if (!HandbookProgressData.get(server).completed(id).containsAll(Set.of(CUSTOM_DIRECTIVE, VANILLA_DIRECTIVE))) {
            throw failure(helper, "the charter should hold the founder's directives");
        }
        if (!NotesData.get(server).found(id).equals(Set.of(FIRST))) {
            throw failure(helper, "the charter should hold the founder's Notes");
        }
        expectComplete(helper, server, founder, CUSTOM_DIRECTIVE, true);
        expectAdvancement(helper, server, founder, CUSTOM_DIRECTIVE, true);
        expectAdvancement(helper, server, founder, VANILLA_DIRECTIVE, true);
        expectNotes(helper, server, founder, List.of(FIRST));
        helper.succeed();
    }

    @GameTest
    public void aJoinerTakesTheCharterProgressAndTheirNotesJoinTheCharters(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer director = MockPlayers.join(helper, "Director").player();
        ServerPlayer joiner = MockPlayers.join(helper, "Joiner").player();
        CharterId id = found(helper, server, director);
        Notes.find(director, SECOND);
        Directives.fire(joiner, CUSTOM_DIRECTIVE);
        Notes.find(joiner, FIRST);

        join(helper, server, id, director, joiner);

        // The joiner's own progress completes none of the charter's steps, and the charter's advancements follow.
        if (HandbookProgressData.get(server).completed(id).contains(CUSTOM_DIRECTIVE)) {
            throw failure(helper, "a joiner's own progress must not complete the charter's step");
        }
        expectComplete(helper, server, joiner, CUSTOM_DIRECTIVE, false);
        expectComplete(helper, server, director, CUSTOM_DIRECTIVE, false);
        expectAdvancement(helper, server, joiner, CUSTOM_DIRECTIVE, false);
        // Notes merge into the charter's, unread for the other members.
        expectNotes(helper, server, director, List.of(FIRST, SECOND));
        expectNotes(helper, server, joiner, List.of(FIRST, SECOND));
        if (ReadMarks.isRead(director, FIRST)) {
            throw failure(helper, "a Note the joiner brought is unread for the director");
        }
        helper.succeed();
    }

    @GameTest
    public void aLeaverFallsBackToTheirOwnProgress(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer director = MockPlayers.join(helper, "Director").player();
        ServerPlayer member = MockPlayers.join(helper, "Member").player();
        CharterId id = found(helper, server, director);
        Directives.fire(member, CUSTOM_DIRECTIVE);
        join(helper, server, id, director, member);
        Directives.fire(director, VANILLA_DIRECTIVE);

        expectDone(helper, Charters.leave(server, member.getUUID()), "leaving");

        expectComplete(helper, server, member, CUSTOM_DIRECTIVE, true);
        expectComplete(helper, server, member, VANILLA_DIRECTIVE, false);
        expectAdvancement(helper, server, member, CUSTOM_DIRECTIVE, true);
        helper.succeed();
    }

    @GameTest
    public void unreadablePersonalProgressNeverThrowsFromAFireAPollAFindOrAFounding(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockPlayers.join(helper, "Reader").player();
        Map<String, Runnable> paths = new LinkedHashMap<>();
        paths.put("fire", () -> Directives.fire(player, CUSTOM_DIRECTIVE));
        paths.put("poll", () -> HandbookProgress.sweep(player));
        paths.put("sync", () -> HandbookSyncPayload.send(server, player));
        paths.put("read", () -> HandbookProgress.completedFor(server, player.getUUID()));
        paths.put("find", () -> Notes.find(player, FIRST));
        paths.put("notes", () -> Notes.foundFor(server, player.getUUID()));
        // The carry runs on founding; the refusal on the second run (already on a charter) is not the point here.
        paths.put("founding", () -> Charters.found(server, player.getUUID(), "Unreadable " + UUID.randomUUID()));
        UnreadableChecks.assertSavedDataNoThrow(
                helper, "personal handbook progress", server, PersonalHandbookData.TYPE, paths);
        helper.succeed();
    }

    @GameTest
    public void personalProgressIsSavedWithItsVersion(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        PersonalHandbookData data = new PersonalHandbookData();
        data.completeDirective(player, CUSTOM_DIRECTIVE);
        data.addNote(player, FIRST);

        Tag saved =
                PersonalHandbookData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        if (!(saved instanceof CompoundTag compound)
                || compound.getIntOr("version", -1) != PersonalHandbookData.VERSION) {
            throw failure(helper, "the saved form carries version %s, got %s", PersonalHandbookData.VERSION, saved);
        }
        PersonalHandbookData loaded =
                PersonalHandbookData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        if (!loaded.directives(player).equals(Set.of(CUSTOM_DIRECTIVE))
                || !loaded.notes(player).equals(Set.of(FIRST))) {
            throw failure(helper, "the saved progress should load as it was saved");
        }
        helper.succeed();
    }

    @GameTest
    public void revivingADormantCharterIsAJoin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer director = MockPlayers.join(helper, "Director").player();
        CharterId id = found(helper, server, director);
        Directives.fire(director, CUSTOM_DIRECTIVE);
        Notes.find(director, SECOND);
        expectDone(helper, Charters.leave(server, director.getUUID()), "the last person leaving");
        // On no charter again, the former Director makes progress of their own.
        CriteriaTriggers.SLEPT_IN_BED.trigger(director);
        HandbookProgress.sweep(director);
        Notes.find(director, FIRST);

        expectDone(helper, Charters.revive(server, director.getUUID(), id), "reviving");

        Set<Identifier> charterDirectives = HandbookProgressData.get(server).completed(id);
        if (!charterDirectives.equals(Set.of(CUSTOM_DIRECTIVE))) {
            throw failure(
                    helper,
                    "the charter keeps its own directives and takes none of the reviver's, has %s",
                    charterDirectives);
        }
        expectComplete(helper, server, director, CUSTOM_DIRECTIVE, true);
        expectComplete(helper, server, director, VANILLA_DIRECTIVE, false);
        expectNotes(helper, server, director, List.of(FIRST, SECOND));
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void usingANoteBlockOnNoCharterSaysTheNoteIsFiledOnFoundingOrJoining(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer loner = MockPlayers.join(helper, "Loner");
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(pos, NoteBlock.stateOf(1), 3);

        helper.getLevel()
                .getBlockState(pos)
                .useWithoutItem(
                        helper.getLevel(),
                        loner.player(),
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));

        expectNotes(helper, server, loner.player(), List.of(FIRST));
        helper.succeedWhen(() -> {
            boolean said = loner.actionBarMessages().stream()
                    .anyMatch(line -> line.getContents() instanceof TranslatableContents contents
                            && contents.getKey().equals("deepcharter.handbook.note.use.no_charter"));
            if (!said) {
                throw failure(helper, "the no-charter line should show, got %s", loner.actionBarMessages());
            }
        });
    }
}
