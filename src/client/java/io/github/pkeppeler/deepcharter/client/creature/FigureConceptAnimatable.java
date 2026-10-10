package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.animatable.GeoReplacedEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;

/**
 * What GeckoLib animates for the figure: one object for every figure (a replaced-entity renderer takes it as a stand-in, so the figure
 * stays free of GeckoLib and a server never loads it). One controller plays the walk while the figure moves and the idle while it stands.
 * What the animations do is in the animation file, not here.
 */
public final class FigureConceptAnimatable implements GeoReplacedEntity {
	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop(FigureConcept.IDLE);
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop(FigureConcept.WALK);
	/** Ticks over which the controller blends from the idle to the walk and back. */
	private static final int BLEND_TICKS = 6;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<FigureConceptAnimatable>("figure", BLEND_TICKS, test -> test.setAndContinue(test.isMoving() ? WALK : IDLE)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
