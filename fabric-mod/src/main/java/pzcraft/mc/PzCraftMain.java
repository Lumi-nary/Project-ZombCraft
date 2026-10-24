package pzcraft.mc;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs before registries freeze (the client entrypoint runs too late to register blocks). */
public class PzCraftMain implements ModInitializer {
    /** The mod's log, on both sides (the client entrypoint's class must not be loaded on a dedicated server). */
    public static final Logger LOG = LoggerFactory.getLogger("project-zombcraft");

    @Override
    public void onInitialize() {
        PzBlocks.init();
        GroundBridge.init();
        PzItems.init();
        PzTiles.init();
        PzSpawners.init();
        PzCreativeTabs.init();
        MirrorBridge.init();
        ServerPlacement.init();
        PzMineServer.init();
        PzSeat.register();
        PzRest.init();
        PzCars.init();
        ZombieSiege.init();
        PzFire.init();
        PzBlocks.aliasOldIds(); // last: every id is registered by now
    }
}
