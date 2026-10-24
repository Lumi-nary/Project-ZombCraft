package pzcraft.mc;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.*;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.util.RenderUtils;

/** GeckoLib 4 renderer for the owner's unchanged M16 geometry, texture and animation clips. */
final class GunRenderer120 extends GeoItemRenderer<GunItem> {
    private final GunModels.Kind kind;
    private PlayerModel<net.minecraft.client.player.AbstractClientPlayer> arms;
    private final java.util.Map<String,PoseStack> armPoses=new java.util.LinkedHashMap<>();
    private PoseStack muzzlePose;
    GunRenderer120(GunModels.Kind kind) {
        super(new GeoModel<GunItem>() {
            public ResourceLocation getModelResource(GunItem gun) { return new ResourceLocation(PzBlocks.NAMESPACE,"geo/item/gun_"+kind.id()+".geo.json"); }
            public ResourceLocation getTextureResource(GunItem gun) { return new ResourceLocation(PzBlocks.NAMESPACE,"textures/item/gun_"+kind.id()+".png"); }
            public ResourceLocation getAnimationResource(GunItem gun) { return new ResourceLocation(PzBlocks.NAMESPACE,"animations/item/gun_"+kind.id()+".animation.json"); }
        });
        this.kind=kind;
    }
    /** Default first-person placement (see actuallyRender). */
    private static final double FP_MODE=1;
    /** A camera-space placement value of this gun (mode 1); the M16's keys keep their old names. */
    private double hip(String axis,double fallback) {
        String id=kind.id().equals("m16a1")?"m16":kind.id();
        return Tuning.get(id+".hip."+axis,Tuning.get("gun.hip."+axis,fallback));
    }
    private boolean first() { return renderPerspective==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || renderPerspective==ItemDisplayContext.FIRST_PERSON_LEFT_HAND; }
    @Override public void actuallyRender(PoseStack pose,GunItem gun,BakedGeoModel model,RenderType type,MultiBufferSource source,VertexConsumer buffer,boolean rerender,float pt,int light,int overlay,float r,float g,float b,float a) {
        if(!rerender) {
            var state=new AnimationState<>(gun,0,0,pt,false);
            state.setData(DataTickets.TICK,gun.getTick(currentItemStack));
            state.setData(DataTickets.ITEM_RENDER_PERSPECTIVE,renderPerspective);
            state.setData(DataTickets.ITEMSTACK,currentItemStack);
            long id=getInstanceId(gun);
            gun.getAnimatableInstanceCache().getManagerForId(id).setData(DataTickets.ITEM_RENDER_PERSPECTIVE,renderPerspective);
            getGeoModel().addAdditionalStateData(gun,id,state::setData);
            getGeoModel().handleAnimations(gun,id,state);
        }
        for(var bone:model.topLevelBones()) visibility(bone);
        pose.pushPose();
        if(first()) {
            // Two placements, chosen per gun with "<kind>.fp.mode" (or "gun.fp.mode" for all): 0 keeps vanilla's
            // first-person arm transform and the model's authored display transform, as 26.x does; 1 replaces both
            // with a fixed camera-space spot ("<kind>.hip.x/y/z/scale").
            float scale=1;
            if(Tuning.get(kind.id()+".fp.mode",Tuning.get("gun.fp.mode",FP_MODE))>.5) {
                // a pistol is held higher and nearer the middle than a long gun, where its hand shows
                boolean pistol=kind.pistol();
                pose.last().pose().identity().translation((float)hip("x",pistol?.30:.40),(float)hip("y",pistol?-.62:-.85),(float)hip("z",-.85));
                pose.last().normal().identity();
                scale=(float)hip("scale",.30);pose.scale(scale,scale,scale);
            }
            GunFeel.sway(pose);
            float hipScale=scale;
            float aim=GunFeel.aim();
            if(aim>.001f)getGeoModel().getBone("scope").ifPresent(scope->{
                PoseStack sight=new PoseStack();toBone(sight,scope);RenderUtils.translateToPivotPoint(sight,scope);
                Matrix4f target=new Matrix4f().translation((float)Tuning.get("ads.x",0),(float)Tuning.get("ads.y",0),(float)Tuning.get("ads.z",-.7)).scale(hipScale).mul(new Matrix4f(sight.last().pose()).invert());
                Matrix4f correction=new Matrix4f(pose.last().pose()).invert().mul(target);
                pose.mulPoseMatrix(new Matrix4f().lerp(correction,aim));
            });
            var sway=GunFeel.adsSway();if(sway!=null)pose.last().pose().mulLocal(sway);
        }
        // Animations were evaluated above, before sight alignment and visibility. Skip the second evaluation.
        armPoses.clear();
        muzzlePose=null;
        super.actuallyRender(pose,gun,model,type,source,buffer,true,pt,light,overlay,r,g,b,a);
        // Vanilla's immediate BufferSource shares a BufferBuilder across these render types.
        // Finish every gun face before switching to Steve's skin, otherwise later faces sample his skin texture.
        armPoses.forEach((side,armPose)->drawArm(armPose,side.equals("rightarm"),source,light,overlay));
        if(muzzlePose!=null && GunFeel.flashing())drawFlash(muzzlePose,source);
        pose.popPose();
    }
    private void visibility(GeoBone bone) {
        String name=bone.getName(),clip=GunItem.shownClip();
        boolean hidden=kind.hide().contains(name);
        if(kind.clipBones().containsKey(name) && !kind.clipBones().get(name).contains(clip))hidden=true;
        int round=kind.roundBones().indexOf(name);
        if(round>=GunItem.roundsHeld() && round>=0 && !kind.roundsShowAll().contains(clip))hidden=true;
        if(GunItem.roundsHeld()>0 && kind.emptyBones().contains(name))hidden=true;
        if(GunItem.withoutMagazine() && !clip.startsWith("reload") && !clip.equals("unload") && (name.equals("magazine")||name.equals("magazine2")))hidden=true;
        bone.setHidden(hidden);bone.setChildrenHidden(hidden);
        for(var child:bone.getChildBones())visibility(child);
    }
    private static void toBone(PoseStack pose,GeoBone bone) {
        if(bone.getParent()!=null)toBone(pose,bone.getParent());
        RenderUtils.prepMatrixForBone(pose,bone);
    }
    @Override public void renderRecursively(PoseStack pose,GunItem gun,GeoBone bone,RenderType type,MultiBufferSource source,VertexConsumer buffer,boolean rerender,float pt,int light,int overlay,float r,float g,float b,float a) {
        super.renderRecursively(pose,gun,bone,type,source,buffer,rerender,pt,light,overlay,r,g,b,a);
        if(!first())return;
        String name=bone.getName();
        if(name.equals("muzzleflash")) {
            muzzlePose=new PoseStack();muzzlePose.last().pose().set(pose.last().pose());muzzlePose.last().normal().set(pose.last().normal());
            RenderUtils.prepMatrixForBone(muzzlePose,bone);RenderUtils.translateToPivotPoint(muzzlePose,bone);GunFeel.muzzleSeen(muzzlePose.last().pose());
        }
        if(!name.equals("rightarm") && !name.equals("leftarm"))return;
        PoseStack armPose=new PoseStack();armPose.last().pose().set(pose.last().pose());armPose.last().normal().set(pose.last().normal());
        RenderUtils.prepMatrixForBone(armPose,bone);RenderUtils.translateToPivotPoint(armPose,bone);
        armPoses.put(name,armPose);
    }
    private void drawArm(PoseStack pose,boolean right,MultiBufferSource source,int light,int overlay) {
        var mc=Minecraft.getInstance();if(mc.player==null)return;
        if(arms==null)arms=new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER),false);
        String side=right?"right":"left";
        pose.pushPose();
        pose.translate(Tuning.get("armBone."+side+".x",0),Tuning.get("armBone."+side+".y",0),Tuning.get("armBone."+side+".z",0));
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float)Tuning.get("armBone."+side+".rx",0)));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float)Tuning.get("armBone."+side+".ry",0)));
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float)Tuning.get("armBone."+side+".rz",0)));
        float scale=(float)Tuning.get("armBone.scale",2);
        pose.scale(-scale,-scale,scale);
        double compensate=Tuning.get("armBone.compensate",-1);
        pose.translate(-(right?1:-1)*5/16.0*compensate,-2/16.0*compensate,0);
        var skin=source.getBuffer(RenderType.entityCutoutNoCull(mc.player.getSkinTextureLocation()));
        (right?arms.rightArm:arms.leftArm).render(pose,skin,light,overlay);
        (right?arms.rightSleeve:arms.leftSleeve).render(pose,skin,light,overlay);
        pose.popPose();
    }
    private void drawFlash(PoseStack pose,MultiBufferSource source) {
        var out=source.getBuffer(RenderType.entityTranslucentEmissive(new ResourceLocation(PzBlocks.NAMESPACE,"textures/effect/flashes.png")));
        float h=(float)Tuning.get("flash.halfSize",1.25),u0=GunFeel.flashFrame()/9f,u1=u0+1/9f,alpha=GunFeel.flashAlpha();
        float[][] quad={{-h,-h,u0,1},{h,-h,u1,1},{h,h,u1,0},{-h,h,u0,0}};
        for(float[] v:quad)out.vertex(pose.last().pose(),v[0],v[1],0).color(1f,1f,1f,alpha).uv(v[2],v[3]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(pose.last().normal(),0,0,1).endVertex();
    }
}
