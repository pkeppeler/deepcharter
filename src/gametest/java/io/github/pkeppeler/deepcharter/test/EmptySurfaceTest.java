package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.CampaignBands.Band;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for the empty surface (#915, ADR 0036, docs/CREATURES.md#surface): no vanilla monster spawns on the surface or in any
 * band of the campaign world, and the Retained keep their own spawns in layers 1 and 2. They run in {@code deepcharter:campaign_sample}.
 * Columns 25000 to 25999 are this class's. The spawners no biome list reaches (patrols, phantoms, wardens, the trader) are pinned in
 * {@code SurfaceTerrainTest}.
 */
public class EmptySurfaceTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 600;
    private static final int X = 25000;
    private static final int Z = 25000;
    /**
     * Attempts of the game's spawner at a spot. One attempt tries up to a dozen cells next to the spot, so a vanilla monster that the
     * biome allowed would be placed in a dark room within the first few dozen.
     */
    private static final int TRIES = 1500;

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    private static String id(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    /** The middle of a zone of a layer: a Y in that zone's biome. */
    private static int zoneMiddle(Band layer, int zone) {
        Zones.Span span = Zones.span(layer.minY(), layer.height(), zone);
        return (span.low() + span.high()) / 2;
    }

    /**
     * The surface lists no creature at all. A layer lists the Retained as its one monster, in each of its three zones, and nothing else
     * in any category. The unbuilt depth lists nothing.
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void noBandListsAVanillaMonsterAndTheLayersKeepTheRetained(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        BlockPos column = new BlockPos(X, 0, Z);
        level.getChunkAt(column);
        Map<String, Integer> spots = new LinkedHashMap<>();
        spots.put("surface", 64);
        spots.put("surface floor", CampaignBands.SURFACE.minY());
        spots.put("unbuilt depth", -1000);
        for (int number = 1; number <= CampaignBands.LAYERS.size(); number++) {
            for (int zone = 0; zone < Zones.COUNT; zone++) {
                spots.put("layer %d zone %d".formatted(number, zone), zoneMiddle(CampaignBands.layer(number), zone));
            }
        }
        for (Map.Entry<String, Integer> spot : spots.entrySet()) {
            boolean layer = spot.getKey().startsWith("layer");
            for (MobCategory category : MobCategory.values()) {
                var mobs = generator
                        .getMobsAt(level, level.structureManager(), category, column.atY(spot.getValue()))
                        .unwrap();
                boolean retainedOnly = layer
                        && category == MobCategory.MONSTER
                        && !mobs.isEmpty()
                        && mobs.stream().allMatch(entry -> entry.value().type() == CreatureRegistry.LAMPLESS_FIGURE);
                if (!mobs.isEmpty() && !retainedOnly) {
                    throw failure(helper, "%s lists %s creatures: %s", spot.getKey(), category, mobs);
                }
                if (layer && category == MobCategory.MONSTER && !retainedOnly) {
                    throw failure(helper, "%s does not list the Retained as a monster", spot.getKey());
                }
            }
        }
        helper.succeed();
    }

    /**
     * The game's spawner, tried {@link #TRIES} times in a dark room on the surface and in one in each layer, places no vanilla monster, and
     * on the surface places nothing at all. The dark of a layer may place a Retained, whose own rules decide.
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void naturalSpawningInTheDarkPlacesNoVanillaMonster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Map<String, BlockPos> rooms = new LinkedHashMap<>();
        rooms.put("surface", new BlockPos(X, CampaignBands.SURFACE.minY() + 250, Z));
        rooms.put("layer 1", new BlockPos(X + 32, zoneMiddle(CampaignBands.LAYER_1, 1), Z));
        rooms.put("layer 2", new BlockPos(X + 64, zoneMiddle(CampaignBands.LAYER_2, 1), Z));
        List<BlockPos> feet = new ArrayList<>(rooms.values());
        int[] ready = {0};
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            if (ready[0] < feet.size()) {
                return;
            }
            // The light engine runs behind the carve: the light of the ore the room replaced is read until it catches
            // up.
            boolean dark = feet.stream().allMatch(room -> level.getBrightness(LightLayer.BLOCK, room) == 0);
            deadline.await(helper, level, dark, () -> "the light of the old rock did not leave the rooms");
            if (!dark) {
                return;
            }
            for (Map.Entry<String, BlockPos> room : rooms.entrySet()) {
                List<String> placed = new ArrayList<>();
                for (int i = 0; i < TRIES; i++) {
                    for (MobCategory category : MobCategory.values()) {
                        NaturalSpawner.spawnCategoryForPosition(
                                category,
                                level,
                                level.getChunkAt(room.getValue()),
                                room.getValue(),
                                (type, spawnLevel, pos, chunk) -> true,
                                (mob, chunk) -> {
                                    placed.add(id(mob.getType()));
                                    mob.discard();
                                });
                    }
                }
                boolean surface = room.getKey().equals("surface");
                for (String type : placed) {
                    if (surface || !type.equals(id(CreatureRegistry.LAMPLESS_FIGURE))) {
                        throw failure(helper, "the spawner placed a %s in the dark of the %s", type, room.getKey());
                    }
                }
            }
            helper.succeed();
        });
        FarChunks.awaitEntityTicking(helper, level, feet, index -> {
            BlockPos at = feet.get(index);
            RoomCarver.carve(level, at.offset(-4, -1, -4), at.offset(4, 6, 4), Blocks.STONE.defaultBlockState());
            RoomCarver.carve(level, at.offset(-2, 0, -2), at.offset(2, 4, 2), Blocks.AIR.defaultBlockState());
            ready[0]++;
        });
    }
}
