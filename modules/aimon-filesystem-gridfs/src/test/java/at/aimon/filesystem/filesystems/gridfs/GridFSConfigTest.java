package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.filesystem.core.gridfs.GridFSConfig;

class GridFSConfigTest {

    @Nested
    class BuilderTests {

        @Test
        void testBuilderWithRequiredFieldsOnly() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            assertThat(config.getConnectionString()).isEqualTo("mongodb://localhost:27017");
            assertThat(config.getDatabaseName()).isEqualTo("mydb");
            assertThat(config.getBucketName()).isEqualTo(GridFSConfig.DEFAULT_BUCKET_NAME);
            assertThat(config.getChunkSizeBytes()).isEqualTo(GridFSConfig.DEFAULT_CHUNK_SIZE);
        }

        @Test
        void testBuilderWithAllFields() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").bucketName("custom_bucket").chunkSizeBytes(512 * 1024).build();

            assertThat(config.getConnectionString()).isEqualTo("mongodb://localhost:27017");
            assertThat(config.getDatabaseName()).isEqualTo("mydb");
            assertThat(config.getBucketName()).isEqualTo("custom_bucket");
            assertThat(config.getChunkSizeBytes()).isEqualTo(512 * 1024);
        }

        @Test
        void testBuilderUsesDefaults() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            assertThat(config.getBucketName()).isEqualTo("fs");
            assertThat(config.getChunkSizeBytes()).isEqualTo(1024 * 1024);
        }

        @Test
        void testBuilderRejectsNullConnectionString() {
            assertThatThrownBy(() -> GridFSConfig.builder().databaseName("mydb").build())
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Connection string cannot be null");
        }

        @Test
        void testBuilderRejectsNullDatabaseName() {
            assertThatThrownBy(() -> GridFSConfig.builder().connectionString("mongodb://localhost:27017").build())
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Database name cannot be null");
        }

        @Test
        void testBuilderRejectsInvalidChunkSize() {
            assertThatThrownBy(() -> GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").chunkSizeBytes(0).build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Chunk size must be positive");
        }

        @Test
        void testBuilderRejectsNegativeChunkSize() {
            assertThatThrownBy(() -> GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").chunkSizeBytes(-1).build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Chunk size must be positive");
        }
    }

    @Nested
    class EqualsAndHashCodeTests {

        @Test
        void testReflexive() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            assertThat(config).isEqualTo(config);
        }

        @Test
        void testSymmetric() {
            GridFSConfig config1 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();
            GridFSConfig config2 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            assertThat(config1).isEqualTo(config2);
            assertThat(config2).isEqualTo(config1);
            assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
        }

        @Test
        void testDifferentConnectionString() {
            GridFSConfig config1 = GridFSConfig.builder().connectionString("mongodb://host1:27017").databaseName("mydb")
                    .build();
            GridFSConfig config2 = GridFSConfig.builder().connectionString("mongodb://host2:27017").databaseName("mydb")
                    .build();

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        void testDifferentDatabaseName() {
            GridFSConfig config1 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("db1").build();
            GridFSConfig config2 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("db2").build();

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        void testDifferentBucketName() {
            GridFSConfig config1 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").bucketName("bucket1").build();
            GridFSConfig config2 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").bucketName("bucket2").build();

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        void testDifferentChunkSize() {
            GridFSConfig config1 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").chunkSizeBytes(1024).build();
            GridFSConfig config2 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").chunkSizeBytes(2048).build();

            assertThat(config1).isNotEqualTo(config2);
        }

        @Test
        void testNotEqualToNull() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            assertThat(config).isNotEqualTo(null);
        }

        @Test
        void testConsistency() {
            GridFSConfig config1 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();
            GridFSConfig config2 = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            // Multiple calls should return same result
            assertThat(config1.equals(config2)).isTrue();
            assertThat(config1.equals(config2)).isTrue();
            assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
        }
    }

    @Nested
    class ToStringTests {

        @Test
        void testContainsDatabaseName() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            assertThat(config.toString()).contains("mydb");
        }

        @Test
        void testMasksPassword() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://user:secret@localhost:27017")
                    .databaseName("mydb").build();

            String str = config.toString();
            assertThat(str).doesNotContain("secret");
            assertThat(str).contains("***@");
        }

        @Test
        void testNoMaskWithoutCredentials() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            String str = config.toString();
            assertThat(str).contains("mongodb://localhost:27017");
            assertThat(str).doesNotContain("***");
        }
    }

    @Nested
    class MaxFileSizeTests {

        @Test
        void testUncappedByDefault() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            assertThat(config.getMaxFileSize()).isEqualTo(GridFSConfig.NO_MAX_FILE_SIZE);
            assertThat(config.hasMaxFileSize()).isFalse();
        }

        @Test
        void testBuilderCarriesTheCap() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").maxFileSize(1024).build();

            assertThat(config.getMaxFileSize()).isEqualTo(1024);
            assertThat(config.hasMaxFileSize()).isTrue();
        }

        @Test
        void testSentinelIsNotACap() {
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").maxFileSize(GridFSConfig.NO_MAX_FILE_SIZE).build();

            assertThat(config.hasMaxFileSize()).isFalse();
        }

        @Test
        void testZeroIsACapThatRejectsEveryByte() {
            // Not the same thing as "no limit": -1 is the sentinel, 0 is a bucket nothing may be written into.
            GridFSConfig config = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").maxFileSize(0).build();

            assertThat(config.hasMaxFileSize()).isTrue();
        }

        @Test
        void testRejectsNegativeCapOtherThanTheSentinel() {
            GridFSConfig.Builder builder = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").maxFileSize(-2);

            assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Max file size");
        }

        @Test
        void testSharedClientConfigCarriesTheCap() {
            GridFSConfig config = GridFSConfig.forSharedClient("mydb", "bucket", 1024, 4096);

            assertThat(config.getConnectionString()).isNull();
            assertThat(config.getMaxFileSize()).isEqualTo(4096);
        }

        @Test
        void testSharedClientConfigIsUncappedWithoutOne() {
            GridFSConfig config = GridFSConfig.forSharedClient("mydb", "bucket", 1024);

            assertThat(config.hasMaxFileSize()).isFalse();
        }

        @Test
        void testCapParticipatesInEqualityAndToString() {
            GridFSConfig capped = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").maxFileSize(1024).build();
            GridFSConfig uncapped = GridFSConfig.builder().connectionString("mongodb://localhost:27017")
                    .databaseName("mydb").build();

            assertThat(capped).isNotEqualTo(uncapped);
            assertThat(capped.toString()).contains("maxFileSize=1024");
        }
    }
}
