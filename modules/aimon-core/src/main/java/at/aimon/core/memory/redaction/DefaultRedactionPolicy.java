package at.aimon.core.memory.redaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link RedactionPolicy} implementation that masks the five categories
 * defined in design doc §6.5: AWS access keys, Bearer/JWT tokens, RFC1918
 * private IPv4 addresses, e-mail addresses, and {@code key=value} secret pairs.
 *
 * <p>
 * Each category is applied as a sequential pass with {@link Matcher#find()};
 * the substring matched in the current working text is replaced with a literal
 * marker token of the form {@code [REDACTED:CATEGORY]}. The replacement tokens
 * are crafted so that none of the patterns match against an already-redacted
 * segment, which makes {@link #redact(String)} idempotent: applying it to its
 * own output yields the same {@link RedactionResult#getRedactedContent()
 * redactedContent} (with no further matches).
 *
 * <p>
 * Stateless and thread-safe — all {@link Pattern} instances are compiled once
 * as {@code static final}.
 */
public final class DefaultRedactionPolicy implements RedactionPolicy {

    /** AWS access key category name. */
    public static final String CATEGORY_AWS_KEY = "AWS_KEY";
    /** Bearer/JWT token category name. */
    public static final String CATEGORY_JWT = "JWT";
    /** RFC1918 private IPv4 category name. */
    public static final String CATEGORY_PRIVATE_IP = "PRIVATE_IP";
    /** E-mail address category name. */
    public static final String CATEGORY_EMAIL = "EMAIL";
    /** Generic key=value secret category name. */
    public static final String CATEGORY_SECRET = "SECRET";

    private static final Logger log = LoggerFactory.getLogger(DefaultRedactionPolicy.class);

    private static final Pattern AWS_KEY_PATTERN = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern JWT_PATTERN = Pattern
            .compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern PRIVATE_IP_PATTERN = Pattern.compile("\\b(?:10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
            + "|192\\.168\\.\\d{1,3}\\.\\d{1,3}" + "|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})\\b");
    // ReDoS-hardened e-mail pattern. The older `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}` form
    // was O(n^2) at this mandatory security gate: the local-part class includes '.', so find() rescanned
    // long dotted runs from every start position. Here every quantifier is BOUNDED (RFC-ish limits) and
    // POSSESSIVE, and the domain is modelled as `(label.)+ tld` with a label class that excludes '.', so
    // there is no overlapping-quantifier ambiguity and per-position work is constant → linear overall.
    private static final Pattern EMAIL_PATTERN = Pattern
            .compile("\\b[A-Za-z0-9._%+-]{1,64}+@(?:[A-Za-z0-9-]{1,63}+\\.)++[A-Za-z]{2,24}+\\b");
    private static final Pattern SECRET_PATTERN = Pattern
            .compile("(?i)\\b(?:api[_-]?key|password|token)\\s*[:=]\\s*\\S+");

    private static final List<PatternRule> RULES = List.of(
            new PatternRule(CATEGORY_AWS_KEY, AWS_KEY_PATTERN, "[REDACTED:AWS_KEY]"),
            new PatternRule(CATEGORY_JWT, JWT_PATTERN, "[REDACTED:JWT]"),
            new PatternRule(CATEGORY_SECRET, SECRET_PATTERN, "[REDACTED:SECRET]"),
            new PatternRule(CATEGORY_EMAIL, EMAIL_PATTERN, "[REDACTED:EMAIL]"),
            new PatternRule(CATEGORY_PRIVATE_IP, PRIVATE_IP_PATTERN, "[REDACTED:PRIVATE_IP]"));

    /**
     * Creates a new instance. The constructor is intentionally cheap — all
     * compiled patterns are static and shared.
     */
    public DefaultRedactionPolicy() {
        // no-op
    }

    @Override
    public RedactionResult redact(String content) {
        Objects.requireNonNull(content, "content cannot be null");
        if (content.isEmpty()) {
            return RedactionResult.unchanged(content);
        }

        String current = content;
        List<RedactionMatch> matches = new ArrayList<>();

        for (PatternRule rule : RULES) {
            current = applyRule(current, rule, matches);
        }

        if (matches.isEmpty()) {
            return RedactionResult.unchanged(content);
        }
        log.debug("Redacted {} matches across {} categories", matches.size(),
                matches.stream().map(RedactionMatch::getPattern).distinct().count());
        return RedactionResult.of(current, Collections.unmodifiableList(matches));
    }

    private static String applyRule(String input, PatternRule rule, List<RedactionMatch> matches) {
        Matcher matcher = rule.pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length());
        int cursor = 0;
        do {
            int start = matcher.start();
            int end = matcher.end();
            out.append(input, cursor, start);
            out.append(rule.replacement);
            matches.add(RedactionMatch.of(rule.category, start, end, rule.replacement));
            cursor = end;
        } while (matcher.find());
        out.append(input, cursor, input.length());
        return out.toString();
    }

    private static final class PatternRule {
        final String category;
        final Pattern pattern;
        final String replacement;

        PatternRule(String category, Pattern pattern, String replacement) {
            this.category = category;
            this.pattern = pattern;
            this.replacement = replacement;
        }
    }
}
