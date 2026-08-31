package at.aimon.core.tools.web;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.InterruptAccess;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.tools.web.cache.WebToolCacheRepository;
import at.aimon.core.tools.web.fetch.ContentExtractor;
import at.aimon.core.tools.web.fetch.FetchResult;
import at.aimon.core.tools.web.fetch.HttpContentFetcher;
import at.aimon.core.tools.web.security.SsrfGuard;
import at.aimon.core.tools.web.util.WebToolUtils;

/**
 * Web content fetch tool for LLM agents.
 *
 * <p>
 * Thin orchestrator that delegates HTTP fetching to {@link HttpContentFetcher},
 * content extraction to {@link ContentExtractor}, and validates URLs via {@link SsrfGuard}.
 *
 * <p>
 * Flow: URL validation → SSRF check → cache check → fetch → extract → truncate → cache store.
 */
public class WebFetchTool extends AbstractTool {

    public static final String TOOL_NAME = "WebFetch";

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int DEFAULT_MAX_CHARS = 50_000;

    private final HttpContentFetcher fetcher;
    private final ContentExtractor contentExtractor;
    private final WebToolCacheRepository cache;
    private final SsrfGuard ssrfGuard;
    private final Duration cacheTtl;
    private final int maxCharsCap;

    /**
     * Creates a new WebFetchTool.
     *
     * @param fetcher
     *            the HTTP content fetcher (holds HTTP settings)
     * @param contentExtractor
     *            the content extractor
     * @param cache
     *            the cache repository
     * @param ssrfGuard
     *            the SSRF guard for initial URL validation
     * @param cacheTtl
     *            the cache TTL
     * @param maxCharsCap
     *            the maximum return character count cap (must be &gt; 0)
     */
    public WebFetchTool(HttpContentFetcher fetcher, ContentExtractor contentExtractor, WebToolCacheRepository cache,
            SsrfGuard ssrfGuard, Duration cacheTtl, int maxCharsCap) {
        super(TOOL_NAME,
                "Fetch and extract content from a web URL. "
                        + "Returns the main content of the page as markdown or plain text. "
                        + "Only http/https URLs are allowed.",
                ToolCategories.SEARCH, createInputSchema());
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher cannot be null");
        this.contentExtractor = Objects.requireNonNull(contentExtractor, "contentExtractor cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.ssrfGuard = Objects.requireNonNull(ssrfGuard, "ssrfGuard cannot be null");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl cannot be null");
        if (maxCharsCap < 1) {
            throw new IllegalArgumentException("maxCharsCap must be > 0");
        }
        this.maxCharsCap = maxCharsCap;
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("url",
                Map.of("type", "string", "description", "The URL to fetch (http/https only)"), "extract_mode",
                Map.of("type", "string", "description", "Content extraction mode", "enum", List.of("markdown", "text")),
                "max_chars",
                Map.of("type", "number", "description", "Maximum characters to return (content will be truncated)")),
                "required", List.of("url"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final CancellationSignal signal = InterruptAccess.signalOf(context);

        try {
            // 1. Parameter extraction
            final String url = input.getRequiredString("url");
            final String extractMode = input.getString("extract_mode", "markdown");
            final int maxChars = WebToolUtils.clamp(input.getInteger("max_chars", maxCharsCap), 1, maxCharsCap);

            // 2. URL validation + SSRF blocking (fast-fail)
            if (!WebToolUtils.isValidHttpUrl(url)) {
                return ToolResult.error("Only http/https URLs are allowed: " + url);
            }
            if (!ssrfGuard.isSafe(url)) {
                return ToolResult.error("URL blocked by security policy: " + url);
            }

            // Cooperative checkpoint before the first blocking operation. Subsequent checkpoints sit on every
            // phase boundary we can observe; the underlying HTTP fetch itself is not interruptible here, so a trip
            // taken mid-fetch will surface on return.
            if (signal.isCancelled()) {
                return interruptedResult(signal);
            }

            // 3. Cache lookup
            final String cacheKey = WebToolUtils.buildCacheKey(url, extractMode);
            final Optional<String> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                log.debug("Cache hit for URL: {}", url);
                return ToolResult.success(cached.get());
            }

            // 4. Delegate to HttpContentFetcher
            final FetchResult fetchResult = fetcher.fetch(url);
            if (signal.isCancelled()) {
                return interruptedResult(signal);
            }

            // 5. Delegate to ContentExtractor
            final String content = contentExtractor.extract(fetchResult.getBody(), url, extractMode);
            if (signal.isCancelled()) {
                return interruptedResult(signal);
            }

            // 6. Apply maxChars truncation
            final boolean wasTruncated = content.length() > maxChars;
            final String truncated = WebToolUtils.truncate(content, maxChars);

            // 7. Build result
            final String result = buildResult(url, fetchResult, truncated, wasTruncated);

            // 8. Store in cache
            cache.put(cacheKey, result, cacheTtl);

            log.debug("Fetch completed: url={}, chars={}", url, truncated.length());
            return ToolResult.success(result);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (SocketTimeoutException e) {
            log.warn("Fetch timeout: {}", e.getMessage());
            return ToolResult.error("Request timed out");
        } catch (IOException e) {
            log.warn("Fetch failed: {}", e.getMessage());
            return ToolResult.error("Fetch failed: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            log.error("Out of memory during fetch: {}", e.getMessage(), e);
            return ToolResult.error("Out of memory: response too large to process");
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Declares {@link InterruptBehavior#COOPERATIVE}: the tool polls the {@link CancellationSignal} between the
     * cache / fetch / extract phases and returns early on cancellation. The underlying HTTP request inside
     * {@link HttpContentFetcher} is not itself interruptible here, so a mid-fetch trip surfaces on the next phase
     * boundary.
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.COOPERATIVE;
    }

    /**
     * Declares {@link ConcurrencyBehavior#CONCURRENT_SAFE}. {@code WebFetch} performs an idempotent external GET; all
     * of
     * its dependencies are stateless or synchronized (the cache repository is synchronized), and it allocates its
     * mutable state locally per invocation, so it is safe to run concurrently with other concurrent-safe tools.
     *
     * <p>
     * Note: the cache is a check-then-act, so two concurrent fetches of the same URL may both miss and fetch
     * independently (last-write-wins on the cache entry). This is acceptable for idempotent GETs — it costs a redundant
     * request, not correctness.
     */
    @Override
    public ConcurrencyBehavior getConcurrencyBehavior() {
        return ConcurrencyBehavior.CONCURRENT_SAFE;
    }

    /**
     * Declares {@link SideEffectLevel#READ_ONLY}. {@code WebFetch} issues an HTTP GET, which carries no server-side
     * effect by HTTP semantics, and its only write is the response cache — a transparent read-through cache, which
     * {@link SideEffectLevel#READ_ONLY} exempts because the entry changes only the latency of the next identical
     * call, never its meaning.
     */
    @Override
    public SideEffectLevel getSideEffectLevel() {
        return SideEffectLevel.READ_ONLY;
    }

    private ToolResult interruptedResult(CancellationSignal signal) {
        final String reason = signal.getReason().map(InterruptReason::name).orElse("UNKNOWN");
        log.debug("WebFetch interrupted: {}", reason);
        return ToolResult.error("WebFetch interrupted: " + reason);
    }

    private String buildResult(String url, FetchResult fetchResult, String content, boolean wasTruncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(url).append('\n');
        sb.append("Content-Type: ").append(fetchResult.getContentType()).append('\n');
        if (wasTruncated) {
            sb.append("[Warning: Content was truncated to fit the character limit]\n");
        }
        sb.append('\n');
        sb.append(content);
        return sb.toString();
    }
}
