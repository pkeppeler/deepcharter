package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.CaveIns;
import io.github.pkeppeler.deepcharter.ore.CompanyRockBlock;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The blasting charge (#509, ADR 0015): set from the seat on the slab under the pod, a fuse, then a blast that clears
 * {@code deepcharter:undiggable} (Company rock) and the natural rock round it. It is the one thing that does. The blast
 * volume is the pod's bore square, {@code chargeDepth} slab rows deep.
 *
 * <p>The shaped charge (#526) is the same set-up with a directed blast: in each column of the bore square it clears the Company rock from the slab under the
 * pod down to the first block that is not Company rock, at most {@code shapedDepth} rows, and nothing else (stone is the drill's). It will not be set when a
 * column's run ends on lava, because the jet would open it; the blasting charge, whose hole is {@code chargeDepth} rows deep and no wider than
 * the bore, still can, and lets the lava flow as a bore does. Counterplay for the rest of what
 * it may open is the pilot's: a gas pocket under the run is not cleared (the sounder is the answer), and a cavern under it is a drop.
 *
 * <p>Rules: only blocks of {@link HazardBlocks#UNDIGGABLE} and {@link HazardBlocks#NATURAL_ROCK} go (so bricks, lining,
 * liner rings, structures, bedrock, crust and every fluid stay), and only in loaded chunks. A gas pocket in the volume
 * goes with the blast and vents no more, as in a gas blast. Lava next to the hole is not touched; the hole lets it
 * flow, as a bore does. Every pod within {@code chargeReach} of the volume takes {@code chargeDamage} less its distance share, the setter's too
 * unless it climbs clear (a wreck takes nothing; another charter's pod in reach is hurt, the risk of a shared shaft). A charge is never a suicide
 * button: a pod cannot set one with no more hull than {@code chargeDamage} plus the hard landing of a free fall to the bottom of the hole it
 * opens ({@link #dropRows}), since a pod left sitting on its charge takes the blast and then drops into the hole (a cavern already below the hole is a
 * drop of its own, as under the drill's last slab). A pilot who holds the rotor
 * hovers through both.
 * The fuse belongs to the pod and is neither saved nor synced: a pod that is discarded or unloaded before the blast loses it.
 *
 * <p>Sideways (#1613, ADR 0120): a pod square to the compass with Company rock on the face of its next bore slab
 * sets the same charges on that face instead of under it. A blasting charge clears {@code chargeSideDepth} slabs along
 * the facing; a shaped charge clears the Company rock along the facing to the first other block, at most
 * {@code shapedSideDepth}. The hull guard counts the blast alone, as nothing opens under the pod, and the pilot gets
 * clear by backing out of {@code chargeReach}. Every blast is computed from a {@link ChargeSite}, a volume and a
 * facing, not from the pod.
 */
public final class PodCharge {
    private static final int TICKS_PER_SECOND = 20;
    private static final int SPARK_EVERY_TICKS = 4;
    /** Ticks after the blast is due that a widened joint waits before it closes again: a fuse that was lost leaves no crack behind. */
    private static final int CLOSE_GRACE_TICKS = 40;

    /** Which charge: the blasting charge clears a small volume of rock, the shaped charge a Company rock column. */
    public enum Kind {
        BLASTING,
        SHAPED
    }

    /**
     * A burning fuse: the site it will clear, the kind of charge, the numbers of the tuning it was set with, and the
     * charter that set it.
     */
    record Fuse(
            Kind kind,
            ChargeSite site,
            int ticksLeft,
            int startTicks,
            float damage,
            double reach,
            Optional<CharterId> blaster) {
        Fuse burned() {
            return new Fuse(kind, site, ticksLeft - 1, startTicks, damage, reach, blaster);
        }

        AABB volume() {
            return site.volume();
        }
    }

    private PodCharge() {}

    /** The charter whose blast a blast from {@code pod} is: the pod's owner, or the pilot's charter when the pod has none. */
    public static Optional<CharterId> blasterOf(PodEntity pod) {
        return PodComponents.ownerCharter(pod)
                .or(() -> pod.getControllingPassenger() instanceof ServerPlayer pilot
                        ? Charters.readableCharterOf(pilot.level().getServer(), pilot.getUUID())
                        : Optional.empty())
                .map(Charter::id);
    }

    /**
     * Server only: sets a charge for {@code pod}, under it or, when it is square to the compass in front of Company
     * rock, on the face ahead. Empty when it is set (the caller uses the item up), or the reason it was not.
     */
    public static Optional<Component> set(PodEntity pod, RepairTuning tuning, Kind kind) {
        if (pod.fuse() != null) {
            return refusal("charge_burning");
        }
        if (!pod.onGround()) {
            return refusal("charge_not_resting");
        }
        ServerLevel level = (ServerLevel) pod.level();
        LoadedBlocks blocks = new LoadedBlocks(level);
        PodFootprint foot = PodFootprint.of(pod);
        int top = Mth.ceil(pod.getY() - PodFootprint.EPSILON) - 1;
        int depth = kind == Kind.SHAPED ? tuning.shapedDepth() : tuning.chargeDepth();
        ChargeSite below = ChargeSite.ahead(
                new BlockPos(foot.lowX(), top, foot.lowZ()),
                new BlockPos(foot.lowX() + foot.width() - 1, top, foot.lowZ() + foot.width() - 1),
                Direction.DOWN,
                depth);
        Direction facing = PodSteering.nearestCardinal(pod);
        BlockPos[] face = foot.faceAhead(facing);
        ChargeSite ahead = ChargeSite.ahead(
                face[0], face[1], facing, kind == Kind.SHAPED ? tuning.shapedSideDepth() : tuning.chargeSideDepth());
        boolean rockAhead = ahead.faceCells().stream().anyMatch(cell -> holdsCompanyRock(blocks, cell));
        boolean square =
                Math.abs(Mth.wrapDegrees(facing.toYRot() - pod.getYRot())) <= PodSteeringTuning.DEFAULT.alignDegrees();
        ChargeSite site = rockAhead && square ? ahead : below;
        Optional<Component> refused = refuse(level, blocks, kind, site);
        if (refused.isPresent()) {
            return rockAhead && !square && site == below ? refusal("charge_not_square") : refused;
        }
        float wrecking = wreckingHull(pod, tuning, dropRows(level, blocks, kind, site));
        if (pod.hull() <= wrecking) {
            return Optional.of(Component.translatable(
                    "message.deepcharter.repair.charge_hull_low",
                    String.format(Locale.ROOT, "%.1f", wrecking),
                    String.format(Locale.ROOT, "%.1f", pod.hull())));
        }
        Fuse fuse = new Fuse(
                kind,
                site,
                tuning.chargeFuseTicks(),
                tuning.chargeFuseTicks(),
                tuning.chargeDamage(),
                tuning.chargeReach(),
                blasterOf(pod));
        pod.setFuse(fuse);
        level.playSound(
                null, pod.getX(), pod.getY(), pod.getZ(), DeepSound.DRILL_DYNAMITE.event(), SoundSource.PLAYERS);
        count(pod, fuse, site.facing() == Direction.DOWN ? "charge_set" : "charge_set_sideways");
        return Optional.empty();
    }

    /**
     * Why {@code kind} may not be set at {@code site}, or empty when it may: the refusals every way of placing a
      * charge shares. A blasting charge needs Company rock
     * somewhere in its volume; a shaped charge needs some column to start in Company rock and none to end on lava.
     */
    static Optional<Component> refuse(ServerLevel level, LoadedBlocks blocks, Kind kind, ChargeSite site) {
        if (kind == Kind.SHAPED) {
            return checkShaped(level, blocks, site);
        }
        return hasCompanyRock(level, site.low(), site.high()) ? Optional.empty() : refusal("charge_no_company_rock");
    }

    private static boolean holdsCompanyRock(LoadedBlocks blocks, BlockPos cell) {
        return blocks.getBlockState(cell).is(HazardBlocks.UNDIGGABLE);
    }

    /**
     * The hull a pod sitting on its own charge loses: the whole blast, then the fall of {@code dropRows} into the
     * hole, landing as {@link HardLanding} counts it with the pod's own stats. A pod with this much hull or less would
     * be wrecked.
     */
    public static float wreckingHull(PodEntity pod, RepairTuning tuning, int dropRows) {
        return tuning.chargeDamage() + HardLanding.freeFallDamage(PodStats.of(pod), dropRows);
    }

    /**
     * The rows a pod sitting on the charge drops once it blows: a blasting charge's whole depth, or a shaped charge's
      * deepest column run, and none when the
     * charge bores sideways, as nothing opens under the pod. Both count the deepest the hole may be: a shallower
      * column may hold the pod sooner, but the
     * refusal must never let a charge wreck the pod.
     */
    private static int dropRows(ServerLevel level, LoadedBlocks blocks, Kind kind, ChargeSite site) {
        if (site.facing() != Direction.DOWN) {
            return 0;
        }
        if (kind == Kind.BLASTING) {
            return site.depth();
        }
        int deepest = 0;
        for (BlockPos start : site.faceCells()) {
            deepest = Math.max(deepest, runLength(level, blocks, start, site));
        }
        return deepest;
    }

    /** Refuses a shaped charge that has no Company rock to start in, or that would open onto lava: some column's run
     * must start at the face and none may end on lava. */
    private static Optional<Component> checkShaped(ServerLevel level, LoadedBlocks blocks, ChargeSite site) {
        boolean anyRock = false;
        for (BlockPos start : site.faceCells()) {
            int run = runLength(level, blocks, start, site);
            anyRock |= run > 0;
            if (run > 0 && run < site.depth()) {
                BlockPos end = start.relative(site.facing(), run);
                if (blocks.getBlockState(end).getFluidState().is(FluidTags.LAVA)) {
                    return refusal("charge_opens_lava");
                }
            }
        }
        return anyRock ? Optional.empty() : refusal("charge_no_company_rock");
    }

    /** Cells of Company rock in a row along the site's facing from {@code start}, as far as the site goes and no
     * further. */
    private static int runLength(ServerLevel level, LoadedBlocks blocks, BlockPos start, ChargeSite site) {
        int run = 0;
        for (int row = 0; row < site.depth(); row++) {
            BlockPos cell = start.relative(site.facing(), row);
            if (!changeable(level, blocks, cell) || !holdsCompanyRock(blocks, cell)) {
                break;
            }
            run++;
        }
        return run;
    }

    /** Called every pod tick, on the server only. */
    static void tick(PodEntity pod) {
        Fuse fuse = pod.fuse();
        if (fuse == null) {
            return;
        }
        ServerLevel level = (ServerLevel) pod.level();
        if (fuse.ticksLeft() <= 0) {
            pod.setFuse(null);
            blast(level, fuse, true, any -> true);
            return;
        }
        if (fuse.ticksLeft() % TICKS_PER_SECOND == 0 && fuse.ticksLeft() < fuse.startTicks()) {
            count(pod, fuse, "charge_count");
        }
        if (fuse.ticksLeft() % SPARK_EVERY_TICKS == 0) {
            Vec3 spark = fuse.site().sparkPoint();
            Vec3 back =
                    Vec3.atLowerCornerOf(fuse.site().facing().getUnitVec3i()).scale(-0.2);
            level.sendParticles(ParticleTypes.FLAME, spark.x, spark.y, spark.z, 3, 0.4, 0.4, 0.4, 0.01);
            level.sendParticles(
                    ParticleTypes.SMOKE, spark.x + back.x, spark.y + back.y, spark.z + back.z, 2, 0.3, 0.3, 0.3, 0.01);
        }
        widenJoints(level, fuse);
        pod.setFuse(fuse.burned());
    }

    /**
     * The joints of the Company rock the blast will clear widen in {@link CompanyRockBlock#MAX_CRACK} steps, evenly through the fuse, so the pilot
     * sees the rock give before it goes. Each step is one block change in the cells that hold Company rock, on one tick of the fuse: the rock never
     * ticks while it waits, and an idle rock costs nothing.
     */
    private static void widenJoints(ServerLevel level, Fuse fuse) {
        int steps = CompanyRockBlock.MAX_CRACK + 1;
        for (int stage = 1; stage <= CompanyRockBlock.MAX_CRACK; stage++) {
            if (fuse.ticksLeft() != fuse.startTicks() * (steps - stage) / steps) {
                continue;
            }
            for (BlockPos cell : cleared(level, new LoadedBlocks(level), fuse)) {
                CompanyRockBlock.widen(level, cell, stage, fuse.ticksLeft() + CLOSE_GRACE_TICKS);
            }
        }
    }

    /** The seconds left, on the pilot's action bar. */
    private static void count(PodEntity pod, Fuse fuse, String key) {
        if (pod.getControllingPassenger() instanceof ServerPlayer pilot) {
            pilot.sendOverlayMessage(Component.translatable(
                    "message.deepcharter.repair." + key, Mth.ceil(fuse.ticksLeft() / (double) TICKS_PER_SECOND)));
        }
    }

    /**
     * A blasting charge's blast that nothing set from a pod (a thrown charge, #985): the volume {@code low} to {@code high} (inclusive corners), hurting
     * each pod within {@code reach} of it that {@code hurts} accepts by {@code damage} less its distance share, and clearing rock by the pod's own rule:
     * only where the volume holds Company rock ({@link #hasCompanyRock}), as a pod's charge is not set anywhere else. {@code blaster} is the charter
     * that threw it, whose crew the cave-in reaches when PvP is off ({@link CaveIns#blasted}).
     */
    public static void detonate(
            ServerLevel level,
            BlockPos low,
            BlockPos high,
            float damage,
            double reach,
            Predicate<PodEntity> hurts,
            Optional<CharterId> blaster) {
        blast(
                level,
                new Fuse(Kind.BLASTING, new ChargeSite(low, high, Direction.DOWN), 0, 0, damage, reach, blaster),
                hasCompanyRock(level, low, high),
                hurts);
    }

    /** Whether the volume {@code low} to {@code high} holds Company rock in a loaded chunk: the rule that lets a blasting charge be set, or clear anything. */
    private static boolean hasCompanyRock(ServerLevel level, BlockPos low, BlockPos high) {
        LoadedBlocks blocks = new LoadedBlocks(level);
        return BlockPos.betweenClosedStream(low, high)
                .anyMatch(cell -> blocks.getBlockState(cell).is(HazardBlocks.UNDIGGABLE));
    }

    /** The share of a blast's damage that reaches a box: 1 touching the blast's volume, falling in a straight line to 0 at {@code reach} blocks from it. */
    public static double reachShare(AABB volume, AABB box, double reach) {
        return 1 - Math.sqrt(distanceSqr(volume, box)) / reach;
    }

    private static void blast(ServerLevel level, Fuse fuse, boolean clearRock, Predicate<PodEntity> hurts) {
        AABB volume = fuse.volume();
        Vec3 middle = volume.getCenter();
        // entity-query: the blast reaches chargeReach round a charge that a ticking pod set, so its chunks
        // are entity-ticking
        for (PodEntity pod : level.getEntitiesOfClass(PodEntity.class, volume.inflate(fuse.reach()))) {
            if (Wrecks.isWreck(pod) || !hurts.test(pod)) {
                continue;
            }
            double share = reachShare(volume, pod.getBoundingBox(), fuse.reach());
            if (share > 0) {
                pod.damageHull((float) (fuse.damage() * share));
            }
        }
        List<BlockPos> cells = clearRock ? cleared(level, new LoadedBlocks(level), fuse) : List.of();
        for (BlockPos cell : cells) {
            level.destroyBlock(cell, false);
        }
        level.sendParticles(ParticleTypes.EXPLOSION, middle.x, middle.y, middle.z, 6, 1.0, 0.5, 1.0, 0.0);
        level.playSound(null, middle.x, middle.y, middle.z, DeepSound.DRILL_PLASTIC.event(), SoundSource.PLAYERS);
        // The blast shakes the roof over anyone on foot near the rock it broke (#987, #1458); a blast that broke none
        // shakes from its volume.
        CaveIns.blasted(
                level, BoundingBox.encapsulatingPositions(cells).map(AABB::of).orElse(volume), fuse.blaster());
    }

    /** The cells this fuse's blast clears: a shaped charge's runs of Company rock, or a blasting charge's volume of rock that may go. */
    private static List<BlockPos> cleared(ServerLevel level, LoadedBlocks blocks, Fuse fuse) {
        List<BlockPos> cells = new ArrayList<>();
        ChargeSite site = fuse.site();
        if (fuse.kind() == Kind.SHAPED) {
            for (BlockPos start : site.faceCells()) {
                int run = runLength(level, blocks, start, site);
                for (int row = 0; row < run; row++) {
                    cells.add(start.relative(site.facing(), row));
                }
            }
        } else {
            for (BlockPos cell : BlockPos.betweenClosed(site.low(), site.high())) {
                if (clears(level, blocks, cell)) {
                    cells.add(cell.immutable());
                }
            }
        }
        return cells;
    }

    private static boolean clears(ServerLevel level, LoadedBlocks blocks, BlockPos cell) {
        if (!changeable(level, blocks, cell)) {
            return false;
        }
        var state = blocks.getBlockState(cell);
        return state.is(HazardBlocks.UNDIGGABLE) || state.is(HazardBlocks.NATURAL_ROCK);
    }

    /** Inside the world, under the ceiling row, and in a loaded chunk: where a blast may change a block at all. */
    private static boolean changeable(ServerLevel level, LoadedBlocks blocks, BlockPos cell) {
        return !level.isOutsideBuildHeight(cell) && cell.getY() < level.getMaxY() && blocks.canChange(cell);
    }

    /** Squared distance between two boxes, 0 when they touch or overlap. */
    private static double distanceSqr(AABB a, AABB b) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    private static Optional<Component> refusal(String reason) {
        return Optional.of(Component.translatable("message.deepcharter.repair." + reason));
    }
}
