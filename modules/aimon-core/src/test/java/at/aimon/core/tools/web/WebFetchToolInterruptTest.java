package at.aimon.core.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.tools.web.cache.WebToolCacheRepository;
import at.aimon.core.tools.web.fetch.ContentExtractor;
import at.aimon.core.tools.web.fetch.FetchResult;
import at.aimon.core.tools.web.fetch.HttpContentFetcher;
import at.aimon.core.tools.web.security.SsrfGuard;

/**
 * Verifies the cooperative interrupt contract on {@link WebFetchTool}:
 * <ul>
 * <li>{@link WebFetchTool#getInterruptBehavior()} returns {@link InterruptBehavior#COOPERATIVE}.
 * <li>A tripped {@link at.aimon.core.agent.interrupt.CancellationSignal} before the first blocking phase aborts the
 * fetch before {@link HttpContentFetcher#fetch} is invoked.
 * <li>A trip observed between fetch and extract aborts before {@link ContentExtractor#extract} runs.
 * <li>Absent or untripped signals behave exactly like the pre-IRQ path.
 * </ul>
 */
@DisplayName("WebFetchTool cooperative interrupt")
class WebFetchToolInterruptTest {

    private HttpContentFetcher fetcher;
    private ContentExtractor contentExtractor;
    private WebToolCacheRepository cache;
    private SsrfGuard ssrfGuard;
    private WebFetchTool tool;

    @BeforeEach
    void setUp() {
        fetcher = mock(HttpContentFetcher.class);
        contentExtractor = mock(ContentExtractor.class);
        cache = mock(WebToolCacheRepository.class);
        ssrfGuard = mock(SsrfGuard.class);
        when(ssrfGuard.isSafe(anyString())).thenReturn(true);
        when(cache.get(anyString())).thenReturn(Optional.empty());
        tool = new WebFetchTool(fetcher, contentExtractor, cache, ssrfGuard, Duration.ofMinutes(15), 50_000);
    }

    @Test
    @DisplayName("getInterruptBehavior() == COOPERATIVE")
    void declaresCooperative() {
        assertThat(tool.getInterruptBehavior()).isEqualTo(InterruptBehavior.COOPERATIVE);
    }

    @Test
    @DisplayName("pre-tripped signal aborts fetch before HttpContentFetcher is invoked")
    void preTrippedSignalShortCircuits() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            final ToolContext context = ToolContext.builder()
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal()).build();

            final ToolResult result = tool.execute(ToolInput.of(Map.of("url", "https://example.com")), context);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("WebFetch interrupted").contains("USER_SIGINT");
            verify(fetcher, never()).fetch(anyString());
            verify(contentExtractor, never()).extract(any(), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("signal tripped between fetch and extract aborts before ContentExtractor runs")
    void signalBetweenFetchAndExtractShortCircuits() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            // The fetcher trips the signal during its own blocking call — emulates a session/queue-level interrupt
            // arriving while the HTTP request is in flight.
            when(fetcher.fetch(anyString())).thenAnswer(inv -> {
                coordinator.requestInterrupt(InterruptReason.NOW_PRIORITY_INPUT);
                return FetchResult.builder().body("body").contentType("text/html").statusCode(200).build();
            });

            final ToolContext context = ToolContext.builder()
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal()).build();

            final ToolResult result = tool.execute(ToolInput.of(Map.of("url", "https://example.com")), context);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("WebFetch interrupted").contains("NOW_PRIORITY_INPUT");
            verify(fetcher).fetch(anyString());
            verify(contentExtractor, never()).extract(any(), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("untripped signal path behaves like pre-IRQ normal execution")
    void untrippedSignalPathIsNormal() throws Exception {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            when(fetcher.fetch(anyString())).thenReturn(
                    FetchResult.builder().body("<html>hi</html>").contentType("text/html").statusCode(200).build());
            when(contentExtractor.extract(any(), anyString(), anyString())).thenReturn("hi");

            final ToolContext context = ToolContext.builder()
                    .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal()).build();

            final ToolResult result = tool.execute(ToolInput.of(Map.of("url", "https://example.com")), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("hi");
            verify(fetcher).fetch(anyString());
            verify(contentExtractor).extract(any(), anyString(), anyString());
        }
    }
}
