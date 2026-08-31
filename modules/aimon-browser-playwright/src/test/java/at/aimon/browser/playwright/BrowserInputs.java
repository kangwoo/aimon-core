package at.aimon.browser.playwright;

/**
 * Builds {@link BrowserInput} values for tests.
 *
 * <p>
 * The record has twenty-six components, so its canonical constructor is unusable by hand — a test that wants
 * {@code selector} would have to write twenty-five nulls around it, and adding a component later would break every call
 * site. This fixture names what a test sets and leaves the rest null:
 *
 * <pre>
 * {@code BrowserInputs.action("click").selector("#submit").build()}
 * </pre>
 *
 * <p>
 * It deliberately does <b>not</b> go through {@code ToolInputBinder}. Handler tests exercise guards that binding makes
 * unreachable through the tool — {@code direction=left}, {@code mode=invalid} — and a fixture that validated its own
 * input could not construct those cases at all. Binding is covered where it belongs, in the tests that call
 * {@link BrowserTool#execute}.
 */
public final class BrowserInputs {

    private final String action;

    private String sessionId;

    private String url;

    private String waitUntil;

    private String selector;

    private String text;

    private String role;

    private Boolean exact;

    private String value;

    private String credentialRef;

    private Boolean clear;

    private String key;

    private String direction;

    private Integer amount;

    private Integer waitMs;

    private String mode;

    private Integer maxChars;

    private Boolean fullPage;

    private String savePath;

    private Integer timeoutMs;

    private String locale;

    private String userAgent;

    private Integer viewportWidth;

    private Integer viewportHeight;

    private String resourcePolicy;

    private String storageState;

    private BrowserInputs(String action) {
        this.action = action;
    }

    /**
     * Starts building an input for the given action.
     *
     * @param action
     *            the action name, which may be null or unknown — handlers are reached directly here, not through the
     *            tool's dispatch
     * @return a new builder
     */
    public static BrowserInputs action(String action) {
        return new BrowserInputs(action);
    }

    public BrowserInputs sessionId(String v) {
        this.sessionId = v;
        return this;
    }

    public BrowserInputs url(String v) {
        this.url = v;
        return this;
    }

    public BrowserInputs waitUntil(String v) {
        this.waitUntil = v;
        return this;
    }

    public BrowserInputs selector(String v) {
        this.selector = v;
        return this;
    }

    public BrowserInputs text(String v) {
        this.text = v;
        return this;
    }

    public BrowserInputs role(String v) {
        this.role = v;
        return this;
    }

    public BrowserInputs exact(Boolean v) {
        this.exact = v;
        return this;
    }

    public BrowserInputs value(String v) {
        this.value = v;
        return this;
    }

    public BrowserInputs credentialRef(String v) {
        this.credentialRef = v;
        return this;
    }

    public BrowserInputs clear(Boolean v) {
        this.clear = v;
        return this;
    }

    public BrowserInputs key(String v) {
        this.key = v;
        return this;
    }

    public BrowserInputs direction(String v) {
        this.direction = v;
        return this;
    }

    public BrowserInputs amount(Integer v) {
        this.amount = v;
        return this;
    }

    public BrowserInputs waitMs(Integer v) {
        this.waitMs = v;
        return this;
    }

    public BrowserInputs mode(String v) {
        this.mode = v;
        return this;
    }

    public BrowserInputs maxChars(Integer v) {
        this.maxChars = v;
        return this;
    }

    public BrowserInputs fullPage(Boolean v) {
        this.fullPage = v;
        return this;
    }

    public BrowserInputs savePath(String v) {
        this.savePath = v;
        return this;
    }

    public BrowserInputs timeoutMs(Integer v) {
        this.timeoutMs = v;
        return this;
    }

    public BrowserInputs locale(String v) {
        this.locale = v;
        return this;
    }

    public BrowserInputs userAgent(String v) {
        this.userAgent = v;
        return this;
    }

    public BrowserInputs viewportWidth(Integer v) {
        this.viewportWidth = v;
        return this;
    }

    public BrowserInputs viewportHeight(Integer v) {
        this.viewportHeight = v;
        return this;
    }

    public BrowserInputs resourcePolicy(String v) {
        this.resourcePolicy = v;
        return this;
    }

    public BrowserInputs storageState(String v) {
        this.storageState = v;
        return this;
    }

    /**
     * @return the assembled record
     */
    public BrowserInput build() {
        return new BrowserInput(sessionId, action, url, waitUntil, selector, text, role, exact, value, credentialRef,
                clear, key, direction, amount, waitMs, mode, maxChars, fullPage, savePath, timeoutMs, locale, userAgent,
                viewportWidth, viewportHeight, resourcePolicy, storageState);
    }
}
