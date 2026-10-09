package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActivity;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.texture.TextureProperties;

/**
 * Evidence scenario "texture-layers" for #242: the texture system's layers in a sealed stone room with no light of its own.
 * Terminals glow in the dark when online and show only a red standby light when broken; a Company lamp lights and goes dark; the
 * ores in the wall show only once a lamp is lit; the Conduit's casing joins across a wall and round the inside of an L.
 *
 * <p>The player is a creative, flying camera with the HUD hidden. The fuel pump and the ore processor are repaired (the first two of
 * the repair order), so three terminals are online with the contract terminal and three stay dark.
 */
public class TextureLayersScenario extends EvidenceScenario {
	private static final double EYE = 1.62;
	private static final int RADIUS = 8;
	private static final int HEIGHT = 5;
	private static final int TICKS_PER_FRAME = 2;
	private static final int SCAN_FRAMES = 24;

	private ClientGameTestContext ctx;
	private TestSingleplayerContext sp;
	private BlockPos floor;

	@Override
	protected String name() {
		return "texture-layers";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		try {
			shoot(context);
		} finally {
			// The HUD is hidden for the stills; a full-suite run goes on to tests that draw on it.
			context.runOnClient(client -> {
				if (client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});
		}
	}

	private void shoot(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			sp = singleplayer;
			context.waitTicks(40);
			floor = serverGet(server -> player(server).blockPosition().below().above(40));
			serverDo(server -> {
				GameRules rules = server.getGameRules();
				rules.set(GameRules.ADVANCE_TIME, false, server);
				rules.set(GameRules.SPAWN_MOBS, false, server);
				rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
				ServerPlayer player = player(server);
				player.setGameMode(GameType.CREATIVE);
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				player.setPermanentlyInvulnerable(true);
				build(player.level());
			});
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.FIRST_PERSON);
				client.options.particles().set(ParticleStatus.MINIMAL);
				client.options.bobView().set(false);
				if (!client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});

			Vec3 terminals = centre(0, 1.5, RADIUS);
			view(centre(0, 2.2, 1), terminals, 80);
			still("terminals-in-the-dark");
			for (int i = 0; i < SCAN_FRAMES / 2; i++) {
				record(1);
			}
			lamp(new BlockPos(0, 1, 4), true);
			view(centre(0, 2.2, 1), terminals, 20);
			still("terminals-in-lamp-light");
			record(SCAN_FRAMES / 2);
			lamp(new BlockPos(0, 1, 4), false);

			view(centre(-1, 1.8, 6.5), centre(-1, 1.5, RADIUS), 20);
			still("terminal-online-close");
			record(SCAN_FRAMES);
			view(centre(3, 1.8, 6.5), centre(3, 1.5, RADIUS), 20);
			still("terminal-offline-close");

			Vec3 ores = centre(-RADIUS - 1, 2, 0);
			view(centre(-3, 2.2, 0), ores, 20);
			still("ores-in-the-dark");
			record(6);
			lamp(new BlockPos(-6, 1, 2), true);
			view(centre(-3, 2.2, 0), ores, 20);
			still("ores-in-lamp-light");
			record(12);

			Vec3 casing = centre(RADIUS + 1, 2.5, 0);
			lamp(new BlockPos(5, 1, 3), true);
			view(centre(2, 2.6, 0), casing, 20);
			still("conduit-wall-connected");
			record(12);

