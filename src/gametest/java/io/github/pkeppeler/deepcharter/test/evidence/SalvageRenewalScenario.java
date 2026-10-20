package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "salvage-renewal" (#470): a charter whose pod brought 72 spoil home from a bore presses it at the ore processor into
 * three salvage crates, places one on the ground and breaks it bare handed, and the wood and scrap of the yard's crates fall out. The
 * charter is on an empty account, so the press is seen to cost spoil and no money.
 */
public class SalvageRenewalScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 2;
    private static final int TYPING_FRAMES = 45;
    private static final int HOLD_FRAMES = 10;
    private static final int PICK_UP_TICKS = 24;
    /** Spoil for three crates, with 0 left over at 24 a crate. */
    private static final int SPOIL = 72;

    private static final int CRATES = 3;
    /** The crate is set down this far north of the player. */
    private static final int PLACE_DISTANCE = 2;

    @Override
    protected String name() {
        return "salvage-renewal";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            EvidenceWorld.pin(context, singleplayer);
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            BlockPos processor = singleplayer.getServer().computeOnServer(SalvageRenewalScenario::setUp);

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
            ClientWait.screen(context, OreProcessorScreen.class);
            OreProcessorScreen screen =
                    context.computeOnClient(client -> ClientSession.screen(client, OreProcessorScreen.class));
            for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME); // tick-wait: the intro types out for a fixed number of frames
                frame(context);
            }
            hold(context);
            screenshot(context, "processor-before-press");

            context.clickScreenButton("PRESS CRATE");
            ClientWait.until(
                    context,
                    "the pressed crates in the pack",
                    () -> crates(singleplayer) == CRATES,
                    () -> crates(singleplayer) + " crates in the pack");
            hold(context);
            screenshot(context, "processor-after-press");
            context.setScreen(() -> null);
            hold(context);
            screenshot(context, "crates-in-the-pack");

            BlockPos ground = singleplayer.getServer().computeOnServer(SalvageRenewalScenario::clearPlace);
            BlockPos crate = ground.above();
            context.getInput().lookAt(crate);
            ClientWait.until(
                    context,
                    "the pad in the client's world",
                    client -> ClientLight.clientLevel(client)
                            .getBlockState(ground)
                            .is(Blocks.STONE));
            String placed = context.computeOnClient(client -> {
                Inventory inventory = ClientSession.player(client).getInventory();
                for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                    if (inventory.getItem(slot).is(SurfaceBlocks.SALVAGE_CRATE.asItem())) {
                        inventory.setSelectedSlot(slot);
                    }
                }
                return ClientSession.player(client).getMainHandItem() + " -> "
                        + ClientSession.gameMode(client)
                                .useItemOn(
                                        ClientSession.player(client),
                                        InteractionHand.MAIN_HAND,
                                        new BlockHitResult(
                                                Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false));
            });
            ClientWait.until(
                    context,
                    "the crate placed (" + placed + ")",
                    client ->
                            ClientLight.clientLevel(client).getBlockState(crate).is(SurfaceBlocks.SALVAGE_CRATE));
            hold(context);
            screenshot(context, "crate-placed");

            mineBlock(context, singleplayer, crate, TICKS_PER_FRAME);
            context.getInput().holdKey(options -> options.keyUp);
            for (int tick = 0; tick < PICK_UP_TICKS; tick += TICKS_PER_FRAME) {
                context.waitTicks(TICKS_PER_FRAME); // tick-wait: a short walk onto what fell out
                frame(context);
            }
            context.getInput().releaseKey(options -> options.keyUp);
            hold(context);
            screenshot(context, "crate-broken");
        }
    }

    /** A charter with an empty account, a repaired processor beside the player and a parked pod holding spoil. Returns the processor. */
    private static BlockPos setUp(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        if (Charters.found(server, player.getUUID(), "Riggs and Sons").isPresent()) {
            throw new AssertionError("founding should succeed");
        }
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        BlockPos at = player.blockPosition().relative(Direction.EAST, 2);
        server.overworld().setBlock(at, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
        RepairState state = RepairState.get(server);
        for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
            type.parts().forEach(part -> state.insert(type, part));
        }
        PodEntity pod = TestEntities.create(PodRegistry.POD, server.overworld());
        pod.setPos(player.position().add(0, 0, 4));
        server.overworld().addFreshEntity(pod);
        PodLining.modify(pod, lining -> lining.withSpoil(SPOIL));
        return at;
    }

    /** A stone pad {@value #PLACE_DISTANCE} blocks north of the player with air above it. Returns the pad's block. */
    private static BlockPos clearPlace(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        BlockPos ground =
                player.blockPosition().relative(Direction.NORTH, PLACE_DISTANCE).below();
        server.overworld().setBlock(ground, Blocks.STONE.defaultBlockState(), 3);
        server.overworld().setBlock(ground.above(), Blocks.AIR.defaultBlockState(), 3);
        server.overworld().setBlock(ground.above(2), Blocks.AIR.defaultBlockState(), 3);
        return ground;
    }

    private static int crates(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .getInventory()
                        .countItem(SurfaceBlocks.SALVAGE_CRATE.asItem()));
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip holds a fixed number of frames
            frame(context);
        }
    }
}
