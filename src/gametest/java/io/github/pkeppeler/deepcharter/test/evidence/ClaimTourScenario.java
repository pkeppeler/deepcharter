package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "claim-tour" (#1277): the dig from the colony through layer 1, The Claim, in the real overworld of
 * a default world. Each view is shot twice: lamp-lit, as a miner's lamp lights it (light blocks round the camera, in
 * the game's own fog and ambient light), and with night vision, which shows the shapes. The views:
 *
 * <ul> <li>a cave pocket in the surface band's underground, the first caves on the way down; <li>the longest open run
 * in a cave of each zone of layer 1; <li>a cut face across each contact between two strata: Topsoil Claims' hardpan
 * over Stone Benches' packed shale, and that over Deep Claim's black shale; <li>the abandoned shaft nearest the start:
 * up from its foot, at a candle niche, and down it from part way up. </ul>
 *
 * The player is a spectator. Places are found in the generated world near the start column, so nothing is staged but
 * the cut faces and the lamps. /
public class ClaimTourScenario extends EvidenceScenario {
    private static final int X = 3000;
    private static final int Z = 3000;
    /** Chunks from the start the search for a view looks. */
    private static final int SEARCH_CHUNKS = 4;
    /** The longest open run along an axis a cave view looks for, and how far ahead of the eye it looks. */
    private static final int RUN = 24;

    private static final double LOOK = 10;
    private static final int ROOF = 10;
    private static final double EYE = 1.62;
    /** A cut face: blocks to each side of the start column, rows each way from the contact, and how deep the cut runs
     * south. */
    private static final int CUT_HALF_WIDTH = 34;

    private static final int CUT_HALF_HEIGHT = 18;
    private static final int CUT_DEPTH = 26;
    /** The surface band's underground that holds caves (#1277): over its floor and under the deepest ground. */
    private static final int SURFACE_CAVES_LOW = CampaignBands.SURFACE.minY() + 8;

    private static final int SURFACE_CAVES_HIGH = 16;
    private static final int LAMP = 15;

    /** A view: where the eye is and what it looks at. */
    private record View(Vec3 eye, Vec3 target) {}

    @Override
    protected String name() {
        return "claim-tour";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                ServerLevel level = TestLayers.level(server, 1);
                for (int dx = -SEARCH_CHUNKS; dx <= SEARCH_CHUNKS; dx++) {
                    for (int dz = -SEARCH_CHUNKS; dz <= SEARCH_CHUNKS; dz++) {
                        level.getChunk((X >> 4) + dx, (Z >> 4) + dz);
                    }
                }
            });
            ClientChecks.withHudHidden(context, () -> tour(context, singleplayer));
        }
    }

    private void tour(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        View surface = singleplayer
                .getServer()
                .computeOnServer(server -> caveView(level(server), SURFACE_CAVES_LOW, SURFACE_CAVES_HIGH));
        shootBoth(context, singleplayer, "surface-underground-cave", surface);
        CampaignBands.Band band = CampaignBands.LAYER_1;
        String[] zones = {"topsoil-claims", "stone-benches", "deep-claim"};
        for (int zone = 0; zone < Zones.COUNT; zone++) {
            Zones.Span span = Zones.span(band.minY(), band.height(), zone);
            int low = Math.max(span.low(), band.minY() + LayerTuning.DEFAULT.crustThickness() + 8);
            View cave =
                    singleplayer.getServer().computeOnServer(server -> caveView(level(server), low, span.high() - 3));
            shootBoth(context, singleplayer, zones[zone] + "-cave", cave);
        }
        for (int zone = 0; zone < Zones.COUNT - 1; zone++) {
            int edge = Zones.span(band.minY(), band.height(), zone).low();
            String name = zone == 0 ? "contact-hardpan-packed-shale" : "contact-packed-shale-black-shale";
            singleplayer.getServer().runOnServer(server -> cut(level(server), edge));
            View face = new View(
                    new Vec3(X + 0.5, edge + 0.5, Z + CUT_DEPTH - 1.5), new Vec3(X + 0.5, edge + 0.5, Z - 1.0));
            shootBoth(context, singleplayer, name, face);
        }
        BlockPos pail = singleplayer.getServer().computeOnServer(server -> nearest(level(server), block("lunch_pail")));
        BlockPos axis = singleplayer.getServer().computeOnServer(server -> shaftAxis(level(server), pail));
        Vec3 foot = Vec3.atBottomCenterOf(axis.atY(pail.getY()));
        shootBoth(
                context, singleplayer, "shaft-up-from-the-foot", new View(foot.add(0, 0.2, 0), foot.add(0.3, 12, 0.3)));
        BlockPos niche = singleplayer.getServer().computeOnServer(server -> niche(level(server), axis, pail));
        Vec3 nicheEye = Vec3.atBottomCenterOf(axis.atY(niche.getY())).add(0, 0.5, 0);
        shootBoth(context, singleplayer, "shaft-candle-niche", new View(nicheEye, Vec3.atCenterOf(niche)));
        int top = singleplayer.getServer().computeOnServer(server -> shaftTop(level(server), axis, pail));
        Vec3 high = Vec3.atBottomCenterOf(axis.atY(top - 2));
        shootBoth(context, singleplayer, "shaft-down", new View(high, foot.add(0.3, 0, 0.3)));
    }

    private static ServerLevel level(MinecraftServer server) {
        return TestLayers.level(server, 1);
    }

    private static Block block(String path) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }

    /** Shoots {@code view} lamp-lit and then with night vision, each a still and a frame. */
    private void shootBoth(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, String name, View view) {
        List<BlockPos> lamps = singleplayer.getServer().computeOnServer(server -> lamps(level(server), view));
        look(context, singleplayer, view, false);
        screenshot(context, name + "-lamplit");
        frame(context);
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = level(server);
            for (BlockPos lamp : lamps) {
                if (level.getBlockState(lamp).is(Blocks.LIGHT)) {
                    level.setBlock(lamp, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        });
        look(context, singleplayer, view, true);
        screenshot(context, name);
        frame(context);
    }

    /**
     * Lamps: light blocks at the eye and along the line of sight, as a miner carries them, and a ring of them two
     * blocks short of what the view looks at, as the Company would hang them on a wall; in open cells only, so nothing
     * in view is replaced. /
    private static List<BlockPos> lamps(ServerLevel level, View view) {
        List<BlockPos> placed = new ArrayList<>();
        Vec3 step = view.target().subtract(view.eye()).normalize();
        for (int along = 0; along <= 12; along += 4) {
            lamp(level, BlockPos.containing(view.eye().add(step.scale(along))), placed);
        }
        Vec3 near = Vec3.atCenterOf(subject(level, view)).subtract(step.scale(2.5));
        Vec3 flat = step.cross(new Vec3(0, 1, 0));
        // A view straight up or down has no side of its own: any horizontal one does.
        Vec3 side = flat.length() < 0.1 ? new Vec3(1, 0, 0) : flat.normalize();
        for (int across = -16; across <= 16; across += 8) {
            for (int up = -12; up <= 12; up += 6) {
                lamp(level, BlockPos.containing(near.add(side.scale(across)).add(0, up, 0)), placed);
            }
        }
        return placed;
    }

    private static void lamp(ServerLevel level, BlockPos at, List<BlockPos> placed) {
        if (level.getBlockState(at).isAir()) {
            level.setBlock(at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LAMP), Block.UPDATE_ALL);
            placed.add(at.immutable());
        }
    }

    private static void look(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, View view, boolean nightVision) {
        Vec3 d = view.target().subtract(view.eye());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            if (nightVision) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
            } else {
                player.removeEffect(MobEffects.NIGHT_VISION);
            }
            player.teleportTo(
                    level(server), view.eye().x, view.eye().y - EYE, view.eye().z, Set.of(), yaw, pitch, true);
        });
        BlockPos eye = BlockPos.containing(view.eye());
        BlockPos subject = singleplayer.getServer().computeOnServer(server -> subject(level(server), view));
        ClientWait.until(
                context,
                "the view at " + eye.toShortString() + " rendered",
                client -> ready(client, eye, subject, nightVision));
        // tick-wait: the light and the section meshes settle a few ticks after the last change; nothing reports that
        context.waitTicks(40);
        ClientWait.until(
                context,
                "the view at " + eye.toShortString() + " rendered again",
                client -> ready(client, eye, subject, nightVision));
    }

    /** The first block the line of sight meets: the client holds the view once it holds this block. */
    private static BlockPos subject(ServerLevel level, View view) {
        Vec3 step = view.target().subtract(view.eye()).normalize().scale(0.5);
        Vec3 at = view.eye();
        for (int i = 0; i < 128; i++) {
            at = at.add(step);
            BlockPos pos = BlockPos.containing(at);
            if (!level.getBlockState(pos).isAir() && !level.getBlockState(pos).is(Blocks.LIGHT)) {
                return pos;
            }
        }
        throw new AssertionError("nothing in view from " + view.eye() + " towards " + view.target());
    }

    private static boolean ready(Minecraft client, BlockPos eye, BlockPos subject, boolean nightVision) {
        return client.player != null
                && LayerChain.isCampaign(ClientLight.clientLevel(client))
                && client.player.blockPosition().distManhattan(eye) <= 2
                && client.player.hasEffect(MobEffects.NIGHT_VISION) == nightVision
                && !ClientLight.clientLevel(client).getBlockState(subject).isAir()
                && client.levelRenderer.hasRenderedAllSections();
    }

    /**
     * The view down the longest run of open cells along an axis at eye height, from a floor between rows {@code low}
     * and {@code high} within {@link #SEARCH_CHUNKS} of the start, looking along it and a little down. /
    private static View caveView(ServerLevel level, int low, int high) {
        @Nullable BlockPos best = null;
        Direction bestDirection = Direction.SOUTH;
        int bestRun = 0;
        int reach = SEARCH_CHUNKS * 16 - RUN;
        for (int x = X - reach; x <= X + reach; x += 2) {
            for (int z = Z - reach; z <= Z + reach; z += 2) {
                for (int y = low; y <= high; y++) {
                    BlockPos feet = new BlockPos(x, y, z);
                    if (!level.getBlockState(feet).isAir()
                            || !level.getBlockState(feet.above()).isAir()
                            || !level.getBlockState(feet.below()).isSolidRender()
                            || !roofed(level, feet)) {
                        continue;
                    }
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        int run = 0;
                        while (run < RUN
                                && level.getBlockState(feet.above().relative(direction, run + 1))
                                        .isAir()) {
                            run++;
                        }
                        if (run > bestRun) {
                            best = feet;
                            bestDirection = direction;
                            bestRun = run;
                        }
                    }
                }
            }
        }
        if (best == null) {
            throw new AssertionError("no cave floor between y=" + low + " and y=" + high + " near " + X + ", " + Z);
        }
        Vec3 eye = Vec3.atBottomCenterOf(best).add(0, EYE, 0);
        Vec3 ahead = Vec3.atLowerCornerOf(bestDirection.getUnitVec3i()).scale(LOOK);
        return new View(eye, eye.add(ahead).add(0, -1.5, 0));
    }

    /** A cave has a roof: rock within {@value #ROOF} blocks over the feet, so the open room over the rock's top is no
     * cave. */
    private static boolean roofed(ServerLevel level, BlockPos feet) {
        for (int up = 2; up <= ROOF; up++) {
            if (!level.getBlockState(feet.above(up)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** Cuts a room south of the start column across the contact near {@code edge}, so its north face shows the strata
     * either side. */
    private static void cut(ServerLevel level, int edge) {
        RoomCarver.carve(
                level,
                new BlockPos(X - CUT_HALF_WIDTH, edge - CUT_HALF_HEIGHT, Z),
                new BlockPos(X + CUT_HALF_WIDTH, edge + CUT_HALF_HEIGHT, Z + CUT_DEPTH),
                Blocks.AIR.defaultBlockState());
    }

    /** The nearest block of {@code wanted} in layer 1 to the start column, within the search. */
    private static BlockPos nearest(ServerLevel level, Block wanted) {
        CampaignBands.Band band = CampaignBands.LAYER_1;
        @Nullable BlockPos best = null;
        int reach = SEARCH_CHUNKS * 16;
        for (int x = X - reach; x < X + reach; x++) {
            for (int z = Z - reach; z < Z + reach; z++) {
                for (int y = band.minY(); y < band.topExclusive(); y++) {
                    BlockPos at = new BlockPos(x, y, z);
                    if (level.getBlockState(at).is(wanted)
                            && (best == null
                                    || at.distSqr(new BlockPos(X, y, Z))
                                            < best.distSqr(new BlockPos(X, best.getY(), Z)))) {
                        best = at;
                    }
                }
            }
        }
        if (best == null) {
            throw new AssertionError("no " + wanted + " in layer 1 within " + reach + " blocks of " + X + ", " + Z);
        }
        return best;
    }

    /** The shaft's axis column at the pail's row: the middle of the 3 x 3 hollow next to the ladder nearest the pail.
     * */
    private static BlockPos shaftAxis(ServerLevel level, BlockPos pail) {
        for (BlockPos at : BlockPos.betweenClosed(pail.offset(-6, 0, -6), pail.offset(6, 6, 6))) {
            BlockState state = level.getBlockState(at);
            if (state.is(Blocks.LADDER)) {
                Direction facing = state.getValue(LadderBlock.FACING);
                return at.relative(facing).immutable();
            }
        }
        throw new AssertionError("no ladder by the pail at " + pail.toShortString());
    }

    /** A candle niche of the shaft: a prayer card if one is there, else a candle stub, the lowest first. */
    private static BlockPos niche(ServerLevel level, BlockPos axis, BlockPos pail) {
        @Nullable BlockPos candle = null;
        for (int y = pail.getY() + 2; y < pail.getY() + 40; y++) {
            for (BlockPos at : BlockPos.betweenClosed(
                    axis.atY(y).offset(-2, 0, -2), axis.atY(y).offset(2, 0, 2))) {
                BlockState state = level.getBlockState(at);
                if (state.is(block("prayer_card"))) {
                    return at.immutable();
                }
                if (candle == null && state.is(Blocks.CANDLE)) {
                    candle = at.immutable();
                }
            }
        }
        if (candle == null) {
            throw new AssertionError("the shaft at " + axis.toShortString() + " has no niche");
        }
        return candle;
    }

    /** The highest rung of the shaft's ladder: the top of the shaft that is still in the rock. */
    private static int shaftTop(ServerLevel level, BlockPos axis, BlockPos pail) {
        int top = pail.getY();
        for (int y = pail.getY(); y < pail.getY() + 64; y++) {
            for (BlockPos at : BlockPos.betweenClosed(
                    axis.atY(y).offset(-1, 0, -1), axis.atY(y).offset(1, 0, 1))) {
                if (level.getBlockState(at).is(Blocks.LADDER)) {
                    top = y;
                }
            }
        }
        return top;
    }
}
