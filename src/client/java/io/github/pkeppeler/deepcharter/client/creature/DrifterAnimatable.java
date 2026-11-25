package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.animatable.GeoReplacedEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.util.GeckoLibUtil;

/** One shared stand-in for every drifter, so the entity stays free of GeckoLib and a server never loads it. */
public final class DrifterAnimatable implements GeoReplacedEntity {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop(DrifterAssets.IDLE);
    private static final RawAnimation DRAWN = RawAnimation.begin().thenLoop(DrifterAssets.DRAWN);
    private static final int BLEND_TICKS = 10;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<DrifterAnimatable>(
                "drifter", BLEND_TICKS, test -> test.setAndContinue(pose(test.renderState()))));
    }

    private static RawAnimation pose(GeoRenderState state) {
        return state instanceof DrifterRenderState drifter && drifter.drawn ? DRAWN : IDLE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
