package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.permission.AllowedTool;

@DisplayName("InlineSkillCeiling — an execution's ceiling as inline skills narrow it (TC-26)")
class InlineSkillCeilingTest {

    private static final ProgrammaticToolSettings READ_GREP = ProgrammaticToolSettings.builder()
            .tools(List.of("Read", "Grep")).build();
    private static final ProgrammaticToolSettings READ = ProgrammaticToolSettings.builder().tools(List.of("Read"))
            .build();

    @Test
    @DisplayName("starts untouched at the loop's own values")
    void startsUntouched() {
        final InlineSkillCeiling.Snapshot snapshot = InlineSkillCeiling.startingAt(tools("Read", "Grep"), READ_GREP)
                .snapshot();

        assertThat(snapshot.getVersion()).isZero();
        assertThat(snapshot.isNarrowed()).isFalse();
        assertThat(snapshot.getAllowedTools()).isEqualTo(tools("Read", "Grep"));
        assertThat(snapshot.getProgrammatic()).isEqualTo(READ_GREP);
        assertThat(snapshot.getSkills()).isEmpty();
    }

    @Test
    @DisplayName("narrows the allow-list to the intersection and the programmatic settings by the current ones")
    void narrowsBothHalves() {
        final InlineSkillCeiling ceiling = InlineSkillCeiling.startingAt(tools("Read", "Grep", "Bash"), READ_GREP);

        assertThat(ceiling.narrow("a", tools("Read", "Write"), READ)).isEmpty();

        final InlineSkillCeiling.Snapshot snapshot = ceiling.snapshot();
        assertThat(snapshot.getVersion()).isEqualTo(1);
        assertThat(snapshot.getAllowedTools()).isEqualTo(tools("Read"));
        assertThat(snapshot.getProgrammatic()).isEqualTo(READ);
        assertThat(snapshot.getSkills()).containsExactly("a");
    }

    @Test
    @DisplayName("an unrestricted loop takes the skill's allow-list; a skill without programmatic keys leaves none()")
    void unrestrictedLoopAndNonePropagation() {
        final InlineSkillCeiling ceiling = InlineSkillCeiling.startingAt(List.of(), READ_GREP);

        ceiling.narrow("a", tools("Read"), ProgrammaticToolSettings.none());

        assertThat(ceiling.snapshot().getAllowedTools()).isEqualTo(tools("Read"));
        assertThat(ceiling.snapshot().getProgrammatic().isEnabled()).isFalse();
        // none() stays none(): a later skill that opens programmatic tool calling cannot widen it back.
        ceiling.narrow("b", List.of(), READ_GREP);
        assertThat(ceiling.snapshot().getProgrammatic().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("several skills narrow cumulatively and never widen")
    void cumulative() {
        final InlineSkillCeiling ceiling = InlineSkillCeiling.startingAt(tools("Read", "Grep", "Bash"), READ_GREP);

        ceiling.narrow("a", tools("Read", "Grep"), READ_GREP);
        ceiling.narrow("b", tools("Read", "Grep", "Bash"), READ_GREP);

        assertThat(ceiling.snapshot().getAllowedTools()).isEqualTo(tools("Read", "Grep"));
        assertThat(ceiling.snapshot().getVersion()).isEqualTo(1);
        ceiling.narrow("c", tools("Grep"), READ_GREP);
        assertThat(ceiling.snapshot().getAllowedTools()).isEqualTo(tools("Grep"));
        assertThat(ceiling.snapshot().getSkills()).containsExactly("a", "c");
    }

    @Test
    @DisplayName("a disjoint allow-list is refused with the slash path's wording and leaves the state unchanged")
    void disjointRefused() {
        final InlineSkillCeiling ceiling = InlineSkillCeiling.startingAt(tools("Read"), READ);

        assertThat(ceiling.narrow("writer", tools("Write"), READ)).hasValueSatisfying(message -> assertThat(message)
                .startsWith("Skill 'writer' cannot run: its allowed-tools and the caller's have nothing in common"));

        assertThat(ceiling.snapshot().getVersion()).isZero();
        assertThat(ceiling.snapshot().getAllowedTools()).isEqualTo(tools("Read"));
    }

    @Test
    @DisplayName("a skill that changes nothing — no allow-list, same programmatic settings — does not bump the version")
    void idempotentReNarrow() {
        final InlineSkillCeiling ceiling = InlineSkillCeiling.startingAt(tools("Read"), READ);

        ceiling.narrow("a", List.of(), READ);
        assertThat(ceiling.snapshot().getVersion()).isZero();
        ceiling.narrow("b", tools("Read"), READ);
        ceiling.narrow("b", tools("Read"), READ);
        assertThat(ceiling.snapshot().getVersion()).isZero();
    }

    @Test
    @DisplayName("concurrent narrows are serialized: the result is the intersection of all and each bump is counted")
    void threadSafe() throws Exception {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            names.add("T" + i);
        }
        final InlineSkillCeiling ceiling = InlineSkillCeiling.startingAt(tools(names.toArray(String[]::new)),
                ProgrammaticToolSettings.none());
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                final List<String> keep = new ArrayList<>(names);
                keep.remove("T" + i);
                final String skill = "s" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return ceiling.narrow(skill, tools(keep.toArray(String[]::new)), ProgrammaticToolSettings.none());
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ceiling.snapshot().getAllowedTools()).isEqualTo(tools("T15"));
        assertThat(ceiling.snapshot().getVersion()).isEqualTo(15);
        assertThat(ceiling.snapshot().getSkills()).hasSize(15);
    }

