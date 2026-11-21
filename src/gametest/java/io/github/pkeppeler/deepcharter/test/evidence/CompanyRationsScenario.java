package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "company-rations" for #838: a new player's food. At the ore cars on the track in front of the Works, the first use
 * finds the last shift's tins (the action bar says so and the hotbar fills) and the second finds the car empty. In the Lamp and Pick
 * the canteen dispenser gives today's ration, and the second use the same day is refused. The HUD stays on, because the action bar
 * line and the hotbar are the evidence. Positions are offsets from the colony's centre (X east, Z south), as in the colony walk.
 */
public class CompanyRationsScenario extends EvidenceScenario {
    private static final double EYE = 1.62;
    /** The first ore car of the pair on the track out of the Works' bay (tools/colony/town.py, works). */
    private static final BlockPos CAR = new BlockPos(13, 1, -8);
    /** The canteen dispenser against the east wall of the Lamp and Pick (tools/colony/town.py, lamp_and_pick). */
    private static final BlockPos CANTEEN = new BlockPos(8, 1, 20);
    /** Ticks the action bar line takes to show, and frames shown while it does. */
    private static final int MESSAGE_TICKS = 6;

    private static final int HOLD_FRAMES = 10;
    private static final int TICKS_PER_FRAME = 2;

    @Override
    protected String name() {
        return "company-rations";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        // The design tour's world: the campaign's own surface, under the pinned sky.
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed("deepcharter-design-tour"))
                .create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            BlockPos centre = singleplayer
                    .getServer()
                    .computeOnServer(server -> Colony.placed(server)
                            .orElseThrow(() -> new AssertionError("the colony was not built"))
                            .center());
            EvidenceWorld.pin(context, singleplayer);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getInventory().clearContent();
                player.setPermanentlyInvulnerable(true);
                player.setNoGravity(true);
            });
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));

            stand(context, singleplayer, centre, new Vec3(13.5, 1, -4.5), centre.offset(CAR));
            use(singleplayer, centre.offset(CAR));
            showMessage(context, "ore-car-find");
            use(singleplayer, centre.offset(CAR));
            showMessage(context, "ore-car-empty");

            stand(context, singleplayer, centre, new Vec3(4.5, 1, 17.5), centre.offset(CANTEEN));
            use(singleplayer, centre.offset(CANTEEN));
            showMessage(context, "canteen-ration");
            use(singleplayer, centre.offset(CANTEEN));
            showMessage(context, "canteen-refused");
        }
    }

    /** Puts the player's feet at {@code feet} from the centre, eyes on {@code target}, and waits for the client to stand there with the world drawn. */
    private void stand(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            BlockPos centre,
            Vec3 feet,
            BlockPos target) {
        Vec3 at = Vec3.atLowerCornerOf(centre).add(feet);
        Vec3 d = Vec3.atCenterOf(target).subtract(at.add(0, EYE, 0));
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        singleplayer
                .getServer()
                .runOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .teleportTo(server.overworld(), at.x, at.y, at.z, Set.of(), yaw, pitch, true));
        ClientWait.until(
                context,
                "the camera at " + at,
                client -> ClientSession.player(client).distanceToSqr(at.x, at.y, at.z) < 0.0001
                        && chunksLoaded(client)
                        && client.levelRenderer.hasRenderedAllSections());
        context.waitTicks(20);
    }

    /** Uses the block at {@code pos} as the player does, through the block as the game calls it. */
    private void use(TestSingleplayerContext singleplayer, BlockPos pos) {
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            if (!(level.getBlockState(pos).is(ColonyKit.ORE_CAR)
                    || level.getBlockState(pos).is(ColonyKit.CANTEEN))) {
                throw new AssertionError("no ore car or canteen at " + pos + ", the colony changed");
            }
            level.getBlockState(pos)
                    .useWithoutItem(
                            level,
                            server.getPlayerList().getPlayers().getFirst(),
                            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        });
    }

    /** Waits for the action bar line to show, holds a few frames, and takes the still. */
    private void showMessage(ClientGameTestContext context, String still) {
        context.waitTicks(MESSAGE_TICKS);
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
        screenshot(context, still);
    }

    private static boolean chunksLoaded(Minecraft client) {
        int radius = client.options.getEffectiveRenderDistance();
        int x = ClientSession.player(client).chunkPosition().x();
        int z = ClientSession.player(client).chunkPosition().z();
        for (int cx = x - radius; cx <= x + radius; cx++) {
            for (int cz = z - radius; cz <= z + radius; cz++) {
                if (ClientLight.clientLevel(client).getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false)
                        == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
