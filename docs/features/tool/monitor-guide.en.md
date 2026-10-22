---
translated_from: docs/features/tool/monitor-guide.md
source_commit: fba6ea54
---

# Background Monitor Guide

How to use the `Monitor` / `MonitorStop` tools, which let an agent **keep working while it watches long-running
external state** — a log, an event stream, alarms. Every time the watched source produces an event, its content
arrives in the agent's conversation as a message. The agent does not poll.

Why it is built this way is in the design document [`monitor.md`](../../design/tool/monitor.md).

---

## 1. When to use it

| You want to | Use |
|-------------|-----|
| run one command and see the result when it ends | `Bash` (for long ones, `run_in_background` + `BashOutput`) |
| run work at a set time or interval | scheduling (`schedule_task`) |
| **watch output that never ends, and hear about every event** | `Monitor` |

Run `tail -f` with `Bash(run_in_background)` and you can read nothing: a background `Bash` command's output is filled
in only once the command **ends**. `Monitor` hands each line, each message, over as an event the moment it appears.

---

## 2. Turning it on

Monitors are **off by default.** They put messages into a conversation that nobody typed, and the command form runs
a shell command for up to half an hour.

### 2.1 Spring Boot

```yaml
aimon:
  tools:
    monitor:
      enabled: true
```

Every `MonitorSourceFactory` bean is registered as a monitor source (§5), and a `MonitorStore` bean, if there is one,
records the running monitors (§8).

**Command monitors follow `Bash`.** The built-in `shell` source exists only while `aimon.tools.bash.enabled` is on —
`Bash` is off by default in Spring Boot, so turning on monitors alone offers `ws` and host sources only. Turn the
WebSocket source off with `aimon.tools.monitor.websocket-enabled: false`.

### 2.2 CLI

The `cli` section of `aimon.yaml`:

```yaml
cli:
  enableMonitor: true
```

### 2.3 Assembling an `AimonStack` yourself

```java
AimonStackSpec spec = AimonStackSpec.builder()
        // ...
        .tools(ToolSpec.builder()
                .monitorEnabled(true)
                .addMonitorSource(new AlarmSourceFactory(alarmClient))   // optional — §5
                .build())
        .build();
```

The stack builds one `MonitorManager`, shared by every agent runtime, and closes it with the stack. A stack with
`bashEnabled(false)` has no `shell` source, and `monitorWebSocketEnabled(false)` leaves out the `websocket` source.

### 2.4 Core only

`MonitorManager` takes, when it is built, the **message queue** it puts events into. It must be the same queue the
executor was given.

```java
MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
MonitorManager monitors = MonitorManager.builder(queue).build();   // application lifetime — close() at shutdown

// the executor must drain the same queue
OrcaAgentExecutorFactory executorFactory = new OrcaAgentExecutorFactory().withMessageQueueManager(queue);

// add it to the tool providers you build runtimes with
List<OrcaToolProvider> providers = new ArrayList<>(OrcaAgentRuntimeFactory.defaultToolProviders());
providers.add(new OrcaMonitorToolProvider(monitors));
```

---

## 3. How the agent uses it

`Monitor` takes a `description` and **exactly one of `command` / `ws` / `source`**.

| Field | Shape | One event is |
|-------|-------|--------------|
| `command` | a shell command | one line of standard output |
| `ws` | `{"url": "wss://…", "protocols": ["v1"]}` | one WebSocket text message |
| `source` + `params` | a kind the application registered, and its parameters | whatever the source decides |
| `timeout_ms` | optional; 5 minutes by default, 30 at most | — |

```json
{
  "description": "errors in deploy.log",
  "command": "tail -f deploy.log | grep --line-buffered -E 'ERROR|FATAL|Traceback'",
  "timeout_ms": 1200000
}
```

```json
{ "description": "critical alarms", "source": "alarms", "params": { "severity": "critical" } }
```

Starting one returns its id, as in `Monitor mon_3f9a1c2e started (shell): errors in deploy.log`. When there is
nothing more to watch, `MonitorStop(monitorId="mon_3f9a1c2e")` stops it.

For the command form:

- **Every stage of the pipe must emit per line.** `grep` needs `--line-buffered`, `awk` needs `fflush()`. Otherwise
  lines sit in a buffer and no event arrives
- **Filter for failure signals too.** Filter only for the success marker and a dead process stays silent — and that
  silence looks exactly like "still running"
- **Filter at all.** An unfiltered `tail -f` hits the rate limit (§7) and is stopped

---

## 4. How events arrive

### 4.1 When

| The session is | The events are injected |
|----------------|-------------------------|
| running a turn | when the next tool-calling iteration ends |
| idle | right before the first LLM call of the **next turn** |

A monitor does **not wake** an idle session. The events wait until the user's next input starts a turn, which reads
them. The CLI is no different: monitor events are not replayed after a turn but go into the start of the next one.

Events go **only to the session that started the monitor.** Other sessions of the same agent do not receive them.

### 4.2 What they look like

```
Monitor mon_3f9a1c2e (errors in deploy.log) — 2 events:
2026-10-22T10:01:02 ERROR connection refused
2026-10-22T10:01:02 ERROR retry 1/3 failed
```

Events within one second are batched into one message. However long a session is idle, each monitor has **one**
message waiting at most — events that arrive meanwhile gather in the next message. When the buffer (200 events,
16,000 characters) overflows, the oldest are dropped and `(N earlier events dropped …)` says so.

### 4.3 When it ends

When a monitor ends on its own, an **end notice arrives exactly once**, after the remaining events.

