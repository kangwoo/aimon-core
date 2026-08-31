package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.command.OrcaCommandProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.scheduling.ScheduledTaskManager;

@DisplayName("OrcaAgentRuntimeManager Tests")
@ExtendWith(MockitoExtension.class)
class OrcaAgentRuntimeManagerTest {

    @Mock
    private OrcaAgentExecutor agentExecutor;

    @Mock
    private OrcaAgentRuntimeFactory agentRuntimeFactory;

    @Mock
    private ScheduledTaskManager scheduledTaskManager;

    @Mock
    private VirtualFileSystem fileSystem;

    @Mock
    private AgentBundle agentBundle;

    @Mock
    private Agent agent;

    private AgentRuntimeRegistry agentRuntimeRegistry;
    private OrcaAgentRuntimeManager manager;

    @BeforeEach
    void setUp() {
        agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        manager = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                .agentRuntimeRegistry(agentRuntimeRegistry).agentRuntimeFactory(agentRuntimeFactory)
                .scheduledTaskManager(scheduledTaskManager).toolProviders(List.of()).commandProviders(List.of())
                .build();
        lenient().when(agentBundle.getAgent()).thenReturn(agent);
        lenient().when(agent.getName()).thenReturn("test-agent");
    }

    /** Returns the agent-derived id for the default {@code agentBundle}/{@code agent} mocks. */
    private AgentRuntimeId expectedId() {
        return AgentRuntimeId.from(agent);
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should fail when agentExecutor is null")
        void shouldFailWhenAgentExecutorIsNull() {
            assertThatThrownBy(() -> OrcaAgentRuntimeManager.builder().agentRuntimeRegistry(agentRuntimeRegistry)
                    .agentRuntimeFactory(agentRuntimeFactory).build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Agent executor");
        }

        @Test
        @DisplayName("Should use default agentRuntimeRegistry when not specified")
        void shouldUseDefaultContextRegistryWhenNotSpecified() {
            OrcaAgentRuntimeManager defaultManager = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeFactory(agentRuntimeFactory).build();

            assertThat(defaultManager).isNotNull();
        }

        @Test
        @DisplayName("Should use default agentRuntimeFactory when not specified")
        void shouldUseDefaultContextFactoryWhenNotSpecified() {
            OrcaAgentRuntimeManager defaultManager = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeRegistry(agentRuntimeRegistry).build();

            assertThat(defaultManager).isNotNull();
        }

        @Test
        @DisplayName("Should use all defaults when only agentExecutor is specified")
        void shouldUseAllDefaultsWhenOnlyAgentExecutorSpecified() {
            OrcaAgentRuntimeManager defaultManager = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .build();

            assertThat(defaultManager).isNotNull();
        }

        @Test
        @DisplayName("Should use default tool providers when not specified")
        void shouldUseDefaultToolProvidersWhenNotSpecified() {
            OrcaAgentRuntimeManager defaultManager = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeRegistry(agentRuntimeRegistry).agentRuntimeFactory(agentRuntimeFactory).build();

            assertThat(defaultManager).isNotNull();
        }

        @Test
        @DisplayName("Should accept null scheduledTaskManager")
        void shouldAcceptNullScheduledTaskManager() {
            OrcaAgentRuntimeManager nullSchedulerManager = OrcaAgentRuntimeManager.builder()
                    .agentExecutor(agentExecutor).agentRuntimeRegistry(agentRuntimeRegistry)
                    .agentRuntimeFactory(agentRuntimeFactory).scheduledTaskManager(null).build();

            assertThat(nullSchedulerManager).isNotNull();
        }
    }

    @Nested
    @DisplayName("getOrCreateRuntime")
    class GetOrCreateContextTests {

        @Test
        @DisplayName("Should create new context when none exists")
        void shouldCreateNewContextWhenNoneExists() {
            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime expectedContext = createMockContext(agentRuntimeId);

            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), eq(scheduledTaskManager),
                    eq(agentBundle), eq(fileSystem), any(), any(), any())).thenReturn(expectedContext);

            OrcaAgentRuntime result = manager.getOrCreateRuntime(agentBundle, fileSystem, null);

