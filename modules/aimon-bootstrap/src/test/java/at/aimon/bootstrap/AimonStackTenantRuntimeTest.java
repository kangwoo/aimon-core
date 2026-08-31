package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.exception.AgentRuntimeExhaustedException;
import at.aimon.bootstrap.exception.UnknownAgentRuntimeException;
import at.aimon.bootstrap.runtime.AgentRuntimeLease;
import at.aimon.bootstrap.spec.AgentRuntimeSpec;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.AgentWorkspaceLayout;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.SubmitRequest;

/**
 * Two agents and two tenants on one stack — runtimes nobody enumerated, created while the process is serving.
 *
 * <p>
 * {@code AimonStackBuilderTest} covers the agents named in configuration, which exist before the first request
 * and are wrong loudly. These are the other half: a tenant arrives once, unannounced, and every way of getting it
 * wrong is quiet. Two tenants sharing a workspace, a tenant given a different tool list than its own agent, a
 * runtime closed while a warm session was about to use it, a typo answered with a working agent nobody
 * configured — none of those throws on its own, so each one is a row here.
 */
class AimonStackTenantRuntimeTest {

    private static final LlmClient STUB_LLM = new LlmClient() {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            // Text and no tool use: one iteration and the turn is over, which is all these tests need from it.
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    };

    private static AgentBundle bundle(String name) {
        return AgentBundle.builder()
                .agent(DefaultAgent.builder().name(name).systemPrompt("You are " + name + ".").maxIterations(5).build())
                .build();
    }

