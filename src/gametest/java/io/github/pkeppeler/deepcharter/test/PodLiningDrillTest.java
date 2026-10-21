package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for what the drill does with hand lining (#313), in layer 1: it keeps the waste rock it bores as spoil when the pod has a hopper (and none without), up to the
 * bay's size and never the ore or the air, and it stops while the pilot lines. Each test has its own X, so the blocks it changes
 * never touch another test's.
 */
public class PodLiningDrillTest {
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1000;
    private static final int Z = 3300;
    private static final int FLOOR = 60;
    private static final int RADIUS = 4;
    private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

    /** A mock pilot, and the pod it sits in once the far chunk ticks entities. */
    private static final class Rig {
        private final MockPlayer pilot;
        private final Arrival<PodEntity> pod = new Arrival<>("pod", "the far chunk ticks");

        private Rig(MockPlayer pilot) {
            this.pilot = pilot;
        }

        static Rig await(
                GameTestHelper helper,
                ServerLevel level,
                int x,
                String name,
                BiConsumer<PodEntity, MockPlayer> prepare) {
            Vec3 at = new Vec3(x, FLOOR, Z);
            MockPlayer pilot = MockPlayers.join(helper, name);
            pilot.teleportTo(level, at, 0f, 0f);
            Rig rig = new Rig(pilot);
            FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(at);
                level.addFreshEntity(pod);
                if (!pilot.player().startRiding(pod)) {
                    throw failure(helper, "the pilot could not mount the pod");
                }
                prepare.accept(pod, pilot);
                pilot.setInput(SPRINT);
                rig.pod.set(pod);
            });
            return rig;
        }
    }

    /** A stone bed under an open room; the pod stands on the bed at the room's middle. */
    private static void room(ServerLevel level, int x) {
        RoomCarver.carve(level, x - RADIUS, x + RADIUS + 1, FLOOR - 8, FLOOR - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
        RoomCarver.carve(level, x - RADIUS, x + RADIUS + 1, FLOOR, FLOOR + 10, Z - RADIUS, Z + RADIUS, Blocks.AIR);
    }

    private static boolean slabBored(ServerLevel level, int x) {
        for (int bx = x - 1; bx <= x; bx++) {
            for (int bz = Z - 1; bz <= Z; bz++) {
                if (!level.getBlockState(new BlockPos(bx, FLOOR - 1, bz)).isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBoredSlabOfStoneAndOreKeepsTheOreAsCargoAndTheStoneAsSpoil(GameTestHelper helper) {
        int x = 3700;
        ServerLevel level = TestLayers.level(helper, 1);
        room(level, x);
        level.setBlock(
                new BlockPos(x - 1, FLOOR - 1, Z - 1),
                OreBlocks.of(OreType.IRONIUM).defaultBlockState(),
                3);
        Rig rig = Rig.await(helper, level, x, "spoil-ore", (pod, pilot) -> fitHopper(helper, pod, pilot));
        TestTicks.each(helper, () -> {
            if (!rig.pod.isPresent() || !slabBored(level, x)) {
                return;
            }
            rig.pilot.releaseInput();
            List<Item> kept = rig.pod.get().cargo().entries().stream()
                    .map(entry -> entry.stack().getItem())
                    .toList();
            if (PodLining.of(rig.pod.get()).spoil() != 3
                    || kept.size() != 1
                    || !kept.contains(OreRegistry.item(OreType.IRONIUM))) {
                throw failure(
                        helper,
                        "one ore and three stone make one cargo and 3 spoil, not %s cargo and %s spoil",
                        kept,
                        PodLining.of(rig.pod.get()).spoil());
            }
            rig.pod.get().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPodWithoutTheHopperKeepsNoSpoilAndStillKeepsOre(GameTestHelper helper) {
        int x = 3892;
        ServerLevel level = TestLayers.level(helper, 1);
        room(level, x);
        level.setBlock(
                new BlockPos(x - 1, FLOOR - 1, Z - 1),
                OreBlocks.of(OreType.IRONIUM).defaultBlockState(),
                3);
        Rig rig = Rig.await(helper, level, x, "spoil-none", (pod, pilot) -> {});
        TestTicks.each(helper, () -> {
            if (!rig.pod.isPresent() || !slabBored(level, x)) {
                return;
            }
            rig.pilot.releaseInput();
            if (PodLining.of(rig.pod.get()).spoil() != 0
                    || rig.pod.get().cargo().entries().size() != 1) {
                throw failure(
                        helper,
                        "a pod with no hopper destroys the stone as before and keeps the ore, it holds %s spoil and %s cargo",
                        PodLining.of(rig.pod.get()).spoil(),
                        rig.pod.get().cargo().entries().size());
            }
            rig.pod.get().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aFullBayLosesTheRockAndDrillingGoesOn(GameTestHelper helper) {
        int x = 3764;
        ServerLevel level = TestLayers.level(helper, 1);
        room(level, x);
        Rig rig = Rig.await(helper, level, x, "spoil-full", (pod, pilot) -> {
            fitHopper(helper, pod, pilot);
            PodLining.modify(pod, state -> state.withSpoil(62));
        });
        TestTicks.each(helper, () -> {
            if (!rig.pod.isPresent() || !slabBored(level, x)) {
                return;
            }
            rig.pilot.releaseInput();
            if (PodLining.of(rig.pod.get()).spoil() != 64) {
                throw failure(
                        helper,
                        "a bay with room for two keeps two of the four stone and loses the rest, it holds %s",
                        PodLining.of(rig.pod.get()).spoil());
            }
            rig.pod.get().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS + 400)
    public void theDrillWaitsWhileThePilotLinesAndGoesOnAfter(GameTestHelper helper) {
        int x = 3828;
        ServerLevel level = TestLayers.level(helper, 1);
        room(level, x);
        // The pod stands in an open room, so its ring is open at the pod's own two levels: 8 cells each, 16 bricks.
        Rig rig = Rig.await(helper, level, x, "line-and-drill", (pod, pilot) -> {
            PodLining.modify(pod, state -> new PodLining.State(0, 16, 0, false, false));
            PodLining.toggle(pilot.player());
            if (!PodLining.working(pod)) {
                throw failure(helper, "the press should have started the lining");
            }
        });
        TestTicks.each(helper, () -> {
            if (!rig.pod.isPresent()) {
                return;
            }
            if (PodLining.working(rig.pod.get())) {
                if (rig.pod.get().drilling()
                        || slabBored(level, x)
                        || level.getBlockState(new BlockPos(x - 1, FLOOR - 1, Z))
                                .isAir()) {
                    throw failure(helper, "the drill must not run while the pilot lines, with sprint held");
                }
                return;
            }
            if (PodLining.of(rig.pod.get()).used() != 16) {
                throw failure(
                        helper,
                        "the lining should have placed all 16 bricks, it placed %s",
                        PodLining.of(rig.pod.get()).used());
            }
            if (slabBored(level, x)) {
                rig.pilot.releaseInput();
                rig.pod.get().discard();
                helper.succeed();
            }
        });
    }

    /** A spoil hopper on the pod, which the pilot's charter owns. */
    private static void fitHopper(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
        ScannerPods.fit(helper.getLevel().getServer(), pilot.player(), pod, ComponentTrack.SPOIL_HOPPER, 1);
    }

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
