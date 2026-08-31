---
translated_from: docs/features/scheduling/quartz-scheduling-web-deployment-guide.md
source_commit: 198ed2f2
---

# Quartz Scheduling — Deployment Guide for a Web-Based Agent System

`aimon-scheduling-quartz` is the Quartz-backed implementation of the `TaskScheduler` interface that `aimon-core` defines. This document collects the deployment and operational facts you need when running `SchedulingEngine` together with `SessionRouter` in a web / multi-node environment.

> This guide's scope model follows [`docs/overview/scope-model.en.md`](../../overview/scope-model.en.md) — `AgentRuntime` is agent-scoped and is shared across sessions. The background to that model is in [`docs/design/agent-execution/agent-runtime-scope.md`](../../design/agent-execution/agent-runtime-scope.md).

For the design background, see the following documents.

- `modules/aimon-scheduling-quartz/README.md` — how to use the module itself
- `docs/features/session/web-session-deployment-guide.md` — the multi-node session deployment model
- `docs/features/session/agent-session-guide.md` — the single-node `LiveSession` API

---

## 1. Why Quartz on the web

`aimon-core` ships `InMemoryTaskScheduler` as its default implementation. That is enough on a single node or in development, but running a web agent system across several nodes brings the following problems.

| Situation | InMemory | Quartz (JDBC + cluster) |
|---|---|---|
| Several nodes | The same cron **fires on every node** | A DB lock keeps it on one node |
| A node restarts | Registered tasks are lost | Recovered from the JDBC JobStore |
| A node fails | The tasks stop | Another node takes over automatically |
| A missed firing | Ignored | Handled according to the misfire policy |

So **every web deployment with two or more instances behind a load balancer** should switch to `QuartzTaskScheduler` (JDBC + clustered).

---

## 2. The component lifetime model

`SchedulingEngine`, `ScheduledTaskManager` and `RoutineExecutor` are **application-scoped** (long-lived). `AgentRuntime` is **agent-scoped** (it lives as long as the agent definition) and must survive the closing of any live session that ran on top of it. It is this separation of lifetimes that keeps `boundRuntimeId` resolvable from the registry when a cron re-fires.

The full scope hierarchy (Application / Agent / Session / Live session, plus the execution units Execution · Turn · Iteration) is governed by [`docs/overview/scope-model.en.md`](../../overview/scope-model.en.md). The picture below draws only the two rows of it that scheduling touches.

