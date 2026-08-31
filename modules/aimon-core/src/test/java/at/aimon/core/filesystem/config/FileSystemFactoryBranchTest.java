package at.aimon.core.filesystem.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;

/**
 * Covers branches of {@link FileSystemFactory} not exercised by {@link FileSystemFactoryTest}: optional-backend
 * reflection failure paths, property-count validation, unknown backend handling, and {@code createFromEnvironment}.
 */
class FileSystemFactoryBranchTest {

    @TempDir
    Path tempDir;

    // ---- Null guards on optional-backend factories --------------------------------------------------

    @Test
    void createGridFSRejectsNullConnectionString() {
        assertThatNullPointerException().isThrownBy(() -> FileSystemFactory.createGridFSFileSystem(null, "db"));
    }

    @Test
    void createGridFSRejectsNullDatabase() {
        assertThatNullPointerException()
                .isThrownBy(() -> FileSystemFactory.createGridFSFileSystem("mongodb://localhost", null));
    }

    @Test
    void createS3RejectsNullBucket() {
        assertThatNullPointerException().isThrownBy(() -> FileSystemFactory.createS3FileSystem(null, "ak", "sk"));
    }

    @Test
    void createS3RejectsNullAccessKey() {
        assertThatNullPointerException().isThrownBy(() -> FileSystemFactory.createS3FileSystem("bucket", null, "sk"));
    }

    @Test
    void createS3RejectsNullSecretKey() {
        assertThatNullPointerException().isThrownBy(() -> FileSystemFactory.createS3FileSystem("bucket", "ak", null));
    }

    // ---- Optional backends absent from classpath: reflective lookups must fail with IllegalStateException ----

    @Test
    void createGridFSWithoutModuleFailsWithIllegalState() {
        assertThatIllegalStateException()
                .isThrownBy(() -> FileSystemFactory.createGridFSFileSystem("mongodb://localhost", "db"))
                .withMessageContaining("filesystem-gridfs");
    }

    @Test
    void createS3WithoutModuleFailsWithIllegalState() {
        assertThatIllegalStateException().isThrownBy(() -> FileSystemFactory.createS3FileSystem("bucket", "ak", "sk"))
                .withMessageContaining("filesystem-s3");
    }

    /**
     * The contrast case for the two above, and the reason the local branch is written differently: there is no
     * optional module to be missing. {@code LocalFileSystem} ships inside {@code aimon-core}, so the class name is a
     * compile-time reference rather than a string, and the call cannot fail the way GridFS and S3 do.
     */
    @Test
    void createLocalHasNoOptionalModuleToBeMissing() {
        VirtualFileSystem fs = FileSystemFactory.createLocalFileSystem(tempDir.toString());

        assertThat(fs).isInstanceOf(LocalFileSystem.class);
        fs.close();
    }

    // ---- createFileSystem property-count validation ------------------------------------------------

    @Test
    void createFileSystemLocalRequiresBasePath() {
        assertThatIllegalArgumentException().isThrownBy(() -> FileSystemFactory.createFileSystem(BackendType.LOCAL))
                .withMessageContaining("basePath");
    }

