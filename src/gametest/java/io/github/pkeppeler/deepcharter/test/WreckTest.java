package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer.RespawnConfig;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.wreck.WreckEvents;
import io.github.pkeppeler.deepcharter.wreck.WreckRegistry;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/** Server GameTests for #67: a pod at hull 0 is a wreck, and the restore hook makes it a pod again. */
public class WreckTest {
	private static final int FLOOR_Y = 1;
	private static final int FLOOR_RADIUS = 3;
	private static final int IDLE_TICKS = 100;
	private static final String ATTACHMENTS_KEY = "fabric:attachments";
	private static final String HULL_KEY = "hull";

	/** What the charter was told about each pod, by pod. Fabric events cannot be unregistered, so this listens once. */
	private record Report(Charter charter, OptionalInt layer, BlockPos pos) {
	}

	private static final Map<UUID, Report> REPORTS = new ConcurrentHashMap<>();

	private static final Input SNEAK = new Input(false, false, false, false, false, true, false);
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
	/** Pods whose crust bore costs 20 hull. Only the crust test adds to it. */
	private static final Set<UUID> CRUST_PODS = ConcurrentHashMap.newKeySet();

	static {
		PodStats.MODIFY.register((pod, stats) -> CRUST_PODS.contains(pod.getUUID()) ? stats.withCrustHullDamage(20f) : stats);
		WreckEvents.REPORTED.register((server, charter, pod, layer, pos) -> REPORTS.put(pod.getUUID(), new Report(charter, layer, pos)));
	}

