package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.function.BiConsumer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

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

	/** A mock pilot, and the pod it sits in once the far chunk ticks entities; {@link #pod} is null until then. */
	private static final class Rig {
		private final MockPlayer pilot;
		private PodEntity pod;

		private Rig(MockPlayer pilot) {
			this.pilot = pilot;
		}

		boolean ready() {
			return pod != null;
		}

		static Rig await(GameTestHelper helper, ServerLevel level, int x, String name, BiConsumer<PodEntity, MockPlayer> prepare) {
			Vec3 at = new Vec3(x, FLOOR, Z);
			MockPlayer pilot = MockPlayers.join(helper, name);
			pilot.teleportTo(level, at, 0f, 0f);
			Rig rig = new Rig(pilot);
			FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
				PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod.setPos(at);
				level.addFreshEntity(pod);
				if (!pilot.player().startRiding(pod)) {
					throw failure(helper, "the pilot could not mount the pod");
				}
				prepare.accept(pod, pilot);
				pilot.setInput(SPRINT);
				rig.pod = pod;
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
		ServerLevel level = layer(helper);
		room(level, x);
		level.setBlock(new BlockPos(x - 1, FLOOR - 1, Z - 1), OreRegistry.block(OreType.IRONIUM).defaultBlockState(), 3);
		Rig rig = Rig.await(helper, level, x, "spoil-ore", (pod, pilot) -> fitHopper(helper, pod, pilot));
		helper.onEachTick(() -> {
			if (!rig.ready() || !slabBored(level, x)) {
				return;
			}
			rig.pilot.releaseInput();
			List<Item> kept = rig.pod.cargo().entries().stream().map(entry -> entry.stack().getItem()).toList();
			if (PodLining.of(rig.pod).spoil() != 3 || kept.size() != 1 || !kept.contains(OreRegistry.item(OreType.IRONIUM))) {
				throw failure(helper, "one ore and three stone make one cargo and 3 spoil, not %s cargo and %s spoil",
						kept, PodLining.of(rig.pod).spoil());
			}
			rig.pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aPodWithoutTheHopperKeepsNoSpoilAndStillKeepsOre(GameTestHelper helper) {
		int x = 3892;
		ServerLevel level = layer(helper);
		room(level, x);
		level.setBlock(new BlockPos(x - 1, FLOOR - 1, Z - 1), OreRegistry.block(OreType.IRONIUM).defaultBlockState(), 3);
		Rig rig = Rig.await(helper, level, x, "spoil-none", (pod, pilot) -> {
		});
		helper.onEachTick(() -> {
			if (!rig.ready() || !slabBored(level, x)) {
				return;
			}
			rig.pilot.releaseInput();
			if (PodLining.of(rig.pod).spoil() != 0 || rig.pod.cargo().entries().size() != 1) {
				throw failure(helper, "a pod with no hopper destroys the stone as before and keeps the ore, it holds %s spoil and %s cargo",
						PodLining.of(rig.pod).spoil(), rig.pod.cargo().entries().size());
			}
			rig.pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aFullBayLosesTheRockAndDrillingGoesOn(GameTestHelper helper) {
		int x = 3764;
		ServerLevel level = layer(helper);
		room(level, x);
		Rig rig = Rig.await(helper, level, x, "spoil-full", (pod, pilot) -> {
			fitHopper(helper, pod, pilot);
			PodLining.modify(pod, state -> state.withSpoil(62));
		});
		helper.onEachTick(() -> {
			if (!rig.ready() || !slabBored(level, x)) {
				return;
			}
			rig.pilot.releaseInput();
			if (PodLining.of(rig.pod).spoil() != 64) {
				throw failure(helper, "a bay with room for two keeps two of the four stone and loses the rest, it holds %s", PodLining.of(rig.pod).spoil());
			}
			rig.pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = MAX_TICKS + 400)
	public void theDrillWaitsWhileThePilotLinesAndGoesOnAfter(GameTestHelper helper) {
		int x = 3828;
		ServerLevel level = layer(helper);
		room(level, x);
		// The pod stands in an open room, so its ring is open at the pod's own two levels: 8 cells each, 16 bricks.
		Rig rig = Rig.await(helper, level, x, "line-and-drill", (pod, pilot) -> {
			PodLining.modify(pod, state -> new PodLining.State(0, 16, 0, false, false));
			PodLining.toggle(pilot.player());
			if (!PodLining.working(pod)) {
				throw failure(helper, "the press should have started the lining");
			}
		});
		helper.onEachTick(() -> {
			if (!rig.ready()) {
				return;
			}
			if (PodLining.working(rig.pod)) {
				if (rig.pod.drilling() || slabBored(level, x) || level.getBlockState(new BlockPos(x - 1, FLOOR - 1, Z)).isAir()) {
					throw failure(helper, "the drill must not run while the pilot lines, with sprint held");
				}
				return;
			}
			if (PodLining.of(rig.pod).used() != 16) {
				throw failure(helper, "the lining should have placed all 16 bricks, it placed %s", PodLining.of(rig.pod).used());
			}
			if (slabBored(level, x)) {
				rig.pilot.releaseInput();
				rig.pod.discard();
				helper.succeed();
			}
		});
	}

	/** A spoil hopper on the pod, which the pilot's charter owns. */
	private static void fitHopper(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
		ScannerPods.fit(helper.getLevel().getServer(), pilot.player(), pod, ComponentTrack.SPOIL_HOPPER, 1);
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static ServerLevel layer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(1));
		}
		return level;
	}
}
