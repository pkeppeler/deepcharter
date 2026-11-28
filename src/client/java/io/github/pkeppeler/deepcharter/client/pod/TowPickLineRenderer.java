package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.client.theme.TowChainLook;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Shows the first click of a tow cable (#1605): the tow line's chain from the pod the person picked to the person,
 * drawn like the tether's line ({@link TetherLineRenderer}) so a person sees which pod holds the cable before they
 * right-click the second. The pick is the attachment {@link PodTowing#PICK}, synced to the person alone, so this draws
 * for the local player only and keeps no state of its own.
 */
public final class TowPickLineRenderer {
    private static final Map<Player, TowChainRenderer.Chain> CHAINS = new WeakHashMap<>();
    private static final List<PodEntity> PODS = new ArrayList<>();

    /** Blocks ahead of the eyes, and below them, that the line ends at: where the held cable is. */
    private static final double HAND_AHEAD = 0.8;

    private static final double HAND_BELOW = 0.45;

    private static final double HAND_ASIDE = 0.4;
    /** How much wider than the tow line a link of the pick line is. */
    private static final double BOLD = 3.0;

    private static final int FULL_LIGHT = 15;

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

    /**
     * The line from the pod {@code person} picked to them, built for this frame, or null when they picked none or it is
     * not among {@code pods}.
     */
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
        // The line ends at the person's hands, ahead of and below the eyes: from the seat of the camera, a line to the
        // person's middle would run behind it and never show.
        Vec3 eyes = new Vec3(
                Mth.lerp(partial, person.xo, person.getX()),
                Mth.lerp(partial, person.yo, person.getY()) + person.getEyeHeight(),
                Mth.lerp(partial, person.zo, person.getZ()));
        Vec3 look = person.getViewVector(partial);
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize().scale(HAND_ASIDE);
        Vec3 hands = eyes.add(look.scale(HAND_AHEAD)).add(right).add(0, -HAND_BELOW, 0);
        TowChainLook plain = TowChainLook.current();
        // Thicker and fully lit, so the picked pod reads at a glance, in a dark layer too.
        TowChainLook bold = new TowChainLook(
                plain.linkLength(), plain.linkWidth() * BOLD, plain.linkOverlap(), plain.tint(), FULL_LIGHT);
        return TetherLineRenderer.chainBetween(
                CHAINS, person, bold, level, podId, pods, partial, new Vec3(cameraX, cameraY, cameraZ), hands, 0.0);
    }
}
