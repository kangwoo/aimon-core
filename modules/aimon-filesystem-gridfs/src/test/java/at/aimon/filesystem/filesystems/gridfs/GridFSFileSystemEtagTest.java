package at.aimon.filesystem.filesystems.gridfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;

import at.aimon.core.environment.FileStamp;
import at.aimon.core.filesystem.exception.InsufficientStorageException;
import at.aimon.filesystem.core.gridfs.GridFSFileSystem;

/**
 * The etag of a GridFS file is a hash of its content (EE-5): the file tools' read stamps compare etags, and the file
 * document's id — the etag before this — changes on every write, also one that writes the same bytes.
 */
@Tag("docker")
@DisplayName("GridFSFileSystem etag — a content hash, with the file id for files written before it (EE-5)")
class GridFSFileSystemEtagTest {

    private GridFSFileSystem fs;

    @BeforeEach
    void setUp() {
        GridFSTestSupport.cleanDatabase();
        fs = GridFSTestSupport.createAndInitialize();
    }

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.close();
        }
    }

    private void write(String path, String content) {
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        fs.write(path, new ByteArrayInputStream(bytes), bytes.length);
    }

    private String etag(String path) {
        return fs.getMetadata(path).getEtag().orElseThrow();
    }

    private FileStamp stamp(String path) {
        return FileStamp.of(fs.getMetadata(path));
    }

    /** Writes a file the way a version before EE-5 did: a GridFS document with no content hash in its metadata. */
    private static ObjectId writeLegacy(String path, String content) {
        try (MongoClient client = MongoClients.create(GridFSTestSupport.MONGO.getConnectionString())) {
            return GridFSBuckets.create(client.getDatabase(GridFSTestSupport.DATABASE_NAME)).uploadFromStream(path,
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                    new GridFSUploadOptions().metadata(new Document("path", path)));
        }
    }

    private static Document fileDocument(String path) {
        try (MongoClient client = MongoClients.create(GridFSTestSupport.MONGO.getConnectionString())) {
            return client.getDatabase(GridFSTestSupport.DATABASE_NAME).getCollection("fs.files")
                    .find(Filters.eq("filename", path)).first();
        }
    }

    @Test
    @DisplayName("rewriting a file with the same bytes keeps its etag, so a read stamp taken before still matches")
    void identicalRewriteKeepsTheEtag() throws Exception {
        write("a.txt", "same bytes");
        final FileStamp read = stamp("a.txt");
        Thread.sleep(5);

        write("a.txt", "same bytes");

        assertThat(etag("a.txt")).isEqualTo(read.getEtag().orElseThrow());
        assertThat(stamp("a.txt")).as("not reported as changed since it was read").isEqualTo(read);
    }

    @Test
    @DisplayName("a rewrite of the same size with other bytes changes the etag")
    void differentContentChangesTheEtag() {
        write("a.txt", "version-1");
        final FileStamp read = stamp("a.txt");

        write("a.txt", "version-2");

        assertThat(stamp("a.txt")).isNotEqualTo(read);
    }

    @Test
    @DisplayName("the etag depends on the bytes only: bulk write, streamed write and copy of one content agree")
    void everyWritePathHashesTheContent() throws Exception {
        write("bulk.txt", "one content");
        try (OutputStream out = fs.openOutputStream("streamed.txt")) {
            out.write("one ".getBytes(StandardCharsets.UTF_8));
            out.write('c');
            out.write("ontent".getBytes(StandardCharsets.UTF_8));
        }
        fs.copy("bulk.txt", "copied.txt", false);
        fs.move("copied.txt", "moved.txt", false);

        assertThat(etag("bulk.txt")).startsWith("sha256:").hasSize("sha256:".length() + 64);
        assertThat(etag("streamed.txt")).isEqualTo(etag("bulk.txt"));
        assertThat(etag("moved.txt")).isEqualTo(etag("bulk.txt"));
        assertThat(fileDocument("bulk.txt").get("metadata", Document.class)
                .getString(GridFSFileSystem.CONTENT_HASH_METADATA_KEY))
                .isEqualTo(etag("bulk.txt").substring("sha256:".length()));
    }

    @Test
    @DisplayName("an empty file has an etag too, the hash of no bytes")
    void emptyFile() {
        write("empty.txt", "");

        assertThat(etag("empty.txt"))
                .isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("a file written before the hash existed keeps the file id as its etag")
    void legacyFileFallsBackToTheFileId() {
        final ObjectId id = writeLegacy("old.txt", "legacy");

        assertThat(etag("old.txt")).isEqualTo(id.toHexString());
        assertThat(stamp("old.txt")).isEqualTo(stamp("old.txt"));
    }

    @Test
    @DisplayName("mixed old and new: every rewrite across the two reads as changed, never as unchanged")
    void mixedVersionsNeverMissAChange() {
        // Old writer, then this one, same bytes: the false positive the id always gave, once.
        writeLegacy("a.txt", "bytes");
        final FileStamp readOld = stamp("a.txt");
        write("a.txt", "bytes");
        final FileStamp readNew = stamp("a.txt");
        assertThat(readNew).isNotEqualTo(readOld);

        // This writer, then an old one (a rolling deploy), other bytes of the same size.
        fs.delete("a.txt");
        write("a.txt", "bytes");
        final FileStamp hashed = stamp("a.txt");
        fs.delete("a.txt");
        writeLegacy("a.txt", "BYTES");
        assertThat(stamp("a.txt")).isNotEqualTo(hashed);
    }

    @Test
    @DisplayName("a write refused for its size leaves the previous content and its etag")
    void refusedWriteKeepsTheEtag() {
        fs.close();
        fs = GridFSTestSupport.createAndInitialize(8);
        write("a.txt", "12345678");
        final String before = etag("a.txt");

        assertThatThrownBy(() -> write("a.txt", "123456789")).isInstanceOf(InsufficientStorageException.class);

        assertThat(etag("a.txt")).isEqualTo(before);
    }

    @Test
    @DisplayName("a directory has no etag")
    void directoryHasNoEtag() {
        fs.createDirectory("docs");

        assertThat(fs.getMetadata("docs").getEtag()).isEmpty();
    }
}
