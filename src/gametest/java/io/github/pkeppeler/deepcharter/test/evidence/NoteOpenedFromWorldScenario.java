package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "note-opened-from-world" for #834: a player on no charter uses a Note in the world, it opens to read and is filed for them alone (#843), then
 * the same player founds a charter and uses another, which opens and is filed too.
 */
public class NoteOpenedFromWorldScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 6;

    @Override
    protected String name() {
        return "note-opened-from-world";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(20);
            screenshot(context, "the-note-in-the-world");
            singleplayer
                    .getServer()
                    .runOnServer(server ->
                            useNoteBlock(server.getPlayerList().getPlayers().getFirst(), 1));
            ClientWait.screen(context, HandbookScreen.class);
            hold(context);
            screenshot(context, "opened-with-no-charter");
            context.setScreen(() -> null);

            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                Charters.found(server, player.getUUID(), "Evidence Co");
                useNoteBlock(player, 2);
            });
            ClientWait.screen(context, HandbookScreen.class);
            hold(context);
            screenshot(context, "opened-with-a-charter");
            context.setScreen(() -> null);
        }
    }

    private void hold(ClientGameTestContext context) {
        context.waitTicks(2);
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }

    /** Puts a Note block in front of the player and uses it, as the game does on a right-click. */
    private static void useNoteBlock(ServerPlayer player, int number) {
        BlockPos pos = player.blockPosition().relative(Direction.NORTH, 2);
        player.level().setBlock(pos, NoteBlock.stateOf(number), 3);
        player.level()
                .getBlockState(pos)
                .useWithoutItem(
                        player.level(), player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }
}
