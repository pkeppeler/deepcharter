package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.model.BakedGeoModel;
import io.github.pkeppeler.deepcharter.client.creature.TallyAnimatable;
import io.github.pkeppeler.deepcharter.client.creature.TallyAssets;
import io.github.pkeppeler.deepcharter.client.creature.TallyRenderState;
import io.github.pkeppeler.deepcharter.client.creature.TallyRenderer;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Tally;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #917: a Tally is drawn by {@link TallyRenderer} with no switch, GeckoLib bakes every bone and the three animations of
 * its files, a whole one has its full shadow, a cutout type and alpha 1 while a broken one coming apart has a smaller shadow, a translucent
 * type and alpha under 1, and a pod's scanner map draws the cell it flies in as ore (not as open space) and as open space again when it is gone.
 */
public class TallyClientTest implements FabricClientGameTest {
    /** The death clock of a Tally half come apart: it takes 20 ticks. */
    private static final int HALF_APART_TICKS = 10;

    private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        boolean shadows =
                context.computeOnClient(client -> client.options.entityShadows().get());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            // The renderer extracts a shadow only when the player's option has entity shadows on.
            context.runOnClient(client -> client.options.entityShadows().set(true));
            server.runCommand("time set midnight");
            context.waitTicks(20); // tick-wait: the night settles before the scanner is read
            ScannerHudTest.rideWithGoldAhead(context, server, 1);
            // A cell of open air in the scanner's row, one ahead of the gold and one before it.
            UUID tally = server.computeOnServer(minecraftServer -> {
                ServerPlayer player =
                        minecraftServer.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.vehicle(player, PodEntity.class);
                BlockPos cell = pod.blockPosition()
                        .above(ScannerHudTest.GOLD_UP)
                        .relative(pod.getDirection(), ScannerHudTest.GOLD_AHEAD - 1);
                // Beside the riding player and gone within the test: never idle 32 blocks from a player (#1237).
                Tally made = TestEntities.create(CreatureRegistry.TALLY, player.level());
                made.setNoAi(true);
                made.setPos(Vec3.atBottomCenterOf(cell));
                player.level().addFreshEntity(made);
                return made.getUUID();
            });
            ClientWait.until(
                    context,
                    "the Tally on the client",
                    client -> client.level != null && clientTally(client, tally) != null);
            require(
                    context.computeOnClient(
                            client -> client.getEntityRenderDispatcher().getRenderer(requireTally(client, tally))
                                    instanceof TallyRenderer),
                    "the Tally should be drawn by TallyRenderer");
            context.runOnClient(TallyClientTest::bakedBonesAndAnimations);
            context.waitTick(); // tick-wait: one frame draws the posed model; nothing else is awaited
            context.runOnClient(client -> require(
                    drawnWhole(client, tally).isEmpty(), "a whole Tally is drawn wrong: " + drawnWhole(client, tally)));
            context.runOnClient(client -> {
                requireTally(client, tally).deathTime = HALF_APART_TICKS;
                require(
                        drawnComingApart(client, tally).isEmpty(),
                        "a Tally coming apart is drawn wrong: " + drawnComingApart(client, tally));
                requireTally(client, tally).deathTime = 0;
            });

