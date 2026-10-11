package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.creature.LamplessFigures;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Server GameTests for #449, the Retained as a mob: a dark pod with a pilot draws a figure that clings to the hull and drains the tank,
 * a lit pod does not, the rotor and a light free the pod, the figure never hurts anyone, and the natural spawn rules keep it to the open dark
 * of layers 1 and 2. A cling test builds a closed arena in a far chunk of the test world, with the pod at one end and the figure at the other.
 */
public class RetainedTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(RetainedTest.class);
	/** The arena's half width; it is 13 blocks across with its walls, inside the one chunk that is forced. */
	private static final int HALF = 6;
	private static final int ARENA_HEIGHT = 14;
	private static final int FIRST_SLOT_X = 6408;
	private static final int SLOT_Z_SPACING = 64;
	private static final int FLOOR_Y = 100;
	/** Ticks for a figure to walk 11 blocks to a pod: the slowest walk is 1 block a second. */
	private static final int APPROACH_BUDGET_TICKS = 11 * 20;
	/** Ticks a figure that is never freed clings for (200: 2.5 litres at 0.25 a second), with a margin for a loaded runner. */
	private static final int FILL_BUDGET_TICKS = 300;
	/** The fade (40 ticks) of a figure that let go, with a margin. */
	private static final int FADE_BUDGET_TICKS = 80;
	/** Ticks, once the rotor is held, in which the grip must break: the shake (40), a tick of lag and the check. */
	private static final int SHAKE_BUDGET_TICKS = 50;
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);
	private static final String GRAB_KEY = "creature.deepcharter.grab";
	/** Ticks for the light of a block to spread through a sealed room, or to go out of it: slow on a loaded runner. */
	private static final int LIGHT_BUDGET_TICKS = 200;
	private static final AtomicInteger CHARTERS = new AtomicInteger();

	private static final CreatureTuning TUNING = CreatureTuning.DEFAULT;
	private static final CreatureTuning.Cling CLING = TUNING.cling();

	/** A closed arena with a pod at its east end and a pilot aboard, and a figure at its west end. */
	private record Arena(ServerLevel level, BlockPos origin, PodEntity pod, MockPlayer pilot, LamplessFigure figure) {
		static Arena build(GameTestHelper helper, ServerLevel level, BlockPos origin, boolean lights) {
			for (int u = -HALF - 1; u <= HALF + 1; u++) {
				for (int v = -HALF - 1; v <= HALF + 1; v++) {
					for (int y = -1; y <= ARENA_HEIGHT; y++) {
						boolean shell = y == -1 || y == ARENA_HEIGHT || Math.abs(u) == HALF + 1 || Math.abs(v) == HALF + 1;
						// room-carver: a closed arena of its own stone shell in the overworld test structure, not layer rock
						level.setBlock(origin.offset(u, y, v), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
					}
				}
			}
			PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			pod.setPos(Vec3.atBottomCenterOf(origin.east(HALF - 1)));
			level.addFreshEntity(pod);
			MockPlayer pilot = MockPlayers.join(helper, "retained-pilot");
			if (lights) {
				// The pilot founds the charter that owns the pod, or the pod would not take them aboard.
				CharterId charter = charter(helper, pilot.player().getUUID());
				PodComponents.register(pod, charter);
				PodComponents.install(pod, ComponentItems.mint(level.getServer(), ComponentTrack.LIGHTS, 1, charter));
			}
			pilot.teleportTo(level, pod.position(), 0f, 0f);
			if (!pilot.player().startRiding(pod)) {
				throw failure(helper, "the pilot could not mount the pod");
			}
			LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			figure.setPos(Vec3.atBottomCenterOf(origin.west(HALF - 1)));
			figure.setHeading(Direction.NORTH);
			level.addFreshEntity(figure);
			return new Arena(level, origin, pod, pilot, figure);
		}

		/** Whether the figure's box touches the hull, give or take a hand's breadth. */
		boolean touchesHull() {
			return pod.getBoundingBox().inflate(0.25).intersects(figure.getBoundingBox());
		}

		void clear() {
			pilot.leave();
			figure.discard();
			pod.discard();
		}
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + 60 + 2 * 400 + FADE_BUDGET_TICKS)
	public void aDarkPodDrawsAFigureThatClingsAndDrainsItAndThenLetsGo(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(0);
		Arena[] made = {null};
		int[] phase = {0};
		long[] since = {0};
		float[] fuelAtGrab = {0};
		float[] hullAtGrab = {0};
		helper.onEachTick(() -> {
			Arena arena = made[0];
			if (arena == null) {
				return;
			}
			long waited = helper.getTick() - since[0];
			switch (phase[0]) {
				case 0 -> {
					if (arena.figure.isClinging()) {
						LOGGER.info("[retained] the figure walked 10 blocks to a dark pod and grabbed it in {} ticks", waited);
						fuelAtGrab[0] = arena.pod.fuel();
						hullAtGrab[0] = arena.pod.hull();
						phase[0] = 1;
						since[0] = helper.getTick();
					} else if (waited > APPROACH_BUDGET_TICKS) {
						throw failure(helper, "the figure had not grabbed the dark pod %d ticks after it was placed 10 blocks away (it stands at %s, the pod at %s)",
								waited, arena.figure.position(), arena.pod.position());
					}
				}
				case 1 -> {
					if (arena.figure.isClinging()) {
						if (waited > 10 && !arena.touchesHull()) {
							throw failure(helper, "the clinging figure at %s is off the hull %s", arena.figure.position(), arena.pod.getBoundingBox());
						}
						if (waited > FILL_BUDGET_TICKS) {
							throw failure(helper, "the figure was still clinging %d ticks after the grab", waited);
						}
						return;
					}
					expectLetGoFull(helper, arena, fuelAtGrab[0], hullAtGrab[0], waited);
					phase[0] = 2;
					since[0] = helper.getTick();
				}
				default -> {
					if (arena.figure.isRemoved()) {
						arena.clear();
						helper.succeed();
					} else if (waited > FADE_BUDGET_TICKS) {
						arena.clear();
						throw failure(helper, "the figure had not faded out %d ticks after it let go", waited);
					}
				}
			}
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			made[0] = Arena.build(helper, level, origin, false);
			since[0] = helper.getTick();
			if (!LamplessFigure.draws(made[0].pod)) {
				throw failure(helper, "a powered pod with a pilot, no lights and a full tank does not draw a figure");
			}
		});
	}

	/** The figure took its fill and no more, the tank lost at least that, and neither the hull nor the pilot were hurt. */
	private static void expectLetGoFull(GameTestHelper helper, Arena arena, float fuelAtGrab, float hullAtGrab, long clungTicks) {
		float taken = arena.figure.taken();
		float fillPercent = CLING.fillLitres() / 10f * 100f;
		float lost = fuelAtGrab - arena.pod.fuel();
		boolean grabbed = arena.pilot.actionBarMessages().stream().anyMatch(message -> message.getContents() instanceof TranslatableContents contents && contents.getKey().equals(GRAB_KEY));
		if (taken < CLING.fillLitres() - 0.05f || taken > CLING.fillLitres() + 0.05f) {
			throw failure(helper, "the figure took %.3f litres, not its fill of %.2f", taken, CLING.fillLitres());
		}
		// A stock tank is 10 litres, so the fill is 25 points of the tank; the pod's own idle burn (0.063 litres a second) is on top.
		if (lost < fillPercent - 0.5f || lost > fillPercent + 8f) {
			throw failure(helper, "the tank lost %.2f points over %d ticks of the grip; the fill is %.1f points and the idle burn a few more", lost, clungTicks, fillPercent);
		}
		if (arena.pod.hull() != hullAtGrab) {
			throw failure(helper, "the pod's hull went from %s to %s while the figure held it", hullAtGrab, arena.pod.hull());
		}
		if (arena.pilot.player().getHealth() != arena.pilot.player().getMaxHealth()) {
			throw failure(helper, "the pilot's health went to %s while the figure held the pod", arena.pilot.player().getHealth());
		}
		if (!grabbed) {
			throw failure(helper, "the pilot was told nothing when the figure grabbed the pod: %s", arena.pilot.actionBarMessages());
		}
		LOGGER.info("[retained] a grip left alone: {} ticks, {} litres taken, the tank lost {} points of 100", clungTicks, taken, lost);
		if (arena.pod.fuel() <= TUNING.cling().floorPercent()) {
			throw failure(helper, "the tank went to %s, under the floor of %s", arena.pod.fuel(), TUNING.cling().floorPercent());
		}
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
	public void aLitPodIsIgnored(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(1);
		Arena[] made = {null};
		int[] ticks = {0};
		helper.onEachTick(() -> {
			Arena arena = made[0];
			if (arena == null) {
				return;
			}
			if (arena.figure.isClinging()) {
				throw failure(helper, "a figure clung to a pod with its lights on, after %d ticks", ticks[0]);
			}
			if (LamplessFigure.draws(arena.pod)) {
				throw failure(helper, "a pod with a lights part and power draws a figure");
			}
			if (++ticks[0] < 300) {
				return;
			}
			if (arena.pod.fuel() < 90f) {
				throw failure(helper, "the lit pod lost fuel to something: %s of 100 after 300 ticks", arena.pod.fuel());
			}
			arena.clear();
			helper.succeed();
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
	}

	@GameTest
	public void onlyAPoweredPodWithAPilotAndFuelAboveTheFloorAndNoLightsDrawsAFigure(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(Vec3.atBottomCenterOf(slot(2).above(40)));
		level.addFreshEntity(pod);
		MockPlayer pilot = MockPlayers.join(helper, "retained-draw");
		try {
			expect(helper, !LamplessFigure.draws(pod), "an empty pod draws a figure");
			pilot.teleportTo(level, pod.position(), 0f, 0f);
			expect(helper, pilot.player().startRiding(pod), "the pilot could not mount the pod");
			expect(helper, LamplessFigure.draws(pod), "a dark pod with a pilot does not draw a figure");
			pod.setFuel(TUNING.cling().floorPercent());
			expect(helper, !LamplessFigure.draws(pod), "a pod with its tank at the floor draws a figure that has nothing to take");
			pod.setFuel(TUNING.cling().floorPercent() + 1f);
			expect(helper, LamplessFigure.draws(pod), "a pod just over the floor draws no figure");
			pod.setStranded(true);
			expect(helper, !LamplessFigure.draws(pod), "an unpowered pod draws a figure");
			helper.succeed();
		} finally {
			pilot.leave();
			pod.discard();
		}
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + 60 + SHAKE_BUDGET_TICKS + 100)
	public void shakingTheFigureOffWithTheRotorFreesThePodBeforeItIsFull(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(3);
		Arena[] made = {null};
		long[] grabbedAt = {-1};
		float[] takenAtRelease = {-1};
		float[] fuelAtRelease = {0};
		helper.onEachTick(() -> {
			Arena arena = made[0];
			if (arena == null) {
				return;
			}
			if (grabbedAt[0] < 0) {
				if (arena.figure.isClinging()) {
					grabbedAt[0] = helper.getTick();
					LOGGER.info("[retained] the figure grabbed the pod {} ticks after it was placed 10 blocks away", helper.getTick());
					arena.pilot.setInput(JUMP);
				} else if (helper.getTick() > APPROACH_BUDGET_TICKS + 20) {
					throw failure(helper, "the figure had not grabbed the dark pod");
				}
				return;
			}
			long held = helper.getTick() - grabbedAt[0];
			if (takenAtRelease[0] < 0) {
				if (!arena.figure.isClinging()) {
					takenAtRelease[0] = arena.figure.taken();
					fuelAtRelease[0] = arena.pod.fuel();
					LOGGER.info("[retained] a grip shaken off: {} ticks of rotor, {} litres taken", held, takenAtRelease[0]);
					if (takenAtRelease[0] >= CLING.fillLitres() - 0.05f) {
						throw failure(helper, "the figure took its full %.2f litres: the rotor did not free the pod", takenAtRelease[0]);
					}
					if (held < CLING.shakeTicks() - 2) {
						throw failure(helper, "the grip broke after %d ticks of thrust, under the %d the tuning asks", held, CLING.shakeTicks());
					}
				} else if (held > SHAKE_BUDGET_TICKS) {
					throw failure(helper, "the figure still clung %d ticks after the pilot held the rotor (the pod is at %s)", held, arena.pod.position());
				}
				return;
			}
			// The pod is free: nothing takes more, whatever the pilot does.
			if (held > CLING.shakeTicks() + 40) {
				float burnedLitres = (fuelAtRelease[0] - arena.pod.fuel()) / 100f * 10f;
				if (burnedLitres > 0.5f) {
					throw failure(helper, "the tank lost %.2f litres in 40 ticks after the figure let go: the pod's own burn is under half of that", burnedLitres);
				}
				arena.clear();
				helper.succeed();
			}
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + 60 + 60 + FADE_BUDGET_TICKS)
	public void aLightFreesAClingingFigureAtOnceAndItFades(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(4);
		Arena[] made = {null};
		long[] litAt = {-1};
		helper.onEachTick(() -> {
			Arena arena = made[0];
			if (arena == null) {
				return;
			}
			if (litAt[0] < 0) {
				if (arena.figure.isClinging() && helper.getTick() > 0) {
					litAt[0] = helper.getTick();
					CharterId charter = charter(helper, arena.pilot.player().getUUID());
					PodComponents.register(arena.pod, charter);
					PodComponents.install(arena.pod, ComponentItems.mint(level.getServer(), ComponentTrack.LIGHTS, 1, charter));
				} else if (helper.getTick() > APPROACH_BUDGET_TICKS + 20) {
					throw failure(helper, "the figure had not grabbed the dark pod");
				}
				return;
			}
			long since = helper.getTick() - litAt[0];
			if (arena.figure.isClinging() && since > 5) {
				throw failure(helper, "the figure still clung %d ticks after the pod's lights part was fitted", since);
			}
			if (!arena.figure.isClinging() && arena.figure.fadeFraction() == 0f && !arena.figure.isRemoved()) {
				throw failure(helper, "the figure let go and did not start to fade");
			}
			if (arena.figure.isRemoved()) {
				if (arena.figure.taken() >= CLING.fillLitres()) {
					throw failure(helper, "the figure had taken its full fill before the light came");
				}
				arena.clear();
				helper.succeed();
			} else if (since > FADE_BUDGET_TICKS + 20) {
				throw failure(helper, "the figure was still there %d ticks after the lights part was fitted", since);
			}
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
	}

	/** The grip test above holds that a clinging figure hurts neither the pilot nor the hull; a figure that cannot attack is why. */
	@GameTest
	public void aFigureHasNoAttackDamage(GameTestHelper helper) {
		LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
		expect(helper, figure.getAttribute(Attributes.ATTACK_DAMAGE) == null, "the figure has an attack damage attribute");
		helper.succeed();
	}

	/**
	 * In a dark room of layer 1 and of layer 2 a figure may appear; beside a light, over air, and in a level that holds the cap it may not;
	 * and in the overworld (where the colony and the hangar stand), the Nether and the End it may not appear at all. The light takes a few
	 * ticks to spread, so the test waits for it, in three phases: dark, lit, dark again.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
	public void aFigureMayAppearInTheDarkOfLayersOneAndTwoAndNowhereElse(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		List<ServerLevel> layers = List.of(server.getLevel(LayerChain.dimension(1)), server.getLevel(LayerChain.dimension(2)));
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		BlockPos column = new BlockPos(7208, 128, 7208);
		BlockPos overworldPos = slot(5).above(2);
		int[] ready = {0};
		int[] phase = {0};
		int[] since = {0};
		helper.onEachTick(() -> {
			if (ready[0] < 3) {
				return;
			}
			since[0]++;
			switch (phase[0]) {
				case 0 -> {
					LamplessFigures.clearFadeDelays();
					for (ServerLevel layer : layers) {
						expect(helper, LamplessFigures.canAppearAt(layer, column), "a figure may not appear in the dark room of " + layer.dimension().identifier());
						layer.setBlock(column.above(), light(15), 3);
					}
					expectNowhereElse(helper, server, overworld, overworldPos, column);
					phase[0] = 1;
					since[0] = 0;
				}
				case 1 -> {
					if (layers.stream().allMatch(layer -> layer.getBrightness(LightLayer.BLOCK, column) >= TUNING.fadeBlockLight())) {
						for (ServerLevel layer : layers) {
							expect(helper, !LamplessFigures.canAppearAt(layer, column), "a figure may appear beside a light of level 15 in " + layer.dimension().identifier());
							// room-carver: removes the light block this test placed itself in its own sealed room
							layer.setBlock(column.above(), Blocks.AIR.defaultBlockState(), 3);
						}
						phase[0] = 2;
						since[0] = 0;
					} else {
						expect(helper, since[0] < LIGHT_BUDGET_TICKS, "the light of level 15 did not reach the room in " + LIGHT_BUDGET_TICKS + " ticks");
					}
				}
				default -> {
					if (layers.stream().allMatch(layer -> layer.getBrightness(LightLayer.BLOCK, column) < TUNING.fadeBlockLight())) {
						for (ServerLevel layer : layers) {
							expect(helper, LamplessFigures.canAppearAt(layer, column), "the room is dark again and a figure may not appear in " + layer.dimension().identifier());
							expectNotOverAir(helper, layer, column);
						}
						expectFullLevelRefuses(helper, layers.get(1), column);
						helper.succeed();
					} else {
						expect(helper, since[0] < LIGHT_BUDGET_TICKS, "the light did not go out of the room in " + LIGHT_BUDGET_TICKS + " ticks");
					}
				}
			}
		});
		for (ServerLevel layer : layers) {
			FarChunks.awaitEntityTicking(helper, layer, column, () -> {
				buildDarkRoom(layer, column);
				ready[0]++;
			});
		}
		FarChunks.awaitEntityTicking(helper, overworld, overworldPos, () -> {
			buildDarkRoom(overworld, overworldPos);
			ready[0]++;
		});
	}

	private static void expectNowhereElse(GameTestHelper helper, MinecraftServer server, ServerLevel overworld, BlockPos overworldPos, BlockPos column) {
		expect(helper, !LamplessFigures.canAppearAt(overworld, overworldPos), "a figure may appear in a dark room of the surface");
		for (ColonyAnchor anchor : List.of(ColonyAnchor.HANGAR, ColonyAnchor.CONDUIT)) {
			BlockPos at = Colony.anchor(server, anchor).orElseThrow(() -> failure(helper, "the colony was not built"));
			expect(helper, !LamplessFigures.canAppearAt(overworld, at.above()), "a figure may appear at the colony's " + anchor);
		}
		for (Level other : List.of(server.getLevel(Level.NETHER), server.getLevel(Level.END))) {
			expect(helper, !LamplessFigures.canAppearAt((ServerLevel) other, column), "a figure may appear in " + other.dimension().identifier());
		}
	}

	private static void expectNotOverAir(GameTestHelper helper, ServerLevel layer, BlockPos column) {
		// room-carver: the floor of this test's own sealed room, put back below
		layer.setBlock(column.below(), Blocks.AIR.defaultBlockState(), 3);
		boolean over = LamplessFigures.canAppearAt(layer, column);
		layer.setBlock(column.below(), Blocks.STONE.defaultBlockState(), 3);
		expect(helper, !over, "a figure may appear over air in " + layer.dimension().identifier() + ", with nothing to stand on");
	}

	private static void expectFullLevelRefuses(GameTestHelper helper, ServerLevel level, BlockPos column) {
		List<LamplessFigure> filling = new ArrayList<>();
		for (int i = 0; i < TUNING.maxPerLevel(); i++) {
			LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			figure.setPos(Vec3.atBottomCenterOf(column.east(2)));
			figure.setNoAi(true);
			level.addFreshEntity(figure);
			filling.add(figure);
		}
		boolean full = LamplessFigures.canAppearAt(level, column);
		filling.forEach(LamplessFigure::discard);
		expect(helper, !full, "a figure may appear in a level that holds " + TUNING.maxPerLevel());
	}

	/**
	 * What the game's spawner asks of a figure it tries to place: about {@code chancePerTry} of the tries pass in a dark room, none beside a
	 * light, and a figure made by a command is never refused.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
	public void theGamesOwnSpawnerRollsRareAndOnlyWhereTheRulesAllow(GameTestHelper helper) {
		ServerLevel two = helper.getLevel().getServer().getLevel(LayerChain.dimension(2));
		BlockPos column = new BlockPos(7272, 128, 7208);
		LamplessFigure[] figure = {null};
		int[] phase = {0};
		int[] since = {0};
		int tries = 4000;
		helper.onEachTick(() -> {
			if (figure[0] == null) {
				return;
			}
			since[0]++;
			if (phase[0] == 0) {
				LamplessFigures.clearFadeDelays();
				int rolled = 0;
				for (int i = 0; i < tries; i++) {
					rolled += figure[0].checkSpawnRules(two, EntitySpawnReason.NATURAL) ? 1 : 0;
				}
				double expected = tries * TUNING.natural().chancePerTry();
				expect(helper, rolled > expected * 0.4 && rolled < expected * 1.8,
						"%d of %d tries passed; about %.0f expected at %s".formatted(rolled, tries, expected, TUNING.natural().chancePerTry()));
				expect(helper, figure[0].checkSpawnRules(two, EntitySpawnReason.COMMAND), "a figure made by a command was refused");
				two.setBlock(column.above(), light(15), 3);
				phase[0] = 1;
				since[0] = 0;
			} else if (two.getBrightness(LightLayer.BLOCK, column) >= TUNING.fadeBlockLight()) {
				for (int i = 0; i < tries; i++) {
					expect(helper, !figure[0].checkSpawnRules(two, EntitySpawnReason.NATURAL), "a natural spawn was allowed beside a light of level 15");
				}
				// room-carver: removes the light block this test placed itself in its own sealed room
				two.setBlock(column.above(), Blocks.AIR.defaultBlockState(), 3);
				helper.succeed();
			} else {
				expect(helper, since[0] < LIGHT_BUDGET_TICKS, "the light of level 15 did not reach the room in " + LIGHT_BUDGET_TICKS + " ticks");
			}
		});
		FarChunks.awaitEntityTicking(helper, two, column, () -> {
			buildDarkRoom(two, column);
			figure[0] = CreatureRegistry.LAMPLESS_FIGURE.create(two, EntitySpawnReason.NATURAL);
			figure[0].setPos(Vec3.atBottomCenterOf(column));
		});
	}

	/** The biomes of layers 1 and 2 list the figure as a rare monster, and no other biome lists it. */
	@GameTest
	public void onlyTheBiomesOfLayersOneAndTwoListTheFigure(GameTestHelper helper) {
		Set<String> inLayers = new HashSet<>();
		for (int layer : new int[] {1, 2}) {
			JsonArray biomes = json("/data/deepcharter/dimension/layer_" + layer + ".json").getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes");
			for (JsonElement biome : biomes) {
				inLayers.add(biome.getAsString().substring("deepcharter:".length()));
			}
		}
		expect(helper, inLayers.size() == 6, "layers 1 and 2 have " + inLayers.size() + " biomes, not the 6 the test expects: " + inLayers);
		List<String> all = List.of("topsoil_claims", "stone_benches", "deep_claim", "upper_levels", "shift_change", "prospectors_run",
				"regolith_plains", "basalt_field", "mesa_country");
		for (String biome : all) {
			JsonObject spawns = json("/data/deepcharter/worldgen/biome/" + biome + ".json").getAsJsonObject("attributes")
					.getAsJsonObject("minecraft:gameplay/natural_mob_spawns").getAsJsonObject("argument").getAsJsonObject("spawns_by_category");
			boolean lists = spawns.has("monster") && spawns.getAsJsonArray("monster").asList().stream()
					.anyMatch(entry -> entry.getAsJsonObject().get("type").getAsString().equals("deepcharter:lampless_figure"));
			expect(helper, lists == inLayers.contains(biome), "%s %s the figure".formatted(biome, lists ? "lists" : "does not list"));
			if (lists) {
				JsonObject entry = spawns.getAsJsonArray("monster").get(0).getAsJsonObject();
				expect(helper, entry.get("weight").getAsInt() == 1 && entry.get("count").getAsInt() == 1 && spawns.getAsJsonArray("monster").size() == 1,
						biome + " lists the figure as more than one at a time, or with weight over 1, or beside another monster");
			}
		}
		helper.succeed();
	}

	private static JsonObject json(String resource) {
		InputStream stream = RetainedTest.class.getResourceAsStream(resource);
		if (stream == null) {
			throw new IllegalStateException("no resource " + resource);
		}
		try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (IOException e) {
			throw new UncheckedIOException(resource, e);
		}
	}

	/** A sealed room of air, 4 high with a stone floor, whose floor-level cell at {@code feet} is where a figure would stand. */
	private static void buildDarkRoom(ServerLevel level, BlockPos feet) {
		if (level.dimension().equals(Level.OVERWORLD)) {
			for (int u = -3; u <= 3; u++) {
				for (int v = -3; v <= 3; v++) {
					for (int y = -1; y <= 5; y++) {
						boolean shell = y == -1 || y == 5 || Math.abs(u) == 3 || Math.abs(v) == 3;
						// room-carver: a closed room of its own stone shell in the overworld test structure, not layer rock
						level.setBlock(feet.offset(u, y, v), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
					}
				}
			}
			return;
		}
		RoomCarver.carve(level, feet.offset(-3, -1, -3), feet.offset(3, -1, 3), Blocks.STONE.defaultBlockState());
		RoomCarver.carve(level, feet.offset(-2, 0, -2), feet.offset(2, 4, 2), Blocks.AIR.defaultBlockState());
	}

	private static BlockPos slot(int index) {
		return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);
	}

	private static BlockState light(int level) {
		return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
	}

	private static CharterId charter(GameTestHelper helper, UUID founder) {
		MinecraftServer server = helper.getLevel().getServer();
		Optional<?> refusal = Charters.found(server, founder, "Retained " + CHARTERS.incrementAndGet());
		if (refusal.isPresent()) {
			throw failure(helper, "a charter step was refused: %s", refusal.get());
		}
		return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
	}

	private static void expect(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw failure(helper, "%s", message);
		}
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