| Why it ended | Notice |
|--------------|--------|
| the source ended (the command exited 0, the socket closed) | `ended: the source ended (exit code 0)` |
| the source failed (a non-zero exit code, a failed connection) | `ended: the source failed: exit code 2; stderr: …` |
| timeout | `ended: timed out after 20m` |
| rate limit exceeded | `ended: stopped — more than 600 events in a minute…` |
| `MonitorStop` · session deleted · application shutdown | no notice |

To keep watching longer, the agent starts it again.

---

## 5. Without a shell — host sources

In operations automation it is safer to offer **an event source the application already has** as a monitor than a
shell command. The model picks only a kind name and parameters; the connection details, credentials and filters stay
in the application's code.

```java
public final class AlarmSourceFactory implements MonitorSourceFactory {

    private final AlarmClient client;

    public AlarmSourceFactory(AlarmClient client) {
        this.client = client;
    }

    @Override
    public String kind() {
        return "alarms";                      // the name the model gives as source
    }

    @Override
    public String describe() {                // one line in the Monitor tool's description
        return "alarms — platform alarms; params: severity (critical|warning)";
    }

    @Override
    public MonitorSource create(MonitorRequest request) {
        String severity = request.getStringParam("severity").orElse("critical");
        if (!severity.equals("critical") && !severity.equals("warning")) {
            throw new MonitorRequestException("severity must be critical or warning");   // goes to the model as is
        }
        return sink -> {
            AlarmSubscription sub = client.subscribe(severity,
                    alarm -> sink.emit(alarm.summary()),          // one event
                    () -> sink.complete("stream closed"),          // normal end
                    error -> sink.fail(error.getMessage()));       // failure
            return sub::cancel;                                    // called on MonitorStop · timeout · shutdown
        };
    }
}
```

The contract a source keeps:

- **`start` does not block.** A source that needs a thread starts its own. The sink may be called from any thread
- **Every way it ends goes through `complete` or `fail`.** A source that dies silently makes the model wait until the
  timeout
- **`MonitorHandle.stop()` is idempotent, does not block for long, and does not throw.** Sink calls after a stop are
  ignored
- Check in `create` whatever can be checked, and refuse with `MonitorRequestException` — its message goes to the model

Register it with `ToolSpec.Builder.addMonitorSource(...)`; in Spring Boot a bean is enough. The default kinds are
`shell` and `websocket`, and registering a factory under the same kind name replaces the default.

---

## 6. Permissions

An agent with an allowed-tools list needs `Monitor` on it to use monitors.

| Entry | What it allows |
|-------|----------------|
| `Monitor` | every form — commands also follow the `Bash` rule below |
| `Monitor(source:alarms)` | the `alarms` source only |
| `Monitor(ws:wss://events.example.com/*)` | WebSocket URLs starting with that prefix only (a trailing `*` is the only wildcard — end it with `/*`: `…example.com*` also matches `…example.com.evil.net`) |
| `Monitor(tail -f:*)` | commands matching that command pattern only |

**The command form must also pass the `Bash` entries.** An agent allowed `Bash(git:*)` and `Monitor` can monitor
only commands starting with `git`, and an agent with no `Bash` entry at all cannot run command monitors. This keeps
a limit placed on `Bash` from being stepped around through `Monitor`.

**A command with a pipe matches no command pattern.** Command patterns always refuse a command containing shell
metacharacters (`|` `;` `&` `$` `>` `<` `(` `)` …) — the same rule as for `Bash`. So a typical watch command such as
`tail -f app.log | grep --line-buffered ERROR` runs only when `Monitor` and `Bash` are both allowed **without a
pattern**. Where commands have to be narrowed by pattern, offer the source as a host source (§5) instead of a shell
command.

WebSockets connect to public hosts only. Private, loopback and link-local addresses are refused by the URL check, and
once more by the check on the address actually connected to.

---

## 7. Limits

| Item | Default |
|------|---------|
| timeout | 5 minutes by default, 30 at most |
| batching window | 1 second |
| length of one event | 2,000 characters (cut beyond) |
| waiting buffer | 200 events · 16,000 characters |
| rate limit | 600 events a minute — beyond it, the monitor is stopped |
| concurrent monitors per owner (session) | 8 |
| concurrent monitors per node | 64 |

`ToolSpec` and Spring Boot build the manager with the defaults. To change them, use the same-named
`MonitorManager.Builder` methods (`maxEventsPerMinute`, `maxTimeout` …) in a core assembly (§2.4).

---

## 8. Lifetime and multiple nodes

- The manager has **application lifetime.** Monitors keep running when an agent runtime is evicted
- **Deleting a session** stops that session's monitors — only those on the node that handled the delete
- **An execution without a session** (a scheduled routine, a fork nobody's turn asked for) cannot start a monitor:
  there is nowhere to deliver its events. A subagent spawned by a session's turn can, and its events go to that
  session
- A monitor runs **on the node that started it.** With a `MonitorStore` shared between nodes, a `MonitorStop` on
  another node answers "runs on another node". If the session moves to another node, the default in-memory queue does
  not carry the events with it

---

## 9. Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| `This environment's shell cannot stream …` | The execution environment's shell does not support line streaming (`ShellFeature.STREAMING`) or cancellation. The local shell supports both; check whether a remote sandbox implements them |
| `A monitor needs a session …` | It was started from an execution without a session (§8) |
| It started, but no events arrive | A stage of the pipe buffers (`--line-buffered`, `fflush()`). Or the session is idle — they arrive with the next turn |
| `stopped — more than 600 events in a minute` | Output went through unfiltered. Narrow the filter and start it again |
| `ws.url points at an address this application does not allow` | A private or loopback address. Only public hosts are allowed |
| `Tool 'Monitor' not allowed (command: …): it must also be allowed for 'Bash'` | The command does not match the `Bash` entries (§6) |
| `You already run 8 monitors, the limit` | Stop the monitors no longer needed with `MonitorStop` |
