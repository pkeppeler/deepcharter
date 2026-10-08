package io.github.pkeppeler.deepcharter.test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.Music;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Client GameTest for #71: a listener on the sound manager confirms that each wired event plays. The pod loops, the
 * low-fuel beep, the terminal music, the typewriter, the sale, the purchase, the error and the music of every layer.
 * Event ids are literals on purpose: they are what the private audio pack is keyed by.
 */
public class SoundWiringClientTest implements FabricClientGameTest {
	private static final int WAIT_TICKS = 400;
	private static final int SETTLE_TICKS = 60;
	/** The layers that have music. A new layer fails the count check until its music is listed here. */
	private static final List<String> LAYER_MUSIC = List.of("music.layer_1", "music.layer_2");
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final int X = 500;
	private static final int Z = 500;
	private static final int FLOOR_Y = 200;

	/** Every sound the client starts, in order. */
	private static final class Heard implements SoundEventListener {
		private final List<Identifier> played = new CopyOnWriteArrayList<>();
		private final Set<SoundInstance> seen = Collections.newSetFromMap(new IdentityHashMap<>());

		@Override
		public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
			// The engine reports a looping sound again every few ticks, for the subtitles: count each instance once.
			if (seen.add(sound)) {
				played.add(sound.getIdentifier());
			}
		}

		long count(String path) {
			Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
			return played.stream().filter(id::equals).count();
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		Heard heard = new Heard();
		context.runOnClient(client -> client.getSoundManager().addListener(heard));
		try {
			podLoops(context, heard);
			terminalsAndUi(context, heard);
		} finally {
			context.runOnClient(client -> client.getSoundManager().removeListener(heard));
		}
	}

	/** A mock pilot's pod: the real client hears its engine, rotor and drill, once each while the state lasts. */
	private static void podLoops(ClientGameTestContext context, Heard heard) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			int podId = two.server().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				for (int x = X - 4; x <= X + 5; x++) {
					for (int z = Z - 4; z <= Z + 4; z++) {
						for (int y = FLOOR_Y - 8; y <= FLOOR_Y + 10; y++) {
							level.setBlock(new BlockPos(x, y, z), (y < FLOOR_Y ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
						}
					}
				}
				ServerPlayer real = server.getPlayerList().getPlayers().stream()
						.filter(player -> player != two.mock().player()).findFirst().orElseThrow();
				real.teleportTo(level, X + 3.5, FLOOR_Y, Z - 3.5, Set.of(), 0, 0, true);
				Vec3 at = new Vec3(X, FLOOR_Y, Z - 1);
				two.mock().teleportTo(level, at, 0, 0);
				PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod.setPos(at);
				level.addFreshEntity(pod);
				if (!two.mock().player().startRiding(pod)) {
					throw new AssertionError("the mock pilot could not mount the pod");
				}
				return pod.getId();
			});
			context.waitFor(client -> client.level.getEntity(podId) != null, WAIT_TICKS);

			// The client loads the pod a few times while the teleported player's chunks settle; count from the settled pod.
			context.waitFor(client -> client.level.getEntity(podId) != null && client.level.getEntity(podId).tickCount > SETTLE_TICKS && heard.count("pod.engine_idle") > 0, WAIT_TICKS);
			long idleLoops = heard.count("pod.engine_idle");
			context.waitTicks(SETTLE_TICKS);
			check(heard.count("pod.engine_idle") == idleLoops, "a steady idle engine keeps one loop, not a new one every tick, heard " + heard.played);
			check(heard.count("pod.rotor") == 0 && heard.count("pod.engine_drive") == 0, "a pod at rest has no rotor or drive sound");

			two.server().runOnServer(server -> two.mock().setInput(JUMP));
			await(context, heard, client -> heard.count("pod.rotor") > 0 && heard.count("pod.engine_drive") > 0);
			two.server().runOnServer(server -> two.mock().releaseInput());
			await(context, heard, client -> heard.count("pod.engine_idle") > idleLoops);

			two.server().runOnServer(server -> two.mock().setInput(SPRINT));
			await(context, heard, client -> heard.count("pod.engine_drill_down") > 0);
			two.server().runOnServer(server -> two.mock().releaseInput());

