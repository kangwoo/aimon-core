package at.aimon.core.memory.redaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stricter {@link RedactionPolicy} that augments {@link DefaultRedactionPolicy}
 * with a fuzzy keyword pass: tokens whose Levenshtein distance to a known
 * secret keyword is at most {@value #MAX_DISTANCE} (e.g. {@code passwrd},
 * {@code apikkey}) and that are immediately followed by {@code :} or {@code =}
 * are masked as {@link DefaultRedactionPolicy#CATEGORY_SECRET SECRET} before
 * the default rules run.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class StrictRedactionPolicy implements RedactionPolicy {

    private static final Logger log = LoggerFactory.getLogger(StrictRedactionPolicy.class);

    private static final int MAX_DISTANCE = 1;
    private static final int MAX_TOKEN_LENGTH = 64;
    private static final String SECRET_REPLACEMENT = "[REDACTED:SECRET]";

    private static final Set<String> KEYWORDS = Set.of("password", "passwd", "secret", "apikey", "api_key", "token",
            "access_key", "private_key");

    /**
     * Matches a token (letters/digits/underscore) followed by optional
     * whitespace, then {@code :} or {@code =}, then optional whitespace, then
     * a non-whitespace value.
     */
    private static final Pattern FUZZY_CANDIDATE = Pattern
            .compile("\\b([A-Za-z][A-Za-z0-9_]{2,63})\\s*([:=])\\s*(\\S+)");

    private final DefaultRedactionPolicy delegate;

    /**
     * Creates a new strict policy with a fresh {@link DefaultRedactionPolicy}
     * delegate.
     */
    public StrictRedactionPolicy() {
        this.delegate = new DefaultRedactionPolicy();
    }

    @Override
    public RedactionResult redact(String content) {
        Objects.requireNonNull(content, "content cannot be null");
        if (content.isEmpty()) {
            return RedactionResult.unchanged(content);
        }

        List<RedactionMatch> fuzzyMatches = new ArrayList<>();
        String afterFuzzy = applyFuzzyPass(content, fuzzyMatches);

        RedactionResult delegateResult = delegate.redact(afterFuzzy);

        if (fuzzyMatches.isEmpty()) {
            return delegateResult;
        }

        List<RedactionMatch> all = new ArrayList<>(fuzzyMatches.size() + delegateResult.getMatches().size());
        all.addAll(fuzzyMatches);
        all.addAll(delegateResult.getMatches());
        log.debug("StrictRedactionPolicy applied {} fuzzy + {} default matches", fuzzyMatches.size(),
                delegateResult.getMatches().size());
        return RedactionResult.of(delegateResult.getRedactedContent(), all);
    }

    private static String applyFuzzyPass(String input, List<RedactionMatch> matches) {
        Matcher matcher = FUZZY_CANDIDATE.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length());
        int cursor = 0;
        do {
            String token = matcher.group(1);
            if (!isFuzzyKeyword(token)) {
                continue;
            }
            int start = matcher.start();
            int end = matcher.end();
            out.append(input, cursor, start);
            out.append(SECRET_REPLACEMENT);
            matches.add(RedactionMatch.of(DefaultRedactionPolicy.CATEGORY_SECRET, start, end, SECRET_REPLACEMENT));
            cursor = end;
        } while (matcher.find());
        out.append(input, cursor, input.length());
        return out.toString();
    }

    private static boolean isFuzzyKeyword(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        String lower = token.toLowerCase();
        // Exact match is handled by DefaultRedactionPolicy SECRET regex; only
        // claim near-misses here so we strictly add value over the default.
        if (KEYWORDS.contains(lower)) {
            return false;
        }
        for (String kw : KEYWORDS) {
            if (Math.abs(kw.length() - lower.length()) > MAX_DISTANCE) {
                continue;
            }
            if (levenshtein(lower, kw, MAX_DISTANCE) <= MAX_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bounded Levenshtein distance — returns {@code threshold + 1} as soon as
     * the running minimum exceeds the threshold. Inputs are clamped to
     * {@value #MAX_TOKEN_LENGTH} characters by the caller.
     */
    private static int levenshtein(String a, String b, int threshold) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > threshold) {
            return threshold + 1;
        }
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (curr[j] < rowMin) {
                    rowMin = curr[j];
                }
            }
            if (rowMin > threshold) {
                return threshold + 1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lb];
    }
}
