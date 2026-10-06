package at.aimon.core.base.text;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Finds the mapping keys a YAML document writes more than once.
 *
 * <p>
 * snakeyaml accepts a key written twice and keeps the last value; the earlier one is gone before the caller sees a
 * map, so nothing downstream can report it. This class reads the same text a second time as a node tree, where both
 * keys are still present, and answers with their paths. It reports and nothing else: the caller's own
 * {@code Yaml.load} is untouched, so what a document parses to does not change, and a caller that turns the answer
 * into a log line has made the loss visible without turning it into a failure.
 *
 * <p>
 * That is the reason this is not {@code LoaderOptions.setAllowDuplicateKeys(false)}. The front-matter parsers keep
 * their {@code LoaderOptions} at the defaults because tightening one would change what existing agent, subagent and
 * skill files parse to — a definition that loads today would stop loading.
 *
 * <p>
 * Two keys are the same key when both are scalars and snakeyaml constructs equal objects for them — that is what
 * makes the second {@code put} replace the first, so it is asked rather than guessed: each scalar key is constructed
 * with the same {@code SafeConstructor} the parsers use, and the results are compared. So {@code name} and
 * {@code "name"} are one key, and so are {@code yes}, {@code true} and {@code True}; {@code ~} and {@code null};
 * {@code 1} and {@code 0x1}; two spellings of one timestamp. {@code 1} and {@code "1"} are two keys, and so are
 * {@code 1} and {@code 1.0} (an {@code Integer} and a {@code Double}) and two {@code !!binary} keys with the same
 * bytes (arrays are equal only to themselves) — snakeyaml keeps each of those pairs apart. When two spellings are one
 * key, the path reported is the one written last, the one whose value survives. A key snakeyaml cannot construct (a
 * tag it has no constructor for) is compared by tag and text instead; the caller's own parse is what reports it. A
 * key that is itself a mapping or a sequence is not compared. A merge key ({@code <<}) is skipped — an explicit key
 * overriding a merged one is what a merge is for.
 *
 * <p>
 * Constructing a key builds a {@code String}, a number, a {@code Boolean}, a date or a byte array from that one
 * scalar, on this class's own reading of the text. It runs nothing from the document, touches nothing the caller
 * parsed, and no value of the document — only key names — ever leaves this class.
 */
public final class YamlDuplicateKeys {

    private YamlDuplicateKeys() {
    }

    /**
     * Returns the path of every key written more than once in one mapping, in document order.
     *
     * <p>
     * A path is dotted, with {@code []} for a sequence: {@code model.temperature}, {@code steps[].name}. A key
     * written three times is listed once.
     *
     * <p>
     * Never throws. Text that is not one well-formed YAML document yields an empty list — the caller's own parse of
     * the same text is what reports that.
     *
     * @param yaml
     *            the YAML text, may be null
     * @return the duplicated key paths (never null, unmodifiable)
     */
    public static List<String> find(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return List.of();
        }
        try {
            // Same constructor and options as the parsers that call this, so the two readings agree on tags.
            final Node root = new Yaml(new SafeConstructor(new LoaderOptions())).compose(new StringReader(yaml));
            if (root == null) {
                return List.of();
            }
            final Set<String> found = new LinkedHashSet<>();
            collect(root, "", found, new HashSet<>(), new KeyConstructor());
            return List.copyOf(found);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Words the finding of {@link #find(String)} as one log line, or nothing when there is no finding.
     *
     * <p>
     * One wording for every front-matter surface, so an operator who has met the line once recognises it on the next
     * surface: which document, which keys, and what happened to the value written first.
     *
     * @param document
     *            what was read, as the reader would name it — e.g. {@code Agent definition 'coder'} (must not be
     *            null)
     * @param duplicated
     *            the key paths {@link #find(String)} returned (must not be null)
     * @return the message, or an empty string when {@code duplicated} is empty
     */
    public static String describe(String document, List<String> duplicated) {
        Objects.requireNonNull(document, "Document cannot be null");
        Objects.requireNonNull(duplicated, "Duplicated keys cannot be null");
        if (duplicated.isEmpty()) {
            return "";
        }
        final StringBuilder keys = new StringBuilder();
        for (String key : duplicated) {
            keys.append(keys.length() == 0 ? "`" : ", `").append(key).append('`');
        }
        return document + " front matter writes " + (duplicated.size() == 1 ? "the key " : "the keys ") + keys
                + " more than once; the earlier value is discarded and the last one is used.";
    }

    private static void collect(Node node, String path, Set<String> found, Set<Node> visited,
            KeyConstructor constructor) {
        // An alias makes the tree a graph, and a recursive one makes it cyclic.
        if (!visited.add(node)) {
            return;
        }
        if (node instanceof MappingNode mapping) {
            final Set<Object> seen = new HashSet<>();
            final List<NodeTuple> tuples = new ArrayList<>(mapping.getValue());
            for (NodeTuple tuple : tuples) {
                final Node keyNode = tuple.getKeyNode();
                String childPath = path;
                if (keyNode instanceof ScalarNode key && !Tag.MERGE.equals(key.getTag())) {
                    childPath = path.isEmpty() ? key.getValue() : path + "." + key.getValue();
                    if (!seen.add(constructor.identityOf(key))) {
                        found.add(childPath);
                    }
                }
                collect(tuple.getValueNode(), childPath, found, visited, constructor);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                collect(item, path + "[]", found, visited, constructor);
            }
        }
    }

    /**
     * Answers what a scalar key is to the map snakeyaml builds: the object it constructs for it. Two keys replace one
     * another there exactly when those objects are equal, so they are what gets compared.
     */
    private static final class KeyConstructor extends SafeConstructor {

        /** Stands for the key snakeyaml constructs as {@code null}: a {@code HashSet} of identities cannot hold it. */
        private static final Object NULL_KEY = new Object();

        KeyConstructor() {
            // The options the parsers construct with, so a key is here what it is to them.
            super(new LoaderOptions());
        }

        Object identityOf(ScalarNode key) {
            try {
                final Object constructed = constructObject(key);
                return constructed == null ? NULL_KEY : constructed;
            } catch (RuntimeException e) {
                // No constructor for the tag, or text the tag's constructor refuses. The caller's parse fails on the
                // same key and says so; here the two spellings are the same key only if they are the same text.
                return List.of(key.getTag().getValue(), key.getValue());
            }
        }
    }
}