    private static AimonStackSpec.Builder twoAgents(Path workspace) {
        return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.of(bundle("ops"))).agent(AgentSpec.of(bundle("audit")));
    }

    private static AgentRuntimeId id(String agentName, String tenant) {
        return AgentRuntimeId.fromName(agentName, tenant);
    }

    @Nested
    @DisplayName("Creating")
    class Creating {

        @Test
        @DisplayName("builds a tenant runtime on first use and hands the same one to the next caller")
        void firstUseBuildsAndTheSecondReuses(@TempDir Path workspace) {
            final AgentRuntimeId acme = id("ops", "acme");

            try (AimonStack stack = AimonStackBuilder.build(twoAgents(workspace).build())) {
                assertThat(stack.agentRuntimeRegistry().get(acme)).as("not built until somebody asks").isEmpty();

                final AgentRuntime first;
                try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(acme)) {
                    first = lease.runtime();
                    assertThat(first.getId()).isEqualTo(acme);
                    assertThat(first.getAgent().getName()).isEqualTo("ops");
                    assertThat(stack.agentRuntimeRegistry().get(acme)).containsSame(first);
                }
                try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(acme)) {
                    assertThat(lease.runtime()).isSameAs(first);
                }
            }
        }

        @Test
        @DisplayName("gives two agents and two tenants four runtimes and four workspaces")
        void twoAgentsTimesTwoTenants(@TempDir Path workspace) {
            try (AimonStack stack = AimonStackBuilder.build(twoAgents(workspace).build())) {
                try (AgentRuntimeLease opsAcme = stack.agentRuntimes().acquire(id("ops", "acme"));
                        AgentRuntimeLease opsGlobex = stack.agentRuntimes().acquire(id("ops", "globex"));
                        AgentRuntimeLease auditAcme = stack.agentRuntimes().acquire(id("audit", "acme"));
                        AgentRuntimeLease auditGlobex = stack.agentRuntimes().acquire(id("audit", "globex"))) {

                    assertThat(
                            List.of(opsAcme.runtime(), opsGlobex.runtime(), auditAcme.runtime(), auditGlobex.runtime()))
                            .doesNotHaveDuplicates();
                    // Both axes in the path: keyed by agent alone, acme and globex would be one directory, and
                    // the two of them are usually two customers.
                    assertThat(workingDirectoryOf(opsAcme)).isEqualTo(workspace + "/ops/acme");
                    assertThat(workingDirectoryOf(opsGlobex)).isEqualTo(workspace + "/ops/globex");
                    assertThat(workingDirectoryOf(auditAcme)).isEqualTo(workspace + "/audit/acme");
                    assertThat(workingDirectoryOf(auditGlobex)).isEqualTo(workspace + "/audit/globex");
                    // And neither collides with the startup runtime of its own agent.
                    assertThat(stack.fileSystem(AgentRuntimeId.fromName("ops")).orElseThrow().getWorkingDirectory())
                            .isEqualTo(workspace + "/ops/" + AgentWorkspaceLayout.NO_DISCRIMINATOR);
                }
                assertThat(stack.agentRuntimes().trackedCount()).isEqualTo(4);
            }
        }

        @Test
        @DisplayName("a file one tenant writes is invisible to the other tenant and to the other agent")
        void tenantsCannotSeeEachOther(@TempDir Path workspace) {
            try (AimonStack stack = AimonStackBuilder.build(twoAgents(workspace).build())) {
                try (AgentRuntimeLease acme = stack.agentRuntimes().acquire(id("ops", "acme"));
                        AgentRuntimeLease globex = stack.agentRuntimes().acquire(id("ops", "globex"));
                        AgentRuntimeLease audit = stack.agentRuntimes().acquire(id("audit", "acme"))) {

                    fileSystemOf(acme).write("invoice.txt", "acme only");

                    assertThat(fileSystemOf(acme).exists("invoice.txt")).isTrue();
                    assertThat(fileSystemOf(globex).exists("invoice.txt")).isFalse();
                    assertThat(fileSystemOf(audit).exists("invoice.txt")).isFalse();
                }
            }
        }

        @Test
        @DisplayName("gives a tenant the same tools its agent was configured with")
        void toolsAreAssembledInOnePlace(@TempDir Path workspace) {
            try (AimonStack stack = AimonStackBuilder.build(twoAgents(workspace).build())) {
                final AgentRuntime configured = stack.runtime(AgentRuntimeId.fromName("ops")).orElseThrow();
                try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(id("ops", "acme"))) {
                    // A second assembly site would not throw. It would give tenants a quietly different tool
                    // list, and "works for the demo agent, does nothing for the customer" is how that reads from
                    // outside.
                    assertThat(toolNames(lease.runtime())).isEqualTo(toolNames(configured));
                    assertThat(toolNames(lease.runtime())).isNotEmpty();
                }
            }
        }

        @Test
        @DisplayName("refuses an agent name nobody configured instead of inventing one")
        void unknownAgentIsRefused(@TempDir Path workspace) {
            try (AimonStack stack = AimonStackBuilder.build(twoAgents(workspace).build())) {
                assertThatThrownBy(() -> stack.agentRuntimes().acquire(id("ops-typo", "acme")))
                        .isInstanceOf(UnknownAgentRuntimeException.class).hasMessageContaining("ops-typo")
                        .hasMessageContaining("ops").hasMessageContaining("audit");

                assertThat(stack.agentRuntimes().trackedCount()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("Capacity health")
    class CapacityHealth {

        /**
         * A cap of one makes "full" reachable with a single tenant. The sweep interval is pushed out of the way so
         * the background sweeper cannot reclaim a slot mid-assertion — what these tests measure is what
         * {@code health()} sees, not what a timer did between two lines.
         */
        private AimonStackSpec.Builder cappedAtOne(Path workspace, Duration idleTtl) {
            return twoAgents(workspace).agentRuntimes(AgentRuntimeSpec.builder().maxEntries(1).idleTtl(idleTtl)
                    .sweepInterval(Duration.ofHours(1)).build());
        }

        @Test
        @DisplayName("a refusal that has passed does not hold the stack DOWN")
        void anOldExhaustionDoesNotLatch(@TempDir Path workspace) {
            // Zero TTL: releasing the lease makes the slot reclaimable immediately, which is what "recovered"
            // means here. With the default 30-minute TTL a released slot is still unavailable to a new tenant for
            // half an hour, and reporting DOWN for that half hour is correct rather than a bug.
            try (AimonStack stack = AimonStackBuilder.build(cappedAtOne(workspace, Duration.ZERO).build())) {
                final AgentRuntimeLease held = stack.agentRuntimes().acquire(id("ops", "acme"));
                assertThatThrownBy(() -> stack.agentRuntimes().acquire(id("ops", "globex")))
                        .isInstanceOf(AgentRuntimeExhaustedException.class);

                // Held and nothing reclaimable: the refusal is happening now, so it is a real reason not to send
                // more work here.
                assertThat(stack.health().isServing()).isFalse();

                held.close();

                // The exact regression: exhaustionCount() is still 1 and always will be, but the slot is free and
                // the very next acquire succeeds — so a report that still said DOWN would be describing history.
                assertThat(stack.agentRuntimes().exhaustionCount()).isEqualTo(1);
                assertThat(stack.health().isServing()).as("recovered stacks report recovered").isTrue();
                assertThat(stack.health().describe()).contains("1 request(s) refused since startup");
                try (AgentRuntimeLease next = stack.agentRuntimes().acquire(id("ops", "globex"))) {
                    assertThat(next.runtime()).isNotNull();
                }
            }
        }

        @Test
        @DisplayName("being at the cap is not itself unhealthy while a slot can still be reclaimed")
        void fullButReclaimableIsHealthy(@TempDir Path workspace) {
            try (AimonStack stack = AimonStackBuilder.build(cappedAtOne(workspace, Duration.ZERO).build())) {
                try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(id("ops", "acme"))) {
                    assertThat(lease.runtime()).isNotNull();
                }

                // At 1 of 1, and serving: admission sweeps before it counts, so the released entry is a slot the
                // next tenant gets. A check that only compared trackedCount() with maxEntries would call this
                // down — and "at the cap" is the steady state of every busy node with a long TTL.
                assertThat(stack.agentRuntimes().trackedCount()).isEqualTo(1);
                assertThat(stack.health().isServing()).isTrue();
                assertThat(stack.health().describe()).contains("1 of 1 tenant runtime slot(s) in use");
            }
        }

        @Test
        @DisplayName("does not count a misspelled agent name as a runtime that failed to build")
        void unknownAgentIsNotAProvisionFailure(@TempDir Path workspace) {
            try (AimonStack stack = AimonStackBuilder.build(twoAgents(workspace).build())) {
                assertThatThrownBy(() -> stack.agentRuntimes().acquire(id("ops-typo", "acme")))
                        .isInstanceOf(UnknownAgentRuntimeException.class);

                // The name is only resolved during provisioning, so this does travel the failure path. Counting it
                // would let anyone holding a keyboard drive the meter an operator pages on.
                assertThat(stack.agentRuntimes().provisionFailureCount()).isZero();
                assertThat(stack.health().isServing()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Sessions")
    class Sessions {

        @Test
        @DisplayName("routes a tenant submit to a runtime built for that tenant")
        void submitReachesTheTenantRuntime(@TempDir Path workspace) throws Exception {
            try (AimonStack stack = AimonStackBuilder.build(twoAgents(workspace).build())) {
                await(stack.sessionRouter().submit(request("s-1", "ops", "acme")));
                await(stack.sessionRouter().submit(request("s-2", "ops", "globex")));

                // Before the opener asked the resolver, a discriminator on a submit reached the registry, missed,
                // and threw — the whole tenant axis was unreachable through the session path.
                assertThat(stack.agentRuntimes().trackedIds()).containsExactlyInAnyOrder(id("ops", "acme"),
                        id("ops", "globex"));
            }
        }

        @Test
        @DisplayName("a live session keeps its runtime alive, and releasing the session gives it back")
        void aLiveSessionPinsItsRuntime(@TempDir Path workspace) throws Exception {
            // Zero TTL: everything with no holder is reclaimable the instant it is released, so what survives a
            // sweep survives because it is held, not because the clock has not caught up.
            //
            // The sweep interval has to be set with it. It defaults to the idle TTL, which at zero means the
            // background sweeper runs every millisecond and reclaims the runtime the moment the release hands its
            // lease back — leaving the sweep below to report 0 for a runtime that is already gone. Pushing the
            // timer out of the way makes this thread the only sweeper, so the count is this release's doing.
            final AimonStackSpec spec = twoAgents(workspace).agentRuntimes(
                    AgentRuntimeSpec.builder().idleTtl(Duration.ZERO).sweepInterval(Duration.ofHours(1)).build())
                    .build();

            try (AimonStack stack = AimonStackBuilder.build(spec)) {
                final SessionId sessionId = SessionId.of("s-pinned");
                await(stack.sessionRouter().submit(request(sessionId.value(), "ops", "acme")));

                // The session is cached and idle between turns. Idle is exactly what a sweeper looks for, and
                // closing the runtime under a handle that is about to run a turn is the failure this guards.
                assertThat(stack.agentRuntimes().sweep()).isZero();
                assertThat(stack.agentRuntimeRegistry().get(id("ops", "acme"))).isPresent();

                // A second turn on the same session must still find a working runtime.
                await(stack.sessionRouter().submit(request(sessionId.value(), "ops", "acme")));

                stack.sessionRouter().releaseSession(sessionId);

                assertThat(stack.agentRuntimes().sweep()).isEqualTo(1);
                assertThat(stack.agentRuntimeRegistry().get(id("ops", "acme"))).isEmpty();
                assertThat(stack.agentRuntimes().trackedCount()).isZero();
            }
        }

        @Test
        @DisplayName("does not pin the agents that were there before the first request")
        void startupRuntimesAreNotTracked(@TempDir Path workspace) throws Exception {
            try (AimonStack stack = AimonStackBuilder.build(twoAgents(workspace).build())) {
                await(stack.sessionRouter().submit(request("s-3", "ops", null)));

                // A configured agent is served from the registry. Tracking it would put it under the tenant cap
                // and let a sweep close an agent the deployment declared.
                assertThat(stack.agentRuntimes().trackedCount()).isZero();
                assertThat(stack.runtime(AgentRuntimeId.fromName("ops"))).isPresent();
            }
        }
    }

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("closes tenant runtimes with the stack and leaves nothing registered")
        void shutdownReclaimsTenants(@TempDir Path workspace) {
            final AgentRuntimeId acme = id("audit", "acme");
            final AimonStack stack = AimonStackBuilder.build(twoAgents(workspace).build());
            final VirtualFileSystem tenantFileSystem;
            try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(acme)) {
                tenantFileSystem = fileSystemOf(lease);
            }
            assertThat(stack.agentRuntimeRegistry().get(acme)).isPresent();

            stack.close();

            assertThat(stack.agentRuntimeRegistry().get(acme)).isEmpty();
            assertThat(stack.agentRuntimes().trackedCount()).isZero();
            // The file system was created for the tenant and is not reachable from AgentRuntime.close(), so the
            // provisioner had to hand it to the runtime as an owned resource or it outlives every tenant.
            assertThat(tenantFileSystem.getStatus().isAvailable()).isFalse();
        }
    }

    /**
     * The gap between {@code AimonStackBuilder.assemble} and {@code startRuntimes()}, where a declared agent's id
     * is in no registry and yet already has a runtime waiting to be registered. That gap is exactly what a host
     * with an inbound port asks for by using {@code assemble} rather than {@code build}, which closes it itself.
     *
     * <p>
     * An agent declared <i>with a discriminator</i> is the case that makes this more than a lookup miss: its id
     * is shaped exactly like a tenant's, the provisioner keys its templates by agent name and so builds it
     * happily, and the stack would then register its own runtime over the one just built — leaving two live
     * runtimes for one id, one of them unreachable through the registry but still leased and swept here.
     */
    @Nested
    @DisplayName("Before the stack is started")
    class BeforeStart {

        private AimonStackSpec.Builder declaredWithDiscriminator(Path workspace) {
            return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(STUB_LLM))
                    .agent(AgentSpec.builder().bundle(bundle("ops")).discriminator("eu").build());
        }

        @Test
        @DisplayName("refuses a declared id instead of building a second runtime for it")
        void declaredIdIsNotBuiltTwice(@TempDir Path workspace) {
            final AgentRuntimeId declared = id("ops", "eu");

            try (AimonStack stack = AimonStackBuilder.assemble(declaredWithDiscriminator(workspace).build())) {
                assertThat(stack.isStarted()).isFalse();
                assertThat(stack.agentRuntimeRegistry().get(declared)).isEmpty();

                assertThatThrownBy(() -> stack.agentRuntimes().acquire(declared))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("agent:ops:eu")
                        .hasMessageContaining("startRuntimes");

                assertThat(stack.agentRuntimes().trackedCount()).isZero();
                assertThat(stack.agentRuntimeRegistry().get(declared)).isEmpty();
            }
        }

        @Test
        @DisplayName("hands out the stack's own runtime once startRuntimes has run")
        void declaredIdResolvesAfterStart(@TempDir Path workspace) {
            final AgentRuntimeId declared = id("ops", "eu");

            try (AimonStack stack = AimonStackBuilder.assemble(declaredWithDiscriminator(workspace).build())) {
                stack.startRuntimes();

                try (AgentRuntimeLease lease = stack.agentRuntimes().acquire(declared)) {
                    assertThat((AgentRuntime) stack.runtime(declared).orElseThrow()).isSameAs(lease.runtime());
                }
                // Pinned, not tracked: the stack owns this runtime and closes it in its own teardown.
                assertThat(stack.agentRuntimes().trackedCount()).isZero();
            }
        }

        @Test
        @DisplayName("still refuses an unknown agent for what it is, not as a declared id")
        void unknownAgentKeepsItsOwnFailure(@TempDir Path workspace) {
            try (AimonStack stack = AimonStackBuilder.assemble(declaredWithDiscriminator(workspace).build())) {
                assertThatThrownBy(() -> stack.agentRuntimes().acquire(id("nope", "eu")))
                        .isInstanceOf(UnknownAgentRuntimeException.class);
            }
        }
    }

    private static SubmitRequest request(String sessionId, String agentRef, String tenant) {
        final SubmitRequest.Builder builder = SubmitRequest.builder().sessionId(SessionId.of(sessionId))
                .agentRef(agentRef).userInput("hello").initiator(Principal.user("tester"));
        if (tenant != null) {
            builder.contextDiscriminator(tenant);
        }
        return builder.build();
    }

    private static void await(SubmitDisposition disposition) throws Exception {
        disposition.getFuture().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static VirtualFileSystem fileSystemOf(AgentRuntimeLease lease) {
        return ((OrcaAgentRuntime) lease.runtime()).getFileSystem();
    }

    private static String workingDirectoryOf(AgentRuntimeLease lease) {
        return fileSystemOf(lease).getWorkingDirectory();
    }

    private static List<String> toolNames(AgentRuntime runtime) {
        return runtime.getAvailableTools().stream().map(tool -> tool.getDefinition().getName()).sorted().toList();
    }
}
