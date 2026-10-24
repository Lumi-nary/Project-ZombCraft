package pzcraft.protocol;

/** A consumer-owned input cache. Reads the seqlock when input is used, without a polling thread. */
public final class InputReader {
    private final InputState scratch = new InputState();
    private InputState latest;
    private long peerPid;

    /** Call from one consumer thread; returned snapshots are read-only and never mutated by this reader. */
    public InputState read(SharedLink link, boolean connected) {
        if (!connected || link == null) {
            latest = null;
            return null;
        }
        long pid = link.peerPidOrZero();
        if (pid != peerPid || !link.inputAvailable()) { latest = null; peerPid = pid; }
        if (link.readInputState(scratch) && (latest == null || scratch.frame != latest.frame)) {
            InputState copy = new InputState();
            copy.set(scratch);
            latest = copy;
        }
        return latest;
    }
}