			lamp(new BlockPos(-6, 1, 2), false);
			lamp(new BlockPos(5, 1, 3), false);
			lamp(new BlockPos(1, 1, -3), true);
			lamp(new BlockPos(-1, 1, -3), false);
			view(centre(0, 1.6, -0.5), centre(0, 1.4, -3), 20);
			still("lamps-lit-and-dark");
			record(8);
		}
	}

	/**
	 * A sealed stone box with its floor at {@link #floor}, so no sky reaches in. The south wall holds the six terminals, facing the
	 * camera; the west wall has the seven ores set into its stone; the east wall is a 5 x 4 patch of Conduit casing with an L before it.
	 */
	private void build(ServerLevel level) {
		for (int dx = -RADIUS - 1; dx <= RADIUS + 1; dx++) {
			for (int dz = -RADIUS - 1; dz <= RADIUS + 1; dz++) {
				for (int dy = 0; dy <= HEIGHT + 1; dy++) {
					boolean shell = dy == 0 || dy == HEIGHT + 1 || Math.abs(dx) == RADIUS + 1 || Math.abs(dz) == RADIUS + 1;
					level.setBlock(floor.offset(dx, dy, dz), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
		MinecraftServer server = level.getServer();
		RepairState repairs = RepairState.get(server);
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
			for (Item part : type.parts()) {
				repairs.insert(type, part);
			}
		}
		List<TerminalType> row = List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, ContractTerminal.TYPE,
				TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION, HangarTerminal.TYPE);
		for (int i = 0; i < row.size(); i++) {
			BlockPos pos = floor.offset(-5 + 2 * i, 1, RADIUS);
			level.setBlock(pos, row.get(i).block().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH), Block.UPDATE_ALL);
			TerminalActivity.sync(level, pos);
		}
		OreType[] oresInWall = OreType.values();
		for (int i = 0; i < oresInWall.length; i++) {
			level.setBlock(floor.offset(-RADIUS - 1, 2 + (i % 2), -3 + i), OreRegistry.block(oresInWall[i]).defaultBlockState(), Block.UPDATE_ALL);
		}
		for (int dz = -2; dz <= 2; dz++) {
			for (int dy = 1; dy <= 4; dy++) {
				level.setBlock(floor.offset(RADIUS + 1, dy, dz), ColonyBlocks.CONDUIT.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		for (BlockPos l : List.of(new BlockPos(RADIUS, 1, -2), new BlockPos(RADIUS, 1, -1), new BlockPos(RADIUS, 2, -2))) {
			level.setBlock(floor.offset(l), ColonyBlocks.CONDUIT.defaultBlockState(), Block.UPDATE_ALL);
		}
		for (BlockPos lamp : List.of(new BlockPos(0, 1, 4), new BlockPos(-6, 1, 2), new BlockPos(5, 1, 3), new BlockPos(1, 1, -3), new BlockPos(-1, 1, -3))) {
			level.setBlock(floor.offset(lamp), ColonyBlocks.COMPANY_LAMP.defaultBlockState().setValue(TextureProperties.ACTIVE, false), Block.UPDATE_ALL);
		}
		player(server).teleportTo(level, floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5, Set.of(), 0, 0, true);
	}

	private void lamp(BlockPos offset, boolean lit) {
		serverDo(server -> {
			ServerLevel level = player(server).level();
			BlockPos pos = floor.offset(offset);
			level.setBlock(pos, level.getBlockState(pos).setValue(TextureProperties.ACTIVE, lit), Block.UPDATE_ALL);
		});
	}

	/** A point dx east, dy up and dz south of the bottom centre of the block above the floor's middle. */
	private Vec3 centre(double dx, double dy, double dz) {
		return Vec3.atBottomCenterOf(floor.above()).add(dx, dy - 1, dz);
	}

	private void view(Vec3 eye, Vec3 target, int wait) {
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(player.level(), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true);
		});
		ctx.waitFor(client -> client.player.distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
				&& Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.01f && Math.abs(client.player.getXRot() - pitch) < 0.01f, 400);
		ctx.waitTicks(wait);
	}

	/** Waits until the light engine has no work left, then a few ticks for the chunk meshes, and shoots the still. */
	private void still(String stillName) {
		for (int poll = 0; serverGet(server -> player(server).level().getLightEngine().hasLightWork()); poll++) {
			if (poll > 200) {
				throw new AssertionError("the light did not settle before the still " + stillName);
			}
			ctx.waitTicks(2);
		}
		ctx.waitTicks(10);
		screenshot(ctx, stillName);
	}

	private void record(int frames) {
		for (int i = 0; i < frames; i++) {
			ctx.waitTicks(TICKS_PER_FRAME);
			frame(ctx);
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private <T> T serverGet(Function<MinecraftServer, T> action) {
		return sp.getServer().computeOnServer(action::apply);
	}

	private void serverDo(Consumer<MinecraftServer> action) {
		sp.getServer().runOnServer(action::accept);
	}
}