	private static PodEntity spawnOnFloor(GameTestHelper helper) {
		for (int x = 0; x <= 2 * FLOOR_RADIUS; x++) {
			for (int z = 0; z <= 2 * FLOOR_RADIUS; z++) {
				helper.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.STONE);
			}
		}
		return helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5));
	}

	private static void clearFloor(GameTestHelper helper) {
		for (int x = 0; x <= 2 * FLOOR_RADIUS; x++) {
			for (int z = 0; z <= 2 * FLOOR_RADIUS; z++) {
				// room-carver: clears the floor of the overworld test structure, not layer rock
				helper.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.AIR);
			}
		}
	}

	private static String uniqueName() {
		return "Wreck " + UUID.randomUUID().toString().substring(0, 8);
	}

	private static void wreck(PodEntity pod) {
		pod.damageHull(pod.maxHull());
	}

	private static int oreInInventory(MockPlayer mock) {
		int count = 0;
		for (ItemStack stack : mock.player().getInventory()) {
			if (OreRegistry.typeOf(stack).isPresent()) {
				count += stack.getCount();
			}
		}
		return count;
	}

	@GameTest
	public void hullZeroTurnsThePodIntoAWreck(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		try {
			if (Wrecks.isWreck(pod)) {
				throw failure(helper, "a new pod must not be a wreck");
			}
			pod.damageHull(pod.maxHull() - 1f);
			if (Wrecks.isWreck(pod)) {
				throw failure(helper, "a pod with 1 hull left must not be a wreck");
			}
			pod.damageHull(1f);
			if (!Wrecks.isWreck(pod)) {
				throw failure(helper, "a pod at hull 0 should be a wreck");
			}
			helper.succeed();
		} finally {
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest
	public void aWreckIsUnpoweredAndCannotBeMounted(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = MockPlayers.join(helper, "wreck-mount");
		try {
			if (!PodEvents.isPowered(pod) || !PodEvents.canMount(pod, mock.player())) {
				throw failure(helper, "a working pod should be powered and mountable");
			}
			wreck(pod);
			if (PodEvents.isPowered(pod)) {
				throw failure(helper, "a wreck must be unpowered");
			}
			if (PodEvents.canMount(pod, mock.player())) {
				throw failure(helper, "a wreck must not be mountable");
			}
			pod.interact(mock.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
			if (mock.player().getVehicle() == pod) {
				throw failure(helper, "using a wreck must not board it");
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest
	public void aMountedPilotIsDismountedAndDies(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = MockPlayers.join(helper, "wreck-pilot");
		try {
			if (!mock.player().startRiding(pod, true, false)) {
				throw failure(helper, "the mock could not board the pod");
			}
			wreck(pod);
			if (mock.player().getVehicle() != null || !pod.getPassengers().isEmpty()) {
				throw failure(helper, "the pilot should be dismounted, vehicle is %s", mock.player().getVehicle());
			}
			if (mock.player().isAlive() || !mock.player().isDeadOrDying()) {
				throw failure(helper, "the pilot should be dead, health is %s in %s", mock.player().getHealth(),
						mock.player().gameMode.getGameModeForPlayer());
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest(maxTicks = IDLE_TICKS + 20)
	public void aWreckNeverDespawnsAndKeepsItsCargo(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
		float fuel = pod.fuel();
		wreck(pod);
		helper.runAfterDelay(IDLE_TICKS, () -> {
			try {
				if (pod.isRemoved() || !pod.isAlive()) {
					throw failure(helper, "the wreck was removed after %d ticks", IDLE_TICKS);
				}
				if (pod.cargo().entries().size() != 2 || pod.cargoUsed() != 2) {
					throw failure(helper, "the wreck should keep its 2 ore, has %s", pod.cargo().entries());
				}
				if (pod.fuel() != fuel) {
					throw failure(helper, "a wreck is powered off and burns no fuel: %s became %s", fuel, pod.fuel());
				}
				helper.succeed();
			} finally {
				pod.discard();
				clearFloor(helper);
			}
		});
	}

	@GameTest
	public void anyoneCanSalvageTheCargo(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = MockPlayers.join(helper, "wreck-salvager");
		try {
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
			InteractionResult working = UseEntityCallback.EVENT.invoker().interact(mock.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
			if (working.consumesAction() || oreInInventory(mock) != 0 || pod.cargo().entries().size() != 2) {
				throw failure(helper, "a working pod's cargo must not be salvaged by using it, got %s", working);
			}
			wreck(pod);
			InteractionResult result = UseEntityCallback.EVENT.invoker().interact(mock.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
			if (!result.consumesAction()) {
				throw failure(helper, "using a wreck should salvage it, got %s", result);
			}
			if (oreInInventory(mock) != 2) {
				throw failure(helper, "the stranger should hold the 2 ore, holds %d", oreInInventory(mock));
			}
			if (!pod.cargo().entries().isEmpty() || pod.cargoUsed() != 0 || pod.cargoMass() != 0f) {
				throw failure(helper, "the wreck's bay should be empty, holds %s", pod.cargo().entries());
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest
	public void theCharterIsToldTheSurfaceAndPosition(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = MockPlayers.join(helper, "wreck-surface");
		try {
			Charters.found(helper.getLevel().getServer(), mock.player().getUUID(), uniqueName()).ifPresent(refusal -> {
				throw failure(helper, "could not found a charter: %s", refusal);
			});
			if (!mock.player().startRiding(pod, true, false)) {
				throw failure(helper, "the mock could not board the pod");
			}
			wreck(pod);
			Report report = REPORTS.get(pod.getUUID());
			if (report == null) {
				throw failure(helper, "the charter was not told");
			}
			if (report.layer().isPresent() || !report.pos().equals(pod.blockPosition())) {
				throw failure(helper, "expected the surface at %s, was told %s", pod.blockPosition(), report);
			}
			if (!report.charter().onRoster(mock.player().getUUID())) {
				throw failure(helper, "the report named a charter the pilot is not on: %s", report.charter());
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	private static Charter found(GameTestHelper helper, MockPlayer mock) {
		MinecraftServer server = helper.getLevel().getServer();
		Charters.found(server, mock.player().getUUID(), uniqueName()).ifPresent(refusal -> {
			throw failure(helper, "could not found a charter: %s", refusal);
		});
		return Charters.charterOfOrThrow(server, mock.player().getUUID()).orElseThrow();
	}

	/** How many wreck reports this player has been sent in chat. */
	private static long reportsTo(MockPlayer mock) {
		return mock.chatMessages().stream()
				.filter(message -> message.getContents() instanceof TranslatableContents contents && contents.getKey().startsWith("deepcharter.wreck.report."))
				.count();
	}

	/** Runs {@code check} after the mocks have read the chat the wreck sent, then cleans up. */
	private static void afterReports(GameTestHelper helper, PodEntity pod, List<MockPlayer> mocks, Runnable check) {
		helper.runAfterDelay(3, () -> {
			try {
				check.run();
				helper.succeed();
			} finally {
				mocks.forEach(MockPlayer::leave);
				pod.discard();
				clearFloor(helper);
			}
		});
	}

	@GameTest
	public void anUnmannedOwnedWreckTellsItsOwnerCharter(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer owner = MockPlayers.join(helper, "wreck-owner");
		Charter charter = found(helper, owner);
		PodComponents.register(pod, charter.id());
		wreck(pod);
		afterReports(helper, pod, List.of(owner), () -> {
			if (reportsTo(owner) != 1) {
				throw failure(helper, "the owner should be told once, was told %d times", reportsTo(owner));
			}
			Report report = REPORTS.get(pod.getUUID());
			if (report == null || !report.charter().id().equals(charter.id())) {
				throw failure(helper, "the report should name the owner charter, was %s", report);
			}
		});
	}

	@GameTest
	public void everyOnlineMemberOfTheOwnerCharterIsToldAndAnOfflineOneIsNot(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer director = MockPlayers.join(helper, "wreck-director");
		MockPlayer online = MockPlayers.join(helper, "wreck-online");
		MockPlayer offline = MockPlayers.join(helper, "wreck-offline");
		MinecraftServer server = helper.getLevel().getServer();
		Charter charter = found(helper, director);
		for (MockPlayer member : List.of(online, offline)) {
			if (Charters.apply(server, member.player().getUUID(), charter.id()).isPresent()
					|| Charters.approve(server, director.player().getUUID(), member.player().getUUID()).isPresent()) {
				throw failure(helper, "could not add %s to the charter", member.player().getName().getString());
			}
		}
		offline.leave();
		PodComponents.register(pod, charter.id());
		wreck(pod);
		afterReports(helper, pod, List.of(director, online), () -> {
			if (reportsTo(director) != 1 || reportsTo(online) != 1 || reportsTo(offline) != 0) {
				throw failure(helper, "expected 1, 1, 0 reports (director, online, offline), got %d, %d, %d",
						reportsTo(director), reportsTo(online), reportsTo(offline));
			}
		});
	}

	@GameTest
	public void aRiderOffTheOwnerCharterIsNotToldOfAnOwnedWreck(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer owner = MockPlayers.join(helper, "wreck-own");
		MockPlayer rider = MockPlayers.join(helper, "wreck-rider");
		Charter ownerCharter = found(helper, owner);
		found(helper, rider);
		PodComponents.register(pod, ownerCharter.id());
		if (!rider.player().startRiding(pod, true, false)) {
			throw failure(helper, "the rider could not board the pod");
		}
		wreck(pod);
		afterReports(helper, pod, List.of(owner, rider), () -> {
			if (reportsTo(owner) != 1 || reportsTo(rider) != 0) {
				throw failure(helper, "only the owner should be told, got owner %d, rider %d", reportsTo(owner), reportsTo(rider));
			}
		});
	}

	@GameTest
	public void aRiderWhoIsOnTheOwnerCharterIsToldOnce(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer owner = MockPlayers.join(helper, "wreck-pilot");
		Charter charter = found(helper, owner);
		PodComponents.register(pod, charter.id());
		if (!owner.player().startRiding(pod, true, false)) {
			throw failure(helper, "the pilot could not board the pod");
		}
		wreck(pod);
		afterReports(helper, pod, List.of(owner), () -> {
			if (reportsTo(owner) != 1) {
				throw failure(helper, "the pilot should be told once, was told %d times", reportsTo(owner));
			}
		});
	}

	@GameTest
	public void anUnownedWreckStillTellsTheRidersCharters(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer rider = MockPlayers.join(helper, "wreck-unowned");
		Charter charter = found(helper, rider);
		if (!rider.player().startRiding(pod, true, false)) {
			throw failure(helper, "the rider could not board the pod");
		}
		wreck(pod);
		afterReports(helper, pod, List.of(rider), () -> {
			Report report = REPORTS.get(pod.getUUID());
			if (reportsTo(rider) != 1 || report == null || !report.charter().id().equals(charter.id())) {
				throw failure(helper, "the rider's charter should be told once, got %d reports and %s", reportsTo(rider), report);
			}
		});
	}

	@GameTest
	public void anOwnerCharterThatIsGoneFallsBackToTheRiders(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer rider = MockPlayers.join(helper, "wreck-gone");
		found(helper, rider);
		PodComponents.register(pod, CharterId.random());
		if (!rider.player().startRiding(pod, true, false)) {
			throw failure(helper, "the rider could not board the pod");
		}
		wreck(pod);
		afterReports(helper, pod, List.of(rider), () -> {
			if (reportsTo(rider) != 1) {
				throw failure(helper, "the rider's charter should be told once, was told %d times", reportsTo(rider));
			}
		});
	}

	@GameTest
	public void aDormantOwnerCharterFallsBackToTheRiders(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer founder = MockPlayers.join(helper, "wreck-dormant");
		MockPlayer rider = MockPlayers.join(helper, "wreck-dormant-rider");
		MinecraftServer server = helper.getLevel().getServer();
		Charter dormant = found(helper, founder);
		found(helper, rider);
		Charters.leave(server, founder.player().getUUID()).ifPresent(refusal -> {
			throw failure(helper, "the founder could not leave: %s", refusal);
		});
		if (!Charters.findOrThrow(server, dormant.id()).orElseThrow().dormant()) {
			throw failure(helper, "the charter should be dormant");
		}
		PodComponents.register(pod, dormant.id());
		if (!rider.player().startRiding(pod, true, false)) {
			throw failure(helper, "the rider could not board the pod");
		}
		wreck(pod);
		afterReports(helper, pod, List.of(founder, rider), () -> {
			if (reportsTo(rider) != 1) {
				throw failure(helper, "the rider's charter should be told once, was told %d times", reportsTo(rider));
			}
		});
	}

	/** The saved charters cannot be read: the hull-0 path does not throw, nobody is told, and the pod is still a wreck. */
	@GameTest
	public void unreadableCharterDataTellsNobodyAndStillWrecksTheOwnedPod(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer owner = MockPlayers.join(helper, "wreck-unread-charters");
		MinecraftServer server = helper.getLevel().getServer();
		Charter charter = found(helper, owner);
		PodComponents.register(pod, charter.id());
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		CharterData unreadable = CharterData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
		WorldData.with(server, CharterData.TYPE, unreadable, () -> wreck(pod));
		afterReports(helper, pod, List.of(owner), () -> {
			if (!Wrecks.isWreck(pod)) {
				throw failure(helper, "the pod should still be a wreck");
			}
			if (reportsTo(owner) != 0 || REPORTS.containsKey(pod.getUUID())) {
				throw failure(helper, "nobody is told while the charters cannot be read, owner was told %d times", reportsTo(owner));
			}
		});
	}

	/** The pod's components cannot be read: its owner is unknown, so it is treated as unowned and the riders' charters are told. */
	@GameTest
	public void aPodWithUnreadableComponentsIsTreatedAsUnownedForTheNotice(GameTestHelper helper) {
		PodEntity original = spawnOnFloor(helper);
		MockPlayer rider = MockPlayers.join(helper, "wreck-unread-components");
		Charter charter = found(helper, rider);
		PodComponents.register(original, charter.id());
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		CompoundTag attachments = new CompoundTag();
		attachments.put(PodComponents.STATE.identifier().toString(), future);
		Vec3 spot = original.position();
		PodEntity pod = reload(helper, original, attachments);
		pod.setPos(spot);
		helper.getLevel().addFreshEntity(pod);
		if (!(pod.getAttached(PodComponents.STATE) instanceof Versioned.Unreadable<?>)) {
			throw failure(helper, "setup: the components should be unreadable");
		}
		if (!rider.player().startRiding(pod, true, false)) {
			throw failure(helper, "the rider could not board the pod");
		}
		wreck(pod);
		afterReports(helper, pod, List.of(rider), () -> {
			Report report = REPORTS.get(pod.getUUID());
			if (reportsTo(rider) != 1 || report == null || !report.charter().id().equals(charter.id())) {
				throw failure(helper, "the rider's charter should be told once, got %d reports and %s", reportsTo(rider), report);
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 40)
	public void theCharterIsToldTheLayer(GameTestHelper helper) {
		double x = 2400.5;
		double z = 2400.5;
		ServerLevel two = helper.getLevel().getServer().getLevel(LayerChain.dimension(2));
		if (two == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(2));
		}
		MockPlayer mock = MockPlayers.join(helper, "wreck-layer");
		Charters.found(helper.getLevel().getServer(), mock.player().getUUID(), uniqueName()).ifPresent(refusal -> {
			throw failure(helper, "could not found a charter: %s", refusal);
		});
		mock.teleportTo(two, new Vec3(x, 8, z), 0, 0);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, two, BlockPos.containing(x, 8, z), () -> {
			pod[0] = PodRegistry.POD.create(two, EntitySpawnReason.COMMAND);
			pod[0].setPos(x, 8, z);
			two.addFreshEntity(pod[0]);
			mock.player().startRiding(pod[0], true, false);
			wreck(pod[0]);
		});
		helper.succeedWhen(() -> {
			if (pod[0] == null) {
				throw failure(helper, "waiting for the chunk at %s to tick entities", BlockPos.containing(x, 8, z));
			}
			Report report = REPORTS.get(pod[0].getUUID());
			if (report == null) {
				throw failure(helper, "the charter was not told");
			}
			if (!report.layer().equals(OptionalInt.of(2)) || !report.pos().equals(pod[0].blockPosition())) {
				throw failure(helper, "expected layer 2 at %s, was told %s", pod[0].blockPosition(), report);
			}
		});
	}

	@GameTest
	public void theRestoreHookTurnsAWreckBackIntoAWorkingPod(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = MockPlayers.join(helper, "wreck-restore");
		try {
			try {
				Wrecks.restore(pod, 10f);
				throw failure(helper, "restoring a pod that is not a wreck must fail loud");
			} catch (IllegalStateException expected) {
				// Expected.
			}
			wreck(pod);
			try {
				Wrecks.restore(pod, 0f);
				throw failure(helper, "restoring to no hull is not a restore and must fail loud");
			} catch (IllegalArgumentException expected) {
				// Expected.
			}
			Wrecks.restore(pod, 25f);
			if (Wrecks.isWreck(pod) || pod.hull() != 25f) {
				throw failure(helper, "the pod should work again with 25 hull, wreck=%s hull=%s", Wrecks.isWreck(pod), pod.hull());
			}
			if (!PodEvents.isPowered(pod) || !PodEvents.canMount(pod, mock.player())) {
				throw failure(helper, "a restored pod should be powered and mountable");
			}
			pod.interact(mock.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
			if (mock.player().getVehicle() != pod) {
				throw failure(helper, "a restored pod should board its pilot");
			}
			mock.player().stopRiding();
			wreck(pod);
			if (!Wrecks.isWreck(pod)) {
				throw failure(helper, "a restored pod that loses its hull again should wreck again");
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest
	public void aWreckStaysAWreckAcrossSaveAndLoad(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.SILVERIUM));
		wreck(pod);
		PodEntity loaded = reload(helper, pod, null);
		try {
			if (!Wrecks.isWreck(loaded) || loaded.hull() != 0f) {
				throw failure(helper, "the loaded pod should be a wreck at hull 0, wreck=%s hull=%s", Wrecks.isWreck(loaded), loaded.hull());
			}
			if (PodEvents.isPowered(loaded) || loaded.cargo().entries().size() != 1) {
				throw failure(helper, "the loaded wreck should be unpowered with its 1 ore");
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	@GameTest
	public void aPodSavedAtHullZeroWithNoWreckStateLoadsAsAWreck(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodEntity loaded = reload(helper, pod, null, tag -> tag.putFloat(HULL_KEY, 0f));
		ServerLevel level = helper.getLevel();
		try {
			level.addFreshEntity(loaded);
			if (!Wrecks.isWreck(loaded)) {
				throw failure(helper, "a pod that enters the world at hull 0 should be a wreck");
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	/**
	 * Saved wreck state of a version this build cannot read: ticking, joining the world and the hull running out must
	 * not throw, the pod is not guessed to be a wreck, and the saved data is written back unchanged.
	 */
	@GameTest
	public void anUnreadableWreckStateNeverThrowsAndIsKeptUnchanged(GameTestHelper helper) {
		String key = WreckRegistry.STATE.identifier().toString();
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putBoolean("wrecked", true);
		future.putString("added-in-v99", "kept");
		CompoundTag attachments = new CompoundTag();
		attachments.put(key, future);

		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodEntity loaded = reload(helper, pod, attachments);
		ServerLevel level = helper.getLevel();
		try {
			level.addFreshEntity(loaded);
			if (!(loaded.getAttached(WreckRegistry.STATE) instanceof Versioned.Unreadable<?>)) {
				throw failure(helper, "version 99 should load as Unreadable, got %s", loaded.getAttached(WreckRegistry.STATE));
			}
			if (Wrecks.isWreck(loaded) || !PodEvents.canMount(loaded, null) || !PodEvents.isPowered(loaded)) {
				throw failure(helper, "an unreadable wreck state must not be guessed to be a wreck");
			}
			loaded.tick();
			wreck(loaded);
			try {
				Wrecks.restore(loaded, 5f);
				throw failure(helper, "restoring over unreadable state must fail loud rather than overwrite it");
			} catch (IllegalStateException expected) {
				// Expected.
			}
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			loaded.saveWithoutId(output);
			CompoundTag saved = output.buildResult().getCompound(ATTACHMENTS_KEY).orElseThrow(() -> failure(helper, "the pod saved no attachments"));
			if (!future.equals(saved.get(key))) {
				throw failure(helper, "the unreadable wreck state was not written back unchanged: %s", saved.get(key));
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	/** Joins a mock in the test level at {@code at}, so that damage reaches it. */
	private static MockPlayer joinLoaded(GameTestHelper helper, String name, Vec3 at) {
		MockPlayer mock = MockPlayers.join(helper, name);
		mock.teleportTo(helper.getLevel(), at, 0, 0);
		return mock;
	}

	private static <T extends Entity> List<T> near(Entity center, Class<T> type) {
		return center.level().getEntitiesOfClass(type, center.getBoundingBox().inflate(5));
	}

	private static List<ItemEntity> nearOre(Entity center) {
		return near(center, ItemEntity.class).stream().filter(item -> OreRegistry.typeOf(item.getItem()).isPresent()).toList();
	}

	@GameTest
	public void aDyingCrewMemberDropsItemsButNotTheHandbook(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = joinLoaded(helper, "wreck-drops", pod.position());
		try {
			ServerPlayer player = mock.player();
			player.getInventory().add(new ItemStack(Items.DIRT));
			boolean hadHandbook = false;
			for (ItemStack stack : player.getInventory()) {
				hadHandbook |= stack.is(HandbookRegistry.HANDBOOK);
			}
			if (!hadHandbook) {
				throw failure(helper, "setup: the pilot should hold the handbook");
			}
			player.startRiding(pod, true, false);
			wreck(pod);
			List<ItemEntity> items = near(player, ItemEntity.class);
			if (items.stream().noneMatch(item -> item.getItem().is(Items.DIRT))) {
				throw failure(helper, "the dirt should drop near the wreck, items: %s", items);
			}
			if (items.stream().anyMatch(item -> item.getItem().is(HandbookRegistry.HANDBOOK))) {
				throw failure(helper, "the handbook must not drop: it is bound to its holder");
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest(maxTicks = 100)
	public void aCrewMemberStillLoadingIsKilledOnceLoaded(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = MockPlayers.joinUnloaded(helper, "wreck-loading");
		mock.teleportTo(helper.getLevel(), pod.position(), 0, 0);
		mock.player().startRiding(pod, true, false);
		wreck(pod);
		if (mock.player().isDeadOrDying()) {
			throw failure(helper, "a player whose client has not loaded is immune, so the first kill should fail");
		}
		mock.markLoaded();
		helper.succeedWhen(() -> {
			if (!mock.player().isDeadOrDying()) {
				throw failure(helper, "the crew member should die once the client has loaded");
			}
			pod.discard();
			clearFloor(helper);
		});
	}

	@GameTest
	public void aCreativeRiderDiesAndPlayersNotRidingLive(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer creative = joinLoaded(helper, "wreck-creative", pod.position());
		MockPlayer survival = joinLoaded(helper, "wreck-survival", pod.position());
		MockPlayer bystander = joinLoaded(helper, "wreck-bystander", pod.position().add(2, 0, 0));
		try {
			creative.player().setGameMode(GameType.CREATIVE);
			survival.player().setGameMode(GameType.SURVIVAL);
			// The Mole has one seat and even a forced second rider does not stay, so the rider is one at a time.
			creative.player().startRiding(pod, true, false);
			if (pod.getPassengers().size() != 1) {
				throw failure(helper, "setup: expected 1 rider, got %d", pod.getPassengers().size());
			}
			wreck(pod);
			if (!creative.player().isDeadOrDying()) {
				throw failure(helper, "a creative rider should die");
			}
			if (survival.player().isDeadOrDying()) {
				throw failure(helper, "a survival player who is not riding must not die");
			}
			if (bystander.player().isDeadOrDying()) {
				throw failure(helper, "crew who are not riding must not die");
			}
			helper.succeed();
		} finally {
			creative.leave();
			survival.leave();
			bystander.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest
	public void salvageDropsWhatDoesNotFitAtThePlayer(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = joinLoaded(helper, "wreck-full", pod.position().add(2, 0, 0));
		try {
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
			for (int slot = 0; slot < 36; slot++) {
				mock.player().getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
			}
			wreck(pod);
			int moved = Wrecks.salvage(mock.player(), pod);
			if (moved != 2 || oreInInventory(mock) != 0 || nearOre(mock.player()).size() != 2) {
				throw failure(helper, "2 ore should drop at the player: moved %d, held %d, dropped %d",
						moved, oreInInventory(mock), nearOre(mock.player()).size());
			}
			if (!pod.cargo().entries().isEmpty()) {
				throw failure(helper, "the bay should be empty");
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest
	public void salvageOfAnEmptyBayDoesNothing(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = joinLoaded(helper, "wreck-empty", pod.position().add(2, 0, 0));
		try {
			wreck(pod);
			if (Wrecks.salvage(mock.player(), pod) != 0 || oreInInventory(mock) != 0) {
				throw failure(helper, "an empty wreck gives nothing");
			}
			if (!UseEntityCallback.EVENT.invoker().interact(mock.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null).consumesAction()) {
				throw failure(helper, "using an empty wreck is still handled, so that the player is not seated or refuelled by accident");
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest
	public void salvageOfUnreadableCargoReportsAndKeepsTheCargo(GameTestHelper helper) {
		PodEntity original = helper.spawn(PodRegistry.POD, 2, 2, 2);
		original.cargo().tryAdd(original, OreRegistry.stack(OreType.GOLDIUM));
		PodEntity pod = reload(helper, original, null, tag -> tag.putInt("cargo_version", 99));
		helper.getLevel().addFreshEntity(pod);
		MockPlayer mock = joinLoaded(helper, "wreck-unreadable", pod.position().add(2, 0, 0));
		try {
			if (pod.cargo().isReadable()) {
				throw failure(helper, "setup: the cargo should be unreadable");
			}
			wreck(pod);
			if (Wrecks.salvage(mock.player(), pod) != 0 || oreInInventory(mock) != 0) {
				throw failure(helper, "unreadable cargo gives nothing");
			}
			UseEntityCallback.EVENT.invoker().interact(mock.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
			if (pod.cargo().isReadable()) {
				throw failure(helper, "salvage must leave the unreadable cargo as it was");
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
		}
	}

	@GameTest
	public void sneakingOnAWreckStillOnlyOpensTheCargoView(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = joinLoaded(helper, "wreck-sneak", pod.position().add(2, 0, 0));
		try {
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
			wreck(pod);
			mock.setInput(SNEAK);
			InteractionResult result = UseEntityCallback.EVENT.invoker().interact(mock.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
			if (!result.consumesAction() || !(mock.player().containerMenu instanceof OreCargoMenu)) {
				throw failure(helper, "sneak-use should open the cargo view, got %s with %s", result, mock.player().containerMenu);
			}
			if (oreInInventory(mock) != 0 || pod.cargo().entries().size() != 2) {
				throw failure(helper, "sneak-use must not salvage: held %d, bay %d", oreInInventory(mock), pod.cargo().entries().size());
			}
			helper.succeed();
		} finally {
			mock.releaseInput();
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
	public void aCrustBreachThatTakesTheLastHullKillsThePilotWhoDoesNotCross(GameTestHelper helper) {
		int x = 4600;
		int z = 3000;
		ServerLevel one = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		if (one == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(1));
		}
		for (BlockPos pos : BlockPos.betweenClosed(x - 2, 0, z - 2, x + 2, 2, z + 2)) {
			one.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		RoomCarver.carve(one, x - 2, x + 2, 1, 8, z - 2, z + 2, Blocks.AIR);
		MockPlayer pilot = MockPlayers.join(helper, "wreck-crust");
		pilot.teleportTo(one, new Vec3(x, 1, z), 0f, 0f);
		PodEntity[] pod = {null};
		long[] zeroSince = {-1};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 1, z), () -> {
			pilot.confirmDimensionChange();
			PodEntity created = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			created.setPos(x, 1, z);
			one.addFreshEntity(created);
			CRUST_PODS.add(created.getUUID());
			created.setHull(15f);
			pilot.player().startRiding(created, true, false);
			pilot.setInput(SPRINT);
			pod[0] = created;
		});
		helper.succeedWhen(() -> {
			if (pod[0] == null || pod[0].hull() != 0f) {
				throw failure(helper, "waiting for the crust bore to take the hull");
			}
			if (zeroSince[0] < 0) {
				zeroSince[0] = helper.getTick();
			}
			if (helper.getTick() - zeroSince[0] < 20) {
				throw failure(helper, "waiting to see whether the wreck crosses");
			}
			pilot.releaseInput();
			CRUST_PODS.remove(pod[0].getUUID());
			if (!pilot.player().isDeadOrDying() || !Wrecks.isWreck(pod[0])) {
				throw failure(helper, "the pilot should be dead and the pod a wreck, dead=%s wreck=%s health=%s loaded=%s vehicle=%s",
						pilot.player().isDeadOrDying(), Wrecks.isWreck(pod[0]), pilot.player().getHealth(),
						pilot.player().connection.hasClientLoaded(), pilot.player().getVehicle());
			}
			if (!pilot.player().level().dimension().equals(LayerChain.dimension(1)) || pod[0].isRemoved()) {
				throw failure(helper, "the wreck must stay in layer 1, pilot in %s", pilot.player().level().dimension());
			}
			pod[0].discard();
		});
	}

	private static MinecraftServer server(GameTestHelper helper) {
		return helper.getLevel().getServer();
	}

	/** Builds a bed in the floor corner and returns a respawn config that names it. */
	private static RespawnConfig bedConfig(GameTestHelper helper) {
		BlockPos foot = new BlockPos(0, FLOOR_Y + 1, 0);
		BlockState bed = Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
		helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
		helper.setBlock(foot.east(), bed.setValue(BedBlock.PART, BedPart.HEAD));
		return new RespawnConfig(LevelData.RespawnData.of(Level.OVERWORLD, helper.absolutePos(foot), 0f, 0f), false);
	}

	private static void clearBed(GameTestHelper helper) {
		// room-carver: removes a bed this test placed in the overworld test structure, not layer rock
		helper.setBlock(new BlockPos(0, FLOOR_Y + 1, 0), Blocks.AIR);
		// room-carver: removes a bed this test placed in the overworld test structure, not layer rock
		helper.setBlock(new BlockPos(1, FLOOR_Y + 1, 0), Blocks.AIR);
	}

	private static boolean near(Vec3 at, BlockPos block) {
		return at.distanceToSqr(Vec3.atBottomCenterOf(block)) < 4;
	}

	/** The player clicks Respawn: the server handles the packet as it would from a client. Returns the new player. */
	private static ServerPlayer respawn(ServerPlayer dead) {
		dead.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
		return dead.connection.getPlayer();
	}

	/** Runs {@code check} after the tick that moves a respawned crew to the office, then {@code cleanup}. */
	private static void afterRespawnTick(GameTestHelper helper, Runnable cleanup, Runnable check) {
		helper.runAfterDelay(2, () -> {
			try {
				check.run();
				helper.succeed();
			} finally {
				cleanup.run();
			}
		});
	}

	@GameTest
	public void aWreckKillRespawnsTheCrewAtTheOfficeEvenWithABedSet(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		GlobalPos office = Colony.respawnPoint(server).orElseThrow(() -> failure(helper, "the colony was not built when the server started"));
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = joinLoaded(helper, "wreck-respawn", pod.position());
		RespawnConfig bed = bedConfig(helper);
		ServerPlayer[] respawned = {null};
		Runnable cleanup = () -> {
			server.getPlayerList().remove(respawned[0] == null ? mock.player() : respawned[0]);
			mock.leave();
			pod.discard();
			clearBed(helper);
			clearFloor(helper);
		};
		try {
			ServerPlayer player = mock.player();
			player.setGameMode(GameType.SURVIVAL);
			player.setRespawnPosition(bed, false);
			player.startRiding(pod, true, false);
			wreck(pod);
			if (!player.isDeadOrDying()) {
				throw failure(helper, "the pilot should be dead");
			}
			respawned[0] = respawn(player);
			afterRespawnTick(helper, cleanup, () -> {
				if (!near(respawned[0].position(), office.pos())) {
					throw failure(helper, "the crew should respawn at the office %s, respawned at %s", office.pos().toShortString(), respawned[0].position());
				}
				if (!bed.equals(respawned[0].getRespawnConfig())) {
					throw failure(helper, "the redirect changes no player data: the bed should still be set, but the respawn point is %s", respawned[0].getRespawnConfig());
				}
			});
		} catch (RuntimeException e) {
			cleanup.run();
			throw e;
		}
	}

	@GameTest
	public void aCrewMemberWithNoBedRespawnsAtTheOfficeAndStillHasNoRespawnPoint(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		GlobalPos office = Colony.respawnPoint(server).orElseThrow(() -> failure(helper, "the colony was not built when the server started"));
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = joinLoaded(helper, "wreck-nobed", pod.position());
		ServerPlayer[] respawned = {null};
		Runnable cleanup = () -> {
			server.getPlayerList().remove(respawned[0] == null ? mock.player() : respawned[0]);
			mock.leave();
			pod.discard();
			clearFloor(helper);
		};
		try {
			mock.player().setGameMode(GameType.SURVIVAL);
			mock.player().startRiding(pod, true, false);
			wreck(pod);
			respawned[0] = respawn(mock.player());
			afterRespawnTick(helper, cleanup, () -> {
				if (!near(respawned[0].position(), office.pos())) {
					throw failure(helper, "the crew should respawn at the office %s, respawned at %s", office.pos().toShortString(), respawned[0].position());
				}
				if (respawned[0].getRespawnConfig() != null) {
					throw failure(helper, "the redirect changes no player data, but the respawn point is %s", respawned[0].getRespawnConfig());
				}
			});
		} catch (RuntimeException e) {
			cleanup.run();
			throw e;
		}
	}

	/**
	 * Runs {@code action} with the colony's saved data replaced by {@code replacement}, and puts the real one back.
	 * Nothing in {@code action} may await: GameTests run concurrently and would see the swap.
	 */
	private static void withColonySite(MinecraftServer server, ColonySite replacement, Runnable action) {
		WorldData.with(server, ColonySite.TYPE, replacement, action);
	}

	private static ColonySite unreadableColonySite(MinecraftServer server) {
		CompoundTag future = ((CompoundTag) ColonySite.CODEC.encodeStart(NbtOps.INSTANCE, ColonySite.get(server)).getOrThrow()).copy();
		future.putInt("version", 7742);
		return ColonySite.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
	}

	/** The colony's data is unreadable: the kill must not throw, the bed stays, and the owner is logged once however often they die. */
	@GameTest
	public void withoutAReadableColonyAWreckKillFallsBackToTheNormalRespawnAndLogsOncePerPlayer(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		PodEntity pod = spawnOnFloor(helper);
		String name = uniqueName();
		MockPlayer mock = joinLoaded(helper, name, pod.position());
		ServerPlayer[] respawned = {null};
		LogCapture log = LogCapture.start(name);
		try {
			RespawnConfig bed = bedConfig(helper);
			mock.player().setGameMode(GameType.SURVIVAL);
			mock.player().setRespawnPosition(bed, false);
			mock.player().startRiding(pod, true, false);
			withColonySite(server, unreadableColonySite(server), () -> {
				if (Colony.respawnPoint(server).isPresent()) {
					throw failure(helper, "setup: the colony should have no respawn point");
				}
				wreck(pod);
			});
			if (!mock.player().isDeadOrDying()) {
				throw failure(helper, "the pilot should still die");
			}
			if (!bed.equals(mock.player().getRespawnConfig())) {
				throw failure(helper, "with no office the respawn point must be left alone, it is %s", mock.player().getRespawnConfig());
			}
			respawned[0] = respawn(mock.player());
			if (!near(respawned[0].position(), bed.respawnData().pos())) {
				throw failure(helper, "with no office the crew should respawn at the bed, respawned at %s", respawned[0].position());
			}
			if (log.errors().size() != 1 || !log.warnings().isEmpty()) {
				throw failure(helper, "unreadable colony data should be logged as one error, was %s and %s", log.errors(), log.warnings());
			}
			PodEntity second = helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5));
			pod.discard();
			respawned[0].teleportTo(helper.getLevel(), second.getX(), second.getY(), second.getZ(), Set.of(), 0f, 0f, true);
			respawned[0].startRiding(second, true, false);
			withColonySite(server, unreadableColonySite(server), () -> wreck(second));
			second.discard();
			if (log.errors().size() != 1) {
				throw failure(helper, "a second kill of the same player should not log again, the errors are %s", log.errors());
			}
			helper.succeed();
		} finally {
			if (respawned[0] != null) {
				server.getPlayerList().remove(respawned[0]);
			}
			mock.leave();
			pod.discard();
			clearBed(helper);
			clearFloor(helper);
		}
	}

	@GameTest
	public void beforeTheColonyIsBuiltAWreckKillLogsAWarningNotAnError(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		PodEntity pod = spawnOnFloor(helper);
		String name = uniqueName();
		MockPlayer mock = joinLoaded(helper, name, pod.position());
		LogCapture log = LogCapture.start(name);
		try {
			mock.player().setGameMode(GameType.SURVIVAL);
			mock.player().startRiding(pod, true, false);
			withColonySite(server, new ColonySite(), () -> wreck(pod));
			if (!mock.player().isDeadOrDying()) {
				throw failure(helper, "the pilot should still die");
			}
			if (!log.errors().isEmpty() || log.warnings().size() != 1) {
				throw failure(helper, "a colony that is not built should be logged as one warning, was %s and %s", log.errors(), log.warnings());
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	/** A player marked to wake at the office who leaves and comes back dies an ordinary death: they wake at their bed. */
	@GameTest
	public void aCrewMemberWhoLeavesAndReturnsBeforeRespawningWakesAtTheirBed(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer mock = MockPlayers.joinUnloaded(helper, "wreck-leaver");
		UUID id = mock.player().getUUID();
		RespawnConfig bed = bedConfig(helper);
		MockPlayer[] returned = {null};
		ServerPlayer[] respawned = {null};
		Runnable cleanup = () -> {
			if (respawned[0] != null) {
				server.getPlayerList().remove(respawned[0]);
			}
			if (returned[0] != null) {
				returned[0].leave();
			}
			mock.leave();
			pod.discard();
			clearBed(helper);
			clearFloor(helper);
		};
		try {
			ServerPlayer player = mock.player();
			player.setRespawnPosition(bed, false);
			mock.teleportTo(helper.getLevel(), pod.position(), 0, 0);
			player.startRiding(pod, true, false);
			wreck(pod);
			if (player.isDeadOrDying()) {
				throw failure(helper, "setup: the player is immune, and the kill is retried");
			}
			mock.leave();
			returned[0] = MockPlayers.join(helper, "wreck-leaver", id);
			returned[0].player().setGameMode(GameType.SURVIVAL);
			returned[0].player().setRespawnPosition(bed, false);
			returned[0].player().kill(helper.getLevel());
			respawned[0] = respawn(returned[0].player());
			afterRespawnTick(helper, cleanup, () -> {
				if (!near(respawned[0].position(), bed.respawnData().pos())) {
					throw failure(helper, "a player who left and came back should wake at their bed, woke at %s", respawned[0].position());
				}
			});
		} catch (RuntimeException e) {
			cleanup.run();
			throw e;
		}
	}

	private static PodEntity reload(GameTestHelper helper, PodEntity pod, CompoundTag attachments) {
		return reload(helper, pod, attachments, tag -> {
		});
	}

	/** Saves {@code pod} (replacing its attachments with {@code attachments} if given), discards it and loads a new pod from the data. */
	private static PodEntity reload(GameTestHelper helper, PodEntity pod, CompoundTag attachments, Consumer<CompoundTag> edit) {
		ServerLevel level = helper.getLevel();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		pod.discard();
		CompoundTag tag = output.buildResult();
		if (attachments != null) {
			tag.put(ATTACHMENTS_KEY, attachments);
		}
		edit.accept(tag);
		return (PodEntity) EntityType.create(PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
				level, EntitySpawnReason.LOAD).orElseThrow(() -> failure(helper, "the saved pod did not load"));
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
