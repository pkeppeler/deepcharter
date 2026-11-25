package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mock-up footage for the death sequence (#1337): a pilot on foot, low on health, walks down a narrow layer-1 drift under a
 * regolith roof. The props under the roof give, the loose regolith comes down on them and they suffocate. Every tick is one
 * frame (20 fps, real time), and the frame stops on the tick before the vanilla death screen, where the mock-up's own death
 * sequence is cut in. {@code events.txt} beside the frames lists the frame of each sound the audio track is rebuilt from.
 */
public class DeathMockupScenario extends EvidenceScenario {
    private static final int X = 5000;
    private static final int Z = 5000;
    private static final int FLOOR_Y = LayerTuning.DEFAULT.crustThickness() + 1;
    private static final int ROOF_HEIGHT = 3;
    private static final int LOOSE_DEPTH = 6;
    private static final int CAVE_WEST = X + 1;
    private static final int CAVE_EAST = X + 5;
    private static final float EAST = -90f;
    private static final float LOOK_PITCH = 4f;
    private static final float START_HEALTH = 3f;
    private static final int WALK_LIMIT_TICKS = 80;
    private static final int DEATH_LIMIT_TICKS = 300;
    private static final int STOP_AT_X = X + 3;

    private final List<String> events = new ArrayList<>();
    private int tick;

    @Override
    protected String name() {
        return "death-mockup";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                buildDrift(one);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X - 6 + 0.5, FLOOR_Y, Z + 0.5, Set.of(), EAST, LOOK_PITCH, true);
            });
            awaitWorld(context);
            ClientWait.until(context, "the player in layer 1", client -> TestLayers.clientIsIn(client, 1));
            context.waitTicks(60);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setHealth(START_HEALTH);
                player.getFoodData().setFoodLevel(6);
            });
            context.waitTicks(10);

            for (int i = 0; i < 20; i++) {
                step(context);
            }
            event("walk_start");
            context.getInput().holdKey(options -> options.keyUp);
            for (int i = 0; i < WALK_LIMIT_TICKS; i++) {
                step(context);
                if (pilot(singleplayer).getX() >= STOP_AT_X) {
                    break;
                }
            }
            context.getInput().releaseKey(options -> options.keyUp);
            event("walk_stop");
            for (int i = 0; i < 14; i++) {
                step(context);
            }

            // The first prop gives at the far end: a crack and a trickle, then the roof over the pilot goes.
            event("crack");
            breakProps(singleplayer, CAVE_EAST, CAVE_EAST);
            for (int i = 0; i < 12; i++) {
                step(context);
            }
            event("collapse");
            breakProps(singleplayer, CAVE_WEST, CAVE_EAST - 1);

            float last = START_HEALTH;
            for (int i = 0; i < DEATH_LIMIT_TICKS; i++) {
                boolean dead = context.computeOnClient(client ->
                        client.screen instanceof DeathScreen || ClientSession.player(client).isDeadOrDying());
                if (dead) {
                    event("death");
                    break;
                }
                step(context);
                float health = pilot(singleplayer).getHealth();
                if (health < last) {
                    event("hurt " + health);
                    last = health;
                }
            }
            writeEvents();
        }
    }

    private void step(ClientGameTestContext context) {
        context.waitTicks(1);
        frame(context);
        tick++;
    }

    private static ServerPlayer pilot(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> server.getPlayerList().getPlayers().getFirst());
    }

    private void event(String what) {
        events.add(tick + " " + what);
        // The CI recording keeps only the media, so the log carries the event frames too.
        System.out.println("death-mockup event: frame " + tick + " " + what);
    }

    private void writeEvents() {
        String root = System.getenv("DEEPCHARTER_EVIDENCE_DIR");
        if (root == null) {
            return;
        }
        try {
            Files.write(Path.of(root).toAbsolutePath().resolve(name()).resolve("events.txt"), events);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void breakProps(TestSingleplayerContext singleplayer, int fromX, int toX) {
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            for (int x = fromX; x <= toX; x++) {
                for (int z = Z - 1; z <= Z + 1; z++) {
                    one.destroyBlock(new BlockPos(x, FLOOR_Y + ROOF_HEIGHT, z), false);
                }
            }
        });
    }

    /**
     * A drift three wide and three high running east through stone, lit by a few glowstone blocks in its walls. Over its east
     * end the roof is a course of packed regolith (the props) under a deep bed of loose regolith.
     */
    private static void buildDrift(ServerLevel level) {
        RoomCarver.carve(level, X - 10, X + 10, 0, 2, Z - 6, Z + 6, LayerBlocks.BREACH_CRUST);
        RoomCarver.carve(
                level,
                new BlockPos(X - 10, 3, Z - 6),
                new BlockPos(X + 10, FLOOR_Y + ROOF_HEIGHT + LOOSE_DEPTH + 2, Z + 6),
                Blocks.STONE.defaultBlockState(),
                Block.UPDATE_ALL);
        RoomCarver.carve(
                level,
                new BlockPos(X - 9, FLOOR_Y, Z - 1),
                new BlockPos(X + 7, FLOOR_Y + ROOF_HEIGHT - 1, Z + 1),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        BlockState packed = SurfaceBlocks.REGOLITH_PACKED.defaultBlockState();
        BlockState loose = SurfaceBlocks.REGOLITH.defaultBlockState();
        for (int x = CAVE_WEST; x <= CAVE_EAST; x++) {
            for (int z = Z - 1; z <= Z + 1; z++) {
                level.setBlock(new BlockPos(x, FLOOR_Y + ROOF_HEIGHT, z), packed, Block.UPDATE_ALL);
                for (int y = 1; y <= LOOSE_DEPTH; y++) {
                    level.setBlock(new BlockPos(x, FLOOR_Y + ROOF_HEIGHT + y, z), loose, Block.UPDATE_ALL);
                }
            }
        }
        for (BlockPos lamp : new BlockPos[] {
            new BlockPos(X - 7, FLOOR_Y + 1, Z - 2),
            new BlockPos(X - 2, FLOOR_Y + 1, Z + 2),
            new BlockPos(X + 3, FLOOR_Y + 1, Z - 2),
            new BlockPos(X + 8, FLOOR_Y + 1, Z)
        }) {
            level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
