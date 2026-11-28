package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.client.theme.TowChainLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodTether;
import io.github.pkeppeler.deepcharter.pod.TetherTuning;
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
import org.jspecify.annotations.Nullable;

/**
 * Draws the tether (#986): the tow line's chain from a pod's winch to the person clipped to it, in the tow line's own look
 * ({@link TowChainRenderer}, the {@code tow} theme area). The clip is the synced attachment on the person ({@link PodTether#STATE}), so
 * every client that sees the person sees the line, and the line needs no state of its own: each frame it reads the two places and builds
 * the curve. A line drawn for a pod that is not loaded here is not drawn.
 */
public final class TetherLineRenderer {
    /** Blocks from the camera beyond which a line is not drawn. */
    private static final double DRAW_DISTANCE = 160.0;
    /** Blocks that the line is longer than the gap it spans while slack: a slight sag, so a person standing by the pod is not shown a rope hanging to the floor. */
    private static final double SAG_BASE = 0.4;

    private static final double SAG_SHARE = 0.06;

    private static final Map<Player, TowChainRenderer.Chain> CHAINS = new WeakHashMap<>();
    private static final List<PodEntity> PODS = new ArrayList<>();
    private static final List<Player> PEOPLE = new ArrayList<>();

    private TetherLineRenderer() {}

    public static void init() {
        LevelRenderEvents.COLLECT_SUBMITS.register(TetherLineRenderer::submit);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CHAINS.clear());
    }

    private static void submit(LevelRenderContext context) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        PODS.clear();
        PEOPLE.clear();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof PodEntity pod && !pod.isRemoved()) {
                PODS.add(pod);
            } else if (entity instanceof Player person && !person.isRemoved() && PodTether.isClipped(person)) {
                PEOPLE.add(person);
            }
        }
        var camera = context.levelState().cameraRenderState.pos;
        float partial = context.levelState().worldPartialTicks;
        for (Player person : PEOPLE) {
            TowChainRenderer.Chain chain = chainFor(level, person, PODS, partial, camera.x, camera.y, camera.z);
            if (chain != null) {
                context.submitNodeCollector()
                        .submitCustomGeometry(context.poseStack(), TowChainRenderer.renderType(), chain);
            }
        }
    }

    /**
     * The line of {@code person}, built for this frame, or null when they are not clipped, the pod they are clipped to is not among
     * {@code pods} (not loaded here, so the line has no far end), or the line is farther from the camera than {@link #DRAW_DISTANCE}.
     */
    public static TowChainRenderer.@Nullable Chain chainFor(
            ClientLevel level,
            Player person,
            List<PodEntity> pods,
            float partial,
            double cameraX,
            double cameraY,
            double cameraZ) {
        UUID podId = PodTether.podId(person).orElse(null);
        if (podId == null) {
            return null;
        }
        return chainBetween(CHAINS, level, person, podId, pods, partial, cameraX, cameraY, cameraZ);
    }

    /**
     * The chain from the pod {@code podId} to {@code person}, kept in {@code chains} (one cache for each kind of line, so two lines to
     * one person do not share a curve), or null when the pod is not among {@code pods} or the person is farther from the camera than
     * {@link #DRAW_DISTANCE}. The tow cable's pick line ({@link TowPickLineRenderer}) is this line too.
     */
    static TowChainRenderer.@Nullable Chain chainBetween(
            Map<Player, TowChainRenderer.Chain> chains,
            ClientLevel level,
            Player person,
            UUID podId,
            List<PodEntity> pods,
            float partial,
            double cameraX,
            double cameraY,
            double cameraZ) {
        PodEntity pod = null;
        for (PodEntity other : pods) {
            if (other.getUUID().equals(podId)) {
                pod = other;
                break;
            }
        }
        if (pod == null) {
            return null;
        }
        double podX = Mth.lerp(partial, pod.xo, pod.getX());
        double podY = Mth.lerp(partial, pod.yo, pod.getY()) + pod.getBbHeight() / 2.0;
        double podZ = Mth.lerp(partial, pod.zo, pod.getZ());
        double personX = Mth.lerp(partial, person.xo, person.getX());
        double personY = Mth.lerp(partial, person.yo, person.getY()) + person.getBbHeight() / 2.0;
        double personZ = Mth.lerp(partial, person.zo, person.getZ());
        double distanceX = personX - cameraX;
        double distanceY = personY - cameraY;
        double distanceZ = personZ - cameraZ;
        if (distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ > DRAW_DISTANCE * DRAW_DISTANCE) {
            return null;
        }
        TowChainRenderer.Chain chain = chains.computeIfAbsent(person, key -> new TowChainRenderer.Chain());
        TowChainLook look = TowChainLook.current();
        chain.look = look;
        chain.cameraX = cameraX;
        chain.cameraY = cameraY;
        chain.cameraZ = cameraZ;
        double radius = pod.chassis().width() / 2.0;
        double between = Math.sqrt((personX - podX) * (personX - podX)
                + (personY - podY) * (personY - podY)
                + (personZ - podZ) * (personZ - podZ));
        double chord = Math.max(0.0, between - radius - person.getBbWidth() / 2.0);
        double rope = Math.min(TetherTuning.DEFAULT.length(), chord * (1.0 + SAG_SHARE) + SAG_BASE);
        chain.curve.build(
                podX,
                podY,
                podZ,
                radius,
                personX,
                personY,
                personZ,
                person.getBbWidth() / 2.0,
                rope,
                look.linkLength());
        TowChainRenderer.light(level, chain, look);
        return chain;
    }
}
