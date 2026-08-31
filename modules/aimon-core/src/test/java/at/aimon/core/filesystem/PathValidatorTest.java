package at.aimon.core.filesystem;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.exception.InvalidPathException;

class PathValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void testValidateSimplePath() {
        PathValidator validator = new PathValidator(tempDir.toString());
        Path normalized = validator.validateAndNormalize("file.txt");

        assertThat(normalized.isAbsolute()).isTrue();
        assertThat(normalized.toString()).startsWith(tempDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void testValidateNestedPath() {
        PathValidator validator = new PathValidator(tempDir.toString());
        Path normalized = validator.validateAndNormalize("dir1/dir2/file.txt");

        assertThat(normalized.isAbsolute()).isTrue();
        assertThat(normalized.toString()).startsWith(tempDir.toAbsolutePath().normalize().toString());
        assertThat(normalized.toString()).contains("dir1");
        assertThat(normalized.toString()).contains("dir2");
    }

    @Test
    void testNormalizesDotsInPath() {
        PathValidator validator = new PathValidator(tempDir.toString());
        Path normalized = validator.validateAndNormalize("dir1/./file.txt");

        assertThat(normalized.toString()).doesNotContain("/./");
    }

    @Test
    void testRejectsNullPath() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThatThrownBy(() -> validator.validateAndNormalize(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Path cannot be null");
    }

    @Test
    void testRejectsEmptyPath() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThatThrownBy(() -> validator.validateAndNormalize("")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Path cannot be empty");
    }

    @Test
    void testRejectsWhitespacePath() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThatThrownBy(() -> validator.validateAndNormalize("   ")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Path cannot be empty");
    }

    @Test
    void testRejectsNullByte() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThatThrownBy(() -> validator.validateAndNormalize("file\0name.txt"))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("invalid characters");
    }

    @Test
    void testRejectsControlCharacters() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThatThrownBy(() -> validator.validateAndNormalize("file\u0001name.txt"))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("invalid characters");
    }

