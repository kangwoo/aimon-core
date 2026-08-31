package at.aimon.workflow.graaljs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.workflow.AgentStepResult;

/**
 * Marshals an {@link AgentStepResult} into a fresh {@code ProxyObject} snapshot for guest consumption (owner thread).
 *
 * <p>
 * Under {@code HostAccess.NONE} the guest may only see proxy values, never a raw host object — so every field is
 * copied into a fresh {@link ProxyObject}/{@link ProxyArray}. The JS-visible shape is
 * {@code {text, structured, isSuccess, isComplete, completionReason, label}} where {@code structured} is the parsed
 * map (recursively proxy-wrapped) or {@code null}.
 */
final class AgentResultView {

    private AgentResultView() {
    }

    /** A fresh proxy snapshot of one step result. */
    static ProxyObject of(AgentStepResult result) {
        final Map<String, Object> view = new LinkedHashMap<>();
        view.put("text", result.text());
        view.put("structured", result.structured().map(JsMarshalling::toGuest).orElse(null));
        view.put("isSuccess", result.isSuccess());
        view.put("isComplete", result.isComplete());
        final CompletionReason reason = result.completionReason();
        view.put("completionReason", reason != null ? reason.name() : null);
        view.put("label", result.getLabel());
        return ProxyObject.fromMap(view);
    }

    /**
     * A fresh proxy array of step results, preserving input order and null-isolating failed slots ({@code null} →
     * guest {@code null}).
     */
    static ProxyArray array(List<AgentStepResult> results) {
        final List<Object> guest = new ArrayList<>(results.size());
        for (final AgentStepResult result : results) {
            guest.add(result == null ? null : of(result));
        }
        return ProxyArray.fromList(guest);
    }
}