            assertThat(result).isSameAs(expectedContext);
            assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isPresent().containsSame(expectedContext);
            verify(agentRuntimeFactory).create(eq(agentRuntimeId), eq(agentExecutor), eq(scheduledTaskManager),
                    eq(agentBundle), eq(fileSystem), any(), any(), any());
        }

        @Test
        @DisplayName("Should return existing context without creating new one")
        void shouldReturnExistingContextWithoutCreatingNewOne() {
            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime existingContext = createMockContext(agentRuntimeId);

            agentRuntimeRegistry.register(existingContext);

            OrcaAgentRuntime result = manager.getOrCreateRuntime(agentBundle, fileSystem, null);

            assertThat(result).isSameAs(existingContext);
            verify(agentRuntimeFactory, never()).create(any(), any(), any(), any(AgentBundle.class), any(), any(),
                    any(), any());
        }

        @Test
        @DisplayName("Should invoke hook registrars on new context creation")
        void shouldInvokeHookRegistrarsOnNewContextCreation() {
            AgentRuntimeHookRegistrar registrar1 = mock(AgentRuntimeHookRegistrar.class);
            AgentRuntimeHookRegistrar registrar2 = mock(AgentRuntimeHookRegistrar.class);

            OrcaAgentRuntimeManager managerWithHooks = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeRegistry(agentRuntimeRegistry).agentRuntimeFactory(agentRuntimeFactory)
                    .scheduledTaskManager(scheduledTaskManager).hookRegistrars(List.of(registrar1, registrar2))
                    .toolProviders(List.of()).commandProviders(List.of()).build();

            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime newContext = createMockContext(agentRuntimeId);
            HookRegistry hookRegistry = newContext.getHookRegistry();

            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), eq(scheduledTaskManager),
                    eq(agentBundle), eq(fileSystem), any(), any(), any())).thenReturn(newContext);

            managerWithHooks.getOrCreateRuntime(agentBundle, fileSystem, null);

            verify(registrar1).register(hookRegistry);
            verify(registrar2).register(hookRegistry);
        }

        @Test
        @DisplayName("Should not invoke hook registrars for existing context")
        void shouldNotInvokeHookRegistrarsForExistingContext() {
            AgentRuntimeHookRegistrar registrar = mock(AgentRuntimeHookRegistrar.class);

            OrcaAgentRuntimeManager managerWithHooks = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeRegistry(agentRuntimeRegistry).agentRuntimeFactory(agentRuntimeFactory)
                    .hookRegistrars(List.of(registrar)).toolProviders(List.of()).commandProviders(List.of()).build();

            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime existingContext = createMockContext(agentRuntimeId);

            agentRuntimeRegistry.register(existingContext);

            managerWithHooks.getOrCreateRuntime(agentBundle, fileSystem, null);

            verify(registrar, never()).register(any());
        }

        @Test
        @DisplayName("Should register hook registrars exactly once across N invocations for the same agent")
        void shouldRegisterHookRegistrarsExactlyOnceAcrossInvocations() {
            AgentRuntimeHookRegistrar registrar = mock(AgentRuntimeHookRegistrar.class);

            OrcaAgentRuntimeManager managerWithHooks = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeRegistry(agentRuntimeRegistry).agentRuntimeFactory(agentRuntimeFactory)
                    .scheduledTaskManager(scheduledTaskManager).hookRegistrars(List.of(registrar))
                    .toolProviders(List.of()).commandProviders(List.of()).build();

            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime newContext = createMockContext(agentRuntimeId);

            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), eq(scheduledTaskManager),
                    eq(agentBundle), eq(fileSystem), any(), any(), any())).thenReturn(newContext);

            for (int i = 0; i < 5; i++) {
                managerWithHooks.getOrCreateRuntime(agentBundle, fileSystem, null);
            }

            verify(registrar).register(newContext.getHookRegistry());
        }

        @Test
        @DisplayName("getOrCreateRuntime(bundle, discriminator, ...) derives composite id")
        void shouldDeriveCompositeIdWithDiscriminator() {
            AgentRuntimeId tenantA = AgentRuntimeId.from(agent, "tenant-a");
            AgentRuntimeId tenantB = AgentRuntimeId.from(agent, "tenant-b");
            OrcaAgentRuntime ctxA = createMockContext(tenantA);
            OrcaAgentRuntime ctxB = createMockContext(tenantB);

            when(agentRuntimeFactory.create(eq(tenantA), eq(agentExecutor), eq(scheduledTaskManager), eq(agentBundle),
                    eq(fileSystem), any(), any(), any())).thenReturn(ctxA);
            when(agentRuntimeFactory.create(eq(tenantB), eq(agentExecutor), eq(scheduledTaskManager), eq(agentBundle),
                    eq(fileSystem), any(), any(), any())).thenReturn(ctxB);

            OrcaAgentRuntime a = manager.getOrCreateRuntime(agentBundle, "tenant-a", fileSystem, null);
            OrcaAgentRuntime b = manager.getOrCreateRuntime(agentBundle, "tenant-b", fileSystem, null);

            assertThat(a).isSameAs(ctxA);
            assertThat(b).isSameAs(ctxB);
            assertThat(a).isNotSameAs(b);
        }

        @Test
        @DisplayName("Should throw NullPointerException when agentBundle is null")
        void shouldThrowWhenAgentBundleIsNull() {
            assertThatThrownBy(() -> manager.getOrCreateRuntime(null, fileSystem, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Agent bundle");
        }

        @Test
        @DisplayName("Should throw NullPointerException when fileSystem is null")
        void shouldThrowWhenFileSystemIsNull() {
            assertThatThrownBy(() -> manager.getOrCreateRuntime(agentBundle, null, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("File system");
        }
    }

    @Nested
    @DisplayName("getOrCreateRuntime concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("Should create context only once for same agent under concurrent access")
        void shouldCreateContextOnlyOnceForSameAgent() throws Exception {
            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime newContext = createMockContext(agentRuntimeId);
            AtomicInteger createCount = new AtomicInteger(0);

            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), eq(scheduledTaskManager),
                    eq(agentBundle), eq(fileSystem), any(), any(), any())).thenAnswer(invocation -> {
                        createCount.incrementAndGet();
                        Thread.sleep(50);
                        return newContext;
                    });

            int threadCount = 5;
            CountDownLatch startLatch = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                List<Future<OrcaAgentRuntime>> futures = new ArrayList<>();

                for (int i = 0; i < threadCount; i++) {
                    futures.add(executor.submit(() -> {
                        startLatch.await();
                        return manager.getOrCreateRuntime(agentBundle, fileSystem, null);
                    }));
                }

                startLatch.countDown();

                List<OrcaAgentRuntime> results = new ArrayList<>();
                for (Future<OrcaAgentRuntime> future : futures) {
                    results.add(future.get());
                }

                assertThat(results).allSatisfy(ctx -> assertThat(ctx).isSameAs(newContext));
                assertThat(createCount.get()).isEqualTo(1);
            } finally {
                executor.shutdown();
            }
        }

        @Test
        @DisplayName("Should allow parallel creation for different discriminators")
        void shouldAllowParallelCreationForDifferentDiscriminators() throws Exception {
            int contextCount = 3;
            List<AgentRuntimeId> contextIds = new ArrayList<>();
            List<OrcaAgentRuntime> mockContexts = new ArrayList<>();
            List<String> discriminators = new ArrayList<>();

            for (int i = 0; i < contextCount; i++) {
                String disc = "tenant-" + i;
                AgentRuntimeId id = AgentRuntimeId.from(agent, disc);
                OrcaAgentRuntime ctx = createMockContext(id);

                contextIds.add(id);
                discriminators.add(disc);
                mockContexts.add(ctx);

                when(agentRuntimeFactory.create(eq(id), eq(agentExecutor), eq(scheduledTaskManager), eq(agentBundle),
                        eq(fileSystem), any(), any(), any())).thenReturn(ctx);
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(contextCount);
            try {
                List<Future<OrcaAgentRuntime>> futures = new ArrayList<>();

                for (int i = 0; i < contextCount; i++) {
                    final int index = i;
                    futures.add(executor.submit(() -> {
                        startLatch.await();
                        return manager.getOrCreateRuntime(agentBundle, discriminators.get(index), fileSystem, null);
                    }));
                }

                startLatch.countDown();

                List<OrcaAgentRuntime> results = new ArrayList<>();
                for (Future<OrcaAgentRuntime> future : futures) {
                    results.add(future.get());
                }

                for (int i = 0; i < contextCount; i++) {
                    assertThat(results.get(i)).isSameAs(mockContexts.get(i));
                    assertThat(agentRuntimeRegistry.get(contextIds.get(i))).isPresent();
                }
            } finally {
                executor.shutdown();
            }
        }

        /**
         * B-3 — a creator parked on the per-id monitor must not end up holding a monitor that the map no longer
         * hands out.
         *
         * <p>
         * {@code destroyRuntime} retires the monitor while holding it, and it has to: that map is keyed by runtime
         * id, so on the tenant axis it is the only place that ever shrinks. But the next caller's
         * {@code computeIfAbsent} then mints a <b>different</b> object for the same id, and two threads holding two
         * different monitors are not mutually excluded at all. The interleaving this test stages is the reachable
         * one — a creator does not race the destroyer for a few nanoseconds, it <i>waits behind it</i> for however
         * long {@code close()} takes (MCP shutdown, seconds), and anything arriving in that window gets the fresh
         * monitor.
         *
         * <p>
         * What the second creation costs is not a wasted call: {@code DefaultAgentRuntimeRegistry.register} is a
         * plain {@code put}, so the loser is silently overwritten and <b>nobody ever closes it</b> — an agent
         * runtime holding live MCP clients, orphaned by the very method whose job is releasing them.
         *
         * <p>
         * The factory here is hand-written rather than mocked on purpose. Mockito serialises the answers of one
         * stubbing behind a monitor, so a mocked factory would hide exactly the concurrency this test is about.
         */
        @Test
        @DisplayName("Should not create twice when destroy retires the monitor a creator is parked on")
        void shouldNotCreateTwiceWhenDestroyRetiresTheMonitorACreatorIsParkedOn() throws Exception {
            final AgentRuntimeId agentRuntimeId = expectedId();
            final OrcaAgentRuntime doomed = createMockContext(agentRuntimeId);
            final OrcaAgentRuntime recreated = createMockContext(agentRuntimeId);
            final OrcaAgentRuntime interloper = mock(OrcaAgentRuntime.class);
            lenient().when(interloper.getId()).thenReturn(agentRuntimeId);

            final CountDownLatch closeEntered = new CountDownLatch(1);
            final CountDownLatch letCloseReturn = new CountDownLatch(1);
            doAnswer(invocation -> {
                closeEntered.countDown();
                letCloseReturn.await();
                return null;
            }).when(doomed).close();

            final GatedRuntimeFactory factory = new GatedRuntimeFactory(List.of(recreated, interloper));
            final OrcaAgentRuntimeManager gated = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeRegistry(agentRuntimeRegistry).agentRuntimeFactory(factory)
                    .scheduledTaskManager(scheduledTaskManager).toolProviders(List.of()).commandProviders(List.of())
                    .build();
            agentRuntimeRegistry.register(doomed);

            final Thread destroyer = new Thread(() -> gated.destroyRuntime(agentRuntimeId), "b3-destroyer");
            final Thread parked = new Thread(() -> gated.getOrCreateRuntime(agentBundle, fileSystem, null),
                    "b3-parked");
            final Thread late = new Thread(() -> gated.getOrCreateRuntime(agentBundle, fileSystem, null), "b3-late");
            try {
                destroyer.start();
                assertThat(closeEntered.await(5, TimeUnit.SECONDS)).as("destroy must reach close()").isTrue();

                parked.start();
                awaitParkedOnAMonitor(parked);

                letCloseReturn.countDown();
                assertThat(factory.entered.await(5, TimeUnit.SECONDS))
                        .as("the parked creator must get in once destroy releases the monitor").isTrue();

                // The late creator now asks for the same id while the first creation is still in flight. Either it
                // parks on the same monitor (correct) or it walks straight into a second creation (the defect).
                late.start();
                awaitSecondCreationOrPark(late, factory);

                factory.release.countDown();
                joinAll(destroyer, parked, late);

                assertThat(factory.creations.get()).as("one id must never be created twice concurrently").isEqualTo(1);
                assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isPresent().containsSame(recreated);
            } finally {
                letCloseReturn.countDown();
                factory.release.countDown();
                joinAll(destroyer, parked, late);
            }
        }
    }

    @Nested
    @DisplayName("getContext")
    class GetContextTests {

        @Test
        @DisplayName("Should return context when it exists")
        void shouldReturnContextWhenExists() {
            AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:existing");
            OrcaAgentRuntime existingContext = createMockContext(agentRuntimeId);

            agentRuntimeRegistry.register(existingContext);

            Optional<OrcaAgentRuntime> result = manager.getRuntime(agentRuntimeId);

            assertThat(result).isPresent().containsSame(existingContext);
        }

        @Test
        @DisplayName("Should return empty when context does not exist")
        void shouldReturnEmptyWhenContextDoesNotExist() {
            AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:non-existent");

            Optional<OrcaAgentRuntime> result = manager.getRuntime(agentRuntimeId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should throw NullPointerException when agentRuntimeId is null")
        void shouldThrowWhenRuntimeIdIsNull() {
            assertThatThrownBy(() -> manager.getRuntime(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Agent runtime ID");
        }
    }

    @Nested
    @DisplayName("destroyRuntime")
    class DestroyContextTests {

        @Test
        @DisplayName("Should unregister and close existing context")
        void shouldUnregisterAndCloseExistingContext() {
            AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:to-destroy");
            OrcaAgentRuntime existingContext = createMockContext(agentRuntimeId);

            agentRuntimeRegistry.register(existingContext);

            manager.destroyRuntime(agentRuntimeId);

            assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isEmpty();
            verify(existingContext).close();
        }

        @Test
        @DisplayName("Should do nothing when context does not exist")
        void shouldDoNothingWhenContextDoesNotExist() {
            AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:non-existent");

            manager.destroyRuntime(agentRuntimeId);

            assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isEmpty();
        }

        @Test
        @DisplayName("Should complete unregister even when close throws exception")
        void shouldCompleteUnregisterEvenWhenCloseThrows() {
            AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:close-fails");
            OrcaAgentRuntime failingContext = createMockContext(agentRuntimeId);
            doThrow(new RuntimeException("close error")).when(failingContext).close();

            agentRuntimeRegistry.register(failingContext);

            manager.destroyRuntime(agentRuntimeId);

            assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isEmpty();
            verify(failingContext).close();
        }

        @Test
        @DisplayName("Should allow re-creation after destroy")
        void shouldAllowReCreationAfterDestroy() {
            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime firstContext = createMockContext(agentRuntimeId);
            OrcaAgentRuntime secondContext = createMockContext(agentRuntimeId);

            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), eq(scheduledTaskManager),
                    eq(agentBundle), eq(fileSystem), any(), any(), any())).thenReturn(firstContext)
                    .thenReturn(secondContext);

            OrcaAgentRuntime created = manager.getOrCreateRuntime(agentBundle, fileSystem, null);
            assertThat(created).isSameAs(firstContext);

            manager.destroyRuntime(agentRuntimeId);
            assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isEmpty();

            OrcaAgentRuntime recreated = manager.getOrCreateRuntime(agentBundle, fileSystem, null);
            assertThat(recreated).isSameAs(secondContext);
            assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isPresent().containsSame(secondContext);
        }

        @Test
        @DisplayName("Should throw NullPointerException when agentRuntimeId is null")
        void shouldThrowWhenRuntimeIdIsNull() {
            assertThatThrownBy(() -> manager.destroyRuntime(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Agent runtime ID");
        }

        /**
         * B-3's other half. Retiring the per-id monitor is what makes the mutual-exclusion problem hard, so the
         * tempting fix is to stop retiring it — but that map is keyed by runtime id and destroy is the only place it
         * ever shrinks, so on the tenant axis it would then grow without bound. Both halves have to hold at once.
         */
        @Test
        @DisplayName("Should not retain per-id monitors for destroyed runtimes")
        void shouldNotRetainLocksForDestroyedRuntimes() {
            final List<AgentRuntimeId> tenantIds = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final String discriminator = "tenant-" + i;
                final AgentRuntimeId tenantId = AgentRuntimeId.from(agent, discriminator);
                final OrcaAgentRuntime tenantRuntime = createMockContext(tenantId);
                tenantIds.add(tenantId);
                when(agentRuntimeFactory.create(eq(tenantId), eq(agentExecutor), eq(scheduledTaskManager),
                        eq(agentBundle), eq(fileSystem), any(), any(), any())).thenReturn(tenantRuntime);
                manager.getOrCreateRuntime(agentBundle, discriminator, fileSystem, null);
            }

            assertThat(manager.trackedLockCount()).as("one monitor per live runtime").isEqualTo(3);

            tenantIds.forEach(manager::destroyRuntime);

            assertThat(manager.trackedLockCount()).as("a destroyed runtime must not leave its monitor behind").isZero();
        }
    }

    @Nested
    @DisplayName("Hook registrar integration")
    class HookRegistrarIntegrationTests {

        @Test
        @DisplayName("Should work with empty hook registrar list")
        void shouldWorkWithEmptyHookRegistrarList() {
            OrcaAgentRuntimeManager emptyHooksManager = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeRegistry(agentRuntimeRegistry).agentRuntimeFactory(agentRuntimeFactory)
                    .hookRegistrars(Collections.emptyList()).toolProviders(List.of()).commandProviders(List.of())
                    .build();

            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime newContext = createMockContext(agentRuntimeId);

            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), any(), eq(agentBundle),
                    eq(fileSystem), any(), any(), any())).thenReturn(newContext);

            OrcaAgentRuntime result = emptyHooksManager.getOrCreateRuntime(agentBundle, fileSystem, null);

            assertThat(result).isSameAs(newContext);
            assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isPresent();
        }

        @Test
        @DisplayName("Should invoke lambda hook registrar")
        void shouldInvokeLambdaHookRegistrar() {
            List<HookRegistry> capturedRegistries = new ArrayList<>();
            AgentRuntimeHookRegistrar lambdaRegistrar = capturedRegistries::add;

            OrcaAgentRuntimeManager lambdaManager = OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeRegistry(agentRuntimeRegistry).agentRuntimeFactory(agentRuntimeFactory)
                    .hookRegistrars(List.of(lambdaRegistrar)).toolProviders(List.of()).commandProviders(List.of())
                    .build();

            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime newContext = createMockContext(agentRuntimeId);
            HookRegistry hookRegistry = newContext.getHookRegistry();

            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), any(), eq(agentBundle),
                    eq(fileSystem), any(), any(), any())).thenReturn(newContext);

            lambdaManager.getOrCreateRuntime(agentBundle, fileSystem, null);

            assertThat(capturedRegistries).hasSize(1).containsExactly(hookRegistry);
        }
    }

    /**
     * Creation is a three-step sequence — {@code factory.create} → run every {@code hookRegistrar} → {@code
     * registry.register} — with no rollback between the steps. These tests pin what an application observes when one
     * of the steps blows up, because the failure mode differs sharply by step: a factory failure leaves nothing behind,
     * whereas a registrar failure abandons a fully-constructed context that owns live resources.
     */
    @Nested
    @DisplayName("Creation failure paths")
    class CreationFailurePathTests {

        private OrcaAgentRuntimeManager managerWith(AgentRuntimeHookRegistrar... registrars) {
            return OrcaAgentRuntimeManager.builder().agentExecutor(agentExecutor)
                    .agentRuntimeRegistry(agentRuntimeRegistry).agentRuntimeFactory(agentRuntimeFactory)
                    .scheduledTaskManager(scheduledTaskManager).hookRegistrars(List.of(registrars))
                    .toolProviders(List.of()).commandProviders(List.of()).build();
        }

        /**
         * Like {@link #createMockContext} but lenient: on the failure paths the context is abandoned before
         * registration, so {@code getId()} is legitimately never called.
         */
        private OrcaAgentRuntime abandonableContext(AgentRuntimeId agentRuntimeId) {
            OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
            lenient().when(context.getId()).thenReturn(agentRuntimeId);
            lenient().when(context.getHookRegistry()).thenReturn(mock(HookRegistry.class));
            return context;
        }

        private void stubFactory(AgentRuntimeId agentRuntimeId, OrcaAgentRuntime context) {
            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), eq(scheduledTaskManager), eq(agentBundle),
                    eq(fileSystem), any(), any(), any())).thenReturn(context);
        }

        @Test
        @DisplayName("A factory failure propagates and leaves the registry untouched")
        void factoryFailurePropagatesAndRegistryStaysEmpty() {
            AgentRuntimeId agentRuntimeId = expectedId();
            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), eq(scheduledTaskManager),
                    eq(agentBundle), eq(fileSystem), any(), any(), any()))
                    .thenThrow(new IllegalStateException("factory boom"));

            assertThatThrownBy(() -> manager.getOrCreateRuntime(agentBundle, fileSystem, null))
                    .isInstanceOf(IllegalStateException.class).hasMessage("factory boom");

            // Nothing was constructed, so there is nothing to leak — the registry must not hold a half-built entry.
            assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isEmpty();
        }

        @Test
        @DisplayName("A hook-registrar failure propagates, and the constructed context is neither registered nor "
                + "closed — it is abandoned with its agent-scoped resources still open")
        void hookRegistrarFailureAbandonsTheConstructedContext() {
            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime newContext = abandonableContext(agentRuntimeId);
            stubFactory(agentRuntimeId, newContext);

            AgentRuntimeHookRegistrar failing = mock(AgentRuntimeHookRegistrar.class);
            doThrow(new IllegalStateException("registrar boom")).when(failing).register(any());

            assertThatThrownBy(() -> managerWith(failing).getOrCreateRuntime(agentBundle, fileSystem, null))
                    .isInstanceOf(IllegalStateException.class).hasMessage("registrar boom");

            assertThat(agentRuntimeRegistry.get(agentRuntimeId))
                    .as("a context that failed registration must not be published").isEmpty();

            // Characterisation of a real leak: getOrCreateInternal has already built the context (which by then owns an
            // McpClientManager and possibly a WorkflowRunner with live thread pools) but the registrar loop throws
            // before registration, so no one holds a reference and close() is never reached. The caller cannot clean up
            // either — it only sees the exception, never the orphaned instance. Wrapping the registrar loop in a
            // try/catch that closes newContext before rethrowing would flip this to verify(newContext).close().
            verify(newContext, never()).close();
        }

        @Test
        @DisplayName("Hook registrars are fail-fast: a later registrar is not invoked after an earlier one throws")
        void laterRegistrarsAreSkippedAfterAnEarlierFailure() {
            AgentRuntimeId agentRuntimeId = expectedId();
            stubFactory(agentRuntimeId, abandonableContext(agentRuntimeId));

            AgentRuntimeHookRegistrar failing = mock(AgentRuntimeHookRegistrar.class);
            doThrow(new IllegalStateException("registrar boom")).when(failing).register(any());
            AgentRuntimeHookRegistrar later = mock(AgentRuntimeHookRegistrar.class);

            assertThatThrownBy(() -> managerWith(failing, later).getOrCreateRuntime(agentBundle, fileSystem, null))
                    .isInstanceOf(IllegalStateException.class);

            // Fail-fast: a partially hooked registry is never handed further registrars.
            verify(later, never()).register(any());
        }

        @Test
        @DisplayName("A creation failure is not sticky: the next call retries creation and can succeed")
        void creationFailureIsNotSticky() {
            AgentRuntimeId agentRuntimeId = expectedId();
            OrcaAgentRuntime firstAttempt = abandonableContext(agentRuntimeId);
            OrcaAgentRuntime secondAttempt = abandonableContext(agentRuntimeId);
            when(agentRuntimeFactory.create(eq(agentRuntimeId), eq(agentExecutor), eq(scheduledTaskManager),
                    eq(agentBundle), eq(fileSystem), any(), any(), any())).thenReturn(firstAttempt)
                    .thenReturn(secondAttempt);

            AtomicInteger attempts = new AtomicInteger();
            AgentRuntimeHookRegistrar flaky = registry -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("registrar boom");
                }
            };
            OrcaAgentRuntimeManager managerWithFlakyHook = managerWith(flaky);

            assertThatThrownBy(() -> managerWithFlakyHook.getOrCreateRuntime(agentBundle, fileSystem, null))
                    .isInstanceOf(IllegalStateException.class);

            // The per-id lock must not stay poisoned and the double-checked read must not see a phantom entry, so a
            // bootstrap that retries after a transient hook failure gets a genuinely fresh, registered context.
            OrcaAgentRuntime recovered = managerWithFlakyHook.getOrCreateRuntime(agentBundle, fileSystem, null);

            assertThat(recovered).isSameAs(secondAttempt);
            assertThat(agentRuntimeRegistry.get(agentRuntimeId)).isPresent().containsSame(secondAttempt);
            assertThat(attempts.get()).as("the registrar ran once per creation attempt").isEqualTo(2);
        }
    }

    /**
     * A factory that holds every creation open until released, so a second concurrent creation for one id is
     * observable rather than merely likely.
     *
     * <p>
     * Hand-written because {@code StubbedInvocationMatcher.answer} is {@code synchronized}: two threads calling the
     * same stubbing on a Mockito mock cannot be inside the answer at once, which would mask the very overlap under
     * test.
     */
    private static final class GatedRuntimeFactory extends OrcaAgentRuntimeFactory {

        private final List<OrcaAgentRuntime> handouts;
        private final AtomicInteger creations = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private GatedRuntimeFactory(List<OrcaAgentRuntime> handouts) {
            this.handouts = handouts;
        }

        @Override
        public OrcaAgentRuntime create(AgentRuntimeId agentRuntimeId, OrcaAgentExecutor agentExecutor,
                ScheduledTaskManager scheduledTaskManager, AgentBundle agentBundle, VirtualFileSystem fileSystem,
                CredentialStore credentialStore, List<OrcaToolProvider> toolProviders,
                List<OrcaCommandProvider> commandProviders) {
            final int index = creations.getAndIncrement();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return handouts.get(Math.min(index, handouts.size() - 1));
        }
    }

    /**
     * Waits until {@code thread} is sitting on a monitor rather than passing through one.
     *
     * <p>
     * The second observation is what makes this an assertion instead of a guess — a thread momentarily blocked on
     * some unrelated monitor clears it, while one parked on the per-id monitor stays put as long as the holder is
     * gated.
     */
    private static void awaitParkedOnAMonitor(Thread thread) throws InterruptedException {
        awaitUntil(() -> isStablyBlocked(thread), "thread " + thread.getName() + " never parked on a monitor");
    }

    /** Waits until the late creator has either gotten into a second creation (the defect) or parked (correct). */
    private static void awaitSecondCreationOrPark(Thread thread, GatedRuntimeFactory factory)
            throws InterruptedException {
        awaitUntil(() -> factory.creations.get() >= 2 || isStablyBlocked(thread),
                "the late creator neither parked nor started a second creation");
    }

    private static boolean isStablyBlocked(Thread thread) {
        if (thread.getState() != Thread.State.BLOCKED) {
            return false;
        }
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return thread.getState() == Thread.State.BLOCKED;
    }

    private static void awaitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(failureMessage);
            }
            Thread.sleep(5);
        }
    }

    private static void joinAll(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    private static OrcaAgentRuntime createMockContext(AgentRuntimeId agentRuntimeId) {
        OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        HookRegistry hookRegistry = mock(HookRegistry.class);
        when(context.getId()).thenReturn(agentRuntimeId);
        lenient().when(context.getHookRegistry()).thenReturn(hookRegistry);
        return context;
    }
}
