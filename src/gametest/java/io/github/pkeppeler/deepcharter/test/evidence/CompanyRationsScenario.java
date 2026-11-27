package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "company-rations" for #838: a new player's food. At the ore cars on the track in front of the Works, the first use
 * finds the last shift's tins (the action bar says so and the hotbar fills) and the second finds the car empty. In the Lamp and Pick
 * the canteen dispenser gives a ration, and a second use gives another (#1439). The HUD stays on, because the action bar
 * line and the hotbar are the evidence. The ore car and the canteen are found in the placed colony, and the player stands in front of each along its facing.
 */
public class CompanyRationsScenario extends EvidenceScenario {
    private static final double EYE = 1.62;
    /** How far the colony is searched for its ore cars and canteen, in blocks each way from the pad's centre. */
    private static final int SEARCH = 40;
    /** How many rows above the pad's ground the search reads. */
    private static final int SEARCH_HEIGHT = 3;
    /** How far in front of the block, along its facing, the player stands. */
    private static final int STAND_BACK = 3;

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

            BlockPos car = singleplayer.getServer().computeOnServer(server -> endCar(server.overworld(), centre));
            Stand carStand = standing(singleplayer, car);
            stand(context, singleplayer, carStand, car);
            use(singleplayer, car);
            showMessage(context, "ore-car-find");
            use(singleplayer, car);
            showMessage(context, "ore-car-empty");

            BlockPos canteen = singleplayer
                    .getServer()
                    .computeOnServer(server ->
                            find(server.overworld(), centre, ColonyKit.CANTEEN).getFirst());
            Stand canteenStand = standing(singleplayer, canteen);
            stand(context, singleplayer, canteenStand, canteen);
            use(singleplayer, canteen);
            showMessage(context, "canteen-ration");
            use(singleplayer, canteen);
            showMessage(context, "canteen-second-ration");
        }
    }

    /** Where the player's feet go: {@code feet}, in front of a block along the block's facing. */
    private record Stand(Vec3 feet) {}

    /** Every {@code wanted} block within {@link #SEARCH} of the pad's centre, by increasing X and then Z: the colony's own, wherever the world's spawn put it. */
    private static List<BlockPos> find(ServerLevel level, BlockPos centre, Block wanted) {
        List<BlockPos> found = new ArrayList<>();
        for (int x = -SEARCH; x <= SEARCH; x++) {
            for (int z = -SEARCH; z <= SEARCH; z++) {
                for (int y = 0; y <= SEARCH_HEIGHT; y++) {
                    BlockPos pos = centre.offset(x, y, z);
                    if (level.getBlockState(pos).is(wanted)) {
                        found.add(pos);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            throw new AssertionError("the colony holds no " + wanted + " within " + SEARCH + " blocks of its centre");
        }
        return found;
    }

    /** The ore car at the open end of its train: the one the player faces without another car in the way. */
    private static BlockPos endCar(ServerLevel level, BlockPos centre) {
        return find(level, centre, ColonyKit.ORE_CAR).stream()
                .filter(pos ->
                        !level.getBlockState(pos.relative(facing(level, pos))).is(ColonyKit.ORE_CAR))
                .findFirst()
                .orElseThrow(() -> new AssertionError("every ore car of the colony has another in front of it"));
    }

    private static Direction facing(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getValue(HorizontalDirectionalBlock.FACING);
    }

    private Stand standing(TestSingleplayerContext singleplayer, BlockPos block) {
        Direction front = singleplayer.getServer().computeOnServer(server -> facing(server.overworld(), block));
        BlockPos at = block.relative(front, STAND_BACK);
        return new Stand(new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
    }

    /** Puts the player's feet at {@code stand}, eyes on {@code target}, and waits for the client to stand there with the world drawn. */
    private void stand(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, Stand stand, BlockPos target) {
        Vec3 at = stand.feet();
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
