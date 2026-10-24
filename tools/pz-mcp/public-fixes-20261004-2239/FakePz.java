import java.time.LocalTime;
import pzcraft.protocol.SharedLink;

/** Stands in for Project Zomboid on the shared link: beats for a while, then quits or goes silent. */
public class FakePz {
    public static void main(String[] a) throws Exception {
        long beatSeconds = Long.parseLong(a[0]);
        long silentSeconds = a.length > 1 ? Long.parseLong(a[1]) : 0; // stay alive without beating ("stalled PZ")
        try (SharedLink l = SharedLink.open(SharedLink.Role.PZ)) {
            System.out.println(LocalTime.now() + " fake PZ pid " + ProcessHandle.current().pid() + " on " + SharedLink.defaultPath());
            long end = System.currentTimeMillis() + beatSeconds * 1000;
            boolean was = false;
            while (System.currentTimeMillis() < end) {
                l.heartbeat();
                boolean alive = l.peerAlive();
                if (alive != was) { System.out.println(LocalTime.now() + (alive ? " Minecraft connected" : " Minecraft disconnected")); was = alive; }
                while (l.rx().poll() != null) { /* drain */ }
                Thread.sleep(100);
            }
            if (silentSeconds > 0) {
                System.out.println(LocalTime.now() + " fake PZ goes silent for " + silentSeconds + " s");
                Thread.sleep(silentSeconds * 1000);
            }
            System.out.println(LocalTime.now() + " fake PZ exiting");
        }
    }
}
