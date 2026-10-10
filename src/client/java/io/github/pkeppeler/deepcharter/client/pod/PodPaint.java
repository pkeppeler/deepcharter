package io.github.pkeppeler.deepcharter.client.pod;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.fabric.api.resource.v1.ResourceLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.Colors;

/**
 * The paint swap of a pod's hull (#383): the texture a pod of a charter is drawn with, its look's texture with the hull's paint texels
 * recoloured to the charter's paint colour ({@link io.github.pkeppeler.deepcharter.client.theme.PodPaintLook}). The paint texels are the
 * ones the look's paint mask names; each holds the brightness of the paint there against the base paint, so the colour keeps its shading,
 * seams and rivets. A wreck is not painted: its own texture is weathered grey.
 *
 * <p>A painted texture is made once for each texture, mask and colour, on the first frame that wants it, and dropped on a resource
 * reload (F3+T), which may have changed the base, the mask or the palette. Client thread only.
 */
public final class PodPaint {
	/** The mask grey that leaves the colour as it is: a texel of the base paint. */
	public static final int MASK_UNIT = 128;

	private record Key(Identifier base, Identifier mask, int rgb) {
	}

	private static final Map<Key, Identifier> TEXTURES = new HashMap<>();
	private static final List<Identifier> REGISTERED = new ArrayList<>();
	/** How many painted textures have been made, for their ids: an id is never reused, so a stale one can never be drawn. */
	private static int made;
	private static final Identifier RELOAD = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_paint");

	private PodPaint() {
	}

	public static void init() {
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(RELOAD, new SimplePreparableReloadListener<Void>() {
			@Override
			protected Void prepare(ResourceManager manager, ProfilerFiller profiler) {
				return null;
			}

			@Override
			protected void apply(Void prepared, ResourceManager manager, ProfilerFiller profiler) {
				release();
			}
		});
	}

	/**
	 * The texture {@code base} shows with the paint texels {@code mask} marks recoloured to {@code paint} (ARGB; its alpha is ignored).
	 * It is registered with the game's texture manager, and the same id comes back for the same three arguments.
	 */
	public static Identifier texture(Identifier base, Identifier mask, int paint) {
		int rgb = Colors.rgb(paint);
		return TEXTURES.computeIfAbsent(new Key(base, mask, rgb), key -> {
			Minecraft client = Minecraft.getInstance();
			ResourceManager resources = client.getResourceManager();
			try (NativeImage baseImage = read(resources, base); NativeImage maskImage = read(resources, mask)) {
				Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_paint/" + made++);
				client.getTextureManager().register(id, new DynamicTexture(() -> "deepcharter pod paint " + id, paint(baseImage, maskImage, rgb)));
				REGISTERED.add(id);
				return id;
			} catch (RuntimeException e) {
				// A pack's texture or mask that the load check could not see (it changed after the load, or does not decode) must not stop
				// the frame: the pod is drawn unpainted, and the log says why once, until the next reload.
				DeepCharter.LOGGER.error("The pod texture {} cannot be painted with the mask {}, so it is drawn unpainted: {}", base, mask, e.getMessage());
				return base;
			}
		});
	}

	/**
	 * Throws unless {@code base} and {@code mask} both decode and are the same size, so that {@link #texture} can paint them. The look load
	 * runs this, so a pack whose texture and mask disagree falls back to the mod's own look.
	 */
	public static void check(ResourceManager resources, Identifier base, Identifier mask) {
		try (NativeImage baseImage = read(resources, base); NativeImage maskImage = read(resources, mask)) {
			if (baseImage.getWidth() != maskImage.getWidth() || baseImage.getHeight() != maskImage.getHeight()) {
				throw new IllegalArgumentException("the paint mask " + mask + " is " + maskImage.getWidth() + " x " + maskImage.getHeight() + ", but the texture "
						+ base + " is " + baseImage.getWidth() + " x " + baseImage.getHeight());
			}
		}
	}

	/** Closes every painted texture, so the next frame makes them again from what the packs now say. */
	static void release() {
		// Only the textures made here: an entry that fell back to the unpainted texture holds a texture that is not ours to close.
		REGISTERED.forEach(Minecraft.getInstance().getTextureManager()::release);
		REGISTERED.clear();
		TEXTURES.clear();
	}

	/**
	 * A new image of {@code base} where every texel that {@code mask} marks (an opaque texel, grey {@value #MASK_UNIT} for the base
	 * paint) is {@code rgb} scaled by the mask's grey over {@value #MASK_UNIT}, and every other texel is as it was.
	 *
	 * @throws IllegalArgumentException if the two images differ in size
	 */
	public static NativeImage paint(NativeImage base, NativeImage mask, int rgb) {
		if (base.getWidth() != mask.getWidth() || base.getHeight() != mask.getHeight()) {
			throw new IllegalArgumentException("the paint mask is " + mask.getWidth() + " x " + mask.getHeight() + ", but the texture is " + base.getWidth() + " x " + base.getHeight());
		}
		NativeImage painted = new NativeImage(base.getWidth(), base.getHeight(), false);
		painted.copyFrom(base);
		for (int y = 0; y < base.getHeight(); y++) {
			for (int x = 0; x < base.getWidth(); x++) {
				int marked = mask.getPixel(x, y);
				if (ARGB.alpha(marked) == 0) {
					continue;
				}
				int grey = ARGB.red(marked);
				painted.setPixel(x, y, Colors.opaque(scaled(ARGB.red(rgb), grey) << 16 | scaled(ARGB.green(rgb), grey) << 8 | scaled(ARGB.blue(rgb), grey)));
			}
		}
		return painted;
	}

	private static int scaled(int channel, int grey) {
		return Mth.clamp(channel * grey / MASK_UNIT, 0, 255);
	}

	private static NativeImage read(ResourceManager resources, Identifier texture) {
		Resource resource = resources.getResource(texture).orElseThrow(() -> new IllegalStateException("No texture " + texture + " for a pod's paint"));
		try (InputStream png = resource.open()) {
			return NativeImage.read(png);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read the texture " + texture, e);
		}
	}
}
