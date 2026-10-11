package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Set;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;

/**
 * Evidence scenario "deepest-point": the charter's deepest point rises as the player goes down, with nothing written by hand. The player
 * founds a charter, reads it on the surface and opens the colony's ore processor (the order list has no Morale Initiative), drops into
 * layer 1 and reads the charter, drops to the floor of layer 2 (where layer 3 begins) and reads it again, then returns to the processor,
 * which now lists the Morale Initiative too.
 */
public class DeepestPointScenario extends EvidenceScenario {
	private static final int TICKS_AFTER_COMMAND = 12;
	private static final int TICKS_PER_FRAME = 3;
	/** Longer than the poll of the deepest point (20 ticks), so the next read sees the record. */
	private static final int POLL_TICKS = 30;
	private static final int ARRIVAL_TICKS = 40;
	private static final int ROOM_RADIUS = 4;
	private static final int ROOM_HEIGHT = 5;
	/** Tilted up, so the floor row of layer 2, which has no rock under it, stays out of the frame. */
	private static final float LOOK_UP = -25f;
	private static final int SETTLE_TICKS = 60;
	private static final int TYPING_FRAMES = 45;

	@Override
	protected String name() {
		return "deepest-point";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			awaitWorld(context);
			context.waitTicks(20);
			frame(context);

			ColonySite.Placed colony = server.computeOnServer(minecraftServer -> {
				ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				// The Handbook in the hand would cover the view.
				player.getInventory().clearContent();
				// The layers are dark: night vision for the evidence, so the shots show the rock around the readout.
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
				if (Charters.found(minecraftServer, player.getUUID(), "Deep Crew").isPresent()) {
					throw new AssertionError("founding should succeed");
				}
				RepairState state = RepairState.get(minecraftServer);
				for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
					type.parts().forEach(part -> state.insert(type, part));
				}
				return Colony.placed(minecraftServer).orElseThrow(() -> new AssertionError("the colony was not built when the world started"));
			});
			BlockPos processor = colony.anchors().get(ColonyAnchor.ORE_PROCESSOR);
			Vec3 viewpoint = Vec3.atBottomCenterOf(colony.center()).add(-4, 1, -4);
			stand(server, viewpoint, Vec3.atCenterOf(processor));
			context.waitTicks(SETTLE_TICKS);
			command(context, server, "deepcharter charter info");
			screenshot(context, "deepest-point-surface");
			int before = orderLines(context, processor);
			screenshot(context, "orders-before-the-dive");
			context.setScreen(() -> null);

			descend(context, server, 1, 1);
			command(context, server, "deepcharter charter info");
			screenshot(context, "deepest-point-layer-1");

			descend(context, server, 2, 0);
			command(context, server, "deepcharter charter info");
			screenshot(context, "deepest-point-top-of-layer-3");
			server.runOnServer(minecraftServer -> {
				ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
				int deepest = Charters.charterOfOrThrow(minecraftServer, player.getUUID()).orElseThrow().deepestPoint();
				if (deepest < LayerChain.topDepth(minecraftServer.registryAccess(), 3)) {
					throw new AssertionError("the floor of layer 2 should have set the deepest point to the top of layer 3, got " + deepest);
				}
				player.setNoGravity(false);
			});

			stand(server, viewpoint, Vec3.atCenterOf(processor));
			context.waitTicks(SETTLE_TICKS);
			int after = orderLines(context, processor);
			screenshot(context, "orders-after-the-dive");
			context.setScreen(() -> null);
			if (after <= before) {
				throw new AssertionError("the Morale Initiative should add lines to the order list: " + before + " before, " + after + " after");
			}
		}
	}

	/** Opens the ore processor's screen, lets its intro type out, and returns how many order lines it lists. */
	private int orderLines(ClientGameTestContext context, BlockPos processor) {
		context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
		ClientWait.screen(context, OreProcessorScreen.class);
		OreProcessorScreen screen = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
		for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
		return screen.orderLines().size();
	}

	/** Puts the player on the ground at {@code at} in the overworld, looking at {@code target}. */
	private static void stand(TestServerContext server, Vec3 at, Vec3 target) {
		Vec3 eye = at.add(0, 1.62, 0);
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		server.runOnServer(minecraftServer ->
				minecraftServer.getPlayerList().getPlayers().getFirst().teleportTo(minecraftServer.overworld(), at.x, at.y, at.z, Set.of(), yaw, pitch, true));
	}

	/**
	 * Puts the player {@code rowsAboveFloor} rows above the floor of {@code layer}, held in place in a sealed room of air cut into the
	 * layer's rock (so a still shows rock and never the void), and waits until the client sees the room's wall and the next poll saw the player.
	 */
	private void descend(ClientGameTestContext context, TestServerContext server, int layer, int rowsAboveFloor) {
		BlockPos wall = server.computeOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			ServerLevel level = minecraftServer.getLevel(LayerChain.dimension(layer));
			int y = level.getMinY() + rowsAboveFloor;
			RoomCarver.carve(level, new BlockPos(-ROOM_RADIUS, y, -ROOM_RADIUS), new BlockPos(ROOM_RADIUS, y + ROOM_HEIGHT, ROOM_RADIUS),
					Blocks.AIR.defaultBlockState());
			player.setNoGravity(true);
			player.teleportTo(level, 0.5, y, 0.5, Set.of(), 0f, LOOK_UP, true);
			return new BlockPos(0, y + 1, ROOM_RADIUS + 1);
		});
		ClientWait.until(context, "the client to see the room's wall in layer " + layer, client -> client.level != null
				&& client.level.dimension().equals(LayerChain.dimension(layer)) && !client.level.getBlockState(wall).isAir());
		context.waitTicks(ARRIVAL_TICKS);
		frame(context);
		context.waitTicks(POLL_TICKS);
	}

	/** Runs the command as the player with operator rights, and echoes it, so the answer reaches the real client's chat. */
	private void command(ClientGameTestContext context, TestServerContext server, String command) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			player.sendSystemMessage(Component.literal("> /" + command));
			try {
				minecraftServer.getCommands().getDispatcher().execute(command,
						player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER));
			} catch (CommandSyntaxException e) {
				throw new AssertionError("command failed: " + command + ": " + e.getMessage(), e);
			}
		});
		for (int i = 0; i < TICKS_AFTER_COMMAND; i += TICKS_PER_FRAME) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
