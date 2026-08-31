package at.aimon.spring.boot.autoconfigure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.VirtualFileSystemFactory;
import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Decides where the agent's files live.
 *
 * <p>
 * <b>It publishes a {@link FileSystemSpec}, not a {@link VirtualFileSystem}.</b> The distinction is the whole
 * point of the spec layer: a slice contributes an <em>ingredient</em> for the stack to assemble, not a finished
 * part. Publishing a {@code VirtualFileSystem} bean would create a second owner — Spring would infer
 * {@code close()} on {@code LocalFileSystem} while the stack's ordered teardown also closes it, and one of the
 * two would run at the wrong moment. Handing over a {@code localAt(root)} spec instead means the stack builds
 * the filesystem, owns it, and closes it exactly once, and no bean of that type exists for Spring to have an
 * opinion about.
 *
 * <p>
 * An application that defines its own {@code VirtualFileSystem} bean — GridFS, S3, a test double — gets
 * {@code FileSystemSpec.supplied(...)} instead, which tells the stack the instance is borrowed and must not be
 * closed. The application created it, so the application closes it; Spring already will, if it is closeable.
 * Either way exactly one owner exists.
 *
 * <p>
 * <b>A {@link VirtualFileSystemFactory} bean is the third case, and it inverts the ownership again.</b> One
 * filesystem bean is one filesystem for every agent and every tenant, which is what a deployment wants until it
 * has tenants; then it is the bug, because two customers writing to one root can read each other's files. A
 * factory is asked per runtime id and its results are stack-owned, closed with the runtime that used them —
 * which is the only way the per-tenant instances get closed at all, since a bean container cannot destroy
 * objects it never created.
 */
@AutoConfiguration
@ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AimonProperties.class)
public class AimonFileSystemAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FileSystemSpec.class)
    FileSystemSpec aimonFileSystemSpec(AimonProperties properties, ObjectProvider<VirtualFileSystem> fileSystems,
            ObjectProvider<VirtualFileSystemFactory> factories) {
        final VirtualFileSystem supplied = fileSystems.getIfAvailable();
        final VirtualFileSystemFactory factory = factories.getIfAvailable();
        if (supplied != null && factory != null) {
            throw new IllegalStateException("Both a " + VirtualFileSystem.class.getName() + " bean and a "
                    + VirtualFileSystemFactory.class.getName() + " bean are defined, and they are alternatives:"
                    + " the instance is shared by every agent and tenant, the factory makes one per runtime."
                    + " Remove whichever does not match how this deployment separates agent files — sharing one"
                    + " instance across tenants is what the factory exists to avoid.");
        }
        if (factory != null) {
            return FileSystemSpec.factory(factory);
        }
        if (supplied != null) {
            return FileSystemSpec.supplied(supplied);
        }
        final String root = properties.getWorkspace().getRoot();
        if (properties.getWorkspace().isEnsureWritable()) {
            ensureWritable(Path.of(root));
        }
        return FileSystemSpec.localAt(root);
    }

    /**
     * Creates the workspace if it is missing and proves it can be written to.
     *
     * <p>
     * The probe is here rather than in the properties bean because it is I/O, and because this is the slice
     * that knows the workspace is going to be used at all — an application that supplied its own filesystem
     * never reaches this line. Failing at startup turns a read-only mount or a typo'd path into one clear
     * error, instead of the agent's first write failing mid-turn as a tool error the model then tries to
     * reason its way around.
     */
    private static void ensureWritable(Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException(AimonProperties.WORKSPACE_ROOT + "=" + root + " could not be created: "
                    + e.getMessage() + ". Point it at a writable directory, or set "
                    + AimonProperties.WORKSPACE_ENSURE_WRITABLE + "=false to skip this check.", e);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    AimonProperties.WORKSPACE_ROOT + "=" + root + " exists but is not a directory.");
        }
        if (!Files.isWritable(root)) {
            throw new IllegalStateException(AimonProperties.WORKSPACE_ROOT + "=" + root
                    + " is not writable by this process. Grant write access, point it elsewhere, or set "
                    + AimonProperties.WORKSPACE_ENSURE_WRITABLE + "=false if the agent is only ever going to"
                    + " read from it.");
        }
    }
}
