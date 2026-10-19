package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.RunDirectoryIntegrityException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.skill.hook.action.HookScriptDirectory;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * Rule 3 of {@link ShellFeature#RUN_DIRECTORY}, through a second provider: a shell whose files travel through
 * something the agent can write (here an "upload" directory, as the sandbox's files API is) checks what it placed
 * against digests that did not travel that way, and does not run the command on a mismatch (EE-109). No aimon-sandbox
 * dependency: this pins the contract the sandbox implements.
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("RUN_DIRECTORY through an agent-writable upload (EE-109, contract rule 3)")
class RunDirectoryUploadContractTest {

    @TempDir
    Path tmp;

    private ShellAction guard(Path layer, Path marker) {
        return new ShellAction("touch " + marker + "; bash \"$AIMON_HOOK_DIR/guard.sh\"", Duration.ofSeconds(10),
                HookScriptDirectory.of(layer, "hooks-project"));
    }

    private Path layer() throws IOException {
        final Path layer = Files.createDirectories(tmp.resolve("project/.aimon"));
        Files.createDirectories(layer.resolve("hooks"));
        Files.writeString(layer.resolve("hooks/guard.sh"), "echo 'not allowed' >&2; exit 2\n");
        return layer;
    }

    private static HookResult fire(DeclarativePreToolHook hook, VirtualShell shell) {
        return hook.execute(PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).executionEnvironment(TestExecutionEnvironments.ofShell(shell))
                .toolUse(ToolUse.of("call-1", "Bash", Map.of())).iterationCount(1).build());
    }

    private static DeclarativePreToolHook hook(ShellAction action, boolean failOpen) {
        return new DeclarativePreToolHook("hooks.json", NameOnlyPredicate.ANY, action,
                new IsolatedShellActionExecutor(), null, null, Map.of(),
                DeclarativeHookOptions.builder().failOpen(failOpen).build());
    }

    @Test
    @DisplayName("an intact upload runs the operator's guard, whose veto blocks")
    void intactUploadRuns() throws Exception {
        final Path marker = tmp.resolve("ran");
        final UploadingShell shell = new UploadingShell(Files.createDirectories(tmp.resolve("upload")), dir -> {
        });

        final HookResult result = fire(hook(guard(layer(), marker), false), shell);

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("not allowed");
        assertThat(marker).exists();
    }

    @Test
    @DisplayName("an upload rewritten before placement is not run, and blocks even a failOpen guard")
    void tamperedUploadBlocksEvenWithFailOpen() throws Exception {
        final Path marker = tmp.resolve("ran");
        final UploadingShell shell = new UploadingShell(Files.createDirectories(tmp.resolve("upload")), dir -> {
            try {
                // What a background loop of the agent's shell can do to a file it can write: allow everything.
                Files.writeString(dir.resolve("guard.sh"), "exit 0\n");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        final HookResult result = fire(hook(guard(layer(), marker), true), shell);

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("the hook's files changed before the run");
        assertThat(marker).as("the command never started").doesNotExist();
    }

    /**
     * Implements {@link ShellFeature#RUN_DIRECTORY} the way a remote shell has to: upload the files, copy them into
     * a private directory, check the copy against SHA-256 digests computed from the bytes it was handed, then run.
     */
    private static final class UploadingShell implements VirtualShell {

        private final LocalShell local = new LocalShell();
        private final Path upload;
        private final Consumer<Path> betweenUploadAndPlacement;

        UploadingShell(Path upload, Consumer<Path> betweenUploadAndPlacement) {
            this.upload = upload;
            this.betweenUploadAndPlacement = betweenUploadAndPlacement;
        }

        @Override
        public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
            return execute(command, ExecutionOptions.defaults());
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options)
                throws ShellExecutionException {
            if (options.getRunDirectory().isEmpty()) {
                return local.execute(command, options);
            }
            final RunDirectory run = options.getRunDirectory().get();
            Path placed = null;
            try {
                // The digests travel with the request, not through the upload.
                final Map<String, String> digests = new HashMap<>();
                for (String relPath : run.getPaths()) {
                    digests.put(relPath, sha256(run.getContent(relPath)));
                    final Path uploaded = upload.resolve(relPath);
                    Files.createDirectories(uploaded.getParent());
                    Files.write(uploaded, run.getContent(relPath));
                }
                betweenUploadAndPlacement.accept(upload);
                placed = Files.createTempDirectory("aimon-run-");
                for (String relPath : run.getPaths()) {
                    final Path target = placed.resolve(relPath);
                    Files.createDirectories(target.getParent());
                    Files.copy(upload.resolve(relPath), target);
                    if (!digests.get(relPath).equals(sha256(Files.readAllBytes(target)))) {
                        throw new RunDirectoryIntegrityException(relPath + " does not match its digest");
                    }
                }
                final Map<String, String> env = new HashMap<>(options.getEnvironment());
                env.put(run.getVariable(), placed.toString());
                return local.execute(command, options.toBuilder().runDirectory(null).environment(env).build());
            } catch (IOException e) {
                throw new ShellExecutionException("upload failed", e);
            } finally {
                delete(placed);
            }
        }

        private static String sha256(byte[] bytes) {
            try {
                return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void delete(Path dir) {
            if (dir == null) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
                // test cleanup
            }
        }

        @Override
        public String getWorkingDirectory() {
            return null;
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return feature == ShellFeature.ISOLATED_EXECUTION || feature == ShellFeature.RUN_DIRECTORY;
        }

        @Override
        public void close() {
        }
    }
}
