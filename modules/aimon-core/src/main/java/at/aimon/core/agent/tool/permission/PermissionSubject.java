package at.aimon.core.agent.tool.permission;

import java.util.Objects;

/**
 * The single value a tool invocation is judged on, together with the <b>kind</b> of value it is.
 *
 * <p>
 * A permission spec such as {@code Bash(git:*)} or {@code Read(/tmp/**)} restricts a tool by comparing its pattern
 * against one string pulled out of the call. Which string that is depends on the tool — {@code Bash} is judged on its
 * {@code command}, {@code Read} on its {@code file_path} — so the tool is the only party that can supply it. It does so
 * by implementing {@link ToolPermissionSubjectAware}.
 *
 * <h2>Why the kind travels with the value</h2>
 *
 * <p>
 * The kind is not decoration: it selects the matcher. {@link ToolPattern} is a <b>command</b> matcher — it treats a
 * trailing {@code :*} as the wildcard marker and rejects any candidate containing shell metacharacters, which is
 * correct for a string headed for {@code bash -c} and wrong for a filesystem path (a legitimate file may be named
 * {@code report(1).csv}). Paths are matched by {@link PathPattern} instead, using glob syntax.
 *
 * <p>
 * The kind cannot be recovered from the spec string. {@code AllowedTool}'s parser never looks at {@code :} — it splits
 * on parentheses only — so {@code Read(/tmp/**)} and {@code Bash(git:*)} are indistinguishable to it. The kind must
 * come from the tool, which is why it is carried here rather than inferred later.
 *
 * <h2>Two kinds, and why there is no third</h2>
 *
 * <p>
 * {@code Browser}'s {@code action:url} grammar is deliberately <b>not</b> a kind. Its {@code :} is a real structural
 * separator rather than a wildcard convention, and it is one tool's private syntax; {@code BrowserTool} keeps its
 * {@link CustomToolPermissionRule} and judges itself. Adding a third member here would make a single tool's spelling
 * part of the framework vocabulary.
 *
 * <p>
 * Immutable and thread-safe value object.
 *
 * @see ToolPermissionSubjectAware
 * @see PathPattern
 * @see ToolPattern
 */
public final class PermissionSubject {

    /**
     * What sort of value the subject is, and therefore which matcher judges it.
     */
    public enum Kind {

        /**
         * A shell command line, matched by {@link ToolPattern} ({@code prefix:*} or exact, shell metacharacters
         * rejected).
         */
        COMMAND,

        /**
         * A filesystem path, matched by {@link PathPattern} (glob, no metacharacter rejection).
         *
         * <p>
         * Producers must hand over an <b>absolute, lexically normalized</b> path — a pattern author writes
         * {@code Read(/tmp/**)} and cannot be expected to also anticipate {@code ./tmp/../tmp/x}.
         */
        PATH
    }

    private final Kind kind;

    private final String value;

    private PermissionSubject(Kind kind, String value) {
        this.kind = Objects.requireNonNull(kind, "Kind cannot be null");
        this.value = Objects.requireNonNull(value, "Value cannot be null");
    }

    /**
     * Creates a {@link Kind#COMMAND} subject.
     *
     * @param command
     *            The command line as the caller wrote it (must not be null)
     * @return A new subject
     */
    public static PermissionSubject command(String command) {
        return new PermissionSubject(Kind.COMMAND, command);
    }

    /**
     * Creates a {@link Kind#PATH} subject.
     *
     * @param absolutePath
     *            An absolute, lexically normalized path (must not be null)
     * @return A new subject
     */
    public static PermissionSubject path(String absolutePath) {
        return new PermissionSubject(Kind.PATH, absolutePath);
    }

    /**
     * Returns the kind of this subject.
     *
     * @return The kind (never null)
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the value being judged.
     *
     * @return The value (never null)
     */
    public String getValue() {
        return value;
    }

    /**
     * Renders this subject for a denial message, e.g. {@code "command: rm -rf /"} or {@code "path: /etc/passwd"}.
     *
     * @return A human-readable description (never null)
     */
    public String describe() {
        return switch (kind) {
            case COMMAND -> "command: " + value;
            case PATH -> "path: " + value;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PermissionSubject that = (PermissionSubject) o;
        return kind == that.kind && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, value);
    }

    @Override
    public String toString() {
        return "PermissionSubject{" + describe() + "}";
    }
}
