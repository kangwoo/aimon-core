package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

@DisplayName("DirectorySummaryContextProvider Tests")
class DirectorySummaryContextProviderTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem() {
        LocalFileSystem fs = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fs.initialize();
        return fs;
    }

    private ContextAssemblyRequest requestWith(LocalFileSystem fs) {
        return ContextAssemblyRequest.builder().fileSystem(fs).build();
    }

    @Nested
    @DisplayName("Listing")
    class Listing {

        @Test
        @DisplayName("summarises sorted top-level entries and flags directories")
        void summarisesEntries() {
            LocalFileSystem fs = fileSystem();
            fs.write("beta.txt", "b");
            fs.write("alpha.txt", "a");
            fs.createDirectory("sub");

            List<ContextBlock> blocks = new DirectorySummaryContextProvider().provide(requestWith(fs));

            assertThat(blocks).hasSize(1);
            ContextBlock block = blocks.get(0);
            assertThat(block.getKind()).isEqualTo(ContextBlockKind.SYSTEM);
            assertThat(block.getKey()).isEqualTo(DirectorySummaryContextProvider.BLOCK_KEY);
            assertThat(block.getBody()).contains("(3 entries)").contains("- alpha.txt").contains("- beta.txt")
                    .contains("- sub/");
            // sorted: alpha before beta
            assertThat(block.getBody().indexOf("alpha.txt")).isLessThan(block.getBody().indexOf("beta.txt"));
        }

        @Test
        @DisplayName("caps the number of listed entries and notes the truncation")
        void capsEntries() {
            LocalFileSystem fs = fileSystem();
            for (int i = 0; i < 5; i++) {
                fs.write("file-" + i + ".txt", "x");
            }

            List<ContextBlock> blocks = new DirectorySummaryContextProvider(2).provide(requestWith(fs));

            assertThat(blocks).hasSize(1);
            String body = blocks.get(0).getBody();
            assertThat(body).contains("(5 entries, showing first 2)");
            // only the first two sorted entries are listed
            assertThat(body).contains("- file-0.txt").contains("- file-1.txt").doesNotContain("- file-4.txt");
        }

        @Test
        @DisplayName("emits nothing for an empty directory")
        void emptyDirectory() {
            assertThat(new DirectorySummaryContextProvider().provide(requestWith(fileSystem()))).isEmpty();
        }

        @Test
        @DisplayName("emits nothing when no filesystem is bound")
        void emptyWhenNoFileSystem() {
            assertThat(new DirectorySummaryContextProvider().provide(ContextAssemblyRequest.builder().build()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("maxEntries < 1 rejected")
        void rejectsZeroMax() {
            assertThatThrownBy(() -> new DirectorySummaryContextProvider(0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxEntries");
        }
    }
}