```
┌──────────────────── the JVM process (application-scoped) ──────────────┐
│                                                                        │
│  SchedulingEngine                                                      │
│   ├─ ScheduledTaskManager   ┐                                          │
│   ├─ RoutineExecutor        │  created once at application start       │
│   └─ QuartzTaskScheduler ───┘  (close happens at JVM shutdown)         │
│                                                                        │
│  AgentRuntimeRegistry (injected from outside; SchedulingEngine         │
│   does not own it — bootstrap code manages register/unregister)        │
│                                                                        │
│  ┌────── AgentRuntime (agent-scoped, long-lived) ──────────────────┐   │
│  │  ID = "agent:<name>" or "agent:<name>:<discriminator>"          │   │
│  │  registered once per agent at bootstrap.                        │   │
│  │  the runtime survives a live session closing                    │   │
│  │  → ScheduledTask.boundRuntimeId still resolves from the         │   │
│  │    registry when cron re-fires.                                 │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

**The principles**

1. Call `SchedulingEngine.close()` only at **JVM shutdown** or from an explicit operational action. Never per session or per request.
2. Call `AgentRuntime.close()` only when the agent is removed or the app shuts down. Never from `LiveSession.close()` — another session of the same agent may still be using that runtime.
3. Inject `AgentRuntimeRegistry` **from outside** with `SchedulingEngineBuilder.agentRuntimeRegistry(...)`. The session side has to see the **same instance** for a scheduled task to find its agent runtime — the exact wiring is in §4.
4. The Quartz scheduler itself is long-lived too, so build `QuartzTaskScheduler` once, in the application bootstrap.

> **The silent failure mode**: leave `agentRuntimeRegistry(...)` uninjected entirely and `SchedulingEngineBuilder` quietly builds its own `DefaultAgentRuntimeRegistry`. Nothing throws; the runtime the bootstrap registered simply is not in that registry, so it only surfaces **when the cron finally fires**, as `IllegalStateException("No agent runtime registered for binding: ...")`.

---

## 3. Deploying in cluster mode

### 3.1 The database schema

Quartz uses eleven tables of its own. The official schema, for PostgreSQL, comes from:

- https://github.com/quartz-scheduler/quartz/tree/master/quartz-core/src/main/resources/org/quartz/impl/jdbcjobstore

Apply the file matching your database — `tables_postgres.sql`, say. The table prefix is fixed at `QRTZ_` by `QuartzTaskSchedulerBuilder` and is not exposed as a builder option (expose it in a separate PR if you need to).

> **Recommended**: put the Quartz tables in a **schema separate** from the web agent's business database (or in a separate database altogether). It reduces both migration-tool conflicts and the DBA's review burden.

### 3.2 Adding the dependency

```kotlin
// the application module's build.gradle.kts
dependencies {
    implementation(project(":aimon-core"))
    implementation(project(":aimon-scheduling-quartz"))
    runtimeOnly("org.postgresql:postgresql:42.7.1")
}
```

`aimon-scheduling-quartz` depends via `implementation(project(":aimon-core"))` and does not re-expose the core interfaces (no `api()` — the module boundary rule).

### 3.3 ScheduledTaskExecutor — only the taskId crosses the cluster

The Quartz JDBC JobStore serialises **the task identifier (`ScheduledTaskId`)** and nothing else. A lambda (`Runnable`) cannot travel between nodes, so the node that fires holds nothing but the taskId and has to reconstruct the execution logic for itself. `ScheduledTaskExecutor` is where that reconstruction happens — a single `(ScheduledTaskId) -> void` function handed to `QuartzTaskSchedulerBuilder.taskExecutor(...)`, which `QuartzTaskScheduler` calls every time a job fires.

If you use `SchedulingEngine`, the core has already done the reconstruction for you. The task definition lives in `ScheduledTaskRepository`, and `ScheduledTaskManager` looks it up by taskId and hands it to `RoutineExecutor`. All the application has to do is **give the builder an executor that delegates to `ScheduledTaskManager`** (see the §4 code).

Write your own mapping only where you register cron tasks directly — an operational tool that bypasses `SchedulingEngine`, for instance.

```java
ScheduledTaskExecutor opsExecutor = taskId -> {
    switch (taskId.value()) {
        case "ops.daily-cleanup" -> opsService.cleanup();
        case "ops.metric-rollup" -> metricsService.rollup();
        default -> log.warn("Unknown scheduled task on this node: {}", taskId);
    }
};
```

Every node has to know the same mapping. A firing on a node that is missing an entry is simply lost — Quartz treats the trigger as consumed, so no other node runs it in that node's place.

---

## 4. Wiring SchedulingEngine + SessionRouter

Below is the standard bootstrap code for bringing both components up in one process. For the meaning of the session-side builder options and their tunables, follow [`web-session-deployment-guide.en.md`](../session/web-session-deployment-guide.en.md).

```java
public final class AgentSchedulingBootstrap implements AutoCloseable {

    private final AgentRuntimeRegistry agentRuntimeRegistry;
    private final QuartzTaskScheduler quartzScheduler;
    private final SchedulingEngine schedulingEngine;
    private final SessionRouter sessionRouter;

    public AgentSchedulingBootstrap(AgentSchedulingProps props,
                                    LiveSessionFactory sessionFactory,
                                    SessionRecordStore sessionRecordStore,
                                    DistributedSpis spis) {
        // 1. the registry has to be shared by scheduling and sessions alike — build it outside
        this.agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();

        // 2. the Quartz scheduler — clustered + JDBC
        //    the ScheduledTaskExecutor is connected as a lazy ref at builder time.
        AtomicReference<ScheduledTaskManager> managerRef = new AtomicReference<>();
        this.quartzScheduler = QuartzTaskSchedulerBuilder.create()
                .instanceName("WebAgentScheduler")
                .threadCount(props.threadCount())
                .jdbcJobStore(props.jdbcUrl(), props.jdbcDriver())
                .clustered(true)
                .clusterCheckinInterval(15_000)
                .taskExecutor(taskId -> managerRef.get().executeTask(taskId))
                .build();

        // 3. the SchedulingEngine — with the external scheduler injected
        this.schedulingEngine = SchedulingEngineBuilder.create()
                .agentRuntimeRegistry(agentRuntimeRegistry)
                .taskScheduler(quartzScheduler)
                .defaultMaxQuota(props.maxQuotaPerRuntime())
                .build();
        managerRef.set(schedulingEngine.getTaskManager());

        // 4. the SessionRouter — all four multi-node SPIs are injected explicitly
        this.sessionRouter = SessionRouter.builder()
                .sessionFactory(sessionFactory)          // this factory is what gets the runtime from the registry
                .sessionRecordStore(sessionRecordStore)
                .mode(DeploymentMode.DISTRIBUTED)
                .nodeId(props.nodeId())
                .sessionLeaseStore(spis.leaseStore())
                .signalBus(spis.signalBus())
                .sessionInbox(spis.inbox())
                .idempotencyStore(spis.idempotency())
                .metrics(spis.metrics())
                .build();

        // 5. the start order: scheduling first, the session router after
        schedulingEngine.start();
    }

