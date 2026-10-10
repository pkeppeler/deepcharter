package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.animatable.GeoReplacedEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;

/** One shared stand-in for every figure, so the figure stays free of GeckoLib and a server never loads it. */
public final class FigureConceptAnimatable implements GeoReplacedEntity {
	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop(FigureConcept.IDLE);
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop(FigureConcept.WALK);
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
