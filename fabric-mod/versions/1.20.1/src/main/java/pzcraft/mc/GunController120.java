package pzcraft.mc;

import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.Animation;

/** Small compatibility surface for the existing gun state machine on GeckoLib 4. */
final class GunController120 extends AnimationController<GunItem> {
    private double renderedTick;
    GunController120(GunItem gun,String name,int blend,AnimationStateHandler<GunItem> handler) { super(gun,name,blend,handler); }
    record Point(Animation animation, Animation.LoopType loopType, boolean hasFinished) {}
    Point getCurrentAnimationPoint() {
        var a=getCurrentAnimation(); return a==null?null:new Point(a.animation(),a.loopType(),hasAnimationFinished());
    }
    @Override public boolean stopTriggeredAnimation() { return super.stopTriggeredAnimation(); }
    void setTransitionTicks(int ticks) { transitionLength(ticks); }
    int getTransitionTicks() { return (int)transitionLength; }
    boolean isTransitioning() { return getAnimationState()==State.TRANSITIONING; }
    State getPlayState() { return getAnimationState(); }
    @Override protected double adjustTick(double tick) { renderedTick=tick;return super.adjustTick(tick); }
    double getCurrentAnimationTime() { return Math.max(0,renderedTick-tickOffset)*getAnimationSpeed()/20; }
    void setAnimationTime(double seconds) { tickOffset=renderedTick-seconds*20/Math.max(.0001,getAnimationSpeed()); }
}
