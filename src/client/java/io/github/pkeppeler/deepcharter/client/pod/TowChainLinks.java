package io.github.pkeppeler.deepcharter.client.pod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Turns a built {@link TowChain} into quads, two for each link in planes at right angles to each other along the line, so a link shows
 * from any side. Even links show their face in the first plane and their edge in the second, odd links the other way round, which is
 * how the links of a real chain lie. A link is drawn a little longer than its share of the line, so neighbours overlap.
 */
final class TowChainLinks {
	private static final double EPSILON = 1e-9;

	private TowChainLinks() {
	}

	static void emit(TowChainRenderer.Chain chain, PoseStack.Pose pose, VertexConsumer vertices) {
		TowChain curve = chain.curve;
		double halfWidth = chain.look.linkWidth() / 2.0;
		int tint = chain.look.tint();
		for (int i = 0; i < curve.links; i++) {
			double tx = curve.x[i + 1] - curve.x[i];
			double ty = curve.y[i + 1] - curve.y[i];
			double tz = curve.z[i + 1] - curve.z[i];
			double length = Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (length < EPSILON) {
				continue;
			}
			tx /= length;
			ty /= length;
			tz /= length;
			// The first plane's across-vector is the tangent crossed with up (or with east when the line is upright), the second's is the
			// tangent crossed with that.
			double ux = tz;
			double uy = 0;
			double uz = -tx;
			double ul = Math.sqrt(ux * ux + uz * uz);
			if (ul < 1e-4) {
				ux = 0;
				uy = tz;
				uz = -ty;
				ul = Math.sqrt(uy * uy + uz * uz);
			}
			ux /= ul;
			uy /= ul;
			uz /= ul;
			double vx = ty * uz - tz * uy;
			double vy = tz * ux - tx * uz;
			double vz = tx * uy - ty * ux;
			double mx = (curve.x[i] + curve.x[i + 1]) / 2.0 - chain.cameraX;
			double my = (curve.y[i] + curve.y[i + 1]) / 2.0 - chain.cameraY;
			double mz = (curve.z[i] + curve.z[i + 1]) / 2.0 - chain.cameraZ;
			double half = length * chain.look.linkOverlap() / 2.0;
			boolean even = i % 2 == 0;
			quad(vertices, pose, tint, chain.lights[i], mx, my, mz, tx * half, ty * half, tz * half, ux * halfWidth, uy * halfWidth, uz * halfWidth,
					vx, vy, vz, even ? 0f : 0.5f);
			quad(vertices, pose, tint, chain.lights[i], mx, my, mz, tx * half, ty * half, tz * half, vx * halfWidth, vy * halfWidth, vz * halfWidth,
					ux, uy, uz, even ? 0.5f : 0f);
		}
	}

	/** One quad centred on (mx, my, mz), {@code along} to each end and {@code across} to each side, with the texture cell that starts at {@code u0}. */
	private static void quad(VertexConsumer vertices, PoseStack.Pose pose, int tint, int light, double mx, double my, double mz, double ax,
			double ay, double az, double cx, double cy, double cz, double nx, double ny, double nz, float u0) {
		vertex(vertices, pose, tint, light, mx - ax - cx, my - ay - cy, mz - az - cz, u0, 0f, nx, ny, nz);
		vertex(vertices, pose, tint, light, mx - ax + cx, my - ay + cy, mz - az + cz, u0 + 0.5f, 0f, nx, ny, nz);
		vertex(vertices, pose, tint, light, mx + ax + cx, my + ay + cy, mz + az + cz, u0 + 0.5f, 1f, nx, ny, nz);
		vertex(vertices, pose, tint, light, mx + ax - cx, my + ay - cy, mz + az - cz, u0, 1f, nx, ny, nz);
	}

	private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, int tint, int light, double x, double y, double z, float u, float v,
			double nx, double ny, double nz) {
		vertices.addVertex(pose, (float) x, (float) y, (float) z).setColor(tint).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, (float) nx, (float) ny, (float) nz);
	}
}
