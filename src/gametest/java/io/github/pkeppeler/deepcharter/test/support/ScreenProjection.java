package io.github.pkeppeler.deepcharter.test.support;

import java.awt.image.BufferedImage;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/**
 * Where a point in the world shows in a screenshot, by a pinhole camera's maths, for a client test that must find a landmark (a lamp) where
 * it should be rather than anywhere in the frame. A block face has size, so a caller looks for its landmark in a window round the pixel.
 */
public final class ScreenProjection {
    /** A point this close to the frame's edge is treated as off it. */
    private static final int EDGE_MARGIN = 20;

    private ScreenProjection() {}

    /**
     * The pixel where {@code point} shows for a camera at {@code eye} facing {@code yawDegrees} and {@code pitchDegrees} (a positive pitch
     * looks down) with a vertical field of view of {@code fovDegrees}, or empty when it is off the picture or behind the camera.
     */
    public static Optional<int[]> project(
            BufferedImage image, Vec3 eye, float yawDegrees, float pitchDegrees, double fovDegrees, Vec3 point) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(forward);
        Vec3 d = point.subtract(eye);
        double depth = d.dot(forward);
        if (depth <= 0) {
            return Optional.empty();
        }
        double tan = Math.tan(Math.toRadians(fovDegrees) / 2);
        double aspect = image.getWidth() / (double) image.getHeight();
        int x = (int) Math.round(image.getWidth() / 2.0 * (1 + d.dot(right) / depth / (tan * aspect)));
        int y = (int) Math.round(image.getHeight() / 2.0 * (1 - d.dot(up) / depth / tan));
        if (x < EDGE_MARGIN
                || y < EDGE_MARGIN
                || x >= image.getWidth() - EDGE_MARGIN
                || y >= image.getHeight() - EDGE_MARGIN) {
            return Optional.empty();
        }
        return Optional.of(new int[] {x, y});
    }
}
