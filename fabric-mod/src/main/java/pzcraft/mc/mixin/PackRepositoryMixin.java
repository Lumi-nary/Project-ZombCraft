package pzcraft.mc.mixin;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import pzcraft.mc.PzPackSource;

/** Adds the generated PZ item pack to the client's pack repository when Minecraft builds it. */
@Mixin(Minecraft.class)
public abstract class PackRepositoryMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/repository/PackRepository;<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V"), index = 0)
    private RepositorySource[] pzcraft$withPzItems(RepositorySource[] sources) {
        RepositorySource[] all = Arrays.copyOf(sources, sources.length + 1);
        all[sources.length] = new PzPackSource();
        return all;
    }
}

