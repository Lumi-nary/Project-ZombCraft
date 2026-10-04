package pzcraft.mc.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.RespawnBridge;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RespawnRequestMixin {
    @Inject(method="handleClientCommand",at=@At("HEAD"),cancellable=true)
    private void prepare(ServerboundClientCommandPacket packet,CallbackInfo ci) {
        if(RespawnBridge.prepare((ServerGamePacketListenerImpl)(Object)this,packet))ci.cancel();
    }
}

