package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.animatable.GeoReplacedEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.util.GeckoLibUtil;

/** One shared stand-in for every Tally, so the Tally stays free of GeckoLib and a server never loads it. */
public final class TallyAnimatable implements GeoReplacedEntity {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop(TallyAssets.IDLE);
    private static final RawAnimation FLY = RawAnimation.begin().thenLoop(TallyAssets.FLY);
    private static final RawAnimation CLAMP = RawAnimation.begin().thenLoop(TallyAssets.CLAMP);
    private static final int BLEND_TICKS = 4;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<TallyAnimatable>(
                "tally", BLEND_TICKS, test -> test.setAndContinue(pose(test.renderState(), test.isMoving()))));
    }

    private static RawAnimation pose(GeoRenderState state, boolean moving) {
        if (state instanceof TallyRenderState tally && tally.clamped) {
            return CLAMP;
        }
        return moving ? FLY : IDLE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
