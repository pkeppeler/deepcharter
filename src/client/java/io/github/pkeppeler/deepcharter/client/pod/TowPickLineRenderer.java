package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Shows the first click of a tow cable (#1605): the tow line's chain from the pod the person picked to the person, drawn like the
 * tether's line ({@link TetherLineRenderer}) so a person sees which pod holds the cable before they right-click the second. The pick is
 * the attachment {@link PodTowing#PICK}, synced to the person alone, so this draws for the local player only and keeps no state of
 * its own.
 */
public final class TowPickLineRenderer {
    private static final Map<Player, TowChainRenderer.Chain> CHAINS = new WeakHashMap<>();
    private static final List<PodEntity> PODS = new ArrayList<>();

    private TowPickLineRenderer() {}

    public static void init() {
        LevelRenderEvents.COLLECT_SUBMITS.register(TowPickLineRenderer::submit);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CHAINS.clear());
    }

    private static void submit(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        Player person = client.player;
        if (level == null || person == null || PodTowing.pickedPodId(person).isEmpty()) {
            return;
        }
        PODS.clear();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof PodEntity pod && !pod.isRemoved()) {
                PODS.add(pod);
            }
        }
        var camera = context.levelState().cameraRenderState.pos;
        TowChainRenderer.Chain chain =
                chainFor(level, person, PODS, context.levelState().worldPartialTicks, camera.x, camera.y, camera.z);
        if (chain != null) {
            context.submitNodeCollector()
                    .submitCustomGeometry(context.poseStack(), TowChainRenderer.renderType(), chain);
        }
    }

    /** The line from the pod {@code person} picked to them, built for this frame, or null when they picked none or it is not among {@code pods}. */
    public static TowChainRenderer.@Nullable Chain chainFor(
            ClientLevel level,
            Player person,
            List<PodEntity> pods,
            float partial,
            double cameraX,
            double cameraY,
            double cameraZ) {
        UUID podId = PodTowing.pickedPodId(person).orElse(null);
        if (podId == null) {
            return null;
        }
        return TetherLineRenderer.chainBetween(CHAINS, level, person, podId, pods, partial, cameraX, cameraY, cameraZ);
    }
}
