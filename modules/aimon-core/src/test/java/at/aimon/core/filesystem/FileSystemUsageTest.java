package at.aimon.core.filesystem;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FileSystemUsageTest {

    @Test
    void testBuilderAndGetters() {
        FileSystemUsage usage = FileSystemUsage.builder().totalSize(1024).fileCount(10).directoryCount(3).build();

        assertThat(usage.getTotalSize()).isEqualTo(1024);
        assertThat(usage.getFileCount()).isEqualTo(10);
        assertThat(usage.getDirectoryCount()).isEqualTo(3);
    }

    @Test
    void testBuilderDefaults() {
        FileSystemUsage usage = FileSystemUsage.builder().build();

        assertThat(usage.getTotalSize()).isZero();
        assertThat(usage.getFileCount()).isZero();
        assertThat(usage.getDirectoryCount()).isZero();
    }

    @Test
    void testEqualsAndHashCode() {
        FileSystemUsage a = FileSystemUsage.builder().totalSize(100).fileCount(5).directoryCount(2).build();
        FileSystemUsage b = FileSystemUsage.builder().totalSize(100).fileCount(5).directoryCount(2).build();
        FileSystemUsage c = FileSystemUsage.builder().totalSize(200).fileCount(5).directoryCount(2).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void testEqualsWithNull() {
        FileSystemUsage usage = FileSystemUsage.builder().build();

        assertThat(usage).isNotEqualTo(null);
    }

    @Test
    void testEqualsWithDifferentType() {
        FileSystemUsage usage = FileSystemUsage.builder().build();

        assertThat(usage).isNotEqualTo("not a FileSystemUsage");
    }

    @Test
    void testEqualsSameInstance() {
        FileSystemUsage usage = FileSystemUsage.builder().totalSize(50).build();

        assertThat(usage).isEqualTo(usage);
    }

    @Test
    void testToString() {
        FileSystemUsage usage = FileSystemUsage.builder().totalSize(1024).fileCount(10).directoryCount(3).build();

        String str = usage.toString();
        assertThat(str).contains("totalSize=1024");
        assertThat(str).contains("fileCount=10");
        assertThat(str).contains("directoryCount=3");
    }

    @Test
    void testRejectsNegativeTotalSize() {
        assertThatThrownBy(() -> FileSystemUsage.builder().totalSize(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalSize");
    }

    @Test
    void testRejectsNegativeFileCount() {
        assertThatThrownBy(() -> FileSystemUsage.builder().fileCount(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fileCount");
    }

    @Test
    void testRejectsNegativeDirectoryCount() {
        assertThatThrownBy(() -> FileSystemUsage.builder().directoryCount(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("directoryCount");
    }
}
