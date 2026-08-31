package at.aimon.workflow.graaljs;

import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * Settles the async-wrapper {@code Promise} and detaches its value into a plain-Java {@code String}, all before the
 * {@code Context} closes.
 *
 * <p>
 * The author body runs inside {@code (async () => { ... })()}, so evaluation yields a {@code Promise}. Because the
 * synchronous bridge only ever {@code await}s non-thenables, the promise is already settled when the microtask queue
 * drains at the embedder boundary — {@link #settle(Value)} attaches capture handlers and reads the result
 * synchronously. A promise that fails to settle synchronously means genuinely-suspending async (unsupported, no
 * event loop) and is surfaced loudly.
 */
final class JsResultMarshaller {

    private JsResultMarshaller() {
    }

    /**
     * Resolves the async-wrapper promise to its fulfilled value on the owner thread. Throws {@link JsScriptException}
     * on rejection or non-synchronous settlement.
     */
    static Value settle(Value promise) {
        if (promise == null) {
            return null;
        }
        if (!isThenable(promise)) {
            return promise;
        }

        final Value[] fulfilled = new Value[1];
        final Value[] rejected = new Value[1];
        final boolean[] settled = new boolean[1];
        // Rejection is tracked by a dedicated flag, not by the reason value: `throw undefined` / `Promise.reject()`
        // reject with a null-ish reason that must still fail the run, never masquerade as an empty success.
        final boolean[] wasRejected = new boolean[1];

        final ProxyExecutable onFulfilled = args -> {
            fulfilled[0] = args.length > 0 ? args[0] : null;
            settled[0] = true;
            return null;
        };
        final ProxyExecutable onRejected = args -> {
            rejected[0] = args.length > 0 ? args[0] : null;
            wasRejected[0] = true;
            settled[0] = true;
            return null;
        };

        // Attaching then-handlers schedules a microtask; the embedder drains it before invokeMember returns, so an
        // already-settled promise reports its outcome synchronously here.
        promise.invokeMember("then", onFulfilled, onRejected);

        if (!settled[0]) {
            throw new JsScriptException(
                    "workflow promise did not settle synchronously (genuinely-async scripts are unsupported)");
        }
        if (wasRejected[0]) {
            final Value reason = rejected[0];
            final String detail = reason == null || reason.isNull() ? "(no reason)" : reason.toString();
            throw new JsScriptException("workflow script rejected: " + detail);
        }
        return fulfilled[0];
    }

    /**
     * Detaches a settled guest value into a plain-Java {@code String} before {@code Context.close()}: scalars via
     * {@code String.valueOf}, {@code Map}/{@code List} as JSON. A {@code null}/{@code undefined} result becomes the
     * empty string.
     */
    static String detach(Value settled) {
        if (settled == null || settled.isNull()) {
            return "";
        }
        final Object detached = JsMarshalling.deepDetach(settled);
        if (detached == null) {
            return "";
        }
        if (detached instanceof String string) {
            return string;
        }
        if (detached instanceof Map || detached instanceof List) {
            return JsMarshalling.toJson(detached);
        }
        return String.valueOf(detached);
    }

    private static boolean isThenable(Value value) {
        return value.hasMembers() && value.hasMember("then") && value.getMember("then").canExecute();
    }
}
