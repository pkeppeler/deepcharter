package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.surface.GreatStalk;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsSpots.Spot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The test-only builds of the {@code hollows-concepts} scenario (#1147), each made in a generated cavern of layer 3 round a {@link Spot}:
 * the giant fungus forms, the Overgrowth and the Drifters, the floor under the ground-hugging gas and the Quiet's cathedral, shrine and
 * membrane. They are mock-ups of looks the game does not have yet: blocks, never game code, and each carries a {@code mock-up} label (a
 * text display) in the still. Runs on the server thread.
 */
final class HollowsMockups {
    private static final Logger LOGGER = LoggerFactory.getLogger(HollowsMockups.class);
    /** Every entity a mock-up summons carries this tag, so {@link #clearEntities} takes them all away. */
    private static final String TAG = "hollows_mock";

    private static final int SET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** A Drifter is pod-sized (CREATURES.md, Drifters): the Prospector's frame. */
    private static final float DRIFTER = Chassis.PROSPECTOR.width();
    /** The seeds tried, in order, for the great stalk: one grows only where its whole trunk has room. */
    private static final int STALK_SEEDS = 12;
    /** And the cells tried for it, this far round the tall focus each way, until one has room. */
    private static final int STALK_SHIFT = 3;

    /** The Overgrowth's bore: how deep, and how far its growth spills round it. */
    private static final int BORE_DEPTH = 8;

    private static final int SPILL = 5;

    /** The membrane's own faint light, an invisible light block under each tile of it: in the Quiet's dark a pale sheet shows only lit. */
    private static final int MEMBRANE_GLOW = 5;

    private HollowsMockups() {}

    /** The block states of a box, to put back after a build: the next option is built on the cavern as it was generated. */
    static final class Snapshot {
        private final Map<BlockPos, BlockState> states = new LinkedHashMap<>();

        static Snapshot of(ServerLevel level, BlockPos low, BlockPos high) {
            Snapshot snapshot = new Snapshot();
            for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
                snapshot.states.put(pos.immutable(), level.getBlockState(pos));
            }
            return snapshot;
        }

        /** The box of {@code radius} blocks round {@code cell}, from {@code below} under it to {@code above} over it. */
        static Snapshot around(ServerLevel level, BlockPos cell, int radius, int below, int above) {
            return of(level, cell.offset(-radius, -below, -radius), cell.offset(radius, above, radius));
        }

        void restore(ServerLevel level) {
            states.forEach((pos, state) -> {
                if (!level.getBlockState(pos).equals(state)) {
                    level.setBlock(pos, state, SET);
                }
            });
        }
    }

    static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)).defaultBlockState();
    }

    /** Sets {@code state} at {@code pos} if the cell is open (air or a glowcap): a build never cuts the cavern's rock. */
    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        if (HollowsSpots.open(level.getBlockState(pos))) {
            level.setBlock(pos, state, SET);
        }
    }

    // ---------------------------------------------------------------------------------------------- giant fungus

    /**
     * The fungus the game has: a great stalk on or near the tall focus (the first cell and seed whose trunk has room) and three ordinary fungal stalks round
     * it, through the game's own feature. Returns what grew, for the log: a still with nothing in it is read from the log, not failed.
     */
    static String stalks(ServerLevel level, Spot spot) {
        String grew = "no great stalk";
        search:
        for (int dx = -STALK_SHIFT; dx <= STALK_SHIFT; dx++) {
            for (int dz = -STALK_SHIFT; dz <= STALK_SHIFT; dz++) {
                BlockPos column = spot.tall().offset(dx, 0, dz);
                Optional<BlockPos> cell = HollowsSpots.floorAt(
                        level, column.getX(), column.getZ(), spot.tall().getY(), 3);
                for (int seed = 0; cell.isPresent() && seed < STALK_SEEDS; seed++) {
                    if (GreatStalk.place(
                            level, RandomSource.create(seed), cell.get().offset(-1, 0, -1))) {
                        grew = "great stalk seed " + seed + " at " + cell.get().toShortString();
                        break search;
                    }
                }
            }
        }
        int stalks = 0;
        for (double[] at : List.of(new double[] {-4, -5}, new double[] {-2, 5}, new double[] {3, 6})) {
            BlockPos column = spot.at(spot.tall(), at[0], at[1], 0);
            Optional<BlockPos> cell = HollowsSpots.floorAt(
                    level, column.getX(), column.getZ(), spot.tall().getY(), 4);
            if (cell.isPresent()) {
                stalks += tryCommand(
                        level,
                        "place feature deepcharter:fungal_stalk_column %d %d %d"
                                .formatted(
                                        cell.get().getX(),
                                        cell.get().getY(),
                                        cell.get().getZ()));
            }
        }
        return grew + ", " + stalks + " fungal stalks";
    }

    /**
     * A new silhouette from the stalk's own blocks: a parasol, a 2 x 2 trunk as tall as the cavern allows (8 to 20) under a broad cap of
     * radius 6 that droops at the rim, with a dome of radius 4 over it.
     */
    static String parasol(ServerLevel level, Spot spot) {
        BlockState trunk = block("deepcharter:great_stalk");
        BlockState cap = block("deepcharter:great_stalk_cap");
        int height = Mth.clamp(spot.clear() - 3, 8, 20);
        BlockPos base = spot.tall();
        for (int y = 0; y < height; y++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    put(level, base.offset(dx, y, dz), trunk);
                }
            }
        }
        for (int dx = -6; dx <= 7; dx++) {
            for (int dz = -6; dz <= 7; dz++) {
                double r = Math.hypot(dx - 0.5, dz - 0.5);
                if (r <= 6.5) {
                    put(level, base.offset(dx, height, dz), cap);
                }
                if (r <= 4.5) {
                    put(level, base.offset(dx, height + 1, dz), cap);
                }
                if (r > 5.5 && r <= 6.5) {
                    put(level, base.offset(dx, height - 1, dz), cap);
                }
            }
        }
        return "parasol " + height + " tall";
    }

    /**
     * A second new silhouette: bracket spires, a cluster of five thin fungal stalks of falling heights, each with a cap knob on top and
     * bracket shelves of cap off its sides every four blocks.
     */
    static String spires(ServerLevel level, Spot spot) {
        BlockState stalk = block("deepcharter:fungal_stalk");
        BlockState cap = block("deepcharter:great_stalk_cap");
        int tallest = Mth.clamp(spot.clear() - 2, 8, 22);
        double[][] spires = {{0, 0, 1.0}, {2, -2, 0.7}, {-2, 1, 0.8}, {1, 3, 0.55}, {-3, -2, 0.45}};
        int built = 0;
        for (double[] s : spires) {
            BlockPos column = spot.tall().offset((int) s[0], 0, (int) s[1]);
            Optional<BlockPos> cell = HollowsSpots.floorAt(
                    level, column.getX(), column.getZ(), spot.tall().getY(), 6);
            if (cell.isEmpty()) {
                continue;
            }
            int height = (int) Math.round(tallest * s[2]);
            for (int y = 0; y < height; y++) {
                put(level, cell.get().above(y), stalk);
                if (y >= 3 && y % 4 == 3) {
                    int side = (y / 4) % 4;
                    put(
                            level,
                            cell.get()
                                    .offset(side == 0 ? 1 : side == 2 ? -1 : 0, y, side == 1 ? 1 : side == 3 ? -1 : 0),
                            cap);
                }
            }
            put(level, cell.get().above(height), cap);
            built++;
        }
        if (built == 0) {
            throw new AssertionError("no spire had a floor round " + spot.tall().toShortString());
        }
        return built + " spires, the tallest " + tallest;
    }

    // ------------------------------------------------------------------------------------------------- Overgrowth

    /** The two looks of the Overgrowth: pale moss, or pale fibre (mushroom stem and cobweb). */
    enum Growth {
        MOSS,
        FIBRE
    }

    /**
     * A bore drilled into the floor at the focus, 5 x 5 and {@value #BORE_DEPTH} deep, left unvisited and grown over (CREATURES.md,
     * Overgrowth): its walls lined with the growth, the growth swelling out of it and spilling {@value #SPILL} blocks over the floor
     * round it, with only the middle of the bore still open.
     */
    static void overgrowth(ServerLevel level, Spot spot, Growth growth) {
        BlockPos top = spot.focus();
        BlockState wall = block(growth == Growth.MOSS ? "minecraft:pale_moss_block" : "minecraft:mushroom_stem");
        BlockState lump = block(growth == Growth.MOSS ? "minecraft:pale_moss_block" : "minecraft:white_wool");
        BlockState strand = block(growth == Growth.MOSS ? "minecraft:pale_moss_carpet" : "minecraft:cobweb");
        for (int depth = 1; depth <= BORE_DEPTH; depth++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int ring = Math.max(Math.abs(dx), Math.abs(dz));
                    boolean grown = ring == 2 || (ring == 1 && depth <= 2);
                    level.setBlock(top.offset(dx, -depth, dz), grown ? wall : air(), SET);
                }
            }
        }
        for (int dx = -SPILL; dx <= SPILL; dx++) {
            for (int dz = -SPILL; dz <= SPILL; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                BlockPos cell = top.offset(dx, 0, dz);
                boolean scatter = Math.floorMod(dx * 7 + dz * 3, 3) != 0;
                if (ring == 1 || ring == 2) {
                    put(level, cell, lump);
                    if (ring == 1 && (dx == 0 || dz == 0)) {
                        put(level, cell.above(), lump);
                    }
                } else if (ring == 3 && scatter) {
                    put(level, cell, lump);
                } else if (ring > 3 && scatter && !HollowsSpots.open(level.getBlockState(cell.below()))) {
                    put(level, cell, strand);
                }
            }
        }
        if (growth == Growth.FIBRE) {
            put(level, top, strand);
        }
        label(
                level,
                Vec3.atBottomCenterOf(top.above(4)),
                "mock-up: Overgrowth (" + growth.name().toLowerCase(Locale.ROOT) + ")");
    }

    private static BlockState air() {
        return block("minecraft:air");
    }

    // ---------------------------------------------------------------------------------------------------- Drifters

    /** The two looks of a Drifter: a translucent green bladder round a bright core, or a pale cold lantern trailing tendrils. */
    enum Drifter {
        BLADDER,
        LANTERN
    }

    /**
     * Three pod-sized Drifters (CREATURES.md: glowing spore bladders) floating in the cavern ahead of the camera, at 9, 15 and 23 blocks,
     * each raised until it has room. Returns how many found room.
     */
    static int drifters(ServerLevel level, Spot spot, Drifter look) {
        double[][] places = {{9, -3, 0.5}, {15, 4, 2.5}, {23, -1, 4}};
        int placed = 0;
        for (double[] p : places) {
            Vec3 eye = spot.eye();
            Vec3 at = eye.add(spot.forward().scale(p[0]))
                    .add(spot.right().scale(p[1]))
                    .add(0, p[2], 0);
            Optional<Vec3> room = roomFor(level, at);
            if (room.isEmpty()) {
                continue;
            }
            drifter(level, room.get(), look);
            if (placed == 0) {
                label(
                        level,
                        room.get().add(0, DRIFTER / 2 + 0.8, 0),
                        "mock-up: Drifter (" + look.name().toLowerCase(Locale.ROOT) + ")");
            }
            placed++;
        }
        return placed;
    }

    /** {@code at}, or the first point up to four blocks over it, round which a pod-sized box is open. */
    private static Optional<Vec3> roomFor(ServerLevel level, Vec3 at) {
        for (int rise = 0; rise <= 4; rise++) {
            Vec3 c = at.add(0, rise, 0);
            boolean free = true;
            for (BlockPos pos : BlockPos.betweenClosed(
                    BlockPos.containing(c.subtract(DRIFTER / 2, DRIFTER / 2, DRIFTER / 2)),
                    BlockPos.containing(c.add(DRIFTER / 2, DRIFTER / 2, DRIFTER / 2)))) {
                free &= HollowsSpots.open(level.getBlockState(pos));
            }
            if (free) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * One Drifter of blocks round {@code at}: a rough ball as wide as a pod (the cells within its radius), a shell round a glowing core
     * whose light is real block light, and for the lantern four strands hanging under it.
     */
    private static void drifter(ServerLevel level, Vec3 at, Drifter look) {
        BlockState shell = block(look == Drifter.BLADDER ? "minecraft:slime_block" : "minecraft:white_stained_glass");
        BlockState core = block(look == Drifter.BLADDER ? "minecraft:verdant_froglight" : "minecraft:sea_lantern");
        BlockPos middle = BlockPos.containing(at);
        double radius = DRIFTER / 2;
        int reach = Mth.ceil(radius);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (r <= 0.5) {
                        put(level, middle.offset(dx, dy, dz), core);
                    } else if (r <= radius) {
                        put(level, middle.offset(dx, dy, dz), shell);
                    }
                }
            }
        }
        if (look == Drifter.LANTERN) {
            BlockState strand = block("minecraft:light_blue_stained_glass_pane");
            for (int[] corner : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int down = reach + 1; down <= reach + 3; down++) {
                    put(level, middle.offset(corner[0], -down, corner[1]), strand);
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- ground gas

    /**
     * The floor ahead of the camera (3 to 22 blocks ahead, 12 to each side), every other column, where the ground-hugging gas is drawn:
     * the scenario spawns glowing particles over these cells on the client, a mock-up of a low layer the game does not have. Labels it.
     */
    static List<Vec3> gasFloor(ServerLevel level, Spot spot) {
        List<Vec3> floor = new ArrayList<>();
        for (int ahead = 3; ahead <= 22; ahead += 1) {
            for (int across = -12; across <= 12; across += 1) {
                BlockPos column = spot.at(spot.feet(), ahead, across, 0);
                HollowsSpots.floorAt(level, column.getX(), column.getZ(), spot.floorY(), 6)
                        .ifPresent(cell -> floor.add(Vec3.atBottomCenterOf(cell)));
            }
        }
        label(level, Vec3.atBottomCenterOf(spot.focus().above(3)), "mock-up: ground gas");
        return floor;
    }

    // -------------------------------------------------------------------------------------------------- the Quiet

    /**
     * The Quiet as LORE.md and CREATURES.md tell it, on the tall focus: a cathedral of four pointed ribs of columnar basalt across the
     * view, as tall as the cavern allows (8 to 18); a pale membrane spread under their apex (the Hush), white glass lit faintly from under; and the Badger's wreck grown into
     * a shrine between them, a pod-sized hull with fungus on it, a ring of glowcaps and five kneeling shapes round it. Returns the rib
     * height.
     */
    static int quiet(ServerLevel level, Spot spot) {
        BlockState rib = LayerBlocks.COLUMNAR_BASALT.defaultBlockState();
        BlockPos centre = spot.tall();
        int height = ribHeight(spot);
        int half = 7;
        for (int ahead : new int[] {-6, -1, 4, 9}) {
            for (int side : new int[] {-1, 1}) {
                for (double s = 0; s <= height; s += 0.25) {
                    double across = side * half * (1 - Math.pow(s / height, 1.7));
                    put(level, spot.at(centre, ahead, across, (int) Math.round(s)), rib);
                }
                BlockPos foot = spot.at(centre, ahead, side * half, 0);
                for (int down = 1; down <= 6 && HollowsSpots.open(level.getBlockState(foot.below(down))); down++) {
                    put(level, foot.below(down), rib);
                }
            }
        }
        BlockState membrane = block("minecraft:white_stained_glass");
        BlockState glow = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, MEMBRANE_GLOW);
        for (int ahead = -7; ahead <= 10; ahead += 3) {
            for (int across = -6; across <= 6; across += 3) {
                double r = Math.hypot((ahead - 1.5) / 8.5, across / 6.0);
                double sag = 1.5 * Math.max(0, 1 - r * r);
                BlockPos tile = spot.at(centre, ahead, across, height - 2 - (int) Math.round(sag));
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        put(level, tile.offset(dx, 0, dz), membrane);
                    }
                }
                put(level, tile.below(), glow);
            }
        }
        shrine(level, spot, centre);
        label(level, Vec3.atBottomCenterOf(spot.at(centre, 0, 0, 5)), "mock-up: cathedral, shrine, membrane");
        return height;
    }

    /** How tall the Quiet's ribs stand at the spot: as tall as the cavern allows, 8 to 18. */
    static int ribHeight(Spot spot) {
        return Mth.clamp(spot.clear() - 2, 8, 18);
    }

    /** The Badger's wreck grown into a shrine: a 3 x 4 x 3 hull of old copper with a dark canopy, capped with fungus, ringed by glowcaps and kneeling shapes. */
    private static void shrine(ServerLevel level, Spot spot, BlockPos centre) {
        BlockState hull = block("minecraft:waxed_weathered_copper");
        BlockState canopy = block("minecraft:tinted_glass");
        BlockState cap = block("deepcharter:great_stalk_cap");
        BlockState stalk = block("deepcharter:fungal_stalk");
        for (int ahead = 0; ahead <= 3; ahead++) {
            for (int across = -1; across <= 1; across++) {
                for (int up = 0; up <= 2; up++) {
                    boolean front = ahead == 0 && across == 0 && up == 1;
                    put(level, spot.at(centre, ahead, across, up), front ? canopy : hull);
                }
            }
        }
        int[][] caps = {{1, -1}, {2, 1}, {3, 0}, {3, -1}};
        for (int[] c : caps) {
            put(level, spot.at(centre, c[0], c[1], 3), cap);
        }
        for (int up = 3; up <= 6; up++) {
            put(level, spot.at(centre, 2, 0, up), stalk);
        }
        put(level, spot.at(centre, 2, 0, 7), cap);
        BlockState glowcap = LayerBlocks.GLOWCAP.defaultBlockState();
        for (double angle = 0; angle < 360; angle += 60) {
            double a = Math.toRadians(angle);
            BlockPos column = spot.at(centre, 1.5 + 3 * Math.cos(a), 3 * Math.sin(a), 0);
            HollowsSpots.floorAt(level, column.getX(), column.getZ(), centre.getY(), 2)
                    .ifPresent(cell -> put(level, cell, glowcap));
        }
        Vec3 middle = Vec3.atBottomCenterOf(spot.at(centre, 1.5, 0, 0));
        for (double angle = -100; angle <= 100; angle += 50) {
            double a = Math.toRadians(angle + 180);
            BlockPos column = spot.at(centre, 1.5 + 4.5 * Math.cos(a), 4.5 * Math.sin(a), 0);
            Optional<BlockPos> cell = HollowsSpots.floorAt(level, column.getX(), column.getZ(), centre.getY(), 2);
            if (cell.isEmpty()) {
                continue;
            }
            Vec3 at = Vec3.atBottomCenterOf(cell.get()).subtract(0, 0.55, 0);
            Vec3 d = middle.subtract(at);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            summon(
                    level,
                    "minecraft:armor_stand",
                    at,
                    ("{NoGravity:1b,Invulnerable:1b,ShowArms:1b,NoBasePlate:1b,Rotation:[%sf,0f],"
                                    + "Pose:{Head:[35f,0f,0f],Body:[25f,0f,0f],LeftArm:[320f,0f,0f],RightArm:[320f,0f,0f]},"
                                    + "equipment:{head:{id:\"deepcharter:great_stalk_cap\",count:1}},Tags:[\"%s\"]}")
                            .formatted(num(yaw), TAG));
        }
    }

    // -------------------------------------------------------------------------------------------------- entities

    /** Takes away every entity a mock-up summoned. */
    static void clearEntities(ServerLevel level) {
        tryCommand(level, "kill @e[tag=" + TAG + "]");
    }

    /** A {@code text} label floating at {@code at}, at full brightness, facing the camera: what the still shows is a mock-up. */
    private static void label(ServerLevel level, Vec3 at, String text) {
        summon(
                level,
                "minecraft:text_display",
                at,
                ("{text:\"%s\",billboard:\"center\",brightness:{sky:0,block:15},Tags:[\"%s\"],"
                                + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                                + "translation:[0f,0f,0f],scale:[1.2f,1.2f,1.2f]}}")
                        .formatted(text, TAG));
    }

    /** Summons one {@code type} at {@code at}; one that fails is logged, not fatal: the still then shows the build without it. */
    private static void summon(ServerLevel level, String type, Vec3 at, String nbt) {
        int made = tryCommand(level, "summon %s %s %s %s %s".formatted(type, num(at.x), num(at.y), num(at.z), nbt));
        if (made != 1) {
            LOGGER.warn("[hollows-concepts] summon {} at {} made {}", type, at, made);
        }
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * Runs {@code command} and returns its result, or 0 where it fails: a {@code place feature} that had no room, a {@code kill} that found
     * nothing.
     */
    private static int tryCommand(ServerLevel level, String command) {
        try {
            return level.getServer().getCommands().getDispatcher().execute(command, source(level));
        } catch (CommandSyntaxException e) {
            return 0;
        }
    }

    private static CommandSourceStack source(ServerLevel level) {
        return level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
    }
}
