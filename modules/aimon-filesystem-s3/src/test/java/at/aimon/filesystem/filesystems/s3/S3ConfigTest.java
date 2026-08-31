package at.aimon.filesystem.filesystems.s3;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import at.aimon.filesystem.core.s3.S3Config;

/** Tests for S3Config construction and builder pattern. */
class S3ConfigTest {

    @Test
    void testBuilderCreatesConfig() {
        S3Config config = S3Config.builder().bucketName("my-bucket").region("eu-west-1").accessKey("AKIA12345678")
                .secretKey("secret123").endpoint("http://localhost:4566").build();

        assertThat(config.getBucketName()).isEqualTo("my-bucket");
        assertThat(config.getRegion()).isEqualTo("eu-west-1");
        assertThat(config.getAccessKey()).isEqualTo("AKIA12345678");
        assertThat(config.getSecretKey()).isEqualTo("secret123");
        assertThat(config.getEndpoint()).isEqualTo("http://localhost:4566");
        assertThat(config.hasCustomEndpoint()).isTrue();
    }

    @Test
    void testBuilderDefaultRegion() {
        S3Config config = S3Config.builder().bucketName("my-bucket").accessKey("key").secretKey("secret").build();

        assertThat(config.getRegion()).isEqualTo(S3Config.DEFAULT_REGION);
    }

    @Test
    void testBuilderWithoutEndpoint() {
        S3Config config = S3Config.builder().bucketName("my-bucket").region("us-east-1").accessKey("key")
                .secretKey("secret").build();

        assertThat(config.getEndpoint()).isNull();
        assertThat(config.hasCustomEndpoint()).isFalse();
    }

    @Test
    void testBuilderRejectsNullBucketName() {
        assertThatThrownBy(() -> S3Config.builder().accessKey("key").secretKey("secret").build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Bucket name");
    }

    @Test
    void testBuilderRejectsNullAccessKey() {
        assertThatThrownBy(() -> S3Config.builder().bucketName("bucket").secretKey("secret").build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Access key");
    }

    @Test
    void testBuilderRejectsNullSecretKey() {
        assertThatThrownBy(() -> S3Config.builder().bucketName("bucket").accessKey("key").build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Secret key");
    }

    @Test
    void testConstructorAndBuilderEquivalence() {
        S3Config fromConstructor = new S3Config("bucket", "us-east-1", "key", "secret", "http://localhost");
        S3Config fromBuilder = S3Config.builder().bucketName("bucket").region("us-east-1").accessKey("key")
                .secretKey("secret").endpoint("http://localhost").build();

        assertThat(fromBuilder).isEqualTo(fromConstructor);
        assertThat(fromBuilder.hashCode()).isEqualTo(fromConstructor.hashCode());
    }

    @Test
    void testToStringMasksKeys() {
        S3Config config = S3Config.builder().bucketName("bucket").accessKey("AKIA12345678")
                .secretKey("supersecretkey123").build();

        String str = config.toString();
        assertThat(str).contains("AKIA***");
        assertThat(str).contains("supe***");
        assertThat(str).doesNotContain("AKIA12345678");
        assertThat(str).doesNotContain("supersecretkey123");
    }

    @Test
    void testThreeArgConstructorUsesDefaultRegion() {
        S3Config config = new S3Config("bucket", "key", "secret");

        assertThat(config.getRegion()).isEqualTo(S3Config.DEFAULT_REGION);
        assertThat(config.getEndpoint()).isNull();
    }

    @Test
    void testEqualityAndHashCode() {
        S3Config a = S3Config.builder().bucketName("b").accessKey("k").secretKey("s").build();
        S3Config b = S3Config.builder().bucketName("b").accessKey("k").secretKey("s").build();
        S3Config c = S3Config.builder().bucketName("other").accessKey("k").secretKey("s").build();

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void testUncappedByDefault() {
        S3Config config = S3Config.builder().bucketName("b").accessKey("k").secretKey("s").build();

        assertThat(config.getMaxFileSize()).isEqualTo(S3Config.NO_MAX_FILE_SIZE);
        assertThat(config.hasMaxFileSize()).isFalse();
    }

    @Test
    void testBuilderCarriesTheCap() {
        S3Config config = S3Config.builder().bucketName("b").accessKey("k").secretKey("s").maxFileSize(1024).build();

        assertThat(config.getMaxFileSize()).isEqualTo(1024);
        assertThat(config.hasMaxFileSize()).isTrue();
    }

    @Test
    void testSentinelIsNotACap() {
        S3Config config = S3Config.builder().bucketName("b").accessKey("k").secretKey("s")
                .maxFileSize(S3Config.NO_MAX_FILE_SIZE).build();

        assertThat(config.hasMaxFileSize()).isFalse();
    }

    @Test
    void testZeroIsACapThatRejectsEveryByte() {
        // Not the same thing as "no limit": -1 is the sentinel, 0 is a bucket nothing may be written into.
        S3Config config = S3Config.builder().bucketName("b").accessKey("k").secretKey("s").maxFileSize(0).build();

        assertThat(config.hasMaxFileSize()).isTrue();
    }

    @Test
    void testRejectsNegativeCapOtherThanTheSentinel() {
        S3Config.Builder builder = S3Config.builder().bucketName("b").accessKey("k").secretKey("s").maxFileSize(-2);

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Max file size");
    }

    @Test
    void testConstructorCarriesTheCap() {
        S3Config config = new S3Config("bucket", "eu-west-1", "key", "secret", "http://localhost:4566", 4096);

        assertThat(config.getMaxFileSize()).isEqualTo(4096);
    }

    @Test
    void testCapParticipatesInEqualityAndToString() {
        S3Config capped = S3Config.builder().bucketName("b").accessKey("k").secretKey("s").maxFileSize(1024).build();
        S3Config uncapped = S3Config.builder().bucketName("b").accessKey("k").secretKey("s").build();

        assertThat(capped).isNotEqualTo(uncapped);
        assertThat(capped.toString()).contains("maxFileSize=1024");
    }
}
