package at.aimon.core.filesystem;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

import at.aimon.core.filesystem.exception.InsufficientStorageException;

/**
 * An {@link OutputStream} decorator that caps the total number of bytes written to the wrapped stream.
 *
 * <p>
 * Each write is checked against a running byte counter <em>before</em> the bytes are delegated downstream; a write that
 * would push the cumulative total past {@code maxBytes} throws {@link InsufficientStorageException} and no bytes from
 * that write reach the wrapped stream. This is the enforcement point every backend shares for the
 * {@link VirtualFileSystem#NO_MAX_FILE_SIZE maximum file size} contract, so a backend's bulk-write and streaming APIs
 * cannot disagree about where the limit is — and neither can two backends.
 *
 * <p>
 * It lives in the neutral {@code at.aimon.core.filesystem} package rather than next to the local backend that first
 * needed it, because backends in other modules cannot import {@code at.aimon.core.filesystem.impl..} (ArchUnit blocks
 * it) and would otherwise each grow their own counter.
 *
 * <p>
 * A cap of {@link VirtualFileSystem#NO_MAX_FILE_SIZE} means unlimited and every write passes through unchecked. That
 * value is accepted so a backend whose own stream type extends this class (see {@link #onLimitExceeded()}) can be
 * constructed unconditionally; callers that merely decorate should prefer {@link #wrap(OutputStream, long)}, which
 * skips the decorator entirely when no cap is configured.
 *
 * <p>
 * The decorator owns no resources of its own — {@link #flush()} and {@link #close()} delegate to the wrapped stream. It
 * is <strong>not</strong> thread-safe; a single output stream is expected to be written by one thread at a time.
 */
public class SizeLimitedOutputStream extends OutputStream {

    private final OutputStream delegate;
    private final long maxBytes;
    private long written;

    /**
     * Creates a size-limited output stream.
     *
     * @param delegate
     *            the stream to forward capped writes to (not null)
     * @param maxBytes
     *            the maximum number of bytes permitted through this stream (&gt;= 0), or
     *            {@link VirtualFileSystem#NO_MAX_FILE_SIZE} for no limit
     * @throws NullPointerException
     *             if delegate is null
     * @throws IllegalArgumentException
     *             if maxBytes is negative and not {@link VirtualFileSystem#NO_MAX_FILE_SIZE}
     */
    public SizeLimitedOutputStream(OutputStream delegate, long maxBytes) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate cannot be null");
        if (maxBytes < VirtualFileSystem.NO_MAX_FILE_SIZE) {
            throw new IllegalArgumentException("maxBytes must be >= 0 or NO_MAX_FILE_SIZE ("
                    + VirtualFileSystem.NO_MAX_FILE_SIZE + "), got: " + maxBytes);
        }
        this.maxBytes = maxBytes;
    }

    /**
     * Decorates {@code delegate} with a size cap, or hands it back untouched when no cap is configured.
     *
     * @param delegate
     *            the stream to cap (not null)
     * @param maxBytes
     *            the cap in bytes, or {@link VirtualFileSystem#NO_MAX_FILE_SIZE} for no limit
     * @return the capped stream, or {@code delegate} itself when unlimited
     */
    public static OutputStream wrap(OutputStream delegate, long maxBytes) {
        Objects.requireNonNull(delegate, "Delegate cannot be null");
        return maxBytes == VirtualFileSystem.NO_MAX_FILE_SIZE
                ? delegate
                : new SizeLimitedOutputStream(delegate, maxBytes);
    }

    @Override
    public void write(int b) throws IOException {
        ensureCapacity(1);
        delegate.write(b);
        written++;
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.requireNonNull(b, "Buffer cannot be null");
        Objects.checkFromIndexSize(off, len, b.length);
        ensureCapacity(len);
        delegate.write(b, off, len);
        written += len;
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    /** @return how many bytes have passed through so far, always within the cap */
    protected final long bytesWritten() {
        return written;
    }

    /**
     * Called once, immediately before the rejecting {@link InsufficientStorageException} is thrown, so a subclass can
     * discard whatever the backend has already accepted — GridFS aborts the upload to reclaim its chunks, S3 marks the
     * buffered object so {@code close()} does not put it. The default does nothing, which is right for a backend whose
     * partial output is a plain file the caller can still see.
     *
     * <p>
     * An implementation may throw; the backend failure then replaces the storage exception, which is the honest
     * outcome when the cleanup itself could not be performed.
     *
     * @throws IOException
     *             if the cleanup fails
     */
    protected void onLimitExceeded() throws IOException {
        // Default: nothing to undo.
    }

    /**
     * Verifies that {@code additional} more bytes can be written without exceeding the cap. The subtraction form
     * ({@code additional > maxBytes - written}) avoids {@code long} overflow because the invariant
     * {@code written <= maxBytes} always holds, so {@code maxBytes - written} is non-negative.
     */
    private void ensureCapacity(int additional) throws IOException {
        if (maxBytes == VirtualFileSystem.NO_MAX_FILE_SIZE) {
            return;
        }
        if (additional > maxBytes - written) {
            onLimitExceeded();
            throw new InsufficientStorageException(
                    "File content exceeds maximum allowed size of " + maxBytes + " bytes");
        }
    }
}
