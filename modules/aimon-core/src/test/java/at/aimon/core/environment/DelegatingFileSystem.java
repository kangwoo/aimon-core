package at.aimon.core.environment;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;

/** A test {@link VirtualFileSystem} that forwards every call; subclasses override the calls they want to observe. */
public class DelegatingFileSystem implements VirtualFileSystem {

    private final VirtualFileSystem delegate;

    public DelegatingFileSystem(VirtualFileSystem delegate) {
        this.delegate = delegate;
    }

    @Override
    public void write(String path, InputStream content, long contentLength) {
        delegate.write(path, content, contentLength);
    }

    @Override
    public InputStream read(String path) {
        return delegate.read(path);
    }

    @Override
    public void delete(String path) {
        delegate.delete(path);
    }

    @Override
    public boolean exists(String path) {
        return delegate.exists(path);
    }

    @Override
    public boolean isDirectory(String path) {
        return delegate.isDirectory(path);
    }

    @Override
    public FileMetadata getMetadata(String path) {
        return delegate.getMetadata(path);
    }

    @Override
    public List<String> list(String directory) {
        return delegate.list(directory);
    }

    @Override
    public List<String> listRecursive(String directory) {
        return delegate.listRecursive(directory);
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        delegate.copy(sourcePath, destinationPath, overwrite);
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        delegate.move(sourcePath, destinationPath, overwrite);
    }

    @Override
    public OutputStream openOutputStream(String path) {
        return delegate.openOutputStream(path);
    }

    @Override
    public InputStream openInputStream(String path) {
        return delegate.openInputStream(path);
    }

    @Override
    public void createDirectory(String path) {
        delegate.createDirectory(path);
    }

    @Override
    public void deleteRecursive(String path) {
        delegate.deleteRecursive(path);
    }

    @Override
    public List<String> search(String directory, String pattern, int maxResults) {
        return delegate.search(directory, pattern, maxResults);
    }

    @Override
    public String getWorkingDirectory() {
        return delegate.getWorkingDirectory();
    }

    @Override
    public void initialize() {
        delegate.initialize();
    }

    @Override
    public BackendStatus getStatus() {
        return delegate.getStatus();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
