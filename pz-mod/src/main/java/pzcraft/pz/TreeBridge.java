package pzcraft.pz;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoTree;

/**
 * Minecraft's invisible tree block was broken (Steve punched or chopped the tree, or an explosion took it): topple the real
 * PZ tree the way PZ does (falling-tree sound, the tree removed, a stump for jumbo trees). PZ's own drops are skipped,
 * because Minecraft drops the logs.
 */
public final class TreeBridge {
    private static final ConcurrentLinkedQueue<int[]> felled = new ConcurrentLinkedQueue<>();
    private static volatile boolean suppressDrops;
    private static volatile int toppled, missed;
    private static volatile String last = "";

    private TreeBridge() {}

    /** Link thread. */
    static void onFelled(int[] tile) {
        if (felled.size() < 256) felled.add(tile);
    }

    /** Game thread, once per frame. */
    static void tick() {
        if (felled.isEmpty()) return;
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        int[] t;
        while ((t = felled.poll()) != null) {
            IsoGridSquare square = cell.getGridSquare(t[0], t[1], t[2]);
            IsoTree tree = square == null ? null : square.getTree();
            if (tree == null) {
                missed++;
                last = "no tree at " + t[0] + "," + t[1];
                Log.info("tree felled in Minecraft at (" + t[0] + ", " + t[1] + ") but PZ has no tree there");
                continue;
            }
            suppressDrops = true;
            try {
                tree.toppleTree(IsoPlayer.getInstance());
                toppled++;
                last = "toppled " + t[0] + "," + t[1];
                WorldExporter.invalidate(t[0], t[1]);
                Log.info("PZ tree at (" + t[0] + ", " + t[1] + ") toppled after Minecraft felled it");
            } finally {
                suppressDrops = false;
            }
        }
    }

    /** Public: ZombieBuddy inlines the advice that calls this into IsoTree.dropWood. */
    public static boolean suppressesDrops() { return suppressDrops; }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("toppled", toppled);
        m.put("missed", missed);
        m.put("pending", felled.size());
        m.put("last", last);
        return m;
    }
}

