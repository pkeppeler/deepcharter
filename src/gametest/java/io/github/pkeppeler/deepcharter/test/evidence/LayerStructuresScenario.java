package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Locale;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;

/**
 * Evidence scenario "m2-layer-structures" for #79: stills of the four Notes in the colony, then a fly-through of every
 * structure kind, a site of each: the three shafts and their candle niches, the gallery, the punch clock hall, the rail
 * line, and the wreck site that is PROSPECTOR-0002's. The player has night vision, because the layers are dark by design.
 * The paths are set from each site, so they follow the structure wherever the seed put it.
 */
public class LayerStructuresScenario extends EvidenceScenario {
	/** The spacing cell of the layer sites that are shown; the wreck site shown is the Prospector's. */
	private static final int CELL = 3;
	private static final int SETTLE_TICKS = 80;
	private static final int TICKS_PER_FRAME = 3;
	private static final int SHAFT_FRAMES = 3;
	private static final int HALL_FRAMES = 5;
	private static final int RAIL_FRAMES = 7;
	private static final int ORBIT_FRAMES = 6;
	private static final double ORBIT_RADIUS = 4;
	/** How far from its Note the camera hangs over a colony building, which has no roof. */
	private static final Vec3 OVER_NOTE = new Vec3(0, 3, -0.6);
	private static final int COLONY_NOTE_REACH = 6;

