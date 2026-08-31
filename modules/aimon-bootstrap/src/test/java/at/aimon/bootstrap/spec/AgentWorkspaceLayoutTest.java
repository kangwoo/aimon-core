package at.aimon.bootstrap.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.bootstrap.exception.AimonBootstrapException;
import at.aimon.core.agent.AgentRuntimeId;

/**
 * The mapping from a runtime id to a directory, and the segments it refuses.
 *
 * <p>
 * Both halves matter equally. The mapping is what keeps two tenants apart; the refusals are what stop an agent
 * name that came from a database row from resolving to a directory outside the workspace root. A sanitising
 * implementation would pass every test in the first group and quietly fail the second — the caller would get a
 * working file system pointed somewhere they did not name.
 */
class AgentWorkspaceLayoutTest {

    @Test
    @DisplayName("a runtime with no discriminator lands under the reserved tenant directory")
    void noDiscriminatorUsesTheReservedName() {
        assertThat(AgentWorkspaceLayout.resolve("/workspace", AgentRuntimeId.of("agent:ops")))
                .isEqualTo("/workspace/ops/_default");
    }

    @Test
    @DisplayName("both axes are in the path, so two tenants of one agent are two directories")
    void bothAxesAreInThePath() {
        final String acme = AgentWorkspaceLayout.resolve("/workspace", AgentRuntimeId.of("agent:ops:acme"));
        final String globex = AgentWorkspaceLayout.resolve("/workspace", AgentRuntimeId.of("agent:ops:globex"));

        assertThat(acme).isEqualTo("/workspace/ops/acme");
        assertThat(globex).isEqualTo("/workspace/ops/globex");
        assertThat(acme).isNotEqualTo(globex);
    }

    @Test
    @DisplayName("a trailing separator on the root does not produce a doubled one")
    void trailingSeparatorOnTheRootIsAbsorbed() {
        assertThat(AgentWorkspaceLayout.resolve("/workspace/", AgentRuntimeId.of("agent:ops:acme")))
                .isEqualTo("/workspace/ops/acme");
    }

    @Test
    @DisplayName("a discriminator that would escape the workspace root is refused, not rewritten")
    void traversalInTheDiscriminatorIsRefused() {
        assertThatThrownBy(() -> AgentWorkspaceLayout.resolve("/workspace", AgentRuntimeId.of("agent:ops:..")))
                .isInstanceOf(AimonBootstrapException.class).hasMessageContaining("discriminator")
                .hasMessageContaining("agent:ops:..");
    }

    @Test
    @DisplayName("a separator inside a segment is refused — it would silently add a path level")
    void aSeparatorInsideASegmentIsRefused() {
        // '../secrets' is the obvious attack; 'a/b' is the one that merely reshapes the tree, which is just as
        // wrong because the id no longer determines the directory one-to-one.
        assertThatThrownBy(() -> AgentWorkspaceLayout.resolve("/workspace", AgentRuntimeId.of("agent:ops:a/b")))
                .isInstanceOf(AimonBootstrapException.class);
        assertThatThrownBy(() -> AgentWorkspaceLayout.resolve("/workspace", AgentRuntimeId.of("agent:ops:a\\b")))
                .isInstanceOf(AimonBootstrapException.class);
    }

    @Test
    @DisplayName("the reserved tenant name cannot be claimed by a real discriminator")
    void theReservedTenantNameIsRefused() {
        // Not traversal, same outcome: this tenant would share a directory with the no-discriminator runtime of
        // the same agent, and nothing would report it.
        assertThatThrownBy(() -> AgentWorkspaceLayout.resolve("/workspace",
                AgentRuntimeId.of("agent:ops:" + AgentWorkspaceLayout.NO_DISCRIMINATOR)))
                .isInstanceOf(AimonBootstrapException.class).hasMessageContaining("reserved");
    }

    @Test
    @DisplayName("an agent name that is not a usable directory name is refused")
    void anUnusableAgentNameIsRefused() {
        assertThatThrownBy(() -> AgentWorkspaceLayout.resolve("/workspace", AgentRuntimeId.of("agent:..")))
                .isInstanceOf(AimonBootstrapException.class).hasMessageContaining("agent name");
    }

    @Test
    @DisplayName("a blank workspace root is a caller bug, not a configuration one")
    void aBlankRootIsRejected() {
        assertThatThrownBy(() -> AgentWorkspaceLayout.resolve("  ", AgentRuntimeId.of("agent:ops")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
