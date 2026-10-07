package at.aimon.core.llms.openai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * The three tasks the context engines are compared on, as scripts: every user input and every tool result is decided
 * by {@code (kind, seed, pressure)}, and the only things left to the model are its answers and what it looks up again
 * (context-engine §13.11).
 *
 * <p>
 * <strong>Pressure</strong> is the estimated tokens a task pushes into the log before the first question — the user
 * inputs of its pressure phase and the tool results they fetch — divided by the effective window. The estimate is the
 * one the engines decide by, so a pressure of 2 means two windows' worth by the number that triggers compaction. The
 * question prompts are not part of it: they come after the pressure is in place.
 *
 * <p>
 * <strong>Two unit sizes, because the rolling engine prunes before it summarizes.</strong> A tool result at or over
 * the engine's prune floor is elided to a placeholder, and while eliding is enough the engine asks for no summary. So
 * {@link #needle} fetches results under the floor — the rolling engine has to summarize, and the facts, which are user
 * messages, are really absorbed — and {@link #keyValue} and {@link #logTriage} fetch results over it, which makes
 * their rolling baseline a prune baseline.
 *
 * <p>
 * Pure: no rig, no provider, no clock. All randomness comes from one {@link SplittableRandom} seeded by the caller.
 *
 * <p>
 * Kept in step with the copy in {@code aimon-llm-anthropic}. What has to stay the same is the input the two providers
 * are given, and {@link #fingerprint} is how the two {@code ContextPressureTasksTest} copies hold it.
 */
final class ContextPressureTasks {

    /** The one system prompt both engines run under. It names no tool: the engines differ in which tools exist. */
    static final String SYSTEM_PROMPT = "You are a terse assistant in a test. Follow the user's formatting instructions"
            + " exactly. Earlier parts of this conversation may no longer be shown to you. When you need something"
            + " that is not shown, use the tools you have to look it up. If you cannot find it, reply UNKNOWN - do not"
            + " guess.";

    /** The tool the pressure phase fetches through; the rig registers it under the same name. */
    static final String REPORT_TOOL = "fetch_report";

    /** The control level: low enough that neither engine should compact. */
    static final double CONTROL_PRESSURE = 0.3;

    /** Below the control level a task has too few units to hold three strata. */
    static final double MIN_PRESSURE = CONTROL_PRESSURE;

    /** A typo must not become thousands of billed calls. */
    static final double MAX_PRESSURE = 8.0;

    /** A needle unit: under the rolling engine's prune floor as a tool message, so it can only be summarized. */
    static final int SMALL_UNIT_CHARS = 1_450;

    /**
     * A key-value page or a log dump: over the prune floor, longer than one {@code SessionHistory} part, and small
     * enough that four of them stay inside the control level's bound.
     */
    static final int LARGE_UNIT_CHARS = 3_850;

    /** The units a 16K window's rolling tail holds verbatim; a question about one of them is {@link Stratum#LATE}. */
    static final int LATE_UNITS = 2;

    static final int NEEDLE_FACTS = 6;
    static final int KEY_VALUE_QUESTIONS = 8;
    static final int LOG_TRIAGE_QUESTIONS = 6;

    static final String BRIEFING = "This is a shift handover exercise. You will be given notes to keep and reports to"
            + " fetch, and asked questions at the end. Reply with only the word: ready.";

    static final String ANSWER_FORMAT = " Reply with the value only. If you cannot find it, reply UNKNOWN.";

    /** The shape of every answer: two letters and a digit, a four-letter word, four digits. Nothing guessable. */
    static final Pattern VALUE_SHAPE = Pattern.compile("(?<![A-Z0-9])[A-Z]{2}[0-9]-[A-Z]{4}-[0-9]{4}(?![0-9])");

    private static final String[] VALUE_WORDS = {"MULE", "WOLF", "CRAB", "DOVE", "HAWK", "LYNX", "MOTH", "NEWT", "ORCA",
            "PUMA", "SWAN", "TOAD", "WASP", "YETI", "BEAR", "CROW", "DEER", "FROG", "GOAT", "HARE", "IBIS", "KIWI",
            "LION", "MINK"};

    private static final String[] ROLES = {"escalation code", "runbook reference", "change ticket", "rota token",
            "audit reference", "badge serial", "failover key", "vendor contract id"};

    private static final String[] THINGS = {"the billing cluster", "the ledger database", "the search tier",
            "the payments gateway", "the archive store", "the metrics pipeline", "the identity service",
            "the build farm"};

    private static final String[] SERVICES = {"billing", "ledger", "search", "payments", "archive", "metrics",
            "identity", "builder", "catalog", "courier", "gateway", "indexer", "journal", "mailer", "notifier",
            "planner", "quoter", "router", "session", "tracker", "uploader", "vault", "webhook", "zipper"};

    private static final String[] ATTRIBUTES = {"timeout", "quota", "owner", "region", "replica", "shard", "token",
            "window", "budget", "cursor", "digest", "epoch"};

    private static final String[] NOISE = {"scheduled job completed", "health probe answered", "cache warmed",
            "connection pool resized", "config reloaded", "snapshot uploaded"};

    private static final String[] FAILURES = {"disk full on /var/data", "replica lag over limit",
            "certificate close to expiry", "queue depth over limit", "checksum mismatch on segment"};

    private ContextPressureTasks() {
    }

    enum TaskKind {
        NEEDLE, KEY_VALUE, LOG_TRIAGE
    }

    /** Where a question's source sits in the pressure phase, by position. */
    enum Stratum {
        /** Among the first units: what compaction reaches first. */
        EARLY,
        /** Between the two. */
        MIDDLE,
        /** In the last {@link #LATE_UNITS} units: still verbatim under rolling when the questions begin. */
        LATE
    }

    enum SourceKind {
        IN_USER_MESSAGE, IN_TOOL_RESULT
    }

    enum Verdict {
        /** The answer holds the expected value and no distractor. */
        CORRECT,
        /** The answer holds some other value. */
        WRONG,
        /** The answer says UNKNOWN, or holds nothing shaped like a value. */
        ABSTAINED,
        /** The question's turn did not complete. */
        FAILED
    }

    // ------------------------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Fact retention: six facts given as user messages right after the briefing, then small reports that have nothing
     * to do with them until the pressure is reached, then one question per fact.
     *
     * <p>
     * Every question is {@link Stratum#EARLY}: the facts are all given before the first report, so at any level over
     * the control all of them are in what the first compaction absorbs. The comparison that isolates what compaction
     * cost is the same questions at the control level.
     */
    static ContextPressureTask needle(long seed, double pressure, int effectiveWindow, TokenEstimator estimator) {
        final Draft draft = new Draft(TaskKind.NEEDLE, seed, pressure, effectiveWindow, estimator);
        final SplittableRandom random = draft.random;
        final Values values = new Values(random);
        final List<String> roles = shuffled(ROLES, random);
        final List<String> things = shuffled(THINGS, random);
        final List<String> expected = new ArrayList<>();
        final List<String> facts = new ArrayList<>();
        for (int n = 0; n < NEEDLE_FACTS; n++) {
            final String value = values.next();
            final String fact = "the " + roles.get(n) + " for " + things.get(n) + " is " + value + ".";
            expected.add(value);
            facts.add(fact);
            draft.say("Note " + (n + 1) + ": " + fact + " Reply with only the word: noted.");
        }
        for (int unit = 1; !draft.reached(); unit++) {
            final String id = "N-" + unit;
            final StringBuilder report = new StringBuilder("Report ").append(id).append(" (operations log excerpt)\n");
            for (int line = 0;; line++) {
                final String entry = "line " + line + ": INFO " + NOISE[random.nextInt(NOISE.length)] + " on host app-"
                        + random.nextInt(40) + " with exit status 0 in " + (100 + random.nextInt(900)) + " ms\n";
                if (report.length() + entry.length() > SMALL_UNIT_CHARS) {
                    break;
                }
                report.append(entry);
            }
            draft.fetch(id, report.toString());
        }
        for (int n = 0; n < NEEDLE_FACTS; n++) {
            final Set<String> distractors = new LinkedHashSet<>(expected);
            distractors.remove(expected.get(n));
            final String lookupKey = roles.get(n) + " for " + things.get(n);
            draft.questions.add(Question.builder().id("Q" + (n + 1))
                    .prompt("What is the " + lookupKey + "?" + ANSWER_FORMAT).expected(expected.get(n))
                    .distractors(distractors).stratum(Stratum.EARLY).sourceKind(SourceKind.IN_USER_MESSAGE)
                    .sourceLine(facts.get(n)).lookupKey(lookupKey).build());
        }
        return draft.build();
    }

    /**
     * Key-value lookup: pages of {@code svc.<name>.<attribute> = <value>} lines fetched one per turn — a page is the
     * pressure unit — and then questions about single keys, stratified by which page the key was on. Each asked key
     * has a neighbour on the next line that differs from it in one character.
     */
    static ContextPressureTask keyValue(long seed, double pressure, int effectiveWindow, TokenEstimator estimator) {
        final Draft draft = new Draft(TaskKind.KEY_VALUE, seed, pressure, effectiveWindow, estimator);
        final SplittableRandom random = draft.random;
        final Values values = new Values(random);
        final Set<String> usedKeys = new HashSet<>();
        final List<String> ids = new ArrayList<>();
        final List<List<String[]>> pages = new ArrayList<>();
        // Sizes first: a page's length does not depend on which of its lines is asked about later, because the
        // neighbour rewrite below keeps every key's length.
        while (!draft.reachedByAccount()) {
            final String id = "KV-" + (ids.size() + 1);
            final List<String[]> lines = new ArrayList<>();
            int length = header(id, "service registry page").length();
            while (length < LARGE_UNIT_CHARS) {
                String key;
                do {
                    key = "svc." + SERVICES[random.nextInt(SERVICES.length)] + "-" + (10 + random.nextInt(90)) + "."
                            + ATTRIBUTES[random.nextInt(ATTRIBUTES.length)];
                } while (!usedKeys.add(key));
                final String[] line = {key, values.next()};
                lines.add(line);
                length += render(line).length();
            }
            ids.add(id);
            pages.add(lines);
            draft.account(id, length);
        }
        final int[] perStratum = {3, 2, 3};
        final List<QuestionDraft> picked = new ArrayList<>();
        for (Stratum stratum : Stratum.values()) {
            final int[] units = unitsOf(stratum, ids.size());
            final Set<Long> taken = new HashSet<>();
            for (int n = 0; n < perStratum[stratum.ordinal()]; n++) {
                int unit;
                int line;
                do {
                    unit = units[0] + random.nextInt(units[1] - units[0]);
                    final int size = pages.get(unit).size() - 1;
                    // The first two questions of a stratum sit at opposite ends of their page, so one of them is past
                    // the first SessionHistory part and one is inside it.
                    final int third = size / 3;
                    line = n == 0
                            ? size - third + random.nextInt(third)
                            : n == 1 ? random.nextInt(third) : random.nextInt(size);
                } while (!taken.add(slot(unit, line)) || taken.contains(slot(unit, line - 1))
                        || taken.contains(slot(unit, line + 1)));
                final String[] asked = pages.get(unit).get(line);
                final String[] neighbour = pages.get(unit).get(line + 1);
                usedKeys.remove(neighbour[0]);
                neighbour[0] = neighbourKey(asked[0], usedKeys, random);
                picked.add(new QuestionDraft(stratum, unit, line));
            }
        }
        final List<String> bodies = new ArrayList<>();
        for (int unit = 0; unit < ids.size(); unit++) {
            final StringBuilder body = new StringBuilder(header(ids.get(unit), "service registry page"));
            pages.get(unit).forEach(line -> body.append(render(line)));
            bodies.add(body.toString());
            draft.fetch(ids.get(unit), body.toString());
        }
        for (QuestionDraft pick : askOrder(picked)) {
            final String[] asked = pages.get(pick.unit).get(pick.line);
            final String[] neighbour = pages.get(pick.unit).get(pick.line + 1);
            final String sourceLine = render(asked).trim();
            draft.questions.add(Question.builder().id("Q" + (draft.questions.size() + 1))
                    .prompt("What is the value of " + asked[0] + "?" + ANSWER_FORMAT).expected(asked[1])
                    .distractors(Set.of(neighbour[1])).stratum(pick.stratum).sourceKind(SourceKind.IN_TOOL_RESULT)
                    .sourceReportId(ids.get(pick.unit)).sourceCharOffset(bodies.get(pick.unit).indexOf(sourceLine))
                    .sourceLine(sourceLine).lookupKey(asked[0]).build());
        }
        return draft.build();
    }

    /**
     * Log triage: service log dumps fetched one per turn — a dump is the pressure unit — each mostly INFO noise with
     * three WARN/ERROR lines carrying an error code, a node and a request id. The questions come in three forms, two
     * per stratum between them: one that names the dump, one that names only the error code and so has to be found by
     * content, and one that names the dump while the same error code sits on another node in another dump.
     */
    static ContextPressureTask logTriage(long seed, double pressure, int effectiveWindow, TokenEstimator estimator) {
        final Draft draft = new Draft(TaskKind.LOG_TRIAGE, seed, pressure, effectiveWindow, estimator);
        final SplittableRandom random = draft.random;
        final Values values = new Values(random);
        final Set<String> usedCodes = new HashSet<>();
        final List<String> ids = new ArrayList<>();
        final List<List<String[]>> dumps = new ArrayList<>();
        final List<List<Integer>> signals = new ArrayList<>();
        while (!draft.reachedByAccount()) {
            final String id = "L-" + (ids.size() + 1);
            final String service = SERVICES[random.nextInt(SERVICES.length)];
            final int hour = random.nextInt(24);
            final String title = "log dump: " + service + ", " + String.format(Locale.ROOT, "%02d", hour) + ":00-"
                    + String.format(Locale.ROOT, "%02d", hour) + ":59";
            // A signal line is a fixed-width record: {prefix, code, node, request, suffix}. Noise is {text}.
            final List<String[]> lines = new ArrayList<>();
            lines.add(new String[]{header(id, title)});
            int length = lines.get(0)[0].length();
            while (length < LARGE_UNIT_CHARS) {
                final String stamp = String.format(Locale.ROOT, "2026-03-14T%02d:%02d:%02dZ ", hour,
                        Math.min(59, lines.size()), random.nextInt(60));
                final String[] line = {
                        stamp + "INFO  svc=" + service + " host=app-" + (10 + random.nextInt(90)) + " msg=\""
                                + NOISE[random.nextInt(NOISE.length)] + "\" ms=" + (100 + random.nextInt(900)) + "\n"};
                lines.add(line);
                length += line[0].length();
            }
            final Set<Integer> places = new TreeSet<>();
            while (places.size() < 3) {
                places.add(1 + random.nextInt(lines.size() - 1));
            }
            for (int place : places) {
                final String stamp = lines.get(place)[0].substring(0, 21);
                String code;
                do {
                    code = "E-" + (1000 + random.nextInt(9000));
                } while (!usedCodes.add(code));
                final String[] signal = {
                        stamp + (random.nextBoolean() ? "ERROR" : "WARN ") + " svc=" + service + " code=", code,
                        values.next(), values.next(), " msg=\"" + FAILURES[random.nextInt(FAILURES.length)] + "\"\n"};
                length += render(signal).length() - lines.get(place)[0].length();
                lines.set(place, signal);
            }
            ids.add(id);
            dumps.add(lines);
            signals.add(new ArrayList<>(places));
            draft.account(id, length);
        }
        // Forms by stratum: 0 names the dump, 1 names only the code, 2 names the dump and has a decoy elsewhere.
        final int[][] forms = {{0, 1}, {2, 0}, {1, 2}};
        final Set<Long> taken = new HashSet<>();
        final List<QuestionDraft> picked = new ArrayList<>();
        for (Stratum stratum : Stratum.values()) {
            final int[] units = unitsOf(stratum, ids.size());
            for (int form : forms[stratum.ordinal()]) {
                int unit;
                int line;
                do {
                    unit = units[0] + random.nextInt(units[1] - units[0]);
                    line = signals.get(unit).get(random.nextInt(3));
                } while (!taken.add(slot(unit, line)));
                picked.add(new QuestionDraft(stratum, unit, line, form));
            }
        }
        final Map<QuestionDraft, String[]> decoys = new LinkedHashMap<>();
        for (QuestionDraft pick : picked) {
            if (pick.form != 2) {
                continue;
            }
            int unit;
            int line;
            do {
                unit = random.nextInt(ids.size());
                line = signals.get(unit).get(random.nextInt(3));
            } while (unit == pick.unit || !taken.add(slot(unit, line)));
            final String[] decoy = dumps.get(unit).get(line);
            usedCodes.remove(decoy[1]);
            decoy[1] = dumps.get(pick.unit).get(pick.line)[1];
            decoys.put(pick, decoy);
        }
        final List<String> bodies = new ArrayList<>();
        for (int unit = 0; unit < ids.size(); unit++) {
            final StringBuilder body = new StringBuilder();
            dumps.get(unit).forEach(line -> body.append(render(line)));
            bodies.add(body.toString());
            draft.fetch(ids.get(unit), body.toString());
        }
        for (QuestionDraft pick : askOrder(picked)) {
            final String[] signal = dumps.get(pick.unit).get(pick.line);
            final String id = ids.get(pick.unit);
            final String sourceLine = render(signal).trim();
            final Set<String> distractors = new LinkedHashSet<>();
            final String prompt;
            final String expected;
            if (pick.form == 1) {
                prompt = "Which request id is on the log line with error code " + signal[1] + "?";
                expected = signal[3];
                distractors.add(signal[2]);
            } else {
                prompt = "In dump " + id + ", which node logged error code " + signal[1] + "?";
                expected = signal[2];
                distractors.add(signal[3]);
            }
            if (decoys.containsKey(pick)) {
                distractors.add(decoys.get(pick)[2]);
                distractors.add(decoys.get(pick)[3]);
            }
            draft.questions.add(Question.builder().id("Q" + (draft.questions.size() + 1)).prompt(prompt + ANSWER_FORMAT)
                    .expected(expected).distractors(distractors).stratum(pick.stratum)
                    .sourceKind(SourceKind.IN_TOOL_RESULT).sourceReportId(id)
                    .sourceCharOffset(bodies.get(pick.unit).indexOf(sourceLine)).sourceLine(sourceLine)
                    .lookupKey(pick.form == 1 ? "code=" + signal[1] : "Report " + id + " (").build());
        }
        return draft.build();
    }

    /** The task of {@code kind}; the three generators share one signature. */
    static ContextPressureTask generate(TaskKind kind, long seed, double pressure, int effectiveWindow,
            TokenEstimator estimator) {
        return switch (kind) {
            case NEEDLE -> needle(seed, pressure, effectiveWindow, estimator);
            case KEY_VALUE -> keyValue(seed, pressure, effectiveWindow, estimator);
            case LOG_TRIAGE -> logTriage(seed, pressure, effectiveWindow, estimator);
        };
    }

    static String fetchInstruction(String reportId) {
        return "Call the " + REPORT_TOOL + " tool with report_id \"" + reportId
                + "\". After reading it, reply with only the word: received.";
    }

    /** What {@code body} costs the view as a tool message, by the estimate the engines prune and compact on. */
    static int toolResultTokens(TokenEstimator estimator, String body) {
        return estimator.estimateMessage(Message.toolUseResults(List.of(ToolUseResult.success("fetch", body))));
    }

    static int userInputTokens(TokenEstimator estimator, String input) {
        return estimator.estimateMessage(Message.user(input));
    }

    private static String header(String id, String title) {
        return "Report " + id + " (" + title + ")\n";
    }

    private static String render(String[] line) {
        if (line.length == 1) {
            return line[0];
        }
        if (line.length == 2) {
            return line[0] + " = " + line[1] + "\n";
        }
        return line[0] + line[1] + " node=" + line[2] + " req=" + line[3] + line[4];
    }

    /** The key that differs from {@code key} in one digit of its service number, and is not in {@code used}. */
    private static String neighbourKey(String key, Set<String> used, SplittableRandom random) {
        final int digit = key.indexOf('.', "svc.".length()) - 1;
        final int start = random.nextInt(10);
        for (int n = 0; n < 10; n++) {
            final char replacement = (char) ('0' + (start + n) % 10);
            final String candidate = key.substring(0, digit) + replacement + key.substring(digit + 1);
            if (replacement != key.charAt(digit) && used.add(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no free neighbour for " + key);
    }

    /** The half-open range of unit indexes in {@code stratum}, of {@code units} units. */
    private static int[] unitsOf(Stratum stratum, int units) {
        final int late = units - LATE_UNITS;
        final int early = (late + 1) / 2;
        if (early < 1 || late - early < 1) {
            throw new IllegalStateException(units + " units cannot hold three strata");
        }
        return switch (stratum) {
            case EARLY -> new int[]{0, early};
            case MIDDLE -> new int[]{early, late};
            case LATE -> new int[]{late, units};
        };
    }

    /**
     * Late first, early last. Under rolling the first lookup's result is unread and larger than the tail, so the
     * prune region grows to everything before it; asked in any other order, a {@code LATE} question would be asked
     * against a result that was verbatim only until an earlier question's lookup.
     */
    private static List<QuestionDraft> askOrder(List<QuestionDraft> picked) {
        final List<QuestionDraft> ordered = new ArrayList<>(picked);
        ordered.sort((a, b) -> b.stratum.compareTo(a.stratum));
        return ordered;
    }

    private static long slot(int unit, int line) {
        return ((long) unit << 32) | (line & 0xffffffffL);
    }

    private static List<String> shuffled(String[] source, SplittableRandom random) {
        final List<String> list = new ArrayList<>(List.of(source));
        for (int i = list.size() - 1; i > 0; i--) {
            Collections.swap(list, i, random.nextInt(i + 1));
        }
        return list;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Scoring and fingerprint
    // ------------------------------------------------------------------------------------------------------------

    /** Scores one answer by string comparison; no judge model. */
    static Verdict verdict(Question question, String finalAnswer, boolean turnSucceeded) {
        Objects.requireNonNull(question, "question cannot be null");
        if (!turnSucceeded) {
            return Verdict.FAILED;
        }
        final String answer = normalize(finalAnswer);
        if (answer.contains(normalize(question.expected()))
                && question.distractors().stream().noneMatch(distractor -> answer.contains(normalize(distractor)))) {
            return Verdict.CORRECT;
        }
        final String upper = finalAnswer == null ? "" : finalAnswer.toUpperCase(Locale.ROOT);
        if (upper.contains("UNKNOWN") || !VALUE_SHAPE.matcher(upper).find()) {
            return Verdict.ABSTAINED;
        }
        return Verdict.WRONG;
    }

    /** Upper case, with whitespace, backticks, quotes and trailing full stops removed. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toUpperCase(Locale.ROOT).replaceAll("[\\s`'\"]", "").replaceAll("\\.+$", "");
    }

    /**
     * A SHA-256 over everything a provider is given: the system prompt, the pressure phase's inputs, the reports in id
     * order, and each question's prompt, expected value and distractors.
     */
    static String fingerprint(ContextPressureTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        final List<String> parts = new ArrayList<>();
        parts.add(task.systemPrompt());
        task.steps().forEach(step -> parts.add(step.input()));
        new TreeMap<>(task.reports()).forEach((id, body) -> {
            parts.add(id);
            parts.add(body);
        });
        for (Question question : task.questions()) {
            parts.add(question.prompt());
            parts.add(question.expected());
            parts.addAll(new TreeSet<>(question.distractors()));
        }
        for (String part : parts) {
            digest.update(part.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Value types
    // ------------------------------------------------------------------------------------------------------------

    /** One task, the same instance for both engines. Immutable. */
    static final class ContextPressureTask {

        private final TaskKind kind;
        private final long seed;
        private final double pressure;
        private final String systemPrompt;
        private final Map<String, String> reports;
        private final List<Step> steps;
        private final List<Question> questions;
        private final int plannedTokens;
        private final int unitTokens;

        private ContextPressureTask(Draft draft) {
            this.kind = draft.kind;
            this.seed = draft.seed;
            this.pressure = draft.pressure;
            this.systemPrompt = SYSTEM_PROMPT;
            this.reports = Collections.unmodifiableMap(new LinkedHashMap<>(draft.reports));
            this.steps = List.copyOf(draft.steps);
            this.questions = List.copyOf(draft.questions);
            this.plannedTokens = draft.planned;
            this.unitTokens = draft.unitTokens;
        }

        TaskKind kind() {
            return kind;
        }

        long seed() {
            return seed;
        }

        /** The level asked for. */
        double pressure() {
            return pressure;
        }

        String systemPrompt() {
            return systemPrompt;
        }

        /** What {@code fetch_report} returns, by id, in the order the pressure phase fetches them. */
        Map<String, String> reports() {
            return reports;
        }

        /** The pressure phase's user inputs, the briefing first. */
        List<Step> steps() {
            return steps;
        }

        /** In the order they are asked. */
        List<Question> questions() {
            return questions;
        }

        /** The pressure phase's estimated tokens: its user inputs and the tool results they fetch. */
        int plannedTokens() {
            return plannedTokens;
        }

        /** The largest pressure unit as a tool message, in estimated tokens. */
        int unitTokens() {
            return unitTokens;
        }
    }

    /** One user input of the pressure phase, and the report it asks for when it asks for one. Immutable. */
    static final class Step {

        private final String input;
        private final String fetchedReportId;

        private Step(String input, String fetchedReportId) {
            this.input = Objects.requireNonNull(input, "input cannot be null");
            this.fetchedReportId = fetchedReportId;
        }

        static Step saying(String input) {
            return new Step(input, null);
        }

        static Step fetching(String reportId) {
            return new Step(fetchInstruction(reportId), Objects.requireNonNull(reportId, "reportId cannot be null"));
        }

        String input() {
            return input;
        }

        Optional<String> fetchedReportId() {
            return Optional.ofNullable(fetchedReportId);
        }
    }

    /** One question, with what scores it and where its answer was. Immutable. */
    static final class Question {

        private final String id;
        private final String prompt;
        private final String expected;
        private final Set<String> distractors;
        private final Stratum stratum;
        private final SourceKind sourceKind;
        private final String sourceReportId;
        private final int sourceCharOffset;
        private final String sourceLine;
        private final String lookupKey;

        private Question(Builder builder) {
            this.id = Objects.requireNonNull(builder.id, "id cannot be null");
            this.prompt = Objects.requireNonNull(builder.prompt, "prompt cannot be null");
            this.expected = Objects.requireNonNull(builder.expected, "expected cannot be null");
            this.distractors = Collections.unmodifiableSet(new LinkedHashSet<>(builder.distractors));
            this.stratum = Objects.requireNonNull(builder.stratum, "stratum cannot be null");
            this.sourceKind = Objects.requireNonNull(builder.sourceKind, "sourceKind cannot be null");
            this.sourceReportId = builder.sourceReportId;
            this.sourceCharOffset = builder.sourceCharOffset;
            this.sourceLine = Objects.requireNonNull(builder.sourceLine, "sourceLine cannot be null");
            this.lookupKey = Objects.requireNonNull(builder.lookupKey, "lookupKey cannot be null");
        }

        static Builder builder() {
            return new Builder();
        }

        String id() {
            return id;
        }

        String prompt() {
            return prompt;
        }

        String expected() {
            return expected;
        }

        /** Other values of the same shape; an answer holding one of them is not correct. */
        Set<String> distractors() {
            return distractors;
        }

        Stratum stratum() {
            return stratum;
        }

        SourceKind sourceKind() {
            return sourceKind;
        }

        /** The report the answer was in; empty when it was given in a user message. */
        Optional<String> sourceReportId() {
            return Optional.ofNullable(sourceReportId);
        }

        /** Where in its report the answer's line starts; 0 for a user message. */
        int sourceCharOffset() {
            return sourceCharOffset;
        }

        /** The text that carries the answer, exactly as it was given. */
        String sourceLine() {
            return sourceLine;
        }

        /** Text that finds the source by a substring search, and that the prompt gives away. */
        String lookupKey() {
            return lookupKey;
        }

        static final class Builder {

            private String id;
            private String prompt;
            private String expected;
            private Set<String> distractors = Set.of();
            private Stratum stratum;
            private SourceKind sourceKind;
            private String sourceReportId;
            private int sourceCharOffset;
            private String sourceLine;
            private String lookupKey;

            Builder id(String id) {
                this.id = id;
                return this;
            }

            Builder prompt(String prompt) {
                this.prompt = prompt;
                return this;
            }

            Builder expected(String expected) {
                this.expected = expected;
                return this;
            }

            Builder distractors(Set<String> distractors) {
                this.distractors = Objects.requireNonNull(distractors, "distractors cannot be null");
                return this;
            }

            Builder stratum(Stratum stratum) {
                this.stratum = stratum;
                return this;
            }

            Builder sourceKind(SourceKind sourceKind) {
                this.sourceKind = sourceKind;
                return this;
            }

            Builder sourceReportId(String sourceReportId) {
                this.sourceReportId = sourceReportId;
                return this;
            }

            Builder sourceCharOffset(int sourceCharOffset) {
                this.sourceCharOffset = sourceCharOffset;
                return this;
            }

            Builder sourceLine(String sourceLine) {
                this.sourceLine = sourceLine;
                return this;
            }

            Builder lookupKey(String lookupKey) {
                this.lookupKey = lookupKey;
                return this;
            }

            Question build() {
                return new Question(this);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Generation scratch
    // ------------------------------------------------------------------------------------------------------------

    /** A task being generated: the running token count, and what {@link ContextPressureTask} is built from. */
    private static final class Draft {

        private final TaskKind kind;
        private final long seed;
        private final double pressure;
        private final double target;
        private final TokenEstimator estimator;
        private final SplittableRandom random;
        private final Map<String, String> reports = new LinkedHashMap<>();
        private final List<Step> steps = new ArrayList<>();
        private final List<Question> questions = new ArrayList<>();
        /** Tokens counted for units whose bodies are not rendered yet. */
        private int accounted;
        private int planned;
        private int unitTokens;

        Draft(TaskKind kind, long seed, double pressure, int effectiveWindow, TokenEstimator estimator) {
            if (!(pressure >= MIN_PRESSURE && pressure <= MAX_PRESSURE)) {
                throw new IllegalArgumentException(
                        "pressure must be in [" + MIN_PRESSURE + ", " + MAX_PRESSURE + "], got: " + pressure);
            }
            if (effectiveWindow < 1) {
                throw new IllegalArgumentException("effectiveWindow must be >= 1, got: " + effectiveWindow);
            }
            this.kind = kind;
            this.seed = seed;
            this.pressure = pressure;
            this.target = pressure * effectiveWindow;
            this.estimator = Objects.requireNonNull(estimator, "estimator cannot be null");
            this.random = new SplittableRandom(seed);
            say(BRIEFING);
        }

        void say(String input) {
            steps.add(Step.saying(input));
            planned += userInputTokens(estimator, input);
        }

        void fetch(String reportId, String body) {
            final Step step = Step.fetching(reportId);
            final int tokens = toolResultTokens(estimator, body);
            reports.put(reportId, body);
            steps.add(step);
            planned += userInputTokens(estimator, step.input()) + tokens;
            unitTokens = Math.max(unitTokens, tokens);
        }

        boolean reached() {
            return planned >= target;
        }

        /** Whether the briefing and the units accounted so far reach the target, for a task that renders later. */
        boolean reachedByAccount() {
            return planned + accounted >= target;
        }

        /** Counts a unit of {@code chars} ASCII characters whose body is rendered after the questions are picked. */
        void account(String reportId, int chars) {
            accounted += userInputTokens(estimator, fetchInstruction(reportId))
                    + toolResultTokens(estimator, "x".repeat(chars));
        }

        ContextPressureTask build() {
            return new ContextPressureTask(this);
        }
    }

    /** A question picked by position, before the bodies are rendered. */
    private static final class QuestionDraft {

        private final Stratum stratum;
        private final int unit;
        private final int line;
        private final int form;

        QuestionDraft(Stratum stratum, int unit, int line) {
            this(stratum, unit, line, 0);
        }

        QuestionDraft(Stratum stratum, int unit, int line, int form) {
            this.stratum = stratum;
            this.unit = unit;
            this.line = line;
            this.form = form;
        }
    }

    /** Draws values of {@link #VALUE_SHAPE}, never the same one twice. */
    private static final class Values {

        private final SplittableRandom random;
        private final Set<String> used = new HashSet<>();

        Values(SplittableRandom random) {
            this.random = random;
        }

        String next() {
            while (true) {
                final String value = "" + (char) ('A' + random.nextInt(26)) + (char) ('A' + random.nextInt(26))
                        + random.nextInt(10) + "-" + VALUE_WORDS[random.nextInt(VALUE_WORDS.length)] + "-"
                        + (1000 + random.nextInt(9000));
                if (used.add(value)) {
                    return value;
                }
            }
        }
    }
}