    @Test
    void testRejectsInvalidWindowsCharacters() {
        PathValidator validator = new PathValidator(tempDir.toString());

        // Windows invalid characters: < > : " | ? *
        assertThatThrownBy(() -> validator.validateAndNormalize("file<name.txt"))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("file>name.txt"))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("file:name.txt"))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("file\"name.txt"))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("file|name.txt"))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("file?name.txt"))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("file*name.txt"))
                .isInstanceOf(InvalidPathException.class);
    }

    // Security Tests (SC-008: Directory Traversal Prevention)

    @Test
    void testPreventsDirectoryTraversalWithDoubleDots() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThatThrownBy(() -> validator.validateAndNormalize("../etc/passwd"))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("Path traversal detected");
    }

    @Test
    void testPreventsDirectoryTraversalWithMultipleLevels() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThatThrownBy(() -> validator.validateAndNormalize("dir1/../../etc/passwd"))
                .isInstanceOf(InvalidPathException.class).hasMessageContaining("Path traversal detected");
    }

    @Test
    void testPreventsAbsolutePathEscape() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThatThrownBy(() -> validator.validateAndNormalize("/etc/passwd")).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("Path traversal detected");
    }

    @Test
    void testAllowsDotsWithinBasePath() {
        PathValidator validator = new PathValidator(tempDir.toString());

        // This is safe: dir/../file.txt = file.txt within base path
        Path normalized = validator.validateAndNormalize("dir/../file.txt");
        assertThat(normalized.toString()).startsWith(tempDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void testIsValidReturnsTrueForValidPath() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThat(validator.isValid("file.txt")).isTrue();
        assertThat(validator.isValid("dir/file.txt")).isTrue();
    }

    @Test
    void testIsValidReturnsFalseForInvalidPath() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThat(validator.isValid("../etc/passwd")).isFalse();
        assertThat(validator.isValid("file\0name.txt")).isFalse();
        assertThat(validator.isValid("")).isFalse();
        assertThat(validator.isValid(null)).isFalse();
    }

    @Test
    void testGetBasePath() {
        PathValidator validator = new PathValidator(tempDir.toString());

        assertThat(validator.getBasePath()).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    // Security Tests (SC-009: Symbolic Link Blocking)

    @Test
    void testRejectsSymbolicLinkDirectory() throws IOException {
        Path outsideDir = Files.createTempDirectory("outside");
        Path symlink = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(symlink, outsideDir);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links not supported on this platform");
        }

        PathValidator validator = new PathValidator(tempDir.toString());

        try {
            assertThatThrownBy(() -> validator.validateAndNormalize("link/secret.txt"))
                    .isInstanceOf(InvalidPathException.class).hasMessageContaining("Symbolic link detected");
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(outsideDir);
        }
    }

    @Test
    void testRejectsSymbolicLinkFile() throws IOException {
        Path outsideFile = Files.createTempFile("outside", ".txt");
        Path symlink = tempDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(symlink, outsideFile);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links not supported on this platform");
        }

        PathValidator validator = new PathValidator(tempDir.toString());

        try {
            assertThatThrownBy(() -> validator.validateAndNormalize("link.txt"))
                    .isInstanceOf(InvalidPathException.class).hasMessageContaining("Symbolic link detected");
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void testAllowsRegularFileNotSymlink() throws IOException {
        Files.writeString(tempDir.resolve("regular.txt"), "content");

        PathValidator validator = new PathValidator(tempDir.toString());

        Path result = validator.validateAndNormalize("regular.txt");
        assertThat(result).isEqualTo(tempDir.toAbsolutePath().normalize().resolve("regular.txt"));
    }

    @Test
    void testAllowsNonExistentPathWithNoSymlinks() {
        PathValidator validator = new PathValidator(tempDir.toString());

        // Non-existent path should pass — no symlinks to detect
        Path result = validator.validateAndNormalize("newdir/newfile.txt");
        assertThat(result.toString()).startsWith(tempDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void testRejectsSymbolicLinkEvenWhenTargetIsInsideBasePath() throws IOException {
        Path realDir = Files.createDirectory(tempDir.resolve("real"));
        Path symlink = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(symlink, realDir);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links not supported on this platform");
        }

        PathValidator validator = new PathValidator(tempDir.toString());

        try {
            // All symlinks are rejected regardless of target location
            assertThatThrownBy(() -> validator.validateAndNormalize("link/file.txt"))
                    .isInstanceOf(InvalidPathException.class).hasMessageContaining("Symbolic link detected");
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(realDir);
        }
    }

    @Test
    void testRejectsNestedSymbolicLink() throws IOException {
        Path outsideDir = Files.createTempDirectory("outside");
        Path subDir = Files.createDirectory(tempDir.resolve("subdir"));
        Path symlink = subDir.resolve("link");
        try {
            Files.createSymbolicLink(symlink, outsideDir);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links not supported on this platform");
        }

        PathValidator validator = new PathValidator(tempDir.toString());

        try {
            assertThatThrownBy(() -> validator.validateAndNormalize("subdir/link/secret.txt"))
                    .isInstanceOf(InvalidPathException.class).hasMessageContaining("Symbolic link detected");
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(subDir);
            Files.deleteIfExists(outsideDir);
        }
    }

    @Test
    void testIsValidReturnsFalseForSymbolicLink() throws IOException {
        Path outsideDir = Files.createTempDirectory("outside");
        Path symlink = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(symlink, outsideDir);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links not supported on this platform");
        }

        PathValidator validator = new PathValidator(tempDir.toString());

        try {
            assertThat(validator.isValid("link/file.txt")).isFalse();
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(outsideDir);
        }
    }

    @Test
    void testConstructorRejectsNullBasePath() {
        assertThatThrownBy(() -> new PathValidator(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Base path cannot be null");
    }

    @Test
    void testPathValidatorNormalizesBasePath() {
        // Base path with dots should be normalized
        PathValidator validator = new PathValidator(tempDir.toString() + "/./subdir/../");

        assertThat(validator.getBasePath()).isAbsolute();
        assertThat(validator.getBasePath().toString()).doesNotContain("./");
        assertThat(validator.getBasePath().toString()).doesNotContain("../");
    }
}
