package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.SalvagePrefabConcepts;
import io.github.pkeppeler.deepcharter.test.support.SalvagePrefabConcepts.Option;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "salvage-prefab-concepts" (#1214, docs/design/salvage-prefab-concepts.md), the look round for the
 * wrecked salvage prefabs of #471: each of three wrecks (a downed supply lander, a toppled survey derrick, a derailed
 * ore train; {@code tools/colony/wrecks.py}) stands in the salvage yard, the biome ring 100 to 160 blocks out from the
 * colony, on open ground, among the yard's own crates and under its dust haze, with a parked pod and a player beside it
 * for scale. Each is shot near, mid, from the air and from a long way off ({@code <option>-near}, {@code -mid},
 * {@code -aerial}, {@code -range}), and flown round once for the GIF.
 *
 * <p>The ground under a wreck is levelled and cleared of the yard's litter inside the box its layout names, nothing
 * more. Nothing here reaches a player's world: the pieces are test resources.
 */
public class SalvagePrefabConceptsScenario extends EvidenceScenario {
    private static final ResourceKey<Biome> YARD =
            ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("deepcharter", "salvage_yard"));
    /** The yard's ring round the colony's centre, and the radii and the number of bearings a site is looked for on. */
    private static final int[] RADII = {115, 128, 141, 152};

    private static final int BEARINGS = 20;
    /**
     * The most the ground may rise and fall over the box a wreck needs (between its tenth and ninetieth percentile),
     * and how far apart two wrecks stand.
     */
    private static final int MAX_RELIEF = 8;

    private static final int SPACING = 90;
    private static final int SAMPLE_STEP = 4;
    /**
     * The wreck's ground is flat this far past its bounds and blends into the plain's over the next stretch, to
     * {@code BLEND}.
     */
    private static final double FLAT = 2;

    private static final double BLEND = 12;
    /** The relief that counts is the ground under the wreck and this far round it. */
    private static final double RELIEF_MARGIN = 4;
    /** The share of a site's samples that must be in the yard's biome. */
    private static final double YARD_SHARE = 0.7;
    /** How far above the ground a wreck's clearing reaches. */
    private static final int CLEARING_HEIGHT = 34;
    /**
     * The pod, the player and the cameras: how far past the figures the near and mid stills stand, and the aerial's and
     * range's reach.
     */
    private static final double NEAR_BEHIND = 4.5;

    private static final double MID_BEHIND = 9.0;
    private static final double AERIAL_BEHIND = 9.0;
    private static final double AERIAL_HEIGHT = 22;
    private static final double RANGE_FROM_FOCUS = 40;
    private static final double EYE_NEAR = 2.2;
    private static final double EYE_MID = 3.4;
    private static final double EYE_RANGE = 4.5;
    private static final int ORBIT_FRAMES = 24;
    private static final double ORBIT_HEIGHT = 13;
    private static final int FRAME_WAIT_TICKS = 3;
    private static final int SETTLE_TICKS = 20;

    @Override
    protected String name() {
        return "salvage-prefab-concepts";
    }

    /** Where a wreck stands: the ground block its piece's Y 0 lies on. */
    private record Site(BlockPos ground, double relief) {}

    @Override
    protected void run(ClientGameTestContext context) {
        TextureTestWorld.open(context, shot -> screenshot(context, shot), world -> {
            world.settleLimit(240);
            List<Option> options = world.serverGet(SalvagePrefabConcepts::read);
            List<BlockPos> used = new ArrayList<>();
            for (Option option : options) {
                Site site = world.serverGet(server -> {
                    BlockPos colony = Colony.placed(server)
                            .orElseThrow(() -> new AssertionError("the colony was not built"))
                            .center();
                    return find(server.overworld(), colony, option, used);
                });
                used.add(site.ground());
                world.serverDo(server -> build(server, option, site));
                shootOption(context, world, option, site);
            }
            frame(context);
        });
    }

    // ------------------------------------------------------------------------------------------------ the site

    /**
     * The flattest open ground in the yard, on a bearing and a radius from the colony: every sample of the box the
     * wreck needs is in the yard biome and none is water, and the box's ground rises and falls by the least. Fails,
     * naming the best it saw, if none is within {@link #MAX_RELIEF}.
     */
    private static Site find(ServerLevel level, BlockPos colony, Option option, List<BlockPos> used) {
        @Nullable Site best = null;
        List<String> seen = new ArrayList<>();
        for (int radius : RADII) {
            for (int step = 0; step < BEARINGS; step++) {
                double bearing = 2 * Math.PI * (step + (radius % 3) / 3.0) / BEARINGS;
                BlockPos at = colony.offset(
                        (int) Math.round(Math.cos(bearing) * radius), 0, (int) Math.round(Math.sin(bearing) * radius));
                if (used.stream()
                        .anyMatch(other -> Math.hypot(other.getX() - at.getX(), other.getZ() - at.getZ()) < SPACING)) {
                    continue;
                }
                Site site = assess(level, at, option, seen);
                if (site != null && (best == null || site.relief() < best.relief())) {
                    best = site;
                }
            }
        }
        if (best == null || best.relief() > MAX_RELIEF) {
            throw new AssertionError("no open ground in the salvage yard for wreck " + option.name() + "; the best was "
                    + best + ", saw " + seen);
        }
        return best;
    }

    /**
     * The site at {@code at}, or null when the box holds water, a ground that will not carry a wreck, or too little of
     * the yard's biome ({@link #YARD_SHARE} of its samples). Every site seen is added to {@code seen}, for the failure
     * message.
     */
    private static @Nullable Site assess(ServerLevel level, BlockPos at, Option option, List<String> seen) {
        List<Integer> heights = new ArrayList<>();
        int yard = 0;
        int samples = 0;
        for (int x = option.levelFrom().x(); x <= option.levelTo().x(); x += SAMPLE_STEP) {
            for (int z = option.levelFrom().z(); z <= option.levelTo().z(); z += SAMPLE_STEP) {
                BlockPos column = at.offset(x, 0, z);
                TextureTestWorld.loadChunks(level, column, 0);
                BlockPos top = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, column);
                BlockPos ground = top.below();
                samples++;
                if (!level.getBlockState(ground).getFluidState().isEmpty()) {
                    seen.add(at.toShortString() + " has fluid");
                    return null;
                }
                if (level.getBiome(top).is(YARD)) {
                    yard++;
                }
                if (outside(option, x, z) <= RELIEF_MARGIN) {
                    heights.add(ground.getY());
                }
            }
        }
        heights.sort(null);
        int median = heights.get(heights.size() / 2);
        int relief = heights.get(heights.size() * 9 / 10) - heights.get(heights.size() / 10);
        seen.add(at.toShortString() + " yard " + yard + "/" + samples + " relief " + relief);
        if (yard < samples * YARD_SHARE) {
            return null;
        }
        return new Site(new BlockPos(at.getX(), median, at.getZ()), relief);
    }

    // ------------------------------------------------------------------------------------------------ building

    /** Levels and clears the wreck's ground, places its piece, and stands the pod and the player beside it. */
    private static void build(MinecraftServer server, Option option, Site site) {
        ServerLevel level = server.overworld();
        BlockPos ground = site.ground();
        BlockState topsoil = SurfaceBlocks.REGOLITH.defaultBlockState();
        BlockState subsoil = SurfaceBlocks.REGOLITH_PACKED.defaultBlockState();
        ColumnPos from = option.levelFrom();
        ColumnPos to = option.levelTo();
        TextureTestWorld.loadChunks(level, ground, 5);
        for (int x = from.x(); x <= to.x(); x++) {
            for (int z = from.z(); z <= to.z(); z++) {
                BlockPos column = ground.offset(x, 0, z);
                double outside = outside(option, x, z);
                if (outside > BLEND) {
                    continue;
                }
                double weight = outside <= FLAT ? 1 : 1 - (outside - FLAT) / (BLEND - FLAT);
                int natural = naturalGround(level, column);
                int target = (int) Math.round(natural + (ground.getY() - natural) * weight);
                for (int y = natural + 6; y > target; y--) {
                    clear(level, new BlockPos(column.getX(), y, column.getZ()));
                }
                for (int y = natural + 1; y <= target; y++) {
                    level.setBlock(new BlockPos(column.getX(), y, column.getZ()), subsoil, Block.UPDATE_CLIENTS);
                }
                level.setBlock(new BlockPos(column.getX(), target, column.getZ()), topsoil, Block.UPDATE_CLIENTS);
                if (outside <= FLAT) {
                    for (int y = target - 1; y > target - 3; y--) {
                        level.setBlock(new BlockPos(column.getX(), y, column.getZ()), subsoil, Block.UPDATE_CLIENTS);
                    }
                    for (int y = target + 1; y <= target + CLEARING_HEIGHT; y++) {
                        clear(level, new BlockPos(column.getX(), y, column.getZ()));
                    }
                }
            }
        }
        StructureTemplate template = server.getStructureTemplateManager()
                .get(option.structure())
                .orElseThrow(() -> new AssertionError("wreck " + option.name() + " does not load"));
        BlockPos origin = ground.offset(option.offset());
        template.placeInWorld(
                level, origin, origin, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
        stand(server, option, ground);
    }

    /** How far a column is outside the wreck's bounds, in blocks: 0 under it. */
    private static double outside(Option option, int x, int z) {
        double dx = Math.max(
                Math.max(option.boundsFrom().x() - x, x - option.boundsTo().x()), 0);
        double dz = Math.max(
                Math.max(option.boundsFrom().z() - z, z - option.boundsTo().z()), 0);
        return Math.hypot(dx, dz);
    }

    /** The ground of a column as the world made it: its top block that is not a crate or a stalk standing on it. */
    private static int naturalGround(ServerLevel level, BlockPos column) {
        BlockPos top =
                level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, column).below();
        while (!level.getBlockState(top).isSolidRender()) {
            top = top.below();
        }
        return top.getY();
    }

    private static void clear(ServerLevel level, BlockPos at) {
        if (!level.getBlockState(at).isAir()) {
            level.setBlock(
                    at,
                    Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS); // room-carver: clears surface litter, not layer rock
        }
    }

    /**
     * A parked pod to the camera's left and a mannequin in a player's shape to its right, both turned to look at the
     * wreck.
     */
    private static void stand(MinecraftServer server, Option option, BlockPos ground) {
        ServerLevel level = server.overworld();
        Vec3 focus = pieceAt(ground, option.focus());
        for (ColumnPos column : List.of(option.pod(), option.player())) {
            Vec3 at = new Vec3(ground.getX() + column.x() + 0.5, ground.getY() + 1, ground.getZ() + column.z() + 0.5);
            Vec3 toward = focus.subtract(at);
            float yaw = (float) Math.toDegrees(Math.atan2(-toward.x, toward.z));
            if (column.equals(option.pod())) {
                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(at);
                pod.setYRot(yaw);
                level.addFreshEntity(pod);
            } else {
                server.getCommands()
                        .performPrefixedCommand(
                                server.createCommandSourceStack(),
                                String.format(
                                        Locale.ROOT,
                                        "summon minecraft:mannequin %.2f %.2f %.2f {Rotation:[%.1ff,0f],immovable:1b,hide_description:1b}",
                                        at.x,
                                        at.y,
                                        at.z,
                                        yaw));
            }
        }
    }

    /** A point of the wreck's piece space as a point of the world: its cell's middle. */
    private static Vec3 pieceAt(BlockPos ground, Vec3 piece) {
        return new Vec3(ground.getX() + piece.x + 0.5, ground.getY() + piece.y + 0.5, ground.getZ() + piece.z + 0.5);
    }

    // ------------------------------------------------------------------------------------------------ the views

    private void shootOption(ClientGameTestContext context, TextureTestWorld world, Option option, Site site) {
        BlockPos ground = site.ground();
        Vec3 focus = pieceAt(ground, option.focus());
        double length = Math.hypot(option.sideX(), option.sideZ());
        double sx = option.sideX() / length;
        double sz = option.sideZ() / length;
        double figures = option.reach();
        double floor = ground.getY() + 1;
        view(world, focus, sx, sz, figures + NEAR_BEHIND, floor + EYE_NEAR, focus.y + 1, option.name() + "-near");
        view(world, focus, sx, sz, figures + MID_BEHIND, floor + EYE_MID, focus.y + 1, option.name() + "-mid");
        view(
                world,
                focus,
                sx,
                sz,
                figures + AERIAL_BEHIND,
                floor + AERIAL_HEIGHT,
                focus.y - 1,
                option.name() + "-aerial");
        view(world, focus, sx, sz, RANGE_FROM_FOCUS, floor + EYE_RANGE, focus.y + 2, option.name() + "-range");
        double radius = figures + MID_BEHIND;
        for (int i = 0; i < ORBIT_FRAMES; i++) {
            double angle = Math.atan2(sz, sx) + 2 * Math.PI * i / ORBIT_FRAMES;
            Vec3 eye = new Vec3(
                    focus.x + Math.cos(angle) * radius, floor + ORBIT_HEIGHT, focus.z + Math.sin(angle) * radius);
            world.view(
                    0,
                    clearView(world, eye, focus, option.name() + "-orbit"),
                    focus,
                    i == 0 ? SETTLE_TICKS : FRAME_WAIT_TICKS);
            frame(context);
        }
    }

    /**
     * One still: the camera {@code distance} blocks from the focus along the wreck's open side, {@code eyeY} up,
     * looking at the focus.
     */
    private void view(
            TextureTestWorld world,
            Vec3 focus,
            double sx,
            double sz,
            double distance,
            double eyeY,
            double aimY,
            String still) {
        Vec3 eye = clearView(world, new Vec3(focus.x + sx * distance, eyeY, focus.z + sz * distance), focus, still);
        world.view(0, eye, new Vec3(focus.x, aimY, focus.z), SETTLE_TICKS);
        world.still(still);
    }

    /**
     * The eye lifted until neither a block holds the camera nor ground that is not the wreck's stands in its sight line
     * to the focus: a rise in the plain must not hide a wreck from the range still. Throws, naming {@code what} and the
     * eye, when 24 lifts do not clear it: a still from inside a hill is never recorded.
     */
    private static Vec3 clearView(TextureTestWorld world, Vec3 eye, Vec3 focus, String what) {
        return world.serverGet(server -> {
            ServerLevel level = server.overworld();
            Vec3 lifted = eye;
            for (int i = 0; i < 24; i++) {
                BlockPos at = BlockPos.containing(lifted);
                boolean held = !level.getBlockState(at).isAir()
                        || !level.getBlockState(at.above()).isAir();
                HitResult hit = level.clip(new ClipContext(
                        lifted,
                        focus.add(0, 3, 0),
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        CollisionContext.empty()));
                boolean hidden = hit.getType() != HitResult.Type.MISS
                        && hit.getLocation().distanceTo(focus) > 24;
                if (!held && !hidden) {
                    return lifted;
                }
                lifted = lifted.add(0, 2, 0);
            }
            throw new IllegalStateException(
                    "no clear view for " + what + ": the eye is still blocked or hidden at " + lifted);
        });
    }
}