    @Test
    @DisplayName("TC-30: a skill that empties enabled programmatic settings records why — no keys, engine conflict,"
            + " no tool in common — and later skills keep the first reason")
    void programmaticReason() {
        final InlineSkillCeiling noKeys = InlineSkillCeiling.startingAt(List.of(), READ_GREP);
        assertThat(noKeys.snapshot().getProgrammaticReason()).isEmpty();
        noKeys.narrow("plain", List.of(), ProgrammaticToolSettings.none());
        assertThat(noKeys.snapshot().getProgrammaticReason()).hasValue("inline skill 'plain' sets no "
                + "programmatic-tools, and an activated skill's programmatic settings narrow the execution's, so no "
                + "tool is open to code");
        noKeys.narrow("later", tools("Read"), READ);
        assertThat(noKeys.snapshot().getProgrammaticReason()).as("kept: nothing narrows none() back")
                .hasValueSatisfying(reason -> assertThat(reason).contains("'plain'"));

        final InlineSkillCeiling conflict = InlineSkillCeiling.startingAt(List.of(), ProgrammaticToolSettings.builder()
                .tools(List.of("Read")).engine(ProgrammaticEngineChoice.PYTHON).build());
        conflict.narrow("js", List.of(), ProgrammaticToolSettings.builder().tools(List.of("Read"))
                .engine(ProgrammaticEngineChoice.JAVASCRIPT).build());
        assertThat(conflict.snapshot().getProgrammaticReason())
                .hasValue("inline skill 'js' sets programmatic-engine javascript, but the execution uses python");

        final InlineSkillCeiling disjoint = InlineSkillCeiling.startingAt(List.of(), READ);
        disjoint.narrow("grep", List.of(), ProgrammaticToolSettings.builder().tools(List.of("Grep")).build());
        assertThat(disjoint.snapshot().getProgrammaticReason()).hasValue(
                "inline skill 'grep' sets programmatic-tools [Grep], but the execution allows none of them (it allows "
                        + "[Read])");
    }

    @Test
    @DisplayName("TC-30: no reason while settings remain, or when the execution started without any")
    void noProgrammaticReason() {
        final InlineSkillCeiling narrowed = InlineSkillCeiling.startingAt(List.of(), READ_GREP);
        narrowed.narrow("reader", List.of(), READ);
        assertThat(narrowed.snapshot().getProgrammatic()).isEqualTo(READ);
        assertThat(narrowed.snapshot().getProgrammaticReason()).isEmpty();

        final InlineSkillCeiling never = InlineSkillCeiling.startingAt(tools("Read", "Grep"),
                ProgrammaticToolSettings.none());
        never.narrow("plain", tools("Read"), ProgrammaticToolSettings.none());
        assertThat(never.snapshot().isNarrowed()).isTrue();
        assertThat(never.snapshot().getProgrammaticReason()).as("a ceiling above it emptied it, or it never opted in")
                .isEmpty();
    }

    private static List<AllowedTool> tools(String... names) {
        return java.util.Arrays.stream(names).map(AllowedTool::parse).toList();
    }
}
