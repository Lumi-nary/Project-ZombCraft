package pzcraft.mc;

/** 1.20.1 impact effects use vanilla particles; native PZ results remain the damage authority. */
final class GunEffects120 {
    static void impact(PzGuns.Shot shot,com.google.gson.JsonObject result) {
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.level==null)return;
        for(var impact:shot.impacts()) {
            var p=impact.at();
            if(impact.block()!=null) {
                var state=mc.level.getBlockState(impact.block());
                for(int i=0;i<6;i++)mc.level.addParticle(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,state),p.x,p.y,p.z,(Math.random()-.5)*.15,Math.random()*.12,(Math.random()-.5)*.15);
            }
            if(impact.normal()!=null)for(int i=0;i<4;i++)mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,p.x,p.y,p.z,0,.015,0);
        }
    }
    private GunEffects120() {}
}
