package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.creature.LamplessFigures;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.CampaignBands.Band;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the lampless figure's natural spawn in the campaign world (#799), in {@code deepcharter:campaign_sample}. A figure
 * keys on the band at its Y: it appears in a layer's band when a player is in that band, with a cap for each band, and never in the surface
 * band, whatever Y the game's spawner picks under a player on the surface. Columns 24000 to 24999 are this class's.
 */
public class BandFiguresTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 600;
    private static final int Z = 24000;
    private static final CreatureTuning TUNING = CreatureTuning.DEFAULT;
    private static final Band ONE = CampaignBands.LAYER_1;
    private static final Band TWO = CampaignBands.LAYER_2;
    /** Tries at a place: a layer's roll passes one in a hundred at the least, so 4,000 tries miss with a chance below 1e-17. */
    private static final int TRIES = 4000;

    private static BlockState light(int level) {
        return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
    }

    /**
     * A dark room of air, 5 high, in a mass of plain stone with a stone floor, so that no glowing ore in the walls lights it: the cell at
     * {@code feet} is where a figure would stand.
     */
    private static void darkRoom(ServerLevel level, BlockPos feet) {
        RoomCarver.carve(level, feet.offset(-4, -1, -4), feet.offset(4, 6, 4), Blocks.STONE.defaultBlockState());
        RoomCarver.carve(level, feet.offset(-2, 0, -2), feet.offset(2, 4, 2), Blocks.AIR.defaultBlockState());
    }

    /** How many of {@link #TRIES} attempts of the game's spawner a figure with its feet at {@code feet} would pass. */
    private static int passes(ServerLevel level, BlockPos feet) {
        LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
        figure.setPos(Vec3.atBottomCenterOf(feet));
        int passed = 0;
        for (int i = 0; i < TRIES; i++) {
            passed += figure.checkSpawnRules(level, EntitySpawnReason.NATURAL) ? 1 : 0;
        }
        return passed;
    }

    /** Whether a player other than {@code mine} is in {@code band}: its figures would then be allowed, and a test that expects none waits. */
    private static boolean othersIn(ServerLevel level, Band band, ServerPlayer mine) {
        for (ServerPlayer player : level.players()) {
            if (player != mine && player.getBlockY() >= band.minY() && player.getBlockY() <= band.maxY()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A figure appears in the dark of a layer's band when a player is in that band, and not in another band's dark, whatever the player's
     * band: in the dark room of layer 2 when the only player is in layer 1, in the surface band's room at all, and under a player on the
     * surface.
     */
    @GameTest(environment = CampaignEnvironments.B, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aFigureAppearsOnlyInTheBandOfAPlayerWhoIsInIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 24000;
        BlockPos one = new BlockPos(x, ONE.minY() + 80, Z);
        BlockPos two = new BlockPos(x + 32, TWO.minY() + 120, Z);
        BlockPos surface = new BlockPos(x + 64, CampaignBands.SURFACE.minY() + 250, Z);
        MockPlayer mock = MockPlayers.join(helper, "band-figures");
        mock.player().setNoGravity(true);
        mock.player().setPermanentlyInvulnerable(true);
        mock.teleportTo(level, Vec3.atBottomCenterOf(one), 0f, 0f);
        int[] ready = {0};
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            if (ready[0] < 3) {
                return;
            }
            // The light engine runs behind the carve: the light of the ore the room replaced is read until it
            // catches up.
            boolean dark = Stream.of(one, two, surface)
                    .allMatch(room -> level.getBrightness(LightLayer.BLOCK, room) < TUNING.litBlockLight());
            deadline.await(helper, level, dark, () -> "the light of the old rock did not leave the rooms");
            if (!dark) {
                return;
            }
            LamplessFigures.clearRespawnDelays();
            // The player is in layer 1: its dark may take a figure, the dark of layer 2 and of the surface may not.
            mock.teleportTo(level, Vec3.atBottomCenterOf(one), 0f, 0f);
            expect(helper, passes(level, one) > 0, "no figure appears in layer 1's dark with a player in layer 1");
            if (!othersIn(level, TWO, mock.player())) {
                expect(
                        helper,
                        passes(level, two) == 0,
                        "a figure appears in layer 2's dark with the only player in layer 1");
            }
            expect(helper, passes(level, surface) == 0, "a figure appears in the surface band's dark");
            // The player is on the surface: nothing in the layers below it, and nothing on the surface.
            mock.teleportTo(level, Vec3.atBottomCenterOf(surface), 0f, 0f);
            if (!othersIn(level, ONE, mock.player())) {
                expect(
                        helper,
                        passes(level, one) == 0,
                        "a figure appears in layer 1's dark under a player on the surface");
            }
            if (!othersIn(level, TWO, mock.player())) {
                expect(
                        helper,
                        passes(level, two) == 0,
                        "a figure appears in layer 2's dark under a player on the surface");
            }
            expect(helper, passes(level, surface) == 0, "a figure appears in the dark beside a player on the surface");
            // The player is in layer 2.
            mock.teleportTo(level, Vec3.atBottomCenterOf(two), 0f, 0f);
            expect(
                    helper,
                    passes(level, two) > 0,
                    "no figure appears in layer 2's dark with a player in layer 2 (may appear %s, block light %d, ticking %s)",
                    LamplessFigures.canAppearAt(level, two),
                    level.getBrightness(LightLayer.BLOCK, two),
                    level.isPositionEntityTicking(two));
            helper.succeed();
        });
        List<BlockPos> rooms = List.of(one, two, surface);
        FarChunks.awaitEntityTicking(helper, level, rooms, index -> {
            darkRoom(level, rooms.get(index));
            ready[0]++;
        });
    }

    /** Each band holds its own cap of natural figures, and the rail's figure of Prospector's Run is held back by neither. */
    @GameTest(environment = CampaignEnvironments.B, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void eachBandHasItsOwnCapOfFigures(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 24128;
        BlockPos one = new BlockPos(x, ONE.minY() + 80, Z);
        BlockPos two = new BlockPos(x + 64, TWO.minY() + 120, Z);
        int[] ready = {0};
        FarChunks.awaitEntityTicking(helper, level, List.of(one, two), index -> {
            darkRoom(level, index == 0 ? one : two);
            ready[0]++;
        });
        TestTicks.each(helper, () -> {
            if (ready[0] < 2) {
                return;
            }
            LamplessFigures.clearRespawnDelays();
            // Made and counted in one tick: a figure with no player near it despawns on the next.
            List<LamplessFigure> placed = new ArrayList<>();
            for (int i = 0; i < TUNING.maxPerBand(); i++) {
                // Counted and discarded in this callback, before the figure's first tick (#1237).
                LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
                figure.markNatural();
                figure.setNoAi(true);
                figure.setPos(Vec3.atBottomCenterOf(one.east(i % 3)));
                level.addFreshEntity(figure);
                placed.add(figure);
            }
            boolean oneFull = !LamplessFigures.bandHasRoom(level, true, one.getY());
            boolean twoOpen = LamplessFigures.bandHasRoom(level, true, two.getY());
            boolean railOpen = LamplessFigures.bandHasRoom(level, false, one.getY());
            placed.forEach(LamplessFigure::discard);
            expect(helper, oneFull, "layer 1's band holds %d figures and takes another", TUNING.maxPerBand());
            expect(helper, twoOpen, "the figures of layer 1 use up the room of layer 2");
            expect(helper, railOpen, "the figures of layer 1 hold back the rail's figure");
            helper.succeed();
        });
    }

    /**
     * The server keeps sky light 15 down an open shaft from the surface, and a figure at its bottom, in layer 1's dark, still appears: it reads
     * block light only. A light of 15 at the bottom stops it.
     */
    @GameTest(environment = CampaignEnvironments.B, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aFigureAppearsAtTheBottomOfAnOpenShaftByBlockLight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 24256;
        BlockPos bottom = new BlockPos(x, ONE.minY() + 80, Z);
        MockPlayer mock = MockPlayers.join(helper, "band-shaft");
        mock.player().setNoGravity(true);
        mock.player().setPermanentlyInvulnerable(true);
        mock.teleportTo(level, Vec3.atBottomCenterOf(bottom), 0f, 0f);
        int[] phase = {0};
        FarChunks.Deadline deadline = FarChunks.deadline();
        FarChunks.awaitEntityTicking(helper, level, bottom, () -> {
            darkRoom(level, bottom);
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, Z) + 2;
            RoomCarver.carve(level, bottom, new BlockPos(x, top, Z), Blocks.AIR.defaultBlockState());
            phase[0] = 1;
        });
        TestTicks.each(helper, () -> {
            if (phase[0] == 0) {
                return;
            }
            int sky = level.getBrightness(LightLayer.SKY, bottom);
            int block = level.getBrightness(LightLayer.BLOCK, bottom);
            if (phase[0] == 1) {
                deadline.await(helper, level, sky == 15, () -> "the sky light at the shaft's bottom is " + sky);
                if (sky != 15) {
                    return;
                }
                LamplessFigures.clearRespawnDelays();
                expect(
                        helper,
                        LamplessFigures.canAppearAt(level, bottom),
                        "no figure may appear at the bottom of an open shaft (sky %d, block %d)",
                        sky,
                        block);
                expect(
                        helper,
                        passes(level, bottom) > 0,
                        "the game's spawner never places a figure at the shaft's bottom");
                RoomCarver.carve(level, bottom.above(), bottom.above(), light(15));
                phase[0] = 2;
            } else {
                deadline.await(
                        helper,
                        level,
                        block >= TUNING.litBlockLight(),
                        () -> "the light of 15 did not reach the shaft's bottom: block " + block);
                if (block < TUNING.litBlockLight()) {
                    return;
                }
                expect(
                        helper,
                        !LamplessFigures.canAppearAt(level, bottom),
                        "a figure may appear at the shaft's bottom beside a light of 15 (block %d)",
                        block);
                helper.succeed();
            }
        });
    }
}
