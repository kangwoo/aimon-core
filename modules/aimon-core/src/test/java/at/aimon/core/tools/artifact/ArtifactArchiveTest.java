package at.aimon.core.tools.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.filesystem.PathValidator;

@DisplayName("ArtifactArchive.directoryName — archive keys become one path-safe segment")
class ArtifactArchiveTest {

    @TempDir
    Path base;

    @Test
    @DisplayName("the keys the executors mint pass the local store's path validation")
    void mintedKeysAreValidLocalPaths() {
        final PathValidator validator = new PathValidator(base.toString());
        assertThat(validator.isValid("artifacts/" + new ArtifactCollector().getArchiveKey() + "/report.csv"))
                .as("the raw key is what used to fail").isFalse();
        for (String key : new String[]{new ArtifactCollector().getArchiveKey(),
                "subagent:worker:3f1c2b1e-0000-4000-8000-000000000000"}) {
            final String name = ArtifactArchive.directoryName(key);
            assertThat(name).doesNotContain(":").doesNotContain("/");
            assertThat(validator.isValid("artifacts/" + name + "/report.csv")).as(key).isTrue();
        }
    }

    @Test
    @DisplayName("safe characters are kept, everything else becomes '_'")
    void replacesUnsafeCharacters() {
        assertThat(ArtifactArchive.directoryName("exec-1")).isEqualTo("exec-1");
        assertThat(ArtifactArchive.directoryName("archive:abc")).isEqualTo("archive_abc");
        assertThat(ArtifactArchive.directoryName("a/b\\c*d")).isEqualTo("a_b_c_d");
    }

    @Test
    @DisplayName("never empty, '.' or '..'")
    void neverASpecialSegment() {
        assertThat(ArtifactArchive.directoryName("")).isEqualTo("_");
        assertThat(ArtifactArchive.directoryName(".")).isEqualTo("_.");
        assertThat(ArtifactArchive.directoryName("..")).isEqualTo("_..");
    }
}
