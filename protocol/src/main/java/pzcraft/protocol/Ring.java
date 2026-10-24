package pzcraft.protocol;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Single-producer / single-consumer byte ring inside the shared mapping.
 *
 * Layout at {@code base}: head (long, written by producer) @0, tail (long, written by consumer) @8, data @64.
 * Messages are {@code [int len][int type][payload]}, padded to 8 bytes; len = -1 marks "wrap to start".
 * head/tail are monotonically increasing byte counts.
 */
public final class Ring {
    static final int HEADER = 64;
    private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final ByteBuffer buf;
    private final int base;
    private final int cap;

    public Ring(ByteBuffer buf, int base, int dataCapacity) {
        this.buf = buf;
        this.base = base;
        this.cap = dataCapacity;
    }

    public static int sizeFor(int dataCapacity) { return HEADER + dataCapacity; }

    private long head() { return (long) LONG.getAcquire(buf, base); }
    private long tail() { return (long) LONG.getAcquire(buf, base + 8); }
    private int data(long pos) { return base + HEADER + (int) (pos % cap); }

    private static int align8(int n) { return (n + 7) & ~7; }

    /** Returns false if the ring is full (caller may drop or retry). */
    public boolean write(int type, byte[] payload) {
        int total = align8(8 + payload.length);
        if (total > cap / 2) throw new IllegalArgumentException("message too large: " + payload.length);
        long head = head();
        long tail = tail();
        int off = (int) (head % cap);
        long wasted = (off + total > cap) ? (cap - off) : 0;
        if (cap - (head - tail) < total + wasted) return false;
        if (wasted > 0) {
            buf.putInt(data(head), -1);
            head += wasted;
        }
        int p = data(head);
        buf.putInt(p, payload.length);
        buf.putInt(p + 4, type);
        buf.put(p + 8, payload, 0, payload.length);
        LONG.setRelease(buf, base, head + total);
        return true;
    }

    /** Returns the next message or null. */
    public Message poll() {
        long tail = tail();
        long head = head();
        if (tail == head) return null;
        int p = data(tail);
        int len = buf.getInt(p);
        if (len == -1) {
            tail += cap - (int) (tail % cap);
            LONG.setRelease(buf, base + 8, tail);
            return poll();
        }
        int type = buf.getInt(p + 4);
        byte[] payload = new byte[len];
        buf.get(p + 8, payload, 0, len);
        LONG.setRelease(buf, base + 8, tail + align8(8 + len));
        return new Message(type, payload);
    }

    public record Message(int type, byte[] payload) {
        public ByteBuffer buffer() { return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN); }
    }
}