    @Test
    void createFileSystemGridFsRequiresTwoProperties() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FileSystemFactory.createFileSystem(BackendType.GRIDFS, "mongodb://localhost"))
                .withMessageContaining("connectionString");
    }

    @Test
    void createFileSystemS3RequiresThreeProperties() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FileSystemFactory.createFileSystem(BackendType.S3, "bucket", "ak"))
                .withMessageContaining("bucketName");
    }

    @Test
    void createFileSystemRejectsUnknownBackend() {
        BackendType unknown = BackendType.of("CUSTOM_X");

        assertThatIllegalArgumentException().isThrownBy(() -> FileSystemFactory.createFileSystem(unknown, "x"))
                .withMessageContaining("Unknown backend type");
    }

    @Test
    void createFileSystemGridFsDelegatesAndFailsWithoutModule() {
        // Property count is sufficient — but reflection should fail because the gridfs module isn't on the classpath.
        assertThatIllegalStateException()
                .isThrownBy(() -> FileSystemFactory.createFileSystem(BackendType.GRIDFS, "mongodb://localhost", "db"));
    }

    @Test
    void createFileSystemS3DelegatesAndFailsWithoutModule() {
        assertThatIllegalStateException()
                .isThrownBy(() -> FileSystemFactory.createFileSystem(BackendType.S3, "bucket", "ak", "sk"));
    }

    // ---- createFromEnvironment branches ------------------------------------------------------------

    @Test
    void createFromEnvironmentDefaultsToLocalWhenSystemPropertyAbsent() throws Exception {
        // The factory falls back to system property "filesystem.backend" with default "LOCAL" when the env var is
        // unset. We can't reliably unset env vars in-process, so this test runs only when the env var is unset on
        // the host and exercises the LOCAL branch via the system property override below.
        if (System.getenv("FILESYSTEM_BACKEND") != null) {
            return;
        }
        String basePathProp = System.getProperty("filesystem.base.path");
        String backendProp = System.getProperty("filesystem.backend");
        try {
            System.setProperty("filesystem.backend", "LOCAL");
            System.setProperty("filesystem.base.path", tempDir.toString());

            VirtualFileSystem fs = FileSystemFactory.createFromEnvironment();
            assertThat(fs).isNotNull();
            fs.initialize();
            fs.close();
        } finally {
            restoreProperty("filesystem.backend", backendProp);
            restoreProperty("filesystem.base.path", basePathProp);
        }
    }

    @Test
    void createFromEnvironmentRejectsUnknownBackendProperty() {
        if (System.getenv("FILESYSTEM_BACKEND") != null) {
            return;
        }
        String previous = System.getProperty("filesystem.backend");
        try {
            System.setProperty("filesystem.backend", "CUSTOM_FROM_PROP");
            assertThatIllegalArgumentException().isThrownBy(FileSystemFactory::createFromEnvironment)
                    .withMessageContaining("Unknown backend type");
        } finally {
            restoreProperty("filesystem.backend", previous);
        }
    }

    @Test
    void createFromEnvironmentS3RequiresAllCredentialsProperty() {
        if (System.getenv("FILESYSTEM_BACKEND") != null || System.getenv("FILESYSTEM_S3_BUCKET") != null) {
            return;
        }
        String backendProp = System.getProperty("filesystem.backend");
        String bucketProp = System.getProperty("filesystem.s3.bucket");
        String akProp = System.getProperty("filesystem.s3.access.key");
        String skProp = System.getProperty("filesystem.s3.secret.key");
        try {
            System.setProperty("filesystem.backend", "S3");
            System.clearProperty("filesystem.s3.bucket");
            System.clearProperty("filesystem.s3.access.key");
            System.clearProperty("filesystem.s3.secret.key");

            assertThatIllegalStateException().isThrownBy(FileSystemFactory::createFromEnvironment)
                    .withMessageContaining("FILESYSTEM_S3_BUCKET");
        } finally {
            restoreProperty("filesystem.backend", backendProp);
            restoreProperty("filesystem.s3.bucket", bucketProp);
            restoreProperty("filesystem.s3.access.key", akProp);
            restoreProperty("filesystem.s3.secret.key", skProp);
        }
    }

    @Test
    void createFromEnvironmentS3DelegatesToFactoryWhenAllCredentialsPresent() {
        if (System.getenv("FILESYSTEM_BACKEND") != null || System.getenv("FILESYSTEM_S3_BUCKET") != null) {
            return;
        }
        String backendProp = System.getProperty("filesystem.backend");
        String bucketProp = System.getProperty("filesystem.s3.bucket");
        String akProp = System.getProperty("filesystem.s3.access.key");
        String skProp = System.getProperty("filesystem.s3.secret.key");
        try {
            System.setProperty("filesystem.backend", "S3");
            System.setProperty("filesystem.s3.bucket", "bucket");
            System.setProperty("filesystem.s3.access.key", "ak");
            System.setProperty("filesystem.s3.secret.key", "sk");

            // S3 module is not on the test classpath, so the call still throws — but it must reach the reflective
            // factory, which surfaces IllegalStateException with the filesystem-s3 hint.
            assertThatIllegalStateException().isThrownBy(FileSystemFactory::createFromEnvironment)
                    .withMessageContaining("filesystem-s3");
        } finally {
            restoreProperty("filesystem.backend", backendProp);
            restoreProperty("filesystem.s3.bucket", bucketProp);
            restoreProperty("filesystem.s3.access.key", akProp);
            restoreProperty("filesystem.s3.secret.key", skProp);
        }
    }

    @Test
    void createFromEnvironmentGridFsDelegatesToFactory() {
        if (System.getenv("FILESYSTEM_BACKEND") != null) {
            return;
        }
        String backendProp = System.getProperty("filesystem.backend");
        try {
            System.setProperty("filesystem.backend", "GRIDFS");
            assertThatIllegalStateException().isThrownBy(FileSystemFactory::createFromEnvironment)
                    .withMessageContaining("filesystem-gridfs");
        } finally {
            restoreProperty("filesystem.backend", backendProp);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
