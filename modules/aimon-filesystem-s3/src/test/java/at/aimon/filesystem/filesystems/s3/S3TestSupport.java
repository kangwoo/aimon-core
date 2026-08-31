package at.aimon.filesystem.filesystems.s3;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;

import at.aimon.filesystem.core.s3.S3Config;
import at.aimon.filesystem.core.s3.S3FileSystem;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/**
 * Shared test infrastructure for S3 integration tests. Manages a singleton LocalStack container and provides factory
 * methods for test setup and cleanup.
 */
final class S3TestSupport {

    static final String BUCKET_NAME = "test-bucket";

    static final LocalStackContainer LOCALSTACK;
    static final S3Client ADMIN_CLIENT;

    static {
        // Keep this within reach of the AWS SDK in the version catalog. Nothing updates it for us:
        // the tag is a string literal in a Java file, so no dependabot ecosystem sees it, which is
        // how it sat at 3.0 while the SDK moved on. When the SDK started sending its default
        // integrity checksum on PutObject, 3.0 answered with a Python AttributeError dressed as an
        // S3 500 -- a failure that reads like a bug in the code under test and is not one. The
        // neighbouring suites pin rolling tags (redis:7-alpine, postgres:16-alpine) and so never
        // drifted this way.
        LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
                .withServices(Service.S3);
        LOCALSTACK.start();

        ADMIN_CLIENT = S3Client.builder().endpointOverride(LOCALSTACK.getEndpointOverride(Service.S3))
                .credentialsProvider(StaticCredentialsProvider
                        .create(AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .region(Region.of(LOCALSTACK.getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();

        ADMIN_CLIENT.createBucket(CreateBucketRequest.builder().bucket(BUCKET_NAME).build());
    }

    private S3TestSupport() {
    }

    static S3Config createConfig() {
        return createConfig(S3Config.NO_MAX_FILE_SIZE);
    }

    static S3Config createConfig(long maxFileSize) {
        return S3Config.builder().bucketName(BUCKET_NAME).region(LOCALSTACK.getRegion())
                .accessKey(LOCALSTACK.getAccessKey()).secretKey(LOCALSTACK.getSecretKey())
                .endpoint(LOCALSTACK.getEndpointOverride(Service.S3).toString()).maxFileSize(maxFileSize).build();
    }

    static S3FileSystem createAndInitialize() {
        return initialized(createConfig());
    }

    static S3FileSystem createAndInitialize(long maxFileSize) {
        return initialized(createConfig(maxFileSize));
    }

    private static S3FileSystem initialized(S3Config config) {
        S3FileSystem fs = new S3FileSystem(config);
        fs.initialize();
        return fs;
    }

    static void cleanBucket() {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(BUCKET_NAME).build();
        ListObjectsV2Response response;
        String continuationToken = null;

        do {
            if (continuationToken != null) {
                listRequest = listRequest.toBuilder().continuationToken(continuationToken).build();
            }
            response = ADMIN_CLIENT.listObjectsV2(listRequest);

            if (!response.contents().isEmpty()) {
                List<ObjectIdentifier> objects = new ArrayList<>();
                for (var obj : response.contents()) {
                    objects.add(ObjectIdentifier.builder().key(obj.key()).build());
                }
                ADMIN_CLIENT.deleteObjects(DeleteObjectsRequest.builder().bucket(BUCKET_NAME)
                        .delete(Delete.builder().objects(objects).quiet(true).build()).build());
            }

            continuationToken = response.nextContinuationToken();
        } while (response.isTruncated());
    }
}
