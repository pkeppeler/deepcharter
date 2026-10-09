package io.github.pkeppeler.deepcharter.client.pod;

import com.geckolib.animatable.GeoAnimatable;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;

/**
 * What GeckoLib draws for a pod renderer: one object for every pod of a chassis (a replaced-entity renderer takes the animatable
 * as a stand-in, so {@code PodEntity} stays free of GeckoLib and a server never loads it). It has no animation controllers: a
 * pod's bones move by their role from the pod's own state, in {@link PodGeoRenderer}.
 */
public final class PodGeoAnimatable implements GeoAnimatable {
	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
