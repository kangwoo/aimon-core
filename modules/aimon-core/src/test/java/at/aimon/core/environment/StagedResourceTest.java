package at.aimon.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

@DisplayName("StagedResource.scan")
class StagedResourceTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem control;

    @BeforeEach
    void setUp() {
        control = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        control.initialize();
        control.write("skills/demo/SKILL.md", "# demo");
        control.write("skills/demo/scripts/run.sh", "echo hi");
        control.write("skills/demo/templates/t.md", "tpl");
    }

    @AfterEach
    void tearDown() {
        control.close();
    }

    @Test
    @DisplayName("records the relative file set, sorted, and the total size")
    void fileSet() {
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");
        assertThat(resource.getFiles()).containsExactly("SKILL.md", "scripts/run.sh", "templates/t.md");
        assertThat(resource.getTotalBytes()).isEqualTo("# demo".length() + "echo hi".length() + "tpl".length());
        assertThat(resource.getName()).isEqualTo("demo");
        assertThat(resource.getSourceDir()).isEqualTo("skills/demo");
        assertThat(resource.getSourceFileSystem()).isSameAs(control);
        assertThat(resource.sourcePath("scripts/run.sh")).isEqualTo("skills/demo/scripts/run.sh");
        assertThat(resource.getContentKey()).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("the key is stable for identical content, also across source directories")
    void stableKey() {
        control.write("copy/demo/SKILL.md", "# demo");
        control.write("copy/demo/scripts/run.sh", "echo hi");
        control.write("copy/demo/templates/t.md", "tpl");
        assertThat(StagedResource.scan(control, "skills/demo", "demo").getContentKey())
                .isEqualTo(StagedResource.scan(control, "skills/demo/", "demo").getContentKey())
                .isEqualTo(StagedResource.scan(control, "copy/demo", "demo").getContentKey());
    }

    @Test
    @DisplayName("the key changes when a file changes, is added or is renamed")
    void keyChangesOnEdit() {
        final String before = StagedResource.scan(control, "skills/demo", "demo").getContentKey();
        control.write("skills/demo/scripts/run.sh", "echo bye");
        final String edited = StagedResource.scan(control, "skills/demo", "demo").getContentKey();
        control.write("skills/demo/extra.txt", "");
        final String added = StagedResource.scan(control, "skills/demo", "demo").getContentKey();
        assertThat(edited).isNotEqualTo(before);
        assertThat(added).isNotEqualTo(edited);
    }

    @Test
    @DisplayName("the key matches a hand-computed hash over the sorted entries")
    void handComputedKey() {
        final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
        hasher.add("SKILL.md", "# demo".getBytes(StandardCharsets.UTF_8));
        hasher.add("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));
        hasher.add("templates/t.md", "tpl".getBytes(StandardCharsets.UTF_8));
        assertThat(StagedResource.scan(control, "skills/demo", "demo").getContentKey()).isEqualTo(hasher.build());
    }

    @Test
    @DisplayName(".stageignore excludes paths from the file set, the size and the hash")
    void stageIgnore() {
        final String before = StagedResource.scan(control, "skills/demo", "demo").getContentKey();
        control.write("skills/demo/assets/big.bin", "0123456789");
        control.write("skills/demo/.stageignore", "assets/\n");
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");
        assertThat(resource.getFiles()).doesNotContain("assets/big.bin").contains(".stageignore");
        final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
        hasher.add(".stageignore", "assets/\n".getBytes(StandardCharsets.UTF_8));
        hasher.add("SKILL.md", "# demo".getBytes(StandardCharsets.UTF_8));
        hasher.add("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));
        hasher.add("templates/t.md", "tpl".getBytes(StandardCharsets.UTF_8));
        assertThat(resource.getContentKey()).isEqualTo(hasher.build()).isNotEqualTo(before);
        assertThat(resource.getTotalBytes()).isEqualTo("assets/\n".length() + 6 + 7 + 3);
    }

    @Test
    @DisplayName("a listed entry that cannot be read fails the scan, naming the file")
    void unreadableFailsScan() {
        final VirtualFileSystem flaky = new DelegatingFileSystem(control) {
            @Override
            public InputStream read(String path) {
                if (path.endsWith("templates/t.md")) {
                    throw new IllegalStateException("unreadable");
                }
                return super.read(path);
            }
        };
        assertThatThrownBy(() -> StagedResource.scan(flaky, "skills/demo", "demo"))
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("templates/t.md")
                .hasMessageContaining("'demo'").hasMessageContaining(StagedResource.STAGE_IGNORE_FILE);
    }

    @Test
    @DisplayName("an unreadable entry that .stageignore excludes is never read")
    void unreadableButIgnored() {
        control.write("skills/demo/.stageignore", "templates/\n");
        final VirtualFileSystem flaky = new DelegatingFileSystem(control) {
            @Override
            public InputStream read(String path) {
                if (path.endsWith("templates/t.md")) {
                    throw new IllegalStateException("unreadable");
                }
                return super.read(path);
            }
        };
        assertThat(StagedResource.scan(flaky, "skills/demo", "demo").getFiles()).doesNotContain("templates/t.md");
    }

    @Test
    @DisplayName("a missing directory scans to an empty resource")
    void missingDirectory() {
        final StagedResource resource = StagedResource.scan(control, "skills/none", "none");
        assertThat(resource.getFiles()).isEmpty();
        assertThat(resource.getTotalBytes()).isZero();
    }

    @Test
    @DisplayName("a scanned resource says so, and one assembled with the same values through the builder does not")
    void scannedOrigin() {
        final StagedResource scanned = StagedResource.scan(control, "skills/demo", "demo");
        final StagedResource assembled = StagedResource.builder().sourceFileSystem(scanned.getSourceFileSystem())
                .sourceDir(scanned.getSourceDir()).name(scanned.getName()).contentKey(scanned.getContentKey())
                .totalBytes(scanned.getTotalBytes()).files(scanned.getFiles()).build();

        assertThat(scanned.isScanned()).isTrue();
        assertThat(assembled.isScanned()).isFalse();
        assertThat(scanned.toString()).contains("scanned=true");
        assertThat(assembled.toString()).contains("scanned=false");
        assertThat(StagedResource.scan(control, "skills/none", "none").isScanned()).isTrue();
    }

    @Test
    @DisplayName("the builder has no way to claim a scan: its only methods are the six values and build()")
    void builderCannotClaimAScan() {
        assertThat(java.util.Arrays.stream(StagedResource.Builder.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic()).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder(
                        "sourceFileSystem", "sourceDir", "contentKey", "name", "totalBytes", "files", "build");
    }

    @Test
    @DisplayName("sizeOf is the size scan would record, .stageignore applied, without reading a file's content")
    void sizeOfMatchesScanWithoutReading() {
        control.write("skills/demo/.stageignore", "templates/\n");
        final java.util.List<String> reads = new java.util.ArrayList<>();
        final VirtualFileSystem counting = new DelegatingFileSystem(control) {
            @Override
            public InputStream read(String path) {
                reads.add(path);
                return super.read(path);
            }
        };

        final long size = StagedResource.sizeOf(counting, "skills/demo/");

        assertThat(reads).containsExactly("skills/demo/.stageignore");
        assertThat(size).isEqualTo(StagedResource.scan(control, "skills/demo", "demo").getTotalBytes());
        assertThat(StagedResource.sizeOf(control, "skills/none")).isZero();
    }
}
