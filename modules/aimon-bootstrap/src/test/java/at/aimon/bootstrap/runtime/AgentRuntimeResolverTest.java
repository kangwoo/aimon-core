package at.aimon.bootstrap.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.bootstrap.exception.AgentRuntimeExhaustedException;
import at.aimon.bootstrap.exception.UnknownAgentRuntimeException;
import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.tool.Tool;

/**
 * Covers the rows of the design's multi-agent risk table that concern runtime lifetime: a runtime in use is never
 * evicted, an eager runtime is never evicted at all, two concurrent first-requests build one runtime, and a full
 * resolver refuses rather than overflows.
 *
 * <p>
 * Everything here runs on a hand-wound clock and calls {@link AgentRuntimeResolver#sweep()} directly. The
 * background sweeper is the same method on a timer, and driving it by hand is what makes "after the TTL" a fact
 * rather than a sleep long enough to usually work.
 */
class AgentRuntimeResolverTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    @Nested
    @DisplayName("Resolving")
    class Resolving {

        @Test
        @DisplayName("returns the registered runtime for an eager id without provisioning")
        void eagerIdIsServedFromTheRegistry() {
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final FakeRuntime eager = new FakeRuntime(AgentRuntimeId.fromName("ops"));
            registry.register(eager);
            final CountingProvisioner provisioner = new CountingProvisioner();

            try (AgentRuntimeResolver resolver = resolver(registry, provisioner).build();
                    AgentRuntimeLease lease = resolver.acquire(eager.getId())) {
                assertThat(lease.runtime()).isSameAs(eager);
            }

            assertThat(provisioner.count()).isZero();
            assertThat(eager.isClosed()).isFalse();
        }

        @Test
        @DisplayName("provisions a tenant id on first use and reuses it afterwards")
        void tenantIdIsProvisionedOnce() {
            final CountingProvisioner provisioner = new CountingProvisioner();
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), provisioner).build()) {
                final AgentRuntime first;
                try (AgentRuntimeLease lease = resolver.acquire(tenant)) {
                    first = lease.runtime();
                }
                try (AgentRuntimeLease lease = resolver.acquire(tenant)) {
                    assertThat(lease.runtime()).isSameAs(first);
                }
                assertThat(provisioner.count()).isEqualTo(1);
                assertThat(resolver.trackedIds()).containsExactly(tenant);
            }
        }

        @Test
        @DisplayName("keeps two tenants of the same agent apart")
        void tenantsAreIsolatedFromEachOther() {
            final CountingProvisioner provisioner = new CountingProvisioner();
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final AgentRuntimeId acme = AgentRuntimeId.fromName("ops", "acme");
            final AgentRuntimeId globex = AgentRuntimeId.fromName("ops", "globex");

            try (AgentRuntimeResolver resolver = resolver(registry, provisioner).build();
                    AgentRuntimeLease first = resolver.acquire(acme);
                    AgentRuntimeLease second = resolver.acquire(globex)) {
                assertThat(first.runtime()).isNotSameAs(second.runtime());
                assertThat(first.runtime().getId()).isEqualTo(acme);
                assertThat(second.runtime().getId()).isEqualTo(globex);
                assertThat(registry.get(acme)).containsSame(first.runtime());
                assertThat(registry.get(globex)).containsSame(second.runtime());
            }
        }

        @Test
        @DisplayName("refuses an unregistered id that has no discriminator")
        void unknownAgentIsNotConjured() {
            final CountingProvisioner provisioner = new CountingProvisioner();

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), provisioner).build()) {
                assertThatThrownBy(() -> resolver.acquire(AgentRuntimeId.fromName("typo")))
                        .isInstanceOf(UnknownAgentRuntimeException.class).hasMessageContaining("agent:typo");
            }
            assertThat(provisioner.count()).isZero();
        }

        @Test
        @DisplayName("does not keep a slot when provisioning fails, so a retry can succeed")
        void failedProvisionIsNotCached() {
            final AtomicBoolean fail = new AtomicBoolean(true);
            final CountingProvisioner provisioner = new CountingProvisioner(id -> {
                if (fail.get()) {
                    throw new IllegalStateException("mcp server unreachable");
                }
                return new FakeRuntime(id);
            });
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), provisioner).build()) {
                assertThatThrownBy(() -> resolver.acquire(tenant)).isInstanceOf(IllegalStateException.class);
                assertThat(resolver.trackedCount()).isZero();

                fail.set(false);
                try (AgentRuntimeLease lease = resolver.acquire(tenant)) {
                    assertThat(lease.runtime().getId()).isEqualTo(tenant);
                }
            }
        }

        @Test
        @DisplayName("builds one runtime when many threads ask for the same absent tenant at once")
        void concurrentFirstUseProvisionsOnce() throws Exception {
            final int threads = 8;
            final CyclicBarrier startTogether = new CyclicBarrier(threads);
            final CountingProvisioner provisioner = new CountingProvisioner(id -> {
                sleepBriefly();
                return new FakeRuntime(id);
            });
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");
            final ExecutorService pool = Executors.newFixedThreadPool(threads);

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), provisioner).build()) {
                final List<Future<AgentRuntime>> results = pool
                        .invokeAll(IntStream.range(0, threads).<Callable<AgentRuntime>>mapToObj(i -> () -> {
                            startTogether.await(5, TimeUnit.SECONDS);
                            try (AgentRuntimeLease lease = resolver.acquire(tenant)) {
                                return lease.runtime();
                            }
                        }).toList());

                final AgentRuntime shared = results.get(0).get();
                for (Future<AgentRuntime> result : results) {
                    assertThat(result.get()).isSameAs(shared);
                }
                assertThat(provisioner.count()).isEqualTo(1);
                assertThat(resolver.trackedCount()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("Eviction")
    class Eviction {

        @Test
        @DisplayName("closes and unregisters a tenant runtime once it has been idle for the TTL")
        void idleTenantIsReclaimed() {
            final MutableClock clock = new MutableClock();
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final CountingProvisioner provisioner = new CountingProvisioner();
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");

            try (AgentRuntimeResolver resolver = resolver(registry, provisioner).clock(clock).build()) {
                final FakeRuntime runtime;
                try (AgentRuntimeLease lease = resolver.acquire(tenant)) {
                    runtime = (FakeRuntime) lease.runtime();
                }

                clock.advance(TTL.minusMinutes(1));
                assertThat(resolver.sweep()).isZero();
                assertThat(runtime.isClosed()).isFalse();

                clock.advance(Duration.ofMinutes(2));
                assertThat(resolver.sweep()).isEqualTo(1);
                assertThat(runtime.isClosed()).isTrue();
                assertThat(registry.get(tenant)).isEmpty();
                assertThat(resolver.trackedCount()).isZero();
            }
        }

        @Test
        @DisplayName("never evicts a runtime that is leased, however long ago the lease was taken")
        void runtimeInUseIsNeverEvicted() {
            final MutableClock clock = new MutableClock();
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");

            try (AgentRuntimeResolver resolver = resolver(registry, new CountingProvisioner()).clock(clock).build()) {
                final AgentRuntimeLease held = resolver.acquire(tenant);
                final FakeRuntime runtime = (FakeRuntime) held.runtime();

                // A turn stuck on a slow tool looks exactly like an unused runtime to a clock.
                clock.advance(TTL.multipliedBy(10));
                assertThat(resolver.sweep()).isZero();
                assertThat(runtime.isClosed()).isFalse();
                assertThat(registry.get(tenant)).containsSame(runtime);

                held.close();
                assertThat(runtime.isClosed()).isFalse();
                clock.advance(TTL.plusMinutes(1));
                assertThat(resolver.sweep()).isEqualTo(1);
                assertThat(runtime.isClosed()).isTrue();
            }
        }

        @Test
        @DisplayName("leaves eager runtimes alone — they are the stack's to close")
        void eagerRuntimeIsNeverEvicted() {
            final MutableClock clock = new MutableClock();
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final FakeRuntime eager = new FakeRuntime(AgentRuntimeId.fromName("ops"));
            registry.register(eager);

            try (AgentRuntimeResolver resolver = resolver(registry, new CountingProvisioner()).clock(clock).build()) {
                resolver.acquire(eager.getId()).close();

                clock.advance(TTL.multipliedBy(10));
                assertThat(resolver.sweep()).isZero();
                assertThat(resolver.trackedCount()).isZero();
                assertThat(eager.isClosed()).isFalse();
                assertThat(registry.get(eager.getId())).containsSame(eager);
            }
            assertThat(eager.isClosed()).isFalse();
        }

        @Test
        @DisplayName("reclaims nothing when eviction is disabled")
        void neverPolicyKeepsEverything() {
            final MutableClock clock = new MutableClock();
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), new CountingProvisioner())
                    .clock(clock).eviction(AgentRuntimeEviction.NEVER).build()) {
                final FakeRuntime runtime;
                try (AgentRuntimeLease lease = resolver.acquire(tenant)) {
                    runtime = (FakeRuntime) lease.runtime();
                }
                clock.advance(TTL.multipliedBy(100));
                assertThat(resolver.sweep()).isZero();
                assertThat(runtime.isClosed()).isFalse();
                assertThat(resolver.trackedCount()).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("Capacity")
    class Capacity {

        @Test
        @DisplayName("refuses a new tenant when the cap is reached and nothing is idle")
        void exhaustionIsRefusedNotExceeded() {
            final MutableClock clock = new MutableClock();
            final CountingProvisioner provisioner = new CountingProvisioner();

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), provisioner).clock(clock)
                    .maxEntries(2).build()) {
                final AgentRuntimeLease first = resolver.acquire(AgentRuntimeId.fromName("ops", "a"));
                final AgentRuntimeLease second = resolver.acquire(AgentRuntimeId.fromName("ops", "b"));

                assertThatThrownBy(() -> resolver.acquire(AgentRuntimeId.fromName("ops", "c")))
                        .isInstanceOf(AgentRuntimeExhaustedException.class).hasMessageContaining("2");
                assertThat(resolver.exhaustionCount()).isEqualTo(1);
                assertThat(resolver.trackedCount()).isEqualTo(2);
                assertThat(provisioner.count()).isEqualTo(2);

                first.close();
                second.close();
            }
        }

        @Test
        @DisplayName("says how many of the full set are actually held when it refuses")
        void refusalReportsTheLeasedCount() {
            final MutableClock clock = new MutableClock();
            final AgentRuntimeId third = AgentRuntimeId.fromName("ops", "c");

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), new CountingProvisioner())
                    .clock(clock).maxEntries(2).build()) {
                final AgentRuntimeLease first = resolver.acquire(AgentRuntimeId.fromName("ops", "a"));
                final AgentRuntimeLease second = resolver.acquire(AgentRuntimeId.fromName("ops", "b"));

                // Full with every slot serving someone: the refusal was honest and the cap is the remedy.
                assertThatThrownBy(() -> resolver.acquire(third)).isInstanceOf(AgentRuntimeExhaustedException.class)
                        .hasMessageContaining("2 of the 2 are held");

                first.close();
                second.close();

                // Still full, still refusing, but nothing is in use — the runtimes are alive on the unexpired TTL
                // and a shorter one would have served this call. Every other surface reports these two refusals
                // identically (same exception, same exhaustion count, same trackedCount, same isSaturated), so if
                // the message does not carry the difference, the instant it is visible in has no record of it.
                assertThatThrownBy(() -> resolver.acquire(third)).isInstanceOf(AgentRuntimeExhaustedException.class)
                        .hasMessageContaining("0 of the 2 are held");
            }
        }

        @Test
        @DisplayName("admits a new tenant at the cap once an existing one has gone idle")
        void idleSlotIsReclaimedOnAdmission() {
            final MutableClock clock = new MutableClock();
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final AgentRuntimeId first = AgentRuntimeId.fromName("ops", "a");

            try (AgentRuntimeResolver resolver = resolver(registry, new CountingProvisioner()).clock(clock)
                    .maxEntries(1).build()) {
                final FakeRuntime evicted;
                try (AgentRuntimeLease lease = resolver.acquire(first)) {
                    evicted = (FakeRuntime) lease.runtime();
                }

                clock.advance(TTL.plusMinutes(1));
                // No explicit sweep: admission sweeps first, which is what keeps a full-but-idle resolver usable.
                try (AgentRuntimeLease lease = resolver.acquire(AgentRuntimeId.fromName("ops", "b"))) {
                    assertThat(lease.runtime()).isNotSameAs(evicted);
                }
                assertThat(evicted.isClosed()).isTrue();
                assertThat(registry.get(first)).isEmpty();
                assertThat(resolver.exhaustionCount()).isZero();
            }
        }

        @Test
        @DisplayName("counts a provisioner that throws, separately from exhaustion")
        void provisionFailuresAreCounted() {
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), id -> {
                throw new IllegalStateException("no credentials for " + id);
            }).build()) {
                assertThatThrownBy(() -> resolver.acquire(tenant)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> resolver.acquire(tenant)).isInstanceOf(IllegalStateException.class);

                assertThat(resolver.provisionFailureCount()).as("both attempts counted — the slot is not cached")
                        .isEqualTo(2);
                assertThat(resolver.exhaustionCount()).as("a broken provisioner is not a full resolver").isZero();
                assertThat(resolver.trackedCount()).as("the failed slot is released").isZero();
            }
        }

        @Test
        @DisplayName("separates runtimes in use from runtimes the TTL alone is keeping alive")
        void leasedCountDistinguishesHeldFromMerelyTracked() {
            final MutableClock clock = new MutableClock();
            final AgentRuntimeId a = AgentRuntimeId.fromName("ops", "a");
            final AgentRuntimeId b = AgentRuntimeId.fromName("ops", "b");

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), new CountingProvisioner())
                    .clock(clock).maxEntries(2).build()) {
                assertThat(resolver.leasedCount()).isZero();

                final AgentRuntimeLease first = resolver.acquire(a);
                final AgentRuntimeLease second = resolver.acquire(b);

                // Full and genuinely busy: every slot is serving someone, so the cap is the thing to raise.
                assertThat(resolver.trackedCount()).isEqualTo(2);
                assertThat(resolver.leasedCount()).isEqualTo(2);
                assertThat(resolver.isSaturated()).isTrue();

                first.close();
                second.close();

                // Still full, still refusing a third tenant — but now nothing is in use and the runtimes are held
                // only by the unexpired TTL, so the remedy is the opposite one. trackedCount() and isSaturated()
                // read identically across these two states; leasedCount() is what tells them apart.
                assertThat(resolver.trackedCount()).isEqualTo(2);
                assertThat(resolver.leasedCount()).isZero();
                assertThat(resolver.isSaturated()).isTrue();

                // And a second holder of one runtime still counts that runtime once.
                try (AgentRuntimeLease held = resolver.acquire(a); AgentRuntimeLease alsoHeld = resolver.acquire(a)) {
                    assertThat(held.runtime()).isSameAs(alsoHeld.runtime());
                    assertThat(resolver.leasedCount()).isEqualTo(1);
                }
                assertThat(resolver.leasedCount()).isZero();
            }
        }

        @Test
        @DisplayName("counts nothing when every runtime builds")
        void provisionFailureCountStaysZeroOnTheHappyPath() {
            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), new CountingProvisioner())
                    .build()) {
                try (AgentRuntimeLease lease = resolver.acquire(AgentRuntimeId.fromName("ops", "acme"))) {
                    assertThat(lease.runtime()).isNotNull();
                }
                assertThat(resolver.provisionFailureCount()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("Invalidation")
    class Invalidation {

        @Test
        @DisplayName("closes an unused tenant runtime immediately")
        void invalidateClosesIdleRuntime() {
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");

            try (AgentRuntimeResolver resolver = resolver(registry, new CountingProvisioner()).build()) {
                final FakeRuntime runtime;
                try (AgentRuntimeLease lease = resolver.acquire(tenant)) {
                    runtime = (FakeRuntime) lease.runtime();
                }

                assertThat(resolver.invalidate(tenant)).isTrue();
                assertThat(runtime.isClosed()).isTrue();
                assertThat(registry.get(tenant)).isEmpty();
                assertThat(resolver.invalidate(tenant)).isFalse();
            }
        }

        @Test
        @DisplayName("waits for the last holder before closing, and hands the next caller a fresh runtime")
        void invalidateDefersToTheLastHolder() {
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), new CountingProvisioner())
                    .build()) {
                final AgentRuntimeLease held = resolver.acquire(tenant);
                final FakeRuntime runtime = (FakeRuntime) held.runtime();

                assertThat(resolver.invalidate(tenant)).isTrue();
                assertThat(runtime.isClosed()).as("a turn is still running against it").isFalse();

                // The next caller must not be handed the retired runtime.
                try (AgentRuntimeLease replacement = resolver.acquire(tenant)) {
                    assertThat(replacement.runtime()).isNotSameAs(runtime);
                }

                held.close();
                assertThat(runtime.isClosed()).isTrue();
            }
        }

        @Test
        @DisplayName("does not touch an eager runtime")
        void invalidateIgnoresEagerIds() {
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final FakeRuntime eager = new FakeRuntime(AgentRuntimeId.fromName("ops"));
            registry.register(eager);

            try (AgentRuntimeResolver resolver = resolver(registry, new CountingProvisioner()).build()) {
                assertThat(resolver.invalidate(eager.getId())).isFalse();
                assertThat(eager.isClosed()).isFalse();
                assertThat(registry.get(eager.getId())).containsSame(eager);
            }
        }
    }

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("closes every tenant runtime it created and leaves eager ones registered")
        void closeReleasesTenantsOnly() {
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final FakeRuntime eager = new FakeRuntime(AgentRuntimeId.fromName("ops"));
            registry.register(eager);
            final AgentRuntimeId acme = AgentRuntimeId.fromName("ops", "acme");
            final AgentRuntimeId globex = AgentRuntimeId.fromName("ops", "globex");

            final AgentRuntimeResolver resolver = resolver(registry, new CountingProvisioner()).build();
            final FakeRuntime first;
            final FakeRuntime second;
            try (AgentRuntimeLease a = resolver.acquire(acme); AgentRuntimeLease b = resolver.acquire(globex)) {
                first = (FakeRuntime) a.runtime();
                second = (FakeRuntime) b.runtime();
            }
            resolver.acquire(eager.getId()).close();

            resolver.close();
            resolver.close();

            assertThat(first.isClosed()).isTrue();
            assertThat(second.isClosed()).isTrue();
            assertThat(eager.isClosed()).isFalse();
            assertThat(registry.get(acme)).isEmpty();
            assertThat(registry.get(globex)).isEmpty();
            assertThat(registry.get(eager.getId())).containsSame(eager);
            assertThat(resolver.trackedCount()).isZero();
        }

        @Test
        @DisplayName("closes the resources the provisioner handed over alongside the runtime")
        void closeReleasesOwnedResources() {
            final AtomicBoolean fileSystemClosed = new AtomicBoolean();
            final CountingProvisioner provisioner = new CountingProvisioner(id -> new FakeRuntime(id),
                    (runtime, builder) -> builder.owns(() -> fileSystemClosed.set(true)));

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), provisioner).build()) {
                resolver.acquire(AgentRuntimeId.fromName("ops", "acme")).close();
            }
            assertThat(fileSystemClosed).isTrue();
        }
    }

    /**
     * The window between assembly and {@code AimonStack.startRuntimes()}, during which a declared id is in no
     * registry and yet must not be created here.
     *
     * <p>
     * A declared agent given a discriminator in the spec produces {@code agent:<name>:<discriminator>} — the same
     * shape a tenant has — so nothing about the id itself says which of the two it is. Before registration the
     * registry cannot say either, and provisioning would hand out a second runtime for an id the stack has
     * already built one for.
     */
    @Nested
    @DisplayName("Declared ids")
    class DeclaredIds {

        private static final AgentRuntimeId DECLARED = AgentRuntimeId.fromName("ops", "eu");

        @Test
        @DisplayName("refuses a declared id that the stack has not registered yet, without building anything")
        void declaredIdIsNotProvisionedBeforeRegistration() {
            final CountingProvisioner provisioner = new CountingProvisioner();
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();

            try (AgentRuntimeResolver resolver = resolver(registry, provisioner).declaredIds(Set.of(DECLARED))
                    .build()) {
                assertThatThrownBy(() -> resolver.acquire(DECLARED)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("agent:ops:eu").hasMessageContaining("startRuntimes");
            }

            assertThat(provisioner.count()).isZero();
            assertThat(registry.get(DECLARED)).isEmpty();
        }

        @Test
        @DisplayName("serves the stack's runtime once it is registered, still without provisioning")
        void declaredIdIsPinnedAfterRegistration() {
            final CountingProvisioner provisioner = new CountingProvisioner();
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final FakeRuntime eager = new FakeRuntime(DECLARED);

            try (AgentRuntimeResolver resolver = resolver(registry, provisioner).declaredIds(Set.of(DECLARED))
                    .build()) {
                registry.register(eager);
                try (AgentRuntimeLease lease = resolver.acquire(DECLARED)) {
                    assertThat(lease.runtime()).isSameAs(eager);
                }
            }

            // The refusal is a property of the pre-registration window, not of the id: once the stack registered
            // its runtime the declared id resolves, and the lease is the pinned kind that closes nothing.
            assertThat(provisioner.count()).isZero();
            assertThat(eager.isClosed()).isFalse();
            assertThat(registry.get(DECLARED)).containsSame(eager);
        }

        @Test
        @DisplayName("still provisions a tenant of the same agent while the declared id is refused")
        void tenantOfADeclaredAgentIsUnaffected() {
            final CountingProvisioner provisioner = new CountingProvisioner();
            final AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
            final AgentRuntimeId tenant = AgentRuntimeId.fromName("ops", "acme");

            try (AgentRuntimeResolver resolver = resolver(registry, provisioner).declaredIds(Set.of(DECLARED))
                    .build()) {
                assertThatThrownBy(() -> resolver.acquire(DECLARED)).isInstanceOf(IllegalStateException.class);
                try (AgentRuntimeLease lease = resolver.acquire(tenant)) {
                    assertThat(lease.runtime().getId()).isEqualTo(tenant);
                }
            }

            assertThat(provisioner.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a resolver told of no declared ids behaves as before")
        void noDeclaredIdsMeansNoRefusal() {
            final CountingProvisioner provisioner = new CountingProvisioner();

            try (AgentRuntimeResolver resolver = resolver(new DefaultAgentRuntimeRegistry(), provisioner).build();
                    AgentRuntimeLease lease = resolver.acquire(DECLARED)) {
                assertThat(lease.runtime().getId()).isEqualTo(DECLARED);
            }

            assertThat(provisioner.count()).isEqualTo(1);
        }
    }

    private static AgentRuntimeResolver.Builder resolver(AgentRuntimeRegistry registry,
            AgentRuntimeProvisioner provisioner) {
        return AgentRuntimeResolver.builder(registry, provisioner).idleTtl(TTL);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Counts provision calls so "exactly once" is a measurement rather than an inference. */
    private static final class CountingProvisioner implements AgentRuntimeProvisioner {

        private final Function<AgentRuntimeId, AgentRuntime> factory;
        private final BiConsumer<AgentRuntime, ProvisionedAgentRuntime.Builder> decorator;
        private final AtomicInteger count = new AtomicInteger();

        CountingProvisioner() {
            this(FakeRuntime::new);
        }

        CountingProvisioner(Function<AgentRuntimeId, AgentRuntime> factory) {
            this(factory, (runtime, builder) -> {
            });
        }

        CountingProvisioner(Function<AgentRuntimeId, AgentRuntime> factory,
                BiConsumer<AgentRuntime, ProvisionedAgentRuntime.Builder> decorator) {
            this.factory = factory;
            this.decorator = decorator;
        }

        @Override
        public ProvisionedAgentRuntime provision(AgentRuntimeId agentRuntimeId) {
            count.incrementAndGet();
            final AgentRuntime runtime = factory.apply(agentRuntimeId);
            final ProvisionedAgentRuntime.Builder builder = ProvisionedAgentRuntime.builder(runtime);
            decorator.accept(runtime, builder);
            return builder.build();
        }

        int count() {
            return count.get();
        }
    }

    /** An {@link AgentRuntime} that records whether it was closed. */
    private static final class FakeRuntime implements AgentRuntime, AutoCloseable {

        private final AgentRuntimeId id;
        private final AtomicBoolean closed = new AtomicBoolean();

        FakeRuntime(AgentRuntimeId id) {
            this.id = id;
        }

        @Override
        public AgentRuntimeId getId() {
            return id;
        }

        @Override
        public Agent getAgent() {
            return DefaultAgent.builder().name(id.agentName()).systemPrompt("test").build();
        }

        @Override
        public List<Tool> getAvailableTools() {
            return List.of();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        boolean isClosed() {
            return closed.get();
        }
    }

    /** A clock the test winds forward, so "after the TTL" needs no sleeping. */
    private static final class MutableClock extends Clock {

        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
