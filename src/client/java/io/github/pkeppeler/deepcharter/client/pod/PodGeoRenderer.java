package io.github.pkeppeler.deepcharter.client.pod;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.WeakHashMap;

import com.geckolib.constant.DataTickets;
import com.geckolib.renderer.GeoReplacedEntityRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.RenderPassInfo;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.PodPaintLook;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Draws a pod of one chassis with GeckoLib (ADR 0030, #243): the model, textures and tier map of its {@link PodLook}, its bones
 * posed by role ({@link PodPose}) from what the pod does ({@link PodMotion}), and its glowmask at full bright while the lamps are on.
 * The cutter on show is the one the pod's drill tier picks, and it changes the moment the drill does.
 *
 * <p>It is a replaced-entity renderer: {@link PodEntity} knows nothing of GeckoLib, so a server never loads it.
 */
public class PodGeoRenderer extends GeoReplacedEntityRenderer<PodGeoAnimatable, PodEntity, PodGeoRenderState> {
	/** How far the hull shakes while the drill bites, in blocks. */
	public static final float SHAKE = 0.02f;

	/**
	 * What a pod shows: the cutter its drill tier picks (null for a model with one plain cutter), its variant, intact or wreck, and the paint
	 * colour of its charter (ARGB) when its hull is painted: an intact pod of a registered owner, on a look that has a paint mask.
	 */
	public record Appearance(String cutter, PodLook.Variant variant, boolean wrecked, OptionalInt paint) {
		/** Whether the glowmask is drawn, given whether the pod's lamps are on. */
		public boolean glows(boolean lit) {
			return switch (variant.glow()) {
				case LIT -> lit;
				case ALWAYS -> true;
				case NEVER -> false;
			};
		}
	}

	private final Chassis chassis;
	private final PodLook look;
	private final GeoModel geo;
	private final PodPose pose;
	/** The model's own extent round the pod's feet, every cutter and the swing included: a cone leads the hitbox by a block, which the default culling box misses. */
	private final AABB modelExtent;
	/** How fast each cutter's drill spins, as a share of the full rate. */
	private final Map<String, Float> spinScales = new HashMap<>();
	/** The root bones of each cutter: {@code drill_head_<cutter>} and {@code drill_ring_<cutter>}. */
	private final Map<String, List<String>> cutterRoots = new HashMap<>();
	private final Map<Appearance, List<String>> hidden = new HashMap<>();
	// Weak, so a pod that leaves the level takes its animation with it.
	private final Map<PodEntity, PodMotion> motions = new WeakHashMap<>();

	/** A pod look and the geometry it names, which fit each other and their textures. */
	private record Loaded(PodLook look, GeoModel geo) {
	}

	public PodGeoRenderer(EntityRendererProvider.Context context, Chassis chassis) {
		this(context, chassis, load(context.getResourceManager(), chassis));
	}

	private PodGeoRenderer(EntityRendererProvider.Context context, Chassis chassis, Loaded loaded) {
		super(context, new PodGeckoModel(loaded.look()), new PodGeoAnimatable());
		this.chassis = chassis;
		look = loaded.look();
		geo = loaded.geo();
		shadowRadius = chassis.width() / 2;
		pose = new PodPose(geo);
		// The shake moves the whole model by up to SHAKE on each horizontal axis and on y while the pod drills.
		modelExtent = geo.cullingBox().inflate(SHAKE);
		for (String cutter : geo.cutters()) {
			spinScales.put(cutter, (float) geo.drillSpinScale(cutter));
			cutterRoots.put(cutter, geo.bones().stream().filter(bone -> GeoModel.cutterOf(bone).map(cutter::equals).orElse(false))
					.map(GeoModel.Bone::name).toList());
		}
		withRenderLayer(new PodGlowLayer(this));
	}

	/**
	 * The look of {@code chassis} that the game draws: the winning pack's if it loads and fits its model and textures, else the mod's
	 * own, after one error in the log that names the pack, the file and the place. A broken skin must not stop the client, which a
	 * renderer that fails to build on a resource reload would.
	 */
	static Loaded load(ResourceManager resources, Chassis chassis) {
		try {
			return validate(resources, PodLook.read(resources, chassis));
		} catch (RuntimeException e) {
			DeepCharter.LOGGER.error("The pod look of the {} does not load, so the mod's own look is drawn instead: {}", chassis.id(), e.getMessage());
			return validate(resources, PodLook.readBuiltIn(resources, chassis));
		}
	}

	/** Reads the geometry that {@code look} names and checks the look and its textures against it. */
	private static Loaded validate(ResourceManager resources, PodLook look) {
		try {
			GeoModel geo = readGeometry(resources, look.modelFile());
			look.check(geo);
			for (PodLook.Variant variant : List.of(look.intact(), look.wreck())) {
				checkTexture(resources, geo, variant.texture());
				if (variant.glow() != PodLook.Glow.NEVER) {
					checkTexture(resources, geo, variant.glowmask());
				}
			}
			if (look.paintMask().isPresent()) {
				checkTexture(resources, geo, look.paintMask().get());
				PodPaint.check(resources, look.intact().texture(), look.paintMask().get());
			}
			return new Loaded(look, geo);
		} catch (RuntimeException e) {
			throw e.getMessage() != null && e.getMessage().contains(look.source()) ? e : new IllegalArgumentException(look.source() + ": " + e.getMessage(), e);
		}
	}

	public Chassis chassis() {
		return chassis;
	}

	public PodLook look() {
		return look;
	}

	/** The model as the game measures it, which GeckoLib draws from the same file. */
	public GeoModel geometry() {
		return geo;
	}

	/** What {@code pod} shows now: the cutter of its drill tier, and the wreck variant if it is a wreck. */
	public Appearance appearanceOf(PodEntity pod) {
		boolean wrecked = Wrecks.isWreck(pod);
		String cutter = geo.cutters().isEmpty() ? null : look.cutterFor(PodComponents.effectiveTier(pod, ComponentTrack.DRILL));
		OptionalInt paint = wrecked || look.paintMask().isEmpty() ? OptionalInt.empty()
				: PodComponents.registration(pod).map(registration -> OptionalInt.of(PodPaintLook.current().paintOf(registration.owner()))).orElse(OptionalInt.empty());
		return new Appearance(cutter, wrecked ? look.wreck() : look.intact(), wrecked, paint);
	}

	/** The texture {@code appearance} is drawn with: its variant's, painted in its paint colour if it has one. */
	public Identifier textureOf(Appearance appearance) {
		Identifier texture = appearance.variant().texture();
		return appearance.paint().isPresent() ? PodPaint.texture(texture, look.paintMask().orElseThrow(), appearance.paint().getAsInt()) : texture;
	}

	/**
	 * The box the game culls {@code pod} by. A cutter leads the hitbox by a block and sinks under the floor, so the box is widened to
	 * the model's: the whole model at every heading, every cutter, at any angle of the drill mount.
	 */
	@Override
	public AABB getBoundingBoxForCulling(PodEntity pod, float partialTick) {
		return super.getBoundingBoxForCulling(pod, partialTick).minmax(modelExtent.move(pod.position()));
	}

	/** The share of the full drill spin that the cutter {@code appearance} shows turns at. */
	private float spinScaleOf(Appearance appearance) {
		return appearance.cutter() == null ? (float) geo.drillSpinScale() : spinScales.get(appearance.cutter());
	}

	/**
	 * The degrees the drill of {@code pod} turns in one tick while it drills: two frames one tick apart, run through a new
	 * {@link PodMotion} with the scale that {@link #addRenderData} gives it. It touches no frame of the pod's own animation, so it does
	 * not depend on how often the game draws.
	 */
	public float drillSpinPerTick(PodEntity pod) {
		PodMotion motion = new PodMotion();
		float scale = spinScaleOf(appearanceOf(pod));
		PodGeoRenderState before = new PodGeoRenderState();
		PodGeoRenderState after = new PodGeoRenderState();
		before.ageInTicks = 0f;
		after.ageInTicks = 1f;
		motion.advance(pod, before, pose.mountRestPitch(), scale);
		motion.advance(pod, after, pose.mountRestPitch(), scale);
		return after.drillSpin - before.drillSpin;
	}

	@Override
	public PodGeoRenderState createRenderState(PodGeoAnimatable animatable, PodEntity pod) {
		return new PodGeoRenderState();
	}

	@Override
	public void addRenderData(PodGeoAnimatable animatable, PodEntity pod, PodGeoRenderState state, float partialTick) {
		Appearance appearance = appearanceOf(pod);
		state.appearance = appearance;
		state.texture = textureOf(appearance);
		motions.computeIfAbsent(pod, ignored -> new PodMotion()).advance(pod, state, pose.mountRestPitch(), spinScaleOf(appearance));
		// The pod turns as its motion says (towards where it drives or drills), not as its entity yaw does.
		state.addGeckolibData(DataTickets.ENTITY_BODY_YAW, state.heading);
	}

	@Override
	public void adjustRenderPose(RenderPassInfo<PodGeoRenderState> renderPassInfo) {
		super.adjustRenderPose(renderPassInfo);
		PodGeoRenderState state = renderPassInfo.renderState();
		if (state.drilling) {
			renderPassInfo.poseStack().translate(SHAKE * Mth.sin(state.ageInTicks * 7.3f), SHAKE * Mth.sin(state.ageInTicks * 9.1f), 0f);
		}
	}

	@Override
	public void adjustModelBonesForRender(RenderPassInfo<PodGeoRenderState> renderPassInfo, BoneSnapshots snapshots) {
		PodGeoRenderState state = renderPassInfo.renderState();
		pose.apply(state, snapshots);
		for (String name : hiddenBones(state.appearance)) {
			snapshots.ifPresent(name, snapshot -> snapshot.skipRender(true).skipChildrenRender(true));
		}
	}

	/** The bones not drawn for {@code appearance}: the cutters it does not show, and what its variant hides. */
	public List<String> hiddenBones(Appearance appearance) {
		return hidden.computeIfAbsent(appearance, each -> {
			List<String> names = new ArrayList<>(each.variant().hide());
			cutterRoots.forEach((cutter, roots) -> {
				if (!cutter.equals(each.cutter())) {
					names.addAll(roots);
				}
			});
			return List.copyOf(names);
		});
	}

	private static GeoModel readGeometry(ResourceManager resources, Identifier id) {
		Resource resource = resources.getResource(id).orElseThrow(() -> new IllegalStateException("No pod model at " + id));
		try (Reader reader = resource.openAsReader()) {
			return GeoModel.parse(id.toString(), reader);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read the pod model " + id, e);
		}
	}

	/** The texture is there and fits the model's UV, or this throws naming both: a missing texture would draw magenta, silently. */
	private static void checkTexture(ResourceManager resources, GeoModel geo, Identifier texture) {
		Resource resource = resources.getResource(texture)
				.orElseThrow(() -> new IllegalStateException("No texture " + texture + " for the pod model " + geo.source()));
		try (InputStream png = resource.open()) {
			geo.checkTexture(texture.toString(), png);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read the texture " + texture + " of the pod model " + geo.source(), e);
		}
	}
}
