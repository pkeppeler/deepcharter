package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import io.github.pkeppeler.deepcharter.client.creature.DrifterAssets;
import io.github.pkeppeler.deepcharter.client.creature.DrifterRenderState;
import io.github.pkeppeler.deepcharter.client.creature.DrifterRenderer;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import io.github.pkeppeler.deepcharter.creature.DrifterVariant;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #1204: every form of a Drifter is drawn by {@link DrifterRenderer} from the files of its own,
 * GeckoLib bakes every bone and both animations of each, each form is drawn with the translucent type and the glow
 * layer, and the form the server gives a drifter is the one its client copy draws, also when the server changes it.
 */
public class DrifterClientTest implements FabricClientGameTest {
    private static final double SPREAD_BLOCKS = 4;
    private static final double AHEAD_BLOCKS = 6;
    private static final double UP_BLOCKS = 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        context.runOnClient(DrifterClientTest::bakedBonesAndAnimations);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            Map<DrifterVariant, UUID> made = new EnumMap<>(DrifterVariant.class);
            Function<DrifterVariant, UUID> idOf =
                    variant -> Objects.requireNonNull(made.get(variant), "a drifter was made for each form");
            server.runOnServer(minecraftServer -> {
                ServerPlayer player =
                        minecraftServer.getPlayerList().getPlayers().getFirst();
                Vec3 centre = player.position().add(0, UP_BLOCKS, AHEAD_BLOCKS);
                for (DrifterVariant variant : DrifterVariant.values()) {
                    Drifter drifter = TestEntities.create(CreatureRegistry.DRIFTER, player.level());
                    drifter.setNoAi(true);
                    drifter.setNoGravity(true);
                    drifter.setVariant(variant);
                    drifter.setPos(centre.add((variant.ordinal() - 1) * SPREAD_BLOCKS, 0, 0));
                    player.level().addFreshEntity(drifter);
                    made.put(variant, drifter.getUUID());
                }
            });
            ClientWait.until(
                    context,
                    "the three drifters on the client",
                    client -> client.level != null
                            && made.values().stream().allMatch(id -> clientDrifter(client, id) != null));
            context.waitTick(); // tick-wait: one frame draws the posed models; nothing else is awaited
            for (DrifterVariant variant : DrifterVariant.values()) {
                context.runOnClient(client -> {
                    Drifter drifter = requireDrifter(client, idOf.apply(variant));
                    require(
                            drifter.variant() == variant,
                            "the server made a " + variant + " and the client copy is a " + drifter.variant());
                    require(
                            drawnRight(client, drifter, variant).isEmpty(),
                            drawnRight(client, drifter, variant).toString());
                });
            }
            // The server changes a form: the client copy follows, and is drawn from the new form's files.
            server.runOnServer(minecraftServer -> TestEntities.require(
                            minecraftServer
                                    .getPlayerList()
                                    .getPlayers()
                                    .getFirst()
                                    .level(),
                            idOf.apply(DrifterVariant.JELLYFISH),
                            Drifter.class)
                    .setVariant(DrifterVariant.CHAIN));
            ClientWait.until(
                    context,
                    "the changed form on the client",
                    client -> requireDrifter(client, idOf.apply(DrifterVariant.JELLYFISH))
                                    .variant()
                            == DrifterVariant.CHAIN);
            context.runOnClient(client -> require(
                    drawnRight(
                                    client,
                                    requireDrifter(client, idOf.apply(DrifterVariant.JELLYFISH)),
                                    DrifterVariant.CHAIN)
                            .isEmpty(),
                    "a changed drifter is drawn wrong"));
        }
    }

    private static @Nullable Drifter clientDrifter(Minecraft client, UUID id) {
        return ClientLight.clientLevel(client)
                .getEntities(
                        CreatureRegistry.DRIFTER,
                        new AABB(Objects.requireNonNull(client.player, "in a world")
                                        .blockPosition())
                                .inflate(32),
                        drifter -> drifter.getUUID().equals(id))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static Drifter requireDrifter(Minecraft client, UUID id) {
        return Objects.requireNonNull(clientDrifter(client, id), "the drifter is not on the client");
    }

    /**
     * What is wrong with the way a drifter of {@code variant} is drawn: its renderer, its form in the state, its files,
     * the translucent type and the glow layer.
     */
    private static List<String> drawnRight(Minecraft client, Drifter drifter, DrifterVariant variant) {
        List<String> wrong = new ArrayList<>();
        if (!(client.getEntityRenderDispatcher().getRenderer(drifter) instanceof DrifterRenderer renderer)) {
            wrong.add("the " + variant + " is not drawn by DrifterRenderer");
            return wrong;
        }
        DrifterRenderState state =
                (DrifterRenderState) client.getEntityRenderDispatcher().extractEntity(drifter, 1f);
        if (state.variant != variant) {
            wrong.add("the render state has the " + state.variant + ", not the " + variant);
        }
        if (!RenderTypes.entityTranslucent(DrifterAssets.texture(variant))
                .equals(renderer.getRenderType(state, DrifterAssets.texture(variant)))) {
            wrong.add("the " + variant + " is not drawn with the translucent render type");
        }
        if (renderer.getRenderLayers().stream().noneMatch(AutoGlowingGeoLayer.class::isInstance)) {
            wrong.add("the " + variant + " has no glow layer");
        }
        if (client.getResourceManager()
                .getResource(DrifterAssets.glowmask(variant))
                .isEmpty()) {
            wrong.add("the " + variant + " has no glowmask file");
        }
        return wrong;
    }

    /** The bones each form's file names, which GeckoLib must bake and the animations move. */
    private static List<String> bonesOf(DrifterVariant variant) {
        return switch (variant) {
            case JELLYFISH ->
                Stream.of(
                                Stream.of("root", "body", "bell", "core"),
                                IntStream.range(0, 4).mapToObj(i -> "arm_" + i),
                                IntStream.range(0, 6)
                                        .boxed()
                                        .flatMap(i -> Stream.of("a", "b", "c")
                                                .map(segment -> "tendril_" + i + "_" + segment)))
                        .flatMap(stream -> stream)
                        .toList();
            case SAC ->
                Stream.of(
                                Stream.of("root", "body", "gills"),
                                IntStream.range(0, 3)
                                        .boxed()
                                        .flatMap(
                                                i -> Stream.of("a", "b").map(segment -> "thread_" + i + "_" + segment)))
                        .flatMap(stream -> stream)
                        .toList();
            case CHAIN ->
                Stream.of(Stream.of("root", "veil"), IntStream.range(0, 8).mapToObj(i -> "bulb_" + i))
                        .flatMap(stream -> stream)
                        .toList();
        };
    }

    /** GeckoLib baked each model with every bone its file names, and loaded its idle and drawn animations. */
    private static void bakedBonesAndAnimations(Minecraft client) {
        for (DrifterVariant variant : DrifterVariant.values()) {
            BakedGeoModel baked = GeckoLibResources.getBakedModels().getModel(DrifterAssets.resource(variant));
            require(
                    !baked.isMissingno(),
                    "GeckoLib did not bake " + DrifterAssets.resource(variant) + ": it draws its missing-model cube");
            List<String> missing = bonesOf(variant).stream()
                    .filter(bone -> baked.getBone(bone).isEmpty())
                    .toList();
            require(missing.isEmpty(), "GeckoLib's " + DrifterAssets.resource(variant) + " lacks the bones " + missing);
            Identifier resource = DrifterAssets.resource(variant);
            for (String animation : List.of(DrifterAssets.idle(variant), DrifterAssets.drawn(variant))) {
                var held = GeckoLibResources.getBakedAnimations().cache().get(resource);
                require(
                        held != null && held.animations().containsKey(animation),
                        "GeckoLib did not load the animation " + animation + " of " + resource);
            }
        }
    }
}
