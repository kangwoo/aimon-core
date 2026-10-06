package at.aimon.filesystem.core.gridfs;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which etag a file document gets (EE-5), without a database: the rule for old and new documents is a pure function
 * of the document, and the Docker tier ({@code GridFSFileSystemEtagTest}) checks that writes produce such documents.
 */
@DisplayName("GridFSFileSystem.etagOf — content hash when recorded, file id otherwise (EE-5)")
class GridFSFileSystemEtagOfTest {

    private static final ObjectId ID = new ObjectId("6ac37bfecc1cf70a8c71a33a");
    private static final String HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    @DisplayName("a document carrying a content hash reports it, prefixed")
    void hashWhenRecorded() {
        final Document metadata = new Document("path", "a.txt").append(GridFSFileSystem.CONTENT_HASH_METADATA_KEY,
                HASH);

        assertThat(GridFSFileSystem.etagOf(ID, metadata)).isEqualTo("sha256:" + HASH);
    }

    @Test
    @DisplayName("a document without one — written before the key existed — reports its id")
    void idWithoutAHash() {
        assertThat(GridFSFileSystem.etagOf(ID, new Document("path", "a.txt"))).isEqualTo(ID.toHexString());
        assertThat(GridFSFileSystem.etagOf(ID, null)).isEqualTo(ID.toHexString());
    }

    @Test
    @DisplayName("a hash that is blank or not a string is not trusted")
    void malformedHashIgnored() {
        assertThat(GridFSFileSystem.etagOf(ID, new Document(GridFSFileSystem.CONTENT_HASH_METADATA_KEY, " ")))
                .isEqualTo(ID.toHexString());
        assertThat(GridFSFileSystem.etagOf(ID, new Document(GridFSFileSystem.CONTENT_HASH_METADATA_KEY, 42)))
                .isEqualTo(ID.toHexString());
    }

    @Test
    @DisplayName("an id and a hash can never be equal: two documents of one content, one hashed and one not, differ")
    void theTwoShapesNeverCollide() {
        // Even a hash that is itself 24 hex characters, which a SHA-256 never is.
        final Document odd = new Document(GridFSFileSystem.CONTENT_HASH_METADATA_KEY, ID.toHexString());

        assertThat(GridFSFileSystem.etagOf(ID, odd)).isNotEqualTo(GridFSFileSystem.etagOf(ID, null));
    }
}