            context.waitTicks(2 * TUNING.rescanTicks() + 2); // tick-wait: the HUD rescans on its own interval
            HudShot with = HudShot.take(context, "tally-echo", ScannerHudTest.TIER_ONE);
            require(
                    !with.isOpenSpace(ScannerHudTest.GOLD_AHEAD - 1, ScannerHudTest.GOLD_UP),
                    "the cell the Tally flies in should draw as ore on the scanner map");
            require(
                    with.isOpenSpace(ScannerHudTest.GOLD_AHEAD + 1, ScannerHudTest.GOLD_UP),
                    "the open cell beside it should stay open space: the echo is the Tally's cell only");
            server.runOnServer(minecraftServer -> {
                ServerPlayer player =
                        minecraftServer.getPlayerList().getPlayers().getFirst();
                TestEntities.require(player.level(), tally, Tally.class).discard();
            });
            ClientWait.until(context, "the Tally gone from the client", client -> clientTally(client, tally) == null);
            context.waitTicks(2 * TUNING.rescanTicks() + 2); // tick-wait: the HUD rescans on its own interval
            HudShot without = HudShot.take(context, "tally-echo-gone", ScannerHudTest.TIER_ONE);
            require(
                    without.isOpenSpace(ScannerHudTest.GOLD_AHEAD - 1, ScannerHudTest.GOLD_UP),
                    "with the Tally gone the cell should be open space again");
        } finally {
            context.runOnClient(client -> client.options.entityShadows().set(shadows));
        }
    }

    private static @Nullable Tally clientTally(Minecraft client, UUID id) {
        return ClientLight.clientLevel(client)
                .getEntities(
                        CreatureRegistry.TALLY,
                        new AABB(Objects.requireNonNull(client.player, "in a world")
                                        .blockPosition())
                                .inflate(32),
                        tally -> tally.getUUID().equals(id))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static Tally requireTally(Minecraft client, UUID id) {
        return Objects.requireNonNull(clientTally(client, id), "the Tally is not on the client");
    }

    /** The state as a frame builds it: the dispatcher extracts the renderer's state, then the shadow, which asks the renderer for its radius. */
    private static TallyRenderState extract(Minecraft client, Tally tally) {
        return (TallyRenderState) client.getEntityRenderDispatcher().extractEntity(tally, 1f);
    }

    /** What is wrong with the way a whole Tally (fade 0) is drawn. */
    private static List<String> drawnWhole(Minecraft client, UUID id) {
        Tally tally = requireTally(client, id);
        TallyRenderer renderer =
                (TallyRenderer) client.getEntityRenderDispatcher().getRenderer(tally);
        TallyRenderState state = extract(client, tally);
        List<String> wrong = new ArrayList<>();
        if (state.fade != 0f) {
            wrong.add("fade " + state.fade + " is not 0");
        }
        if (state.shadowRadius != TallyRenderer.SHADOW_RADIUS) {
            wrong.add("shadow radius " + state.shadowRadius + " is not the full " + TallyRenderer.SHADOW_RADIUS);
        }
        if (RenderTypes.entityTranslucent(TallyAssets.texture())
                .equals(renderer.getRenderType(state, TallyAssets.texture()))) {
            wrong.add("the render type is translucent");
        }
        int alpha = ARGB.alpha(renderer.getRenderColor(new TallyAnimatable(), tally, 1f));
        if (alpha != 255) {
            wrong.add("alpha " + alpha + " is not 255");
        }
        return wrong;
    }

    /** What is wrong with the way a Tally coming apart (fade above 0) is drawn. */
    private static List<String> drawnComingApart(Minecraft client, UUID id) {
        Tally tally = requireTally(client, id);
        TallyRenderer renderer =
                (TallyRenderer) client.getEntityRenderDispatcher().getRenderer(tally);
        TallyRenderState state = extract(client, tally);
        List<String> wrong = new ArrayList<>();
        if (state.fade <= 0f || state.fade >= 1f) {
            wrong.add("fade " + state.fade + " is not part way");
        }
        if (state.shadowRadius >= TallyRenderer.SHADOW_RADIUS) {
            wrong.add("shadow radius " + state.shadowRadius + " did not shrink from " + TallyRenderer.SHADOW_RADIUS);
        }
        if (!RenderTypes.entityTranslucent(TallyAssets.texture())
                .equals(renderer.getRenderType(state, TallyAssets.texture()))) {
            wrong.add("the render type is not translucent");
        }
        int alpha = ARGB.alpha(renderer.getRenderColor(new TallyAnimatable(), tally, 1f));
        if (alpha >= 255) {
            wrong.add("alpha " + alpha + " is not under 255");
        }
        return wrong;
    }

    /** GeckoLib baked the model with every bone its file names, and loaded its three animations. */
    private static void bakedBonesAndAnimations(Minecraft client) {
        BakedGeoModel baked = GeckoLibResources.getBakedModels().getModel(TallyAssets.resource());
        require(
                !baked.isMissingno(),
                "GeckoLib did not bake " + TallyAssets.resource() + ": it draws its missing-model cube");
        List<String> missing = new ArrayList<>();
        for (String bone : List.of("root", "body", "dome", "wheel", "lens", "clamp_l", "clamp_r")) {
            if (baked.getBone(bone).isEmpty()) {
                missing.add(bone);
            }
        }
        require(missing.isEmpty(), "GeckoLib's " + TallyAssets.resource() + " lacks the bones " + missing);
        for (String animation : List.of(TallyAssets.IDLE, TallyAssets.FLY, TallyAssets.CLAMP)) {
            require(
                    GeckoLibResources.getBakedAnimations().cache().containsKey(TallyAssets.resource())
                            && GeckoLibResources.getBakedAnimations()
                                    .cache()
                                    .get(TallyAssets.resource())
                                    .animations()
                                    .containsKey(animation),
                    "GeckoLib did not load the animation " + animation + " of " + TallyAssets.resource());
        }
    }
}