    @PreDestroy
    @Override
    public void close() {
        // shutdown runs in the reverse of the start order
        boolean drained = sessionRouter.closeGracefully(Duration.ofSeconds(20));
        if (!drained) {
            log.warn("SessionRouter forced shutdown");
        }
        schedulingEngine.close();   // <-- this shuts the Quartz scheduler down too
    }
}
```

**The key points**

- Build `AgentRuntimeRegistry` once in the bootstrap so that `SchedulingEngine` and **the session-opening path** both see the same instance. `SessionRouterBuilder` has no registry setter — the router never handles runtimes directly; it reaches them indirectly through the `ContextBuilder` of `sessionFactory(LiveSessionFactory)` (or the `AgentRuntimeId` that `sessionOpener(LiveSessionOpener)` takes). If that path uses a separate registry, `RoutineExecutor` will not find the agent runtime by `boundRuntimeId` when the cron re-fires.
- Set **exactly one** of `sessionFactory(...)` and `sessionOpener(...)`. Both, or neither, and `build()` refuses. Use the opener side when caller domain attributes (a tenant id, say) have to reach the session context.
- The lambda handed to `taskExecutor` refers **lazily**, in the `managerRef.get().executeTask(...)` shape. That is what breaks the circular dependency between `QuartzTaskScheduler` and `ScheduledTaskManager`.
- One call to `schedulingEngine.close()` brings the Quartz scheduler inside it safely down as well (`SchedulingEngine.close()` → `taskScheduler.shutdown()` → `Scheduler.shutdown(true)` → `RoutineExecutor.shutdown()`). Do not call it separately.
- Shut down in the order **session router → scheduling engine**. The other way round, a scheduled-task tool called from a turn still in flight hits an engine that is already down.

> **A constraint as of HEAD**: `ScheduledTaskManager.executeTask(ScheduledTaskId)` is package-private (`at.aimon.core.scheduling`). Leave the `taskExecutor(...)` lambda above in an application package and it **will not compile.** Until the core widens the visibility or exposes a public bridge, the workaround is to wrap just that lambda in a thin bridge class placed in the `at.aimon.core.scheduling` package (this project has no `module-info.java`, so it works on a classpath basis). The `InMemoryTaskScheduler` path does not have this problem, because `SchedulingEngineBuilder` builds the lambda inside the same package.

---

## 5. Agent-scoped AgentRuntime and scheduled tasks

Because `AgentRuntime` is **agent-scoped**, `ScheduledTask.boundRuntimeId` (`"agent:<name>"`) stays valid in the registry after the session ends. `RoutineExecutor` looks the runtime up at execution time with `agentRuntimeRegistry.get(boundRuntimeId)`, and it succeeds as long as the runtime the bootstrap registered has not been destroyed.

| Moment | Is a session active? | The scheduled task |
|---|---|---|
| A user request | A live session is open; the runtime is already in the registry | Registered by a tool (`schedule_task`) |
| The user goes idle | The live session closes, **the runtime stays in the registry** | Persisted in the DB, fires on its cron |
| The cron fires | It does not matter that there is no live session | `RoutineExecutor` looks the runtime up successfully → it runs normally |
| The user comes back | A new live session opens, the same runtime is reused | Existing tasks stay mapped by owner ID |

**What this means**

1. `ScheduledTask.boundRuntimeId` is a deterministic ID of the form `"agent:<name>"`, so the value stored in the Quartz JDBC JobStore stays valid across an instance restart or the end of a session. Had the id been issued afresh per execution, a re-firing would never have resolved it.
2. A task runs on its cron even while the user is offline. Pushing the result to the UI needs a separate adapter that takes `TaskCompletedEvent`/`TaskFailedEvent` through a `ScheduledTaskEventListener` and fans them out over `signalBus` (no reference implementation is included — it is the application's responsibility).

---

## 6. Clock sync, authentication, operations

### 6.1 NTP is mandatory

A Quartz cluster's misfire handling and leader election break down **once the nodes' clocks drift more than five seconds apart**. Switch on `chrony` or `systemd-timesyncd` on every node. In a container environment, check whether the host clock can be trusted.

### 6.2 Sharing a DataSource

To reuse the DataSource pool of a framework such as Spring or Helidon, use `dataSourceClass(String)`.

```java
QuartzTaskSchedulerBuilder.create()
    .taskExecutor(taskExecutor)
    .jdbcJobStore(jdbcUrl, jdbcDriver)
    .dataSourceClass("org.example.QuartzDataSourceProvider")  // your own ConnectionProvider
    .clustered(true)
    .build();
