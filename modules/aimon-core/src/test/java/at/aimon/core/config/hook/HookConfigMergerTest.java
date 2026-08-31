package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.config.hook.MergedHookConfig.MergedHookEntry;

@DisplayName("HookConfigMerger")
class HookConfigMergerTest {

    private final HookConfigMerger merger = new HookConfigMerger();
    private final JacksonHookConfigParser parser = new JacksonHookConfigParser();

    @Test
    @DisplayName("USER → PROJECT → LOCAL precedence is preserved as dispatch order")
    void layeredOrder() {
        final HookConfigDocument user = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"a\",\"hooks\":[{\"type\":\"command\",\"command\":\"u\"}]}]}}");
        final HookConfigDocument project = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"a\",\"hooks\":[{\"type\":\"command\",\"command\":\"p\"}]}]}}");
        final HookConfigDocument local = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"a\",\"hooks\":[{\"type\":\"command\",\"command\":\"l\"}]}]}}");

        final LayeredHookConfig layered = LayeredHookConfig.builder().put(HookConfigSource.USER, user)
                .put(HookConfigSource.PROJECT, project).put(HookConfigSource.LOCAL, local).build();

        final MergedHookConfig merged = merger.merge(layered);

        final List<MergedHookEntry> entries = merged.forEvent("preTool");
        assertThat(entries).extracting(MergedHookEntry::getSource).containsExactly(HookConfigSource.USER,
                HookConfigSource.PROJECT, HookConfigSource.LOCAL);
    }

    @Test
    @DisplayName("Unknown / unsupported events are filtered out")
    void unknownEventsFiltered() {
        final HookConfigDocument doc = parser.parse("{\"hooks\":{\"Notification\":[{\"matcher\":\"x\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"y\"}]}],\"GibberishEvent\":[]}}");

        final LayeredHookConfig layered = LayeredHookConfig.builder().put(HookConfigSource.PROJECT, doc).build();
        final MergedHookConfig merged = merger.merge(layered);

        assertThat(merged.entriesByAimonEvent()).isEmpty();
    }

    @Test
    @DisplayName("SKILL entries are tagged with skill name and source=SKILL")
    void skillEntries() {
        final HookConfigDocument doc = parser.parse(
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"a\",\"hooks\":[{\"type\":\"command\",\"command\":\"s\"}]}]}}");

        final LayeredHookConfig layered = LayeredHookConfig.builder().putSkill("my-skill", doc).build();
        final MergedHookConfig merged = merger.merge(layered);

        final List<MergedHookEntry> entries = merged.forEvent("preTool");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSource()).isEqualTo(HookConfigSource.SKILL);
        assertThat(entries.get(0).getSkillName()).isEqualTo("my-skill");
    }
}
