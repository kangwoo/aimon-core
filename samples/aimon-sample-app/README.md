# aimon-sample-app

A Spring Boot application that embeds AIMON through
[`aimon-spring-boot-starter`](../../modules/aimon-spring-boot-starter) and does nothing else.

It is not a tutorial and not a starting template. **It exists to be packaged.** Every other test in this
repository runs against an exploded class path — a `build/classes` directory and a list of jars — because
that is what Gradle hands a test JVM. A fat jar is a different class path: resources live in nested
archives, `getResource` returns URLs with a scheme the JDK has never heard of, and a "directory" stops
being listable. AIMON reads skills out of exactly those places, so the only honest way to know whether
packaging works is to package something and run it.

If you want a starting point for your own application, read
[`docs/features/`](../../docs/README.md) instead. If you want to know whether skills survive `bootJar`,
this is the module that answers.

## What it proves

The module is deliberately minimal in every direction except one. It declares **one** agent bundle in its
own resources (`agents/sample/agent.md`), depends on **two** sibling jars that contribute skills to that
same bundle without being named anywhere in this code, and answers with a scripted `LlmClient` so a turn
needs no credentials.

That shape is what makes four separate claims testable — each can fail while the others pass:

| Claim | Why one jar or one layout would not show it |
|-------|---------------------------------------------|
| Both dependency jars contribute their skills | Reading only the first class-path root is a regression this framework has had. A single-jar sample cannot see it |
| Boot's `jar:nested:` (3.2+) and classic `jar:file:` loaders agree | The skill reader casts a `URLConnection` to `JarURLConnection`. Whether that holds is a fact about the world, not about this repository |
| Packaged and exploded give the same agent | The deployment layout against the development layout. This is the only test in the build that can see them diverge, and they have |
| A bundle whose skills carry no index says so | Loading zero skills in silence is the failure mode; the warning is the assertion |

The split — agent bundle here, skills in dependencies — is also the realistic shape. An integrator writes
their own `agent.md` and pulls skill packs in as dependencies. It is additionally the shape that triggers
the loader's development/production branch: `agents/sample/agent.md` resolves to a `file:` URL exploded and
a `jar:` URL packaged, and the two branches reach for different repositories underneath.

## Running the packaging tests

```bash
./gradlew :aimon-sample-app:packagingTest
```

This builds **two** fat jars (nested and classic loaders) and launches **three** JVMs (nested, classic,
exploded). It is excluded from `./gradlew test` for the same reason the Docker tests are — it is too slow
for the loop a developer runs on every save.

Test output streams the child JVMs' stdout, because that stdout *is* the evidence: a WARN the framework is
required to emit, and a skill list that must be complete.

## Running it by hand

Two profiles, and they cover different things.

**Default profile** — the packaging shape. No shell, no scheduler, approvals denied, a scripted model that
answers verbatim.

```bash
./gradlew :aimon-sample-app:bootRun
```

The server binds port **0** here (`PortFileWriter` reports where it landed), because the packaging tests
launch it repeatedly in parallel and a fixed port would be a source of flakes.

**`live` profile** — everything the starter turns off by default, turned on.

```bash
./gradlew :aimon-sample-app:bootRun --args='--spring.profiles.active=live'
```

The starter's defaults differ from the CLI's on purpose: the shell is off, skill approvals deny, the
scheduling backend is `none`. Those are the paths a Spring-assembled deployment exercises least and knows
least about. The `live` profile turns all three on, binds a fixed port **18080**, and swaps in a model that
actually asks for tools. Nothing in the build depends on this profile — it is for manual verification, and
it may not change what the packaging tests see.

## Endpoints

| Method | Path | Profile | What it reports |
|--------|------|---------|-----------------|
| `GET` | `/aimon/introspect` | all | The class-path shape and, for every configured agent, what its registries and workspace hold |
| `POST` | `/aimon/turn` | all | Runs one turn against the scripted model |
| `POST` | `/aimon/turns` | `live` | Runs a turn with the tool-asking model |
| `POST` | `/aimon/turn-as` | `live` | Runs a turn as a named agent — reaches the approval bundle |
| `GET` | `/aimon/scheduled` | `live` | What the Quartz-backed scheduler currently holds |

`/aimon/introspect` exists because a fat jar's class path only exists **inside** the process that was
launched. A test running in the parent JVM cannot see it; asking the child is the only way.

## Agent bundles

| Bundle | Where it lives | Why |
|--------|---------------|-----|
| `sample` | this module's `agent.md` + skills from both sibling jars | The main assertion |
| `noindex` | [`aimon-sample-skills-beta`](../aimon-sample-skills-beta) | Ships skills with **no index**, so the framework must say so out loud |
| `approval` | this module, configured only under `live` | Carries `guarded-open` / `guarded-shut` — a pair that makes the approval chain's two outcomes distinguishable |

`approval` is declared only in the `live` profile even though its bundle sits in this module's resources
under both. An agent nobody configures is never loaded, so the packaging tier keeps seeing exactly the two
agents it asserts on.

## Related

- [`aimon-sample-skills-alpha`](../aimon-sample-skills-alpha) — the first contributing jar
- [`aimon-sample-skills-beta`](../aimon-sample-skills-beta) — the second, plus a bundled subagent
- [`docs/features/skill/builtin-agent-skill-guide.md`](../../docs/features/skill/builtin-agent-skill-guide.md) — how bundled skills are declared
- `build.gradle.kts` in this directory — why each dependency is `runtimeOnly` rather than `implementation`
