package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.transmission.TransmissionData;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Server GameTests for #82: the Prospector is a two-seat chassis with its own hitbox, a 3 x 3 bore and a tier 3 cap; wrecks of it
 * lie at the wreck sites, PROSPECTOR-0002 at the one nearest the Conduit with a lamp and N10; and the hangar restores one for
 * $1,500 and 3 Cicatrium, then fires T17.
 */
public class ProspectorChassisTest {
	private static final AtomicInteger CHARTERS = new AtomicInteger();
	private static final Identifier T17 = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "t17");
	/** A spacing cell that no other test loads, so its wreck site is generated for the first time inside these tests. */
	private static final int WRECK_CELL = 9;
	private static final int DRILL_MARGIN_TICKS = 1000;
	private static final int DRILL_TICKS = FarChunks.AWAIT_BUDGET_TICKS + DRILL_MARGIN_TICKS;
	private static final int BORE_X = 3400;
	private static final int BORE_Z = 3000;
	private static final int BORE_FLOOR = 60;
	private static final int BORE_DEPTH = 3;
	private static final int DRIVE_TICKS = 40;
	/** Ticks a pod needs, once its chunk ticks, to fall through the shaft and cross. */
	private static final int CROSSING_TICKS = 200;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
	private static final Input FORWARD_AND_SPRINT = new Input(true, false, false, false, false, false, true);
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final long PRICE = 1_500;
	private static final int CATALYSTS = 3;

	@GameTest
	public void theProspectorHasTwoSeatsItsOwnHitboxAndKeepsItAcrossASave(GameTestHelper helper) {
		PodEntity prospector = helper.spawn(PodRegistry.PROSPECTOR, 2, 2, 2);
		PodEntity mole = helper.spawn(PodRegistry.POD, 6, 2, 6);
		try {
			expect(helper, prospector.chassis() == Chassis.PROSPECTOR && prospector.chassis().seats() == 2,
					"a Prospector has the Prospector chassis with 2 seats, it has %s", prospector.chassis());
			expect(helper, prospector.getBbWidth() == 2.9f && prospector.getBbHeight() == 2.9f,
					"the Prospector's hitbox is 2.9 x 2.9, it is %s x %s", prospector.getBbWidth(), prospector.getBbHeight());
			expect(helper, mole.chassis() == Chassis.MOLE && mole.getBbWidth() == 1.9f && mole.getBbHeight() == 1.9f,
					"the Mole keeps its 1.9 x 1.9 hitbox, it is %s %s x %s", mole.chassis(), mole.getBbWidth(), mole.getBbHeight());

			ServerLevel level = helper.getLevel();
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			prospector.saveWithoutId(output);
			CompoundTag saved = output.buildResult();
			Entity loaded = EntityType.create(PodRegistry.PROSPECTOR, TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved),
					level, EntitySpawnReason.LOAD).orElseThrow(() -> failure(helper, "the saved Prospector did not load"));
			try {
				expect(helper, loaded instanceof PodEntity copy && copy.chassis() == Chassis.PROSPECTOR,
						"a saved Prospector loads as a Prospector, it loaded as %s", loaded);
			} finally {
				loaded.discard();
			}
			// A pod saved with one chassis and loaded into another one's type keeps the type's chassis: it logs and never throws.
			Entity mismatched = EntityType.create(PodRegistry.POD, TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved),
					level, EntitySpawnReason.LOAD).orElseThrow(() -> failure(helper, "a Prospector's save must still load into a Mole's type"));
			try {
				expect(helper, mismatched instanceof PodEntity copy && copy.chassis() == Chassis.MOLE && copy.getBbWidth() == 1.9f
						&& copy.getBbHeight() == 1.9f, "a Prospector's save in a Mole's type keeps the Mole chassis and hitbox, it loaded as %s", mismatched);
				// The save carries the Prospector's uuid, which is still in the level.
				mismatched.setUUID(UUID.randomUUID());
				expect(helper, level.addFreshEntity(mismatched) && level.getEntity(mismatched.getUUID()) == mismatched,
						"the pod with the mismatched chassis is in the level");
			} finally {
				mismatched.discard();
			}
			helper.succeed();
		} finally {
			prospector.discard();
			mole.discard();
		}
	}

	@GameTest
	public void aProspectorTakesPartsUpToTierThreeAndAMoleOnlyTierTwo(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity prospector = helper.spawn(PodRegistry.PROSPECTOR, 2, 2, 2);
		PodEntity mole = helper.spawn(PodRegistry.POD, 6, 2, 6);
		try {
			PodComponents.register(prospector, charter);
			PodComponents.register(mole, charter);
			for (PodEntity pod : List.of(prospector, mole)) {
				PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.HULL, 4, charter));
			}
			expect(helper, PodComponents.effectiveTier(prospector, ComponentTrack.HULL) == 3,
					"a tier 4 part works at tier 3 on a Prospector, at tier %s", PodComponents.effectiveTier(prospector, ComponentTrack.HULL));
			expect(helper, PodComponents.effectiveTier(mole, ComponentTrack.HULL) == 2,
					"a tier 4 part works at tier 2 on a Mole, at tier %s", PodComponents.effectiveTier(mole, ComponentTrack.HULL));
			expect(helper, PodStats.of(prospector).maxHull() > PodStats.of(mole).maxHull(),
					"the Prospector's tier 3 hull is stronger than the Mole's tier 2: %s against %s", PodStats.of(prospector).maxHull(), PodStats.of(mole).maxHull());
			expect(helper, PodComponents.registration(prospector).orElseThrow().serial().startsWith("PROSPECTOR-"),
					"a Prospector's serial starts with PROSPECTOR, it is %s", PodComponents.registration(prospector).orElseThrow().serial());
			helper.succeed();
		} finally {
			prospector.discard();
			mole.discard();
		}
	}

	@GameTest(maxTicks = 3 * DRIVE_TICKS)
	public void twoPlayersRideAndOnlyThePilotDrives(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.PROSPECTOR, 4, 2, 4);
		for (int x = 0; x < 9; x++) {
			for (int z = 0; z < 9; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
		MockPlayer pilot = MockPlayers.join(helper, "ProspectorPilot");
		MockPlayer navigator = MockPlayers.join(helper, "ProspectorNavigator");
		MockPlayer third = MockPlayers.join(helper, "ProspectorStowaway");
		expect(helper, pilot.player().startRiding(pod) && navigator.player().startRiding(pod),
				"both seats of a Prospector take a rider");
		expect(helper, !third.player().startRiding(pod) && pod.getPassengers().size() == 2,
				"a third player cannot board a Prospector: %s ride it", pod.getPassengers().size());
		expect(helper, pod.getControllingPassenger() == pilot.player() && pod.getPassengers().get(1) == navigator.player(),
				"the first aboard pilots and the second navigates");
		pod.setFuel(100f);
		Vec3 start = pod.position();
		int[] phase = {0};
		long[] startedAt = {0};
		navigator.setInput(FORWARD_AND_SPRINT);
		helper.onEachTick(() -> {
			if (phase[0] == 0 && helper.getTick() >= DRIVE_TICKS) {
				expect(helper, pod.position().distanceTo(start) < 0.01 && !pod.drilling(), "the navigator's input must not move or drill the pod: it moved %s and drilling is %s",
						pod.position().distanceTo(start), pod.drilling());
				navigator.releaseInput();
				pilot.setInput(FORWARD);
				phase[0] = 1;
				startedAt[0] = helper.getTick();
			}
			if (phase[0] == 1 && helper.getTick() >= startedAt[0] + DRIVE_TICKS) {
				expect(helper, pod.position().distanceTo(start) > 1,
						"the pilot's input moves the pod, it moved %s", pod.position().distanceTo(start));
				// The navigator takes the controls when the pilot gets off.
				pilot.releaseInput();
				pilot.player().stopRiding();
				expect(helper, pod.getControllingPassenger() == navigator.player(),
						"with the pilot gone the navigator is the one aboard and takes the controls");
				pod.discard();
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = DRILL_TICKS)
	public void aProspectorBoresThreeByThree(GameTestHelper helper) {
		ServerLevel level = layer(helper, 1);
		RoomSeal.seal(level, new BlockPos(BORE_X - 5, BORE_FLOOR - 8, BORE_Z - 5), new BlockPos(BORE_X + 5, BORE_FLOOR + 10, BORE_Z + 5));
		fill(level, BORE_X - 5, BORE_X + 5, BORE_FLOOR - 8, BORE_FLOOR - 1, BORE_Z - 5, BORE_Z + 5, Blocks.STONE);
		fill(level, BORE_X - 5, BORE_X + 5, BORE_FLOOR, BORE_FLOOR + 10, BORE_Z - 5, BORE_Z + 5, Blocks.AIR);
		MockPlayer pilot = MockPlayers.join(helper, "ProspectorBorer");
		// Off the block grid on purpose: the pod must centre itself and bore the nearest 3 x 3.
		Vec3 at = new Vec3(BORE_X + 0.3, BORE_FLOOR, BORE_Z + 0.8);
		pilot.teleportTo(level, at, 0f, 0f);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
			pod[0] = PodRegistry.PROSPECTOR.create(level, EntitySpawnReason.COMMAND);
			pod[0].setPos(at);
			level.addFreshEntity(pod[0]);
			expect(helper, pilot.player().startRiding(pod[0]), "the pilot could not board the Prospector");
			pilot.setInput(SPRINT);
		});
		boolean[] released = {false};
		helper.onEachTick(() -> {
			if (pod[0] == null) {
				return;
			}
			if (!released[0] && air(level, BORE_X - 1, BORE_X + 1, BORE_FLOOR - BORE_DEPTH, BORE_FLOOR - 1, BORE_Z - 1, BORE_Z + 1) == 9 * BORE_DEPTH) {
				pilot.releaseInput();
				released[0] = true;
			}
			if (released[0] && pod[0].onGround() && pod[0].getY() < BORE_FLOOR - BORE_DEPTH + 0.1) {
				for (int x = BORE_X - 4; x <= BORE_X + 4; x++) {
					for (int z = BORE_Z - 4; z <= BORE_Z + 4; z++) {
						for (int y = BORE_FLOOR - BORE_DEPTH - 1; y < BORE_FLOOR; y++) {
							boolean inBore = Math.abs(x - BORE_X) <= 1 && Math.abs(z - BORE_Z) <= 1 && y >= BORE_FLOOR - BORE_DEPTH;
							boolean isAir = level.getBlockState(new BlockPos(x, y, z)).isAir();
							if (isAir != inBore) {
								throw failure(helper, "block (%d,%d,%d) is %s, expected %s", x, y, z, isAir ? "air" : "solid", inBore ? "air" : "solid");
							}
						}
					}
				}
				expect(helper, Math.abs(pod[0].getX() - (BORE_X + 0.5)) < 0.01 && Math.abs(pod[0].getZ() - (BORE_Z + 0.5)) < 0.01,
						"the pod ends centred in its bore at (%s, %s), it is at %s", BORE_X + 0.5, BORE_Z + 0.5, pod[0].position());
				pod[0].discard();
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = 12000)
	public void everyWreckSiteHoldsAnUnownedProspectorWreckAndOnlyPROSPECTOR0002HasALampAndN10(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = layer(helper, 2);
		BlockPos conduit = conduit(helper);
		StructureSite ordinary = StructureSite.in(level.getSeed(), StructureKind.WRECK, level.getMinY(), level.getHeight(), WRECK_CELL, WRECK_CELL, conduit);
		StructureSite prospector = LayerStructures.prospector(server).orElseThrow(() -> failure(helper, "no Prospector site"));
		if (prospector.equals(ordinary)) {
			throw failure(helper, "cell %d is the Prospector's own site, so it cannot be the ordinary one", WRECK_CELL);
		}
		generate(level, ordinary);
		generate(level, prospector);
		helper.succeedWhen(() -> {
			// Loading the chunks again must not add a second pod to either site.
			generate(level, ordinary);
			generate(level, prospector);
			PodEntity plain = wreckAt(helper, level, ordinary);
			expect(helper, plain.getCustomName() == null && PodComponents.registration(plain).isEmpty(),
					"an ordinary wreck is unnamed and nobody owns it");
			expect(helper, lanterns(level, ordinary) == 0 && notes(level, ordinary).isEmpty(),
					"an ordinary wreck site has no lamp burning and no Note: %s lanterns, notes %s", lanterns(level, ordinary), notes(level, ordinary));

			PodEntity famous = wreckAt(helper, level, prospector);
			expect(helper, famous.getCustomName() != null && famous.getCustomName().getString().equals("PROSPECTOR-0002"),
					"the wreck at the site nearest the Conduit is PROSPECTOR-0002, it is %s", famous.getCustomName());
			expect(helper, lanterns(level, prospector) == 1,
					"PROSPECTOR-0002 has exactly one lamp still burning, it has %s", lanterns(level, prospector));
			expect(helper, notes(level, prospector).equals(List.of(10)),
					"PROSPECTOR-0002 holds Note N10 and no other, it holds %s", notes(level, prospector));
			expect(helper, Notes.exists(Notes.id(10)) && Notes.id(notes(level, prospector).getFirst()).equals(Notes.id(10)),
					"the lamp's Note is the Note %s, Ines's log", Notes.id(10));
		});
	}

	@GameTest(maxTicks = FoundingMoleHangarTest.MAX_TICKS)
	public void restoringAProspectorCostsFifteenHundredAndThreeCicatriumRegistersItAndFiresT17(GameTestHelper helper) {
		FoundingMoleHangarTest.inTheHangar(helper, before -> FoundingMoleHangarTest.withFreshWorld(helper, () -> {
			FoundingMoleHangarTest.repairTheConsole(helper);
			MockPlayer owner = FoundingMoleHangarTest.member(helper, "Restorer");
			BlockPos console = FoundingMoleHangarTest.console(helper, owner);
			CharterId charter = FoundingMoleHangarTest.charterOf(helper, owner).id();
			PodEntity wreck = prospectorWreck(helper);
			wreck.setCustomName(Component.literal("PROSPECTOR-0002"));
			FoundingMoleHangarTest.deposit(helper, owner, FoundingMoleHangarTest.RICH);
			FoundingMoleHangarTest.give(owner.player(), FoundingMoleHangarTest.CATALYST, CATALYSTS + 1);
			expect(helper, !fired(helper, charter), "T17 has not fired yet");

			FoundingMoleHangarTest.expectDone(helper, FoundingMoleHangarTest.act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring the Prospector");
			expect(helper, FoundingMoleHangarTest.balance(helper, owner) == FoundingMoleHangarTest.RICH - PRICE,
					"restoring a Prospector costs $1,500, the account holds %s", FoundingMoleHangarTest.balance(helper, owner));
			expect(helper, FoundingMoleHangarTest.count(owner.player(), FoundingMoleHangarTest.CATALYST) == 1,
					"restoring a Prospector uses up 3 Cicatrium, the player holds %s", FoundingMoleHangarTest.count(owner.player(), FoundingMoleHangarTest.CATALYST));
			expect(helper, !Wrecks.isWreck(wreck) && wreck.hull() == wreck.maxHull(), "the restored Prospector is whole: hull %s of %s", wreck.hull(), wreck.maxHull());
			PodComponents.Registration registration = PodComponents.registration(wreck).orElseThrow(() -> failure(helper, "the restored Prospector should be registered"));
			expect(helper, registration.owner().equals(charter) && registration.serial().equals("PROSPECTOR-0001"),
					"the Prospector is re-registered to the restoring charter as PROSPECTOR-0001 (this world issues serials afresh), it is %s", registration);
			expect(helper, wreck.getCustomName() == null, "the wreck's old name goes: the registration names the pod now");
			expect(helper, fired(helper, charter), "restoring a Prospector fires T17");
			FoundingMoleHangarTest.clearFloor(helper);
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = FoundingMoleHangarTest.MAX_TICKS)
	public void aProspectorRestoreRefusesShortOfEitherOrWithUnreadableSerialsAndTakesNothing(GameTestHelper helper) {
		FoundingMoleHangarTest.inTheHangar(helper, before -> FoundingMoleHangarTest.withFreshWorld(helper, () -> {
			FoundingMoleHangarTest.repairTheConsole(helper);
			MinecraftServer server = FoundingMoleHangarTest.server(helper);
			MockPlayer owner = FoundingMoleHangarTest.member(helper, "Short Of It");
			BlockPos console = FoundingMoleHangarTest.console(helper, owner);
			CharterId charter = FoundingMoleHangarTest.charterOf(helper, owner).id();
			PodEntity wreck = prospectorWreck(helper);

			// A Mole is $100 and 1 Cicatrium: a Prospector's price is its own.
			FoundingMoleHangarTest.deposit(helper, owner, PRICE - 1);
			FoundingMoleHangarTest.give(owner.player(), FoundingMoleHangarTest.CATALYST, CATALYSTS);
			FoundingMoleHangarTest.expectRefused(helper, FoundingMoleHangarTest.act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring a dollar short");
			nothingChanged(helper, owner, wreck, charter, PRICE - 1, CATALYSTS, "a dollar short");

			FoundingMoleHangarTest.deposit(helper, owner, 1);
			owner.player().getInventory().clearContent();
			FoundingMoleHangarTest.give(owner.player(), FoundingMoleHangarTest.CATALYST, CATALYSTS - 1);
			FoundingMoleHangarTest.expectRefused(helper, FoundingMoleHangarTest.act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring a Cicatrium short");
			nothingChanged(helper, owner, wreck, charter, PRICE, CATALYSTS - 1, "a Cicatrium short");

			FoundingMoleHangarTest.give(owner.player(), FoundingMoleHangarTest.CATALYST, 1);
			Tag serials = Serials.CODEC.encodeStart(NbtOps.INSTANCE, new Serials()).getOrThrow();
			CompoundTag future = ((CompoundTag) serials).copy();
			future.putInt("version", 7743);
			WorldData.with(server, Serials.TYPE, Serials.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow(), () -> {
				try {
					FoundingMoleHangarTest.expectRefused(helper, FoundingMoleHangarTest.act(owner.player(), console, HangarTerminal.RESTORE_WRECK),
							"restoring with unreadable serials");
					nothingChanged(helper, owner, wreck, charter, PRICE, CATALYSTS, "unreadable serials");
				} finally {
				}
			});
			FoundingMoleHangarTest.clearFloor(helper);
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = FoundingMoleHangarTest.MAX_TICKS)
	public void aMoleStillRestoresForOneHundredAndOneCicatriumWithoutT17(GameTestHelper helper) {
		FoundingMoleHangarTest.inTheHangar(helper, before -> FoundingMoleHangarTest.withFreshWorld(helper, () -> {
			FoundingMoleHangarTest.repairTheConsole(helper);
			MockPlayer owner = FoundingMoleHangarTest.member(helper, "Mole Restorer");
			BlockPos console = FoundingMoleHangarTest.console(helper, owner);
			CharterId charter = FoundingMoleHangarTest.charterOf(helper, owner).id();
			PodEntity mole = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 5.5));
			PodComponents.register(mole, charter);
			mole.damageHull(mole.maxHull());
			FoundingMoleHangarTest.deposit(helper, owner, FoundingMoleHangarTest.RICH);
			FoundingMoleHangarTest.give(owner.player(), FoundingMoleHangarTest.CATALYST, 2);

			FoundingMoleHangarTest.expectDone(helper, FoundingMoleHangarTest.act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring the Mole");
			expect(helper, FoundingMoleHangarTest.balance(helper, owner) == FoundingMoleHangarTest.RICH - 100
					&& FoundingMoleHangarTest.count(owner.player(), FoundingMoleHangarTest.CATALYST) == 1 && !Wrecks.isWreck(mole),
					"a Mole restores for $100 and 1 Cicatrium: the account holds %s", FoundingMoleHangarTest.balance(helper, owner));
			expect(helper, !fired(helper, charter), "restoring a Mole does not fire T17");
			FoundingMoleHangarTest.clearFloor(helper);
			helper.succeed();
		}));
	}

	/** A Prospector with a pilot and a navigator crosses a breach and both arrive in the same seats. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void aProspectorCrossesABreachWithBothRidersInTheirSeats(GameTestHelper helper) {
		double x = 3700.5;
		double z = 3700.5;
		ServerLevel one = layer(helper, 1);
		openShaft(one, x, z);
		MockPlayer pilot = MockPlayers.join(helper, "prospector-cross-pilot");
		MockPlayer navigator = MockPlayers.join(helper, "prospector-cross-navigator");
		pilot.teleportTo(one, new Vec3(x, 8, z), 0, 0);
		navigator.teleportTo(one, new Vec3(x, 8, z), 0, 0);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 8, z), () -> {
			pod[0] = PodRegistry.PROSPECTOR.create(one, EntitySpawnReason.COMMAND);
			pod[0].setPos(x, 8, z);
			one.addFreshEntity(pod[0]);
			if (!pilot.player().startRiding(pod[0], true, false) || !navigator.player().startRiding(pod[0], true, false)) {
				throw failure(helper, "the mocks could not board the Prospector");
			}
		});
		helper.succeedWhen(() -> {
			if (pod[0] == null) {
				throw failure(helper, "waiting for the chunk at %s to tick entities", BlockPos.containing(x, 8, z));
			}
			expect(helper, pilot.player().level().dimension().equals(LayerChain.dimension(2)),
					"the pilot should have crossed to layer 2, is in %s", pilot.player().level().dimension());
			expect(helper, pilot.player().getVehicle() instanceof PodEntity arrived && arrived.chassis() == Chassis.PROSPECTOR
					&& arrived.getUUID().equals(pod[0].getUUID()), "the pilot rides the same Prospector after crossing, rides %s", pilot.player().getVehicle());
			PodEntity arrived = (PodEntity) pilot.player().getVehicle();
			expect(helper, arrived.getPassengers().size() == 2 && arrived.getPassengers().get(0) == pilot.player()
					&& arrived.getPassengers().get(1) == navigator.player(),
					"the pilot is still first aboard and the navigator second after crossing, the passengers are %s", arrived.getPassengers());
			expect(helper, arrived.getControllingPassenger() == pilot.player() && PodSeat.find(arrived, navigator.player()).orElseThrow() == PodSeat.NAVIGATOR,
					"the pilot still controls and the navigator is still the navigator");
		});
	}

	/** Riders in a towed Prospector cross with it, still in the pod they boarded and in the order they boarded. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
	public void aTowedProspectorKeepsBothRidersInTheirSeatsAcrossABreach(GameTestHelper helper) {
		double x = 3800.5;
		double z = 3800.5;
		ServerLevel one = layer(helper, 1);
		openShaft(one, x, z);
		MockPlayer pilot = MockPlayers.join(helper, "prospector-tow-pilot");
		MockPlayer navigator = MockPlayers.join(helper, "prospector-tow-navigator");
		pilot.teleportTo(one, new Vec3(x, 10, z), 0, 0);
		navigator.teleportTo(one, new Vec3(x, 10, z), 0, 0);
		PodEntity[] pods = {null, null};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 8, z), () -> {
			pods[0] = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			pods[0].setPos(x, 8, z);
			one.addFreshEntity(pods[0]);
			pods[1] = PodRegistry.PROSPECTOR.create(one, EntitySpawnReason.COMMAND);
			pods[1].setPos(x, 10, z);
			one.addFreshEntity(pods[1]);
			PodTowing.attach(pods[0], pods[1]);
			if (!pilot.player().startRiding(pods[1], true, false) || !navigator.player().startRiding(pods[1], true, false)) {
				throw failure(helper, "the mocks could not board the towed Prospector");
			}
		});
		helper.succeedWhen(() -> {
			if (pods[1] == null) {
				throw failure(helper, "waiting for the chunk at %s to tick entities", BlockPos.containing(x, 8, z));
			}
			expect(helper, pilot.player().level().dimension().equals(LayerChain.dimension(2)) && navigator.player().level().dimension().equals(LayerChain.dimension(2)),
					"both riders of the towed Prospector should have crossed to layer 2, they are in %s and %s",
					pilot.player().level().dimension(), navigator.player().level().dimension());
			expect(helper, pilot.player().getVehicle() instanceof PodEntity towed && towed.chassis() == Chassis.PROSPECTOR && PodTowing.isTowed(towed),
					"the first rider is still in the towed Prospector on its cable, is in %s", pilot.player().getVehicle());
			PodEntity towed = (PodEntity) pilot.player().getVehicle();
			expect(helper, towed.getPassengers().size() == 2 && towed.getPassengers().get(0) == pilot.player()
					&& towed.getPassengers().get(1) == navigator.player(),
					"the riders keep the order they boarded in the towed Prospector, the passengers are %s", towed.getPassengers());
		});
	}

	private static void nothingChanged(GameTestHelper helper, MockPlayer owner, PodEntity wreck, CharterId charter, long money, int catalysts, String why) {
		expect(helper, Wrecks.isWreck(wreck) && PodComponents.registration(wreck).isEmpty() && !fired(helper, charter)
				&& FoundingMoleHangarTest.balance(helper, owner) == money
				&& FoundingMoleHangarTest.count(owner.player(), FoundingMoleHangarTest.CATALYST) == catalysts,
				"a restore refused for %s changes nothing: wreck %s, registered %s, T17 %s, money %s, catalysts %s", why, Wrecks.isWreck(wreck),
				PodComponents.registration(wreck).isPresent(), fired(helper, charter), FoundingMoleHangarTest.balance(helper, owner),
				FoundingMoleHangarTest.count(owner.player(), FoundingMoleHangarTest.CATALYST));
	}

	/** An unowned Prospector wreck on the floor of the test, as the wreck sites hold them. */
	private static PodEntity prospectorWreck(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.PROSPECTOR, new Vec3(5.5, 2, 5.5));
		pod.damageHull(pod.maxHull());
		return pod;
	}

	private static boolean fired(GameTestHelper helper, CharterId charter) {
		return TransmissionData.get(helper.getLevel().getServer()).progress(charter).fired().contains(T17);
	}

	/** Loads every chunk the site touches, so that a fresh one is drawn. */
	private static void generate(ServerLevel level, StructureSite site) {
		BoundingBox box = site.bounds();
		for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
			for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
				level.getChunk(chunkX, chunkZ, ChunkStatus.FULL);
			}
		}
	}

	/** The one pod standing in the site's bay. */
	private static PodEntity wreckAt(GameTestHelper helper, ServerLevel level, StructureSite site) {
		List<PodEntity> pods = level.getEntitiesOfClass(PodEntity.class, new AABB(site.origin()).inflate(3));
		if (pods.size() != 1) {
			throw failure(helper, "the wreck site at %s should hold one pod, it holds %d", site.origin().toShortString(), pods.size());
		}
		PodEntity pod = pods.getFirst();
		expect(helper, pod.getType() == PodRegistry.PROSPECTOR && pod.chassis() == Chassis.PROSPECTOR,
				"the pod at %s is a Prospector, it is a %s", site.origin().toShortString(), pod.getType());
		expect(helper, Wrecks.isWreck(pod) && pod.hull() == 0f, "the pod at %s is a wreck", site.origin().toShortString());
		expect(helper, pod.position().equals(Vec3.atBottomCenterOf(site.origin())),
				"the pod stands in the middle of the bay at %s, it is at %s", Vec3.atBottomCenterOf(site.origin()), pod.position());
		return pod;
	}

	private static int lanterns(ServerLevel level, StructureSite site) {
		int found = 0;
		BoundingBox box = site.bounds();
		for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			if (level.getBlockState(pos).is(Blocks.LANTERN)) {
				found++;
			}
		}
		return found;
	}

	private static List<Integer> notes(ServerLevel level, StructureSite site) {
		List<Integer> found = new ArrayList<>();
		BoundingBox box = site.bounds();
		for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			BlockState state = level.getBlockState(pos);
			if (state.is(HandbookRegistry.NOTE)) {
				found.add(state.getValue(NoteBlock.NOTE));
			}
		}
		return found;
	}

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

	private static BlockPos conduit(GameTestHelper helper) {
		return Colony.anchor(helper.getLevel().getServer(), ColonyAnchor.CONDUIT)
				.orElseThrow(() -> failure(helper, "the colony was not built"));
	}

	private static CharterId charter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		UUID founder = UUID.randomUUID();
		if (Charters.found(server, founder, "Prospector " + CHARTERS.incrementAndGet()).isPresent()) {
			throw failure(helper, "founding a charter should succeed");
		}
		return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}

	private static void fill(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
				}
			}
		}
	}

	private static int air(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2) {
		int found = 0;
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					if (level.getBlockState(new BlockPos(x, y, z)).isAir()) {
						found++;
					}
				}
			}
		}
		return found;
	}

	private static void expect(GameTestHelper helper, boolean condition, String format, Object... args) {
		FoundingMoleHangarTest.expect(helper, condition, format, args);
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return FoundingMoleHangarTest.failure(helper, format, args);
	}
}
