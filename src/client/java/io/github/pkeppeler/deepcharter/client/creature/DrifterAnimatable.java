package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.animatable.GeoReplacedEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.util.GeckoLibUtil;
import io.github.pkeppeler.deepcharter.creature.DrifterVariant;
import java.util.Arrays;
import java.util.function.Function;

/** One shared stand-in for every drifter, so the entity stays free of GeckoLib and a server never loads it. */
public final class DrifterAnimatable implements GeoReplacedEntity {
    /** By {@link DrifterVariant#ordinal()}. */
    private static final RawAnimation[] IDLE = poses(DrifterAssets::idle);

    private static final RawAnimation[] DRAWN = poses(DrifterAssets::drawn);
    private static final int BLEND_TICKS = 10;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<DrifterAnimatable>(
                "drifter", BLEND_TICKS, test -> test.setAndContinue(pose(test.renderState()))));
    }

    private static RawAnimation[] poses(Function<DrifterVariant, String> animation) {
        return Arrays.stream(DrifterVariant.values())
                .map(variant -> RawAnimation.begin().thenLoop(animation.apply(variant)))
                .toArray(RawAnimation[]::new);
    }

    private static RawAnimation pose(GeoRenderState state) {
        int form = DrifterRenderState.variantOf(state).ordinal();
        return state instanceof DrifterRenderState drifter && drifter.drawn ? DRAWN[form] : IDLE[form];
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
