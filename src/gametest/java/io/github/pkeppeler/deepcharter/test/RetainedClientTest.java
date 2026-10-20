package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.model.BakedGeoModel;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureAssets;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderState;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderer;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest for #449 and #506: a figure whose lamp is lit draws the dim lamp in its helmet (a one-texel-deep lens in the lamp bracket of
 * the baked model, and a glowmask that is clear except on that lens, dim and amber), a figure whose lamp is not lit does not, and a figure that
 * holds a pod's hull is drawn in the cling pose, with its animation loaded, and its body turned toward the pod.
 */
public class RetainedClientTest implements FabricClientGameTest {
    private static final int HALF = 7;
    private static final int APPROACH = 6;
    private static final String LANTERN = "lantern";
    private static final String BRACKET = "lamp_bracket";
    /** The lens is at most this many texels: the hole of the bracket, not a floodlight. */
    private static final int MAX_LENS_TEXELS = 8;
    /** A dim lamp: no lit texel of the glowmask is brighter than this (0 to 255 of the larger channel), and none is darker than the least. */
    private static final int MAX_GLOW = 200;

    private static final int MIN_GLOW = 60;
    /** The cosine of the angle between the way a clinging figure looks and the way to the pod's centre: 0.97 is 14 degrees. */
    private static final double FACING_COSINE_MIN = 0.97;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            int[] ids = {0, 0};
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                ids[0] = spawn(player, true, 4).getId();
                ids[1] = spawn(player, false, -4).getId();
            });
            ClientWait.until(
                    context,
                    "both figures on the client",
                    client -> client.level != null
                            && client.level.getEntity(ids[0]) instanceof LamplessFigure lit
                            && lit.hasLamp()
                            && client.level.getEntity(ids[1]) instanceof LamplessFigure);
            context.runOnClient(client -> expectTheHelmetLight(client, ids[0], ids[1]));
            expectTheClingPose(context, singleplayer, ids);
        }
    }

    /** One model draws both figures; its lens sits in the helmet's bracket; its glowmask lights only the lens, dimly; the render state says whether the lamp is lit. */
    private static void expectTheHelmetLight(Minecraft client, int litId, int unlitId) {
        EntityRenderer<?, ?> renderer = client.getEntityRenderDispatcher()
                .getRenderer(TestEntities.require(ClientLight.clientLevel(client), litId));
        var model = ((LamplessFigureRenderer) renderer).getGeoModel();
        LamplessFigureRenderState lit = state(client, litId);
        LamplessFigureRenderState unlit = state(client, unlitId);
        require(
                lit.lamp && !unlit.lamp,
                "the render state does not carry whether the lamp is lit: lit " + lit.lamp + ", unlit " + unlit.lamp);
        for (LamplessFigureRenderState figure : List.of(lit, unlit)) {
            require(
                    model.getModelResource(figure).equals(LamplessFigureAssets.resource())
                            && model.getTextureResource(figure).equals(LamplessFigureAssets.texture()),
                    "a figure is not drawn from the one model and texture");
        }
        BakedGeoModel baked = GeckoLibResources.getBakedModels().getModel(LamplessFigureAssets.resource());
        require(!baked.isMissingno(), "GeckoLib did not bake the model " + LamplessFigureAssets.resource());
        require(baked.getBone(BRACKET).isPresent(), "the figure has no lamp bracket on its helmet");
        var lantern = baked.getBone(LANTERN);
        require(
                lantern.isPresent()
                        && lantern.get().parent() != null
                        && BRACKET.equals(lantern.get().parent().name()),
                "the lamp is not in the helmet's bracket");
        require(
                renderer.getClass() == LamplessFigureRenderer.class
                        && ((LamplessFigureRenderer) renderer)
                                .getRenderLayers().stream()
                                        .anyMatch(layer ->
                                                layer.getClass().getSimpleName().equals("LamplessFigureGlowLayer")),
                "the figure's renderer draws no glow layer for the lamp");
        expectADimLens(client);
        require(
                Objects.requireNonNull(
                                GeckoLibResources.getBakedAnimations().cache().get(LamplessFigureAssets.resource()),
                                "GeckoLib did not bake the figure's animations")
                        .animations()
                        .containsKey(LamplessFigureAssets.CLING),
                "GeckoLib did not load the cling animation");
    }

    /** The glowmask is clear except on a few texels, all dim amber: it shows from far in the dark and does not read as a beacon. */
    private static void expectADimLens(Minecraft client) {
        try (var stream = client.getResourceManager()
                        .getResourceOrThrow(LamplessFigureAssets.glowmask())
                        .open();
                NativeImage glow = NativeImage.read(stream)) {
            int lit = 0;
            for (int y = 0; y < glow.getHeight(); y++) {
                for (int x = 0; x < glow.getWidth(); x++) {
                    int argb = glow.getPixel(x, y);
                    if (ARGB.alpha(argb) == 0) {
                        continue;
                    }
                    lit++;
                    int brightest = Math.max(ARGB.red(argb), Math.max(ARGB.green(argb), ARGB.blue(argb)));
                    require(
                            brightest >= MIN_GLOW && brightest <= MAX_GLOW,
                            "the glowmask has a texel at " + x + "," + y + " with brightness " + brightest
                                    + ", not a dim lamp");
                    require(
                            ARGB.red(argb) > ARGB.blue(argb),
                            "the glowmask has a texel at " + x + "," + y + " that is not warm");
                }
            }
            require(lit > 0 && lit <= MAX_LENS_TEXELS * 6, "the glowmask lights " + lit + " texels, not a tiny lens");
        } catch (IOException e) {
            throw new AssertionError("could not read the glowmask", e);
        }
    }

    private static LamplessFigureRenderState state(Minecraft client, int id) {
        return (LamplessFigureRenderState) client.getEntityRenderDispatcher()
                .extractEntity(TestEntities.require(ClientLight.clientLevel(client), id), 1f);
    }

    private static LamplessFigure spawn(ServerPlayer player, boolean lamp, int dz) {
        ServerLevel level = player.level();
        LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
        figure.setNoAi(true);
        figure.setLamp(lamp);
        figure.setPos(player.position().add(0, 0, dz));
        level.addFreshEntity(figure);
        return figure;
    }

    /** A pilot in a dark pod on a flat floor, and a figure that walks to the hull: its client copy holds the hull and is drawn in the cling pose. */
    private static void expectTheClingPose(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, int[] ids) {
        int[] clinging = {0};
        int[] pods = {0};
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            ServerLevel level = player.level();
            TestEntities.require(level, ids[0], LamplessFigure.class).discard();
            TestEntities.require(level, ids[1], LamplessFigure.class).discard();
            player.setGameMode(GameType.SURVIVAL);
            BlockPos origin = player.blockPosition().above(60);
            for (int u = -HALF - 1; u <= HALF + 1; u++) {
                for (int v = -HALF - 1; v <= HALF + 1; v++) {
                    for (int y = -1; y <= 6; y++) {
                        boolean shell = y == -1 || y == 6 || Math.abs(u) == HALF + 1 || Math.abs(v) == HALF + 1;
                        // room-carver: a closed arena of its own stone shell high over the overworld's test spawn, not
                        // layer rock
                        level.setBlock(
                                origin.offset(u, y, v), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
                    }
                }
            }
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(Vec3.atBottomCenterOf(origin.east(APPROACH)));
            level.addFreshEntity(pod);
            player.teleportTo(level, pod.getX(), pod.getY(), pod.getZ(), Set.of(), 0f, 0f, true);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the player could not mount the pod");
            }
            LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
            figure.setPos(Vec3.atBottomCenterOf(origin.west(APPROACH)));
            level.addFreshEntity(figure);
            clinging[0] = figure.getId();
            pods[0] = pod.getId();
        });
        ClientWait.until(
                context,
                "the figure clinging to the pod on the client",
                client -> client.level != null
                        && client.level.getEntity(clinging[0]) instanceof LamplessFigure figure
                        && figure.isClinging());
        context.runOnClient(client ->
                require(state(client, clinging[0]).clinging, "a clinging figure's render state is not clinging"));
        ClientWait.until(
                context,
                "the clinging figure turned to face the pod on the client",
                client -> faces(client, clinging[0], pods[0]));
    }

    /** Whether the client's copy of the figure looks at the pod's centre with its body and its head: what is drawn is what the server holds. */
    private static boolean faces(Minecraft client, int figureId, int podId) {
        if (!(ClientLight.clientLevel(client).getEntity(figureId) instanceof LamplessFigure figure)
                || ClientLight.clientLevel(client).getEntity(podId) == null) {
            return false;
        }
        Vec3 toPod = TestEntities.require(ClientLight.clientLevel(client), podId)
                .position()
                .subtract(figure.position())
                .multiply(1, 0, 1)
                .normalize();
        return Vec3.directionFromRotation(0, figure.yBodyRot).dot(toPod) >= FACING_COSINE_MIN
                && Vec3.directionFromRotation(0, figure.getYHeadRot()).dot(toPod) >= FACING_COSINE_MIN;
    }
}
