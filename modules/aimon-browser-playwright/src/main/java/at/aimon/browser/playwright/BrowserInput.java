package at.aimon.browser.playwright;

import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.agent.tool.generic.ToolParam;

/**
 * The parameters of a {@link BrowserTool} call, and the source of its schema.
 *
 * <p>
 * A record rather than the project's usual builder class — the narrow exception granted to {@link GenericTool} input
 * types, whose reasoning is in {@code at.aimon.core.agent.tool.generic}'s package documentation.
 *
 * <p>
 * This is the largest input surface in the tree: twenty-six parameters that used to be a 132-line hand-written
 * {@code Map.ofEntries} nested four deep, which the design cites as the point where {@code Map.of} had already broken
 * down (it caps at ten pairs, so the properties map had to switch forms).
 *
 * <p>
 * <b>Only {@code action} is required.</b> Most of the rest are required <em>for one action and meaningless for the
 * others</em> — {@code url} for {@code open}, {@code key} for {@code press}, {@code direction} for {@code scroll},
 * {@code selector} and {@code value} for {@code select}. JSON Schema's flat {@code required} list cannot say "required
 * when action=open", so those stay optional here and the owning handler reports them missing. That division is
 * deliberate: binding enforces what the schema can actually express, and per-action conditionals stay where the action
 * lives.
 *
 * <p>
 * The last six parameters ({@code locale} through {@code storage_state}) apply only when a call omits
 * {@code session_id} and therefore opens a new session; on a call that reuses a session they are ignored.
 *
 * @param sessionId
 *            the session to act on, or null to open a new one
 * @param action
 *            which action to perform
 * @param url
 *            the address to navigate to, for {@code open}
 * @param waitUntil
 *            the navigation readiness signal to wait for, or null for {@code domcontentloaded}
 * @param selector
 *            a CSS selector identifying the target element
 * @param text
 *            visible text identifying the target element, or the accessible name alongside {@code role}
 * @param role
 *            an ARIA role identifying the target element
 * @param exact
 *            whether text matching is exact, or null for false
 * @param value
 *            the text to type or the option to select
 * @param credentialRef
 *            a {@code profile.field} reference resolved from the credential store instead of {@code value}
 * @param clear
 *            whether to empty the field before typing, or null for false
 * @param key
 *            the keyboard key to press
 * @param direction
 *            which way to scroll
 * @param amount
 *            how far to scroll in pixels, or null for the default
 * @param waitMs
 *            how long to wait in milliseconds, or null for the default
 * @param mode
 *            which rendering to extract, or null for {@code text}
 * @param maxChars
 *            how much extracted content to keep, or null for the default
 * @param fullPage
 *            whether to capture beyond the viewport, or null for false
 * @param savePath
 *            where to write the screenshot, or null to return base64 instead
 * @param timeoutMs
 *            the per-action timeout, or null for the default
 * @param locale
 *            the locale for a newly opened session
 * @param userAgent
 *            the user agent for a newly opened session
 * @param viewportWidth
 *            the viewport width for a newly opened session, or null for the default
 * @param viewportHeight
 *            the viewport height for a newly opened session, or null for the default
 * @param resourcePolicy
 *            which resources a newly opened session loads, or null for {@code minimal}
 * @param storageState
 *            a {@code save_auth} payload restoring authentication into a newly opened session
 */
public record BrowserInput(

        @ToolParam(name = "session_id", description = "Browser session ID. "
                + "Omit to create a new session.") String sessionId,

        @ToolParam(required = true, description = "The browser action to perform.", allowed = {
                "open", "click", "type", "press", "select", "scroll", "wait", "extract", "screenshot", "back",
                "forward", "reload", "close", "save_auth"}) String action,

        @ToolParam(description = "URL to navigate to (action=open)") String url,

        @ToolParam(name = "wait_until", allowed = {"domcontentloaded", "load",
                "networkidle"}, description = "Navigation wait strategy (default: domcontentloaded)") String waitUntil,

        @ToolParam(description = "CSS selector for target element") String selector,

        @ToolParam(description = "Text to match element (click) or label (click)") String text,

        @ToolParam(description = "ARIA role for element lookup (click)") String role,

        @ToolParam(description = "Exact text matching (default: false)") Boolean exact,

        @ToolParam(description = "Value to type (action=type) or select (action=select). "
                + "For type action, either value or credential_ref must be provided.") String value,

        @ToolParam(name = "credential_ref", description = "Credential reference in 'profile.field' format. "
                + "For action=type only. " + "Resolves the actual value from CredentialStore so the LLM "
                + "never sees sensitive data. Use instead of value for " + "passwords, tokens, and secrets. "
                + "Available profiles are listed in the tool description. "
                + "Cannot be used together with value.") String credentialRef,

        @ToolParam(description = "Clear field before typing (default: false)") Boolean clear,

        @ToolParam(description = "Key to press (e.g. Enter, Tab, Escape)") String key,

        @ToolParam(allowed = {"up", "down"}, description = "Scroll direction") String direction,

        @ToolParam(description = "Scroll amount in pixels (default: 500)") Integer amount,

        @ToolParam(name = "wait_ms", description = "Milliseconds to wait (action=wait)") Integer waitMs,

        @ToolParam(allowed = {"text", "html", "markdown"}, description = "Extraction mode") String mode,

        @ToolParam(name = "max_chars", description = "Maximum characters to return (default: 50000)") Integer maxChars,

        @ToolParam(name = "full_page", description = "Capture full page screenshot (default: false)") Boolean fullPage,

        @ToolParam(name = "save_path", description = "File path to save screenshot directly (PNG). "
                + "If provided, base64 is not included in the response.") String savePath,

        @ToolParam(name = "timeout_ms", description = "Action timeout in milliseconds "
                + "(default: 30000, range: 1000-120000)") Integer timeoutMs,

        @ToolParam(description = "Browser locale for new session (e.g. en-US, ko-KR)") String locale,

        @ToolParam(name = "user_agent", description = "User agent string for new session") String userAgent,

        @ToolParam(name = "viewport_width", description = "Viewport width for new session "
                + "(default: 1280)") Integer viewportWidth,

        @ToolParam(name = "viewport_height", description = "Viewport height for new session "
                + "(default: 720)") Integer viewportHeight,

        @ToolParam(name = "resource_policy", allowed = {"minimal",
                "visual"}, description = "Resource loading policy for new session (default: minimal). "
                        + "minimal: blocks images/fonts/media/stylesheets for fast text extraction. "
                        + "visual: loads all resources for accurate screenshots.") String resourcePolicy,

        @ToolParam(name = "storage_state", description = "Storage state JSON (cookies + localStorage) "
                + "from save_auth action. "
                + "Provide when creating a new session to restore authentication state.") String storageState){
}
