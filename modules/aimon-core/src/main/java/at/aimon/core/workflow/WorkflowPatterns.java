package at.aimon.core.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import at.aimon.core.subagent.Subagent;

/**
 * Reusable quality-pattern helpers built purely on the {@link WorkflowContext} primitives (design §6.4) —
 * named, tested crystallisations of flows an author can already express with {@code agent}/{@code parallel}/
 * {@code pipeline}. No new execution primitive is added to the SPI.
 *
 * <p>
 * Every helper follows three load-bearing rules: (1) it is <b>null-tolerant</b> of {@code parallel} results (a thunk
 * that throws yields {@code null} at its position); (2) it never catches {@code WorkflowException} (a run-fatal
 * budget abort must propagate); and (3) it <b>flattens cross-products</b> into a single top-level {@code parallel}
 * rather than nesting {@code parallel} inside a fan-out worker, so it achieves real concurrency (a nested fan-out
 * degrades to sequential up to {@code maxNestingDepth}).
 */
public final class WorkflowPatterns {

    private WorkflowPatterns() {
    }

    /**
     * Fans N independent skeptics out to try to refute {@code finding}, then tallies a majority verdict (§6.4). Each
     * skeptic is the same immutable {@code Subagent} run against a JSON-emit goal (its per-execution
     * {@code ToolContext}
     * is isolated, so reuse is safe). Skeptics that produce no {@code refuted} boolean abstain.
     *
     * @param ctx
     *            the run context (must not be null)
     * @param finding
     *            the finding to challenge (must not be null)
     * @param skeptic
     *            the skeptic subagent (must not be null)
     * @param n
     *            the number of skeptics (must be &gt;= 1)
     * @param quorum
     *            the minimum valid votes required for a decisive verdict, else {@link Verdict#isInconclusive()} (must
     *            be &gt;= 1)
     * @param refuteSchema
     *            the JSON schema requesting a {@code {refuted: boolean, reason: string}} verdict (nullable — then
     *            skeptics abstain and the verdict is inconclusive)
     * @return the tallied verdict
     * @throws IllegalArgumentException
     *             if {@code n < 1} or {@code quorum < 1}
     */
    public static Verdict adversarialVerify(WorkflowContext ctx, String finding, Subagent skeptic, int n, int quorum,
            Map<String, Object> refuteSchema) {
        Objects.requireNonNull(ctx, "ctx cannot be null");
        Objects.requireNonNull(finding, "finding cannot be null");
        Objects.requireNonNull(skeptic, "skeptic cannot be null");
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1, got: " + n);
        }
        if (quorum < 1) {
            throw new IllegalArgumentException("quorum must be >= 1, got: " + quorum);
        }
        final String goal = "Independently attempt to REFUTE the following finding. Decide whether it is refuted and "
                + "return your verdict.\n\nFinding: " + finding;
        final List<Supplier<AgentStepResult>> thunks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final AgentTask task = AgentTask.builder().subagent(skeptic).goal(goal).resultSchema(refuteSchema).build();
            thunks.add(() -> ctx.agent(task));
        }
        final List<AgentStepResult> results = ctx.parallel(thunks);
        final List<Boolean> votes = new ArrayList<>();
        for (final AgentStepResult r : results) {
            readBoolean(r, "refuted").ifPresent(votes::add);
        }
        return Verdict.tally(votes, n, quorum);
    }

    /**
     * Runs a set of candidate producers, has a panel of judges score each (flattened into one top-level fan-out),
     * picks the highest mean score, and synthesizes a final answer from the winner (§6.4).
     *
     * @param ctx
     *            the run context (must not be null)
     * @param attempts
     *            the candidate-producing tasks (must not be null)
     * @param judge
     *            the judge subagent (must not be null)
     * @param panelSize
     *            the number of judges per candidate (must be &gt;= 1)
     * @param synthesizer
     *            the subagent that synthesizes the final answer from the winner (nullable — then no synthesis)
     * @param scoreSchema
     *            the JSON schema requesting a {@code {score: number}} verdict (nullable — then every candidate scores
     *            as
     *            NaN and none is selected)
     * @return the judged result
     */
    public static JudgedResult judgePanel(WorkflowContext ctx, List<AgentTask> attempts, Subagent judge, int panelSize,
            Subagent synthesizer, Map<String, Object> scoreSchema) {
        Objects.requireNonNull(ctx, "ctx cannot be null");
        Objects.requireNonNull(attempts, "attempts cannot be null");
        Objects.requireNonNull(judge, "judge cannot be null");
        if (panelSize < 1) {
            throw new IllegalArgumentException("panelSize must be >= 1, got: " + panelSize);
        }
        // 1. Produce the candidates concurrently.
        final List<Supplier<AgentStepResult>> candidateThunks = new ArrayList<>(attempts.size());
        for (final AgentTask t : attempts) {
            candidateThunks.add(() -> ctx.agent(t));
        }
        final List<AgentStepResult> candidates = ctx.parallel(candidateThunks);
        final int nc = candidates.size();

        // 2. Flatten (candidate x judge) into ONE top-level fan-out, so scoring runs concurrently (never nested).
        final List<Integer> pairCandidate = new ArrayList<>();
        final List<Supplier<AgentStepResult>> judgeThunks = new ArrayList<>();
        for (int c = 0; c < nc; c++) {
            final AgentStepResult candidate = candidates.get(c);
            if (candidate == null || !candidate.isSuccess()) {
                continue;
            }
            final String candText = candidate.text();
            for (int j = 0; j < panelSize; j++) {
                final AgentTask jt = AgentTask.builder().subagent(judge)
                        .goal("Score this candidate from 0 to 10 for quality; return {score: number}.\n\nCandidate:\n"
                                + candText)
                        .resultSchema(scoreSchema).build();
                pairCandidate.add(c);
                judgeThunks.add(() -> ctx.agent(jt));
            }
        }
        final List<AgentStepResult> judgeResults = judgeThunks.isEmpty() ? List.of() : ctx.parallel(judgeThunks);

        // 3. Aggregate a mean score per candidate.
        final double[] sum = new double[nc];
        final int[] count = new int[nc];
        for (int k = 0; k < judgeResults.size(); k++) {
            final int candIdx = pairCandidate.get(k);
            final Optional<Double> score = readNumber(judgeResults.get(k), "score");
            if (score.isPresent()) {
                sum[candIdx] += score.get();
                count[candIdx]++;
            }
        }
        final List<Double> scores = new ArrayList<>(nc);
        int bestIdx = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < nc; c++) {
            final double mean = count[c] > 0 ? sum[c] / count[c] : Double.NaN;
            scores.add(mean);
            if (count[c] > 0 && mean > bestScore) {
                bestScore = mean;
                bestIdx = c;
            }
        }
        final AgentStepResult best = bestIdx >= 0 ? candidates.get(bestIdx) : null;

        // 4. Synthesize from the winner.
        AgentStepResult synthesis = null;
        if (best != null && synthesizer != null) {
            synthesis = ctx.agent(synthesizer,
                    "Synthesize a final answer grounded in the best candidate below.\n\nBest candidate:\n"
                            + best.text());
        }
        return JudgedResult.of(best, bestIdx, scores, synthesis);
    }

    /**
     * Runs dependency-ordered rounds sequentially, stopping after {@code quietK} consecutive rounds that completed
     * normally and produced nothing new (§6.4). Gating on {@link AgentStepResult#isComplete()} keeps a budget stop from
     * being miscounted as an empty round. The agent-count backstop is the hard runaway guard.
     *
     * @param ctx
     *            the run context (must not be null)
     * @param round
     *            builds the task for round {@code r} (must not be null)
     * @param isEmpty
     *            predicate: did this round produce nothing new? (must not be null)
     * @param quietK
     *            stop after this many consecutive empty rounds (must be &gt;= 1)
     * @param maxRounds
     *            hard cap on rounds (must be &gt;= 1)
     * @return the results of every round run, in order (may contain nulls)
     */
    public static List<AgentStepResult> loopUntilDry(WorkflowContext ctx, IntFunction<AgentTask> round,
            Predicate<AgentStepResult> isEmpty, int quietK, int maxRounds) {
        Objects.requireNonNull(ctx, "ctx cannot be null");
        Objects.requireNonNull(round, "round cannot be null");
        Objects.requireNonNull(isEmpty, "isEmpty cannot be null");
        if (quietK < 1 || maxRounds < 1) {
            throw new IllegalArgumentException("quietK and maxRounds must be >= 1");
        }
        final List<AgentStepResult> all = new ArrayList<>();
        int consecutiveEmpty = 0;
        for (int r = 0; r < maxRounds && consecutiveEmpty < quietK; r++) {
            final AgentStepResult result = ctx.agent(round.apply(r));
            all.add(result);
            if (result != null && result.isComplete() && isEmpty.test(result)) {
                consecutiveEmpty++;
            } else {
                consecutiveEmpty = 0;
            }
        }
        return all;
    }

    /**
     * Iteratively critiques and revises a draft up to {@code maxRevisions} times (§6.4).
     *
     * <p>
     * Each cycle is gated on the step-outcome signals ({@link AgentStepResult#isSuccess()} /
     * {@link AgentStepResult#isComplete()}) the design prescribes for judgment: an unsuccessful or incomplete critique
     * ends the loop without revising (its {@code text()} is an error message or a truncated critique, and an
     * immediate identical retry would only burn budget), and an unsuccessful or incomplete revision is discarded (the
     * previous draft is kept) and ends the loop. The returned result is therefore always either a successful,
     * normally-completed revision or the original {@code draft}; a failed step's error text never replaces a good
     * draft.
     *
     * @param ctx
     *            the run context (must not be null)
     * @param draft
     *            the starting draft (must not be null)
     * @param critic
     *            the subagent that critiques (must not be null)
     * @param reviser
     *            the subagent that revises (must not be null)
     * @param maxRevisions
     *            the maximum number of critique/revise cycles (must be &gt;= 0)
     * @return the last accepted revision, or the original draft when no revision was accepted (including
     *         {@code maxRevisions == 0})
     */
    public static AgentStepResult completenessCritic(WorkflowContext ctx, AgentStepResult draft, Subagent critic,
            Subagent reviser, int maxRevisions) {
        Objects.requireNonNull(ctx, "ctx cannot be null");
        Objects.requireNonNull(draft, "draft cannot be null");
        Objects.requireNonNull(critic, "critic cannot be null");
        Objects.requireNonNull(reviser, "reviser cannot be null");
        if (maxRevisions < 0) {
            throw new IllegalArgumentException("maxRevisions must be >= 0, got: " + maxRevisions);
        }
        AgentStepResult current = draft;
        for (int i = 0; i < maxRevisions; i++) {
            final AgentStepResult critique = ctx.agent(critic,
                    "Critique this draft for completeness and correctness; list what is missing or wrong.\n\nDraft:\n"
                            + current.text());
            if (critique == null || !critique.isSuccess() || !critique.isComplete()) {
                // A failed or truncated critique is no critique at all; revising against its error text would
                // poison the draft, and an immediate identical retry would only burn budget. Keep the good draft.
                break;
            }
            final AgentStepResult revised = ctx.agent(reviser,
                    "Revise the draft to address the critique.\n\nCritique:\n" + critique.text() + "\n\nDraft:\n"
                            + current.text());
            if (revised == null || !revised.isSuccess() || !revised.isComplete()) {
                // A failed reviser reports its error message as text(); never let it replace the good draft.
                break;
            }
            current = revised;
        }
        return current;
    }

    /**
     * Verifies a claim from N distinct perspectives concurrently (§6.4) — one subagent per perspective, a single
     * top-level fan-out.
     *
     * @param ctx
     *            the run context (must not be null)
     * @param claim
     *            the claim to verify (must not be null)
     * @param perspectives
     *            the distinct perspective subagents (must not be null)
     * @param schema
     *            the JSON schema each perspective should emit (nullable)
     * @return the per-perspective results in order (may contain nulls)
     */
    public static List<AgentStepResult> perspectiveDiverseVerify(WorkflowContext ctx, String claim,
            List<Subagent> perspectives, Map<String, Object> schema) {
        Objects.requireNonNull(ctx, "ctx cannot be null");
        Objects.requireNonNull(claim, "claim cannot be null");
        Objects.requireNonNull(perspectives, "perspectives cannot be null");
        final List<Supplier<AgentStepResult>> thunks = new ArrayList<>(perspectives.size());
        for (final Subagent sa : perspectives) {
            final AgentTask task = AgentTask.builder().subagent(sa)
                    .goal("From your distinct perspective, verify the following claim.\n\nClaim: " + claim)
                    .resultSchema(schema).build();
            thunks.add(() -> ctx.agent(task));
        }
        return ctx.parallel(thunks);
    }

    /**
     * Sweeps a target through several blind, independent modes concurrently (§6.4) — one task per mode, a single
     * top-level fan-out. Each mode task is a full {@link AgentTask} the caller has already targeted.
     *
     * @param ctx
     *            the run context (must not be null)
     * @param modes
     *            the per-mode tasks (must not be null)
     * @return the per-mode results in order (may contain nulls)
     */
    public static List<AgentStepResult> multiModalSweep(WorkflowContext ctx, List<AgentTask> modes) {
        Objects.requireNonNull(ctx, "ctx cannot be null");
        Objects.requireNonNull(modes, "modes cannot be null");
        final List<Supplier<AgentStepResult>> thunks = new ArrayList<>(modes.size());
        for (final AgentTask t : modes) {
            thunks.add(() -> ctx.agent(t));
        }
        return ctx.parallel(thunks);
    }

    private static Optional<Boolean> readBoolean(AgentStepResult result, String key) {
        if (result == null) {
            return Optional.empty();
        }
        return result.structured().map(m -> m.get(key)).filter(Boolean.class::isInstance).map(Boolean.class::cast);
    }

    private static Optional<Double> readNumber(AgentStepResult result, String key) {
        if (result == null) {
            return Optional.empty();
        }
        return result.structured().map(m -> m.get(key)).filter(Number.class::isInstance)
                .map(v -> ((Number) v).doubleValue());
    }
}
