package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import at.aimon.bootstrap.runtime.AgentRuntimeEviction;
import at.aimon.core.knowledge.SimpleDocumentChunker;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import at.aimon.core.llm.capability.ThinkingDialect;
import at.aimon.session.routing.DeploymentMode;

/**
 * Binding and validation of the {@code aimon.*} tree, with no stack behind it.
 *
 * <p>
 * The context here registers the properties bean and nothing else. That is not a shortcut — it is the claim
 * being tested. {@code AimonProperties} is a dependency of every slice, so its {@code afterPropertiesSet} runs
 * before anything is built, and a rejected configuration must fail there rather than several beans later with a
 * message about a builder argument. A context this empty either fails for the stated reason or does not fail.
 *
 * <p>
 * Assertions are on the property name, not the sentence. The names are constants that other code reads; the
 * wording around them is meant to be improved without a test having to be edited to allow it.
 */
class AimonPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues("aimon.workspace.root=/workspace", "aimon.agent-defaults.default-agent=test-agent");

    /**
     * The same context without a default agent, for the cases that are about how the map is declared.
     *
     * <p>
     * Kept separate rather than overriding: {@code withPropertyValues} adds, and a default naming an agent the
     * test did not declare fails for a reason the test is not about.
     */
    private final ApplicationContextRunner agentsRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class).withPropertyValues("aimon.workspace.root=/workspace");

    @Test
    @DisplayName("a ceiling of zero is rejected by name")
    void zeroCeilingIsRejectedByName() {
        // ExecutionBudget refuses this too, from a constructor that only ever saw the number. The property name
        // is the entire value this check adds.
        runner.withPropertyValues("aimon.budget.max-iterations=0").run(
                ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining("aimon.budget.max-iterations"));
    }

    @Test
    @DisplayName("an empty value restores the default rather than removing the ceiling")
    void clearingACeilingRestoresTheDefault() {
        // Worth pinning because the opposite is the natural assumption, and it is wrong: Boot converts the empty
        // value to null and the JavaBean binder then declines to call the setter, so the field keeps its
        // initialiser. There is consequently no way to spell "unlimited" in this property tree — which matches
        // the Budget javadoc's position on unbounded loops in a server, but is not what "clear it" suggests.
        runner.withPropertyValues("aimon.budget.max-tokens=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AimonProperties.class).getBudget().getMaxTokens()).isEqualTo(100_000);
        });
    }

    @Test
    @DisplayName("a duration that has to elapse cannot be zero")
    void zeroTimeoutIsRejectedByName() {
        runner.withPropertyValues("aimon.llm.timeout=0s")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining("aimon.llm.timeout"));
    }

    @Test
    @DisplayName("a drain timeout of zero is a decision, not a mistake")
    void zeroDrainTimeoutIsAllowed() {
        // Unlike a timeout, zero here means something: do not wait for in-flight turns. Rejecting it would take
        // away the only way to ask for an immediate shutdown.
        runner.withPropertyValues("aimon.session.shutdown-drain-timeout=0s").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("a negative duration is rejected by name")
    void negativeDrainTimeoutIsRejectedByName() {
        runner.withPropertyValues("aimon.session.shutdown-drain-timeout=-1s").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasStackTraceContaining("aimon.session.shutdown-drain-timeout"));
    }

    @Test
    @DisplayName("tracing is now built, so enabling it is a configuration and not a refusal")
    void tracingEnabledIsHonoured() {
        // This test replaced two that asserted the opposite. Until the observability slice existed, every
        // aimon.tracing.* value was refused as unsupported — correctly, because nothing read them. The pair of
        // refusals was the last of a list that also held aimon.skill.approval.mode, aimon.session.mode and
        // aimon.scheduling.backend, and each left the list the same way: the slice that reads it was written.
        // Nothing is refused as unsupported any more, which is why there is no smaller version of those tests to
        // keep — only this one, saying the values now bind.
        runner.withPropertyValues("aimon.tracing.enabled=true", "aimon.tracing.payload-capture=full",
                "aimon.tracing.max-chars=4000", "aimon.tracing.max-spans=500").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties.Tracing tracing = ctx.getBean(AimonProperties.class).getTracing();
                    assertThat(tracing.getPayloadCapture()).isEqualTo(PayloadCapture.FULL);
                    assertThat(tracing.toPayloadPolicy().capturesContent()).isTrue();
                    assertThat(tracing.maxSpansOrDefault()).isEqualTo(500);
                });
    }

    @Test
    @DisplayName("tracing settings under tracing.enabled=false are refused together, by name")
    void tracingSettingsWithoutATracerAreRefusedByName() {
        // A deployment ahead of this starter is usually ahead in more than one place. Failing on the first one
        // found would teach the same lesson three restarts in a row.
        runner.withPropertyValues("aimon.tracing.payload-capture=full", "aimon.tracing.max-chars=4000",
                "aimon.tracing.max-spans=500")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.TRACING_PAYLOAD_CAPTURE)
                        .hasStackTraceContaining(AimonProperties.TRACING_MAX_CHARS)
                        .hasStackTraceContaining(AimonProperties.TRACING_MAX_SPANS)
                        .hasStackTraceContaining(AimonProperties.TRACING_ENABLED));
    }

    @Test
    @DisplayName("a character cap with nothing to cap is refused, and names the property that would use it")
    void aPayloadCapWithoutPayloadCaptureIsRefusedByName() {
        // Inert rather than contradictory, which is exactly why it is worth refusing: nobody notices a setting
        // that binds cleanly and bounds nothing.
        runner.withPropertyValues("aimon.tracing.enabled=true", "aimon.tracing.max-chars=4000")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.TRACING_MAX_CHARS)
                        .hasStackTraceContaining(AimonProperties.TRACING_PAYLOAD_CAPTURE));
    }

    @Test
    @DisplayName("the keyword store's chunk settings bind and reach the chunker's defaults when unset")
    void knowledgeChunkSettingsBind() {
        runner.withPropertyValues("aimon.knowledge.backend=keyword", "aimon.knowledge.chunk-size=800").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            final AimonProperties.Knowledge knowledge = ctx.getBean(AimonProperties.class).getKnowledge();
            assertThat(knowledge.chunkSizeOrDefault()).isEqualTo(800);
            // Unset stays the chunker's own default rather than becoming zero — the boxed field is what makes
            // "left alone" distinguishable, and it is the same field the refusals above depend on.
            assertThat(knowledge.chunkOverlapOrDefault()).isEqualTo(SimpleDocumentChunker.DEFAULT_OVERLAP);
        });
    }

    @Test
    @DisplayName("chunk settings under a backend that does no chunking are refused by name")
    void chunkSettingsWithoutAKeywordStoreAreRefusedByName() {
        runner.withPropertyValues("aimon.knowledge.backend=supplied", "aimon.knowledge.chunk-size=800")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.KNOWLEDGE_CHUNK_SIZE)
                        .hasStackTraceContaining(AimonProperties.KNOWLEDGE_BACKEND));
    }

    @Test
    @DisplayName("an overlap at or above the chunk size is rejected rather than left to loop forever")
    void anOverlapThatCannotAdvanceIsRejectedByName() {
        // SimpleDocumentChunker rejects this too, from a constructor that only ever saw two numbers. Naming both
        // properties is the entire value this check adds — and the failure it prevents is not a bad chunk but a
        // window that never moves.
        runner.withPropertyValues("aimon.knowledge.backend=keyword", "aimon.knowledge.chunk-size=500",
                "aimon.knowledge.chunk-overlap=500")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.KNOWLEDGE_CHUNK_OVERLAP)
                        .hasStackTraceContaining(AimonProperties.KNOWLEDGE_CHUNK_SIZE));
    }

    @Test
    @DisplayName("memory without a workspace is refused, because a default would merge unrelated deployments")
    void memoryWithoutAWorkspaceIsRefusedByName() {
        // The one place a silent default would not be inert but actively wrong: two applications inheriting the
        // same properties file would file their observations under one workspace and read each other's back.
        runner.withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.peer-id=alice")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_WORKSPACE_ID)
                        .hasStackTraceContaining(AimonProperties.MEMORY_BACKEND));
    }

    @Test
    @DisplayName("the default peer mode needs the peer it is fixed to, by name")
    void fixedPeerModeRequiresAPeerId() {
        runner.withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.workspace-id=ops")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_PEER_ID)
                        .hasStackTraceContaining(AimonProperties.MEMORY_PEER_MODE));
    }

    @Test
    @DisplayName("a peer id under caller mode is refused rather than read by nobody")
    void aPeerIdUnderCallerModeIsRefusedByName() {
        // Inert, and inert in the direction that matters: the operator who set it believes memory is pinned to
        // one peer while every execution is in fact reading its own.
        runner.withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.workspace-id=ops",
                "aimon.memory.peer-mode=caller", "aimon.memory.peer-id=alice")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_PEER_ID)
                        .hasStackTraceContaining(AimonProperties.MEMORY_PEER_MODE));
    }

    @Test
    @DisplayName("memory settings under backend=none are refused by name")
    void memorySettingsWithoutABackendAreRefusedByName() {
        runner.withPropertyValues("aimon.memory.workspace-id=ops", "aimon.memory.peer-id=alice")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_WORKSPACE_ID)
                        .hasStackTraceContaining(AimonProperties.MEMORY_BACKEND));
    }

    @Test
    @DisplayName("a token cap of zero means no cap, and a negative one is rejected by name")
    void memoryTokenCapIsCheckedByName() {
        runner.withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.workspace-id=ops",
                "aimon.memory.peer-id=alice", "aimon.memory.max-tokens=0").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AimonProperties.class).getMemory().maxTokensOrDefault()).isZero();
                });
        runner.withPropertyValues("aimon.memory.backend=in-memory", "aimon.memory.workspace-id=ops",
                "aimon.memory.peer-id=alice", "aimon.memory.max-tokens=-1")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.MEMORY_MAX_TOKENS));
    }

    @Test
    @DisplayName("writing out the supported values explicitly changes nothing")
    void supportedSelectionsBindAndStart() {
        runner.withPropertyValues("aimon.session.store=in-memory", "aimon.session.mode=single-node",
                "aimon.skill.approval.mode=deny", "aimon.scheduling.backend=none", "aimon.tracing.enabled=false",
                "aimon.tracing.payload-capture=none").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties properties = ctx.getBean(AimonProperties.class);
                    assertThat(properties.getSession().getStore()).isEqualTo(SessionStoreType.IN_MEMORY);
                    assertThat(properties.getSession().getMode()).isEqualTo(DeploymentMode.SINGLE_NODE);
                    assertThat(properties.getSkill().getApproval().getMode()).isEqualTo(ApprovalMode.DENY);
                    assertThat(properties.getScheduling().getBackend()).isEqualTo(SchedulingBackend.NONE);
                    assertThat(properties.getTracing().getPayloadCapture()).isEqualTo(PayloadCapture.NONE);
                });
    }

    @Test
    @DisplayName("distributed mode requires a node id, by name")
    void distributedModeRequiresANodeId() {
        // Defaulting it to a generated per-process id would satisfy "different on every node" and fail "the same
        // after a restart", which is the half that decides whether a node can reclaim the sessions it held.
        runner.withPropertyValues("aimon.session.mode=distributed", "aimon.session.store=redis")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SESSION_MODE)
                        .hasStackTraceContaining(AimonProperties.SESSION_NODE_ID));
    }

    @Test
    @DisplayName("distributed mode refuses the in-memory store, by name")
    void distributedModeRefusesTheInMemoryStore() {
        // The combination binds cleanly and each half is individually legal, which is what makes it worth a
        // check: leases and signals shared, transcripts node-local, so routing a session to its holder reaches a
        // node that has never seen it.
        runner.withPropertyValues("aimon.session.mode=distributed", "aimon.session.node-id=pod-a",
                "aimon.session.store=in-memory")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SESSION_MODE)
                        .hasStackTraceContaining(AimonProperties.SESSION_STORE));
    }

    @Test
    @DisplayName("the store left at its default is refused the same way — the mode is the deliberate half")
    void distributedModeRefusesTheDefaultedStore() {
        runner.withPropertyValues("aimon.session.mode=distributed", "aimon.session.node-id=pod-a")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SESSION_STORE + "=in-memory"));
    }

    @Test
    @DisplayName("a node id carrying the lease stamp separator is rejected by name, in either mode")
    void nodeIdWithLeaseStampSeparatorIsRejectedByName() {
        // RedisSessionLeaseStore writes holder and token as holder:token and reads them back with
        // lastIndexOf(':'), so a colon in the holder makes the two unrecoverable. It refuses the id at
        // tryAcquire, by which point the process is serving traffic; this refuses it while it is still config.
        // Checked outside distributed mode on purpose: the id is legal to set in single-node too, and a
        // deployment that gets it wrong there discovers it on the day it turns clustering on.
        runner.withPropertyValues("aimon.session.node-id=pod-a:8080").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasStackTraceContaining(AimonProperties.SESSION_NODE_ID + "=pod-a:8080"));
    }

    @Test
    @DisplayName("a complete distributed topology binds")
    void distributedModeBindsWhenTheTopologyIsComplete() {
        // No bean in this context, so nothing here can be satisfied by the slice's checks — this is only the
        // half that is decidable from the property tree alone.
        runner.withPropertyValues("aimon.session.mode=distributed", "aimon.session.node-id=pod-a",
                "aimon.session.store=redis").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties.SessionProperties session = ctx.getBean(AimonProperties.class).getSession();
                    assertThat(session.getMode()).isEqualTo(DeploymentMode.DISTRIBUTED);
                    assertThat(session.getNodeId()).isEqualTo("pod-a");
                });
    }

    @Test
    @DisplayName("one agent needs no default — there is no choice to get wrong")
    void oneAgentNeedsNoDefault() {
        agentsRunner.withPropertyValues("aimon.agents.ops.bundle=test-agent").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AimonProperties.class).resolveDefaultAgentRef()).isEqualTo("ops");
        });
    }

    @Test
    @DisplayName("two agents bind when one of them is named as the default")
    void twoAgentsBindWithAnExplicitDefault() {
        agentsRunner.withPropertyValues("aimon.agents.ops.bundle=test-agent",
                "aimon.agents.inquiry.bundle=second-agent", "aimon.agent-defaults.default-agent=inquiry").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties properties = ctx.getBean(AimonProperties.class);
                    assertThat(properties.getAgents().keySet()).containsExactly("ops", "inquiry");
                    assertThat(properties.getAgents().get("ops").getBundle()).isEqualTo("test-agent");
                    assertThat(properties.resolveDefaultAgentRef()).isEqualTo("inquiry");
                });
    }

    @Test
    @DisplayName("two agents without a default are rejected rather than resolved by map order")
    void twoAgentsWithoutADefaultAreRejectedByName() {
        // Taking the first entry would work deterministically — and would move traffic to another agent the day
        // someone reorders the YAML or a profile contributes an agent that lands first, with no property changed
        // and nothing logged.
        agentsRunner
                .withPropertyValues("aimon.agents.ops.bundle=test-agent", "aimon.agents.inquiry.bundle=second-agent")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.DEFAULT_AGENT)
                        .hasStackTraceContaining(AimonProperties.AGENTS));
    }

    @Test
    @DisplayName("a default that names no declared agent is rejected, and the message lists what was declared")
    void defaultNamingNoDeclaredAgentIsRejected() {
        // Without the map this spelling is legal — it names a bundle to load. With the map it is a typo, and the
        // failure has to say so, because the runtime would otherwise route to an agent that does not exist.
        agentsRunner.withPropertyValues("aimon.agents.ops.bundle=test-agent", "aimon.agent-defaults.default-agent=opps")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.DEFAULT_AGENT + "=opps")
                        .hasStackTraceContaining("[ops]"));
    }

    @Test
    @DisplayName("a bundle name that differs from the ref is allowed — one bundle can run under two refs")
    void aBundleMayDifferFromTheRef() {
        agentsRunner.withPropertyValues("aimon.agents.day.bundle=test-agent", "aimon.agents.night.bundle=test-agent",
                "aimon.agent-defaults.default-agent=day").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties properties = ctx.getBean(AimonProperties.class);
                    assertThat(properties.getAgents().get("night").getBundle()).isEqualTo("test-agent");
                });
    }

    @Test
    @DisplayName("a bundle name that is a path is rejected by name")
    void pathLikeBundleIsRejectedByName() {
        // The name is pasted into agents/<bundle>/agent.md. It was unreachable while ref and bundle had to be
        // equal — a ref is a map key someone writes deliberately — and became reachable when they were allowed
        // to differ, which is a configuration value that may well arrive from a database row.
        agentsRunner
                .withPropertyValues("aimon.agents.ops.bundle=../../etc/passwd",
                        "aimon.agent-defaults.default-agent=ops")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.AGENTS + ".ops.bundle"));
    }

    @Test
    @DisplayName("a ref carrying the runtime id separator is rejected by name")
    void refWithIdSeparatorIsRejectedByName() {
        // agent:ops:acme parses back into a name and a tenant, so a ref with a colon in it produces an id that
        // reads as a tenant runtime of an agent nobody declared. AgentRuntimeId refuses it too, from a static
        // factory that only ever saw the string.
        // The property source is built by hand because withPropertyValues cannot express this input:
        // TestPropertyValues splits each string on the first '=' *or* ':', so a ref containing a colon becomes a
        // truncated name and the test would fail on a binding error instead of reaching the check.
        agentsRunner
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("colon-in-ref", Map.of("aimon.agents[ops:acme].bundle", "test-agent"))))
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.AGENTS)
                        .hasStackTraceContaining("ops:acme"));
    }

    @Test
    @DisplayName("per-agent properties bind as written and reach the entry")
    void perAgentPropertiesBind() {
        // These are the values a customizer matches on — a region, a tenant tier, an integration id. Nothing in
        // the starter reads them, which is exactly why they need a test: a binding that silently produced an
        // empty map would look identical from here.
        agentsRunner
                .withPropertyValues("aimon.agents.ops.bundle=test-agent",
                        "aimon.agents.ops.properties.region=eu-west-1", "aimon.agents.ops.properties.tier=gold")
                .run(ctx -> assertThat(ctx.getBean(AimonProperties.class).getAgents().get("ops").getProperties())
                        .containsEntry("region", "eu-west-1").containsEntry("tier", "gold"));
    }

    @Test
    @DisplayName("the runtime cache settings bind, including the two that stay null to mean \"the default\"")
    void agentRuntimePropertiesBind() {
        runner.withPropertyValues("aimon.agent-runtime.eviction=never", "aimon.agent-runtime.max-entries=12")
                .run(ctx -> {
                    final AimonProperties.AgentRuntimeProperties agentRuntime = ctx.getBean(AimonProperties.class)
                            .getAgentRuntime();
                    assertThat(agentRuntime.getEviction()).isEqualTo(AgentRuntimeEviction.NEVER);
                    assertThat(agentRuntime.getMaxEntries()).isEqualTo(12);
                    assertThat(agentRuntime.getIdleTtl()).isNull();
                    assertThat(agentRuntime.getSweepInterval()).isNull();
                });
    }

    @Test
    @DisplayName("a runtime cache of zero is rejected by name")
    void zeroRuntimeCacheIsRejectedByName() {
        // Zero here would not mean "unlimited"; it would mean the resolver refuses every tenant it is asked for.
        runner.withPropertyValues("aimon.agent-runtime.max-entries=0").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasStackTraceContaining(AimonProperties.AGENT_RUNTIME_MAX_ENTRIES));
    }

    @Test
    @DisplayName("a sweep interval of zero is rejected by name rather than becoming a spinning reaper")
    void zeroSweepIntervalIsRejectedByName() {
        runner.withPropertyValues("aimon.agent-runtime.sweep-interval=0s").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasStackTraceContaining(AimonProperties.AGENT_RUNTIME_SWEEP_INTERVAL));
    }

    @Test
    @DisplayName("all four approval modes bind")
    void everyApprovalModeBinds() {
        // The four are a closed set the starter translates one-to-one into a channel shape; a value that binds
        // here but has no arm in that translation would fail as a switch error further in.
        for (ApprovalMode mode : ApprovalMode.values()) {
            runner.withPropertyValues(AimonProperties.SKILL_APPROVAL_MODE + "=" + AimonProperties.asPropertyValue(mode))
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        assertThat(ctx.getBean(AimonProperties.class).getSkill().getApproval().getMode())
                                .isEqualTo(mode);
                    });
        }
    }

    @Test
    @DisplayName("a pending-turn TTL of zero is rejected by name")
    void zeroPendingTurnTtlIsRejectedByName() {
        // Zero would expire every suspended turn before anything could answer it, which reads as "approvals
        // never work" rather than as a TTL.
        runner.withPropertyValues("aimon.skill.approval.pending-turn-ttl=0s").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasStackTraceContaining(AimonProperties.SKILL_APPROVAL_PENDING_TURN_TTL));
    }

    @Test
    @DisplayName("a blank allow-list entry is rejected by index, not silently allowed to match nothing")
    void blankAllowEntryIsRejectedByName() {
        // A trailing comma binds to an empty string, and an allow-list holding one matches no skill while
        // looking longer than it is.
        runner.withPropertyValues("aimon.skill.approval.mode=allow-list", "aimon.skill.approval.allow=deploy, ")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SKILL_APPROVAL_ALLOW + "[1]"));
    }

    @Test
    @DisplayName("auto-startup under backend=none is refused rather than quietly ignored")
    void autoStartupWithoutAnEngineIsRejectedByName() {
        // Reading it the natural way — "start scheduling with the context" — it is a request that is being
        // declined, and declined silently: there is no engine for it to start and nothing says so. Note the
        // value is the same as the default; what makes it a mistake is the backend, not the boolean.
        runner.withPropertyValues("aimon.scheduling.auto-startup=true").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasStackTraceContaining(AimonProperties.SCHEDULING_AUTO_STARTUP));
    }

    @Test
    @DisplayName("quartz settings under the in-memory backend are refused by name")
    void quartzSettingsUnderInMemoryAreRejectedByName() {
        runner.withPropertyValues("aimon.scheduling.backend=in-memory", "aimon.scheduling.quartz.thread-count=4")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SCHEDULING_QUARTZ_THREAD_COUNT));
    }

    @Test
    @DisplayName("configuring a scheduler AIMON only borrows is refused by name")
    void quartzSettingsUnderABorrowedSchedulerAreRejectedByName() {
        // The one worth failing a startup over. Borrowing is the default, so this is what a first attempt at
        // sizing the pool looks like — and the pool it would be sizing belongs to the application, which
        // configured it through spring.quartz.* and will go on doing so with no sign this was ever asked for.
        runner.withPropertyValues("aimon.scheduling.backend=quartz", "aimon.scheduling.quartz.thread-count=4")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SCHEDULING_QUARTZ_THREAD_COUNT)
                        .hasStackTraceContaining(AimonProperties.SCHEDULING_QUARTZ_USE_APPLICATION_SCHEDULER));
    }

    @Test
    @DisplayName("the flag that selects borrowing is not itself listed as ineffective")
    void selectingBorrowingExplicitlyIsNotRefused() {
        // Spelling out the default has to remain legal — otherwise the way to be explicit about which scheduler
        // is in use would be to say nothing, and the refusal above would name the property that caused it as
        // one of its own victims.
        runner.withPropertyValues("aimon.scheduling.backend=quartz",
                "aimon.scheduling.quartz.use-application-scheduler=true").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("a scheduler AIMON builds itself takes the settings the borrowed one refuses")
    void quartzSettingsAreAcceptedWhenAimonOwnsTheScheduler() {
        runner.withPropertyValues("aimon.scheduling.backend=quartz",
                "aimon.scheduling.quartz.use-application-scheduler=false",
                "aimon.scheduling.quartz.instance-name=aimon-scheduler", "aimon.scheduling.quartz.thread-count=4",
                "aimon.scheduling.quartz.wait-for-jobs-on-shutdown=true").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("a thread pool of zero threads is rejected by name")
    void zeroThreadCountIsRejectedByName() {
        // Quartz accepts it at build time and then never runs a job. A scheduler that is up, holding triggers,
        // and firing nothing is the failure mode this check exists to convert into a startup error.
        runner.withPropertyValues("aimon.scheduling.backend=quartz",
                "aimon.scheduling.quartz.use-application-scheduler=false", "aimon.scheduling.quartz.thread-count=0")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.SCHEDULING_QUARTZ_THREAD_COUNT));
    }

    @Test
    @DisplayName("a value that is not one of the constants is rejected during binding, by name")
    void unknownEnumValueIsRejectedByName() {
        // This is what the six selectors are enums for. `aimon.llm.provider` is a String and cannot get this,
        // which is why it has a startup check of its own.
        runner.withPropertyValues("aimon.session.store=cassandra").run(
                ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.SESSION_STORE));
    }

    @Test
    @DisplayName("relaxed binding accepts the spelling the messages use")
    void relaxedBindingAcceptsKebabCase() {
        // The enum constant is IN_MEMORY; nothing a user types looks like that. The kebab-case spelling is what
        // the YAML examples show and what asPropertyValue renders, so it has to be the one that binds.
        runner.withPropertyValues("aimon.session.store=in-memory")
                .run(ctx -> assertThat(ctx.getBean(AimonProperties.class).getSession().getStore())
                        .isEqualTo(SessionStoreType.IN_MEMORY));
    }

    @Test
    @DisplayName("the kill switch skips validation entirely")
    void disabledSkipsValidation() {
        // aimon.enabled=false has to start from nothing. Validating a configuration nobody is going to act on
        // would make the kill switch conditional on the configuration being valid first.
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("aimon.enabled=false", "aimon.scheduling.backend=quartz")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("a credential profile binds as profile, field, value")
    void credentialsBindAsProfileFieldValue() {
        runner.withPropertyValues("aimon.credentials.jira.username=admin", "aimon.credentials.jira.password=hunter2",
                "aimon.credentials.github.token=ghp_abc").run(ctx -> {
                    assertThat(ctx.getBean(AimonProperties.class).getCredentials())
                            .containsEntry("jira", Map.of("username", "admin", "password", "hunter2"))
                            .containsEntry("github", Map.of("token", "ghp_abc"));
                });
    }

    @Test
    @DisplayName("a profile spelled with a dot is refused, because no reference could name it")
    void aDottedProfileIsRefused() {
        // The reference syntax splits on a dot it requires to appear exactly once, so aimon.credentials[my.jira]
        // binds and is then unreachable. Refused at binding rather than at the tool call it would fail.
        runner.withPropertyValues("aimon.credentials[my.jira].password=hunter2").run(
                ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.CREDENTIALS));
    }

    @Test
    @DisplayName("a field spelled with a dot is refused for the same reason")
    void aDottedFieldIsRefused() {
        runner.withPropertyValues("aimon.credentials.jira[api.key]=hunter2").run(
                ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(AimonProperties.CREDENTIALS));
    }

    @Test
    @DisplayName("a profile with no fields is refused rather than carried as an empty one")
    void anEmptyProfileIsRefused() {
        // Same shape as the tracing settings refused above: it binds, reaches nothing, and the deployment that
        // wrote it believes a credential is configured. The property source is built by hand because the input
        // is `jira: {}` in YAML — an entry whose value is a map, which withPropertyValues can only spell as text.
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("empty-profile", Map.of("aimon.credentials.jira", Map.of()))))
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.CREDENTIALS + ".jira"));
    }

    // ----------------------------------------------------------------------------------------------------------
    // aimon.llm.model-capabilities
    // ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a model capability declaration binds and extends the built-in table")
    void modelCapabilitiesBindAndExtendTheBuiltInTable() {
        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.supports-sampling-parameters=false",
                "aimon.llm.model-capabilities.prod-assistant.lowest-reasoning-effort=low").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties.Llm llm = ctx.getBean(AimonProperties.class).getLlm();
                    assertThat(llm.getModelCapabilities()).containsOnlyKeys("prod-assistant");

                    final ModelCapabilityRegistry registry = AimonProperties.modelCapabilityRegistry(llm);
                    assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
                    assertThat(registry.resolve("prod-assistant").acceptedReasoningEfforts())
                            .containsExactly(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH);
                    // The three flags the entry did not name stay fail-open rather than falling to false, and the
                    // built-in rows are still there.
                    assertThat(registry.resolve("prod-assistant").supportsReasoningTraceRoundTrip()).isFalse();
                    assertThat(registry.resolve("prod-assistant").supportsToolsWithReasoning()).isTrue();
                    assertThat(registry.resolve("gpt-5-mini"))
                            .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-mini"));
                    assertThat(registry.resolve("gpt-5.6-terra"))
                            .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra"));
                });
    }

    @Test
    @DisplayName("a model whose name contains a dot needs bracket notation, and gets it")
    void aDottedModelNameNeedsBracketNotation() {
        // Boot reads an unbracketed dot as a segment separator. Measured here rather than assumed, because the shape
        // of this surface depended on it: if brackets did not preserve the name, the map key could not have been the
        // model name and the entry would have needed a `model:` field of its own.
        runner.withPropertyValues("aimon.llm.model-capabilities[gpt-5.7-x].supports-sampling-parameters=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties.Llm llm = ctx.getBean(AimonProperties.class).getLlm();
                    assertThat(llm.getModelCapabilities()).containsOnlyKeys("gpt-5.7-x");
                    assertThat(AimonProperties.modelCapabilityRegistry(llm).resolve("gpt-5.7-x")
                            .supportsSamplingParameters()).isFalse();
                });

        // And the other half, which is why the documentation says brackets rather than suggesting them: without them
        // the entry does not arrive at all. Nothing reports that -- so this assertion is the report.
        runner.withPropertyValues("aimon.llm.model-capabilities.gpt-5.7-x.supports-sampling-parameters=false")
                .run(ctx -> assertThat(ctx.getBean(AimonProperties.class).getLlm().getModelCapabilities()).isEmpty());
    }

    @Test
    @DisplayName("a map key keeps its case, and the registry is what folds it")
    void mapKeyCaseIsPreservedAndTheRegistryFoldsIt() {
        // Boot rebuilds a map key from the property name's original form, so the case an operator copied out of an
        // Azure portal survives binding -- the same property as aimon.credentials.<profile>. That makes this the
        // starter's half of "case-insensitivity holds on the configuration path": the name arrives upper-case and the
        // registry finds it lower-case, so the fold being tested is genuinely the registry's.
        runner.withPropertyValues("aimon.llm.model-capabilities.Prod-Assistant.supports-sampling-parameters=false")
                .run(ctx -> {
                    final AimonProperties.Llm llm = ctx.getBean(AimonProperties.class).getLlm();
                    assertThat(llm.getModelCapabilities()).containsOnlyKeys("Prod-Assistant");
                    final ModelCapabilityRegistry registry = AimonProperties.modelCapabilityRegistry(llm);
                    assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
                    assertThat(registry.resolve("PROD-ASSISTANT").supportsSamplingParameters()).isFalse();
                });
    }

    @Test
    @DisplayName("a declaration that states nothing is refused by name")
    void anEmptyModelCapabilityDeclarationIsRefused() {
        // The whole reason this surface exists is that a silent no-op produced an HTTP 400, so it does not ship one of
        // its own: an entry that declares nothing registers the capabilities the model already had. The property
        // source is built by hand because the input is `prod-assistant: {}` in YAML, which withPropertyValues can
        // only spell as text.
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("empty-declaration",
                        Map.of("aimon.llm.model-capabilities.prod-assistant", Map.of()))))
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_MODEL_CAPABILITIES));
    }

    @Test
    @DisplayName("the three anthropic thinking keys bind, and an absent block binds empty")
    void theAnthropicBlockBinds() {
        // Binding only. Whether a value is usable is decided in the guarded slice rather than in
        // afterPropertiesSet, because folding the string onto the vendor enum would name a compileOnly type in a
        // method that runs in every deployment -- see AimonProperties.Llm.Anthropic. That asymmetry with
        // model-capabilities is deliberate, and it is why these assertions stop at the bound values.
        runner.withPropertyValues("aimon.llm.anthropic.thinking-mode=Auto",
                "aimon.llm.anthropic.thinking-budget-tokens=4000", "aimon.llm.anthropic.replay-thinking-blocks=false")
                .run(ctx -> {
                    final AimonProperties.Llm.Anthropic anthropic = ctx.getBean(AimonProperties.class).getLlm()
                            .getAnthropic();
                    assertThat(anthropic.getThinkingMode()).isEqualTo("Auto");
                    assertThat(anthropic.getThinkingBudgetTokens()).isEqualTo(4000);
                    assertThat(anthropic.getReplayThinkingBlocks()).isFalse();
                    assertThat(anthropic.isEmpty()).isFalse();
                });

        runner.run(ctx -> assertThat(ctx.getBean(AimonProperties.class).getLlm().getAnthropic().isEmpty()).isTrue());
    }

    @Test
    @DisplayName("a misspelled anthropic key is silent here too, which widens the same limit by three keys")
    void aMisspelledAnthropicKeyIsSilentInTheStarter() {
        // The same limitation record as aMisspelledFlagIsSilentInTheStarter below, on the keys this round adds.
        // Backlog L-1: Boot's ignoreUnknownFields default makes the starter quiet where the CLI's mapper throws.
        // Closing it turns both of these red, which is the correct outcome -- they are records, not guarantees.
        runner.withPropertyValues("aimon.llm.anthropic.thinking-mod=auto").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AimonProperties.class).getLlm().getAnthropic().isEmpty()).isTrue();
        });
    }

    @Test
    @DisplayName("a misspelled flag is silent here, and that limit is measured rather than assumed")
    void aMisspelledFlagIsSilentInTheStarter() {
        // @ConfigurationProperties ignores unknown fields by default, and Boot's JavaBeanBinder does not instantiate
        // a value whose every leaf failed to bind -- so an entry whose only flag is misspelled does not arrive as an
        // empty declaration the check above would catch. It does not arrive at all.
        //
        // This is asserted rather than left implicit because it is the one acceptance criterion this feature does not
        // fully meet on this surface: "invalid configuration does not pass silently" holds for values and for meaning,
        // and not for a misspelled field name. Closing it means either turning off ignoreUnknownFields for the whole
        // aimon.* tree or binding these entries as raw string maps, and both were weighed and declined -- see the
        // design note. The CLI half is noisy for free, because its mapper fails on an unknown property.
        //
        // If someone does close it, this test goes red, which is the correct outcome: it is a limitation record, not
        // a guarantee.
        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.supports-sampling-parameter=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AimonProperties.class).getLlm().getModelCapabilities()).isEmpty();
                });
    }

    @Test
    @DisplayName("a ladder written as a set binds, in both spellings relaxed binding accepts")
    void acceptedReasoningEffortsBinds() {
        // The general ladder form. Boot takes a comma-separated scalar and an indexed list onto the same
        // List<ReasoningEffort>, and this asserts both rather than assuming: the empty-ladder refusal downstream
        // depends on an unwritten key staying null rather than materialising an empty list, and that is only
        // observable if the written forms are known to arrive.
        runner.withPropertyValues(
                "aimon.llm.model-capabilities.prod-assistant.accepted-reasoning-efforts=none,low,medium,high")
                .run(ctx -> assertThat(
                        AimonProperties.modelCapabilityRegistry(ctx.getBean(AimonProperties.class).getLlm())
                                .resolve("prod-assistant").acceptedReasoningEfforts())
                        .containsExactly(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                                ReasoningEffort.HIGH));

        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.accepted-reasoning-efforts[0]=NONE",
                "aimon.llm.model-capabilities.prod-assistant.accepted-reasoning-efforts[1]=high")
                .run(ctx -> assertThat(
                        AimonProperties.modelCapabilityRegistry(ctx.getBean(AimonProperties.class).getLlm())
                                .resolve("prod-assistant").acceptedReasoningEfforts())
                        .containsExactly(ReasoningEffort.NONE, ReasoningEffort.HIGH));
    }

    @Test
    @DisplayName("an empty element in the ladder fails the context, naming the property and the position")
    void aNullRungFailsTheContext() {
        // #70's starter half, and also the measurement behind the premise. The comma-delimited spelling is the one
        // that reaches rungSet() with a hole in the list: commaDelimitedListToStringArray keeps the empty token and
        // relaxed conversion turns it into a null element, for an interior comma and for a trailing one alike. If
        // the binder had rejected it earlier these would fail by NOT failing, which is the signal -- and the third
        // case below is exactly that outcome for the other spelling.
        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.accepted-reasoning-efforts=none,,high")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_MODEL_CAPABILITIES)
                        .hasStackTraceContaining("accepted-reasoning-efforts[1]")
                        .hasStackTraceContaining("has no value"));

        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.accepted-reasoning-efforts=none,high,")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining("accepted-reasoning-efforts[2] has no value"));

        // The indexed spelling never reaches this check, and that is Boot's doing rather than ours: an index whose
        // value is empty is not converted to null but left UNBOUND, and IndexedElementsBinder then refuses that index
        // and every one after it -- "The elements [...[1],...[2]] were left unbound." So this spelling of the mistake
        // was already loud. Recorded rather than asserted through our message, because asserting our own text here
        // would claim a path this surface does not take.
        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.accepted-reasoning-efforts[0]=none",
                "aimon.llm.model-capabilities.prod-assistant.accepted-reasoning-efforts[1]=",
                "aimon.llm.model-capabilities.prod-assistant.accepted-reasoning-efforts[2]=high")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining("left unbound")
                        .hasStackTraceContaining("accepted-reasoning-efforts[1]"));
    }

    @Test
    @DisplayName("the thinking dialect binds, folds case, and reaches the descriptor a client reads")
    void theThinkingDialectBinds() {
        // #69's starter half. The enum binds directly -- ThinkingDialect is at.aimon.core.llm.capability, which
        // noPropertiesSignatureNamesAVendorType allows -- so relaxed binding folds case and the configuration
        // processor records the type, the same arrangement lowest-reasoning-effort already has.
        for (String written : new String[]{"adaptive", "ADAPTIVE", "Adaptive"}) {
            runner.withPropertyValues("aimon.llm.model-capabilities.prod-claude.thinking-dialect=" + written)
                    .run(ctx -> assertThat(
                            AimonProperties.modelCapabilityRegistry(ctx.getBean(AimonProperties.class).getLlm())
                                    .resolve("prod-claude").thinkingDialect())
                            .as("written as %s", written).isEqualTo(ThinkingDialect.ADAPTIVE));
        }
    }

    @Test
    @DisplayName("a declared reasoning-summary refusal binds and reaches the descriptor a client reads")
    void theReasoningSummaryFlagBinds() {
        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.supports-reasoning-summary=false").run(
                ctx -> assertThat(AimonProperties.modelCapabilityRegistry(ctx.getBean(AimonProperties.class).getLlm())
                        .resolve("prod-assistant").supportsReasoningSummary()).isFalse());
    }

    @Test
    @DisplayName("a declaration replaces the built-in row for its name, and the full form is what keeps it")
    void aDeclarationReplacesTheBuiltInRowRatherThanPatchingIt() {
        // The starter half of the pair in LlmClientFactoryTest, so neither surface can drift from the yaml the two
        // operator guides print. An entry is the whole row: naming only the dialect for a claude-* name hands the
        // sampling suppression back at its fail-open true, and restating it is what the guides now prescribe.
        // The mechanism is #46's; the general remedy is L-8.
        runner.withPropertyValues("aimon.llm.model-capabilities.claude-sonnet-5.thinking-dialect=unknown").run(
                ctx -> assertThat(AimonProperties.modelCapabilityRegistry(ctx.getBean(AimonProperties.class).getLlm())
                        .resolve("claude-sonnet-5").supportsSamplingParameters()).isTrue());

        runner.withPropertyValues("aimon.llm.model-capabilities.claude-sonnet-5.thinking-dialect=unknown",
                "aimon.llm.model-capabilities.claude-sonnet-5.supports-sampling-parameters=false")
                .run(ctx -> assertThat(
                        AimonProperties.modelCapabilityRegistry(ctx.getBean(AimonProperties.class).getLlm())
                                .resolve("claude-sonnet-5").supportsSamplingParameters())
                        .isFalse());
    }

    @Test
    @DisplayName("an entry that writes no ladder key at all still binds — the empty-set refusal needs that")
    void anUnwrittenLadderKeyStaysUndeclared() {
        // The binder assumption the empty-ladder refusal rests on: an unwritten List property is left null rather
        // than materialised empty. If Boot materialised one, every declaration that never mentioned the ladder
        // would be refused as stating an empty one.
        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.supports-sampling-parameters=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AimonProperties.class).getLlm().getModelCapabilities().get("prod-assistant")
                            .getAcceptedReasoningEfforts()).isNull();
                });
    }

    @Test
    @DisplayName("stating both ladder keys fails the context, naming the property path")
    void bothLadderKeysFailTheContext() {
        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.lowest-reasoning-effort=low",
                "aimon.llm.model-capabilities.prod-assistant.accepted-reasoning-efforts=none,high")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_MODEL_CAPABILITIES)
                        .hasStackTraceContaining("acceptedReasoningEfforts"));
    }

    @Test
    @DisplayName("an unusable reasoning-effort value fails at binding with the accepted values")
    void anInvalidReasoningEffortFailsAtBinding() {
        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.lowest-reasoning-effort=lowish")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining("aimon.llm.model-capabilities"));
    }

    @Test
    @DisplayName("a reasoning-effort value is not case-sensitive")
    void reasoningEffortIsRelaxed() {
        runner.withPropertyValues("aimon.llm.model-capabilities.prod-assistant.lowest-reasoning-effort=LOW")
                .run(ctx -> assertThat(ctx.getBean(AimonProperties.class).getLlm().getModelCapabilities()
                        .get("prod-assistant").getLowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW));
    }

    @Test
    @DisplayName("aimon.llm.reasoning-effort binds the enum, folds case, and fails loudly on a bad value")
    void theSharedReasoningEffortKeyBinds() {
        // The enum itself rather than a String -- ReasoningEffort is an aimon-core type, a hard dependency here, so
        // the NoClassDefFoundError reasoning that made thinking-mode a String does not apply. Relaxed binding folds
        // case for free as a consequence, which is the half asserted here.
        for (String written : new String[]{"medium", "MEDIUM", "Medium"}) {
            runner.withPropertyValues(AimonProperties.LLM_REASONING_EFFORT + "=" + written)
                    .run(ctx -> assertThat(ctx.getBean(AimonProperties.class).getLlm().getReasoningEffort())
                            .as("written as %s", written).isEqualTo(ReasoningEffort.MEDIUM));
        }

        runner.withPropertyValues(AimonProperties.LLM_REASONING_EFFORT + "=mediumish").run(ctx -> assertThat(ctx)
                .hasFailed().getFailure().hasStackTraceContaining(AimonProperties.LLM_REASONING_EFFORT));

        runner.run(ctx -> assertThat(ctx.getBean(AimonProperties.class).getLlm().getReasoningEffort()).isNull());
    }

    @Test
    @DisplayName("a misspelled aimon.llm.reasoning-effort is silent here too — the same limitation, one key wider")
    void aMisspelledReasoningEffortIsSilentInTheStarter() {
        // The third limitation record of the same shape, and the one that widens
        // docs/backlog/llm-config-surface-open-items.md item L-1 by a fourth key. Its two siblings pin a
        // map-of-object leaf and a nested-object leaf; this one pins a SCALAR leaf on a bean that binds regardless
        // of this key -- so nothing here fails to instantiate and the misspelling simply leaves the field null.
        //
        // The CLI throws for the same typo. If someone closes L-1, this test goes red with its two siblings, which
        // is the correct outcome: it is a limitation record, not a guarantee.
        runner.withPropertyValues("aimon.llm.reasoning-effor=medium").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AimonProperties.class).getLlm().getReasoningEffort()).isNull();
        });
    }

    @Test
    @DisplayName("two names differing only in case are refused")
    void namesDifferingOnlyInCaseAreRefused() {
        // Both bind, and look-ups fold case, so one of them would answer for the other with nothing to say which.
        // Reachable without brackets because Boot preserves the key's case.
        runner.withPropertyValues("aimon.llm.model-capabilities.Prod.supports-sampling-parameters=false",
                "aimon.llm.model-capabilities.prod.supports-sampling-parameters=true")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(AimonProperties.LLM_MODEL_CAPABILITIES));
    }

    @Test
    @DisplayName("enum values are rendered the way they are written in a properties file")
    void enumsRenderAsPropertyValues() {
        assertThat(AimonProperties.asPropertyValue(SessionStoreType.IN_MEMORY)).isEqualTo("in-memory");
        assertThat(AimonProperties.asPropertyValue(DeploymentMode.SINGLE_NODE)).isEqualTo("single-node");
        assertThat(AimonProperties.asPropertyValue(ApprovalMode.ALLOW_LIST)).isEqualTo("allow-list");
        assertThat(AimonProperties.asPropertyValue(PayloadCapture.FULL)).isEqualTo("full");
        assertThat(AimonProperties.asPropertyValue(AgentRuntimeEviction.IDLE)).isEqualTo("idle");
    }

    /** The binding target on its own — no slice, no stack, nothing that could fail for another reason. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AimonProperties.class)
    static class PropertiesOnly {
    }
}
