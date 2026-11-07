package at.aimon.core.tools.code.shell;

/**
 * The last {@code capacity} bytes given to it, in order: the program's standard error kept by
 * {@link ShellProgramRun}, trimmed from the front ({@code docs/design/tool/programmatic-tool-calling-shell.md} §7).
 *
 * <p>
 * A ring of bytes. It grows by doubling from {@link #INITIAL_BYTES} up to {@code capacity}, so a huge cap does not
 * allocate up front, and once full it is never reallocated: appending {@code n} bytes costs {@code O(n)} amortized
 * however often the program writes, where copying the kept tail on every chunk cost {@code O(n²)}.
 *
 * <p>
 * Not thread-safe: the caller holds its own lock.
 */
final class ByteTail {

    /** The first allocation, the smaller of this and {@code capacity}. */
    static final int INITIAL_BYTES = 8 * 1024;

    private final int capacity;
    private byte[] ring = new byte[0];
    private int start;
    private int size;
    /** How many arrays were allocated, for the linearity test. */
    private int allocations;

    ByteTail(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * Appends {@code bytes}, dropping the oldest bytes beyond {@code capacity}.
     *
     * @param bytes
     *            the bytes to keep at the end
     */
    void append(byte[] bytes) {
        if (capacity == 0 || bytes.length == 0) {
            return;
        }
        if (bytes.length >= capacity) {
            ensureRoom(capacity);
            System.arraycopy(bytes, bytes.length - capacity, ring, 0, capacity);
            start = 0;
            size = capacity;
            return;
        }
        ensureRoom((int) Math.min((long) size + bytes.length, capacity));
        final int overflow = size + bytes.length - ring.length;
        if (overflow > 0) {
            start = (start + overflow) % ring.length;
            size -= overflow;
        }
        final int end = (start + size) % ring.length;
        final int first = Math.min(bytes.length, ring.length - end);
        System.arraycopy(bytes, 0, ring, end, first);
        System.arraycopy(bytes, first, ring, 0, bytes.length - first);
        size += bytes.length;
    }

    /**
     * Returns the kept bytes, oldest first, as a new array.
     *
     * @return a copy of the kept bytes
     */
    byte[] toArray() {
        return copyInto(new byte[size]);
    }

    int size() {
        return size;
    }

    int allocations() {
        return allocations;
    }

    /** Grows the ring, by doubling, until it can hold {@code needed} bytes, laid out from index 0. */
    private void ensureRoom(int needed) {
        if (needed <= ring.length) {
            return;
        }
        int next = Math.max(ring.length, Math.min(INITIAL_BYTES, capacity));
        while (next < needed) {
            next = (int) Math.min(2L * next, capacity);
        }
        final byte[] grown = copyInto(new byte[next]);
        allocations++;
        ring = grown;
        start = 0;
    }

    /** Copies the kept bytes, oldest first, to the start of {@code target}. */
    private byte[] copyInto(byte[] target) {
        final int first = Math.min(size, ring.length - start);
        System.arraycopy(ring, start, target, 0, first);
        System.arraycopy(ring, 0, target, first, size - first);
        return target;
    }
}
