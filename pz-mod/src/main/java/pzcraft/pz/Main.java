package pzcraft.pz;

/** Entry point ZombieBuddy runs when the mod loads. */
public class Main {
    public static void main(String[] args) {
        Log.info("PzCraft " + "0.13.0" + " loading (pid " + ProcessHandle.current().pid() + ")");
        LinkService.start();
        McLauncher.start();
    }
}


