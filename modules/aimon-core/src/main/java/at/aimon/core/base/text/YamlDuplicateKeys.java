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
 * Two keys are the same key when both are scalars with the same resolved tag and the same text, which is exactly
 * when snakeyaml constructs equal objects for them: {@code name} and {@code "name"} are one key, {@code 1} and
 * {@code "1"} are two. Two spellings of one number ({@code 1} and {@code 0x1}) also collapse in snakeyaml and are not
 * reported here; a key that is itself a mapping or a sequence is not compared. A merge key ({@code <<}) is skipped —
 * an explicit key overriding a merged one is what a merge is for.
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
            collect(root, "", found, new HashSet<>());
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

    private static void collect(Node node, String path, Set<String> found, Set<Node> visited) {
        // An alias makes the tree a graph, and a recursive one makes it cyclic.
        if (!visited.add(node)) {
            return;
        }
        if (node instanceof MappingNode mapping) {
            final Set<String> seen = new HashSet<>();
            final List<NodeTuple> tuples = new ArrayList<>(mapping.getValue());
            for (NodeTuple tuple : tuples) {
                final Node keyNode = tuple.getKeyNode();
                String childPath = path;
                if (keyNode instanceof ScalarNode key && !Tag.MERGE.equals(key.getTag())) {
                    childPath = path.isEmpty() ? key.getValue() : path + "." + key.getValue();
                    if (!seen.add(key.getTag().getValue() + '\u0000' + key.getValue())) {
                        found.add(childPath);
                    }
                }
                collect(tuple.getValueNode(), childPath, found, visited);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                collect(item, path + "[]", found, visited);
            }
        }
    }
}
