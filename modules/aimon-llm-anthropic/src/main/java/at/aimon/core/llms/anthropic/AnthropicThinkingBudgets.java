package at.aimon.core.llms.anthropic;

import java.util.Optional;
import java.util.OptionalInt;

import com.anthropic.models.messages.OutputConfig;

import at.aimon.core.llm.ReasoningEffort;

/**
 * Translates the provider-neutral {@link ReasoningEffort} ladder onto what Anthropic actually accepts.
 *
 * <p>
 * <strong>In {@link AnthropicThinkingMode#EXTENDED} the two axes do not match, and pretending otherwise would be the
 * dishonest part.</strong> {@code ReasoningEffort} is a five-rung ladder; extended thinking takes a <em>token
 * budget</em>. The mapping is therefore a choice, and only two of its four numbers are the vendor's:
 *
 * <table border="1">
 * <caption>effort to {@code budget_tokens}</caption>
 * <tr>
 * <th>rung</th>
 * <th>budget</th>
 * <th>where the number comes from</th>
 * </tr>
 * <tr>
 * <td>{@code NONE}</td>
 * <td>—</td>
 * <td>no {@code thinking} parameter at all; the caller omits it</td>
 * </tr>
 * <tr>
 * <td>{@code MINIMAL}</td>
 * <td>1024</td>
 * <td>the API's documented floor, and its own advice for simple tasks</td>
 * </tr>
 * <tr>
 * <td>{@code LOW}</td>
 * <td>2048</td>
 * <td>doubled from the floor — <strong>arbitrary</strong></td>
 * </tr>
 * <tr>
 * <td>{@code MEDIUM}</td>
 * <td>4096</td>
 * <td>doubled again — <strong>arbitrary</strong></td>
 * </tr>
 * <tr>
 * <td>{@code HIGH}</td>
 * <td>16000</td>
 * <td>the docs' own starting point for complex tasks</td>
 * </tr>
 * </table>
 *
 * <p>
 * The gap between {@code MEDIUM} and {@code HIGH} is deliberately the widest, because the vendor's guidance is itself
 * bimodal — near-1024 for simple work, 16000+ for complex — rather than a linear scale. Nothing above 16000 is
 * reachable, which keeps every request clear of the "above 32k, use batch processing" warning. What is <em>not</em>
 * arbitrary, and what the tests pin: the mapping is monotonic non-decreasing (the neutral enum's declaration order is
 * documented as load-bearing), it never falls below the 1024 floor the API rejects outright, it is always strictly
 * below {@code max_tokens}, and when no legal budget exists it gives up rather than sending a request the server is
 * certain to reject.
 *
 * <p>
 * In {@link AnthropicThinkingMode#ADAPTIVE} it is a ladder-to-ladder mapping and barely a mapping at all — but onto
 * <em>this SDK's</em> ladder, not the API's: {@code OutputConfig.Effort} here offers {@code low}/{@code medium}/
 * {@code high}/{@code max}, while the current API also has {@code xhigh}. The top of the vendor's range is unreachable
 * for two independent reasons — nothing in the neutral ladder sits above {@code HIGH}, and {@code xhigh} is not in
 * this SDK version at all. The one real loss is {@code MINIMAL} and {@code LOW} collapsing onto {@code low}, because
 * Anthropic's ladder starts there.
 *
 * <p>
 * <strong>One direction is an inverse rather than a mapping.</strong> {@link #nearestEffort(int)} goes from a token
 * count back to a rung, which the two mappings above cannot do because they are not injective — it exists for the one
 * request shape that has a budget and needs an effort, and it is the only lossy step here. See its javadoc.
 *
 * <p>
 * Pure and stateless. Reporting the clamp and the give-up belongs to the caller, which owns the divergence log.
 */
final class AnthropicThinkingBudgets {

    /** The API rejects any {@code budget_tokens} below this, on every request. */
    static final int MINIMUM_BUDGET_TOKENS = 1024;

    private static final int MINIMAL_BUDGET = 1024;
    private static final int LOW_BUDGET = 2048;
    private static final int MEDIUM_BUDGET = 4096;
    private static final int HIGH_BUDGET = 16000;

    private AnthropicThinkingBudgets() {
    }

