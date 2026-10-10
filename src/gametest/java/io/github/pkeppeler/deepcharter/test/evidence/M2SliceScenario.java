package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.hangar.Hangar;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrders;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.M2SliceEndState;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Evidence scenario "m2-slice" for #85: the whole M2 vertical slice, in order, on one dedicated server. The real client is the
 * player, and a mock crewmate joins the same charter. The colony's terminals, the founding Mole, the pump, the drill and the breach,
 * the processor, the upgrade terminal, the scanner, the handbook, the lampless figure, PROSPECTOR-0002's wreck and note, the tow,
 * the hangar's restoration and the Founder's hands work order all use the mod's real systems. What is skipped (grinding, the long
 * climbs and descents, the crafting of parts) is stated on screen as it happens. See the PR's list of shortcuts.
 */
public class M2SliceScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 3;
	private static final int HOLD_FRAMES = 6;
	private static final int WAIT_TICKS = 600;
	/** Chat lines (the join messages) stay on screen for 10 seconds. */
	private static final int CHAT_FADE_TICKS = 220;

	/** Colony ground is y -61 in the test world's flat overworld, so a player stands at y -60. */
	private static final int FEET_Y = -60;
	/** A Mole parked here is within the pump's, the processor's and the upgrade terminal's parking radius (8). */
	private static final Vec3 PARK = new Vec3(-6, FEET_Y, -4);
	/** A player stands here, 2.5 blocks south of the terminals' row, to use one. */
	private static final double VIEW_Z = -5.5;
	/**
	 * The Mole bores under PARK and crosses back up through it, and the breach carves a pocket round the crossing that reaches 2
	 * blocks each way: x -8 to -3 and z -6 to -1. The processor is used from beside that pocket, not from inside it.
	 */
	private static final double PROCESSOR_VIEW_X = -2.5;

	/** The room at the floor of layer 1, in the Deep Claim, far from everything else. */
	private static final int X = 4000;
	private static final int Z = 4000;
	private static final int FLOOR_Y = 4;
	private static final int ROOM_WEST = 6;
	private static final int ROOM_EAST = 16;
	private static final int ROOM_RADIUS_Z = 6;

	private TwoPlayerServer two;
	private ClientGameTestContext ctx;
	private ColonySite.Placed colony;
	private CharterId charter;
	private UUID mole;
	private UUID prospector;
	/** Read by the render thread, which draws it over the world and over every screen. */
	private static volatile Component caption = Component.empty();
	private static boolean captionsRegistered;
	private int captured;
	private int beat;

	@Override
	protected String name() {
		return "m2-slice";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		registerCaptions();
		try (TwoPlayerServer server = TwoPlayerServer.start(context)) {
			two = server;
			ctx = context;
			colony = onServer(minecraft -> Colony.placed(minecraft).orElseThrow(() -> new AssertionError("the colony was not built when the world started")));
			List<Runnable> beats = List.of(this::foundTheCharter, this::starterItems, this::repairColonyTerminals, this::repairFoundingMole,
					this::refuel, this::boardAndDrillDown, this::sellOre, this::buyScanner, this::diveToTheDeepClaim, this::crossTheBreach,
					this::lamplessFigure, this::wreckSite, this::towHome, this::repairHullAndRestore, this::workOrder, this::theOldWorkingsFloor);
			for (int i = 0; i < beats.size(); i++) {
				beat = i + 1;
				beats.get(i).run();
			}
		}
	}

	// ---------------------------------------------------------------- beats

	private void foundTheCharter() {
		onServer(server -> {
			ServerPlayer real = real(server);
			real.setPermanentlyInvulnerable(true);
			command(server, "time set noon");
			command(server, "weather clear");
			return null;
		});
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
		stand(new Vec3(12, FEET_Y, 8), Vec3.atCenterOf(colony.anchors().get(ColonyAnchor.STATUE)).add(0, 2, 0));
		say("M2 slice, from the start. Real player and a mock crewmate on a dedicated server.");
		// The join messages: waited out, with the colony in view.
		snap(CHAT_FADE_TICKS / TICKS_PER_FRAME);
		ctx.runOnClient(client -> {
			client.gui.toastManager().clear();
			client.player.getInventory().setSelectedSlot(8);
		});
		still("01-colony-at-spawn");

		say("1. The player founds a charter. The mock crewmate applies and the Director approves.");
		onServer(server -> {
			ServerPlayer real = real(server);
			expectNoRefusal(Charters.found(server, real.getUUID(), "Riggs and Sons"), "founding");
			charter = Charters.charterOfOrThrow(server, real.getUUID()).orElseThrow().id();
			ServerPlayer mock = two.mock().player();
			expectNoRefusal(Charters.apply(server, mock.getUUID(), charter), "applying");
			expectNoRefusal(Charters.approve(server, real.getUUID(), mock.getUUID()), "approving");
			two.mock().teleportTo(server.overworld(), new Vec3(11, FEET_Y, 6), 90f, 0f);
			return null;
		});
		snap(HOLD_FRAMES);
		still("02-charter-founded-with-crewmate");
		onServer(server -> {
			Charter founded = Charters.findOrThrow(server, charter).orElseThrow();
			if (!founded.onRoster(two.mock().player().getUUID()) || !founded.onRoster(real(server).getUUID())) {
				throw new AssertionError("both players should be on the charter's roster");
			}
			return null;
		});
	}

	private void starterItems() {
		say("2. Handbook chapter 1. SHORTCUT: a log, a crafting table, stone tools and iron are handed over, not gathered.");
		onServer(server -> {
			ServerPlayer real = real(server);
			stash(real, new ItemStack(Items.OAK_LOG, 4));
			stash(real, new ItemStack(Items.CRAFTING_TABLE));
			stash(real, new ItemStack(Items.STONE_PICKAXE));
			stash(real, new ItemStack(Items.RAW_IRON, 3));
			stash(real, new ItemStack(Items.IRON_INGOT, 3));
			return null;
		});
		awaitServer(server -> chapterDone(server, "welcome"));
		readHandbook("welcome", "03-handbook-chapter-1-welcome");
	}

	private void repairColonyTerminals() {
		say("3. Handbook chapter 2. SHORTCUT: the parts are handed over, not crafted. They go in through the real terminal screens.");
		onServer(server -> {
			ServerPlayer real = real(server);
			for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION)) {
				type.parts().forEach(part -> stash(real, new ItemStack(part)));
			}
			HangarParts.ALL.forEach(part -> stash(real, new ItemStack(part)));
			return null;
		});
		repairTerminal(TerminalTypes.FUEL_PUMP, ColonyAnchor.FUEL_PUMP, FuelPumpScreen.class, "04-pump-repaired");
		repairTerminal(TerminalTypes.ORE_PROCESSOR, ColonyAnchor.ORE_PROCESSOR, OreProcessorScreen.class, "05-ore-processor-repaired");
		repairTerminal(TerminalTypes.UPGRADE_TERMINAL, ColonyAnchor.UPGRADE_TERMINAL, UpgradeScreen.class, "06-upgrade-terminal-repaired");
		repairTerminal(TerminalTypes.REPAIR_STATION, ColonyAnchor.REPAIR_STATION, RepairStationScreen.class, "07-repair-station-repaired");
		awaitServer(server -> chapterDone(server, "back_online"));
		readHandbook("back_online", "08-handbook-chapter-2-back-online");
	}

	private void repairFoundingMole() {
		say("4. Handbook chapter 3. The hangar console takes its four parts, and the derelict Mole is repaired and registered to the charter.");
		BlockPos console = onServer(server -> Hangar.consolePos(server).orElseThrow(() -> new AssertionError("the colony has no hangar")));
		Vec3 derelict = onServer(server -> Hangar.derelict(server).orElseThrow(() -> new AssertionError("the founding Mole is not in the hangar")).position());
		stand(Vec3.atBottomCenterOf(console.west(2)), derelict.add(0, 1, 0));
		ctx.waitTicks(60);
		snap(HOLD_FRAMES);
		still("09-derelict-mole-in-the-hangar");
		stand(Vec3.atBottomCenterOf(console.west(2)), Vec3.atCenterOf(console));
		ctx.waitTicks(20);
		useKey();
		ctx.waitForScreen(TerminalScreen.class);
		snap(HOLD_FRAMES);
		for (var part : HangarParts.ALL) {
			click("INSERT " + new ItemStack(part).getHoverName().getString().toUpperCase(Locale.ROOT));
			ctx.waitTicks(8);
			snap(2);
		}
		ctx.waitFor(client -> client.gui.screen() instanceof HangarScreen, WAIT_TICKS);
		snap(HOLD_FRAMES);
		still("10-hangar-console-online");
		ctx.setScreen(() -> null);
		awaitServer(server -> Hangar.derelict(server).flatMap(PodComponents::registration).isPresent());
		mole = onServer(server -> {
			PodEntity pod = Hangar.derelict(server).orElseThrow();
			String serial = PodComponents.registration(pod).orElseThrow().serial();
			if (!serial.equals("MOLE-0001")) {
				throw new AssertionError("the founding Mole should be MOLE-0001, it is " + serial);
			}
			return pod.getUUID();
		});
		stand(Vec3.atBottomCenterOf(console.west(2)), derelict.add(0, 1, 0));
		ctx.waitTicks(30);
		snap(HOLD_FRAMES);
		still("11-founding-mole-repaired");
	}

	private void refuel() {
		say("5. SHORTCUT: the Mole is moved by hand to the pump, and $60 is granted. The fuel is bought on the real pump screen.");
		onServer(server -> {
			PodEntity pod = pod(server, mole);
			pod.setFuel(20f);
			pod.teleportTo(server.overworld(), PARK.x, PARK.y, PARK.z, Set.of(), 0f, 0f, true);
			Charters.deposit(server, charter, 60);
			return null;
		});
		BlockPos pump = colony.anchors().get(ColonyAnchor.FUEL_PUMP);
		openTerminal(new Vec3(-8, FEET_Y, -5.5), pump, FuelPumpScreen.class);
		snap(HOLD_FRAMES);
		still("12-pump-before-fuel");
		click("FILL UP");
		awaitServer(server -> pod(server, mole).fuel() >= 99f);
		snap(HOLD_FRAMES);
		still("13-pump-after-fuel");
		ctx.setScreen(() -> null);
	}

	private void boardAndDrillDown() {
		say("6. Handbook chapter 3 and 4. Board the Mole, fly it, drill down through the floor and into layer 1, then climb back home. "
				+ "SHORTCUT: the test world is superflat, so its bedrock floor is cleared under the bore and four ore blocks are planted in it.");
		onServer(server -> {
			PodEntity pod = pod(server, mole);
			ServerLevel overworld = server.overworld();
			int lowX = (int) Math.floor(pod.getX() - 1.0 + 0.5);
			int lowZ = (int) Math.floor(pod.getZ() - 1.0 + 0.5);
			for (int dx = 0; dx < 2; dx++) {
				for (int dz = 0; dz < 2; dz++) {
					// room-carver: clears the overworld's bedrock under the bore, which is the surface and not layer rock
					overworld.setBlock(new BlockPos(lowX + dx, -64, lowZ + dz), Blocks.AIR.defaultBlockState(), 3);
				}
			}
			overworld.setBlock(new BlockPos(lowX, -62, lowZ), OreRegistry.block(OreType.IRONIUM).defaultBlockState(), 3);
			overworld.setBlock(new BlockPos(lowX + 1, -62, lowZ + 1), OreRegistry.block(OreType.BRONZIUM).defaultBlockState(), 3);
			overworld.setBlock(new BlockPos(lowX + 1, -63, lowZ), OreRegistry.block(OreType.IRONIUM).defaultBlockState(), 3);
			overworld.setBlock(new BlockPos(lowX, -63, lowZ + 1), OreRegistry.block(OreType.SILVERIUM).defaultBlockState(), 3);
			return null;
		});
		Vec3 at = onServer(server -> pod(server, mole).position());
		stand(at.add(-2.5, 0, 0), at.add(0, Chassis.MOLE.height() / 2, 0));
		ctx.waitTicks(20);
		snap(HOLD_FRAMES);
		still("14-the-mole-in-the-colony");
		useKey();
		ctx.waitFor(client -> client.player.getVehicle() instanceof PodEntity, WAIT_TICKS);
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
		ctx.waitTicks(20);
		still("15-boarded-the-mole");

		// Fly.
		ctx.getInput().holdKey(options -> options.keyJump);
		snap(7);
		ctx.getInput().releaseKey(options -> options.keyJump);
		awaitServer(server -> directiveDone(server, "meet_the_mole/board_mole") && directiveDone(server, "fuel_is_life/fly_mole"));
		snap(10);
		still("16-flying-the-mole");

		// Drill down: the floor, the dirt, the planted ore, and out of the world into layer 1.
		ctx.runOnClient(client -> client.player.setXRot(40f));
		ctx.getInput().holdKey(options -> options.keySprint);
		recordUntil(server -> podIn(server, mole, LayerChain.dimension(1)), WAIT_TICKS * 3, "The Mole did not cross into layer 1",
				stillOnce(server -> findPod(server, mole).filter(pod -> pod.getY() < FEET_Y - 1).isPresent(), "17-drilling-down-in-the-colony"));
		say("6. Layer 1. The Mole has crossed the breach. The drill goes on, which completes \"drill down\".");
		// The crossing gave the server a new player state, so the held key is pressed again for the server to see it.
		ctx.getInput().releaseKey(options -> options.keySprint);
		snap(2);
		ctx.getInput().holdKey(options -> options.keySprint);
		// Released the tick the directive is done: a stone slab takes 24 ticks to bore, and below layer 1's ceiling there are caves.
		for (int tick = 0; tick < 40 && !onServer(server -> directiveDone(server, "fuel_is_life/drill_down")); tick++) {
			ctx.waitTicks(1);
			if (tick % TICKS_PER_FRAME == 0) {
				capture();
			}
		}
		ctx.getInput().releaseKey(options -> options.keySprint);
		awaitServer(server -> directiveDone(server, "fuel_is_life/drill_down"));
		snap(HOLD_FRAMES);
		still("18-layer-1-below-the-colony");

		// Climb back out, through the ceiling of layer 1 and the shaft, to the colony.
		ctx.getInput().holdKey(options -> options.keyJump);
		recordUntil(server -> podIn(server, mole, Level.OVERWORLD), WAIT_TICKS, "The Mole did not climb back to the overworld", () -> { });
		ctx.getInput().releaseKey(options -> options.keyJump);
		say("6. The Mole climbs back up the shaft it bored and is home in the colony, with ore in its bay.");
		awaitServer(server -> directiveDone(server, "fuel_is_life/return_to_colony"));
		snap(HOLD_FRAMES);
		still("19-back-in-the-colony");
		awaitServer(server -> chapterDone(server, "meet_the_mole") && chapterDone(server, "fuel_is_life"));
		dismount();
		readHandbook("meet_the_mole", "20-handbook-chapter-3-meet-the-mole");
		readHandbook("fuel_is_life", "21-handbook-chapter-4-fuel-is-life");
	}

	private void sellOre() {
		say("7. Handbook chapter 5. The ore the drill brought up is sold at the real ore processor.");
		BlockPos processor = colony.anchors().get(ColonyAnchor.ORE_PROCESSOR);
		stand(new Vec3(PROCESSOR_VIEW_X, FEET_Y, VIEW_Z), Vec3.atCenterOf(processor));
		ctx.waitTicks(30);
		snap(2);
		still("21a-at-the-processor");
		useKey();
		ctx.waitForScreen(OreProcessorScreen.class);
		OreProcessorScreen screen = ctx.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
		recordUntilTyped(screen.typewriter()::done, 45);
		snap(HOLD_FRAMES);
		still("22-processor-with-cargo");
		click("SELL ALL POD CARGO");
		awaitServer(server -> directiveDone(server, "every_sale_counts/sell_ore"));
		snap(HOLD_FRAMES);
		still("23-ore-sold");
		ctx.setScreen(() -> null);
	}

	private void buyScanner() {
		say("8. Handbook chapters 5 and 6. SHORTCUT: $400 is granted. A scanner and lights are bought on the real upgrade screen.");
		onServer(server -> {
			Charters.deposit(server, charter, 400);
			return null;
		});
		BlockPos terminal = colony.anchors().get(ColonyAnchor.UPGRADE_TERMINAL);
		openTerminal(new Vec3(0, FEET_Y, VIEW_Z), terminal, UpgradeScreen.class);
		UpgradeScreen screen = ctx.computeOnClient(client -> (UpgradeScreen) client.gui.screen());
		recordUntilTyped(screen.typewriter()::done, 40);
		snap(HOLD_FRAMES);
		still("24-upgrade-terminal");
		click("SCANNER  T0");
		snap(HOLD_FRAMES);
		click("BUY TIER 1  $200");
		awaitServer(server -> directiveDone(server, "seeing_below/install_scanner"));
		snap(HOLD_FRAMES);
		still("25-scanner-bought");
		click("LIGHTS  T0");
		snap(2);
		click("BUY TIER 1  $125");
		awaitServer(server -> PodComponents.partOf(pod(server, mole), ComponentTrack.LIGHTS).isPresent());
		snap(HOLD_FRAMES);
		still("26-lights-bought");
		ctx.setScreen(() -> null);
		awaitServer(server -> chapterDone(server, "every_sale_counts"));
		readHandbook("every_sale_counts", "27-handbook-chapter-5-every-sale-counts");
	}

	private void diveToTheDeepClaim() {
		say("9. Handbook chapters 6 and 7. SHORTCUT: the Mole is lowered to a room at the floor of layer 1, in the Deep Claim, with ore planted in its walls. The camera has night vision so the dark can be seen.");
		onServer(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			ServerLevel two = server.getLevel(LayerChain.dimension(2));
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					one.getChunk(X / 16 + dx, Z / 16 + dz, ChunkStatus.FULL);
					two.getChunk(X / 16 + dx, Z / 16 + dz, ChunkStatus.FULL);
				}
			}
			buildRoom(one);
			return null;
		});
		board(mole);
		onServer(server -> {
			real(server).addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			PodEntity pod = pod(server, mole);
			pod.setFuel(100f);
			Entity arrived = pod.teleport(new TeleportTransition(one, new Vec3(X, FLOOR_Y, Z), Vec3.ZERO, -90f, 30f, TeleportTransition.DO_NOTHING));
			if (arrived == null) {
				throw new AssertionError("the Mole could not be moved into layer 1");
			}
			return null;
		});
		ctx.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(1)) && client.player.getVehicle() instanceof PodEntity, WAIT_TICKS);
		ctx.runOnClient(client -> client.player.setYRot(-90f));
		ctx.waitTicks(60);
		snap(HOLD_FRAMES);
		still("28-the-mole-in-the-deep-claim-room");
		say("9. The scanner reads the ore planted in the walls. The Deep Claim is the floor zone of layer 1.");
		awaitServer(server -> directiveDone(server, "seeing_below/find_ore") && directiveDone(server, "staying_safe/reach_deep_claim"));
		showTransmission("29a-transmission-in-the-deep-claim");
		snap(HOLD_FRAMES);
		still("29-scanner-sees-ore");
		awaitServer(server -> chapterDone(server, "seeing_below"));
		dismount();
		readHandbook("seeing_below", "30-handbook-chapter-6-seeing-below");
		board(mole);
	}

	private void crossTheBreach() {
		say("10. Handbook chapter 8. The Mole is refuelled with coal, then the real drill bores the stone and the crust and crosses the breach. SHORTCUT: the coal is handed over.");
		refuelWithCoal();
		snap(HOLD_FRAMES);
		ctx.runOnClient(client -> client.player.setXRot(35f));
		ctx.getInput().holdKey(options -> options.keySprint);
		recordUntil(server -> podIn(server, mole, LayerChain.dimension(2)), WAIT_TICKS * 5, "The Mole did not cross into layer 2",
				stillOnce(server -> findPod(server, mole).filter(pod -> pod.getY() < FLOOR_Y - 1).isPresent(), "31-boring-through-the-crust"));
		ctx.getInput().releaseKey(options -> options.keySprint);
		ctx.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(2)), WAIT_TICKS);
		say("10. Layer 2, the Old Workings. The first breach is crossed.");
		awaitServer(server -> directiveDone(server, "first_breach/breach_workings"));
		refuelWithCoal();
		showTransmission("32-layer-2-transmission");
		snap(HOLD_FRAMES);
		still("32-layer-2-the-old-workings");
	}

	/** A rail drift this long runs from the wreck bay's west wall, in the structure's own axis {@code u}, to where the figure starts. */
	private static final int DRIFT_LENGTH = 40;
	/** Where the Mole is set in the drift, in {@code u}: the bay's west wall is at -7. */
	private static final double MOLE_AT_U = -10;
	/** Where the figure starts: the far end of the drift is lit by the lava behind it (a figure fades when it is lit), so it starts where the drift is dark. */
	private static final int FIGURE_AT_U = -27;

	private void lamplessFigure() {
		say("11. SHORTCUT: the Mole is carried to Prospector's Run, the floor of layer 2, to a rail drift cut beside a wreck bay.");
		clearTransmission();
		StructureSite site = onServer(server -> LayerStructures.prospector(server).orElseThrow());
		Vec3 bay = at(site, 0, 0, 0);
		Vec3 figureStart = at(site, FIGURE_AT_U, 0, 0);
		Vec3 moleAt = at(site, MOLE_AT_U, 0, 0);
		onServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(2));
			Vec3 far = at(site, -DRIFT_LENGTH - 3, 0, 3);
			Vec3 near = at(site, 7, 0, -3);
			for (int chunkX = Math.min((int) far.x, (int) near.x) >> 4; chunkX <= Math.max((int) far.x, (int) near.x) >> 4; chunkX++) {
				for (int chunkZ = Math.min((int) far.z, (int) near.z) >> 4; chunkZ <= Math.max((int) far.z, (int) near.z) >> 4; chunkZ++) {
					level.setChunkForced(chunkX, chunkZ, true);
				}
			}
			return null;
		});
		// Forced chunks load, and tick their entities: the figure is not added to a chunk that is only loaded.
		awaitEntityTickingInLayer2(List.of(figureStart, bay, moleAt));
		awaitServer(server -> !wrecksAt(server, bay).isEmpty());
		prospector = onServer(server -> wrecksAt(server, bay).getFirst().getUUID());
		LamplessFigure[] figure = {null};
		dismount();
		onServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(2));
			cutDrift(level, site);
			pod(server, mole).teleportTo(level, moleAt.x, moleAt.y, moleAt.z, Set.of(), yawToward(moleAt, figureStart), 0f, true);
			return null;
		});
		board(mole);
		ctx.waitFor(client -> client.gui.screen() == null, WAIT_TICKS);
		float towardFigure = yawToward(moleAt, figureStart);
		// From the pilot's own eyes: from behind, the Mole and its rider would stand in front of the drift.
		ctx.runOnClient(client -> {
			client.options.setCameraType(CameraType.FIRST_PERSON);
			client.player.setYRot(towardFigure);
			client.player.setXRot(6f);
		});
		// Arriving in Prospector's Run brings a transmission, and it is read before the figure comes.
		showTransmission("32a-transmission-in-prospectors-run");
		say("11. SHORTCUT: the figure is placed at the far end of the drift. It walks toward the Mole.");
		onServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(2));
			LamplessFigure made = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			made.setPos(figureStart);
			made.setHeading(site.alongZ() ? Direction.SOUTH : Direction.EAST);
			if (!level.addFreshEntity(made)) {
				throw new AssertionError("the world did not take the figure at " + figureStart);
			}
			figure[0] = made;
			return null;
		});
		ctx.waitTicks(10);
		boolean shotWalk = false;
		for (int frames = 0; frames < 200 && !onServer(server -> figure[0].fadeFraction() > 0); frames++) {
			ctx.waitTicks(TICKS_PER_FRAME);
			capture();
			if (!shotWalk && onServer(server -> figure[0].position().distanceTo(pod(server, mole).position()) < 16)) {
				still("33-the-lampless-figure-walks-out-of-the-dark");
				shotWalk = true;
			}
		}
		if (!onServer(server -> figure[0].fadeFraction() > 0)) {
			throw new AssertionError("the figure never began to fade near the Mole: "
					+ onServer(server -> figure[0].position() + ", " + figure[0].tickCount + " ticks old, removed " + figure[0].isRemoved() + ", Mole at " + pod(server, mole).position()));
		}
		say("11. It never attacks and never stops. It fades when it is lit or approached.");
		snap(3);
		still("34-the-figure-fades");
		for (int frames = 0; frames < 40 && !onServer(server -> figure[0].isRemoved()); frames++) {
			ctx.waitTicks(TICKS_PER_FRAME);
			capture();
		}
		snap(HOLD_FRAMES);
		still("35-the-figure-is-gone");
	}

	/** A 3 x 4 drift with a rail down it, from the bay's west wall out {@link #DRIFT_LENGTH} blocks. Its shell is sealed first. */
	private static void cutDrift(ServerLevel level, StructureSite site) {
		// The box that is cut: RoomCarver seals the shell round it, which is the floor, the ceiling, both walls and the far end.
		BlockPos a = BlockPos.containing(at(site, -DRIFT_LENGTH, 0, -1));
		BlockPos b = BlockPos.containing(at(site, -7, 3, 1));
		RoomCarver.carve(level, a, b, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		BlockState rail = Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, site.alongZ() ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST);
		for (int u = -DRIFT_LENGTH; u <= -7; u++) {
			for (int v = -1; v <= 1; v++) {
				level.setBlock(BlockPos.containing(at(site, u, -1, v)), Blocks.COBBLESTONE.defaultBlockState(), 2);
			}
			level.setBlock(BlockPos.containing(at(site, u, 0, 0)), rail, 2);
		}
	}

	private void wreckSite() {
		say("12. Handbook chapter 9. The Mole drives on along the rails, into the bay of PROSPECTOR-0002's wreck.");
		StructureSite site = onServer(server -> LayerStructures.prospector(server).orElseThrow());
		Vec3 bay = at(site, 0, 0, 0);
		Vec3 moleAt = at(site, MOLE_AT_U, 0, 0);
		ctx.runOnClient(client -> {
			client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			client.player.setYRot(yawToward(moleAt, bay));
		});
		ctx.waitTicks(10);
		ctx.getInput().holdKey(options -> options.keyUp);
		recordUntil(server -> pod(server, mole).position().distanceTo(bay) < 7, WAIT_TICKS, "The Mole did not drive into the wreck's bay", () -> { });
		ctx.getInput().releaseKey(options -> options.keyUp);
		ctx.waitTicks(30);
		snap(HOLD_FRAMES);
		still("36-prospector-0002-wreck-ahead");
		awaitServer(server -> directiveDone(server, "company_property/find_prospector"));
		showTransmission("36a-transmission-at-the-wreck");

		// On foot to Ines's log on the table, with the lamp that still burns.
		dismount();
		say("12. On foot: Ines's log (Note N10) lies on the table, beside the lamp that still burns.");
		Vec3 table = at(site, 0, 1.03, 4);
		standInLayer(2, at(site, 0, 0, 2), table);
		ctx.waitTicks(40);
		snap(HOLD_FRAMES);
		still("37-note-n10-on-the-table");
		useKey();
		awaitServer(server -> Notes.foundFor(server, real(server).getUUID()).contains(Notes.id(10)));
		snap(HOLD_FRAMES);
		still("38-note-n10-found");

		// The tow cable: the pilot uses it on the wreck.
		say("13. The tow cable goes on the wreck. SHORTCUT: the cable is handed over, not crafted.");
		board(mole);
		refuelWithCoal();
		useTowCable("fitted");
		snap(HOLD_FRAMES);
		still("39-the-wreck-on-the-cable");
	}

	private static List<PodEntity> wrecksAt(MinecraftServer server, Vec3 bay) {
		return server.getLevel(LayerChain.dimension(2)).getEntitiesOfClass(PodEntity.class, new AABB(BlockPos.containing(bay)).inflate(6),
				pod -> pod.chassis().equals(Chassis.PROSPECTOR) && Wrecks.isWreck(pod));
	}

	private void towHome() {
		say("14. SHORTCUT: the climb home through three layers is skipped; the Mole and its tow are set at the colony's west edge. The last leg is driven.");
		Vec3 start = new Vec3(-62, FEET_Y, 7);
		onServer(server -> {
			PodEntity tower = pod(server, mole);
			PodEntity towed = pod(server, prospector);
			ServerLevel overworld = server.overworld();
			overworld.getChunk(BlockPos.containing(start).getX() >> 4, BlockPos.containing(start).getZ() >> 4, ChunkStatus.FULL);
			Entity arrived = tower.teleport(new TeleportTransition(overworld, start, Vec3.ZERO, -90f, 0f, TeleportTransition.DO_NOTHING));
			if (arrived == null) {
				throw new AssertionError("the Mole could not be set at the colony's edge");
			}
			Entity wreck = towed.teleport(new TeleportTransition(overworld, start.add(-3, 0, 0), Vec3.ZERO, -90f, 0f, TeleportTransition.DO_NOTHING));
			if (wreck == null) {
				throw new AssertionError("the wreck could not be set at the colony's edge");
			}
			return null;
		});
		ctx.waitFor(client -> client.level.dimension().equals(Level.OVERWORLD) && client.player.getVehicle() instanceof PodEntity, WAIT_TICKS);
		ctx.runOnClient(client -> {
			client.player.setYRot(-90f);
			client.player.setXRot(10f);
		});
		ctx.waitTicks(60);
		say("14. Towing the wreck home along the ground, with the real drive keys.");
		snap(HOLD_FRAMES);
		still("40-towing-at-the-edge-of-the-colony");
		ctx.getInput().holdKey(options -> options.keyUp);
		recordUntil(server -> pod(server, mole).getX() > -26, WAIT_TICKS * 2, "The Mole did not tow the wreck into the colony", () -> { });
		ctx.getInput().releaseKey(options -> options.keyUp);
		awaitServer(server -> directiveDone(server, "company_property/tow_prospector"));
		snap(HOLD_FRAMES);
		still("41-the-wreck-towed-home");
	}

	private void repairHullAndRestore() {
		say("15. Handbook chapter 7. The crust cost the Mole hull. SHORTCUT: the Mole is moved by hand to the repair station.");
		BlockPos station = colony.anchors().get(ColonyAnchor.REPAIR_STATION);
		useTowCable("removed");
		dismount();
		onServer(server -> {
			PodEntity tower = pod(server, mole);
			if (tower.hull() >= tower.maxHull()) {
				throw new AssertionError("the Mole should have lost hull on the crust, it has " + tower.hull());
			}
			tower.teleportTo(server.overworld(), 6.5, FEET_Y, -3.5, Set.of(), 0f, 0f, true);
			return null;
		});
		openTerminal(new Vec3(4, FEET_Y, VIEW_Z), station, RepairStationScreen.class);
		snap(HOLD_FRAMES);
		still("42-repair-station");
		click("REPAIR ALL");
		awaitServer(server -> directiveDone(server, "staying_safe/repair_hull"));
		snap(HOLD_FRAMES);
		still("43-hull-repaired");
		ctx.setScreen(() -> null);
		awaitServer(server -> chapterDone(server, "staying_safe") && chapterDone(server, "first_breach"));
		readHandbook("staying_safe", "44-handbook-chapter-7-staying-safe");
		readHandbook("first_breach", "45-handbook-chapter-8-first-breach");

		say("16. The wreck is in reach of the hangar console. SHORTCUT: $1,390 and 3 Cicatrium are granted. The console restores PROSPECTOR-0002.");
		onServer(server -> {
			Charters.deposit(server, charter, 1_390);
			for (int i = 0; i < 3; i++) {
				stash(real(server), OreRegistry.stack(OreType.CICATRIUM));
			}
			return null;
		});
		BlockPos console = onServer(server -> Hangar.consolePos(server).orElseThrow());
		openTerminal(Vec3.atBottomCenterOf(console.west(2)), console, HangarScreen.class);
		snap(HOLD_FRAMES);
		still("46-hangar-console-with-a-wreck-in-reach");
		clickStartingWith("RESTORE NEAREST WRECK");
		awaitServer(server -> directiveDone(server, "company_property/restore_prospector"));
		snap(HOLD_FRAMES);
		still("47-prospector-restored");
		ctx.setScreen(() -> null);
		String serial = onServer(server -> PodComponents.registration(pod(server, prospector)).orElseThrow().serial());
		say("16. The wreck is registered to the charter by its serials: it is " + serial + " now, not PROSPECTOR-0002.");
		showTransmission("48-t17-transmission");
		Vec3 wreckAt = onServer(server -> pod(server, prospector).position());
		// The hangar's walls are in the way at ground level, so the camera hovers over them.
		onServer(server -> {
			ServerPlayer real = real(server);
			real.getAbilities().mayfly = true;
			real.getAbilities().flying = true;
			real.onUpdateAbilities();
			return null;
		});
		stand(wreckAt.add(-2, 7, 3), wreckAt.add(0, 1, 0));
		ctx.waitTicks(30);
		snap(HOLD_FRAMES);
		still("49-the-restored-prospector");
		onServer(server -> {
			ServerPlayer real = real(server);
			real.getAbilities().flying = false;
			real.getAbilities().mayfly = false;
			real.onUpdateAbilities();
			return null;
		});
	}

	private void workOrder() {
		say("17. The Founder's hands. SHORTCUT: 10 Bronzium is granted, 4 to the player and 6 to the crewmate, as if mined elsewhere.");
		onServer(server -> {
			for (int i = 0; i < 4; i++) {
				stash(real(server), OreRegistry.stack(OreType.BRONZIUM));
			}
			for (int i = 0; i < 6; i++) {
				two.mock().player().getInventory().add(OreRegistry.stack(OreType.BRONZIUM));
			}
			return null;
		});
		BlockPos processor = colony.anchors().get(ColonyAnchor.ORE_PROCESSOR);
		Vec3 viewpoint = new Vec3(PROCESSOR_VIEW_X, FEET_Y, VIEW_Z);
		stand(viewpoint, Vec3.atCenterOf(processor));
		ctx.waitTicks(30);
		useKey();
		ctx.waitForScreen(OreProcessorScreen.class);
		OreProcessorScreen screen = ctx.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
		recordUntilTyped(screen.typewriter()::done, 45);
		snap(HOLD_FRAMES);
		still("50-the-work-order-on-the-processor");
		click("DELIVER BRONZIUM");
		ctx.waitFor(client -> screen.orderLines().getLast().startsWith("4 / 10"), WAIT_TICKS);
		snap(HOLD_FRAMES);
		still("51-four-handed-in-by-the-player");
		ctx.setScreen(() -> null);
		say("17. The mock crewmate hands in the other six, on the same charter.");
		onServer(server -> {
			ServerPlayer mock = two.mock().player();
			two.mock().teleportTo(server.overworld(), new Vec3(-1.0, FEET_Y, VIEW_Z), 180f, 0f);
			CompoundTag args = new CompoundTag();
			args.putString(WorkOrders.ORDER_KEY, WorkOrder.FOUNDERS_HANDS.id().toString());
			Terminals.act(mock, processor, WorkOrders.DELIVER, args).ifPresent(refusal -> {
				throw new AssertionError("the crewmate's delivery was refused: " + refusal);
			});
			two.mock().teleportTo(server.overworld(), new Vec3(11, FEET_Y, 6), 90f, 0f);
			return null;
		});
		snap(HOLD_FRAMES);
		// The screen of the player who did not hand in does not change, so it is opened again.
		stand(viewpoint, Vec3.atCenterOf(processor));
		ctx.waitTicks(20);
		useKey();
		ctx.waitForScreen(OreProcessorScreen.class);
		OreProcessorScreen done = ctx.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
		ctx.waitFor(client -> done.orderLines().getLast().endsWith("DONE"), WAIT_TICKS);
		recordUntilTyped(done.typewriter()::done, 45);
		snap(HOLD_FRAMES);
		still("52-work-order-done");
		ctx.setScreen(() -> null);
		BlockPos statue = colony.anchors().get(ColonyAnchor.STATUE);
		// The anchor is the block the Host stands in, over his 5-block plinth: his feet are 5 blocks above the square.
		stand(Vec3.atBottomCenterOf(statue.below(5)).add(0, 1, 12), Vec3.atCenterOf(statue).add(0, 5, 0));
		ctx.waitTicks(60);
		snap(HOLD_FRAMES);
		still("53-the-founders-hands-restored");
	}

	private void theOldWorkingsFloor() {
		say("18. Handbook chapter 9. Both crew board the restored Prospector. SHORTCUT: it is carried to the floor of the Old Workings.");
		StructureSite site = onServer(server -> LayerStructures.prospector(server).orElseThrow());
		Vec3 park = at(site, -4.5, 0, 3.5);
		Vec3 prospectorHere = onServer(server -> pod(server, prospector).position());
		stand(prospectorHere.add(-4, 0, 0), prospectorHere.add(0, 1.2, 0));
		ctx.waitTicks(20);
		board(prospector);
		onServer(server -> {
			ServerPlayer mock = two.mock().player();
			two.mock().teleportTo(server.overworld(), prospectorHere.add(0, 0, 3), 0f, 0f);
			if (!mock.startRiding(pod(server, prospector))) {
				throw new AssertionError("the crewmate could not take the navigator's seat");
			}
			return null;
		});
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
		ctx.waitTicks(20);
		snap(HOLD_FRAMES);
		still("54-two-crew-in-the-prospector");
		onServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(2));
			PodEntity pod = pod(server, prospector);
			Entity arrived = pod.teleport(new TeleportTransition(level, park, Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING));
			if (arrived == null) {
				throw new AssertionError("the Prospector could not be carried to the Old Workings");
			}
			return null;
		});
		ctx.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(2)) && client.player.getVehicle() instanceof PodEntity, WAIT_TICKS);
		awaitServer(server -> directiveDone(server, "company_property/reach_workings_floor"));
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
		ctx.waitTicks(60);
		snap(HOLD_FRAMES);
		still("55-the-prospector-on-the-floor-of-the-old-workings");
		awaitServer(server -> chapterDone(server, "company_property"));
		readHandbook("company_property", "56-handbook-chapter-9-company-property");

		onServer(server -> {
			M2SliceEndState.require(server, charter, pod(server, prospector));
			return null;
		});
		say("End of the slice. All nine chapters are done, the Prospector is restored and the Founder's hands are back.");
		snap(HOLD_FRAMES);
		still("57-end-state");
	}

	// ---------------------------------------------------------------- directive state

	private boolean directiveDone(MinecraftServer server, String path) {
		return HandbookProgress.completedFor(server, real(server).getUUID())
				.contains(Identifier.fromNamespaceAndPath("deepcharter", "handbook/" + path));
	}

	private boolean chapterDone(MinecraftServer server, String chapter) {
		Set<Identifier> done = HandbookProgress.completedFor(server, real(server).getUUID());
		return HandbookChapters.all(server.registryAccess()).stream()
				.filter(entry -> entry.key().identifier().getPath().equals(chapter))
				.flatMap(entry -> entry.value().directives().stream())
				.allMatch(entry -> done.contains(entry.id()));
	}

	// ---------------------------------------------------------------- UI helpers

	private void repairTerminal(TerminalType type, ColonyAnchor anchor, Class<? extends Screen> online, String name) {
		BlockPos pos = colony.anchors().get(anchor);
		openTerminal(new Vec3(pos.getX() + 0.5, FEET_Y, pos.getZ() + 2.5), pos, TerminalScreen.class);
		snap(HOLD_FRAMES);
		for (var part : type.parts()) {
			click("INSERT " + new ItemStack(part).getHoverName().getString().toUpperCase(Locale.ROOT));
			ctx.waitTicks(8);
			snap(2);
		}
		ctx.waitFor(client -> online.isInstance(client.gui.screen()), WAIT_TICKS);
		snap(HOLD_FRAMES);
		still(name);
		ctx.setScreen(() -> null);
	}

	/** Opens the handbook at the chapter's directives, takes a still, and closes it. */
	private void readHandbook(String chapter, String still) {
		ctx.waitTicks(20);
		// The test pack adds a sample chapter that is never finished, which would classify every real chapter after it.
		// So the screen is built as HandbookScreen.open builds it, from the shipped chapters only and the progress the server synced.
		ctx.setScreen(() -> {
			Map<Identifier, HandbookChapter> shipped = new LinkedHashMap<>();
			HandbookChapters.all(Minecraft.getInstance().getConnection().registryAccess()).stream()
					.filter(entry -> !entry.key().identifier().getPath().equals("sample"))
					.forEach(entry -> shipped.put(entry.key().identifier(), entry.value()));
			return new HandbookScreen(HandbookPages.of(shipped, ClientHandbook.completed()), id -> true, id -> { }, List.of());
		});
		ctx.waitForScreen(HandbookScreen.class);
		HandbookScreen screen = ctx.computeOnClient(client -> (HandbookScreen) client.gui.screen());
		int page = ctx.computeOnClient(client -> {
			List<HandbookPage> pages = screen.pages();
			for (int index = 0; index < pages.size(); index++) {
				if (pages.get(index) instanceof HandbookPage.Chapter found && found.id().getPath().equals(chapter)) {
					return index;
				}
			}
			throw new AssertionError("the handbook has no page for chapter " + chapter);
		});
		ctx.runOnClient(client -> screen.goTo(page));
		ctx.waitTicks(HandbookScreenTuning.current().flipTicks());
		snap(HOLD_FRAMES);
		still(still);
		ctx.setScreen(() -> null);
	}

	/** If a transmission is coming, records it from the first letter until it is typed out. */
	private void showTransmission(String still) {
		boolean appeared = false;
		for (int waited = 0; waited < 90 && !appeared; waited += TICKS_PER_FRAME) {
			appeared = ctx.computeOnClient(client -> TransmissionOverlay.transmission().isPresent());
			if (!appeared) {
				ctx.waitTicks(TICKS_PER_FRAME);
			}
		}
		if (!appeared) {
			return;
		}
		for (int waited = 0; waited < WAIT_TICKS && !ctx.computeOnClient(client -> TransmissionOverlay.typed()); waited += TICKS_PER_FRAME) {
			ctx.waitTicks(TICKS_PER_FRAME);
			capture();
		}
		snap(HOLD_FRAMES);
		shoot(still);
		clearTransmission();
	}

	/** Records until no transmission is on screen, so that a still never has one over the view. */
	private void clearTransmission() {
		for (int waited = 0; waited < WAIT_TICKS * 2 && ctx.computeOnClient(client -> TransmissionOverlay.transmission().isPresent()); waited += TICKS_PER_FRAME) {
			ctx.waitTicks(TICKS_PER_FRAME);
			capture();
		}
	}

	/** Stands at {@code feet} facing the terminal, uses it, and waits for the screen. */
	private void openTerminal(Vec3 feet, BlockPos terminal, Class<? extends Screen> screen) {
		stand(feet, Vec3.atCenterOf(terminal));
		ctx.waitTicks(30);
		useKey();
		ctx.waitForScreen(screen);
	}

	/** The pilot uses a tow cable on the wreck, which fits it or takes it off; {@code outcome} names which, for the failure message. */
	private void useTowCable(String outcome) {
		onServer(server -> {
			ServerPlayer real = real(server);
			real.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
			InteractionResult result = UseEntityCallback.EVENT.invoker().interact(real, real.level(), InteractionHand.MAIN_HAND, pod(server, prospector), null);
			if (!result.consumesAction()) {
				throw new AssertionError("the tow cable was not " + outcome + ": " + result);
			}
			real.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			return null;
		});
	}

	private void click(String label) {
		ctx.clickScreenButton(label);
	}

	private void clickStartingWith(String prefix) {
		String label = ctx.computeOnClient(client -> client.gui.screen().children().stream()
				.filter(CrtButton.class::isInstance).map(child -> ((CrtButton) child).getMessage().getString())
				.filter(text -> text.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError("no button starts with " + prefix)));
		ctx.clickScreenButton(label);
	}

	private void useKey() {
		ctx.getInput().pressKey(options -> options.keyUse);
	}

	// ---------------------------------------------------------------- recording

	private void say(String text) {
		caption = Component.literal(text).withStyle(text.contains("SHORTCUT") ? ChatFormatting.GOLD : ChatFormatting.WHITE);
	}

	/** Draws the caption in a dark band at the top of the HUD and of every screen, wrapped to the width of the window. */
	private static void registerCaptions() {
		if (captionsRegistered) {
			return;
		}
		captionsRegistered = true;
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("deepcharter-test", "m2_slice_caption"),
				(graphics, tracker) -> drawCaption(graphics));
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
				ScreenEvents.afterExtract(screen).register((shown, graphics, mouseX, mouseY, delta) -> drawCaption(graphics)));
	}

	private static void drawCaption(GuiGraphicsExtractor graphics) {
		int width = graphics.guiWidth();
		Component text = caption;
		if (text.getString().isEmpty()) {
			return;
		}
		var font = Minecraft.getInstance().font;
		List<FormattedCharSequence> lines = font.split(text, width * 5 / 6);
		graphics.fill(0, 0, width, lines.size() * (font.lineHeight + 1) + 6, 0xB0000000);
		int y = 4;
		for (FormattedCharSequence line : lines) {
			graphics.text(font, line, 4, y, 0xFFFFFFFF, true);
			y += font.lineHeight + 1;
		}
	}

	/** One frame of the recording. The caption is drawn by {@link #registerCaptions}. */
	private void capture() {
		ctx.runOnClient(client -> client.gui.toastManager().clear());
		frame(ctx);
		captured++;
	}

	private void snap(int frames) {
		for (int i = 0; i < frames; i++) {
			ctx.waitTicks(TICKS_PER_FRAME);
			capture();
		}
	}

	private void still(String name) {
		clearTransmission();
		shoot(name);
	}

	private void shoot(String name) {
		ctx.waitTicks(1);
		System.out.println("M2SLICE-FRAME " + captured + " beat " + beat + " " + name);
		screenshot(ctx, name);
	}

	/** Records frames until the typewriter has typed everything out, or {@code maxFrames} have passed. */
	private void recordUntilTyped(BooleanSupplier done, int maxFrames) {
		for (int i = 0; i < maxFrames && !done.getAsBoolean(); i++) {
			ctx.waitTicks(TICKS_PER_FRAME);
			capture();
		}
	}

	/** Records frames until the server reaches {@code reached}, running {@code eachFrame} after each; fails after {@code maxTicks}. */
	private void recordUntil(Predicate<MinecraftServer> reached, int maxTicks, String failure, Runnable eachFrame) {
		int ticks = 0;
		while (!onServer(reached::test)) {
			ctx.waitTicks(TICKS_PER_FRAME);
			capture();
			eachFrame.run();
			if ((ticks += TICKS_PER_FRAME) > maxTicks) {
				throw new AssertionError(failure + " within " + ticks + " ticks");
			}
		}
	}

	/** Takes {@code name} the first time the server reaches {@code when}. */
	private Runnable stillOnce(Predicate<MinecraftServer> when, String name) {
		boolean[] shot = {false};
		return () -> {
			if (!shot[0] && onServer(when::test)) {
				still(name);
				shot[0] = true;
			}
		};
	}

	// ---------------------------------------------------------------- world helpers

	private <T> T onServer(Function<MinecraftServer, T> action) {
		return two.server().computeOnServer(action::apply);
	}

	private void awaitServer(java.util.function.Predicate<MinecraftServer> condition) {
		for (int tick = 0; tick < WAIT_TICKS * 3; tick++) {
			if (onServer(condition::test)) {
				return;
			}
			ctx.waitTicks(1);
		}
		throw new AssertionError("the server did not reach the awaited state within " + WAIT_TICKS * 3 + " ticks (beat " + beat + ")");
	}

	/** Waits on the wall clock, not on ticks (the client and server tick unthrottled), and names the first layer 2 chunk that is not ticking entities. */
	private void awaitEntityTickingInLayer2(List<Vec3> positions) {
		FarChunks.Deadline deadline = FarChunks.deadline();
		while (true) {
			Vec3 waiting = onServer(server -> {
				ServerLevel level = server.getLevel(LayerChain.dimension(2));
				return positions.stream().filter(at -> !level.isPositionEntityTicking(BlockPos.containing(at))).findFirst().orElse(null);
			});
			if (waiting == null) {
				return;
			}
			if (deadline.expired()) {
				throw new AssertionError("chunk " + chunkOf(BlockPos.containing(waiting)) + " in " + LayerChain.dimension(2)
						+ " was not entity-ticking after " + FarChunks.WAIT_SECONDS + " s (beat " + beat + ")");
			}
			ctx.waitTicks(1);
		}
	}

	private static ChunkPos chunkOf(BlockPos pos) {
		return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
	}

	private ServerPlayer real(MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().filter(player -> player != two.mock().player()).findFirst().orElseThrow();
	}

	private static void command(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private static PodEntity pod(MinecraftServer server, UUID id) {
		return findPod(server, id).orElseThrow(() -> new AssertionError("pod " + id + " is not loaded in any level"));
	}

	/** The pod, wherever it is. Empty for the tick or two in which a crossing has taken it out of one level and not yet put it in the next. */
	private static java.util.Optional<PodEntity> findPod(MinecraftServer server, UUID id) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(id) instanceof PodEntity pod) {
				return java.util.Optional.of(pod);
			}
		}
		return java.util.Optional.empty();
	}

	private static boolean podIn(MinecraftServer server, UUID id, net.minecraft.resources.ResourceKey<Level> dimension) {
		return findPod(server, id).filter(pod -> pod.level().dimension().equals(dimension)).isPresent();
	}

	private static void expectNoRefusal(java.util.Optional<? extends Object> refusal, String what) {
		refusal.ifPresent(found -> {
			throw new AssertionError(what + " was refused: " + found);
		});
	}

	/** Puts an item in the main inventory, keeping the hotbar free: the hand stays empty for the use key. */
	private static void stash(ServerPlayer player, ItemStack stack) {
		Inventory inventory = player.getInventory();
		for (int slot = 9; slot < 36; slot++) {
			if (inventory.getItem(slot).isEmpty()) {
				inventory.setItem(slot, stack);
				return;
			}
		}
		throw new AssertionError("the main inventory is full");
	}

	/** Stands the real player on the overworld at {@code feet}, looking at {@code target}. */
	private void stand(Vec3 feet, Vec3 target) {
		standInLayer(0, feet, target);
	}

	private void standInLayer(int layer, Vec3 feet, Vec3 target) {
		Vec3 d = target.subtract(feet.add(0, 1.62, 0));
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		onServer(server -> {
			ServerPlayer real = real(server);
			real.teleportTo(server.getLevel(LayerChain.dimension(layer)), feet.x, feet.y, feet.z, Set.of(), yaw, pitch, true);
			return null;
		});
		ctx.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(layer)) && client.player.distanceToSqr(feet) < 1.0, WAIT_TICKS);
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
		ctx.waitTicks(4);
	}

	private void dismount() {
		onServer(server -> {
			real(server).stopRiding();
			return null;
		});
		ctx.waitFor(client -> client.player.getVehicle() == null, WAIT_TICKS);
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
	}

	/** Seats the real player in the pod, as a right-click would. */
	private void board(UUID pod) {
		onServer(server -> {
			if (!real(server).startRiding(pod(server, pod))) {
				throw new AssertionError("the player could not board " + pod);
			}
			return null;
		});
		ctx.waitFor(client -> client.player.getVehicle() instanceof PodEntity, WAIT_TICKS);
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
	}

	/**
	 * Coal on the pod refuels it, as it does in the game: the pilot uses coal on the pod they ride. SHORTCUT: the coal is handed over,
	 * not mined, and the run burns more fuel than a player would, because it waits on screens with the engine idling.
	 */
	private void refuelWithCoal() {
		onServer(server -> {
			ServerPlayer real = real(server);
			PodEntity pod = (PodEntity) real.getVehicle();
			for (int coal = 0; coal < 6 && pod.fuel() < 90f; coal++) {
				real.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
				UseEntityCallback.EVENT.invoker().interact(real, real.level(), InteractionHand.MAIN_HAND, pod, null);
			}
			real.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			return null;
		});
	}

	private static float yawToward(Vec3 from, Vec3 to) {
		return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
	}

	/** The world position of a point in the structure's own axes, {@code y} up from the floor of its hollow. */
	private static Vec3 at(StructureSite site, double u, double y, double v) {
		BlockPos origin = site.origin();
		return new Vec3(origin.getX() + 0.5 + (site.alongZ() ? v : u), origin.getY() + y, origin.getZ() + 0.5 + (site.alongZ() ? u : v));
	}

	/** The room in the Deep Claim: crust, a row of stone with ore in it, an airy room, lamps and ore in the walls. */
	private static void buildRoom(ServerLevel level) {
		RoomCarver.carve(level, X - ROOM_WEST, X + ROOM_EAST, level.getMinY(), level.getMinY() + LayerTuning.DEFAULT.crustThickness() - 1,
				Z - ROOM_RADIUS_Z, Z + ROOM_RADIUS_Z, LayerBlocks.BREACH_CRUST);
		// The wall ores sit in this stone shell, so it is carved sealed as well: lava must not be one block behind them.
		RoomCarver.carve(level, X - ROOM_WEST - 1, X + ROOM_EAST + 1, FLOOR_Y - 1, FLOOR_Y + 10, Z - ROOM_RADIUS_Z - 1, Z + ROOM_RADIUS_Z + 1,
				Blocks.STONE);
		box(level, FLOOR_Y - 1, FLOOR_Y - 1, Blocks.STONE);
		RoomCarver.carve(level, new BlockPos(X - ROOM_WEST, FLOOR_Y, Z - ROOM_RADIUS_Z), new BlockPos(X + ROOM_EAST, FLOOR_Y + 9, Z + ROOM_RADIUS_Z),
				Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		for (int x = X - 3; x <= X + 11; x += 4) {
			level.setBlock(new BlockPos(x, FLOOR_Y + 3, Z + 4), Blocks.GLOWSTONE.defaultBlockState(), 3);
			level.setBlock(new BlockPos(x, FLOOR_Y + 3, Z - 4), Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
		// Ore under the Mole (it is drilled and collected) and in the walls (the scanner sees it).
		level.setBlock(new BlockPos(X, FLOOR_Y - 1, Z), OreRegistry.block(OreType.GOLDIUM).defaultBlockState(), 3);
		level.setBlock(new BlockPos(X + 6, FLOOR_Y + 1, Z + ROOM_RADIUS_Z + 1), OreRegistry.block(OreType.PLATINIUM).defaultBlockState(), 3);
		level.setBlock(new BlockPos(X + 9, FLOOR_Y, Z - ROOM_RADIUS_Z - 1), OreRegistry.block(OreType.SILVERIUM).defaultBlockState(), 3);
		level.setBlock(new BlockPos(X + ROOM_EAST + 1, FLOOR_Y + 1, Z), OreRegistry.block(OreType.IRONIUM).defaultBlockState(), 3);
	}

	private static void box(ServerLevel level, int yFrom, int yTo, Block block) {
		for (int x = X - ROOM_WEST; x <= X + ROOM_EAST; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - ROOM_RADIUS_Z; z <= Z + ROOM_RADIUS_Z; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}
}
