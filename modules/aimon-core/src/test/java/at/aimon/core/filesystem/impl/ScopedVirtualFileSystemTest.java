package at.aimon.core.filesystem.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

@DisplayName("ScopedVirtualFileSystem — worktree path-prefix decorator (Phase 4 §4.3a)")
class ScopedVirtualFileSystemTest {

    private LocalFileSystem base;
    private String basePath;

    private VirtualFileSystem scoped(Path tempDir) {
        this.basePath = tempDir.toString();
        this.base = new LocalFileSystem(new LocalFileSystemConfig(basePath));
        base.initialize();
        return new ScopedVirtualFileSystem(base, ".worktrees/branch0");
    }

    @Test
    @DisplayName("writes land in the branch subtree; two branches over the same logical path do not clobber")
    void disjointSubtrees(@TempDir Path tempDir) {
        base = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        base.initialize();
        final VirtualFileSystem a = new ScopedVirtualFileSystem(base, ".worktrees/a");
        final VirtualFileSystem b = new ScopedVirtualFileSystem(base, ".worktrees/b");

        a.write("out.txt", "from-a");
        b.write("out.txt", "from-b");

        assertThat(new String(readAll(a.read("out.txt")))).isEqualTo("from-a");
        assertThat(new String(readAll(b.read("out.txt")))).isEqualTo("from-b");
        assertThat(base.exists(".worktrees/a/out.txt")).isTrue();
        assertThat(base.exists(".worktrees/b/out.txt")).isTrue();
    }

    @Test
    @DisplayName("branch-relative write/read round-trips")
    void relativeRoundTrip(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        vfs.write("dir/f.txt", "hi");
        assertThat(new String(readAll(vfs.read("dir/f.txt")))).isEqualTo("hi");
        assertThat(vfs.exists("dir/f.txt")).isTrue();
    }

    @Test
    @DisplayName("absolute base-anchored input paths are stripped and re-scoped (LLM sends absolute)")
    void absolutePathRoundTrip(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        // The LLM is told the base working dir and emits <base>/f.txt; scope() strips <base> then prepends the prefix.
        vfs.write(basePath + "/f.txt", "abs");
        assertThat(new String(readAll(vfs.read(basePath + "/f.txt")))).isEqualTo("abs");
        assertThat(base.exists(".worktrees/branch0/f.txt")).isTrue();
    }

    @Test
    @DisplayName("getWorkingDirectory() is branch-relative '.' and round-trips through list/read (grep default root)")
    void workingDirectoryRoundTrips(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        vfs.write("a.txt", "one");
        vfs.write("sub/b.txt", "two");

        assertThat(vfs.getWorkingDirectory()).isEqualTo(".");
        assertThat(vfs.isDirectory(vfs.getWorkingDirectory())).isTrue();

        final List<String> all = vfs.listRecursive(vfs.getWorkingDirectory());
        // Every returned path is branch-relative (no leaked ".worktrees/branch0/" prefix) and re-feeds without failure.
        assertThat(all).allSatisfy(p -> {
            assertThat(p).doesNotStartWith(".worktrees");
            assertThat(vfs.exists(p)).isTrue();
        });
        assertThat(all).contains("a.txt", "sub/b.txt");
    }

    @Test
    @DisplayName("close()/initialize() are no-ops on the borrowed delegate")
    void borrowNotOwn(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        vfs.close();
        vfs.initialize();
        // The delegate is still usable after the decorator's close().
        base.write("still/alive.txt", "yes");
        assertThat(base.exists("still/alive.txt")).isTrue();
    }

