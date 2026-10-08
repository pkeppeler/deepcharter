package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.PodTowing.Refusal;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #76, the tow cable: a cable reaches 8 blocks, the towed pod trails its tower and passes through blocks,
 * its mass cuts the tower's lift, the link is saved, anyone may tow, and the towed pod crosses a breach with its tower.
 */
public class TowingTest {
	private static final String ATTACHMENTS_KEY = "fabric:attachments";
	private static final int FLOOR_Y = 1;
	private static final int FLOOR_RADIUS = 3;
	private static final double EPSILON = 1e-6;
	/** Ticks a pod needs, once its chunk ticks, to fall through the shaft and cross. */
	private static final int CROSSING_TICKS = 200;
	/** Entities BreachEvents.CROSSED has fired for. */
	private static final Set<UUID> CROSSED = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger CHARTERS = new AtomicInteger();
	/** A block of rock fills x 1..5, y 2..5, z 1..4 (see fillRock). The pod sits in it and its tower beyond its south face, in the open. */
	private static final Vec3 ROCK_POD = new Vec3(3.5, 3, 3.5);
	private static final Vec3 ROCK_TOWER = new Vec3(3.5, 3, 6.0);
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);

	static {
		BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> CROSSED.add(entity.getUUID()));
	}

	@GameTest
	public void aCableReachesEightBlocksAndNoFurther(GameTestHelper helper) {
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity atEight = helper.spawn(PodRegistry.POD, new Vec3(9.5, 2, 1.5));
		PodEntity pastEight = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 10.0));
		try {
			if (!Optional.of(Refusal.TOO_FAR).equals(PodTowing.refusal(tower, pastEight))) {
				throw failure(helper, "a pod 8.5 blocks away should be out of reach, got %s", PodTowing.refusal(tower, pastEight));
			}
			try {
				PodTowing.attach(tower, pastEight);
				throw failure(helper, "attaching out of reach must throw");
			} catch (IllegalStateException expected) {
				// Expected.
			}
			if (PodTowing.isTowed(pastEight)) {
				throw failure(helper, "a refused cable must leave no link");
			}
			if (PodTowing.refusal(tower, atEight).isPresent()) {
				throw failure(helper, "a pod exactly 8 blocks away is in reach, got %s", PodTowing.refusal(tower, atEight));
			}
			PodTowing.attach(tower, atEight);
			if (!Optional.of(tower.getUUID()).equals(PodTowing.towerId(atEight)) || PodTowing.isTowed(tower)) {
				throw failure(helper, "the pod in reach should be towed by the tower, and not the reverse");
			}
			helper.succeed();
		} finally {
			tower.discard();
			atEight.discard();
			pastEight.discard();
		}
	}

	@GameTest
	public void aPodCannotBeTowedTwiceOrTowItselfOrFormAChain(GameTestHelper helper) {
		PodEntity a = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity b = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
		PodEntity c = helper.spawn(PodRegistry.POD, new Vec3(4.5, 2, 1.5));
		try {
			expectRefusal(helper, a, a, Refusal.SAME_POD);
			if (PodTowing.detach(b)) {
				throw failure(helper, "detaching a pod that is not towed should report that nothing was detached");
			}
			PodTowing.attach(a, b);
			expectRefusal(helper, a, c, Refusal.ALREADY_TOWING);
			expectRefusal(helper, c, b, Refusal.ALREADY_TOWED);
			expectRefusal(helper, b, c, Refusal.TOWER_IS_TOWED);
			expectRefusal(helper, c, a, Refusal.TOWED_TOWS);
			if (!PodTowing.detach(b) || PodTowing.isTowed(b)) {
				throw failure(helper, "detaching a towed pod should free it");
			}
			PodTowing.attach(c, b);
			if (!Optional.of(c.getUUID()).equals(PodTowing.towerId(b))) {
				throw failure(helper, "a freed pod can be towed by another tower, got %s", PodTowing.towerId(b));
			}
			helper.succeed();
		} finally {
			a.discard();
			b.discard();
			c.discard();
		}
	}

	@GameTest(maxTicks = 100)
	public void aTowedPodTrailsItsTowerWithoutFallingOrTakingDamage(GameTestHelper helper) {
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(4.5, 4, 4.5));
		PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(3.5, 4, 4.5));
		Vec3 towerStart = tower.position();
		Vec3 towedStart = towed.position();
		float hullStart = towed.hull();
		PodTowing.attach(tower, towed);
		int[] step = {0};
		helper.onEachTick(() -> {
			// The tower is pinned in the air: it walks 6 blocks east and then stays, so the towed pod's sinking could only be its own.
			step[0] = Math.min(step[0] + 1, 24);
			tower.setPos(towerStart.x + 0.25 * step[0], towerStart.y, towerStart.z);
			tower.setDeltaMovement(Vec3.ZERO);
		});
		helper.runAfterDelay(40, () -> {
			try {
				double trail = TowTuning.DEFAULT.trailDistance();
				double gap = tower.position().distanceTo(towed.position());
				if (gap > trail + 0.5) {
					throw failure(helper, "the towed pod should trail within %s blocks, it is %s away", trail, gap);
				}
				if (towed.getX() - towedStart.x < 3.0) {
					throw failure(helper, "the towed pod should have followed 6 blocks east, it moved from %s to %s", towedStart, towed.position());
				}
				if (Math.abs(towed.getY() - towedStart.y) > 0.3) {
					throw failure(helper, "the towed pod should not sink while towed, it went from %s to %s", towedStart.y, towed.getY());
				}
				if (towed.hull() != hullStart) {
					throw failure(helper, "a towed pod takes no damage, hull went from %s to %s", hullStart, towed.hull());
				}
				helper.succeed();
			} finally {
				tower.discard();
				towed.discard();
			}
		});
	}

	@GameTest
	public void aTowedPodPassesThroughBlocksUntilItIsFreed(GameTestHelper helper) {
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(3.5, 3, 3.5));
		PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(3.5, 3, 5.0));
		PodTowing.attach(tower, towed);
		helper.runAfterDelay(3, () -> {
			if (!towed.noPhysics || !PodEvents.ignoresBlockCollision(towed) || tower.noPhysics || PodEvents.ignoresBlockCollision(tower)) {
				tower.discard();
				towed.discard();
				throw failure(helper, "only the towed pod should ignore blocks, towed %s, tower %s", towed.noPhysics, tower.noPhysics);
			}
			PodTowing.detach(towed);
			helper.runAfterDelay(3, () -> {
				try {
					if (towed.noPhysics || PodEvents.ignoresBlockCollision(towed)) {
						throw failure(helper, "a freed pod collides with blocks again");
					}
					helper.succeed();
				} finally {
					tower.discard();
					towed.discard();
				}
			});
		});
	}

	@GameTest
	public void aPodWhoseTowerIsGoneIsFreeButKeepsItsLink(GameTestHelper helper) {
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(3.5, 3, 3.5));
		PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(3.5, 3, 5.0));
		UUID towerId = tower.getUUID();
		PodTowing.attach(tower, towed);
		tower.discard();
		helper.runAfterDelay(3, () -> {
			try {
				if (towed.noPhysics) {
					throw failure(helper, "a pod with no tower in the world has nothing to pass blocks for");
				}
				if (!Optional.of(towerId).equals(PodTowing.towerId(towed))) {
					throw failure(helper, "the link should persist while the tower is away, got %s", PodTowing.towerId(towed));
				}
				helper.succeed();
			} finally {
				towed.discard();
			}
		});
	}

	@GameTest(maxTicks = 100)
	public void aTowedPodsMassCutsTheTowersLift(GameTestHelper helper) {
		fillFloor(helper, Blocks.STONE);
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5));
		PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 2.4));
		MockPlayer pilot = MockPlayers.join(helper, "tow-pilot");
		pilot.teleportTo(helper.getLevel(), tower.position(), 0f, 0f);
		if (!pilot.player().startRiding(tower)) {
			throw failure(helper, "the pilot could not mount the tower");
		}
		if (PodEvents.extraMass(tower) != 0f) {
			throw failure(helper, "a pod that tows nothing adds no mass, got %s", PodEvents.extraMass(tower));
		}
		PodTowing.attach(tower, towed);
		float empty = PodEvents.extraMass(tower);
		towed.setCargoMass(80f);
		float loaded = PodEvents.extraMass(tower);
		if (empty <= 0f || loaded - empty != 80f) {
			throw failure(helper, "the towed pod should weigh something on its own and add its cargo mass, got %s empty and %s loaded", empty, loaded);
		}
		double startY = tower.getY();
		pilot.setInput(JUMP);
		helper.runAfterDelay(10, () -> {
			if (tower.getY() - startY > EPSILON || tower.flying()) {
				cleanUp(helper, tower, towed, pilot);
				throw failure(helper, "a tower dragging more than its engine power should not lift, rose %s", tower.getY() - startY);
			}
			PodTowing.detach(towed);
			helper.runAfterDelay(15, () -> {
				try {
					if (tower.getY() - startY < 1.0) {
						throw failure(helper, "once the pod is let go the tower should climb again, rose %s", tower.getY() - startY);
					}
					helper.succeed();
				} finally {
					cleanUp(helper, tower, towed, pilot);
				}
			});
		});
	}

	@GameTest
	public void theLinkSurvivesSaveAndLoad(GameTestHelper helper) {
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
		PodTowing.attach(tower, towed);
		Entity loaded = reload(helper, towed, null);
		try {
			if (!(loaded instanceof PodEntity pod) || !Optional.of(tower.getUUID()).equals(PodTowing.towerId(pod))) {
				throw failure(helper, "the cable should survive save and load, got %s", loaded);
			}
			helper.succeed();
		} finally {
			loaded.discard();
			tower.discard();
		}
	}

	/**
	 * Saved towing data of a version this build cannot read: the pod loads, ticks and syncs as a pod that is not towed, nothing
	 * on a tick path throws, the data is logged once and written back unchanged, and only an explicit change throws.
	 */
	@GameTest
	public void anUnreadableLinkIsKeptAndNeverBreaksATick(GameTestHelper helper) {
		String key = PodTowing.STATE.identifier().toString();
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putString("cable", "from the future");
		CompoundTag attachments = new CompoundTag();
		attachments.put(key, future);

		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity original = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
		Entity loaded = reload(helper, original, attachments);
		helper.getLevel().addFreshEntity(loaded);
		PodEntity towed = (PodEntity) loaded;
		LogCapture logged = LogCapture.start(towed.getUUID().toString());
		helper.runAfterDelay(10, () -> {
			try {
				if (!(towed.getAttached(PodTowing.STATE) instanceof Versioned.Unreadable<PodTowing.State>)) {
					throw failure(helper, "version 99 should load as unreadable, got %s", towed.getAttached(PodTowing.STATE));
				}
				if (PodTowing.isTowed(towed) || PodEvents.ignoresBlockCollision(towed) || PodEvents.extraMass(tower) != 0f) {
					throw failure(helper, "an unreadable link must read as no link");
				}
				if (!Optional.of(Refusal.UNREADABLE).equals(PodTowing.refusal(tower, towed))) {
					throw failure(helper, "a cable must not be fitted over unreadable data, got %s", PodTowing.refusal(tower, towed));
				}
				try {
					PodTowing.detach(towed);
					throw failure(helper, "detaching an unreadable link must throw rather than overwrite it");
				} catch (IllegalStateException expected) {
					if (!expected.getMessage().contains(key)) {
						throw failure(helper, "the failure should name the attachment, got: %s", expected.getMessage());
					}
				}
				if (logged.errors().size() != 1) {
					throw failure(helper, "the unreadable pod should be logged once, not once for each tick: %s", logged.errors());
				}
				TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
				towed.saveWithoutId(output);
				if (!future.equals(output.buildResult().getCompound(ATTACHMENTS_KEY).orElseThrow().get(key))) {
					throw failure(helper, "the unreadable data should be written back unchanged");
				}
				helper.succeed();
			} finally {
				towed.discard();
				tower.discard();
			}
		});
	}

	@GameTest
	public void anOutsiderCannotTowAPodOfAnotherCharter(GameTestHelper helper) {
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity owned = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
		MockPlayer outsider = MockPlayers.join(helper, "tow-outsider-" + CHARTERS.incrementAndGet());
		PodComponents.register(owned, someCharter(helper));
		outsider.teleportTo(helper.getLevel(), tower.position(), 0f, 0f);
		if (!outsider.player().startRiding(tower)) {
			throw failure(helper, "the outsider could not mount the unowned tower");
		}
		outsider.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
		if (PodComponents.mayAccess(owned, Optional.empty())) {
			throw failure(helper, "the test needs a pod the outsider may not access");
		}
		if (use(outsider.player(), owned).consumesAction()) {
			throw failure(helper, "a refused cable must not count as used");
		}
		helper.runAfterDelay(2, () -> {
			try {
				if (PodTowing.isTowed(owned)) {
					throw failure(helper, "a player of no charter must not tow another charter's pod, got %s", PodTowing.towerId(owned));
				}
				if (!List.of(Refusal.NOT_ALLOWED_TO_TOW.message()).equals(outsider.actionBarMessages())) {
					throw failure(helper, "the outsider should be told why, got %s", outsider.actionBarMessages());
				}
				helper.succeed();
			} finally {
				outsider.leave();
				tower.discard();
				owned.discard();
			}
		});
	}

	@GameTest
	public void aPodOfAGoneCharterCanBeTowedByAnyone(GameTestHelper helper) {
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity orphan = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
		MockPlayer outsider = MockPlayers.join(helper, "tow-orphan-" + CHARTERS.incrementAndGet());
		try {
			PodComponents.register(orphan, CharterId.random());
			outsider.teleportTo(helper.getLevel(), tower.position(), 0f, 0f);
			if (!outsider.player().startRiding(tower)) {
				throw failure(helper, "the outsider could not mount the tower");
			}
			outsider.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
			use(outsider.player(), orphan);
			if (!Optional.of(tower.getUUID()).equals(PodTowing.towerId(orphan))) {
				throw failure(helper, "a pod whose owner charter is gone is anyone's to tow, got %s", PodTowing.towerId(orphan));
			}
			helper.succeed();
		} finally {
			outsider.leave();
			tower.discard();
			orphan.discard();
		}
	}

	@GameTest
	public void onlyTheOwnerOrTheTowersCrewMayFreeATowedPod(GameTestHelper helper) {
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
		MockPlayer owner = MockPlayers.join(helper, "tow-owner-" + CHARTERS.incrementAndGet());
		MockPlayer crew = MockPlayers.join(helper, "tow-crew-" + CHARTERS.incrementAndGet());
		MockPlayer stranger = MockPlayers.join(helper, "tow-stranger-" + CHARTERS.incrementAndGet());
		try {
			PodComponents.register(towed, charterOf(helper, owner));
			PodComponents.register(tower, charterOf(helper, crew));
			for (MockPlayer player : List.of(owner, crew, stranger)) {
				player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
			}
			PodTowing.attach(tower, towed);
			use(stranger.player(), towed);
			if (!PodTowing.isTowed(towed)) {
				throw failure(helper, "a stranger to both pods must not free the towed pod");
			}
			use(owner.player(), towed);
			if (PodTowing.isTowed(towed)) {
				throw failure(helper, "the owner should free a pod someone else towed");
			}
			PodTowing.attach(tower, towed);
			use(crew.player(), towed);
			if (PodTowing.isTowed(towed)) {
				throw failure(helper, "the tower's crew should free the pod it tows");
			}
			helper.runAfterDelay(2, () -> {
				try {
					if (!List.of(Refusal.NOT_ALLOWED_TO_FREE.message()).equals(stranger.actionBarMessages())) {
						throw failure(helper, "the stranger should be told why, got %s", stranger.actionBarMessages());
					}
					helper.succeed();
				} finally {
					owner.leave();
					crew.leave();
					stranger.leave();
					tower.discard();
					towed.discard();
				}
			});
		} catch (RuntimeException failed) {
			owner.leave();
			crew.leave();
			stranger.leave();
			tower.discard();
			towed.discard();
			throw failed;
		}
	}

	@GameTest
	public void aPodFreedInRockIsMovedIntoOpenSpaceTowardItsTower(GameTestHelper helper) {
		fillRock(helper, Blocks.STONE);
		PodEntity tower = helper.spawn(PodRegistry.POD, ROCK_TOWER);
		PodEntity towed = helper.spawn(PodRegistry.POD, ROCK_POD);
		Vec3 towerAt = tower.position();
		Vec3 inRock = towed.position();
		MockPlayer rider = MockPlayers.join(helper, "tow-rock-rider-" + CHARTERS.incrementAndGet());
		rider.teleportTo(helper.getLevel(), towed.position(), 0f, 0f);
		if (!rider.player().startRiding(towed)) {
			throw failure(helper, "the rider could not mount the towed pod");
		}
		PodTowing.attach(tower, towed);
		helper.onEachTick(() -> {
			tower.setPos(towerAt);
			tower.setDeltaMovement(Vec3.ZERO);
		});
		helper.runAfterDelay(5, () -> {
			towed.setPos(inRock);
			if (helper.getLevel().noCollision(towed, towed.getBoundingBox())) {
				cleanUpRock(helper, tower, towed, rider);
				throw failure(helper, "the test needs the towed pod inside rock");
			}
			PodTowing.detach(towed);
			helper.runAfterDelay(3, () -> {
				try {
					if (!helper.getLevel().noCollision(towed, towed.getBoundingBox())) {
						throw failure(helper, "a pod freed in rock should end in open space, it is at %s", towed.position());
					}
					if (towed.getZ() <= inRock.z) {
						throw failure(helper, "the pod should leave the rock toward its tower, which is at higher z, it is at %s", towed.position());
					}
					if (rider.player().isInWall()) {
						throw failure(helper, "the rider should not be suffocating in the rock");
					}
					helper.succeed();
				} finally {
					cleanUpRock(helper, tower, towed, rider);
				}
			});
		});
	}

	@GameTest
	public void aPodLeftInRockByALostTowerIsMovedIntoOpenSpace(GameTestHelper helper) {
		fillRock(helper, Blocks.STONE);
		PodEntity tower = helper.spawn(PodRegistry.POD, ROCK_TOWER);
		PodEntity towed = helper.spawn(PodRegistry.POD, ROCK_POD);
		Vec3 inRock = towed.position();
		MockPlayer rider = MockPlayers.join(helper, "tow-lost-rider-" + CHARTERS.incrementAndGet());
		rider.teleportTo(helper.getLevel(), towed.position(), 0f, 0f);
		if (!rider.player().startRiding(towed)) {
			throw failure(helper, "the rider could not mount the towed pod");
		}
		PodTowing.attach(tower, towed);
		helper.runAfterDelay(3, () -> {
			towed.setPos(inRock);
			tower.discard();
			helper.runAfterDelay(3, () -> {
				try {
					if (!helper.getLevel().noCollision(towed, towed.getBoundingBox())) {
						throw failure(helper, "a pod whose tower is lost should end in open space, it is at %s", towed.position());
					}
					if (rider.player().isInWall()) {
						throw failure(helper, "the rider should not be suffocating in the rock");
					}
					if (!PodTowing.isTowed(towed)) {
						throw failure(helper, "the pod keeps its link while the tower is lost");
					}
					helper.succeed();
				} finally {
					cleanUpRock(helper, tower, towed, rider);
				}
			});
		});
	}

	@GameTest
	public void theCableIsFittedFromTheSeatAndTakenOffAgain(GameTestHelper helper) {
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity near = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
		PodEntity far = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 12.5));
		MockPlayer mock = MockPlayers.join(helper, "tow-user-" + CHARTERS.incrementAndGet());
		ServerPlayer player = mock.player();
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
		use(player, near);
		if (PodTowing.isTowed(near)) {
			throw failure(helper, "a cable cannot be fitted from outside a pod");
		}
		mock.teleportTo(helper.getLevel(), tower.position(), 0f, 0f);
		if (!player.startRiding(tower)) {
			throw failure(helper, "the player could not mount the tower");
		}
		use(player, far);
		if (PodTowing.isTowed(far)) {
			throw failure(helper, "a cable cannot be fitted to a pod out of reach");
		}
		if (!use(player, near).consumesAction() || !Optional.of(tower.getUUID()).equals(PodTowing.towerId(near))) {
			throw failure(helper, "using the cable on a pod in reach should fit it, got %s", PodTowing.towerId(near));
		}
		if (!use(player, near).consumesAction() || PodTowing.isTowed(near)) {
			throw failure(helper, "using the cable on a towed pod should take it off");
		}
		if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(PodRegistry.TOW_CABLE)) {
			throw failure(helper, "the cable is a tool and is not used up");
		}
		helper.runAfterDelay(2, () -> {
			try {
				List<Component> expected = List.of(Refusal.NOT_RIDING.message(), Refusal.TOO_FAR.message(),
						Component.translatable("message.deepcharter.towing.attached"), Component.translatable("message.deepcharter.towing.detached"));
				if (!expected.equals(mock.actionBarMessages())) {
					throw failure(helper, "the player should be told what each use did, got %s", mock.actionBarMessages());
				}
				helper.succeed();
			} finally {
				mock.leave();
				tower.discard();
				near.discard();
				far.discard();
			}
		});
	}

	/** The M1 crossing with a second, unridden pod on a cable: the arriving tower is a new entity and the towed pod must arrive with it. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void theTowedPodCrossesABreachWithItsTower(GameTestHelper helper) {
		double x = 2100.5;
		double z = 2100.5;
		ServerLevel one = layer(helper, 1);
		openShaft(one, x, z);
		MockPlayer mock = MockPlayers.join(helper, "towing-crossing");
		mock.teleportTo(one, new Vec3(x, 8, z), 0, 0);
		PodEntity[] pods = {null, null};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 8, z), () -> {
			pods[0] = spawn(one, x, 8, z);
			pods[1] = spawn(one, x, 10, z);
			PodTowing.attach(pods[0], pods[1]);
			if (!mock.player().startRiding(pods[0], true, false)) {
				throw failure(helper, "the mock could not board the tower");
			}
		});
		helper.succeedWhen(() -> {
			if (pods[0] == null) {
				throw failure(helper, "waiting for the chunk at %s to tick entities", BlockPos.containing(x, 8, z));
			}
			ServerPlayer player = mock.player();
			if (!player.level().dimension().equals(LayerChain.dimension(2))) {
				throw failure(helper, "the rider is still in %s (tower ticked %s times)", player.level().dimension(), pods[0].tickCount);
			}
			if (!(player.getVehicle() instanceof PodEntity arrived) || !arrived.getUUID().equals(pods[0].getUUID())) {
				throw failure(helper, "the rider should be on the same tower after crossing, is on %s", player.getVehicle());
			}
			if (!(player.level() instanceof ServerLevel two) || !(two.getEntity(pods[1].getUUID()) instanceof PodEntity crossed)) {
				throw failure(helper, "the towed pod did not arrive in layer 2");
			}
			if (crossed == pods[1] || !pods[1].isRemoved()) {
				throw failure(helper, "expected a new towed pod after the crossing and the old one removed");
			}
			if (!Optional.of(arrived.getUUID()).equals(PodTowing.towerId(crossed))) {
				throw failure(helper, "the cable did not cross with the towed pod, got %s", PodTowing.towerId(crossed));
			}
			double gap = crossed.position().distanceTo(arrived.position());
			double trail = TowTuning.DEFAULT.trailDistance();
			if (Math.abs(gap - trail) > 0.5) {
				throw failure(helper, "the towed pod should arrive at the trail offset of %s from its tower, not inside it, it is %s away", trail, gap);
			}
		});
	}

	/** A rider in the towed pod crosses with it, and CROSSED fires for that rider as it does for the pilot: both crossed. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void aRiderInTheTowedPodCrossesWithIt(GameTestHelper helper) {
		double x = 2200.5;
		double z = 2200.5;
		ServerLevel one = layer(helper, 1);
		openShaft(one, x, z);
		MockPlayer pilot = MockPlayers.join(helper, "towing-pilot");
		MockPlayer rider = MockPlayers.join(helper, "towing-rider");
		pilot.teleportTo(one, new Vec3(x, 8, z), 0, 0);
		rider.teleportTo(one, new Vec3(x, 10, z), 0, 0);
		PodEntity[] pods = {null, null};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 8, z), () -> {
			pods[0] = spawn(one, x, 8, z);
			pods[1] = spawn(one, x, 10, z);
			PodTowing.attach(pods[0], pods[1]);
			if (!pilot.player().startRiding(pods[0], true, false) || !rider.player().startRiding(pods[1], true, false)) {
				throw failure(helper, "the mocks could not board");
			}
		});
		helper.succeedWhen(() -> {
			if (pods[0] == null) {
				throw failure(helper, "waiting for the chunk at %s to tick entities", BlockPos.containing(x, 8, z));
			}
			ServerPlayer seated = rider.player();
			if (!seated.level().dimension().equals(LayerChain.dimension(2))) {
				throw failure(helper, "the rider in the towed pod is still in %s", seated.level().dimension());
			}
			if (!(seated.getVehicle() instanceof PodEntity vehicle) || !vehicle.getUUID().equals(pods[1].getUUID()) || !PodTowing.isTowed(vehicle)) {
				throw failure(helper, "the rider should still ride the towed pod on its cable, is on %s", seated.getVehicle());
			}
			if (!pilot.player().level().dimension().equals(LayerChain.dimension(2))) {
				throw failure(helper, "the pilot is still in %s", pilot.player().level().dimension());
			}
			if (!CROSSED.contains(seated.getUUID()) || !CROSSED.contains(pilot.player().getUUID())) {
				throw failure(helper, "CROSSED should fire for the pilot and for the rider of the towed pod, fired for %s", CROSSED);
			}
		});
	}

	/** A towed pod that vanilla will not teleport (an End to Overworld trip with a rider who has not seen the credits) stays behind on its cable. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTowedPodThatCannotCrossStaysBehindAsAnOrdinaryPod(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel end = server.getLevel(Level.END);
		ServerLevel overworld = server.overworld();
		double x = 2300.5;
		double z = 2300.5;
		MockPlayer rider = MockPlayers.join(helper, "towing-stay-" + CHARTERS.incrementAndGet());
		rider.teleportTo(end, new Vec3(x, 100, z), 0, 0);
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity[] stay = {null};
		LogCapture[] logged = {null};
		FarChunks.awaitEntityTicking(helper, end, BlockPos.containing(x, 100, z), () -> {
			stay[0] = spawn(end, x, 100, z);
			logged[0] = LogCapture.start(stay[0].getUUID().toString());
			Versioned.modify(stay[0], PodTowing.STATE, state -> new PodTowing.State(Optional.of(tower.getUUID())));
			if (!rider.player().startRiding(stay[0], true, false)) {
				throw failure(helper, "the rider could not board");
			}
			BreachEvents.CROSSED.invoker().onCrossed(tower, end, overworld, 1, 2);
		});
		helper.succeedWhen(() -> {
			if (stay[0] == null) {
				throw failure(helper, "waiting for the chunk at %s in the End to tick entities", BlockPos.containing(x, 100, z));
			}
			try {
				if (stay[0].isRemoved() || stay[0].level() != end || rider.player().getVehicle() != stay[0]) {
					throw failure(helper, "the pod that cannot cross should stay in the End with its rider, is in %s", stay[0].level().dimension());
				}
				if (!Optional.of(tower.getUUID()).equals(PodTowing.towerId(stay[0]))) {
					throw failure(helper, "it keeps its link, got %s", PodTowing.towerId(stay[0]));
				}
				if (PodEvents.ignoresBlockCollision(stay[0]) || logged[0].errors().size() != 1) {
					throw failure(helper, "with its tower in another level it is an ordinary pod, and the failure is logged once: %s", logged[0].errors());
				}
			} finally {
				rider.leave();
				stay[0].discard();
				tower.discard();
			}
		});
	}

	private static void expectRefusal(GameTestHelper helper, PodEntity tower, PodEntity towed, Refusal expected) {
		Optional<Refusal> actual = PodTowing.refusal(tower, towed);
		if (!Optional.of(expected).equals(actual)) {
			throw failure(helper, "fitting a cable from %s to %s should be refused as %s, got %s", tower.getUUID(), towed.getUUID(), expected, actual);
		}
	}

	private static InteractionResult use(ServerPlayer player, PodEntity pod) {
		return UseEntityCallback.EVENT.invoker().interact(player, pod.level(), InteractionHand.MAIN_HAND, pod, null);
	}

	private static CharterId someCharter(GameTestHelper helper) {
		return foundCharter(helper, UUID.randomUUID());
	}

	/** Founds a charter with {@code player} as its founder. */
	private static CharterId charterOf(GameTestHelper helper, MockPlayer player) {
		return foundCharter(helper, player.player().getUUID());
	}

	private static CharterId foundCharter(GameTestHelper helper, UUID founder) {
		MinecraftServer server = helper.getLevel().getServer();
		if (Charters.found(server, founder, "Towing Test " + CHARTERS.incrementAndGet()).isPresent()) {
			throw failure(helper, "founding the charter should succeed");
		}
		return Charters.charterOf(server, founder).orElseThrow().id();
	}

	private static void fillRock(GameTestHelper helper, Block block) {
		for (int x = 1; x <= 5; x++) {
			for (int y = 2; y <= 5; y++) {
				for (int z = 1; z <= 4; z++) {
					helper.setBlock(new BlockPos(x, y, z), block);
				}
			}
		}
	}

	private static void cleanUpRock(GameTestHelper helper, PodEntity tower, PodEntity towed, MockPlayer rider) {
		rider.leave();
		tower.discard();
		towed.discard();
		fillRock(helper, Blocks.AIR);
	}

	private static PodEntity spawn(ServerLevel level, double x, double y, double z) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(x, y, z);
		level.addFreshEntity(pod);
		return pod;
	}

	private static void cleanUp(GameTestHelper helper, PodEntity tower, PodEntity towed, MockPlayer pilot) {
		pilot.leave();
		tower.discard();
		towed.discard();
		fillFloor(helper, Blocks.AIR);
	}

	private static void fillFloor(GameTestHelper helper, Block block) {
		for (int x = -FLOOR_RADIUS; x <= FLOOR_RADIUS; x++) {
			for (int z = -FLOOR_RADIUS; z <= FLOOR_RADIUS; z++) {
				helper.setBlock(new BlockPos(x + FLOOR_RADIUS, FLOOR_Y, z + FLOOR_RADIUS), block);
			}
		}
	}

	/** Saves {@code pod} (replacing its attachments with {@code attachments} if given), discards it and loads a new pod from the data. */
	private static Entity reload(GameTestHelper helper, PodEntity pod, CompoundTag attachments) {
		ServerLevel level = helper.getLevel();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		pod.discard();
		CompoundTag tag = output.buildResult();
		if (attachments != null) {
			tag.put(ATTACHMENTS_KEY, attachments);
		}
		return EntityType.create(PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
				level, EntitySpawnReason.LOAD).orElseThrow(() -> failure(helper, "the saved pod did not load"));
	}

	/** Clear a 3x3 shaft through the floor of {@code level}, wide enough for the pod's 1.9-block hull. */
	private static void openShaft(ServerLevel level, double x, double z) {
		BlockPos column = BlockPos.containing(x, 0, z);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int y = level.getMinY(); y <= level.getMinY() + 10; y++) {
					level.setBlock(column.offset(dx, 0, dz).atY(y), Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
