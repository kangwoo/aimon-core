package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.parser.MarkdownSubagentParser;
import at.aimon.core.subagent.parser.SubagentContentParser;

@DisplayName("InMemorySubagentRegistry Tests")
class InMemorySubagentRegistryTest {

    // --- T1: basic mutable-registry behavior ---

    @Nested
    @DisplayName("register / unregister / lookup")
    class BasicBehaviorTests {

        @Test
        @DisplayName("register then getSubagent returns the registered subagent")
        void registerThenGet() {
            InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
            Subagent agent = Subagent.builder().name("db-triage").systemPrompt("You triage DB incidents.").build();

            registry.register(agent);

            assertThat(registry.getSubagent("db-triage")).contains(agent);
        }

        @Test
        @DisplayName("getAllSubagents returns every registered subagent")
        void getAllReturnsEverything() {
            InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
            registry.register(Subagent.builder().name("a").systemPrompt("pa").build());
            registry.register(Subagent.builder().name("b").systemPrompt("pb").build());

            assertThat(registry.getAllSubagents()).extracting(Subagent::getName).containsExactlyInAnyOrder("a", "b");
        }

        @Test
        @DisplayName("getSubagent returns empty when not found")
        void getMissingReturnsEmpty() {
            InMemorySubagentRegistry registry = new InMemorySubagentRegistry();

            assertThat(registry.getSubagent("nope")).isEmpty();
        }

        @Test
        @DisplayName("register replaces on name collision")
        void replaceOnCollision() {
            InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
            registry.register(Subagent.builder().name("x").description("first").systemPrompt("p1").build());
            registry.register(Subagent.builder().name("x").description("second").systemPrompt("p2").build());

            assertThat(registry.getAllSubagents()).hasSize(1);
            assertThat(registry.getSubagent("x")).get().extracting(s -> s.getMetadata().getDescription())
                    .isEqualTo("second");
        }

        @Test
        @DisplayName("unregister removes and returns the subagent; empty when absent")
        void unregister() {
            InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
            Subagent agent = Subagent.builder().name("x").systemPrompt("p").build();
            registry.register(agent);

            assertThat(registry.unregister("x")).contains(agent);
            assertThat(registry.getSubagent("x")).isEmpty();
            assertThat(registry.unregister("x")).isEmpty();
        }

        @Test
        @DisplayName("getAllSubagents result is a defensive copy")
        void getAllIsDefensiveCopy() {
            InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
            registry.register(Subagent.builder().name("a").systemPrompt("pa").build());

            List<Subagent> snapshot = registry.getAllSubagents();
            registry.register(Subagent.builder().name("b").systemPrompt("pb").build());

            assertThat(snapshot).hasSize(1);
        }

        @Test
        @DisplayName("null guards on register / unregister / getSubagent")
        void nullGuards() {
            InMemorySubagentRegistry registry = new InMemorySubagentRegistry();

            assertThatNullPointerException().isThrownBy(() -> registry.register(null));
            assertThatNullPointerException().isThrownBy(() -> registry.unregister(null));
            assertThatNullPointerException().isThrownBy(() -> registry.getSubagent(null));
        }
    }

    // --- T2 (C1): reload must be a safe no-op even when propagated through a composite ---

    @Nested
    @DisplayName("reload is a safe no-op (C1)")
    class ReloadNoOpTests {

        @Test
        @DisplayName("reloadAll / reloadSubagent retain in-memory state (direct)")
        void reloadKeepsStateDirectly() {
            InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
            Subagent agent = Subagent.builder().name("db-triage").systemPrompt("p").build();
            registry.register(agent);

            registry.reloadAll();
            registry.reloadSubagent("db-triage");

            assertThat(registry.getSubagent("db-triage")).contains(agent);
        }

        @Test
        @DisplayName("code subagents survive a composite reload that clears a sibling file layer")
        void codeSubagentsSurviveCompositeReload() {
            // Composite propagates reloadAll/reloadSubagent unconditionally to EVERY layer. The code layer must not be
            // wiped — only the file-like layer (here a stub that drops its content on reload) is re-scanned.
            InMemorySubagentRegistry code = new InMemorySubagentRegistry();
            code.register(Subagent.builder().name("db-triage").systemPrompt("p").build());

            ClearOnReloadStubRegistry user = new ClearOnReloadStubRegistry();
            user.add(Subagent.of("user-only", SubagentMetadata.builder().build(), SubagentContent.of("p")));

            // Real composition order is [bundled, user, code]: code last = authoritative.
            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(user, code));
            assertThat(composite.getSubagent("db-triage")).isPresent();
            assertThat(composite.getSubagent("user-only")).isPresent();

            composite.reloadAll();

            assertThat(composite.getSubagent("user-only")).as("file layer was re-scanned (cleared)").isEmpty();
            assertThat(composite.getSubagent("db-triage")).as("code layer survives composite reloadAll").isPresent();

