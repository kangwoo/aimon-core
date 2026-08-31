package at.aimon.filesystem.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InsufficientStorageException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;

/**
 * The behaviour every {@link VirtualFileSystem} backend owes its callers, executed against each one.
 *
 * <p>
 * Its subject is the part of the contract that is <b>not</b> obvious from a method signature and that a backend can
 * therefore drift out of without anyone noticing: what a directory is, which listing includes them, what happens when
 * a path names the wrong kind of thing. That drift is not hypothetical — the GridFS backend could not represent an
 * empty directory at all, omitted subdirectories from {@code list}, and answered {@code false} from {@code exists} for
 * a directory holding files. Nothing failed, because each backend's tests only ever described that backend.
 *
 * <p>
 * A backend joins by subclassing and returning a fresh, initialized filesystem from {@link #newFileSystem()}; this
 * class closes it. Anything a backend needs to reset between cases (dropping a bucket, cleaning a container) belongs
 * inside that factory method, because JUnit runs a superclass {@code @BeforeEach} before the subclass's own. A backend
 * that supports a per-file size cap overrides {@link #newFileSystem(long)} as well, which is the only way to run the
 * maximum-file-size cases — a backend that stays silent about it reports them as skipped.
 *
 * <p>
 * Assertions are on the contract, not on any one backend's phrasing. Where backends legitimately report a failure with
 * different exception subtypes, the assertion names the common supertype the contract actually promises — but never
 * merely {@code VirtualFileSystemException} where the distinction matters to a caller, which is why the
 * "wrong kind of thing" cases pin {@link InvalidPathException} and {@link FileNotFoundException} apart.
 */
public abstract class AbstractVirtualFileSystemContractTest {

    /** The root, spelled the way every backend accepts it. */
    protected static final String ROOT = ".";

    /** The cap the maximum-file-size cases configure. Small enough that a payload past it is a short string literal. */
    protected static final long CAP = 4;

    private VirtualFileSystem fs;

    /**
     * @return a fresh, already-initialized filesystem with no content, reset from any previous test
     */
    protected abstract VirtualFileSystem newFileSystem();

    /**
     * The same thing as {@link #newFileSystem()}, configured with a per-file byte cap so the maximum-file-size cases
     * have a subject. The returned filesystem is <b>not</b> the one {@link #fs()} hands out and is closed by the case
     * that asked for it.
     *
     * @param maxFileSize
     *            the cap in bytes, always a positive value here
     * @return the capped filesystem, or {@code null} if this backend has no such setting — the size cases then report
     *         as skipped rather than as passing, so "no cap" stays a statement a backend makes out loud
     */
    protected VirtualFileSystem newFileSystem(long maxFileSize) {
        return null;
    }

    /** @return the filesystem under test for the current case */
    protected final VirtualFileSystem fs() {
        return fs;
    }

    // Lifecycle hooks are `protected`, not package-private: subclasses live in other packages and other modules, and
    // JUnit has to be able to reach these through the hierarchy.
    @BeforeEach
    protected final void setUpContractSubject() {
        fs = newFileSystem();
    }

    @AfterEach
    protected final void tearDownContractSubject() {
        if (fs != null) {
            fs.close();
            fs = null;
        }
    }

    // --- directories exist ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a directory implied by a file it contains exists, and is a directory")
    protected void impliedDirectoryExists() {
        write("dir/a.txt", "a");

        assertThat(fs.exists("dir")).isTrue();
        assertThat(fs.isDirectory("dir")).isTrue();
        assertThat(fs.isDirectory("dir/a.txt")).isFalse();
    }

    @Test
    @DisplayName("an explicitly created directory survives with nothing in it")
    protected void emptyDirectorySurvives() {
        fs.createDirectory("empty");

        assertThat(fs.exists("empty")).isTrue();
        assertThat(fs.isDirectory("empty")).isTrue();
        assertThat(fs.list("empty")).isEmpty();
    }

    @Test
    @DisplayName("createDirectory creates intermediate levels and is idempotent")
    protected void createDirectoryIsMkdirP() {
        fs.createDirectory("a/b/c");
        fs.createDirectory("a/b/c");

        assertThat(fs.isDirectory("a")).isTrue();
        assertThat(fs.isDirectory("a/b")).isTrue();
        assertThat(fs.isDirectory("a/b/c")).isTrue();
    }

    @Test
    @DisplayName("a path that names nothing neither exists nor is a directory")
    protected void missingPathIsNeither() {
        assertThat(fs.exists("nope")).isFalse();
        assertThat(fs.isDirectory("nope")).isFalse();
    }

