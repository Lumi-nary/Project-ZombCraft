package pzcraft.mc;

import net.fabricmc.api.ModInitializer;

/** Runs before registries freeze (the client entrypoint runs too late to register blocks). */
public class PzCraftMain implements ModInitializer {
    @Override
    public void onInitialize() {
        PzBlocks.init();
        GroundBridge.init();
        PzItems.init();
        MirrorBridge.init();
    }
}