    /**
     * The budget this call asks for, before it is measured against {@code max_tokens}.
     *
     * @param effort
     *            the call's reasoning effort, or {@code null} when the caller set none — which resolves to the
     *            {@code MEDIUM} rung, the same middle default the neutral ladder's own midpoint names
     * @param configuredBudget
     *            an explicit {@link AnthropicConfig#getThinkingBudgetTokens()}, or {@code null}. When set it wins:
     *            an operator who named a number meant that number, and the ladder is what fills in for its absence
     * @return the requested budget
     */
    static int requestedBudget(ReasoningEffort effort, Integer configuredBudget) {
        if (configuredBudget != null) {
            return configuredBudget;
        }
        if (effort == null) {
            return MEDIUM_BUDGET;
        }
        return switch (effort) {
            // NONE never reaches here — the caller omits the whole parameter — but the ladder still has to be total,
            // and the floor is the only answer that cannot be rejected.
            case NONE, MINIMAL -> MINIMAL_BUDGET;
            case LOW -> LOW_BUDGET;
            case MEDIUM -> MEDIUM_BUDGET;
            case HIGH -> HIGH_BUDGET;
        };
    }

    /**
     * The budget that can legally be sent, or empty when there is none.
     *
     * <p>
     * Thinking tokens count against {@code max_tokens} for the turn, so the budget must be strictly below it. That
     * bites immediately rather than at the edges: {@link AnthropicConfig}'s default {@code maxTokens} is 4096, so
     * {@code HIGH} clamps to 4095 out of the box. Empty means {@code max_tokens} leaves no room for even the floor —
     * omitting the parameter is better than sending one the server will certainly reject.
     *
     * @param effort
     *            the call's reasoning effort, or {@code null}
     * @param configuredBudget
     *            an explicit configured budget, or {@code null}
     * @param maxTokens
     *            the {@code max_tokens} this request carries
     * @return the budget to send, or empty when no legal budget exists
     */
    static OptionalInt budgetFor(ReasoningEffort effort, Integer configuredBudget, int maxTokens) {
        if (maxTokens <= MINIMUM_BUDGET_TOKENS) {
            return OptionalInt.empty();
        }
        final int requested = requestedBudget(effort, configuredBudget);
        final int clamped = Math.min(requested, maxTokens - 1);
        return OptionalInt.of(Math.max(clamped, MINIMUM_BUDGET_TOKENS));
    }

    /**
     * The rung whose budget is nearest the given token count — the one lossy step in the whole translation.
     *
     * <p>
     * It exists for one corner: an operator who set an explicit {@code thinkingBudgetTokens} (which
     * {@link AnthropicConfig} accepts only under {@link AnthropicThinkingMode#EXTENDED}) against a model the
     * capability table says speaks the adaptive dialect. A token count has no adaptive counterpart, so the request
     * cannot carry the number; the nearest rung is what survives, and the caller names both in its warning rather
     * than substituting silently.
     *
     * <p>
     * This is the only direction of the mapping that loses information, because it is the only one that is not a
     * function of the neutral ladder: {@link #requestedBudget} and {@link #effortFor} both start from a rung.
     * Distances are compared against the same four numbers {@code requestedBudget} produces, so the two stay in step
     * by construction, and a tie resolves <em>downwards</em> — asking for less thinking than an ambiguous number
     * might have meant is the cheaper of the two mistakes.
     *
     * @param budgetTokens
     *            a {@code budget_tokens} value
     * @return the nearest rung; never {@link ReasoningEffort#NONE}, which is the absence of thinking rather than an
     *         amount of it
     */
    static ReasoningEffort nearestEffort(int budgetTokens) {
        ReasoningEffort nearest = ReasoningEffort.MINIMAL;
        long best = Long.MAX_VALUE;
        for (ReasoningEffort rung : new ReasoningEffort[]{ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM, ReasoningEffort.HIGH}) {
            final long distance = Math.abs((long) budgetTokens - requestedBudget(rung, null));
            if (distance < best) {
                best = distance;
                nearest = rung;
            }
        }
        return nearest;
    }

    /**
     * Maps a neutral effort onto {@code output_config.effort} for adaptive mode.
     *
     * @param effort
     *            the call's reasoning effort, or {@code null} when the caller set none
     * @return the SDK effort, or empty when there is nothing to send — no effort was set, or it was {@code NONE},
     *         which suppresses the {@code thinking} parameter entirely and so has no effort to accompany it
     */
    static Optional<OutputConfig.Effort> effortFor(ReasoningEffort effort) {
        if (effort == null) {
            return Optional.empty();
        }
        return switch (effort) {
            case NONE -> Optional.empty();
            case MINIMAL, LOW -> Optional.of(OutputConfig.Effort.LOW);
            case MEDIUM -> Optional.of(OutputConfig.Effort.MEDIUM);
            case HIGH -> Optional.of(OutputConfig.Effort.HIGH);
        };
    }
}
