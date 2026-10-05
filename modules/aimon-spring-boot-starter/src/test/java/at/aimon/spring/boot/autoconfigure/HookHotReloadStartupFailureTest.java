package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.config.hook.HookConfigParseException;
import at.aimon.core.config.hook.HookHotReloadBootstrap;
import at.aimon.core.config.hook.ReloadInvoker;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;

/**
 * EE-71 as a Spring host meets it. The starter does not wire {@code hooks.json} — a host that wants it calls
 * {@link HookHotReloadBootstrap} from a bean of its own, as {@code LiveSessionOpener}'s Javadoc describes. This pins
 * what that pattern now does with a file that does not load: the bean fails, so the context does not start, and the
 * failure names the file.
 */
@DisplayName("A host bean that starts hooks.json hot reload fails the context on a broken file")
class HookHotReloadStartupFailureTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HostHookConfiguration.class);

    @Test
    @DisplayName("a hooks.json that does not parse stops the context; the failure names the file and the layer")
    void brokenHooksJsonFailsTheContext(@TempDir Path root) throws IOException {
        final Path file = Files.createDirectories(root.resolve(".aimon")).resolve("hooks.json");
        Files.writeString(file, "{not valid json");

        runner.withPropertyValues("test.hooks.root=" + root).run(ctx -> {
            assertThat(ctx).hasFailed();
            // Spring wraps the bean's exception; the one naming the file is the outermost of ours in the chain.
            Throwable cause = ctx.getStartupFailure();
            while (cause != null && !(cause instanceof HookConfigParseException)) {
                cause = cause.getCause();
            }
            assertThat(cause).as("a HookConfigParseException in the startup failure's cause chain").isNotNull()
                    .hasMessageContaining(file.toAbsolutePath().toString())
                    .hasMessageContaining("(PROJECT layer) is invalid");
        });
    }

    @Test
    @DisplayName("with no hooks.json the same bean starts, and the watcher is closed with the context")
    void missingHooksJsonStartsTheContext(@TempDir Path root) {
        runner.withPropertyValues("test.hooks.root=" + root).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(HookHotReloadBootstrap.Started.class).isWatcherActive()).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class HostHookConfiguration {

        @Bean(destroyMethod = "close")
        HookHotReloadBootstrap.Started hookHotReload(Environment environment) {
            final Path root = Path.of(environment.getRequiredProperty("test.hooks.root"));
            return HookHotReloadBootstrap.builder().userHome(root.resolve("home")).projectRoot(root)
                    .shellExecutor(new DefaultShellActionExecutor()).processEnv(Map.of())
                    .registry(new DefaultHookRegistry()).invoker(new ReloadInvoker(InvokerType.MAIN_AGENT, "host"))
                    .start();
        }
    }
}
