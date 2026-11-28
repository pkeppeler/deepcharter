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
import net.minecraft.world.phys.Vec3;
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
        Vec3 middle = new Vec3(
                Mth.lerp(partial, person.xo, person.getX()),
                Mth.lerp(partial, person.yo, person.getY()) + person.getBbHeight() / 2.0,
                Mth.lerp(partial, person.zo, person.getZ()));
        return chainBetween(
                CHAINS,
                person,
                TowChainLook.current(),
                level,
                podId,
                pods,
                partial,
                new Vec3(cameraX, cameraY, cameraZ),
                middle,
                person.getBbWidth() / 2.0);
    }

    /**
     * The chain from the pod {@code podId} to {@code end}, kept in {@code chains} for {@code owner} (one cache for each
     * kind of line, so two lines to one person do not share a curve), drawn in {@code look}. Null when the pod is not
     * among {@code pods} or {@code end} is farther from the camera than {@link #DRAW_DISTANCE}. The tow cable's pick
     * line ({@link TowPickLineRenderer}) is this line too.
     *
     * @param endRadius blocks round {@code end} that the chain stops short by
     */
    static TowChainRenderer.@Nullable Chain chainBetween(
            Map<Player, TowChainRenderer.Chain> chains,
            Player owner,
            TowChainLook look,
            ClientLevel level,
            UUID podId,
            List<PodEntity> pods,
            float partial,
            Vec3 camera,
            Vec3 end,
            double endRadius) {
        PodEntity pod = null;
        for (PodEntity other : pods) {
            if (other.getUUID().equals(podId)) {
                pod = other;
                break;
            }
        }
        if (pod == null || end.distanceToSqr(camera) > DRAW_DISTANCE * DRAW_DISTANCE) {
            return null;
        }
        double podX = Mth.lerp(partial, pod.xo, pod.getX());
        double podY = Mth.lerp(partial, pod.yo, pod.getY()) + pod.getBbHeight() / 2.0;
        double podZ = Mth.lerp(partial, pod.zo, pod.getZ());
        TowChainRenderer.Chain chain = chains.computeIfAbsent(owner, key -> new TowChainRenderer.Chain());
        chain.look = look;
        chain.cameraX = camera.x;
        chain.cameraY = camera.y;
        chain.cameraZ = camera.z;
        double radius = pod.chassis().width() / 2.0;
        double between = Math.sqrt(
                (end.x - podX) * (end.x - podX) + (end.y - podY) * (end.y - podY) + (end.z - podZ) * (end.z - podZ));
        double chord = Math.max(0.0, between - radius - endRadius);
        double rope = Math.min(TetherTuning.DEFAULT.length(), chord * (1.0 + SAG_SHARE) + SAG_BASE);
        chain.curve.build(podX, podY, podZ, radius, end.x, end.y, end.z, endRadius, rope, look.linkLength());
        TowChainRenderer.light(level, chain, look);
        return chain;
    }
}
