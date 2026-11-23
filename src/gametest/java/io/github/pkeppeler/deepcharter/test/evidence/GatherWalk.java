package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.test.support.BlockSurvey;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import java.util.Comparator;
import java.util.Set;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * A player on foot in the design tour's world, bare handed with an empty pack, who finds a block the world generated and walks up to it
 * and breaks it: what {@link SalvageGatherScenario} and {@link StalkCollapseScenario} share. The blocks are the real generated
 * features, found by scanning the world.
 */
final class GatherWalk {
    /** The survey looks this many chunks each way of the colony, and a crate must be this far out to be beyond the colony pad. */
    private static final int SURVEY_CHUNKS = 8;

    private static final int MIN_CRATE_DISTANCE = 70;
    static final int TICKS_PER_FRAME = 2;
    static final int WALK_UP_TICKS = 40;
    private static final int PICK_UP_TICKS = 16;
    private static final int HOLD_FRAMES = 10;

    private final EvidenceScenario scenario;
    private final ClientGameTestContext ctx;
    private final TestSingleplayerContext world;
    /** How far south of a block the player starts. */
    private final int distance;
    /** Whether the walk records frames of itself: a scenario that cuts to the moment after the walk turns it off. */
    private final boolean framed;

    GatherWalk(
            EvidenceScenario scenario,
            ClientGameTestContext ctx,
            TestSingleplayerContext world,
            int distance,
            boolean framed) {
        this.scenario = scenario;
        this.ctx = ctx;
        this.world = world;
        this.distance = distance;
        this.framed = framed;
    }

    /** Builds the tour's world, pins it, puts the camera in first person and empties the pack, and runs {@code scenes} in it. */
    static void inWorld(
            ClientGameTestContext context, BiConsumer<ClientGameTestContext, TestSingleplayerContext> scenes) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed("deepcharter-design-tour"))
                .create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            EvidenceWorld.pin(context, singleplayer);
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            singleplayer
                    .getServer()
                    .runOnServer(server -> server.getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .getInventory()
                            .clearContent());
            scenes.accept(context, singleplayer);
        }
    }

    /**
     * The block of the given kind nearest the colony that the player can walk up to: the crates must stand beyond the pad, the stalk must be
     * a column of at least {@code height} blocks of its kind with a free standing place {@code distance} blocks south of its foot. Failing
     * that, the run fails and says so.
     */
    static BlockPos nearest(MinecraftServer server, String path, boolean foot, int distance, int height) {
        Block wanted = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        ServerLevel level = server.overworld();
        return BlockSurvey.generateAndFind(level, SURVEY_CHUNKS, wanted).stream()
                .filter(at -> reachable(level, at, foot, distance, height))
                .min(Comparator.comparingDouble(GatherWalk::distanceFromOrigin))
                .orElseThrow(() -> new AssertionError("no " + path + " within " + SURVEY_CHUNKS
                        + " chunks of the colony that a player can walk up to"));
    }

    private static double distanceFromOrigin(BlockPos at) {
        return Vec3.atCenterOf(at).horizontalDistanceSqr();
    }

    private static boolean reachable(ServerLevel level, BlockPos at, boolean foot, int distance, int height) {
        Block kind = level.getBlockState(at).getBlock();
        if (foot ? level.getBlockState(at.below()).is(kind) : distanceFromOrigin(at) < MIN_CRATE_DISTANCE) {
            return false;
        }
        for (int up = 1; foot && up < height; up++) {
            if (!level.getBlockState(at.above(up)).is(kind)) {
                return false;
            }
        }
        for (int dz = 1; dz <= distance; dz++) {
            BlockPos cell = at.offset(0, 0, dz);
            boolean free = level.getBlockState(cell).isAir()
                    && level.getBlockState(cell.above()).isAir();
            boolean ground = level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP);
            if (!free || !ground) {
                return false;
            }
        }
        return true;
    }

    /** Walks up to {@code target}, breaks it bare handed and picks up what falls. */
    void gather(BlockPos target) {
        stand(target);
        walk(WALK_UP_TICKS);
        breakBlock(target);
        walk(PICK_UP_TICKS);
        for (int i = 0; i < HOLD_FRAMES; i++) {
            ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip holds on the full hotbar for a fixed number of
            // frames
            scenario.frame(ctx);
        }
    }

    /** Puts the player {@code distance} blocks south of the block, on the ground, bare handed, and looking at it. */
    void stand(BlockPos target) {
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            ServerLevel level = player.level();
            player.setGameMode(GameType.SURVIVAL);
            player.setNoGravity(false);
            player.getAbilities().flying = false;
            player.getAbilities().mayfly = false;
            player.onUpdateAbilities();
            BlockPos feet = target.offset(0, 0, distance);
            while (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
                feet = feet.below();
            }
            player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, Set.of(), 180f, 0f, true);
        });
        ClientWait.until(
                ctx,
                "the player next to " + target.toShortString(),
                client -> ClientSession.player(client).blockPosition().distSqr(target) < distance * distance * 2.5
                        && ClientSession.player(client).onGround()
                        && ClientLight.clientLevel(client)
                                .getChunkSource()
                                .hasChunk(target.getX() >> 4, target.getZ() >> 4));
        ctx.getInput().lookAt(target);
        ctx.waitTicks(10); // tick-wait: the chunks and the light round the player come in
        if (framed) {
            scenario.frame(ctx);
        }
    }

    /** Holds forward for {@code ticks} ticks, a frame every {@link #TICKS_PER_FRAME}. */
    void walk(int ticks) {
        ctx.getInput().holdKey(options -> options.keyUp);
        for (int tick = 0; tick < ticks; tick += TICKS_PER_FRAME) {
            ctx.waitTicks(TICKS_PER_FRAME); // tick-wait: a walk of a fixed length
            if (framed) {
                scenario.frame(ctx);
            }
        }
        ctx.getInput().releaseKey(options -> options.keyUp);
    }

    /** Mines the block bare handed, a frame every {@link #TICKS_PER_FRAME} ticks. */
    void breakBlock(BlockPos target) {
        scenario.mineBlock(ctx, world, target, framed ? TICKS_PER_FRAME : EvidenceScenario.NO_FRAMES);
    }
}
