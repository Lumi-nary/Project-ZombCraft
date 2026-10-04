package pzcraft.pz;

final class Log {
    private Log() {}

    static void info(String msg) {
        System.out.println("[PzCraft] " + msg);
    }

    static void error(String msg, Throwable t) {
        System.out.println("[PzCraft] ERROR " + msg + ": " + t);
        t.printStackTrace(System.out);
    }
}