	@Override
	protected String name() {
		return "m2-layer-structures";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				// The Handbook in the hand would cover the view.
				player.getInventory().clearContent();
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
			});
			colonyNotes(context, singleplayer);
			for (StructureKind kind : StructureKind.values()) {
				StructureSite site = prepare(singleplayer, kind);
				tour(context, singleplayer, site);
			}
		}
	}

	/** N01 to N04, each seen from over its building: the Personnel office, the pay office, the chapel and the Continuity Office. */
	private void colonyNotes(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		ColonyAnchor[] buildings = {ColonyAnchor.PERSONNEL_OFFICE, ColonyAnchor.PAY_OFFICE, ColonyAnchor.CHAPEL_CANDLE, ColonyAnchor.CONTINUITY_OFFICE};
		for (int number = 1; number <= buildings.length; number++) {
			ColonyAnchor building = buildings[number - 1];
			int wanted = number;
			BlockPos note = singleplayer.getServer().computeOnServer(server -> {
				ColonySite.Placed colony = Colony.placed(server).orElseThrow(() -> new AssertionError("the colony was not built when the world started"));
				ServerLevel overworld = server.overworld();
				BlockPos at = colony.anchors().get(building);
				for (BlockPos pos : BlockPos.betweenClosed(at.offset(-COLONY_NOTE_REACH, -1, -COLONY_NOTE_REACH), at.offset(COLONY_NOTE_REACH, 3, COLONY_NOTE_REACH))) {
					overworld.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL);
					var state = overworld.getBlockState(pos);
					if (state.is(HandbookRegistry.NOTE) && state.getValue(NoteBlock.NOTE) == wanted) {
						return pos.immutable();
					}
				}
				throw new AssertionError("N0" + wanted + " is not within " + COLONY_NOTE_REACH + " blocks of the " + building);
			});
			Vec3 target = Vec3.atCenterOf(note);
			fly(singleplayer, Level.OVERWORLD, target.add(OVER_NOTE), target);
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "n0" + number + "-colony-" + building.getSerializedName().replace('_', '-'));
		}
	}

	/** Finds the kind's site, and generates its chunks, on the server. */
	private static StructureSite prepare(TestSingleplayerContext singleplayer, StructureKind kind) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(kind.layer()));
			StructureSite site = kind == StructureKind.WRECK
					? LayerStructures.prospector(server).orElseThrow(() -> new AssertionError("the colony was not built when the world started"))
					: StructureSite.in(level.getSeed(), kind, level.getMinY(), level.getHeight(), CELL, CELL,
							Colony.anchor(server, ColonyAnchor.CONDUIT).orElseThrow(() -> new AssertionError("the colony was not built when the world started")));
			BoundingBox box = site.bounds();
			for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
				for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
					level.getChunk(chunkX, chunkZ, ChunkStatus.FULL);
				}
			}
			return site;
		});
	}

	private void tour(ClientGameTestContext context, TestSingleplayerContext singleplayer, StructureSite site) {
		ResourceKey<Level> layer = LayerChain.dimension(site.kind().layer());
		String shot = site.kind().name().toLowerCase(Locale.ROOT).replace('_', '-');
		switch (site.kind()) {
			case TOPSOIL_SHAFT, BENCHES_SHAFT, DEEP_SHAFT -> {
				// Down the shaft to its candle niche, which holds the Note.
				int niche = site.height() / 2;
				Vec3 look = at(site, 2, niche + 0.2, 0);
				for (int i = 0; i < SHAFT_FRAMES; i++) {
					fly(singleplayer, layer, at(site, 0, niche + 10 - 9.0 * i / (SHAFT_FRAMES - 1), 0), look);
					settle(context, i);
					frame(context);
				}
				screenshot(context, shot + "-candle-niche");
			}
			case GALLERY -> {
				fly(singleplayer, layer, at(site, -7, 1, 0), at(site, -13, 1.6, 0));
				settle(context, 0);
				screenshot(context, shot + "-quota-board");
				for (int i = 0; i < HALL_FRAMES; i++) {
					double u = -8 + 16.0 * i / (HALL_FRAMES - 1);
					fly(singleplayer, layer, at(site, u, 1, 0), at(site, u + 6, 1.6, 0));
					settle(context, i == 0 ? 0 : 1);
					frame(context);
				}
				screenshot(context, shot + "-collapse");
			}
			case PUNCH_CLOCK -> {
				fly(singleplayer, layer, at(site, -2, 1, 0), at(site, -7, 1.6, 0));
				settle(context, 0);
				screenshot(context, shot + "-punch-clock");
				for (int i = 0; i < HALL_FRAMES; i++) {
					double u = -4 + 7.0 * i / (HALL_FRAMES - 1);
					fly(singleplayer, layer, at(site, u, 1, 0), at(site, 8, 1.6, 0));
					settle(context, i == 0 ? 0 : 1);
					frame(context);
				}
				screenshot(context, shot + "-card-rack");
			}
			case RAILS -> {
				for (int i = 0; i < RAIL_FRAMES; i++) {
					double u = -30 + 58.0 * i / (RAIL_FRAMES - 1);
					fly(singleplayer, layer, at(site, u, 1, 0), at(site, u + 8, 1.2, 0));
					settle(context, i == 0 ? 0 : 1);
					if (i == 0) {
						screenshot(context, shot + "-rail-line");
					}
					frame(context);
				}
			}
			case WRECK -> {
				// The site nearest the Conduit: PROSPECTOR-0002's, with the middle clear for the pod.
				Vec3 centre = at(site, 0, 0.5, 0);
				for (int i = 0; i < ORBIT_FRAMES; i++) {
					double angle = 2 * Math.PI * i / ORBIT_FRAMES;
					fly(singleplayer, layer, at(site, ORBIT_RADIUS * Math.cos(angle), 1.5, ORBIT_RADIUS * Math.sin(angle)), centre);
					settle(context, i == 0 ? 0 : 1);
					if (i == 0) {
						screenshot(context, shot + "-site-of-prospector-0002");
					}
					frame(context);
				}
			}
		}
	}

	/** Waits longer for the first frame of a path, when the chunks and the dimension have to arrive. */
	private static void settle(ClientGameTestContext context, int step) {
		context.waitTicks(step == 0 ? SETTLE_TICKS : TICKS_PER_FRAME);
	}

	/** The world position of a point in the structure's own axes, {@code y} up from the floor of its hollow. */
	private static Vec3 at(StructureSite site, double u, double y, double v) {
		BlockPos origin = site.origin();
		return new Vec3(origin.getX() + 0.5 + (site.alongZ() ? v : u), origin.getY() + y, origin.getZ() + 0.5 + (site.alongZ() ? u : v));
	}

	/** Puts the player at {@code at} in {@code dimension}, looking at {@code target}. */
	private static void fly(TestSingleplayerContext singleplayer, ResourceKey<Level> dimension, Vec3 at, Vec3 target) {
		Vec3 d = target.subtract(at);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			// Crossing into a layer ends the flight, so the player would fall down a shaft: gravity is off instead.
			player.setNoGravity(true);
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.teleportTo(server.getLevel(dimension), at.x, at.y, at.z, Set.of(), yaw, pitch, true);
		});
	}
}
