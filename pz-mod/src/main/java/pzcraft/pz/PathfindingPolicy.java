package pzcraft.pz;

/** Select PZ's Java backend before IsoWorld initializes its pathfinding data. */
public final class PathfindingPolicy {
    private PathfindingPolicy() {}
    public static void select() {
        if (Multiplayer.serverProcess() || Boolean.getBoolean("pzcraft.nativePathfinding")) return;
        zombie.debug.DebugOptions.instance.pathfindUseNativeCode.setValue(false);
        zombie.pathfind.nativeCode.PathfindNative.useNativeCode = false;
    }
}
