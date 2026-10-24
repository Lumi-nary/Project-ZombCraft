package pzcraft.mc.mixin;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the PZ creative tabs refill after PZ exported its items again (CreativeModeTabs caches every tab's contents). */
@Mixin(CreativeModeTabs.class)
public interface CreativeTabsAccessor {
    @Accessor("CACHED_PARAMETERS")
    static void pzcraft$setCachedParameters(CreativeModeTab.ItemDisplayParameters parameters) { throw new AssertionError(); }
}