            composite.reloadSubagent("db-triage");
            assertThat(composite.getSubagent("db-triage")).as("code layer survives composite reloadSubagent")
                    .isPresent();
        }
    }

    // --- T3 (C2): code layer (last) wins over a same-named user layer ---

    @Nested
    @DisplayName("composition precedence (C2): code is authoritative")
    class PrecedenceTests {

        @Test
        @DisplayName("code wins over same-named user subagent in getSubagent and getAllSubagents")
        void codeWinsOverUser() {
            InMemorySubagentRegistry user = new InMemorySubagentRegistry();
            user.register(Subagent.builder().name("explore").description("User explore")
                    .tools(List.of("Read", "Write", "Bash")).systemPrompt("user").build());

            InMemorySubagentRegistry code = new InMemorySubagentRegistry();
            code.register(Subagent.builder().name("explore").description("Code explore").tools(List.of("Read"))
                    .systemPrompt("code").build());

            // [user, code] mirrors the real [bundled, user, code] ordering: later layer wins.
            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(user, code));

            assertThat(composite.getSubagent("explore")).get().extracting(s -> s.getMetadata().getDescription())
                    .isEqualTo("Code explore");

            Subagent merged = composite.getAllSubagents().stream().filter(s -> s.getName().equals("explore"))
                    .findFirst().orElseThrow();
            assertThat(merged.getMetadata().getDescription()).isEqualTo("Code explore");
            assertThat(merged.getAllowedTools()).hasSize(1); // curated code allow-list, not the wider user one
        }
    }

    // --- T4: code subagents appear in getAllSubagents (the list TaskTool exposes to the model) ---

    @Nested
    @DisplayName("discovery via getAllSubagents (F4)")
    class DiscoveryTests {

        @Test
        @DisplayName("a code-defined subagent is included in the composite getAllSubagents list")
        void codeSubagentIsDiscoverable() {
            InMemorySubagentRegistry user = new InMemorySubagentRegistry();
            user.register(Subagent.builder().name("file-agent").systemPrompt("p").build());

            InMemorySubagentRegistry code = new InMemorySubagentRegistry();
            code.register(Subagent.builder().name("code-agent").systemPrompt("p").build());

            CompositeSubagentRegistry composite = new CompositeSubagentRegistry(List.of(user, code));

            assertThat(composite.getAllSubagents()).extracting(Subagent::getName).contains("code-agent", "file-agent");
        }
    }

    // --- T5 (C3): Subagent.builder() == equivalent markdown parse ---

    @Nested
    @DisplayName("builder defaults match markdown parsing (C3)")
    class BuilderParityTests {

        private final MarkdownSubagentParser parser = new MarkdownSubagentParser(new SubagentContentParser());

        @Test
        @DisplayName("no-frontmatter markdown equals builder with only name + systemPrompt")
        void defaultsParity() {
            // Literal body on both sides: independently proves builder defaults == parser defaults AND that the parser
            // preserves the body verbatim (Subagent.equals compares content too).
            Subagent parsed = parser.parse("plain", "Just a body, no frontmatter.");

            Subagent built = Subagent.builder().name("plain").systemPrompt("Just a body, no frontmatter.").build();

            assertThat(built).isEqualTo(parsed);
            assertThat(built.getMaxIterations()).isEqualTo(1000);
            assertThat(built.hasToolRestrictions()).isFalse();
            assertThat(built.getMetadata().getModel()).isNull();
            assertThat(built.getMetadata().getWhenToUse()).isNull();
            assertThat(built.getMetadata().getDescription()).isNull();
        }

        @Test
        @DisplayName("full-frontmatter markdown equals builder with the same fields")
        void fullParity() {
            String md = "---\n" + "description: DB triage\n" + "when-to-use: When a database incident needs triage\n"
                    + "allowed-tools: Read, Bash(psql:*)\n" + "model: sonnet\n" + "max-iterations: 25\n" + "---\n"
                    + "You are a database triage specialist.";
            Subagent parsed = parser.parse("db-triage", md);

            // Literal inputs on both sides (no reuse of parsed fields): independently reconstructs the same Subagent.
            Subagent built = Subagent.builder().name("db-triage").description("DB triage")
                    .whenToUse("When a database incident needs triage").tools(List.of("Read", "Bash(psql:*)"))
                    .model("sonnet").maxIterations(25).systemPrompt("You are a database triage specialist.").build();

            assertThat(built).isEqualTo(parsed);
        }
    }

    // --- helpers ---

    /** A file-like registry that drops all entries when reloaded, simulating an agents/*.md re-scan. */
    private static final class ClearOnReloadStubRegistry implements SubagentRegistry {
        private final List<Subagent> subagents = new ArrayList<>();

        void add(Subagent subagent) {
            subagents.add(subagent);
        }

        @Override
        public Optional<Subagent> getSubagent(String subagentName) {
            return subagents.stream().filter(s -> s.getName().equals(subagentName)).findFirst();
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return new ArrayList<>(subagents);
        }

        @Override
        public void reloadSubagent(String subagentName) {
            subagents.removeIf(s -> s.getName().equals(subagentName));
        }

        @Override
        public void reloadAll() {
            subagents.clear();
        }
    }
}
