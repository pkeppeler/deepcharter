package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "thrown-kit" (#985): a person on foot in a sealed, dark room in layer 1 throws one of each item of the thrown kit, the items the
 * repair station sells. A flare to the left lands and stands burning: the room lights up round it. A decoy to the right lands and rings out, with notes
 * rising. A charge straight ahead lands on a slab of Company rock, sparks through its fuse with the action bar saying to get clear, and blasts a hole in
 * the slab while the rock beside it stands.
 */
public class ThrownKitScenario extends EvidenceScenario {
    private static final int X = 5600;
    private static final int Z = 5000;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final int TICKS_PER_FRAME = 3;
    private static final int MAX_TICKS = 600;
    /** A throw 8 degrees down lands about 7 blocks off, a charge's blast reach clear of the thrower; the slab of Company rock round that is 7 wide and 8 deep, two rows thick. */
    private static final int SLAB_FROM = 4;

    private static final int SLAB_TO = 11;
    /** How far down the person looks to throw, and to see the floor ahead. */
    private static final float DOWN = 8f;

    private static final int SLAB_HALF_WIDTH = 3;
    /** Turns, in degrees, from the way the room faces: positive turns to the player's right. */
    private static final float LEFT = -25f;

    private static final float RIGHT = 25f;
    private static final float AHEAD = 0f;
    /** A hole of the blast's size: 3 x 3 columns, 2 rows. */
    private static final int BLAST_CELLS = 18;

    @Override
    protected String name() {
        return "thrown-kit";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(one, X - 16, X + 16, FLOOR_Y - 9, FLOOR_Y + 9, Z - 6, Z + 26, Blocks.STONE);
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 15, FLOOR_Y, Z - 5),
                        new BlockPos(X + 15, FLOOR_Y + 7, Z + 25),
                        Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                for (int dx = -SLAB_HALF_WIDTH; dx <= SLAB_HALF_WIDTH; dx++) {
                    for (int dz = SLAB_FROM; dz <= SLAB_TO; dz++) {
                        for (int dy = -2; dy <= -1; dy++) {
                            one.setBlock(
                                    new BlockPos(X + dx, FLOOR_Y + dy, Z + dz),
                                    HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                                    3);
                        }
                    }
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y, Z + 0.5, Set.of(), AHEAD, DOWN, true);
            });
            ClientWait.until(
                    context,
                    "the person on foot in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            context.waitTicks(30);
            for (int i = 0; i < 6; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "thrown-kit-dark");

            throwItem(singleplayer, Consumable.THROWN_FLARE, LEFT);
            for (int i = 0; i < 14; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            if (!flareBurns(singleplayer)) {
                throw new AssertionError("the flare should have landed and be burning");
            }
            screenshot(context, "thrown-kit-flare");

            throwItem(singleplayer, Consumable.THROWN_DECOY, RIGHT);
            for (int i = 0; i < 14; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "thrown-kit-decoy");

            throwItem(singleplayer, Consumable.THROWN_CHARGE, AHEAD);
            for (int i = 0; i < 8; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "thrown-kit-fuse");
            int ticks = 0;
            while (cleared(singleplayer) < BLAST_CELLS && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            if (cleared(singleplayer) != BLAST_CELLS) {
                throw new AssertionError("the charge should clear a hole of " + BLAST_CELLS + " cells, it cleared "
                        + cleared(singleplayer));
            }
            for (int i = 0; i < 8; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "thrown-kit-blast");
        }
    }

    /** Faces the person {@code yaw} degrees off the room's way, puts one {@code item} in the hand and uses it, as a player does. */
    private static void throwItem(TestSingleplayerContext singleplayer, Consumable item, float yaw) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(TestLayers.level(server, 1), X + 0.5, FLOOR_Y, Z + 0.5, Set.of(), yaw, DOWN, true);
            player.getInventory()
                    .setItem(player.getInventory().getSelectedSlot(), new ItemStack(RepairRegistry.item(item)));
            player.getCooldowns()
                    .removeCooldown(
                            player.getCooldowns().getCooldownGroup(player.getItemInHand(InteractionHand.MAIN_HAND)));
            if (!player.gameMode
                    .useItem(
                            player,
                            player.level(),
                            player.getItemInHand(InteractionHand.MAIN_HAND),
                            InteractionHand.MAIN_HAND)
                    .consumesAction()) {
                throw new AssertionError("the " + item + " should be thrown");
            }
        });
    }

    private static boolean flareBurns(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            for (int dx = -SLAB_HALF_WIDTH * 3; dx <= SLAB_HALF_WIDTH * 3; dx++) {
                for (int dz = 0; dz <= SLAB_TO; dz++) {
                    if (one.getBlockState(new BlockPos(X + dx, FLOOR_Y, Z + dz)).is(RepairRegistry.FLARE_LIGHT)) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    /** How many cells of the slab are air: the blast's hole. */
    private static int cleared(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            int air = 0;
            for (int dx = -SLAB_HALF_WIDTH; dx <= SLAB_HALF_WIDTH; dx++) {
                for (int dz = SLAB_FROM; dz <= SLAB_TO; dz++) {
                    for (int dy = -2; dy <= -1; dy++) {
                        if (one.getBlockState(new BlockPos(X + dx, FLOOR_Y + dy, Z + dz))
                                .isAir()) {
                            air++;
                        }
                    }
                }
            }
            return air;
        });
    }
}
