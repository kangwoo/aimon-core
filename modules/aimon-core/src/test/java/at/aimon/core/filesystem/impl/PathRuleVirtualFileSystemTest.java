package at.aimon.core.filesystem.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.environment.DelegatingFileSystem;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

@DisplayName("PathRuleVirtualFileSystem")
class PathRuleVirtualFileSystemTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem raw;
    private VirtualFileSystem fs;
    private String base;

    @BeforeEach
    void setUp() {
        raw = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        raw.initialize();
        raw.write(".aimon/skills/s/SKILL.md", "secret");
        raw.write(".aimon-staged/n/k/x.sh", "staged");
        raw.write("src/a.txt", "work");
        fs = new PathRuleVirtualFileSystem(raw, List.of(PathRule.deny(".aimon"), PathRule.readOnly(".aimon-staged")));
        base = raw.getWorkingDirectory();
    }

    @AfterEach
    void tearDown() {
        raw.close();
    }

    @Test
    @DisplayName("DENY hides the prefix from exists, isDirectory, list, listRecursive and search")
    void denyHides() {
        assertThat(fs.exists(".aimon/skills/s/SKILL.md")).isFalse();
        assertThat(fs.isDirectory(".aimon")).isFalse();
        assertThat(fs.list(".")).contains("src", ".aimon-staged").doesNotContain(".aimon");
        assertThat(fs.listRecursive(".")).contains("src/a.txt", ".aimon-staged/n/k/x.sh")
                .noneMatch(p -> p.startsWith(".aimon/"));
        assertThat(fs.search(".", "*.md", 100)).noneMatch(p -> p.startsWith(".aimon/"));
    }

    @Test
    @DisplayName("DENY blocks reading, metadata, writing, deleting, moving and copying")
    void denyBlocks() {
        final String secret = ".aimon/skills/s/SKILL.md";
        assertThatThrownBy(() -> fs.read(secret)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.openInputStream(secret)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.getMetadata(secret)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.write(secret, "x")).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.delete(secret)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.move(secret, "stolen.md", true)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.copy(secret, "stolen.md", true)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.copy("src/a.txt", ".aimon/x", true)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.list(".aimon")).isInstanceOf(FileAccessDeniedException.class);
    }

    @Test
    @DisplayName("READ_ONLY allows reads and blocks every write into the prefix")
    void readOnly() throws Exception {
        final String staged = ".aimon-staged/n/k/x.sh";
        assertThat(fs.exists(staged)).isTrue();
        assertThat(new String(fs.read(staged).readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("staged");
        assertThat(fs.getMetadata(staged).getSize()).isEqualTo(6);
        assertThatThrownBy(() -> fs.write(staged, "x")).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.write(".aimon-staged/n/k/new", "x")).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.delete(staged)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.move(staged, "out.sh", true)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.copy("src/a.txt", ".aimon-staged/n/k/a", true))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.openOutputStream(staged)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.createDirectory(".aimon-staged/new")).isInstanceOf(FileAccessDeniedException.class);
        fs.copy(staged, "copied.sh", true);
        assertThat(raw.exists("copied.sh")).isTrue();
    }

    @Test
    @DisplayName("./ prefixes, .. traversal and absolute paths under the base are all caught")
    void normalisedMatching() {
        assertThat(fs.exists("./.aimon/skills/s/SKILL.md")).isFalse();
        assertThat(fs.exists("src/../.aimon/skills/s/SKILL.md")).isFalse();
        assertThat(fs.exists(base + "/.aimon/skills/s/SKILL.md")).isFalse();
        assertThatThrownBy(() -> fs.write("src/../.aimon/x", "x")).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.write(base + "/.aimon-staged/n/k/y", "x"))
                .isInstanceOf(FileAccessDeniedException.class);
        fs.write(base + "/.aimon2/ok.txt", "fine");
        assertThat(fs.exists(".aimon2/ok.txt")).isTrue();
    }

    @Test
    @DisplayName("a path that leaves the base and comes back is matched where it lands (review 2, blocking 1)")
    void roundTripPathsCaught() {
        final String name = tempDir.getFileName().toString();
        for (String secret : List.of("../" + name + "/.aimon/skills/s/SKILL.md",
                base + "/../" + name + "/.aimon/skills/s/SKILL.md",
                "src/../../" + name + "/.aimon/skills/s/SKILL.md")) {
            assertThat(fs.exists(secret)).as(secret).isFalse();
            assertThatThrownBy(() -> fs.read(secret)).as(secret).isInstanceOf(FileAccessDeniedException.class);
            assertThatThrownBy(() -> fs.openInputStream(secret)).as(secret)
                    .isInstanceOf(FileAccessDeniedException.class);
            assertThatThrownBy(() -> fs.write(secret, "x")).as(secret).isInstanceOf(FileAccessDeniedException.class);
        }
        for (String staged : List.of("../" + name + "/.aimon-staged/n/k/x.sh",
                base + "/../" + name + "/.aimon-staged/evil.txt")) {
            assertThatThrownBy(() -> fs.write(staged, "x")).as(staged).isInstanceOf(FileAccessDeniedException.class);
            assertThatThrownBy(() -> fs.delete(staged)).as(staged).isInstanceOf(FileAccessDeniedException.class);
        }
        assertThat(raw.exists(".aimon-staged/evil.txt")).isFalse();
        assertThatThrownBy(() -> fs.deleteRecursive("../" + name)).isInstanceOf(FileAccessDeniedException.class);
        // An ordinary file reached the same roundabout way is still an ordinary file.
        assertThat(fs.exists("../" + name + "/src/a.txt")).isTrue();
    }

    @Test
    @DisplayName("a path that lands outside the base fails closed instead of passing through")
    void outsideFailsClosed() {
        for (String outside : List.of("../elsewhere.txt", "/etc/hosts", base + "/../sibling/x")) {
            assertThat(fs.exists(outside)).as(outside).isFalse();
            assertThatThrownBy(() -> fs.read(outside)).as(outside).isInstanceOf(FileAccessDeniedException.class);
            assertThatThrownBy(() -> fs.write(outside, "x")).as(outside).isInstanceOf(FileAccessDeniedException.class);
        }
    }

    @Test
    @DisplayName("prefixes match ignoring case, so a case-insensitive store cannot be reached by respelling")
    void caseInsensitivePrefixes() {
        assertThat(fs.exists(".AIMON/skills/s/SKILL.md")).isFalse();
        assertThatThrownBy(() -> fs.read(".AIMON/skills/s/SKILL.md")).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.write(".Aimon/x", "x")).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.write(".Aimon-Staged/n/k/x.sh", "x")).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.deleteRecursive(".AIMON-STAGED")).isInstanceOf(FileAccessDeniedException.class);
        assertThat(PathRule.deny(".aimon").covers(".AIMON/x")).isTrue();
        assertThat(PathRule.deny(".aimon").covers(".AIMON2/x")).isFalse();
    }

    @Test
    @DisplayName("a Unicode letter that folds onto a rule's name does not bypass it (U+017F LONG S)")
    void unicodeFoldedPrefixes() throws IOException {
        assertThatThrownBy(() -> fs.write(".aimon-\u017Ftaged/n/k/x.sh", "PWNED"))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThat(Files.readString(tempDir.resolve(".aimon-staged/n/k/x.sh"))).isEqualTo("staged");

        raw.write(".secrets/key", "top secret");
        final VirtualFileSystem secrets = new PathRuleVirtualFileSystem(raw, List.of(PathRule.deny(".secrets")));
        assertThat(secrets.exists(".\u017Fecrets/key")).isFalse();
        assertThatThrownBy(() -> secrets.read(".\u017Fecrets/key")).isInstanceOf(FileAccessDeniedException.class);
        assertThat(PathRule.deny(".secrets").covers(".\u017Fecrets/key")).isTrue();

        // U+1E9E CAPITAL SHARP S folds to "ss": APFS opens .ssh/id through it
        raw.write(".ssh/id", "private key");
        final VirtualFileSystem ssh = new PathRuleVirtualFileSystem(raw, List.of(PathRule.deny(".ssh")));
        for (String alias : List.of(".\u1E9Eh/id", ".\u00DFh/id", ".s\u017Fh/id", ".SSH/id")) {
            assertThat(ssh.exists(alias)).as(alias).isFalse();
            assertThatThrownBy(() -> ssh.read(alias)).as(alias).isInstanceOf(FileAccessDeniedException.class);
        }
        assertThat(PathRule.deny(".ssh").covers(".\u1E9Eh/id")).isTrue();
    }

    @Test
    @DisplayName("deleting or moving a directory that contains a protected prefix is refused")
    void subtreeRefused() {
        assertThatThrownBy(() -> fs.deleteRecursive(".")).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> fs.move(".", "elsewhere", true)).isInstanceOf(FileAccessDeniedException.class);
        fs.deleteRecursive("src");
        assertThat(raw.exists("src/a.txt")).isFalse();
    }

    @Test
    @DisplayName("metadata passes through unchanged, etag included, and so does the working directory")
    void metadataPassThrough() {
        final Instant t = Instant.parse("2026-01-01T00:00:00Z");
        final VirtualFileSystem tagged = new DelegatingFileSystem(raw) {
            @Override
            public FileMetadata getMetadata(String path) {
                return FileMetadata.builder().path(path).size(4).createdAt(t).modifiedAt(t).etag("v1").build();
            }
        };
        final VirtualFileSystem guarded = new PathRuleVirtualFileSystem(tagged, List.of(PathRule.deny(".aimon")));
        assertThat(guarded.getMetadata("src/a.txt").getEtag()).hasValue("v1");
        assertThat(guarded.getWorkingDirectory()).isEqualTo(base);
        guarded.write("src/b.txt", new ByteArrayInputStream(new byte[]{1}), 1);
        assertThat(raw.exists("src/b.txt")).isTrue();
    }

    @Test
    @DisplayName("PathRule normalises its prefix and matches whole segments")
    void pathRule() {
        assertThat(PathRule.deny("./.aimon/").getPrefix()).isEqualTo(".aimon");
        assertThat(PathRule.deny(".aimon").covers(".aimon/x")).isTrue();
        assertThat(PathRule.deny(".aimon").covers(".aimon2/x")).isFalse();
        assertThatThrownBy(() -> PathRule.deny("..")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EE-34: usage of the whole filesystem leaves the DENYed subtree out, with or without a path")
    void usageLeavesDeniedOut() {
        // visible: src/a.txt ("work") and .aimon-staged/n/k/x.sh ("staged"); dirs src, .aimon-staged, n, k
        final FileSystemUsage visible = FileSystemUsage.builder().totalSize(10).fileCount(2).directoryCount(4).build();
        assertThat(raw.getUsageSummary().getFileCount()).isEqualTo(3);
        assertThat(fs.getUsageSummary()).isEqualTo(visible);
        assertThat(fs.getUsageSummary(".")).isEqualTo(visible);
        assertThat(fs.getUsageSummary(base)).isEqualTo(visible);
        assertThat(fs.getUsageSummary("src")).isEqualTo(raw.getUsageSummary("src"));
        assertThatThrownBy(() -> fs.getUsageSummary(".aimon")).isInstanceOf(FileAccessDeniedException.class);
    }

    @Test
    @DisplayName("EE-34: a nested DENY prefix is left out of the usage of every directory above it")
    void usageLeavesNestedDeniedOut() {
        raw.write("a/.secrets/key", "top secret");
        raw.write("a/b.txt", "bb");
        final VirtualFileSystem nested = new PathRuleVirtualFileSystem(raw, List.of(PathRule.deny("a/.secrets")));
        assertThat(nested.getUsageSummary("a"))
                .isEqualTo(FileSystemUsage.builder().totalSize(2).fileCount(1).directoryCount(0).build());
        assertThat(nested.getUsageSummary().getFileCount()).isEqualTo(raw.getUsageSummary().getFileCount() - 1);
        assertThat(nested.getUsageSummary().getDirectoryCount())
                .isEqualTo(raw.getUsageSummary().getDirectoryCount() - 1);
    }

    @Test
    @DisplayName("EE-34: a symbolic link on the way down to a DENY prefix is counted, not thrown on")
    void usageCountsASymlinkInsteadOfThrowing() throws IOException {
        // The delegate refuses any path through a link, so asking it about the entry would throw; the raw walk counts
        // the link as a file without following it, and so does the guarded total.
        Files.createSymbolicLink(tempDir.resolve("link.txt"), tempDir.resolve("src/a.txt"));

        assertThat(fs.getUsageSummary().getFileCount()).isEqualTo(raw.getUsageSummary().getFileCount() - 1);
        assertThat(fs.getUsageSummary(".").getFileCount()).isEqualTo(fs.getUsageSummary().getFileCount());
    }

    @Test
    @DisplayName("EE-39: search keeps asking until it has maxResults visible hits when DENYed hits come first")
    void searchFillsMaxResults() {
        for (int i = 0; i < 5; i++) {
            raw.write(".aimon/hidden" + i + ".md", "h");
        }
        raw.write("src/visible1.md", "v");
        raw.write("src/visible2.md", "v");
        // a backend whose walk happens to reach the control store first
        final VirtualFileSystem hiddenFirst = new DelegatingFileSystem(raw) {
            @Override
            public List<String> search(String directory, String pattern, int maxResults) {
                return raw.search(directory, pattern, 1000).stream().sorted(Comparator
                        .comparing((String p) -> !p.startsWith(".aimon/")).thenComparing(Comparator.naturalOrder()))
                        .limit(maxResults).toList();
            }
        };
        final VirtualFileSystem guarded = new PathRuleVirtualFileSystem(hiddenFirst, List.of(PathRule.deny(".aimon")));
        assertThat(guarded.search(".", "*.md", 2)).containsExactly("src/visible1.md", "src/visible2.md");
        assertThat(guarded.search(".", "*.md", 1)).containsExactly("src/visible1.md");
        assertThat(guarded.search(".", "*.md", 100)).containsExactly("src/visible1.md", "src/visible2.md");
        assertThat(guarded.search("src", "*.md", 1)).containsExactly("src/visible1.md");
    }
}