    @Test
    @DisplayName("metadata for a directory says so")
    protected void metadataReportsDirectory() {
        write("dir/a.txt", "a");

        assertThat(fs.getMetadata("dir").isDirectory()).isTrue();
        assertThat(fs.getMetadata("dir/a.txt").isDirectory()).isFalse();
    }

    // --- listing ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("list returns files and immediate subdirectories, and nothing deeper")
    protected void listReturnsFilesAndSubdirectories() {
        write("root.txt", "r");
        write("dir/nested.txt", "n");
        write("dir/deeper/x.txt", "x");

        assertThat(fs.list(ROOT)).containsExactlyInAnyOrder("root.txt", "dir");
        assertThat(fs.list("dir")).containsExactlyInAnyOrder("dir/nested.txt", "dir/deeper");
    }

    @Test
    @DisplayName("listRecursive returns files only — no directories at any depth")
    protected void listRecursiveReturnsFilesOnly() {
        write("root.txt", "r");
        write("dir/nested.txt", "n");
        write("dir/deeper/x.txt", "x");
        fs.createDirectory("empty");

        assertThat(fs.listRecursive(ROOT)).containsExactlyInAnyOrder("root.txt", "dir/nested.txt", "dir/deeper/x.txt");
    }

    @Test
    @DisplayName("listing something that is not there fails rather than returning nothing")
    protected void listRejectsMissingDirectory() {
        assertThatThrownBy(() -> fs.list("nope")).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> fs.listRecursive("nope")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    @DisplayName("listing a regular file fails as a path error, not as a missing path")
    protected void listRejectsRegularFile() {
        write("a.txt", "a");

        assertThatThrownBy(() -> fs.list("a.txt")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.listRecursive("a.txt")).isInstanceOf(InvalidPathException.class);
    }

    // --- deletion ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("delete removes an empty directory")
    protected void deleteRemovesEmptyDirectory() {
        fs.createDirectory("empty");

        fs.delete("empty");

        assertThat(fs.exists("empty")).isFalse();
    }

    @Test
    @DisplayName("delete refuses a populated directory and leaves its contents alone")
    protected void deleteRefusesPopulatedDirectory() {
        write("dir/a.txt", "a");

        assertThatThrownBy(() -> fs.delete("dir")).isInstanceOf(VirtualFileSystemException.class)
                .isNotInstanceOf(FileNotFoundException.class);
        assertThat(fs.exists("dir/a.txt")).isTrue();
    }

    @Test
    @DisplayName("delete of a path that names nothing is a missing-file failure")
    protected void deleteRejectsMissingPath() {
        assertThatThrownBy(() -> fs.delete("nope")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    @DisplayName("deleteRecursive removes the whole subtree, and only it")
    protected void deleteRecursiveRemovesSubtree() {
        write("keep.txt", "k");
        write("dir/a.txt", "a");
        write("dir/deeper/b.txt", "b");

        fs.deleteRecursive("dir");

        assertThat(fs.exists("dir")).isFalse();
        assertThat(fs.exists("dir/a.txt")).isFalse();
        assertThat(fs.exists("dir/deeper/b.txt")).isFalse();
        assertThat(fs.exists("keep.txt")).isTrue();
    }

    // --- files and directories do not collide -----------------------------------------------------------------------

    @Test
    @DisplayName("a write cannot land on a path that is already a directory")
    protected void writeOverDirectoryIsRejected() {
        write("dir/a.txt", "a");

        assertThatThrownBy(() -> write("dir", "clobber")).isInstanceOf(VirtualFileSystemException.class);
        assertThat(fs.isDirectory("dir")).isTrue();
        assertThat(readBack("dir/a.txt")).isEqualTo("a");
    }

    @Test
    @DisplayName("createDirectory cannot land on a path that is already a file")
    protected void createDirectoryOverFileIsRejected() {
        write("a.txt", "a");

        assertThatThrownBy(() -> fs.createDirectory("a.txt")).isInstanceOf(VirtualFileSystemException.class);
        assertThat(readBack("a.txt")).isEqualTo("a");
    }

    @Test
    @DisplayName("reading a directory fails as a path error, not as a missing file")
    protected void readingADirectoryIsAPathError() {
        write("dir/a.txt", "a");

        assertThatThrownBy(() -> fs.read("dir")).isInstanceOf(InvalidPathException.class);
    }

    // --- usage ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("usage covers the whole filesystem, and the path overload covers just that subtree")
    protected void usageSummaryScopesToSubtree() {
        write("a.txt", "1");
        write("dir/b.txt", "22");
        write("dir/deeper/c.txt", "333");

        final FileSystemUsage all = fs.getUsageSummary();
        assertThat(all.getFileCount()).isEqualTo(3);
        assertThat(all.getTotalSize()).isEqualTo(6);

        final FileSystemUsage subtree = fs.getUsageSummary("dir");
        assertThat(subtree.getFileCount()).isEqualTo(2);
        assertThat(subtree.getTotalSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("an empty filesystem reports zero usage rather than failing")
    protected void usageSummaryOfEmptyFilesystem() {
        final FileSystemUsage usage = fs.getUsageSummary();

        assertThat(usage.getFileCount()).isZero();
        assertThat(usage.getTotalSize()).isZero();
    }

    // --- maximum file size ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a write of exactly the cap is stored")
    protected void writeAtExactlyTheCapIsAccepted() {
        try (VirtualFileSystem capped = cappedFileSystem(CAP)) {
            write(capped, "at-cap.txt", "abcd");

            assertThat(readBack(capped, "at-cap.txt")).isEqualTo("abcd");
        }
    }

    @Test
    @DisplayName("a write past the cap is refused as insufficient storage, and nothing is stored")
    protected void writeBeyondTheCapIsRejected() {
        try (VirtualFileSystem capped = cappedFileSystem(CAP)) {
            assertThatThrownBy(() -> write(capped, "too-big.txt", "abcde"))
                    .isInstanceOf(InsufficientStorageException.class);

            assertThat(capped.exists("too-big.txt")).isFalse();
        }
    }

    @Test
    @DisplayName("a refused write never leaves a truncated prefix where the file used to be")
    protected void rejectedWriteLeavesNoTruncatedPrefix() {
        try (VirtualFileSystem capped = cappedFileSystem(CAP)) {
            write(capped, "f.txt", "old");

            assertThatThrownBy(() -> write(capped, "f.txt", "abcde")).isInstanceOf(InsufficientStorageException.class);

            // Backends legitimately differ on whether the path survives — GridFS and S3 never touch the stored object
            // until the whole payload is accepted, so the previous content stands, while the local backend has already
            // truncated the file it was writing into and removes it. What none of them may do is leave a prefix of the
            // refused content, because a later reader has no way to tell that from a complete file.
            if (capped.exists("f.txt")) {
                assertThat(readBack(capped, "f.txt")).isEqualTo("old");
            }
        }
    }

    @Test
    @DisplayName("the streaming path enforces the same cap, and stores no more than it")
    protected void streamingWriteBeyondTheCapIsRejected() throws IOException {
        try (VirtualFileSystem capped = cappedFileSystem(CAP)) {
            try (OutputStream out = capped.openOutputStream("streamed.txt")) {
                out.write("ab".getBytes(StandardCharsets.UTF_8));

                assertThatThrownBy(() -> out.write("cde".getBytes(StandardCharsets.UTF_8)))
                        .isInstanceOf(InsufficientStorageException.class);
            }

            // Whether the accepted prefix survives is the streaming path's one documented divergence: the caller owns
            // the stream, so a backend that writes straight through keeps what it already wrote, and one that stages
            // the payload discards all of it. Both are within the contract; storing more than the cap is not.
            if (capped.exists("streamed.txt")) {
                assertThat(capped.getMetadata("streamed.txt").getSize()).isLessThanOrEqualTo(CAP);
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    /** Writes {@code content} to {@code path}, declaring the length the way a real caller would. */
    protected final void write(String path, String content) {
        write(fs, path, content);
    }

    /** Writes {@code content} to {@code path} of a filesystem other than the one under test. */
    protected static void write(VirtualFileSystem target, String path, String content) {
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        target.write(path, new ByteArrayInputStream(bytes), bytes.length);
    }

    /** Reads {@code path} back as a UTF-8 string. */
    protected final String readBack(String path) {
        return readBack(fs, path);
    }

    /** Reads {@code path} back as a UTF-8 string from a filesystem other than the one under test. */
    protected static String readBack(VirtualFileSystem target, String path) {
        try (InputStream in = target.read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read back " + path, e);
        }
    }

    /**
     * @return a capped filesystem from {@link #newFileSystem(long)}, aborting the case as skipped when the backend has
     *         no such setting
     */
    private VirtualFileSystem cappedFileSystem(long maxFileSize) {
        final VirtualFileSystem capped = newFileSystem(maxFileSize);
        assumeTrue(capped != null, "backend has no maximum file size setting");
        return capped;
    }
}
