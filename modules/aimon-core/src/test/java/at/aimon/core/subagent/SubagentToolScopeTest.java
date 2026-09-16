package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.DefaultToolPermissionValidator;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
import at.aimon.core.agent.tool.permission.ToolPermissionValidator;

/** Contract of the shared allow-list narrowing used by both subagent execution paths. */
@DisplayName("SubagentToolScope")
class SubagentToolScopeTest {

    private static Tool tool(String name) {
        return new AbstractTool(name, name + " description", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ran " + name);
            }
        };
    }

    private static Subagent subagentAllowing(List<String> allowedTools) {
        return Subagent.of("explorer", SubagentMetadata.builder().description("d").tools(allowedTools).build(),
                SubagentContent.of("you are explorer"));
    }

    private static ToolRegistry registryOf(Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    @Test
    @DisplayName("admits every tool when the subagent declares no restrictions")
    void admitsEverythingWithoutRestrictions() {
        final Subagent subagent = subagentAllowing(List.of());

        assertThat(SubagentToolScope.admits(subagent, tool("Anything"))).isTrue();
    }

    @Test
    @DisplayName("admits a name in the allow-list and refuses one that is absent")
    void admitsByName() {
        final Subagent subagent = subagentAllowing(List.of("Reader", "Grepper"));

        assertThat(SubagentToolScope.admits(subagent, tool("Reader"))).isTrue();
        assertThat(SubagentToolScope.admits(subagent, tool("Writer"))).isFalse();
    }

    @Test
    @DisplayName("admits a pattern entry's tool — the pattern narrows arguments, not the offer")
    void admitsPatternEntries() {
        final Subagent subagent = subagentAllowing(List.of("Bash(git:*)"));

        assertThat(SubagentToolScope.admits(subagent, tool("Bash"))).isTrue();
    }

    @Test
    @DisplayName("matching is exact, so a differently-cased name is not admitted")
    void matchingIsExact() {
        final Subagent subagent = subagentAllowing(List.of("Reader"));

        assertThat(SubagentToolScope.admits(subagent, tool("reader"))).isFalse();
    }

    @Test
    @DisplayName("scope() narrows the registry to the allow-list")
    void scopeNarrowsRegistry() {
        final ToolRegistry scoped = SubagentToolScope.scope(registryOf(tool("Reader"), tool("Writer")),
                subagentAllowing(List.of("Reader")));

        assertThat(scoped.findByName("Reader")).isPresent();
        assertThat(scoped.findByName("Writer")).isEmpty();
    }

    @Test
    @DisplayName("scope() returns the very same registry when there is nothing to narrow")
    void scopeReturnsSameInstanceWithoutRestrictions() {
        final ToolRegistry full = registryOf(tool("Reader"), tool("Writer"));

        assertThat(SubagentToolScope.scope(full, subagentAllowing(List.of()))).isSameAs(full);
    }

    @Test
    @DisplayName("null arguments are rejected at the entry points")
    void rejectsNulls() {
        final Subagent subagent = subagentAllowing(List.of("Reader"));

        assertThatNullPointerException().isThrownBy(() -> SubagentToolScope.admits(null, tool("Reader")));
        assertThatNullPointerException().isThrownBy(() -> SubagentToolScope.admits(subagent, null));
        assertThatNullPointerException().isThrownBy(() -> SubagentToolScope.scope(null, subagent));
        assertThatNullPointerException().isThrownBy(() -> SubagentToolScope.scope(registryOf(), null));
    }

    /**
     * A tool that reports its {@code command} argument as a COMMAND subject, so a pattern entry like
     * {@code Bash(git:*)} can actually be judged. Without one, a patterned entry is denied for want of anything to
     * interpret it ({@code DefaultToolPermissionValidator:205-211}) — which is what made the first version of
     * {@link #neverWithholdsWhatTheValidatorWouldPermit()} vacuous.
     */
    private static Tool commandTool(String name) {
        final class CommandTool extends AbstractTool implements ToolPermissionSubjectAware {
            CommandTool() {
                super(name, name + " description", Map.of("type", "object"));
            }

            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ran " + name);
            }

            @Override
            public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
                // Raw read: this runs ahead of execute(), so a typed accessor's throw would have nowhere to go.
                if (!(input.get("command") instanceof String command) || command.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(PermissionSubject.command(command));
            }
        }
        return new CommandTool();
    }

    /**
     * The property the narrowing rests on: a name match is a <i>necessary</i> condition for the dispatch permission
     * check, so withholding on the name set can never hide a tool that would have been allowed through. Asserted
     * against the real validator rather than restated in prose, because the two would otherwise be free to drift.
     *
     * <p>
     * The permitted set is asserted <b>exactly</b> before it is used, which is the point: an earlier version looped
     * with a bare {@code if (permitted)} and a plain {@code AbstractTool} for every candidate, so the patterned entry
     * was denied for want of a subject and only the patternless name ever reached an assertion. Pinning the set keeps
     * the pattern path exercised rather than silently skipped.
     */
    @Test
    @DisplayName("anything the validator permits is also admitted — the filter never over-withholds")
    void neverWithholdsWhatTheValidatorWouldPermit() {
        final ToolPermissionValidator validator = new DefaultToolPermissionValidator();
        final List<String> specs = List.of("Reader", "Bash(git:*)");
        final Subagent subagent = subagentAllowing(specs);
        final List<AllowedTool> allowed = specs.stream().map(AllowedTool::parse).toList();

        final Map<Tool, ToolInput> candidates = new LinkedHashMap<>();
        candidates.put(tool("Reader"), ToolInput.of(Map.of()));
        candidates.put(commandTool("Bash"), ToolInput.of(Map.of("command", "git status")));
        candidates.put(commandTool("Bash"), ToolInput.of(Map.of("command", "rm -rf /")));
        candidates.put(tool("Writer"), ToolInput.of(Map.of()));
        candidates.put(tool("reader"), ToolInput.of(Map.of()));

        final List<Tool> permitted = candidates.entrySet().stream()
                .filter(e -> validator.validate(e.getKey(), e.getValue(), ToolContext.empty(), allowed).isAllowed())
                .map(Map.Entry::getKey).toList();

        assertThat(permitted).as("the pattern path must be exercised, not skipped")
                .extracting(t -> t.getDefinition().getName()).containsExactly("Reader", "Bash");

        for (Tool candidate : permitted) {
            assertThat(SubagentToolScope.admits(subagent, candidate))
                    .as("%s is permitted at dispatch, so it must stay on offer", candidate.getDefinition().getName())
                    .isTrue();
        }
    }
}
