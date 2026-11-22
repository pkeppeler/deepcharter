package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Ledger;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Pending;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Report;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Survey;
import io.github.pkeppeler.deepcharter.outpost.VisitBreaks.Broken;
import io.github.pkeppeler.deepcharter.pod.PodFuelItems;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Away settling (ADR 0079): creatures keep visiting an outpost nobody is near. A sweep over every outpost rolls its visits in windows of
 * game time from what the last survey of its loaded zone found ({@link VisitRoll}), holds them while a player is near, and meets them in
 * the world when the whole zone is loaded: fuel and ore taken from storage ({@link StorageTakes}), unrated blocks broken. Nothing is
 * ever force-loaded, and a visit that cannot be met yet waits in the ledger ({@link OutpostVisits}). Online members of the charter are
 * told when a visit is rolled ({@link VisitAlerts}); the report waits for them at the outpost's terminal ({@link IncidentReports}).
 */
public final class OutpostSettling {
    /** Blocks no visit ever touches: terminals (the lure never goes dark) and salvage heaps (what is left must stay). */
    public static final TagKey<Block> NEVER_SETTLED =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "never_settled"));
    /** The hall's spots, at the floor's height plus one, where block light says whether the hall is dark: the middle and four quadrants. */
    private static final int QUADRANT = 2;

    private static final Comparator<ItemStack> ANY_ORDER = (_, _) -> 0;
    private static final Comparator<ItemStack> DEAREST_ORE_FIRST = Comparator.comparingInt((ItemStack stack) ->
                    OreRegistry.typeOf(stack).map(OreType::value).orElse(0))
            .reversed();

    /** The outposts whose failure is logged already, so a sweep that fails again every time logs once. */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private OutpostSettling() {}

    /** Registers the sweep. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPED.register(_ -> FAILED.clear());
        ServerTickEvents.END_SERVER_TICK.register(OutpostSettling::sweep);
    }

    private static void sweep(MinecraftServer server) {
        if (server.getTickCount() % SettlingTuning.DEFAULT.sweepIntervalTicks() == 0) {
            settleAll(server);
        }
    }

    /**
     * Settles every outpost of the world now, as a sweep does. Does nothing while the outposts or their ledgers cannot be read, and never
     * throws: an outpost that fails is logged once, its pending visits are dropped and its windows marked settled, and the rest settle.
     * What the failed attempt already took or broke stays done and is not in the report.
     */
    public static void settleAll(MinecraftServer server) {
        SettlingTuning tuning = SettlingTuning.DEFAULT;
        OutpostData outposts = OutpostData.get(server);
        OutpostVisits visits = OutpostVisits.get(server);
        if (!outposts.isReadable() || !visits.isReadable()) {
            return;
        }
        for (Outpost outpost : outposts.all()) {
            ServerLevel level = server.getLevel(outpost.dimension());
            if (level == null) {
                continue;
            }
            long now = level.getGameTime();
            long window = now / tuning.windowTicks();
            Ledger ledger = visits.ledgerOf(outpost.charter(), outpost.layer(), window);
            try {
                visits.put(settle(level, outpost, ledger, now, tuning));
            } catch (RuntimeException e) {
                // Dropped, not retried: the failure repeats for this outpost, and a retry would take fuel or break
                // blocks again every sweep. What the attempt applied stays applied and is not in the report.
                visits.put(ledger.withPending(Pending.NONE).withSettledTo(Math.max(ledger.settledTo(), window)));
                if (FAILED.add(outpost.charter().toString() + "/" + outpost.layer())) {
                    DeepCharter.LOGGER.error(
                            "Away settling failed for the outpost of charter {} in layer {}; its visits are dropped",
                            outpost.charter(),
                            outpost.layer(),
                            e);
                }
            }
        }
    }

    /**
     * Brings one outpost's ledger up to game time {@code now}: surveys the zone if it is loaded, rolls the windows since the ledger was
     * settled (none while a player is near), and meets the pending visits if the zone is loaded. Returns the new ledger; the caller keeps it.
     */
    public static Ledger settle(ServerLevel level, Outpost outpost, Ledger ledger, long now, SettlingTuning tuning) {
        long nowWindow = now / tuning.windowTicks();
        BoundingBox zone = zone(outpost, tuning);
        boolean loaded = isLoaded(level, zone);
        Ledger surveyed = loaded ? ledger.withSurvey(survey(level, outpost, zone)) : ledger;
        Ledger settled = surveyed;
        int rolled = 0;
        if (playerNear(level, outpost, tuning)) {
            settled = surveyed.withSettledTo(Math.max(surveyed.settledTo(), nowWindow));
        } else if (surveyed.settledTo() < nowWindow) {
            long to = Math.min(nowWindow, surveyed.settledTo() + tuning.maxWindowsPerSweep());
            Pending visits = surveyed.survey()
                    .map(draws -> VisitRoll.roll(
                            seed(level, outpost),
                            surveyed.settledTo(),
                            to,
                            draws,
                            tuning.ratesOf(outpost.layer()),
                            tuning))
                    .orElse(Pending.NONE);
            rolled = visits.total();
            settled = surveyed.withSettledTo(to).withPending(surveyed.pending().plus(visits, tuning.pendingCap()));
        }
        if (loaded && !settled.pending().isEmpty()) {
            settled = meet(level, outpost, zone, settled, now, tuning);
        }
        if (rolled > 0) {
            VisitAlerts.tell(level.getServer(), outpost);
        }
        return settled;
    }

    /** The hall's box grown by the visit's reach. */
    public static BoundingBox zone(Outpost outpost, SettlingTuning tuning) {
        BlockPos centre = outpost.centre();
        int out = OutpostBuilder.EDGE + tuning.reachOut();
        return new BoundingBox(
                centre.getX() - out,
                centre.getY() - tuning.reachDown(),
                centre.getZ() - out,
                centre.getX() + out,
                centre.getY() + OutpostBuilder.HEIGHT + 1 + tuning.reachUp(),
                centre.getZ() + out);
    }

    /** True when every chunk of the zone is loaded. Never loads one. */
    public static boolean isLoaded(ServerLevel level, BoundingBox zone) {
        for (int chunkX = zone.minX() >> 4; chunkX <= zone.maxX() >> 4; chunkX++) {
            for (int chunkZ = zone.minZ() >> 4; chunkZ <= zone.maxZ() >> 4; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long seed(ServerLevel level, Outpost outpost) {
        return VisitRoll.outpostSeed(level.getSeed(), outpost.charter(), outpost.layer());
    }

    /** True when a player who is not a spectator is within the near distance of the outpost's middle, in its level. */
    private static boolean playerNear(ServerLevel level, Outpost outpost, SettlingTuning tuning) {
        double near = (double) tuning.nearBlocks() * tuning.nearBlocks();
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(Vec3.atCenterOf(outpost.centre())) <= near) {
                return true;
            }
        }
        return false;
    }

    /** What the outpost has to lose, read from its loaded zone. */
    private static Survey survey(ServerLevel level, Outpost outpost, BoundingBox zone) {
        BlockPos spot = outpost.centre().above();
        boolean dark = false;
        for (int dx : new int[] {-QUADRANT, QUADRANT}) {
            for (int dz : new int[] {-QUADRANT, QUADRANT}) {
                dark |= isDark(level, spot.offset(dx, 0, dz));
            }
        }
        dark |= isDark(level, spot);
        return new Survey(
                dark,
                terminals(level, zone),
                StorageTakes.count(level, zone, OutpostSettling::isFuel),
                StorageTakes.count(level, zone, OutpostSettling::isOre),
                VisitBreaks.candidates(level, outpost, zone, OutpostSettling::holds)
                        .size());
    }

    /**
     * True when the depth the outpost stands at rates {@code state} enough that a visit leaves it alone. The ratings are outpost ratings
     * (#989, PR #991); wired in #1005. Until then every block counts as held, so no visit breaks anything, which changes nothing while
     * the Sleeper's rate is 0.
     */
    private static boolean holds(BlockState state) {
        return true;
    }

    private static boolean isDark(ServerLevel level, BlockPos pos) {
        return level.getBrightness(LightLayer.BLOCK, pos) < CreatureTuning.DEFAULT.litBlockLight();
    }

    private static boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && stack.is(PodFuelItems.TAG);
    }

    private static boolean isOre(ItemStack stack) {
        return !stack.isEmpty() && OreRegistry.typeOf(stack).isPresent();
    }

    /** The terminals in the zone, a placeholder for how loud the outpost is until the Sleeper's draws are built (#1005). */
    private static int terminals(ServerLevel level, BoundingBox zone) {
        return (int) blockEntities(level, zone).stream()
                .filter(entity -> entity instanceof TerminalBlockEntity)
                .count();
    }

    /** The block entities inside {@code zone} whose chunk is loaded. */
    static List<BlockEntity> blockEntities(ServerLevel level, BoundingBox zone) {
        List<BlockEntity> found = new ArrayList<>();
        for (int chunkX = zone.minX() >> 4; chunkX <= zone.maxX() >> 4; chunkX++) {
            for (int chunkZ = zone.minZ() >> 4; chunkZ <= zone.maxZ() >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (zone.isInside(entity.getBlockPos())) {
                        found.add(entity);
                    }
                }
            }
        }
        return found;
    }

    /** Meets every pending visit in the world, adds what they did to the unread report, and takes a fresh survey. */
    private static Ledger meet(
            ServerLevel level, Outpost outpost, BoundingBox zone, Ledger ledger, long now, SettlingTuning tuning) {
        RandomSource random = RandomSource.create(VisitRoll.mix(seed(level, outpost), now, tuning.windowTicks()));
        Pending pending = ledger.pending();
        Map<Identifier, Integer> taken = new LinkedHashMap<>();
        int fuelItems = ledger.survey().map(Survey::fuelItems).orElse(0);
        for (int visit = 0; visit < pending.retained(); visit++) {
            int most = Math.clamp((long) Math.ceil(tuning.fuelShare() * fuelItems), 1, tuning.maxFuelItemsPerVisit());
            int took = record(taken, StorageTakes.take(level, zone, OutpostSettling::isFuel, ANY_ORDER, most, random));
            if (took == 0) {
                break;
            }
            fuelItems -= took;
        }
        for (int visit = 0; visit < pending.recovery(); visit++) {
            int took = record(
                    taken,
                    StorageTakes.take(
                            level, zone, OutpostSettling::isOre, DEAREST_ORE_FIRST, tuning.maxOrePerVisit(), random));
            if (took == 0) {
                break;
            }
        }
        Broken broken = VisitBreaks.apply(
                level, outpost, zone, OutpostSettling::holds, pending.sleepers(), tuning.maxBlocksPerVisit(), random);
        broken.changed()
                .ifPresent(box -> LightSync.holdSendUntilLit(
                        level,
                        new BlockPos(box.minX(), box.minY(), box.minZ()),
                        new BlockPos(box.maxX(), box.maxY(), box.maxZ())));
        Report done = new Report(
                pending.total(),
                taken,
                broken.lost(),
                broken.heaps(),
                pending.retained() > 0 ? Set.of(VisitCause.DARK) : Set.of());
        return ledger.withPending(Pending.NONE)
                .withUnread(ledger.unread().plus(done))
                .withSurvey(survey(level, outpost, zone));
    }

    /** Adds {@code took} to {@code into}, by item id, and returns how many items it holds. */
    private static int record(Map<Identifier, Integer> into, Map<Item, Integer> took) {
        int total = 0;
        for (Map.Entry<Item, Integer> entry : took.entrySet()) {
            into.merge(BuiltInRegistries.ITEM.getKey(entry.getKey()), entry.getValue(), Integer::sum);
            total += entry.getValue();
        }
        return total;
    }
}
