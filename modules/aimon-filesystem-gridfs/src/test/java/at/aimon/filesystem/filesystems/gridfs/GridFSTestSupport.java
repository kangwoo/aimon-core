package at.aimon.filesystem.filesystems.gridfs;

import org.testcontainers.containers.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import at.aimon.filesystem.core.gridfs.GridFSConfig;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

/**
 * Shared test infrastructure for GridFS integration tests. Manages a singleton MongoDB container and provides factory
 * methods for test setup and cleanup.
 */
final class GridFSTestSupport {

    static final String DATABASE_NAME = "testdb";

    static final MongoDBContainer MONGO;

    static {
        MONGO = new MongoDBContainer("mongo:6.0");
        MONGO.start();
    }

    private GridFSTestSupport() {
    }

    static GridFSConfig createConfig() {
        return GridFSConfig.builder().connectionString(MONGO.getConnectionString()).databaseName(DATABASE_NAME).build();
    }

    static GridFSConfig createConfig(long maxFileSize) {
        return GridFSConfig.builder().connectionString(MONGO.getConnectionString()).databaseName(DATABASE_NAME)
                .maxFileSize(maxFileSize).build();
    }

    static GridFSFileSystem createAndInitialize() {
        return initialized(createConfig());
    }

    static GridFSFileSystem createAndInitialize(long maxFileSize) {
        return initialized(createConfig(maxFileSize));
    }

    private static GridFSFileSystem initialized(GridFSConfig config) {
        GridFSFileSystem fs = new GridFSFileSystem(config);
        fs.initialize();
        return fs;
    }

    static void cleanDatabase() {
        try (MongoClient client = MongoClients.create(MONGO.getConnectionString())) {
            MongoDatabase db = client.getDatabase(DATABASE_NAME);
            db.getCollection("fs.files").drop();
            db.getCollection("fs.chunks").drop();
        }
    }

    /**
     * Counts documents in a bucket collection directly, bypassing {@link GridFSFileSystem}. Tests that assert nothing
     * was left behind need to look underneath the abstraction, because a stranded upload is invisible from above.
     */
    static long countDocuments(String collectionName) {
        try (MongoClient client = MongoClients.create(MONGO.getConnectionString())) {
            return client.getDatabase(DATABASE_NAME).getCollection(collectionName).countDocuments();
        }
    }
}