```

The class you name has to implement `org.quartz.utils.ConnectionProvider`. This is what keeps you from ending up with two connection pools (by default Quartz builds its own with `maxConnections=10`).

That default pool is **HikariCP**, and `aimon-scheduling-quartz` ships it. Quartz 2.5 moved c3p0 and
HikariCP to `provided`, so a pool no longer arrives with the scheduler and whoever configures a JDBC job
store has to supply one — this module does. The **JDBC driver is still yours to put on the classpath**,
though. HikariCP loads the driver class while the pool is being configured, so a missing one fails in
`build()` rather than at first database access.

### 6.3 Recommended builder values

| Option | Default | Recommended | Why |
|---|---|---|---|
| `instanceName` | Derived uniquely within the JVM (`AimonScheduler-<n>`) | Unique per environment (`web-prod`, `web-stg`) — **mandatory in a cluster** | The nodes of a cluster are "the schedulers sharing one name", so a derived name turns every node into its own one-node cluster. `clustered(true)` is refused by `build()` unless the name is stated |
| `instanceId` | `AUTO` | leave it `AUTO` | Assigned automatically per node |
| `threadCount` | The number of available CPU cores | CPU cores × 2 | Most tasks are waiting on LLM I/O |
| `clusterCheckinInterval` | 20_000ms | 15_000ms | Faster failover than the default |
| `defaultMaxQuota` (`SchedulingEngineBuilder`) | 10 | **10–20** per agent runtime | Defends against LLM cost and runaway cron registration |
| `executionGuard` (`SchedulingEngineBuilder`) | `InMemoryScheduledExecutionGuard` (node-local) | A distributed implementation | The core-level second line of defence against duplicate firing across nodes |
| `interruptBus` (`SchedulingEngineBuilder`) | `ScheduledTaskInterruptBus.LOCAL_ONLY` (node-local) | A distributed implementation | Lets a cancellation also stop the run on **the node that fired** |

`defaultMaxQuota` is a ceiling **per agent runtime** (it is ignored if you inject a `TaskQuotaManager` directly).

`executionGuard` is the place where permission to run is asked, immediately before firing. The default only prevents overlap within this node, so in a cluster you have to hand it an implementation that looks at shared storage — that is what creates the second line of defence the "the same task ran on two nodes at once" item in §7 talks about. Under the starter a single `ScheduledExecutionGuard` bean wires it (`SchedulingSpec.withExecutionGuard`).

`interruptBus` points the other way — it is where something already running is **stopped**. `RoutineExecutor`'s in-flight registry is node-local, so without a bus `cancel` only cuts the runs held by the node the cancellation was entered on. In a cluster that node is usually not the one the cron fired on, and the run over there then works through its remaining steps on behalf of a task that has just been deleted. With the starter, a single `ScheduledTaskInterruptBus` bean wires it (`SchedulingSpec.withInterruptBus`); what the core ships reaches one node (`LOCAL_ONLY`) and one JVM (`InMemoryScheduledTaskInterruptBus`), so the cluster-wide one is written by the application. The design note is [`interrupt.md` §12.7](../../design/agent-execution/interrupt.md).

### 6.4 Monitoring

Send the events you receive from `SchedulingEngine.addEventListener(...)` on to Micrometer. The execution figures are not exposed by the event directly; they arrive on `RoutineResult` (`getResult()`).

```java
schedulingEngine.addEventListener(new ScheduledTaskEventListener() {
    @Override public void onTaskRegistered(TaskRegisteredEvent e) {
        registered.increment(); }
    @Override public void onTaskCompleted(TaskCompletedEvent e) {
        completed.increment();
        durations.record(e.getResult().getDuration()); }
    @Override public void onTaskFailed(TaskFailedEvent e) {
        failed.increment();
        log.warn("Scheduled task failed: {} — {}", e.getTask().getId(),
                 e.getResult().getErrorMessage().orElse("<no message>")); }
});
```

Every method on `ScheduledTaskEventListener` has an empty default implementation, so override only the events you care about. If you need step-level observation, `onStepCompleted`/`onStepFailed` are there too.

Switching on Quartz's own JMX MBean (the `org.quartz.scheduler.jmx.export=true` property) also lets you observe `MisfiresPerSecond`, `JobsExecuted`, `RunningSince` and the rest from outside. `QuartzTaskSchedulerBuilder` does not expose that property, though, so you have to hand a `Scheduler` you configured yourself to the `QuartzTaskScheduler(Scheduler, ScheduledTaskExecutor)` constructor.

---

## 7. The operations playbook

### "The same task ran on two nodes at once"

- Either `clustered(true)` is missing or the two nodes are looking at **different DB schemas**.  
  Compare the values of `org.quartz.jobStore.isClustered=true` and `org.quartz.dataSource.aimonDS.URL` in a properties dump.
- Broken NTP makes leader election wobble — verify with `timedatectl status` on the hosts.
- Check whether you **injected** the core-side second line of defence, `ScheduledExecutionGuard`. `SchedulingEngineBuilder` defaults to the node-local `InMemoryScheduledExecutionGuard`, which only prevents duplication and overlap within the same node, not across nodes. In other words, if you injected nothing, Quartz's DB lock is the entirety of your single-execution guarantee across the cluster. Hand `.executionGuard(...)` a distributed guard backed by a shared lock/lease store and you get one more line of defence at the core level on top of it.

### "The cron registered but never runs"

1. `SchedulingEngine.start()` was never called — it lands in the DB, but no trigger event is dispatched. `QuartzTaskScheduler.scheduleRecurrently` throws `TaskSchedulerException("Scheduler is not running")` when the scheduler is not started.
2. Check whether the task was stored `disabled` in the JDBC JobStore (with `task.isEnabled()` false it is skipped right before execution, even after firing).
3. The node's `threadCount` is 0/1 and other tasks are occupying it — grow the thread pool.
4. `No agent runtime registered for binding: agent:<name>` because the `agentRuntimeRegistry` is not shared — see "the silent failure mode" in §2.

### "The same task ran twice right after a node restart"

- The misfire policy is `FireAndProceed`, so this is the intended behaviour (`QuartzTaskScheduler` attaches `withMisfireHandlingInstructionFireAndProceed()` to cron triggers). Write non-idempotent tasks so that they check an idempotency key inside the `ScheduledTask` handler (reusing the session side's `IdempotencyStore`, for example).

### "A lock in the JDBC JobStore (`QRTZ_LOCKS`) won't clear"

- When a node dies to `kill -9`, another node takes over after `clusterCheckinInterval`. If it has not cleared after five minutes, check first that **every node's `instanceName` is identical** before deleting the row by hand — differing names read as different clusters, so no leader takes over.

---

## 8. Single node → cluster migration checklist

What to check when moving a web system that has been running on `InMemoryTaskScheduler` over to a cluster.

- [ ] The Quartz schema is applied to the DB
- [ ] The `aimon-scheduling-quartz` dependency is added, with the JDBC driver as `runtimeOnly`
- [ ] Swapped over with `SchedulingEngineBuilder.taskScheduler(quartzScheduler)`
- [ ] `clustered(true)` + `jdbcJobStore(...)` are configured
- [ ] Every node has the same `instanceName`, with `instanceId=AUTO`
- [ ] `AgentRuntimeRegistry` is shared with the session-opening path (`LiveSessionFactory` / `LiveSessionOpener`)
- [ ] `SessionRouter` is raised to `DeploymentMode.DISTRIBUTED` with all four SPIs + `nodeId` injected explicitly
- [ ] A distributed implementation is injected into `interruptBus` — without one, a cancellation cannot stop the run on the node that fired
- [ ] A distributed implementation is injected into `executionGuard` — the core-level line of defence against duplicate firing
- [ ] NTP is switched on
- [ ] The shutdown order is `closeGracefully` → `schedulingEngine.close()`
- [ ] Backup and monitoring are configured for the Quartz tables
- [ ] Cron behaviour verified on a single node → verified free of duplicate execution after scaling out to two

---

## 9. References

- `modules/aimon-scheduling-quartz/README.md`
- `docs/overview/scope-model.md`
- `docs/features/session/web-session-deployment-guide.md`
- `modules/aimon-core/src/main/java/at/aimon/core/scheduling/SchedulingEngineBuilder.java`
- `modules/aimon-session-routing/src/main/java/at/aimon/session/routing/builder/SessionRouterBuilder.java`
- `modules/aimon-scheduling-quartz/src/main/java/at/aimon/scheduling/quartz/QuartzTaskSchedulerBuilder.java`
- [Quartz Scheduler Configuration Reference](http://www.quartz-scheduler.org/documentation/quartz-2.3.0/configuration/)
