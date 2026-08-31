package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;
import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("ContextResolvingWikiStorageLocator Tests")
class ContextResolvingWikiStorageLocatorTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "agent:ctx-1", "runbook");

    private VirtualFileSystem fileSystem;

    @BeforeEach
    void setUp() {
        fileSystem = mock(VirtualFileSystem.class);
    }

    @Nested
    @DisplayName("defaultLayout")
    class DefaultLayout {

        @Test
        @DisplayName("Produces {root}/{agent}/{ctx}/{wiki} without trailing slash")
        void producesHistoricalLayout() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .defaultLayout(id -> Optional.of(fileSystem), "/wiki");

            assertThat(locator.directoryFor(SCOPE)).isEqualTo("/wiki/ops-agent/agent:ctx-1/runbook");
        }

        @Test
        @DisplayName("Strips trailing slash from root")
        void stripsTrailingSlash() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .defaultLayout(id -> Optional.of(fileSystem), "/wiki/");

            assertThat(locator.directoryFor(SCOPE)).isEqualTo("/wiki/ops-agent/agent:ctx-1/runbook");
        }

        @Test
        @DisplayName("fileSystemFor invokes resolver with the scope's contextId and returns the resolved VFS")
        void resolvesVfs() {
            @SuppressWarnings("unchecked")
            Function<AgentRuntimeId, Optional<VirtualFileSystem>> resolver = mock(Function.class);
            when(resolver.apply(AgentRuntimeIds.testCtx("ctx-1"))).thenReturn(Optional.of(fileSystem));

            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator.defaultLayout(resolver,
                    "/wiki");

            assertThat(locator.fileSystemFor(SCOPE)).isSameAs(fileSystem);
            verify(resolver).apply(AgentRuntimeIds.testCtx("ctx-1"));
        }

        @Test
        @DisplayName("fileSystemFor throws ISE when resolver returns empty")
        void throwsWhenResolverEmpty() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .defaultLayout(id -> Optional.empty(), "/wiki");

            assertThatThrownBy(() -> locator.fileSystemFor(SCOPE)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("agent:ctx-1").hasMessageContaining("agent runtime must exist");
        }

        @Test
        @DisplayName("Each fileSystemFor call invokes the resolver (no caching)")
        void resolvesLazilyEachCall() {
            AtomicInteger calls = new AtomicInteger();
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator.defaultLayout(id -> {
                calls.incrementAndGet();
                return Optional.of(fileSystem);
            }, "/wiki");

            locator.fileSystemFor(SCOPE);
            locator.fileSystemFor(SCOPE);
            locator.fileSystemFor(SCOPE);

            assertThat(calls.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("directoryFor never invokes the resolver")
        void directoryForDoesNotConsultResolver() {
            AtomicInteger calls = new AtomicInteger();
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator.defaultLayout(id -> {
                calls.incrementAndGet();
                return Optional.of(fileSystem);
            }, "/wiki");

            locator.directoryFor(SCOPE);
            locator.directoryFor(new WikiScope("other", "ctx-2", "wiki"));

            assertThat(calls.get()).isZero();
        }

        @Test
        @DisplayName("Null contextResolver throws NPE")
        void nullResolverThrowsNpe() {
            assertThatThrownBy(() -> ContextResolvingWikiStorageLocator.defaultLayout(null, "/wiki"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null wikiRoot throws NPE")
        void nullWikiRootThrowsNpe() {
            assertThatThrownBy(() -> ContextResolvingWikiStorageLocator.defaultLayout(id -> Optional.empty(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Empty wikiRoot throws IAE")
        void emptyWikiRootThrowsIae() {
            assertThatThrownBy(() -> ContextResolvingWikiStorageLocator.defaultLayout(id -> Optional.empty(), ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Null scope throws NPE on directoryFor")
        void nullScopeOnDirectoryThrowsNpe() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .defaultLayout(id -> Optional.of(fileSystem), "/wiki");

            assertThatThrownBy(() -> locator.directoryFor(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null scope throws NPE on fileSystemFor")
        void nullScopeOnFileSystemThrowsNpe() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .defaultLayout(id -> Optional.of(fileSystem), "/wiki");

            assertThatThrownBy(() -> locator.fileSystemFor(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("contextScoped")
    class AgentScoped {

        @Test
        @DisplayName("Produces {root}/{ctx}/{wiki} dropping agentName")
        void producesContextScopedLayout() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .contextScoped(id -> Optional.of(fileSystem), "/data/agent-1/.knowledge/wiki");

            assertThat(locator.directoryFor(SCOPE)).isEqualTo("/data/agent-1/.knowledge/wiki/agent:ctx-1/runbook");
        }

        @Test
        @DisplayName("Strips trailing slash from root")
        void stripsTrailingSlash() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .contextScoped(id -> Optional.of(fileSystem), "/wiki/");

            assertThat(locator.directoryFor(SCOPE)).isEqualTo("/wiki/agent:ctx-1/runbook");
        }

        @Test
        @DisplayName("fileSystemFor returns the resolved VFS")
        void resolvesVfs() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .contextScoped(id -> Optional.of(fileSystem), "/wiki");

            assertThat(locator.fileSystemFor(SCOPE)).isSameAs(fileSystem);
        }

        @Test
        @DisplayName("Throws ISE when resolver returns empty")
        void throwsWhenResolverEmpty() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .contextScoped(id -> Optional.empty(), "/wiki");

            assertThatThrownBy(() -> locator.fileSystemFor(SCOPE)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Null contextResolver throws NPE")
        void nullResolverThrowsNpe() {
            assertThatThrownBy(() -> ContextResolvingWikiStorageLocator.contextScoped(null, "/wiki"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Empty wikiRoot throws IAE")
        void emptyWikiRootThrowsIae() {
            assertThatThrownBy(() -> ContextResolvingWikiStorageLocator.contextScoped(id -> Optional.empty(), ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Determinism")
    class Determinism {

        @Test
        @DisplayName("Equal scopes produce equal directories")
        void deterministic() {
            ContextResolvingWikiStorageLocator locator = ContextResolvingWikiStorageLocator
                    .defaultLayout(id -> Optional.of(fileSystem), "/wiki");
            WikiScope a = new WikiScope("agent", "ctx", "wiki");
            WikiScope b = new WikiScope("agent", "ctx", "wiki");

            assertThat(locator.directoryFor(a)).isEqualTo(locator.directoryFor(b));
        }
    }
}
