package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.CampaignBands.Band;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.Vec3;

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
    /** More than the 24 blocks the spawner keeps from a player, less than the 128 at which a monster would despawn at once. */
    private static final int PLAYER_DISTANCE = 40;

    private static String id(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    /** The layers whose zones list the Retained: the Claim and the Old Workings (CREATURES section 5). */
    private static final int RETAINED_LAYERS = 2;

    /**
     * Loads the chunk of the column the test reads the mob lists at, in the test's own level, before {@code body} runs.
     */
    private static void whenColumnLoaded(GameTestHelper helper, Runnable body) {
        FarChunks.whenBlocksLoaded(helper, List.of(new FarChunks.At(helper.getLevel(), X >> 4, Z >> 4)), body);
    }

    /** The middle of a zone of a layer: a Y in that zone's biome. */
    private static int zoneMiddle(Band layer, int zone) {
        Zones.Span span = Zones.span(layer.minY(), layer.height(), zone);
        return (span.low() + span.high()) / 2;
    }

    /**
     * The surface lists no creature at all. Layers 1 and 2 list the Retained as their one monster, in each of their three zones, and
     * nothing else in any category. Layer 3 lists nothing yet: its creatures are its own (CREATURES section 5; #918 to #920). The unbuilt
     * depth lists nothing.
     */
    @GameTest(dimension = SAMPLE, environment = CampaignEnvironments.B, maxTicks = MAX_TICKS)
    public void noBandListsAVanillaMonsterAndTheLayersKeepTheRetained(GameTestHelper helper) {
        whenColumnLoaded(helper, () -> {
            ServerLevel level = helper.getLevel();
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            BlockPos column = new BlockPos(X, 0, Z);
            Map<String, Integer> spots = new LinkedHashMap<>();
            spots.put("surface", 64);
            // 8 up from the floor: a cell on the band edge blurs into the layer below.
            spots.put("surface floor", CampaignBands.SURFACE.minY() + 8);
            spots.put("unbuilt depth", -1000);
            for (int number = 1; number <= CampaignBands.LAYERS.size(); number++) {
                for (int zone = 0; zone < Zones.COUNT; zone++) {
                    String place = number <= RETAINED_LAYERS ? "retained layer" : "layer";
                    spots.put(
                            "%s %d zone %d".formatted(place, number, zone),
                            zoneMiddle(CampaignBands.layer(number), zone));
                }
            }
            for (Map.Entry<String, Integer> spot : spots.entrySet()) {
                boolean retained = spot.getKey().startsWith("retained layer");
                for (MobCategory category : MobCategory.values()) {
                    var mobs = generator
                            .getMobsAt(level, level.structureManager(), category, column.atY(spot.getValue()))
                            .unwrap();
                    boolean retainedOnly = retained
                            && category == MobCategory.MONSTER
                            && !mobs.isEmpty()
                            && mobs.stream()
                                    .allMatch(entry -> entry.value().type() == CreatureRegistry.LAMPLESS_FIGURE);
                    if (!mobs.isEmpty() && !retainedOnly) {
                        throw failure(helper, "%s lists %s creatures: %s", spot.getKey(), category, mobs);
                    }
                    if (retained && category == MobCategory.MONSTER && !retainedOnly) {
                        throw failure(helper, "%s does not list the Retained as a monster", spot.getKey());
                    }
                }
            }
            helper.succeed();
        });
    }

    /** Every biome the mod ships is closed to vanilla monsters, so a later biome cannot slip through: no siege, no patrol, no monster listed. */
    @GameTest
    public void everyBiomeOfTheModIsClosedToVanillaMonsters(GameTestHelper helper) throws IOException {
        var biomes = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        List<Holder.Reference<Biome>> ours = biomes.listElements()
                .filter(biome -> biome.key().identifier().getNamespace().equals(DeepCharter.MOD_ID))
                .toList();
        if (ours.isEmpty()) {
            throw failure(helper, "the registry holds no biome of the mod");
        }
        for (Holder.Reference<Biome> biome : ours) {
            String name = biome.key().identifier().getPath();
            if (!biome.is(BiomeTags.WITHOUT_ZOMBIE_SIEGES)) {
                throw failure(helper, "%s is not in without_zombie_sieges", name);
            }
            JsonObject attributes;
            try (Reader reader = new InputStreamReader(
                    EmptySurfaceTest.class.getResourceAsStream("/data/deepcharter/worldgen/biome/" + name + ".json"),
                    StandardCharsets.UTF_8)) {
                attributes = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("attributes");
            }
            JsonElement patrols = attributes.get("minecraft:gameplay/can_pillager_patrol_spawn");
            if (patrols == null || patrols.getAsBoolean()) {
                throw failure(helper, "%s allows pillager patrols", name);
            }
            JsonObject spawns = attributes.has("minecraft:gameplay/natural_mob_spawns")
                    ? attributes
                            .getAsJsonObject("minecraft:gameplay/natural_mob_spawns")
                            .getAsJsonObject("argument")
                            .getAsJsonObject("spawns_by_category")
                    : new JsonObject();
            if (!spawns.has("monster")) {
                continue;
            }
            for (JsonElement monster : spawns.getAsJsonArray("monster")) {
                String type = monster.getAsJsonObject().get("type").getAsString();
                if (type.startsWith("minecraft:")) {
                    throw failure(helper, "%s lists the vanilla monster %s", name, type);
                }
            }
        }
        helper.succeed();
    }

    /**
     * Carves a sealed dark room around each spot, waits for the light of the old rock to go (block and sky), then runs {@code then}. The
     * game's spawner tries a cell only when a player is within the mobs' despawn range (128 blocks) and more than 24 blocks away, so
     * {@code then} gets the player to stand {@link #PLAYER_DISTANCE} blocks from a room with {@link #standBeside}.
     */
    private static void inDarkRooms(
            GameTestHelper helper, ServerLevel level, List<BlockPos> feet, Consumer<MockPlayer> then) {
        MockPlayer mock = MockPlayers.join(helper, "empty-surface");
        mock.player().setNoGravity(true);
        mock.player().setPermanentlyInvulnerable(true);
        int[] ready = {0};
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            if (ready[0] < feet.size()) {
                return;
            }
            // The light engine runs behind the carve, and the test judges the spawner, not the light: it reads both
            // until they settle.
            boolean dark = feet.stream()
                    .allMatch(room -> level.getBrightness(LightLayer.BLOCK, room) == 0
                            && level.getBrightness(LightLayer.SKY, room) == 0);
            deadline.await(helper, level, dark, () -> "the light of the old rock did not leave the rooms");
            if (dark) {
                then.accept(mock);
            }
        });
        FarChunks.awaitEntityTicking(helper, level, feet, index -> {
            BlockPos at = feet.get(index);
            RoomCarver.carve(level, at.offset(-4, -1, -4), at.offset(4, 6, 4), Blocks.STONE.defaultBlockState());
            RoomCarver.carve(level, at.offset(-2, 0, -2), at.offset(2, 4, 2), Blocks.AIR.defaultBlockState());
            ready[0]++;
        });
    }

    /** Puts the player {@link #PLAYER_DISTANCE} blocks east of {@code room}, at its height. */
    private static void standBeside(ServerLevel level, MockPlayer mock, BlockPos room) {
        mock.teleportTo(level, Vec3.atBottomCenterOf(room.east(PLAYER_DISTANCE)), 0f, 0f);
    }

    /** The types the game's spawner places in {@link #TRIES} attempts at {@code room}, over every category; each is removed at once. */
    private static List<String> placedAt(ServerLevel level, BlockPos room) {
        List<String> placed = new ArrayList<>();
        for (int i = 0; i < TRIES; i++) {
            for (MobCategory category : MobCategory.values()) {
                NaturalSpawner.spawnCategoryForPosition(
                        category,
                        level,
                        // sync-load: inDarkRooms waited until the room was entity ticking
                        level.getChunkAt(room),
                        room,
                        (type, spawnLevel, pos, chunk) -> true,
                        (mob, chunk) -> {
                            placed.add(id(mob.getType()));
                            mob.discard();
                        });
            }
        }
        return placed;
    }

    /**
     * The game's spawner, tried {@link #TRIES} times in a dark room on the surface and in one in each layer, places no vanilla monster, and
     * on the surface places nothing at all. The dark of a layer may place a Retained, whose own rules decide. The control below shows the
     * same loop does place a vanilla monster where a biome lists one.
     */
    @GameTest(dimension = SAMPLE, environment = CampaignEnvironments.B, maxTicks = MAX_TICKS)
    public void naturalSpawningInTheDarkPlacesNoVanillaMonster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            throw failure(helper, "the difficulty is peaceful, which stops every monster spawn and proves nothing");
        }
        Map<String, BlockPos> rooms = new LinkedHashMap<>();
        rooms.put("surface", new BlockPos(X, CampaignBands.SURFACE.minY() + 250, Z));
        rooms.put("layer 1", new BlockPos(X + 32, zoneMiddle(CampaignBands.LAYER_1, 1), Z));
        rooms.put("layer 2", new BlockPos(X + 64, zoneMiddle(CampaignBands.LAYER_2, 1), Z));
        inDarkRooms(helper, level, new ArrayList<>(rooms.values()), mock -> {
            for (Map.Entry<String, BlockPos> room : rooms.entrySet()) {
                standBeside(level, mock, room.getValue());
                boolean surface = room.getKey().equals("surface");
                for (String type : placedAt(level, room.getValue())) {
                    if (surface || !type.equals(id(CreatureRegistry.LAMPLESS_FIGURE))) {
                        throw failure(helper, "the spawner placed a %s in the dark of the %s", type, room.getKey());
                    }
                }
            }
            helper.succeed();
        });
    }

    /**
     * The control: in the GameTest server's own overworld, a flat world of vanilla's plains, which lists vanilla's monsters, the same loop
     * in a dark room places a vanilla monster. Without it the test above could pass because the room, the light or the difficulty stops every spawn.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void theSpawnerLoopPlacesAVanillaMonsterWhereABiomeListsOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            throw failure(helper, "the difficulty is peaceful");
        }
        BlockPos room = new BlockPos(X, 64, Z);
        inDarkRooms(helper, level, List.of(room), mock -> {
            standBeside(level, mock, room);
            List<String> placed = placedAt(level, room);
            if (placed.stream().noneMatch(type -> type.startsWith("minecraft:"))) {
                throw failure(
                        helper, "no vanilla monster in %d tries where the biome lists them; placed %s", TRIES, placed);
            }
            helper.succeed();
        });
    }
}
