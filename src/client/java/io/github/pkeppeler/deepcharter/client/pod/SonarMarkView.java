package io.github.pkeppeler.deepcharter.client.pod;

import net.minecraft.world.phys.Vec3;

/**
 * Where a mark lands on the HUD (#988): in the view where it is, or, when it is off screen or behind, on the edge of the screen toward it, so the
 * pilot always knows which way to turn.
 */
public final class SonarMarkView {
    private SonarMarkView() {}

    /**
     * A mark's place.
     *
     * @param x GUI pixels from the left
     * @param y GUI pixels from the top
     * @param onScreen true when the mark is in view, false when the point is the edge of the screen toward it
     */
    public record Spot(int x, int y, boolean onScreen) {}

    /**
     * Projects {@code target} for a camera at {@code eye} looking along {@code yawDegrees} and {@code pitchDegrees} (the game's: yaw 0 faces +z,
     * positive pitch looks down), through a vertical field of view of {@code fovDegrees}, onto a {@code width} x {@code height} GUI with
     * {@code margin} pixels kept clear at the edge.
     */
    public static Spot project(
            Vec3 eye,
            float yawDegrees,
            float pitchDegrees,
            Vec3 target,
            double fovDegrees,
            int width,
            int height,
            int margin) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(forward);
        Vec3 to = target.subtract(eye);
        double sideways = to.dot(right);
        double rise = to.dot(up);
        double ahead = to.dot(forward);
        double tanY = Math.tan(Math.toRadians(fovDegrees) / 2);
        double tanX = tanY * width / height;
        double ndcX;
        double ndcY;
        boolean inFront = ahead > 1e-6;
        if (inFront) {
            ndcX = sideways / (ahead * tanX);
            ndcY = rise / (ahead * tanY);
        } else {
            // Behind or abeam: only the direction round the view counts, so take it as the screen direction and push it
            // to the edge.
            ndcX = sideways;
            ndcY = rise == 0 && sideways == 0 ? -1 : rise;
            double along = Math.max(Math.abs(ndcX), Math.abs(ndcY));
            ndcX /= along;
            ndcY /= along;
        }
        boolean onScreen = inFront && Math.abs(ndcX) <= 1 && Math.abs(ndcY) <= 1;
        if (!onScreen) {
            double along = Math.max(Math.abs(ndcX), Math.abs(ndcY));
            ndcX /= along;
            ndcY /= along;
        }
        double halfWidth = width / 2.0 - (onScreen ? 0 : margin);
        double halfHeight = height / 2.0 - (onScreen ? 0 : margin);
        return new Spot(
                (int) Math.round(width / 2.0 + ndcX * halfWidth),
                (int) Math.round(height / 2.0 - ndcY * halfHeight),
                onScreen);
    }
}
