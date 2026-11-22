package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.layer.CaveIns;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "bolt-driver" (#987): a person on foot in a lit tunnel with the bolt driver fires three bolts at a Retained that stands
 * in it, which end it, then sets roof bolts along one stretch of the roof. A blast shakes the roof over that stretch and over a stretch
 * with no bolts: both creak for three seconds, the unbolted roof falls and fills the tunnel with rock, and the bolted roof stays up.
 */
public class BoltDriverScenario extends EvidenceScenario {
    private static final int X = 5900;
    private static final int Z = 5100;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final int LENGTH = 28;
    private static final int WIDTH = 3;
    private static final int TUNNEL_HEIGHT = 3;
    private static final int TICKS_PER_FRAME = 3;
    private static final float EAST = -90f;
    private static final float WEST = 90f;
    /** The Retained's place, the driver's rest between shots (12 ticks) with a tick to spare. */
    private static final int RETAINED_ALONG = 14;

    private static final int SHOT_TICKS = 13;
    /** Where the person stands to fire, and the roof bolts that hold the stretch behind them. */
    private static final int FIRING_ALONG = 4;

    private static final int[] BOLTS_ALONG = {4, 8};
    /** The two stretches the blast shakes, one bolted and one not: the person stands between them. */
    private static final int HELD_ALONG = 6;

    private static final int LOOSE_ALONG = 22;
    private static final int STANDING_ALONG = 14;
    /** The creak (60 ticks), the fall and the rock coming to rest. */
    private static final int COLLAPSE_TICKS = 110;

    private static BlockPos floor(int along) {
        return new BlockPos(X + along, FLOOR_Y, Z);
    }

    private static BlockPos roof(int along) {
        return floor(along).above(TUNNEL_HEIGHT);
    }

    @Override
    protected String name() {
        return "bolt-driver";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(
                        one,
                        X - 3,
                        X + LENGTH + 3,
                        FLOOR_Y - 3,
                        FLOOR_Y + TUNNEL_HEIGHT + 4,
                        Z - WIDTH - 2,
                        Z + WIDTH + 2,
                        Blocks.STONE);
                RoomCarver.carve(
                        one,
                        new BlockPos(X, FLOOR_Y, Z - WIDTH / 2),
                        new BlockPos(X + LENGTH - 1, FLOOR_Y + TUNNEL_HEIGHT - 1, Z + WIDTH / 2),
                        Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                // Lamps set in the walls, never the roof: the roof is what the scenario brings down.
                for (int along = 2; along < LENGTH; along += 4) {
                    one.setBlock(floor(along).offset(0, 1, -WIDTH / 2 - 1), Blocks.SEA_LANTERN.defaultBlockState(), 3);
                    one.setBlock(floor(along).offset(0, 1, WIDTH / 2 + 1), Blocks.SEA_LANTERN.defaultBlockState(), 3);
                }
                LamplessFigure retained = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, one);
                retained.setPos(Vec3.atBottomCenterOf(floor(RETAINED_ALONG)));
                retained.setNoAi(true);
                one.addFreshEntity(retained);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.getInventory()
                        .setItem(
                                player.getInventory().getSelectedSlot(),
                                new ItemStack(RepairRegistry.item(Consumable.BOLT_DRIVER)));
                player.getInventory().setItem(1, new ItemStack(RepairRegistry.item(Consumable.BOLT), 16));
                stand(one, player, FIRING_ALONG, EAST);
            });
            ClientWait.until(
                    context,
                    "the person on foot in the tunnel in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.waitTicks(40);
            for (int i = 0; i < 4; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "bolt-driver-before");

            for (int shot = 0; shot < 3; shot++) {
                singleplayer.getServer().runOnServer(server -> use(server, null));
                for (int tick = 0; tick < SHOT_TICKS; tick += TICKS_PER_FRAME) {
                    context.waitTicks(TICKS_PER_FRAME);
                    frame(context);
                }
                if (shot == 0) {
                    screenshot(context, "bolt-driver-fired");
                }
            }
            if (!singleplayer.getServer().computeOnServer(server -> retainedGone(server))) {
                throw new AssertionError("three bolts should end the Retained");
            }
            screenshot(context, "bolt-driver-retained-down");

            for (int along : BOLTS_ALONG) {
                singleplayer.getServer().runOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    stand(TestLayers.level(server, 1), player, along, EAST);
                    use(server, roof(along));
                });
                for (int i = 0; i < 6; i++) {
                    context.waitTicks(TICKS_PER_FRAME);
                    frame(context);
                }
            }
            screenshot(context, "bolt-driver-roof-bolt");

            // Back between the two stretches, looking at the loose one, and the blast.
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                stand(one, server.getPlayerList().getPlayers().getFirst(), STANDING_ALONG - 3, EAST);
                CaveIns.shake(one, floor(HELD_ALONG));
                CaveIns.shake(one, floor(LOOSE_ALONG));
            });
            for (int tick = 0; tick < COLLAPSE_TICKS; tick += TICKS_PER_FRAME) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
                if (tick == 0) {
                    screenshot(context, "bolt-driver-creak");
                }
            }
            screenshot(context, "bolt-driver-fallen");
            if (!singleplayer.getServer().computeOnServer(server -> collapsed(server))) {
                throw new AssertionError("the unbolted roof should have fallen and the bolted roof stood");
            }
            singleplayer
                    .getServer()
                    .runOnServer(server -> stand(
                            TestLayers.level(server, 1),
                            server.getPlayerList().getPlayers().getFirst(),
                            STANDING_ALONG,
                            WEST));
            for (int i = 0; i < 8; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "bolt-driver-held");
            context.runOnClient(client -> ClientSession.player(client).setXRot(0f));
        }
    }

    private static void stand(ServerLevel level, ServerPlayer player, int along, float yaw) {
        player.teleportTo(level, X + along + 0.5, FLOOR_Y, Z + 0.5, Set.of(), yaw, 0f, true);
    }

    /** A right click with the driver: on the underside of {@code roof}, or in the air when it is null. */
    private static void use(MinecraftServer server, @Nullable BlockPos roof) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.getCooldowns()
                .removeCooldown(
                        player.getCooldowns().getCooldownGroup(player.getItemInHand(InteractionHand.MAIN_HAND)));
        boolean done = roof == null
                ? player.gameMode
                        .useItem(
                                player,
                                player.level(),
                                player.getItemInHand(InteractionHand.MAIN_HAND),
                                InteractionHand.MAIN_HAND)
                        .consumesAction()
                : player.gameMode
                        .useItemOn(
                                player,
                                player.level(),
                                player.getItemInHand(InteractionHand.MAIN_HAND),
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(roof).add(0, -0.5, 0), Direction.DOWN, roof, false))
                        .consumesAction();
        if (!done) {
            throw new AssertionError("the driver should have fired or set a bolt");
        }
    }

    private static boolean retainedGone(MinecraftServer server) {
        return TestLayers.level(server, 1)
                .getEntities(CreatureRegistry.LAMPLESS_FIGURE, figure -> figure.isAlive())
                .isEmpty();
    }

    private static boolean collapsed(MinecraftServer server) {
        ServerLevel one = TestLayers.level(server, 1);
        for (int along : new int[] {HELD_ALONG - 2, HELD_ALONG, HELD_ALONG + 2}) {
            if (!one.getBlockState(roof(along)).is(Blocks.STONE)) {
                return false;
            }
        }
        for (int along : new int[] {LOOSE_ALONG - 2, LOOSE_ALONG, LOOSE_ALONG + 2}) {
            if (one.getBlockState(roof(along)).is(Blocks.STONE)) {
                return false;
            }
        }
        return one.getBlockState(roof(BOLTS_ALONG[0]).below()).is(LayerBlocks.ROOF_BOLT);
    }
}
