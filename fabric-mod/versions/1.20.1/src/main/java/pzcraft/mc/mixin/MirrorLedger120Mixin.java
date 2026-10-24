package pzcraft.mc.mixin;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.MirrorLedger120;
@Mixin(Player.class)
public abstract class MirrorLedger120Mixin implements MirrorLedger120 {
    @Unique private List<Long> pzcraft$ids=List.of();
    public List<Long> pzcraft$applied(){return pzcraft$ids;}
    public void pzcraft$applied(List<Long> ids){pzcraft$ids=List.copyOf(ids);}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void pzcraft$save(CompoundTag tag,CallbackInfo ci){tag.putLongArray("PzCraftMirrorApplied",pzcraft$ids);}
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void pzcraft$load(CompoundTag tag,CallbackInfo ci){pzcraft$ids=java.util.Arrays.stream(tag.getLongArray("PzCraftMirrorApplied")).boxed().toList();}
}
