package io.github.pkeppeler.deepcharter.test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.serialization.Dynamic;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Server GameTests for #65: parts change a pod's stats, a chassis caps their tier, another charter's parts are void,
 * the state survives a reload, and only the owner's charter can pilot a pod.
 *
 * <p>Towing is not tested for non-members: it does not exist yet (#76), and its test belongs there.
 */
public class PodComponentsTest {
	private static final float EPSILON = 0.001f;
	private static final String ATTACHMENTS_KEY = "fabric:attachments";
	private static final String BOOSTED = "pod-components-test-boosted";
	private static final AtomicInteger CHARTERS = new AtomicInteger();
	private static final UpgradeTuning TUNING = UpgradeTuning.DEFAULT;

	static {
		// Runs in the default phase, which is before CAP: a cap must still limit what this adds. Marked pods only.
		PodStats.MODIFY.register((pod, stats) -> pod.entityTags().contains(BOOSTED) ? stats.withMaxHull(stats.maxHull() + 1000f) : stats);
	}

	@GameTest
	public void partsChangeWhatThePodCanDo(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity pod = ownedPod(helper, charter);
		try {
			PodStats stock = PodStats.of(pod);
			expectEqual(helper, "stock hull", PodStats.base().maxHull(), stock.maxHull());
			install(helper, pod, ComponentTrack.HULL, 2, charter);
			install(helper, pod, ComponentTrack.CARGO_BAY, 1, charter);
			install(helper, pod, ComponentTrack.ENGINE, 2, charter);
			install(helper, pod, ComponentTrack.DRILL, 2, charter);
			install(helper, pod, ComponentTrack.FUEL_TANK, 1, charter);
			PodStats stats = PodStats.of(pod);
			expectEqual(helper, "hull tier 2 maxHull", 300f, stats.maxHull());
			expectEqual(helper, "bay tier 1 slots", 15f, stats.cargoSlots());
			expectEqual(helper, "engine tier 2 power", stock.enginePower() * 170f / 150f, stats.enginePower());
			expectEqual(helper, "engine tier 2 speed", stock.horizontalSpeed() * 170f / 150f, stats.horizontalSpeed());
			expectEqual(helper, "drill tier 2 ticks per hardness", stock.ticksPerHardness() / 2f, stats.ticksPerHardness());
			expectEqual(helper, "tank tier 1 litres", 15f, stats.tankLitres());

			// The pod really behaves differently, not only its stats record.
			pod.setHull(1000f);
			expectEqual(helper, "hull held at the new maximum", 300f, pod.hull());
			for (int i = 0; i < 15; i++) {
				if (!pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM))) {
					throw failure(helper, "a 15-slot bay refused ore number %d", i + 1);
				}
			}
			if (pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM))) {
				throw failure(helper, "a 15-slot bay took a 16th ore");
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void aStockPodAndAPodWithOnlyStockPartsAreTheSame(GameTestHelper helper) {
		PodEntity pod = ownedPod(helper, charter(helper));
		try {
			if (!PodStats.of(pod).equals(PodStats.base())) {
				throw failure(helper, "a pod with no parts should have the stock stats, got %s", PodStats.of(pod));
			}
			for (ComponentTrack track : ComponentTrack.values()) {
				if (PodComponents.partOf(pod, track).isPresent() || PodComponents.effectiveTier(pod, track) != 0) {
					throw failure(helper, "a new Mole should have no %s part and tier 0", track);
				}
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void aBiggerTankKeepsTheLitresAndABiggerHullKeepsTheDamage(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity pod = ownedPod(helper, charter);
		try {
			pod.setFuel(60f);
			pod.setHull(70f);
			install(helper, pod, ComponentTrack.FUEL_TANK, 1, charter);
			// 60% of 10 litres is 6 litres, which is 40% of 15.
			expectEqual(helper, "fuel percent after a bigger tank", 40f, pod.fuel());
			install(helper, pod, ComponentTrack.HULL, 1, charter);
			// 30 points were taken off 100, and 30 stay taken off 170.
			expectEqual(helper, "hull after a bigger hull", 140f, pod.hull());
			Optional<PartLabel> replaced = install(helper, pod, ComponentTrack.FUEL_TANK, 2, charter);
			if (replaced.isEmpty() || replaced.get().tier() != 1) {
				throw failure(helper, "installing over the tier 1 tank should return it, got %s", replaced);
			}
			// 6 litres of 15 is 40%; a 25 litre tank holds the same 6 litres as 24%.
			expectEqual(helper, "fuel percent after the next tank", 24f, pod.fuel());
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void rescaleFuelKeepsTheLitresAndClampsToFull(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity pod = ownedPod(helper, charter);
		try {
			PodStats tank = PodStats.base().withTankLitres(10f);
			pod.setFuel(60f);
			PodComponents.rescaleFuel(pod, tank, tank.withTankLitres(10f));
			expectEqual(helper, "fuel after an equal tank", 60f, pod.fuel());
			PodComponents.rescaleFuel(pod, tank, tank.withTankLitres(20f));
			expectEqual(helper, "fuel after a doubled tank", 30f, pod.fuel());
			PodComponents.rescaleFuel(pod, tank.withTankLitres(20f), tank.withTankLitres(1f));
			expectEqual(helper, "fuel after a tank 20 times smaller", 100f, pod.fuel());
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void aTierThreePartOnAMoleDoesExactlyWhatTierTwoDoes(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity capped = ownedPod(helper, charter);
		PodEntity within = ownedPod(helper, charter);
		try {
			for (ComponentTrack track : new ComponentTrack[] {ComponentTrack.HULL, ComponentTrack.CARGO_BAY, ComponentTrack.FUEL_TANK,
					ComponentTrack.ENGINE, ComponentTrack.DRILL}) {
				install(helper, capped, track, 4, charter);
				install(helper, within, track, 2, charter);
			}
			install(helper, capped, ComponentTrack.SCANNER, 4, charter);
			if (!PodStats.of(capped).equals(PodStats.of(within))) {
				throw failure(helper, "tier 4 parts on a Mole should give the stats of tier 2: %s against %s", PodStats.of(capped), PodStats.of(within));
			}
			expectEqual(helper, "tier 4 hull on a Mole", 300f, PodStats.of(capped).maxHull());
			expectEqual(helper, "tier 4 scanner on a Mole", 2f, PodComponents.effectiveTier(capped, ComponentTrack.SCANNER));
			expectEqual(helper, "the part itself keeps its tier", 4f, PodComponents.partOf(capped, ComponentTrack.HULL).orElseThrow().tier());

			// Another feature's bonus on the same track still counts: the cap limits the part, not the stat.
			capped.addTag(BOOSTED);
			expectEqual(helper, "a boost on a capped hull", 1300f, PodStats.of(capped).maxHull());
			helper.succeed();
		} finally {
			capped.discard();
			within.discard();
		}
	}

	@GameTest
	public void aPartWithinTheCapIsNotLimited(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity pod = ownedPod(helper, charter);
		try {
			install(helper, pod, ComponentTrack.HULL, TUNING.tierCap("mole"), charter);
			expectEqual(helper, "tier 2 hull", 300f, PodStats.of(pod).maxHull());
			expectEqual(helper, "tier 2 hull effective tier", 2f, PodComponents.effectiveTier(pod, ComponentTrack.HULL));
			// The cap limits parts above it, not what other features add to a stat that no such part touches.
			pod.addTag(BOOSTED);
			expectEqual(helper, "a boost on a hull within the cap", 1300f, PodStats.of(pod).maxHull());
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void partsFromAnotherCharterAreVoid(GameTestHelper helper) {
		CharterId owner = charter(helper);
		CharterId other = charter(helper);
		PodEntity pod = ownedPod(helper, owner);
		try {
			install(helper, pod, ComponentTrack.HULL, 2, other);
			install(helper, pod, ComponentTrack.SCANNER, 1, other);
			if (!PodStats.of(pod).equals(PodStats.base())) {
				throw failure(helper, "another charter's hull should do nothing, got %s", PodStats.of(pod));
			}
			expectEqual(helper, "a void scanner's tier", 0f, PodComponents.effectiveTier(pod, ComponentTrack.SCANNER));
			if (PodComponents.partOf(pod, ComponentTrack.HULL).isEmpty()) {
				throw failure(helper, "a void part is still installed, only without effect");
			}
			// The pod's own charter's part next to it works.
			install(helper, pod, ComponentTrack.CARGO_BAY, 1, owner);
			expectEqual(helper, "the owner's bay", 15f, PodStats.of(pod).cargoSlots());
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void aPodWithNoOwnerIgnoresEveryPart(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			install(helper, pod, ComponentTrack.HULL, 2, charter);
			if (!PodStats.of(pod).equals(PodStats.base())) {
				throw failure(helper, "a pod nobody owns has no charter whose parts count, got %s", PodStats.of(pod));
			}
			if (PodComponents.registration(pod).isPresent()) {
				throw failure(helper, "a spawned pod should have no registration");
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void partItemsCarryTrackTierCharterAndSerial(GameTestHelper helper) {
		CharterId charter = charter(helper);
		MinecraftServer server = helper.getLevel().getServer();
		Set<String> serials = new HashSet<>();
		for (ComponentTrack track : ComponentTrack.values()) {
			ItemStack stack = ComponentItems.mint(server, track, 1, charter);
			PartLabel label = ComponentItems.labelOf(stack).orElseThrow(() -> failure(helper, "%s part has no label", track));
			if (ComponentItems.trackOf(stack).orElseThrow() != track || label.tier() != 1 || !label.charter().equals(charter)) {
				throw failure(helper, "%s part has the wrong stamp: %s", track, label);
			}
			if (!label.serial().matches("[A-Z_]+-\\d{4,}") || !serials.add(label.serial())) {
				throw failure(helper, "%s part serial %s is not a new serial", track, label.serial());
			}
		}
		for (ComponentTrack track : ComponentTrack.values()) {
			try {
				ComponentItems.mint(server, track, track.maxTier() + 1, charter);
				throw failure(helper, "a %s tier above %d must be refused", track, track.maxTier());
			} catch (IllegalArgumentException expected) {
				// Expected.
			}
		}
		helper.succeed();
	}

	@GameTest
	public void installRefusesAStackThatIsNotALabelledPart(GameTestHelper helper) {
		PodEntity pod = ownedPod(helper, charter(helper));
		try {
			for (ItemStack bad : new ItemStack[] {new ItemStack(Items.STICK), new ItemStack(ComponentItems.item(ComponentTrack.DRILL))}) {
				try {
					PodComponents.install(pod, bad);
					throw failure(helper, "installing %s must be refused", bad);
				} catch (IllegalArgumentException expected) {
					// Expected.
				}
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void everyPodGetsItsOwnSerial(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity first = ownedPod(helper, charter);
		PodEntity second = ownedPod(helper, charter);
		try {
			String a = PodComponents.registration(first).orElseThrow().serial();
			String b = PodComponents.registration(second).orElseThrow().serial();
			if (!a.matches("MOLE-\\d{4,}") || !b.matches("MOLE-\\d{4,}") || a.equals(b)) {
				throw failure(helper, "two Moles should have two MOLE-nnnn serials, got %s and %s", a, b);
			}
			if (Integer.parseInt(b.substring(5)) != Integer.parseInt(a.substring(5)) + 1) {
				throw failure(helper, "serials should count up by one, got %s then %s", a, b);
			}
			try {
				PodComponents.register(first, charter);
				throw failure(helper, "a pod that has an owner cannot be registered again");
			} catch (IllegalStateException expected) {
				// Expected.
			}
			helper.succeed();
		} finally {
			first.discard();
			second.discard();
		}
	}

	@GameTest
	public void componentsOwnerAndSerialSurviveSaveAndLoad(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity pod = ownedPod(helper, charter);
		install(helper, pod, ComponentTrack.HULL, 2, charter);
		install(helper, pod, ComponentTrack.ENGINE, 1, charter);
		install(helper, pod, ComponentTrack.SCANNER, 1, charter);
		PodComponents.Registration registration = PodComponents.registration(pod).orElseThrow();
		PartLabel hull = PodComponents.partOf(pod, ComponentTrack.HULL).orElseThrow();
		PodStats before = PodStats.of(pod);
		Entity loaded = reload(helper, pod, null);
		try {
			PodEntity copy = (PodEntity) loaded;
			if (!PodComponents.registration(copy).equals(Optional.of(registration))) {
				throw failure(helper, "owner and serial did not survive: %s, wanted %s", PodComponents.registration(copy), registration);
			}
			if (!PodComponents.partOf(copy, ComponentTrack.HULL).equals(Optional.of(hull))
					|| PodComponents.partOf(copy, ComponentTrack.ENGINE).isEmpty() || PodComponents.partOf(copy, ComponentTrack.SCANNER).isEmpty()
					|| PodComponents.partOf(copy, ComponentTrack.DRILL).isPresent()) {
				throw failure(helper, "the installed parts did not survive");
			}
			if (!PodStats.of(copy).equals(before)) {
				throw failure(helper, "the reloaded pod has other stats: %s, wanted %s", PodStats.of(copy), before);
			}
			// Version 1 is the first format of this attachment, so there is no earlier format to load. A new version must
			// add that test here, next to the unknown-version test below.
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	/** The real load path: an unknown version must load, leave the pod usable on stock stats, and be written back unchanged. */
	@GameTest
	public void anUnreadableComponentStateIsKeptAndTheTickAndMountPathsSkipIt(GameTestHelper helper) {
		String key = PodComponents.STATE.identifier().toString();
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putString("added-in-v99", "kept");
		CompoundTag attachments = new CompoundTag();
		attachments.put(key, future);
		PodEntity pod = ownedPod(helper, charter(helper));
		MockPlayer player = MockPlayers.join(helper, "components-unreadable");
		Entity loaded = reload(helper, pod, attachments);
		try {
			PodEntity copy = (PodEntity) loaded;
			if (!(copy.getAttached(PodComponents.STATE) instanceof Versioned.Unreadable<PodComponents.State>)) {
				throw failure(helper, "version 99 should load as Unreadable, got %s", copy.getAttached(PodComponents.STATE));
			}
			// None of these may throw: they run every tick and on every boarding.
			if (!PodStats.of(copy).equals(PodStats.base())) {
				throw failure(helper, "a pod with unreadable parts should run on stock stats, got %s", PodStats.of(copy));
			}
			if (PodComponents.registration(copy).isPresent() || PodComponents.partOf(copy, ComponentTrack.HULL).isPresent()
					|| PodComponents.effectiveTier(copy, ComponentTrack.HULL) != 0) {
				throw failure(helper, "an unreadable state should read as nothing");
			}
			if (PodEvents.canMount(copy, player.player())) {
				throw failure(helper, "a pod whose owner cannot be read must refuse every pilot");
			}
			// An explicit change fails loud instead of overwriting the kept data.
			try {
				PodComponents.install(copy, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.HULL, 1, charter(helper)));
				throw failure(helper, "installing into an unreadable state must fail");
			} catch (IllegalStateException expected) {
				if (!expected.getMessage().contains(key)) {
					throw failure(helper, "the failure should name the attachment, got: %s", expected.getMessage());
				}
			}
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
			copy.saveWithoutId(output);
			CompoundTag saved = output.buildResult().getCompound(ATTACHMENTS_KEY).orElseThrow(() -> failure(helper, "the pod saved no attachments"));
			if (!future.equals(saved.get(key))) {
				throw failure(helper, "the unreadable data was not written back unchanged: %s", saved.get(key));
			}
			helper.succeed();
		} finally {
			player.leave();
			loaded.discard();
		}
	}

	@GameTest
	public void theStateCodecNeverFails(GameTestHelper helper) {
		CompoundTag noVersion = new CompoundTag();
		noVersion.putString("parts", "not a map");
		var codec = PodComponents.STATE.persistenceCodec();
		Versioned<PodComponents.State> read = codec.parse(NbtOps.INSTANCE, noVersion).getOrThrow();
		if (!(read instanceof Versioned.Unreadable<PodComponents.State> unreadable) || !noVersion.equals(unreadable.raw())) {
			throw failure(helper, "a state with no version should decode to Unreadable holding it, got %s", read);
		}
		if (!noVersion.equals(codec.encodeStart(NbtOps.INSTANCE, read).getOrThrow())) {
			throw failure(helper, "the unreadable state was not re-encoded unchanged");
		}
		CompoundTag current = new CompoundTag();
		current.putInt("version", PodComponents.VERSION);
		current.put("parts", new CompoundTag());
		if (!Versioned.of(PodComponents.State.EMPTY).equals(codec.parse(new Dynamic<>(NbtOps.INSTANCE, current)).getOrThrow())) {
			throw failure(helper, "the current version should decode to an empty state");
		}
		helper.succeed();
	}

	@GameTest
	public void unreadableSavedSerialsAreKeptAndRefuseToIssue(GameTestHelper helper) {
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putString("added-in-v99", "kept");
		Serials serials = Serials.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
		try {
			serials.next("MOLE");
			throw failure(helper, "an unreadable serial table must not issue a serial");
		} catch (IllegalStateException expected) {
			if (!expected.getMessage().contains("99")) {
				throw failure(helper, "the failure should name the version, got: %s", expected.getMessage());
			}
		}
		if (!future.equals(Serials.CODEC.encodeStart(NbtOps.INSTANCE, serials).getOrThrow())) {
			throw failure(helper, "the unreadable serials were not written back unchanged");
		}
		Serials fresh = new Serials();
		String first = fresh.next("DRILL");
		Serials loaded = Serials.CODEC.parse(NbtOps.INSTANCE, Serials.CODEC.encodeStart(NbtOps.INSTANCE, fresh).getOrThrow()).getOrThrow();
		if (!"DRILL-0001".equals(first) || !"DRILL-0002".equals(loaded.next("DRILL")) || !"MOLE-0001".equals(loaded.next("MOLE"))) {
			throw failure(helper, "serials should count per prefix and survive a save, got %s", first);
		}
		helper.succeed();
	}

	@GameTest
	public void onlyMembersOfTheOwnerCharterCanPilot(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer director = MockPlayers.join(helper, "components-director");
		MockPlayer crew = MockPlayers.join(helper, "components-crew");
		MockPlayer outsider = MockPlayers.join(helper, "components-outsider");
		MockPlayer rival = MockPlayers.join(helper, "components-rival");
		PodEntity pod = null;
		try {
			expectNoRefusal(helper, Charters.found(server, director.player().getUUID(), "Podowners " + CHARTERS.incrementAndGet()));
			CharterId owner = Charters.charterOf(server, director.player().getUUID()).orElseThrow().id();
			expectNoRefusal(helper, Charters.apply(server, crew.player().getUUID(), owner));
			expectNoRefusal(helper, Charters.approve(server, director.player().getUUID(), crew.player().getUUID()));
			expectNoRefusal(helper, Charters.found(server, rival.player().getUUID(), "Rivals " + CHARTERS.incrementAndGet()));
			pod = ownedPod(helper, owner);

			if (outsider.player().startRiding(pod)) {
				throw failure(helper, "a player on no charter must not board");
			}
			if (rival.player().startRiding(pod)) {
				throw failure(helper, "a member of another charter must not board");
			}
			InteractionResult used = pod.interact(outsider.player(), InteractionHand.MAIN_HAND, pod.position());
			if (used.consumesAction() || !pod.getPassengers().isEmpty()) {
				throw failure(helper, "using the pod as an outsider must not board, got %s", used);
			}
			if (!crew.player().startRiding(pod)) {
				throw failure(helper, "crew of the owner charter should board");
			}
			crew.player().stopRiding();
			if (!director.player().startRiding(pod)) {
				throw failure(helper, "the Director of the owner charter should board");
			}
			helper.succeed();
		} finally {
			for (MockPlayer mock : new MockPlayer[] {director, crew, outsider, rival}) {
				mock.leave();
			}
			if (pod != null) {
				pod.discard();
			}
		}
	}

	@GameTest
	public void aDormantOrMissingOwnerCharterLocksNobodyOut(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer director = MockPlayers.join(helper, "components-dormant-director");
		MockPlayer stranger = MockPlayers.join(helper, "components-dormant-stranger");
		PodEntity dormantPod = null;
		PodEntity missingPod = null;
		try {
			expectNoRefusal(helper, Charters.found(server, director.player().getUUID(), "Dormant " + CHARTERS.incrementAndGet()));
			CharterId owner = Charters.charterOf(server, director.player().getUUID()).orElseThrow().id();
			dormantPod = ownedPod(helper, owner);
			missingPod = ownedPod(helper, CharterId.random());
			if (stranger.player().startRiding(dormantPod)) {
				throw failure(helper, "a stranger must not board a pod of a charter that has people");
			}
			expectNoRefusal(helper, Charters.leave(server, director.player().getUUID()));
			if (!Charters.find(server, owner).orElseThrow().dormant()) {
				throw failure(helper, "the charter should be dormant now");
			}
			if (!stranger.player().startRiding(dormantPod)) {
				throw failure(helper, "a pod of a dormant charter should be anyone's");
			}
			stranger.player().stopRiding();
			if (!stranger.player().startRiding(missingPod)) {
				throw failure(helper, "a pod of a charter that does not exist should be anyone's");
			}
			helper.succeed();
		} finally {
			director.leave();
			stranger.leave();
			if (dormantPod != null) {
				dormantPod.discard();
			}
			if (missingPod != null) {
				missingPod.discard();
			}
		}
	}

	/** Pod ownership is the charter id, so a revived charter takes its pods back; a wreck stays a wreck and nothing done meanwhile is undone. */
	@GameTest
	public void aRevivedCharterTakesItsPodsBackAndTheirWreckStateStays(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer director = MockPlayers.join(helper, "components-revive-director");
		MockPlayer reviver = MockPlayers.join(helper, "components-revive-new");
		MockPlayer stranger = MockPlayers.join(helper, "components-revive-stranger");
		PodEntity pod = null;
		try {
			expectNoRefusal(helper, Charters.found(server, director.player().getUUID(), "Revived " + CHARTERS.incrementAndGet()));
			CharterId owner = Charters.charterOf(server, director.player().getUUID()).orElseThrow().id();
			pod = ownedPod(helper, owner);
			pod.damageHull(pod.maxHull());
			expectNoRefusal(helper, Charters.leave(server, director.player().getUUID()));
			if (!PodComponents.mayAccess(pod, Charters.charterOf(server, stranger.player().getUUID()))) {
				throw failure(helper, "while the charter is dormant its pod is anyone's");
			}
			if (PodComponents.ownerCharter(pod).isPresent()) {
				throw failure(helper, "a dormant charter owns no pod for the others");
			}

			expectNoRefusal(helper, Charters.revive(server, reviver.player().getUUID(), owner));
			if (PodComponents.ownerCharter(pod).map(Charter::id).filter(owner::equals).isEmpty()) {
				throw failure(helper, "the revived charter owns its pod again");
			}
			if (stranger.player().startRiding(pod)) {
				throw failure(helper, "a stranger must not board the pod of a revived charter");
			}
			if (!Wrecks.isWreck(pod)) {
				throw failure(helper, "reviving a charter does not repair its wrecked pod");
			}
			if (!PodComponents.mayAccess(pod, Charters.charterOf(server, reviver.player().getUUID()))) {
				throw failure(helper, "the new Director may act on the pod");
			}
			helper.succeed();
		} finally {
			director.leave();
			reviver.leave();
			stranger.leave();
			if (pod != null) {
				pod.discard();
			}
		}
	}

	@GameTest
	public void registeringAPodWithPartsAlreadyInstalledKeepsLitresAndDamage(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			install(helper, pod, ComponentTrack.FUEL_TANK, 1, charter);
			install(helper, pod, ComponentTrack.HULL, 1, charter);
			pod.setFuel(60f);
			pod.setHull(70f);
			// Until now the parts were void. The owner makes them count: the tank grows and the hull grows.
			PodComponents.register(pod, charter);
			expectEqual(helper, "tank litres after registering", 15f, PodStats.of(pod).tankLitres());
			expectEqual(helper, "fuel percent keeps the 6 litres", 40f, pod.fuel());
			expectEqual(helper, "hull keeps the 30 points of damage", 140f, pod.hull());
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void unreadableSavedChartersAreSkippedWhenCheckingOwnership(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer player = MockPlayers.join(helper, "components-bad-charters");
		PodEntity pod = ownedPod(helper, charter(helper));
		CharterData good = CharterData.get(server);
		try {
			CompoundTag future = new CompoundTag();
			future.putInt("version", 99);
			server.getDataStorage().set(CharterData.TYPE, CharterData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow());
			if (!PodEvents.canMount(pod, player.player())) {
				throw failure(helper, "with unreadable charters the owner cannot be checked, so the pod must not lock");
			}
			helper.succeed();
		} finally {
			server.getDataStorage().set(CharterData.TYPE, good);
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void anUnownedPodCanBePilotedByAnyone(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "components-anyone");
		try {
			if (!player.player().startRiding(pod)) {
				throw failure(helper, "a pod nobody owns is anyone's, as in M1");
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void anyoneCanRefuelAPodTheyCannotPilot(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer owner = MockPlayers.join(helper, "components-fuel-owner");
		MockPlayer outsider = MockPlayers.join(helper, "components-fuel-outsider");
		PodEntity pod = null;
		try {
			expectNoRefusal(helper, Charters.found(server, owner.player().getUUID(), "Refuelled " + CHARTERS.incrementAndGet()));
			pod = ownedPod(helper, Charters.charterOf(server, owner.player().getUUID()).orElseThrow().id());
			pod.setFuel(50f);
			outsider.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
			InteractionResult result = UseEntityCallback.EVENT.invoker().interact(
					outsider.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
			if (!result.consumesAction() || pod.fuel() <= 50f) {
				throw failure(helper, "an outsider's coal should refuel the pod: result %s, fuel %s", result, pod.fuel());
			}
			if (outsider.player().startRiding(pod)) {
				throw failure(helper, "refuelling must not let the outsider pilot");
			}
			// Towing by anyone is part of the same rule but towing does not exist yet: #76 adds that test.
			helper.succeed();
		} finally {
			owner.leave();
			outsider.leave();
			if (pod != null) {
				pod.discard();
			}
		}
	}

	@GameTest
	public void theCargoMenuHasAsManySlotsAsTheBayStat(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity pod = ownedPod(helper, charter);
		MockPlayer player = MockPlayers.join(helper, "components-cargo-menu");
		try {
			OreCargoMenu.open(player.player(), pod);
			if (!(player.player().containerMenu instanceof OreCargoMenu stock) || stock.cargoSlots() != PodStats.base().cargoSlots()) {
				throw failure(helper, "a stock bay's menu should have %d slots, got %s", PodStats.base().cargoSlots(), player.player().containerMenu);
			}
			install(helper, pod, ComponentTrack.CARGO_BAY, 2, charter);
			for (int i = 0; i < 12; i++) {
				pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
			}
			OreCargoMenu.open(player.player(), pod);
			if (!(player.player().containerMenu instanceof OreCargoMenu menu) || menu.cargoSlots() != 25) {
				throw failure(helper, "a tier 2 bay's menu should have 25 slots, got %s", player.player().containerMenu);
			}
			if (menu.slots.size() != 25 || menu.shownOre().size() != 12) {
				throw failure(helper, "the menu should have 25 slots and show all 12 ore, has %d slots and shows %d",
						menu.slots.size(), menu.shownOre().size());
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	private static CharterId charter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		UUID founder = UUID.randomUUID();
		expectNoRefusal(helper, Charters.found(server, founder, "Components " + CHARTERS.incrementAndGet()));
		return Charters.charterOf(server, founder).orElseThrow().id();
	}

	private static PodEntity ownedPod(GameTestHelper helper, CharterId owner) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodComponents.register(pod, owner);
		return pod;
	}

	private static Optional<PartLabel> install(GameTestHelper helper, PodEntity pod, ComponentTrack track, int tier, CharterId charter) {
		return PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), track, tier, charter));
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

	private static void expectEqual(GameTestHelper helper, String what, float expected, float actual) {
		if (Math.abs(expected - actual) > EPSILON) {
			throw failure(helper, "%s: expected %s, got %s", what, expected, actual);
		}
	}

	private static void expectNoRefusal(GameTestHelper helper, Optional<?> refusal) {
		if (refusal.isPresent()) {
			throw failure(helper, "a charter step was refused: %s", refusal.get());
		}
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