    @Test
    @DisplayName("rejects a '../' that would escape the branch prefix into a sibling branch")
    void rejectsTraversalEscape(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        assertThatThrownBy(() -> vfs.read("../../etc/passwd")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> vfs.write("../sibling/f.txt", "x")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    @DisplayName("rejects any path containing a backslash (Windows-hosted backend escape vector)")
    void rejectsBackslashSeparators(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        // "..\..\x" would pass the branch-escape check as a single opaque segment if not rejected up front.
        assertThatThrownBy(() -> vfs.read("..\\..\\x")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("backslash");
        assertThatThrownBy(() -> vfs.write("a\\b", "x")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("backslash");
        assertThatThrownBy(() -> vfs.exists("a\\b.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("backslash");
    }

    @Test
    @DisplayName("a sibling-prefix absolute path (base + '2/...') is not mistaken for a child of the base")
    void siblingPrefixIsNotInsideBase(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        // Shares the base as a string prefix but not on a '/' boundary: it is a sibling, i.e. outside the base.
        final String sibling = basePath + "2/file.txt";
        assertThatThrownBy(() -> vfs.write(sibling, "x")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("outside the base working directory");
        assertThatThrownBy(() -> vfs.read(sibling)).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> vfs.exists(sibling)).isInstanceOf(InvalidPathException.class);
        // Nothing was silently remapped into the branch subtree.
        assertThat(base.exists(".worktrees/branch0/file.txt")).isFalse();
    }

    @Test
    @DisplayName("an absolute path outside the base working directory is rejected, not remapped into the branch")
    void rejectsAbsolutePathOutsideBase(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        assertThatThrownBy(() -> vfs.read("/somewhere/else/f.txt")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("outside the base working directory");
        assertThatThrownBy(() -> vfs.write("/etc/passwd", "x")).isInstanceOf(InvalidPathException.class);
        assertThat(base.exists(".worktrees/branch0/somewhere/else/f.txt")).isFalse();
    }

    @Test
    @DisplayName("getMetadata() returns a branch-relative path that re-feeds without double-prefixing")
    void metadataPathRoundTrips(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        vfs.write("dir/f.txt", "meta");

        final FileMetadata metadata = vfs.getMetadata("dir/f.txt");
        assertThat(metadata.getPath()).isEqualTo("dir/f.txt");
        assertThat(metadata.getPath()).doesNotStartWith(".worktrees");
        assertThat(metadata.getSize()).isEqualTo("meta".length());
        assertThat(metadata.isDirectory()).isFalse();

        // Re-feeding the returned path through the same decorator resolves the same file (no double-prefix).
        assertThat(vfs.exists(metadata.getPath())).isTrue();
        assertThat(vfs.getMetadata(metadata.getPath()).getPath()).isEqualTo("dir/f.txt");
    }

    @Test
    @DisplayName("list('.') and search('.') return branch-relative paths that re-feed through the same decorator")
    void listAndSearchRoundTrip(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        vfs.write("a.txt", "one");
        vfs.write("sub/b.txt", "two");

        final List<String> listed = vfs.list(".");
        assertThat(listed).contains("a.txt", "sub");
        assertThat(listed).allSatisfy(p -> {
            assertThat(p).doesNotStartWith(".worktrees");
            assertThat(vfs.exists(p)).isTrue();
        });

        final List<String> found = vfs.search(".", "*.txt", 100);
        assertThat(found).contains("a.txt", "sub/b.txt");
        assertThat(found).allSatisfy(p -> {
            assertThat(p).doesNotStartWith(".worktrees");
            assertThat(vfs.exists(p)).isTrue();
        });
    }

    @Test
    @DisplayName("constructor rejects a prefix that normalizes to empty or escapes above its own root")
    void constructorValidatesPrefix(@TempDir Path tempDir) {
        base = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        base.initialize();

        assertThatThrownBy(() -> new ScopedVirtualFileSystem(base, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedVirtualFileSystem(base, ".")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedVirtualFileSystem(base, "/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedVirtualFileSystem(base, "../escape"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedVirtualFileSystem(base, "a/../.."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedVirtualFileSystem(base, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedVirtualFileSystem(null, ".worktrees/x"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("'dir/../a.txt' normalizes inside the branch while '../x' is rejected")
    void inBoundsTraversalResolves(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);
        vfs.write("a.txt", "in-bounds");

        // '..' that stays inside the branch root is resolved, not rejected.
        assertThat(vfs.exists("dir/../a.txt")).isTrue();
        assertThat(new String(readAll(vfs.read("dir/../a.txt")))).isEqualTo("in-bounds");

        // '..' from the branch root escapes the prefix and is rejected.
        assertThatThrownBy(() -> vfs.read("../x")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    @DisplayName("URI-shaped base (gridfs://, s3://): leading-slash inputs are scoped into the branch, not rejected")
    void uriShapedBaseAcceptsLeadingSlash(@TempDir Path tempDir) {
        base = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        base.initialize();
        // GridFS/S3 report URI-shaped working dirs and ACCEPT root-anchored paths, so the decorator must scope a
        // leading-slash input into the branch (like the base == "/" case), never reject it as outside-base.
        final VirtualFileSystem uriBase = withWorkingDir(base, "s3://bucket");
        final VirtualFileSystem vfs = new ScopedVirtualFileSystem(uriBase, ".worktrees/uri");

        vfs.write("/report.md", "hi");

        assertThat(new String(readAll(vfs.read("/report.md")))).isEqualTo("hi");
        assertThat(base.exists(".worktrees/uri/report.md")).isTrue();
    }

    /** Forwards everything to {@code delegate} but reports the given working directory (a URI-shaped-base stand-in). */
    private static VirtualFileSystem withWorkingDir(VirtualFileSystem delegate, String workingDir) {
        return (VirtualFileSystem) java.lang.reflect.Proxy.newProxyInstance(VirtualFileSystem.class.getClassLoader(),
                new Class<?>[]{VirtualFileSystem.class}, (proxy, method, args) -> {
                    if ("getWorkingDirectory".equals(method.getName())) {
                        return workingDir;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // --- shared prefixes and branch host paths (execution-environment design §4.2, stage 4) --------------------

    private VirtualFileSystem sharing(Path tempDir) {
        this.basePath = tempDir.toString();
        this.base = new LocalFileSystem(new LocalFileSystemConfig(basePath));
        base.initialize();
        base.write(".aimon-staged/n/k/x.sh", "echo staged");
        return new ScopedVirtualFileSystem(base, ".worktrees/k", Set.of(".aimon-staged"));
    }

    @Test
    @DisplayName("a shared prefix reaches the delegate's own directory in relative, ./, absolute and a/.. forms")
    void sharedPrefixRoutesToTheDelegate(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);

        for (final String path : List.of(".aimon-staged/n/k/x.sh", "./.aimon-staged/n/k/x.sh",
                basePath + "/.aimon-staged/n/k/x.sh", "a/../.aimon-staged/n/k/x.sh")) {
            assertThat(new String(readAll(vfs.read(path)))).as(path).isEqualTo("echo staged");
        }
        assertThat(base.exists(".worktrees/k/.aimon-staged")).isFalse();
    }

    @Test
    @DisplayName("a shared prefix matches whole segments only: .aimon-staged2/ is scoped into the branch")
    void sharedPrefixMatchesWholeSegments(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);

        vfs.write(".aimon-staged2/y.txt", "branch");

        assertThat(base.exists(".worktrees/k/.aimon-staged2/y.txt")).isTrue();
        assertThat(base.exists(".aimon-staged2/y.txt")).isFalse();
    }

    @Test
    @DisplayName("a shared prefix matches ignoring case, like the path rules: .AIMON-STAGED/ reaches the delegate's")
    void sharedPrefixMatchesIgnoringCase(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);

        for (final String prefix : List.of(".AIMON-STAGED", ".Aimon-Staged", ".aimon-\u017Ftaged")) {
            vfs.write(prefix + "/y.txt", "routed");

            assertThat(base.exists(prefix + "/y.txt")).as(prefix).isTrue();
            assertThat(base.exists(".worktrees/k/" + prefix)).as(prefix).isFalse();
        }
    }

    @Test
    @DisplayName("escaping above the root from a shared path is still rejected")
    void sharedPrefixEscapeRejected(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);

        assertThatThrownBy(() -> vfs.read(".aimon-staged/../../outside.txt")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    @DisplayName("staged files never show up in a branch listing")
    void sharedPrefixIsNotListedFromTheBranchRoot(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);
        vfs.write("mine.txt", "x");

        assertThat(vfs.listRecursive(".")).containsExactly("mine.txt");
    }

    @Test
    @DisplayName("the constructor without shared prefixes shares nothing")
    void noSharedPrefixesByDefault(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = scoped(tempDir);

        vfs.write(".aimon-staged/z.txt", "branch");

        assertThat(base.exists(".worktrees/branch0/.aimon-staged/z.txt")).isTrue();
        assertThat(base.exists(".aimon-staged/z.txt")).isFalse();
    }

    @Test
    @DisplayName("the branch root's host path is accepted: {base}/.worktrees/k/a is the branch's a")
    void branchHostPathMapsIntoTheBranch(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);

        vfs.write(basePath + "/.worktrees/k/a.txt", "via host path");

        assertThat(base.exists(".worktrees/k/a.txt")).isTrue();
        assertThat(base.exists(".worktrees/k/.worktrees/k/a.txt")).isFalse();
        assertThat(new String(readAll(vfs.read("a.txt")))).isEqualTo("via host path");
        assertThat(vfs.exists(basePath + "/.worktrees/k")).isTrue();
    }

    @Test
    @DisplayName("the branch root's host path matches after normalisation and ignoring case: ./, // and K all reach k")
    void branchHostPathMatchesEverySpelling(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);

        for (final String hostPath : List.of(basePath + "/./.worktrees/k/a.txt", basePath + "//.worktrees/k/b.txt",
                basePath + "/.worktrees/K/c.txt", basePath + "/x/../.worktrees/k/./d.txt")) {
            vfs.write(hostPath, "via host path");
        }

        assertThat(vfs.listRecursive(".")).containsExactlyInAnyOrder("a.txt", "b.txt", "c.txt", "d.txt");
        assertThat(base.exists(".worktrees/k/.worktrees")).isFalse();
        assertThat(base.exists(".worktrees/k/.worktrees/K")).isFalse();
        assertThat(vfs.isDirectory(basePath + "/.worktrees/K")).isTrue();
    }

    @Test
    @DisplayName("a host path that climbs out of the branch root and back into the base maps like any base path")
    void branchHostPathClimbingOutIsABasePath(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);

        vfs.write(basePath + "/.worktrees/k/../../e.txt", "base-anchored");

        assertThat(base.exists(".worktrees/k/e.txt")).isTrue();
        assertThatThrownBy(() -> vfs.write(basePath + "/.worktrees/k/../../../f.txt", "x"))
                .isInstanceOf(InvalidPathException.class);
    }

    @Test
    @DisplayName("a branch-local directory under a shared prefix (a shell made it) is left out of every listing")
    void branchLocalSharedDirectoryIsNotListed(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);
        vfs.write("mine.txt", "x");
        // What a shell in the branch root does: the file tools cannot, since every spelling routes to the delegate's.
        base.write(".worktrees/k/.aimon-staged/copied.sh", "shell copy");
        base.write(".worktrees/k/.Aimon-Staged/other.sh", "shell copy");

        assertThat(vfs.listRecursive(".")).containsExactly("mine.txt");
        assertThat(vfs.list(".")).containsExactly("mine.txt");
        assertThat(vfs.search(".", "*.sh", 10)).isEmpty();
        // The delegate's own shared directory is still listed through the shared prefix.
        assertThat(vfs.listRecursive(".aimon-staged")).contains(".aimon-staged/n/k/x.sh")
                .noneMatch(p -> p.contains("copied.sh"));
    }

    // --- reserved prefixes (EE-46) -----------------------------------------------------------------------------------

    private VirtualFileSystem reserving(Path tempDir) {
        this.basePath = tempDir.toString();
        this.base = new LocalFileSystem(new LocalFileSystemConfig(basePath));
        base.initialize();
        return new ScopedVirtualFileSystem(base, ".worktrees/k", Set.of(".aimon-staged"), Set.of(".worktrees"));
    }

    @Test
    @DisplayName("EE-46: a path under a reserved prefix is refused in every spelling, and nothing is written")
    void reservedPrefixIsRefusedInEverySpelling(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = reserving(tempDir);

        for (final String path : List.of(".worktrees/other/x", ".worktrees/k/x", ".worktrees", ".WORKTREES/other/x",
                "a/../.worktrees/other/x", "./.worktrees//other/x", basePath + "/.worktrees/other/x",
                basePath + "/.Worktrees/other/x", basePath + "/.worktrees/k/.worktrees/other/x",
                basePath + "/.worktrees")) {
            assertThatThrownBy(() -> vfs.write(path, "x")).as(path).isInstanceOf(InvalidPathException.class)
                    .hasMessageContaining("cannot address worktree directories");
            assertThatThrownBy(() -> vfs.exists(path)).as(path).isInstanceOf(InvalidPathException.class);
        }

        assertThat(base.exists(".worktrees")).isFalse();
    }

    @Test
    @DisplayName("EE-46: the branch's own host path and a deeper directory of the same name are not reserved")
    void reservedPrefixIsOnlyTheNameAtTheBranchRoot(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = reserving(tempDir);

        vfs.write(basePath + "/.worktrees/k/a.txt", "host path");
        vfs.write(basePath + "/.worktrees/K/b.txt", "host path, other case");
        vfs.write("docs/.worktrees/c.txt", "deeper");
        vfs.write(".worktrees2/d.txt", "another name");

        assertThat(vfs.listRecursive(".")).containsExactlyInAnyOrder("a.txt", "b.txt", "docs/.worktrees/c.txt",
                ".worktrees2/d.txt");
        assertThat(base.exists(".worktrees/k/.worktrees")).isFalse();
    }

    @Test
    @DisplayName("EE-46: a branch-local directory under a reserved prefix (a shell made it) is left out of every listing")
    void branchLocalReservedDirectoryIsNotListed(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = reserving(tempDir);
        vfs.write("mine.txt", "x");
        base.write(".worktrees/k/.worktrees/other/planted.sh", "shell-written");
        base.write(".worktrees/k/.Worktrees/k/planted.sh", "shell-written");

        assertThat(vfs.listRecursive(".")).containsExactly("mine.txt");
        assertThat(vfs.list(".")).containsExactly("mine.txt");
        assertThat(vfs.search(".", "*.sh", 10)).isEmpty();
    }

    @Test
    @DisplayName("EE-46: a scope built without reserved prefixes keeps every name — the control-store scope is one")
    void noReservedPrefixesByDefault(@TempDir Path tempDir) {
        final VirtualFileSystem vfs = sharing(tempDir);

        vfs.write(".worktrees/other/x.txt", "an ordinary path here");

        assertThat(base.exists(".worktrees/k/.worktrees/other/x.txt")).isTrue();
        assertThat(vfs.listRecursive(".")).containsExactly(".worktrees/other/x.txt");
        assertThatThrownBy(() -> new ScopedVirtualFileSystem(base, ".worktrees/k", Set.of(), Set.of("../x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved prefix");
    }

    private static byte[] readAll(java.io.InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
