package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.ore.OreCargoScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.hangar.Hangar;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.Transmissions;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "design-tour" for #223: a fixed set of named stills of everything in the game as it looks today, so the design
 * overhaul can shoot the same views afterwards and show before and after. It is a reference shoot, not a story: the player is a
 * creative, invulnerable camera. The still names are the file names, and docs/design/current-state.md embeds them. A short orbit of
 * the colony supplies the frames of the GIF.
 *
 * <p>Every view is derived from the world, never from fixed coordinates: the colony views are offsets from the {@link ColonyAnchor}
 * of the building they show (the statue for the square), and the structure views are offsets in the axes of the structure site. A
 * building that the overhaul moves, so moves its views with it. Each still that has a subject (the chapel candle, the statue
 * column, a terminal, a lantern) is taken through {@link #shoot}, which first checks that a block of the subject stands within a few
 * blocks of the point looked at, and that nothing else stands in the line of sight. When it does not, the tour throws an
 * {@link AssertionError} that names the still, so that a moved or redrawn building fails the run, and no wrong picture is filed
 * as the "after".
 *
 * <p>Two runs of one commit give the same stills (tools/diff-stills compares them): the world is pinned by {@link #pinWorld} (seed,
 * clock, weather, random ticks, mob spawning, particles) and every still goes through {@link #settle} first, which clears the mobs
 * and the particles, parks the cursor off the window, and waits until the chunks have rendered. See docs/design/skins.md.
 *
 * <p>The order: handbook and item gallery, the surface by day, dusk and night, the colony, the terminal screens, the pods by day,
 * a dark room (pods lit and unlit, the lampless figure), the HUDs, layer 1, the breach into layer 2, layer 2 and its structures.
 */
public class DesignTourScenario extends EvidenceScenario {
	private static final double EYE = 1.62;
	private static final int WAIT = 400;
	private static final int ORBIT_FRAMES = 26;
	private static final int ORBIT_RADIUS = 52;
	private static final int ORBIT_HEIGHT = 30;
	/** The sealed dark room is built this far from the colony, at this height: no sky reaches it. */
	private static final int ROOM_OFFSET = 300;
	private static final int ROOM_Y = 200;
	private static final int ROOM_RADIUS = 9;
	private static final int ROOM_HEIGHT = 7;
	private static final int LAYER_X = 2000;
	private static final int LAYER_Z = 2000;
	private static final int ITEMS_PER_PAGE = 36;
	private static final long FUNDS = 5_000;
	private static final int TERMINAL_TYPING_TICKS = 140;
	/** The wall-clock limit of one settle. A world that has not settled by then fails the run, naming the still. */
	private static final long SETTLE_LIMIT_NANOS = 90_000_000_000L;
	/** Ticks between two looks at whether the world has settled. */
	private static final int SETTLE_POLL_TICKS = 2;
	/** Where the cursor is parked when a screen is open: well outside the window, so that no slot or button is hovered. */
	private static final double OFF_SCREEN = -10_000;
	/** The breach fade is shot at the first client tick where it is half black: tick 4 of its 8 ticks of fading in, the same every run. */
	private static final float FADE_SHOT_ALPHA = 0.5f;

	private ClientGameTestContext ctx;
	private TestSingleplayerContext sp;
	private BlockPos ground;
	private Map<ColonyAnchor, BlockPos> anchors;
	private CharterId charter;

	@Override
	protected String name() {
		return "design-tour";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		// A real world, as a new player gets one: the vanilla terrain of the seed, not the flat world of the other scenarios.
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed("deepcharter-design-tour")).create()) {
			sp = singleplayer;
			context.waitFor(client -> client.player != null && client.level != null);
			context.waitTicks(80);
			ColonySite.Placed colony = serverGet(server -> Colony.placed(server).orElseThrow(() -> new AssertionError("no colony")));
			ground = colony.center();
			anchors = colony.anchors();

			pinWorld();
			handbook();
			itemGallery();
			setUpCamera();
			surface();
			colonyTour();
			terminalScreens();
			podsByDay();
			blockGallery();
			darkRoom();
			hudsOnTheSurface();
			layerOne();
			breachToLayerTwo();
			layerTwo();
			remainingTransmissions();
		}
	}

	// ------------------------------------------------------------------------------------------------ determinism

	/**
	 * Pins everything that would make two runs of one commit differ, before the first still: the clock stops at noon, the weather is
	 * clear and stays so, nothing grows or burns by random tick, no mob spawns, and particles are at their minimum. The seed is the
	 * world's own (see {@link #run}). Mobs that the world generated, and particles already flying, are cleared by {@link #settle}.
	 */
	private void pinWorld() {
		serverDo(server -> {
			GameRules rules = server.getGameRules();
			rules.set(GameRules.ADVANCE_TIME, false, server);
			rules.set(GameRules.ADVANCE_WEATHER, false, server);
			rules.set(GameRules.SPAWN_MOBS, false, server);
			rules.set(GameRules.SPAWN_MONSTERS, false, server);
			rules.set(GameRules.SPAWN_PATROLS, false, server);
			rules.set(GameRules.SPAWN_PHANTOMS, false, server);
			rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
			rules.set(GameRules.SPAWN_WARDENS, false, server);
			rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
			command(server, "weather clear");
			command(server, "time set noon");
		});
		ctx.runOnClient(client -> {
			client.options.particles().set(ParticleStatus.MINIMAL);
			client.options.bobView().set(false);
		});
	}

	/** A mob of the world (a cow, a villager) or a loose item or orb: what the tour never shows. The mod's own mobs are the tour's. */
	private static boolean isStray(Entity entity) {
		boolean thing = entity instanceof Mob || entity instanceof ItemEntity || entity instanceof ExperienceOrb;
		return thing && !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals(DeepCharter.MOD_ID);
	}

	/**
	 * Gets the world ready for a still: clears the mobs that chunk generation has put in view since the last one, clears the
	 * particles, parks the cursor off the window when a screen is open (so no slot or button is hovered and no tooltip shows), and
	 * waits until the client has removed the mobs and rendered every chunk section. The wait ends on those conditions, and fails
	 * with an {@link AssertionError} naming the still after {@link #SETTLE_LIMIT_NANOS} of wall-clock time; it never counts ticks.
	 */
	private void settle(String stillName) {
		long deadline = System.nanoTime() + SETTLE_LIMIT_NANOS;
		while (true) {
			serverDo(server -> {
				for (ServerLevel level : server.getAllLevels()) {
					List<Entity> strays = new ArrayList<>();
					level.getAllEntities().forEach(entity -> {
						if (isStray(entity)) {
							strays.add(entity);
						}
					});
					strays.forEach(Entity::discard);
				}
			});
			ctx.runOnClient(client -> client.particleEngine.clearParticles());
			if (ctx.computeOnClient(client -> client.gui.screen() != null)) {
				ctx.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
			}
			ctx.waitTicks(SETTLE_POLL_TICKS);
			boolean ready = ctx.computeOnClient(client -> {
				for (Entity entity : client.level.entitiesForRendering()) {
					if (isStray(entity)) {
						return false;
					}
				}
				return client.levelRenderer.hasRenderedAllSections();
			});
			if (ready) {
				return;
			}
			if (System.nanoTime() > deadline) {
				throw new AssertionError("still " + stillName + ": the world did not settle (mobs gone, chunks rendered) in "
						+ SETTLE_LIMIT_NANOS / 1_000_000_000L + " s");
			}
		}
	}

	// ------------------------------------------------------------------------------------------------ handbook, items

	private void handbook() {
		ctx.waitFor(client -> handbookSlot(client) >= 0, WAIT);
		ctx.runOnClient(client -> client.player.getInventory().setSelectedSlot(handbookSlot(client)));
		ctx.getInput().pressKey(options -> options.keyUse);
		ctx.waitForScreen(HandbookScreen.class);
		ctx.waitTicks(HandbookScreenTuning.DEFAULT.flipTicks() + 10);
		still("handbook-opened-from-the-item");
		ctx.setScreen(() -> null);

		// Built as HandbookScreen.open builds it: the shipped chapters only, with the progress the server synced.
		ctx.setScreen(() -> {
			Map<Identifier, HandbookChapter> shipped = new LinkedHashMap<>();
			HandbookChapters.all(Minecraft.getInstance().getConnection().registryAccess()).stream()
					.filter(entry -> !entry.key().identifier().getPath().equals("sample"))
					.forEach(entry -> shipped.put(entry.key().identifier(), entry.value()));
			return new HandbookScreen(HandbookPages.of(shipped, ClientHandbook.completed()), id -> true, id -> { }, List.of());
		});
		ctx.waitForScreen(HandbookScreen.class);
		HandbookScreen screen = ctx.computeOnClient(client -> (HandbookScreen) client.gui.screen());
		List<HandbookPage> pages = ctx.computeOnClient(client -> screen.pages());
		Set<String> seen = new HashSet<>();
		for (int index = 0; index < pages.size(); index++) {
			HandbookPage page = pages.get(index);
			String kind = page.getClass().getSimpleName().toLowerCase();
			boolean classified = page instanceof HandbookPage.Chapter chapter && chapter.visibility() != HandbookVisibility.FULL;
			String key = classified ? "chapter-classified" : kind;
			if (!seen.add(key) || (page instanceof HandbookPage.Contents contents && contents.part() > 1)) {
				continue;
			}
			int target = index;
			ctx.runOnClient(client -> screen.goTo(target));
			ctx.waitTicks(HandbookScreenTuning.DEFAULT.flipTicks() + 6);
			still("handbook-page-" + key);
		}
		ctx.runOnClient(client -> screen.showNotes());
		ctx.waitTicks(6);
		still("handbook-notes-tab");
		ctx.setScreen(() -> null);
	}

	private static int handbookSlot(Minecraft client) {
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (HandbookItems.isHandbook(client.player.getInventory().getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	/** Every item of the mod in the inventory screen, 36 to a page. Block items show their block model. */
	private void itemGallery() {
		List<Item> items = BuiltInRegistries.ITEM.keySet().stream()
				.filter(id -> id.getNamespace().equals(DeepCharter.MOD_ID))
				.sorted()
				.map(BuiltInRegistries.ITEM::getValue)
				.toList();
		for (int page = 0; page * ITEMS_PER_PAGE < items.size(); page++) {
			int first = page * ITEMS_PER_PAGE;
			serverDo(server -> {
				ServerPlayer player = player(server);
				player.getInventory().clearContent();
				for (int slot = 0; slot < ITEMS_PER_PAGE && first + slot < items.size(); slot++) {
					player.getInventory().setItem(slot, new ItemStack(items.get(first + slot)));
				}
			});
			ctx.waitTicks(4);
			ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
			ctx.waitForScreen(InventoryScreen.class);
			ctx.waitTicks(6);
			still("items-gallery-" + (page + 1));
			ctx.setScreen(() -> null);
		}
	}

	// ------------------------------------------------------------------------------------------------ surface, colony

	private void setUpCamera() {
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.setGameMode(GameType.CREATIVE);
			player.getInventory().clearContent();
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.setPermanentlyInvulnerable(true);
			if (Charters.found(server, player.getUUID(), "Design Tour Co.").isPresent()) {
				throw new AssertionError("founding the charter should succeed");
			}
			charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
			Charters.deposit(server, charter, FUNDS);
		});
		ctx.runOnClient(client -> {
			client.options.setCameraType(CameraType.FIRST_PERSON);
			setHidden(client, true);
		});
	}

	/** A point {@code dx} east and {@code dz} south of the middle of the square (the foot of the statue), {@code h} above it. */
	private Vec3 p(double dx, double h, double dz) {
		return rel(ColonyAnchor.STATUE, dx, h, dz);
	}

	/** A point {@code dx} east, {@code dy} up and {@code dz} south of the bottom centre of the block of {@code anchor}. */
	private Vec3 rel(ColonyAnchor anchor, double dx, double dy, double dz) {
		return Vec3.atBottomCenterOf(anchors.get(anchor)).add(dx, dy, dz);
	}

	/** What a still is of: a block that must stand within {@code radius} blocks of the point the camera looks at. */
	private record Subject(String what, Predicate<BlockState> test, double radius) { }

	/** Takes the named still of {@code target} from {@code eye}, after checking that the {@code subject} is really there. */
	private void shoot(String stillName, int layer, Vec3 eye, Vec3 target, int wait, Subject subject) {
		view(layer, eye, target, wait);
		verify(stillName, layer, eye, target, subject);
		still(stillName);
	}

	/**
	 * Throws an {@link AssertionError} naming the still when the world has moved the subject: when no block of the subject is within
	 * its radius of {@code target}, or when something else stands in the line of sight and hides it. A building that moves in the
	 * overhaul then fails the tour loudly, and does not make it shoot the wrong thing.
	 */
	private void verify(String stillName, int layer, Vec3 eye, Vec3 target, Subject subject) {
		String problem = serverGet(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(layer));
			BlockPos centre = BlockPos.containing(target);
			int reach = (int) Math.ceil(subject.radius());
			boolean found = BlockPos.betweenClosedStream(centre.offset(-reach, -reach, -reach), centre.offset(reach, reach, reach))
					.anyMatch(pos -> Vec3.atCenterOf(pos).distanceTo(target) <= subject.radius()
							&& subject.test().test(level.getBlockState(pos)));
			if (!found) {
				return "no " + subject.what() + " within " + subject.radius() + " blocks of " + target;
			}
			BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player(server)));
			if (hit.getType() == HitResult.Type.MISS) {
				return null;
			}
			BlockState first = level.getBlockState(hit.getBlockPos());
			if (!subject.test().test(first) && hit.getLocation().distanceTo(target) > subject.radius() + 1) {
				return first + " at " + hit.getBlockPos().toShortString() + " is in the way of " + subject.what()
						+ " (from " + eye + " to " + target + ", hit at " + hit.getLocation() + ")";
			}
			return null;
		});
		if (problem != null) {
			throw new AssertionError("still " + stillName + ": " + problem);
		}
	}

	/** Throws, naming the still, if a building or a hill now stands where the pods are put: the 9 x 9 blocks round {@code stage}. */
	private void expectOpenGround(String stillName, Vec3 stage) {
		BlockPos centre = BlockPos.containing(stage);
		BlockPos solid = serverGet(server -> BlockPos.betweenClosedStream(centre.offset(-4, 0, -4), centre.offset(4, 3, 4))
				.filter(pos -> !server.overworld().getBlockState(pos).getCollisionShape(server.overworld(), pos).isEmpty())
				.findFirst().map(BlockPos::immutable).orElse(null));
		if (solid != null) {
			throw new AssertionError("still " + stillName + ": " + solid.toShortString() + " is solid, so the pods' stage is not open ground");
		}
	}

	/** The stage for the pods, on the open ground north-east of the square. */
	private Vec3 stage() {
		return p(22, 0, -18);
	}

	private void surface() {
		// From the pad's south edge, where nothing of the colony is in the way to the south, east and west.
		Vec3 stand = p(0, EYE, 28);
		String[] names = {"south", "east", "west", "north"};
		Vec3[] targets = {p(0, EYE, 128), p(100, EYE, 28), p(-100, EYE, 28), p(0, EYE, -72)};
		view(0, stand, targets[0], 100);
		for (int i = 0; i < names.length; i++) {
			view(0, stand, targets[i], 20);
			still("surface-" + names[i] + "-noon");
		}
		view(0, stand, p(0, EYE + 90, 70), 20);
		still("sky-up-noon");
		serverDo(server -> command(server, "time set 12500"));
		view(0, stand, targets[0], 40);
		still("surface-south-dusk");
		view(0, stand, p(0, EYE + 70, 120), 20);
		still("sky-up-dusk");
		serverDo(server -> command(server, "time set 18000"));
		view(0, stand, targets[3], 40);
		still("surface-north-night");
		view(0, stand, p(0, EYE + 90, 70), 20);
		still("sky-up-night");
		serverDo(server -> command(server, "time set noon"));
	}

	private void colonyTour() {
		Subject statue = new Subject("the bronze statue", state -> state.is(Blocks.COPPER_BLOCK.waxed().unaffected()), 3);
		shoot("colony-aerial-south", 0, p(0, 32, 62), p(0, 3, 0), 80, statue);
		shoot("colony-aerial-northwest", 0, p(-48, 30, -48), p(0, 3, 0), 30, statue);
		shoot("colony-aerial-northeast", 0, p(48, 30, -48), p(0, 3, 0), 30, statue);
		shoot("colony-from-straight-above", 0, p(0, 80, 25), p(0, 0, 0), 30, statue);
		shoot("colony-from-the-south-edge", 0, p(0, EYE, 30), p(0, 4, 0), 30, statue);

		// The orbit for the GIF.
		for (int i = 0; i < ORBIT_FRAMES; i++) {
			double angle = 2 * Math.PI * i / ORBIT_FRAMES + Math.PI / 2;
			Vec3 eye = p(Math.cos(angle) * ORBIT_RADIUS, ORBIT_HEIGHT, Math.sin(angle) * ORBIT_RADIUS);
			view(0, eye, p(0, 3, 0), i == 0 ? 40 : 2);
			if (i == 0) {
				verify("colony-orbit", 0, eye, p(0, 3, 0), statue);
			}
			frame(ctx);
		}

		// Close-ups, each from the open side of the building (the doorway of a ruin, the south face of a plinth). Every view is
		// measured from the building's own anchor, so it follows the building when the colony is redrawn.
		for (TerminalType type : TerminalTypes.all()) {
			Optional<ColonyAnchor> anchor = ColonyAnchor.forTerminal(type);
			if (anchor.isPresent()) {
				Vec3 plinth = rel(anchor.get(), 0, 0, 0);
				shoot("terminal-" + type.id().getPath().replace('_', '-'), 0, plinth.add(0, 2.0, 4.5), plinth.add(0, 0.5, 0), 20,
						new Subject("the " + type.id().getPath() + " terminal", state -> state.is(type.block()), 1.5));
			}
		}
		Vec3 upgrade = rel(ColonyAnchor.UPGRADE_TERMINAL, 0, 0, 0);
		// From the south-east, so that the statue, which stands in line with the upgrade terminal, is not in the line of sight.
		shoot("terminal-row", 0, upgrade.add(7, 4, 14), upgrade.add(0, 0.5, 0), 20,
				new Subject("a terminal", state -> TerminalTypes.all().stream().anyMatch(type -> state.is(type.block())), 1.5));
		shoot("statue-from-the-square", 0, p(7, 4, 8), p(0, 4, 0), 20, statue);
		shoot("statue-close", 0, p(3, 5.5, 3.5), p(0, 6, 0), 20, statue);
		shoot("statue-hands-from-above", 0, p(-3, 8, 12), p(0, 5, 0), 20, statue);

		Subject lectern = new Subject("the lectern", state -> state.is(Blocks.LECTERN), 2.5);
		Vec3 office = rel(ColonyAnchor.CONTINUITY_OFFICE, 0, 0, 0);
		shoot("continuity-office-from-the-square", 0, office.add(-12, EYE, 0), office.add(0, 1.3, 0), 40,
				new Subject("the lectern", state -> state.is(Blocks.LECTERN), 6));
		shoot("continuity-office-inside", 0, office.add(-2, 2.5, 0), office.add(3, 1.5, -3), 20, lectern);

		Vec3 hangar = rel(ColonyAnchor.HANGAR, 0, 0, 0);
		shoot("hangar-from-the-square", 0, hangar.add(14, EYE, 0), hangar.add(0, 1.3, 0), 40,
				new Subject("the hangar's iron floor", state -> state.is(Blocks.IRON_BLOCK), 3));

		Vec3 candle = rel(ColonyAnchor.CHAPEL_CANDLE, 0, 0, 0);
		Subject lit = new Subject("the lit chapel candle", state -> state.is(Blocks.CANDLE) && state.getValue(CandleBlock.LIT), 1.5);
		shoot("chapel-from-the-square", 0, candle.add(0, 0.62, 10), candle.add(0, 0.3, 0), 40, lit);
		shoot("chapel-altar-and-candle", 0, candle.add(0, 0.62, 6), candle.add(-0.5, 0.3, 0), 20, lit);

		Vec3 bunkhouse = rel(ColonyAnchor.BUNKHOUSE, 0, 0, 0);
		shoot("bunkhouse-from-the-square", 0, bunkhouse.add(0, EYE, -9), bunkhouse.add(0, 1.3, 0), 40,
				new Subject("a bed", state -> state.getBlock() instanceof BedBlock, 4));
		Vec3 pay = rel(ColonyAnchor.PAY_OFFICE, 0, 0, 0);
		shoot("pay-office-from-the-square", 0, pay.add(0, EYE, -7), pay.add(0, 1.3, 2.5), 40,
				new Subject("the grille", state -> state.is(Blocks.IRON_BARS), 3));
		Vec3 personnel = rel(ColonyAnchor.PERSONNEL_OFFICE, 0, 0, 0);
		shoot("personnel-office-from-the-square", 0, personnel.add(0, EYE, -8), personnel.add(0, 1.5, 2), 40,
				new Subject("Joy's calendar (a Note)", state -> state.getBlock() instanceof NoteBlock, 3));
		Vec3 bar = rel(ColonyAnchor.LAMP_AND_PICK, 0, 0, 0);
		// Its doorway faces the hangar's south wall, 2 blocks away, so it is shot from the square side, from above its east wall.
		shoot("lamp-and-pick-from-the-square", 0, bar.add(16, 12, 0), bar.add(0, 0.3, 2), 40,
				new Subject("the coal blocks of the bar", state -> state.is(Blocks.COAL_BLOCK), 3));

		Vec3 conduit = rel(ColonyAnchor.CONDUIT, 0, 0, 0);
		Subject casing = new Subject("the conduit casing", state -> state.is(ColonyBlocks.CONDUIT), 3);
		shoot("conduit-from-the-square", 0, conduit.add(0, 8, 18), conduit.add(0, 8, 0), 30, casing);
		shoot("conduit-from-the-west", 0, conduit.add(-16, 10, 10), conduit.add(0, 8, 0), 20, casing);
		shoot("conduit-from-the-north", 0, conduit.add(0, 22, -12), conduit.add(0, 6, 0), 20, casing);
	}

	// ------------------------------------------------------------------------------------------------ terminals

	private void terminalScreens() {
		Vec3 derelict = serverGet(server -> Hangar.derelict(server).orElseThrow().position());
		BlockPos console = serverGet(server -> Hangar.consolePos(server).orElseThrow());
		Vec3 consoleEye = Vec3.atBottomCenterOf(console.west(2)).add(0, EYE, 0);
		view(0, consoleEye, derelict.add(0, 1, 0), 60);
		still("hangar-derelict-mole");
		Vec3 bay = rel(ColonyAnchor.HANGAR, 0, 0, 0);
		if (derelict.distanceTo(bay) > 6) {
			throw new AssertionError("still hangar-derelict-mole: the derelict Mole is " + derelict.distanceTo(bay) + " blocks from the hangar anchor");
		}
		view(0, bay.add(5.5, 3, -2), derelict.add(0, 0.8, 0), 20);
		still("hangar-derelict-mole-from-the-door");
		view(0, bay.add(-5, 3, 5), derelict.add(0, 0.8, 0), 20);
		still("hangar-derelict-mole-from-the-back");

		// Offline: the screen where the parts go in.
		List<TerminalType> order = List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION);
		ColonyAnchor[] anchorOf = {ColonyAnchor.FUEL_PUMP, ColonyAnchor.ORE_PROCESSOR, ColonyAnchor.UPGRADE_TERMINAL, ColonyAnchor.REPAIR_STATION};
		for (int i = 0; i < order.size(); i++) {
			openTerminal(anchors.get(anchorOf[i]), TerminalScreen.class);
			still("screen-" + order.get(i).id().getPath().replace('_', '-') + "-offline");
			ctx.setScreen(() -> null);
		}
		openAt(console, consoleEye, TerminalScreen.class);
		still("screen-hangar-console-offline");
		ctx.setScreen(() -> null);

		// Repair the four colony terminals at once, and park a worked pod at each one so the screens have something to show.
		serverDo(server -> {
			RepairState state = RepairState.get(server);
			for (TerminalType type : order) {
				type.parts().forEach(part -> state.insert(type, part));
			}
		});
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
			player.getInventory().add(OreRegistry.stack(OreType.SILVERIUM));
		});
		Class<?>[] online = {FuelPumpScreen.class, OreProcessorScreen.class, UpgradeScreen.class, RepairStationScreen.class};
		for (int i = 0; i < order.size(); i++) {
			PodEntity pod = spawnPod(PodRegistry.POD, 0, rel(anchorOf[i], 0, -1, 3.5), 0f, false);
			serverDo(server -> {
				pod.setFuel(34f);
				pod.damageHull(pod.maxHull() * 0.45f);
				pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
				pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.PLATINIUM));
			});
			openTerminal(anchors.get(anchorOf[i]), online[i]);
			still("screen-" + order.get(i).id().getPath().replace('_', '-') + "-online");
			ctx.setScreen(() -> null);
			serverDo(server -> pod.discard());
		}
		openTerminal(anchors.get(ColonyAnchor.CONTRACT_TERMINAL), ContractScreen.class);
		still("screen-contract-terminal");
		ctx.setScreen(() -> null);

		// The hangar console takes its four parts and repairs the founding Mole.
		view(0, consoleEye, Vec3.atCenterOf(console), 20);
		serverDo(server -> {
			ServerPlayer player = player(server);
			for (Item part : HangarParts.ALL) {
				player.getInventory().add(new ItemStack(part));
				Terminals.insertPart(player, console, part).ifPresent(refusal -> {
					throw new AssertionError("inserting " + part + ": " + refusal);
				});
			}
		});
		ctx.waitTicks(40);
		openAt(console, consoleEye, HangarScreen.class);
		still("screen-hangar-console-online");
		ctx.setScreen(() -> null);
		view(0, consoleEye, derelict.add(0, 1, 0), 40);
		still("hangar-founding-mole-repaired");
		serverDo(server -> player(server).getInventory().clearContent());
	}

	private void openTerminal(BlockPos terminal, Class<?> screen) {
		Vec3 eye = Vec3.atCenterOf(terminal).add(0, 0.9, 2.6);
		openAt(terminal, eye, screen);
	}

	private void openAt(BlockPos terminal, Vec3 eye, Class<?> screen) {
		view(0, eye, Vec3.atCenterOf(terminal), 20);
		ctx.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(terminal)));
		ctx.waitFor(client -> screen.isInstance(client.gui.screen()), WAIT);
		if (!TerminalViewScreen.class.isAssignableFrom(screen)) {
			throw new AssertionError(screen + " is not a terminal screen");
		}
		ctx.waitTicks(TERMINAL_TYPING_TICKS);
	}

	// ------------------------------------------------------------------------------------------------ pods

	private PodEntity spawnPod(EntityType<PodEntity> type, int layer, Vec3 at, float yaw, boolean lights) {
		return serverGet(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(layer));
			PodEntity pod = type.create(level, EntitySpawnReason.COMMAND);
			pod.setPos(at);
			pod.setYRot(yaw);
			level.addFreshEntity(pod);
			PodComponents.register(pod, charter);
			if (lights) {
				PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
			}
			return pod;
		});
	}

	/** The pod stands facing south (yaw 0). Front is its south side, back its north, side its east. */
	private void podAngles(String prefix, int layer, Vec3 base, double distance, double height, String[] angles) {
		Vec3 centre = base.add(0, height / 2, 0);
		for (String angle : angles) {
			Vec3 eye = switch (angle) {
				case "front" -> base.add(0, 1.8, distance);
				case "side" -> base.add(distance, 1.8, 0);
				case "back" -> base.add(0, 1.8, -distance);
				case "top" -> base.add(0, distance + 1, 0.5 * distance);
				default -> throw new IllegalArgumentException(angle);
			};
			view(layer, eye, centre, 12);
			still(prefix + "-" + angle);
		}
	}

	private static final String[] FSB = {"front", "side", "back"};

	private void podsByDay() {
		Vec3 stage = stage();
		expectOpenGround("pods-by-day", stage);
		PodEntity mole = spawnPod(PodRegistry.POD, 0, stage, 0f, false);
		view(0, stage.add(0, 1.8, 6), stage.add(0, 1, 0), 60);
		podAngles("mole-unlit-day", 0, stage, 5.5, 1.9, FSB);
		podAngles("mole-unlit-day", 0, stage, 5.5, 1.9, new String[] {"top"});
		serverDo(server -> mole.discard());
		PodEntity prospector = spawnPod(PodRegistry.PROSPECTOR, 0, stage, 0f, false);
		podAngles("prospector-unlit-day", 0, stage, 7.5, 2.9, FSB);
		podAngles("prospector-unlit-day", 0, stage, 7.5, 2.9, new String[] {"top"});
		serverDo(server -> prospector.discard());

		// Wrecks: the hull gone to zero, the pod dark.
		PodEntity wreckedMole = spawnPod(PodRegistry.POD, 0, stage.add(-3, 0, 0), 0f, false);
		PodEntity wreckedProspector = spawnPod(PodRegistry.PROSPECTOR, 0, stage.add(3.5, 0, 0), 0f, false);
		serverDo(server -> {
			wreckedMole.damageHull(wreckedMole.maxHull());
			wreckedProspector.damageHull(wreckedProspector.maxHull());
		});
		view(0, stage.add(0, 2.4, 8), stage.add(0, 1, 0), 40);
		still("wrecks-mole-and-prospector-day");
		view(0, stage.add(-3, 1.8, 5.5), stage.add(-3, 1, 0), 20);
		still("wreck-mole-front-day");
		view(0, stage.add(3.5, 2, 7), stage.add(3.5, 1.4, 0), 20);
		still("wreck-prospector-front-day");
		serverDo(server -> {
			wreckedMole.discard();
			wreckedProspector.discard();
		});
	}

	/** Every block of the mod in a row on the pad, seven to a still, fronts to the camera. */
	private void blockGallery() {
		List<Block> blocks = BuiltInRegistries.BLOCK.keySet().stream()
				.filter(id -> id.getNamespace().equals(DeepCharter.MOD_ID))
				.sorted()
				.map(BuiltInRegistries.BLOCK::getValue)
				.toList();
		BlockPos row = ground.offset(-8, 1, 28);
		serverDo(server -> {
			ServerLevel level = server.overworld();
			for (int i = 0; i < blocks.size(); i++) {
				BlockState state = blocks.get(i).defaultBlockState();
				if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
					state = state.setValue(BlockStateProperties.HORIZONTAL_FACING,
							Direction.SOUTH);
				}
				level.setBlock(row.offset(i, 0, 0), state, 3);
			}
		});
		for (int page = 0; page * 7 < blocks.size(); page++) {
			int mid = Math.min(blocks.size() - 1, page * 7 + 3);
			Vec3 target = Vec3.atCenterOf(row.offset(mid, 0, 0));
			view(0, target.add(0, 1.2, 6), target, page == 0 ? 60 : 12);
			still("block-gallery-" + (page + 1));
		}
	}

	// ------------------------------------------------------------------------------------------------ dark room

	private void darkRoom() {
		BlockPos floor = ground.offset(ROOM_OFFSET, 0, ROOM_OFFSET).atY(ROOM_Y);
		serverDo(server -> {
			ServerLevel level = server.overworld();
			loadChunks(level, floor.getX(), floor.getZ(), 2);
			for (int dx = -ROOM_RADIUS - 1; dx <= ROOM_RADIUS + 1; dx++) {
				for (int dz = -ROOM_RADIUS - 1; dz <= ROOM_RADIUS + 1; dz++) {
					for (int dy = 0; dy <= ROOM_HEIGHT + 1; dy++) {
						boolean shell = dy == 0 || dy == ROOM_HEIGHT + 1 || Math.abs(dx) == ROOM_RADIUS + 1 || Math.abs(dz) == ROOM_RADIUS + 1;
						// room-carver: a room in the overworld closed by its own stone shell, not layer rock
						level.setBlock(floor.offset(dx, dy, dz), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
					}
				}
			}
		});
		Vec3 base = Vec3.atBottomCenterOf(floor.above());
		view(0, base.add(0, 1.8, 6), base.add(0, 1, 0), 120);

		// Unlit first: a pod with no lights part in a room with no light at all.
		PodEntity mole = spawnPod(PodRegistry.POD, 0, base, 0f, false);
		view(0, base.add(0, 1.8, 5.5), base.add(0, 1, 0), 40);
		still("mole-unlit-dark-front");
		serverDo(server -> mole.discard());
		PodEntity prospector = spawnPod(PodRegistry.PROSPECTOR, 0, base, 0f, false);
		view(0, base.add(0, 1.8, 7.5), base.add(0, 1.4, 0), 40);
		still("prospector-unlit-dark-front");
		serverDo(server -> prospector.discard());

		// Lit: the lights part is a light source round the pod.
		PodEntity litMole = spawnPod(PodRegistry.POD, 0, base, 0f, true);
		ctx.waitTicks(30);
		podAngles("mole-lit-dark", 0, base, 5.5, 1.9, FSB);
		serverDo(server -> litMole.discard());
		PodEntity litProspector = spawnPod(PodRegistry.PROSPECTOR, 0, base, 0f, true);
		ctx.waitTicks(30);
		podAngles("prospector-lit-dark", 0, base, 7.5, 2.9, FSB);
		serverDo(server -> litProspector.discard());

		// The lampless figure, still, in the dark room: once with night vision so it can be seen, once as the player would see it.
		LamplessFigure figure = serverGet(server -> {
			ServerLevel level = server.overworld();
			LamplessFigure f = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			f.setPos(base);
			f.setNoAi(true);
			level.addFreshEntity(f);
			return f;
		});
		view(0, base.add(0, 1.8, 5), base.add(0, 1.2, 0), 40);
		still("lampless-figure-dark-no-night-vision");
		nightVision();
		ctx.waitTicks(10);
		for (String angle : FSB) {
			Vec3 eye = switch (angle) {
				case "front" -> base.add(0, 1.8, 4.5);
				case "side" -> base.add(4.5, 1.8, 0);
				default -> base.add(0, 1.8, -4.5);
			};
			view(0, eye, base.add(0, 1.2, 0), 12);
			still("lampless-figure-" + angle);
		}
		view(0, base.add(0, 1.8, 2.2), base.add(0, 1.6, 0), 12);
		still("lampless-figure-close");
		serverDo(server -> figure.discard());

		// Walking toward a lit pod: it fades near the light.
		PodEntity lamp = spawnPod(PodRegistry.POD, 0, base.add(3, 0, 0), 0f, true);
		LamplessFigure walker = serverGet(server -> {
			ServerLevel level = server.overworld();
			LamplessFigure f = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			f.setPos(base.add(-6, 0, 0));
			f.setHeading(Direction.EAST);
			level.addFreshEntity(f);
			return f;
		});
		view(0, base.add(-1, 2.2, 7), base.add(-1, 1.2, 0), 10);
		for (int frames = 0; frames < 80 && !serverGet(server -> walker.fadeFraction() > 0 || walker.isRemoved()); frames++) {
			ctx.waitTicks(3);
		}
		ctx.waitTicks(4);
		still("lampless-figure-fading-by-a-lit-pod");
		serverDo(server -> {
			walker.discard();
			lamp.discard();
			player(server).removeEffect(MobEffects.NIGHT_VISION);
		});
	}

	// ------------------------------------------------------------------------------------------------ HUDs

	/** The pod's cargo screen, opened as a player does: sneak and use the pod. */
	private void cargoScreen() {
		Vec3 stage = stage();
		view(0, stage.add(0, EYE, -3), stage.add(2, 1, -3), 20);
		PodEntity pod = spawnPod(PodRegistry.POD, 0, stage.add(2, 0, -3), 0f, false);
		serverDo(server -> {
			for (OreType type : OreType.values()) {
				pod.cargo().tryAdd(pod, OreRegistry.stack(type));
			}
			ServerPlayer player = player(server);
			player.setShiftKeyDown(true);
			UseEntityCallback.EVENT.invoker()
					.interact(player, player.level(), InteractionHand.MAIN_HAND, pod, null);
			player.setShiftKeyDown(false);
		});
		ctx.waitForScreen(OreCargoScreen.class);
		ctx.waitTicks(10);
		still("screen-pod-cargo");
		ctx.setScreen(() -> null);
		serverDo(server -> pod.discard());
	}

	private void hudsOnTheSurface() {
		cargoScreen();
		Vec3 stage = stage();
		view(0, stage.add(0, EYE, 0), stage.add(0, EYE, 20), 60);
		ScannerHudTest.mountFirstPlayer(sp.getServer(), 0);
		ctx.waitFor(client -> client.player.getVehicle() instanceof PodEntity, WAIT);
		fillPodForHud();
		hud(true);
		ctx.waitTicks(20);
		still("hud-pod-status-and-altimeter-surface");
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
		ctx.waitTicks(20);
		still("hud-pod-in-third-person-surface");
		ctx.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
		leavePod();

		ScannerHudTest.rideWithGoldAhead(ctx, sp.getServer(), 1);
		fillPodForHud();
		ctx.waitTicks(20);
		still("hud-scanner-tier-1-surface");
		leavePod();
		hud(false);
	}

	/** Puts the player out of the pod they ride, if they ride one, and removes it. */
	private void leavePod() {
		serverDo(server -> {
			ServerPlayer player = player(server);
			if (player.getVehicle() instanceof PodEntity pod) {
				player.stopRiding();
				pod.discard();
			}
		});
		ctx.waitFor(client -> client.player.getVehicle() == null, WAIT);
	}

	private void fillPodForHud() {
		serverDo(server -> {
			PodEntity pod = (PodEntity) player(server).getVehicle();
			pod.setFuel(62f);
			pod.damageHull(pod.maxHull() * 0.3f);
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
		});
	}

	private static void setHidden(Minecraft client, boolean hidden) {
		if (client.gui.hud.isHidden() != hidden) {
			client.gui.hud.toggle();
		}
	}

	private void hud(boolean shown) {
		ctx.runOnClient(client -> setHidden(client, !shown));
	}

	// ------------------------------------------------------------------------------------------------ layer 1

	private void layerOne() {
		serverDo(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			loadChunks(one, LAYER_X, LAYER_Z, 3);
		});
		Vec3 cell = serverGet(server -> openCell(server.getLevel(LayerChain.dimension(1))));
		terrainViews(1, cell);

		// The structures of layer 1: the three shafts, one in each zone.
		for (StructureKind kind : StructureKind.inLayer(1)) {
			StructureSite site = siteOf(kind, null);
			String name = kind.name().toLowerCase().replace('_', '-');
			int niche = site.height() / 2;
			view(1, at(site, 0, site.height() - 1.2, 0), at(site, 0, 0, 0), 120);
			still("structure-" + name + "-looking-down");
			shoot("structure-" + name + "-note-niche", 1, at(site, -0.6, niche + 1.0, 0), at(site, 2, niche + 0.5, 0.5), 20,
					new Subject("the Note in the niche", state -> state.getBlock() instanceof NoteBlock, 1.5));
			view(1, at(site, 0.6, niche - 3, 0), at(site, 0, niche + 4, 0), 20);
			still("structure-" + name + "-looking-up");
		}

		// The breach crust: a room cut onto the floor of the layer, then the crust broken through.
		int x = LAYER_X + 200;
		int z = LAYER_Z + 200;
		serverDo(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			loadChunks(one, x, z, 2);
			BlockPos min = new BlockPos(x - 3, 3, z - 3);
			BlockPos max = new BlockPos(x + 3, 8, z + 3);
			RoomCarver.carve(one, min, max, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		});
		nightVision();
		view(1, new Vec3(x + 0.5, 6.5, z + 0.5), new Vec3(x + 0.5, 1.5, z + 1.5), 120);
		still("layer-1-breach-crust-floor");
		view(1, new Vec3(x - 2.5, 4.5, z - 2.5), new Vec3(x + 1.5, 3, z + 1.5), 20);
		still("layer-1-breach-crust-floor-low");
		serverDo(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int y = one.getMinY(); y <= one.getMinY() + 3; y++) {
						BreachService.breakCrust(one, new BlockPos(x + dx, y, z + dz));
					}
				}
			}
		});
		view(1, new Vec3(x + 0.5, 6.5, z + 0.5), new Vec3(x + 0.5, 1.5, z + 1.5), 40);
		still("layer-1-breach-crust-broken");
	}

	// ------------------------------------------------------------------------------------------------ breach, layer 2

	private void breachToLayerTwo() {
		int x = LAYER_X + 200;
		int z = LAYER_Z + 200;
		serverDo(server -> {
			ServerLevel two = server.getLevel(LayerChain.dimension(2));
			loadChunks(two, x, z, 3);
		});
		hud(true);
		ctx.runOnClient(client -> client.player.getInventory().clearContent());
		view(1, new Vec3(x + 0.5, 4.0, z + 0.5), new Vec3(x + 0.5, 0.5, z + 1.5), 30);
		clearTransmissions();
		still("hud-altimeter-layer-1-floor");
		serverDo(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			player(server).teleportTo(one, x + 0.5, one.getMinY() - 1, z + 0.5, Set.of(), 0, 60, true);
		});

		// The fade, then the two transmissions of the crossing, each shot once it has typed out.
		boolean faded = false;
		Set<Identifier> shot = new HashSet<>();
		int tail = 0;
		Identifier last = null;
		for (int tick = 0; tick < 1500 && !(shot.size() >= 2 && tail >= 12); tick++) {
			float alpha = ctx.computeOnClient(client -> BreachEffects.fadeAlpha(0f));
			if (!faded && alpha >= FADE_SHOT_ALPHA && ctx.computeOnClient(client -> client.gui.screen() == null)) {
				faded = true;
				screenshot(ctx, "hud-breach-fade");
			}
			Identifier current = ctx.computeOnClient(client -> TransmissionOverlay.transmission().map(Transmission::id).orElse(null));
			boolean typed = ctx.computeOnClient(client -> TransmissionOverlay.typed());
			if (typed && current != null && shot.add(current)) {
				screenshot(ctx, "hud-transmission-" + current.getPath());
			}
			tail = typed && current != null && current.equals(last) ? tail + 1 : 0;
			last = current;
			ctx.waitTick();
		}
		ctx.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(2)), WAIT);
		clearTransmissions();
	}

	private void layerTwo() {
		serverDo(server -> {
			ServerLevel two = server.getLevel(LayerChain.dimension(2));
			loadChunks(two, LAYER_X, LAYER_Z, 3);
		});
		hud(false);
		Vec3 cell = serverGet(server -> openCell(server.getLevel(LayerChain.dimension(2))));
		terrainViews(2, cell);

		// A pod in the dark with a scanner: the HUD at depth.
		// A sealed hall, so that no lava or gas of the cave reaches the pod.
		Vec3 hall = serverGet(server -> {
			ServerLevel two = server.getLevel(LayerChain.dimension(2));
			int x = LAYER_X + 120;
			int z = LAYER_Z + 120;
			int floor = (two.getMinY() + two.getMaxY()) / 2;
			loadChunks(two, x, z, 3);
			BlockPos min = new BlockPos(x - 6, floor, z - 6);
			BlockPos max = new BlockPos(x + 6, floor + 7, z + 6);
			RoomCarver.carve(two, min, max, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			return new Vec3(x + 0.5, floor + EYE, z + 0.5);
		});
		view(2, hall, hall.add(1, 0, 0), 60);
		hud(true);
		serverDo(server -> player(server).removeEffect(MobEffects.NIGHT_VISION));
		ScannerHudTest.mountFirstPlayer(sp.getServer(), 4);
		ctx.waitFor(client -> client.player.getVehicle() instanceof PodEntity, WAIT);
		fillPodForHud();
		ctx.waitTicks(80);
		still("hud-scanner-tier-4-layer-2");
		leavePod();
		hud(false);
		nightVision();

		// The structures of layer 2.
		for (StructureKind kind : StructureKind.inLayer(2)) {
			if (kind == StructureKind.WRECK) {
				continue;
			}
			StructureSite site = siteOf(kind, null);
			switch (kind) {
				case GALLERY -> {
					view(2, at(site, -8, 2, 0), at(site, 12, 1.5, 0), 120);
					still("structure-gallery-toward-the-rubble");
					// The board is on the near end wall and the rubble at the far end, so stand toward the board's end, in the clear.
					shoot("structure-gallery-quota-board", 2, at(site, -4, 1.8, 0), at(site, -12.5, 1.8, 0), 20,
							new Subject("the quota board", state -> state.is(Blocks.CONCRETE.black()), 1.5));
					shoot("structure-gallery-note-n08", 2, at(site, -9.5, 2.2, 0.8), at(site, -12, 0.8, 0), 20,
							new Subject("Note N08", state -> state.getBlock() instanceof NoteBlock, 1.5));
				}
				case PUNCH_CLOCK -> {
					view(2, at(site, -7, 3, -7), at(site, 2, 1, 2), 120);
					still("structure-punch-clock-overview");
					shoot("structure-punch-clock-shelves", 2, at(site, 2, 1.8, -1), at(site, 7.5, 1.5, 0), 20,
							new Subject("the shelves", state -> state.is(Blocks.BOOKSHELF), 1.5));
					// Down the middle of the hall, clear of the stone pillars, at the lit sea lantern above the clock.
					shoot("structure-punch-clock-lit-clock", 2, at(site, -1, 2.2, 0), at(site, -7, 2.5, 0), 20,
							new Subject("the lit sea lantern", state -> state.is(Blocks.SEA_LANTERN), 1.5));
				}
				case RAILS -> {
					view(2, at(site, -30, 2, 0), at(site, 30, 1.5, 0), 120);
					still("structure-rails-long-drift");
					// Head on at a timber set: its two posts and the cap across.
					shoot("structure-rails-timbering", 2, at(site, -6, 1.7, 0), at(site, 0, 1.7, 0), 20,
							new Subject("a timber post", state -> state.is(Blocks.OAK_LOG), 2.5));
				}
				default -> throw new AssertionError(kind + " has no views in the tour");
			}
		}

		// The wreck sites: Prospector's wreck, with its lamp and Note N10, and an empty bay.
		StructureSite prospector = serverGet(server -> LayerStructures.prospector(server).orElseThrow());
		loadSite(prospector);
		Vec3 pod = at(prospector, 0, 1.2, 0);
		view(2, at(prospector, 5, 2, 0), pod, 120);
		still("structure-wreck-prospector-0002");
		view(2, at(prospector, 0, 2, 5), pod, 20);
		still("structure-wreck-prospector-0002-side");
		view(2, at(prospector, -5, 2, 0), pod, 20);
		still("structure-wreck-prospector-0002-back");
		// The burning lamp is the lantern at (3, 2): close, from the side away from the pod, so the pod is not in front of it.
		shoot("structure-wreck-the-lamp", 2, at(prospector, 5.5, 1.4, 3.8), at(prospector, 3, 0.6, 2), 20,
				new Subject("the burning lantern", state -> state.is(Blocks.LANTERN), 1.2));
		shoot("structure-wreck-note-n10-on-the-table", 2, at(prospector, 1.5, 1.6, 6), at(prospector, 0, 1.2, 4), 20,
				new Subject("Note N10", state -> state.getBlock() instanceof NoteBlock, 1.5));
		view(2, at(prospector, 4, 6, 4), at(prospector, 0, 0, 0), 20);
		still("structure-wreck-from-above");
		serverDo(server -> player(server).removeEffect(MobEffects.NIGHT_VISION));
		view(2, at(prospector, 5, 2, 0), pod, 40);
		still("structure-wreck-prospector-0002-no-night-vision");
		nightVision();
		BlockPos far = serverGet(server -> Colony.anchor(server, ColonyAnchor.CONDUIT).orElseThrow()).offset(384, 0, 384);
		StructureSite bay = siteOf(StructureKind.WRECK, far);
		view(2, at(bay, 5, 2.2, 0), at(bay, 0, 0.8, 0), 120);
		still("structure-wreck-empty-bay");
	}

	/** A cell of air with room round it, close to the layer's start column, as the middle of a cave. */
	private static Vec3 openCell(ServerLevel level) {
		BlockPos best = null;
		int bestClearance = 0;
		for (int x = LAYER_X - 40; x <= LAYER_X + 40; x += 2) {
			for (int z = LAYER_Z - 40; z <= LAYER_Z + 40; z += 2) {
				for (int y = level.getMinY() + 24; y <= level.getMaxY() - 40; y += 2) {
					BlockPos pos = new BlockPos(x, y, z);
					int clearance = clearance(level, pos);
					if (clearance > bestClearance) {
						best = pos;
						bestClearance = clearance;
					}
				}
			}
		}
		if (best == null) {
			// No cave in reach: cut a hall into the rock, sealed first, so there is a place to stand.
			int mid = (level.getMinY() + level.getMaxY()) / 2;
			BlockPos min = new BlockPos(LAYER_X - 6, mid, LAYER_Z - 6);
			BlockPos max = new BlockPos(LAYER_X + 6, mid + 6, LAYER_Z + 6);
			RoomCarver.carve(level, min, max, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			best = new BlockPos(LAYER_X, mid + 3, LAYER_Z);
		}
		return Vec3.atCenterOf(best).add(0, EYE - 0.5, 0);
	}

	private static int clearance(ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos).isAir()) {
			return 0;
		}
		int least = 8;
		for (Direction direction : Direction.values()) {
			int free = 0;
			while (free < least && level.getBlockState(pos.relative(direction, free + 1)).isAir()) {
				free++;
			}
			least = Math.min(least, free);
		}
		return least;
	}

	/** The cave: four ways round, up, down, and a view with no night vision, as it is played. */
	private void terrainViews(int layer, Vec3 cell) {
		serverDo(server -> player(server).removeEffect(MobEffects.NIGHT_VISION));
		view(layer, cell, cell.add(0, 0, 10), 120);
		still("layer-" + layer + "-cave-as-played-no-light");
		nightVision();
		String[] names = {"south", "west", "north", "east"};
		Vec3[] offsets = {new Vec3(0, 0, 10), new Vec3(-10, 0, 0), new Vec3(0, 0, -10), new Vec3(10, 0, 0)};
		for (int i = 0; i < names.length; i++) {
			view(layer, cell, cell.add(offsets[i]), 20);
			still("layer-" + layer + "-cave-" + names[i]);
		}
		view(layer, cell, cell.add(2, 10, 2), 20);
		still("layer-" + layer + "-cave-up");
		view(layer, cell, cell.add(2, -10, 2), 20);
		still("layer-" + layer + "-cave-down");
	}

	// ------------------------------------------------------------------------------------------------ structures

	private StructureSite siteOf(StructureKind kind, BlockPos near) {
		StructureSite site = serverGet(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(kind.layer()));
			BlockPos conduit = Colony.anchor(server, ColonyAnchor.CONDUIT).orElseThrow();
			return LayerStructures.nearest(level.getSeed(), kind, level.getMinY(), level.getHeight(), near == null ? conduit : near, conduit);
		});
		loadSite(site);
		return site;
	}

	private void loadSite(StructureSite site) {
		serverDo(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(site.kind().layer()));
			BoundingBox box = site.bounds();
			for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
				for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
					level.getChunk(cx, cz, ChunkStatus.FULL);
				}
			}
		});
	}

	/** The world position of a point in the structure's own axes, {@code y} up from the floor of its hollow. */
	private static Vec3 at(StructureSite site, double u, double y, double v) {
		BlockPos origin = site.origin();
		return new Vec3(origin.getX() + 0.5 + (site.alongZ() ? v : u), origin.getY() + y, origin.getZ() + 0.5 + (site.alongZ() ? u : v));
	}

	// ------------------------------------------------------------------------------------------------ transmissions

	private void remainingTransmissions() {
		hud(true);
		for (String id : List.of("t02", "surface_arrival")) {
			Identifier transmission = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, id);
			settle("hud-transmission-" + id);
			serverDo(server -> Transmissions.fire(server, charter, transmission));
			boolean typed = false;
			for (int tick = 0; tick < 1200 && !typed; tick++) {
				typed = ctx.computeOnClient(client -> TransmissionOverlay.typed());
				ctx.waitTick();
			}
			ctx.waitTicks(10);
			screenshot(ctx, "hud-transmission-" + id);
			clearTransmissions();
		}
	}

	private void clearTransmissions() {
		for (int tick = 0; tick < 1500 && ctx.computeOnClient(client -> TransmissionOverlay.transmission().isPresent()); tick += 4) {
			ctx.waitTicks(4);
		}
	}

	// ------------------------------------------------------------------------------------------------ helpers

	/** Puts the camera at {@code eye} in {@code layer}, looking at {@code target}, then waits {@code wait} ticks for chunks and light. */
	private void view(int layer, Vec3 eye, Vec3 target, int wait) {
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.setNoGravity(true);
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.teleportTo(server.getLevel(LayerChain.dimension(layer)), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true);
		});
		ctx.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(layer))
				&& client.player.distanceToSqr(eye.x, eye.y - EYE, eye.z) < 1.0, WAIT);
		ctx.waitTicks(wait);
	}

	/** A named still, with no toast, chat line or transmission over it. */
	private void still(String stillName) {
		ctx.runOnClient(client -> {
			client.gui.toastManager().clear();
			client.gui.hud.getChat().clearMessages(false);
		});
		clearTransmissions();
		settle(stillName);
		screenshot(ctx, stillName);
	}

	private void nightVision() {
		serverDo(server -> player(server).addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false)));
	}

	/** Loads the chunks within {@code radius} chunks of the one holding block column ({@code x}, {@code z}). */
	private static void loadChunks(ServerLevel level, int x, int z, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				level.getChunk((x >> 4) + dx, (z >> 4) + dz);
			}
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static void command(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private <T> T serverGet(Function<MinecraftServer, T> action) {
		return sp.getServer().computeOnServer(action::apply);
	}

	private void serverDo(Consumer<MinecraftServer> action) {
		sp.getServer().runOnServer(action::accept);
	}
}