			// Back on the floor, wall in front: pushing into it drills sideways.
			two.server().runOnServer(server -> {
				ServerLevel level = server.overworld();
				PodEntity pod = (PodEntity) level.getEntity(podId);
				pod.teleportTo(X, FLOOR_Y, Z - 1);
				for (int x = X - 2; x <= X + 2; x++) {
					for (int y = FLOOR_Y; y <= FLOOR_Y + 3; y++) {
						level.setBlock(new BlockPos(x, y, Z + 2), Blocks.STONE.defaultBlockState(), 3);
					}
				}
				two.mock().setInput(FORWARD);
			});
			await(context, heard, client -> heard.count("pod.engine_drill_side") > 0);
			two.server().runOnServer(server -> two.mock().releaseInput());
		}
	}

	private static void terminalsAndUi(ClientGameTestContext context, Heard heard) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(SoundWiringClientTest::setUp);

			// Riding a nearly empty pod beeps.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				if (!player.startRiding(server.overworld().getEntity(scene.podId()))) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			await(context, heard, client -> heard.count("fuel.low") > 0);
			singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().stopRiding());

			// A terminal: music while it is open, letters while it types, and a sound for a sale, an error and a purchase.
			open(context, scene.processor());
			await(context, heard, client -> heard.count("music.terminal") == 1 && heard.count("ui.typewriter") > 0);
			context.clickScreenButton("SELL ALL CARRIED ORE");
			await(context, heard, client -> heard.count("ui.sale") > 0);
			check(heard.count("ui.error") == 0, "a sale that works is not an error");
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalActionPayload(scene.processor(), OreProcessor.SELL_INVENTORY, new CompoundTag())));
			await(context, heard, client -> heard.count("ui.error") > 0);
			check(heard.count("ui.sale") == 1, "a refused sale makes no sale sound");

			// Closing the terminal ends its music: opening another starts it again.
			context.setScreen(() -> null);
			context.waitTicks(SETTLE_TICKS);
			open(context, scene.pump());
			await(context, heard, client -> heard.count("music.terminal") == 2);
			context.waitFor(client -> buy(client), WAIT_TICKS);
			context.clickScreenButton("BUY 1 L");
			await(context, heard, client -> heard.count("ui.purchase") > 0);
			context.setScreen(() -> null);

			// Each layer's dimension carries its music, and the client plays what the dimension says.
			int layers = singleplayer.getServer().computeOnServer(server -> LayerChain.count(server.registryAccess()));
			check(layers == LAYER_MUSIC.size(), "LAYER_MUSIC lists " + LAYER_MUSIC.size() + " layers, the world has " + layers);
			for (int layer = 1; layer <= layers; layer++) {
				int target = layer;
				singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
						.teleportTo(server.getLevel(LayerChain.dimension(target)), 0.5, 100, 0.5, Set.of(), 0, 0, true));
				context.waitFor(client -> client.level != null && client.level.dimension().equals(LayerChain.dimension(target)), WAIT_TICKS);
				String expected = LAYER_MUSIC.get(layer - 1);
				Identifier music = context.computeOnClient(client -> {
					Music playing = client.level.environmentAttributes().getValue(EnvironmentAttributes.BACKGROUND_MUSIC, client.player.position())
							.defaultMusic().orElseThrow(() -> new AssertionError("layer " + target + " has no background music"));
					client.getMusicManager().startPlaying(playing);
					return playing.sound().value().location();
				});
				check(music.equals(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, expected)), "layer " + layer + " plays " + expected + ", got " + music);
				await(context, heard, client -> heard.count(expected) > 0);
				context.runOnClient(client -> client.getMusicManager().stopPlaying());
			}
		}
	}

	private record Scene(BlockPos processor, BlockPos pump, int podId) {
	}

	/** The player founds a charter with $20, carries ore, and has a repaired processor and pump and a nearly empty pod. */
	private static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		if (Charters.found(server, player.getUUID(), "Sound Test Charter").isPresent()) {
			throw new AssertionError("founding should succeed");
		}
		var charter = Charters.charterOf(server, player.getUUID()).orElseThrow().id();
		if (Charters.deposit(server, charter, 20).isPresent()) {
			throw new AssertionError("the deposit should succeed");
		}
		RepairState repairs = RepairState.get(server);
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
			type.parts().forEach(part -> repairs.insert(type, part));
		}
		BlockPos pump = player.blockPosition().relative(Direction.EAST, 2);
		BlockPos processor = player.blockPosition().relative(Direction.SOUTH, 2);
		server.overworld().setBlock(pump, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
		server.overworld().setBlock(processor, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
		player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
		PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
		pod.setPos(player.position().relative(Direction.WEST, 3));
		player.level().addFreshEntity(pod);
		PodComponents.register(pod, charter);
		pod.setFuel(5f);
		return new Scene(processor, pump, pod.getId());
	}

	private static void open(ClientGameTestContext context, BlockPos terminal) {
		context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(terminal)));
		context.waitFor(client -> client.gui.screen() != null, WAIT_TICKS);
	}

	/** The pump's buy button is live: the screen has seen the pod and the account. */
	private static boolean buy(Minecraft client) {
		return client.gui.screen() != null && client.gui.screen().children().stream()
				.anyMatch(child -> child instanceof CrtButton button && button.getMessage().getString().equals("BUY 1 L") && button.active);
	}

	/** waitFor, with what was heard in the failure. */
	private static void await(ClientGameTestContext context, Heard heard, Predicate<Minecraft> condition) {
		try {
			context.waitFor(condition, WAIT_TICKS);
		} catch (AssertionError timeout) {
			throw new AssertionError("timed out; the client heard " + heard.played, timeout);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
