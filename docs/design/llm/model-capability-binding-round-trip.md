# Design — #82: assert the round trip, not the shape

> Status: **IMPLEMENTED.**
>
> **§11, appended after the build, is where this document is wrong.** Everything above it
> is the design as written before any code moved. The house habit in this directory is to keep the
> reviewed body byte-exact and record the departures in a trailing section rather than editing the
> body; §11 is that section's reserved place.

> Target: `aimon-cli` (`at.aimon.cli.factory`, `at.aimon.cli.config`), `aimon-spring-boot-starter`
> (`at.aimon.spring.boot.autoconfigure`), and one new unpublished module,
> `aimon-llm-capability-testkit`. `aimon-core` is read but not changed.
>
> **No API call was made for this document.** Every fact below is read out of the tree on
> **2026-09-10**, on branch `herdr/binding-coverage-round-trip` at base `6c4d2e2`. Line numbers are
> anchors for review, not addresses to patch blindly. §9 is the list of what this leaves unresolved.
>
> Prior documents this one continues and does not restate:
> `docs/design/llm/model-capability-config-key.md` — §2.1 (one translator, two surfaces), §2.6 (leaf
> names are transcriptions of the Java fields), the #69 block at `:384-431` (the eighth key, and the
> two guards this document rewrites), R13 (why a package-private extraction beats widening a
> published surface for a test);
> `docs/design/llm/reasoning-effort-config-surface.md` §K-2 / §K-4 (the ladder pair, and why the
> configuration key name did not follow the SPI rename).
>
> It is in English, matching its siblings in `docs/design/llm/`; `docs/design/` is not a translation
> target either way (`docs/project/documentation-guide.md` §5.1).

---

## 0. The decisions, first

| # | Question | Answer |
|---|---|---|
| **B-1** | What is asserted, and against what? | **A whole-declaration equality at the forwarding seam.** For one declarable key, write one value onto the surface, run that surface's own hand-written forwarding, and assert the result `isEqualTo` `ModelCapabilityDeclaration.builder().<key>(value).build()`. The type's own `equals` covers *arrived*, *arrived with this value*, and *nothing else arrived*, in one assertion whose failure prints both `toString()`s. §2.4 |
| **B-2** | How is a value synthesised for an arbitrary setter parameter type? | **A small explicit table, keyed by the setter's generic parameter type, returning two distinct values** — `Boolean`, enum, `Set`/`List`/`Collection` of enum, `String`, `Integer`/`Long`. A type not in the table **throws** and names the key, the type, and the one file to extend. Nothing is ever skipped. §2.2 |
| **B-3** | Why *two* values per key rather than one? | One value proves the key *arrived*; two prove the value is *carried*. A forwarding that hard-codes a constant (`.thinkingDialect(BUDGETED)`) passes a one-value probe whenever the probe guessed that constant. §2.2 |
| **B-4** | How is the value written onto a surface whose property type differs from the builder's? | A one-way coercion table in the testkit: `Set<E>` → `List<E>` (today's only real case), `Enum` → `String`, `Collection<Enum>` → `List<String>`, `Boolean` → `String`. The **expected** declaration is always built from the un-coerced value, so the assertion is stated in the builder's terms and the coercion cannot launder a wrong answer. An unwritable pairing throws and names both types. §2.3 |
| **B-5** | One key at a time, or all eight at once? | **One at a time.** The ladder keys are mutually exclusive, so "all at once" is not expressible; and with exactly one key written, a dropped forwarding leaves the builder empty, which `build()` already refuses **by name**. The probe catches that refusal and rewrites it into a message naming the key and the forwarding method. §2.5 |
| **B-6** | One guard for both surfaces, or two? | **One contract, two subjects.** A new unpublished module, `aimon-llm-capability-testkit`, holds the contract; each surface module keeps a ~30-line subclass, so there is still one test class per surface module and a failure still names its module. §3 |
| **B-7** | The CLI's forwarding is `private`. What gives the test a seam? | Widen `LlmClientFactory.declarationOf(String, ModelCapabilityConfig)` (`:252`) from `private` to **package-private**, with the javadoc line this module already writes for `anthropicConfig` / `openAiConfig`. No behaviour changes; this is R13's answer applied one method deeper. §4.2 |
| **B-8** | Does the #69 assertion survive? | **Yes, as check 1 of the contract, and stronger.** It was one `containsAll` over names; it becomes one case per key that also states the property must be readable *and* writable, which is what the forwarding actually needs. §2.6 |
| **B-9** | Does the new guard replace the existing per-key end-to-end tests? | **No, and it cannot.** The probe writes through bean setters, so it never exercises a binder. `AimonPropertiesValidationTest` (property strings through a real context) and `LlmClientFactoryTest` (yaml objects through the registry) stay exactly as they are. §2.7 |

---

## 1. The problem, in one paragraph

The two guards #69 added assert that a key's **name** exists on a configuration surface, not that the
key's **value** is forwarded to `ModelCapabilityDeclaration.Builder`. Both collect the single-argument
public setters on that builder, collect the bean properties of their surface, and assert the second
`containsAll` the first — and then stop, one step short of the only code that has to be written by
hand for every new key: `LlmClientFactory.declarationOf` (`modules/aimon-cli/src/main/java/at/aimon/cli/factory/LlmClientFactory.java:252`)
and `AimonProperties.ModelCapabilityProperties.toDeclaration`
(`modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/AimonProperties.java:1697`).
A ninth key with a getter and a setter on both surfaces and no forwarding call therefore reproduces
#69 — a key an operator can write, that binds, that produces no message, and that the descriptor the
client reads does not carry — with both guards reporting success. Nothing is broken today (each of
#69's two keys has separate end-to-end coverage, and both forwardings do call all eight setters); this
is about the guard's reach for the next key.

---

## 2. The approach — a round trip, generated from the builder

### 2.1 Where the seam is, and why there

The chain an operator's value travels has four links:

```
   yaml / properties text
        │  (1) binder        Jackson (CLI, fails on unknown fields) / Boot relaxed binding (starter, ignores them)
        ▼
   ModelCapabilityConfig · AimonProperties.ModelCapabilityProperties      ← the surface, eight bean properties
        │  (2) forwarding    declarationOf(...) / toDeclaration()          ← HAND-WRITTEN, ONE LINE PER KEY
        ▼
   ModelCapabilityDeclaration                                             ← the neutral declaration
        │  (3) resolve()     ModelCapabilityDeclaration.capabilities()
        ▼
   ModelCapabilities → InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(...)
```

The old guards look at link (2)'s *input* and assert it has the right shape. #82 is a defect of link
(2) itself. So the probe writes at the surface and reads at the declaration: it drives link (2) end to
end and nothing else.

It is deliberately **not** stretched over link (1). A binder-level probe would have to render every
synthesised value as text in two different notations (a yaml sequence, a comma-delimited relaxed
list), and its failures would be ambiguous — a red would mean "the name did not bind" *or* "the value
was dropped", and disambiguating them is the reader's problem. Link (1) is already covered per key by
the existing tests (§2.7), and what remains uncovered there is registered as an open question (§9,
O-2) rather than assumed away.

It is also deliberately not stretched over link (3). `capabilities()` would work — with one key
written, a dropped forwarding makes `build()` throw, so the "is the effect observable against
`unknown()`?" hazard never arises — but it drags registry semantics into a binding test, and the two
surfaces' reachable end points differ (`AimonProperties.modelCapabilityRegistry(llm)` is public, the
CLI's equivalent is inside `openAiConfig`). Asserting at the declaration keeps both subclasses testing
the same chain, which is what makes "one contract" a true sentence rather than a shared file name.
Link (3) is itself an unguarded hand-written forwarding, in `aimon-core`, and that is O-1.

### 2.2 Generating the value — the part that has to survive key nine

The key list is what it already is in both guards: the single-argument public methods on
`ModelCapabilityDeclaration.Builder` that return `Builder`. Discovery moves into
`DeclarableKeys.names()`, unchanged in behaviour.

For each key, `ProbeValues.distinctPairFor(key)` returns **two distinct values** of the setter's
generic parameter type (`Method.getGenericParameterTypes()[0]`):

| Setter parameter type | The pair | Notes |
|---|---|---|
| `Boolean`, `boolean` | `TRUE`, `FALSE` | today: the five `supports*` keys |
| enum `E` | `E.values()[0]`, `E.values()[1]` | today: `lowestReasoningEffort` → `NONE`, `MINIMAL`; `thinkingDialect` → `UNKNOWN`, `EITHER`. `UNKNOWN` is a legitimate declared value here — the declaration's own javadoc says declaring it is not the same as omitting it |
| `Set<E>` / `List<E>` / `Collection<E>`, `E` an enum | `EnumSet.of(v0)`, `EnumSet.of(v0, v1)` | today: `acceptedReasoningEfforts`. Both non-empty, because `ModelCapabilities.Builder` refuses an empty ladder by name |
| `String` | `"aimon-probe-a"`, `"aimon-probe-b"` | no such key today |
| `Integer`/`int`, `Long`/`long` | `1`, `2` | no such key today |
| **anything else** | **`AssertionError`** | names the key, the parameter type, and `ProbeValues` as the single place to extend |

Two rules govern this table, and they are the ones that make it safe to leave standing:

1. **An unknown type fails loudly; it is never skipped.** A guard that silently passes over the key it
   cannot handle is the same defect as #82 wearing a different hat. The message is written for the
   person who just added key nine and names the file, the method and the line they must extend.
2. **A type that cannot yield two distinct values fails loudly too** — a single-constant enum, or a
   collection of one. The message says why two are needed, because the reason (§B-3) is not obvious
   from the failure.

There is one escape hatch, and it cannot be used to disable a key: a subclass may override
`Optional<List<Object>> valuesFor(String key)` to supply a hand-picked pair for a key whose
synthetic value its surface legitimately refuses (a future `String` key with a format, say). The
default returns empty. There is no return value that means "skip". Because this hook is an instance
method, values are obtained **inside** the test method — override first, `ProbeValues` second — and
never from the static `@MethodSource`, which could not see an override (§7.1).

**Why two values.** With one value, a forwarding that ignores the surface and hard-codes the constant
the probe happened to guess is green. Two distinct values make "carried" and "constant" different
observations, and the whole subject of this issue is a value that is not carried.

### 2.3 Writing it onto a surface

`SurfaceWriter.write(surface, key, value)` finds the bean property named `key` on `surface.getClass()`
(`Introspector.getBeanInfo(type, Object.class)`), and writes the value through its write method,
coercing when the surface's declared parameter type is not assignable from the value's class:

| value | surface property type | written |
|---|---|---|
| any | assignable | as is |
| `Collection<E>` | `List` / `Collection` | `new ArrayList<>(value)` — **today's only live case** |
| `Collection<E>` | `Set` | as is |
| `Enum` | `String` | `value.name()` |
| `Collection<Enum>` | `List<String>` | the names, in iteration order |
| `Boolean` / number | `String` | `String.valueOf(value)` |
| anything else | — | **`AssertionError`** naming the key, the builder's type and the surface's type |

Measured today, both surfaces declare **byte-identical** field types for all eight keys — five
`Boolean`, `ReasoningEffort`, `List<ReasoningEffort>`, `ThinkingDialect` — so the only coercion that
fires is `Set` → `List`. The `Enum` → `String` and `Collection<Enum>` → `List<String>` rows exist
because the premise the issue states for the duplication (§3) would, if it ever became true again,
land exactly there; they are unit-tested in the testkit's own tests (§7.2) rather than left as an
untested claim.

**The coercion is one-way.** The expected declaration is built from the *un-coerced* value, so if a
surface ever holds strings and its forwarding parses them, the assertion still reads
`thinkingDialect = EITHER` and not `"EITHER"`. A coercion that could also shape the expectation could
launder a wrong answer into a green.

### 2.4 Asserting arrival

```java
final S surface = newSurface();
SurfaceWriter.write(surface, key, value);

final ModelCapabilityDeclaration actual = forward(surface);          // the subclass's own forwarding
final ModelCapabilityDeclaration expected = DeclarableKeys.expectedDeclaration(key, value);

assertThat(actual).as(probeMessage(key, value)).isEqualTo(expected);
```

`ModelCapabilityDeclaration` already implements `equals`/`hashCode` over all eight fields (and not
over the derived `capabilities`), and a full `toString`. So this one assertion states three things at
once — the key arrived, it arrived with *this* value, and **no other key arrived** — and AssertJ's
failure prints both declarations side by side, where the null field is visible without reading the
test.

The third of those is worth having explicitly. The forwarding is a chain of eight nearly identical
lines, which is the shape that invites `.supportsToolsWithReasoning(capabilities.getSupportsReasoningEffort())`.
The half of that copy-paste where the wrong getter is read is caught by the key arriving as `null`;
the half where the *same* getter feeds two setters is caught only by "nothing else arrived".

A consequence to state plainly: a forwarding that **defaults or derives** a key rather than copying it
will fail this contract. That is intended. `toDeclaration()`'s own javadoc already claims to be "a
field copy and nothing more — what an omitted flag means is decided by `ModelCapabilityDeclaration`",
and this assertion is that claim, enforced. If a surface ever needs to depart from it, the departure
belongs in the contract, visibly, once — not in a per-surface hook that lets one surface quietly hold
itself to less.

**The probe checks that `equals` can see the key before trusting it.** The whole assertion rests on
`equals` comparing the key under test, and a ninth field added to the builder but forgotten in
`equals` would leave the "arrived" half intact (a dropped forwarding still makes `build()` refuse) and
quietly remove the "carried" half — a wrong value would compare equal. So before the round trip the
probe asserts `expectedDeclaration(key, v1)` is **not** equal to `expectedDeclaration(key, v2)`, and
if they are equal it fails with "`ModelCapabilityDeclaration.equals` does not compare `<key>`; this
contract cannot see a wrong value for it". Three lines, and it turns a silent weakening into a
build failure.

**Why not read the declaration's per-key `Optional` getter instead.** It works today (every setter has
a same-named `Optional<T>` accessor), but it needs a second reflective name mapping that can go stale
independently, it gives up the "nothing else arrived" half, and it makes the guard's correctness
depend on a naming convention the type never promised. Whole-object equality depends only on `equals`,
which the type does promise.

### 2.5 One key at a time, and the loud failure it buys

Two keys cannot always be written together: `build()` refuses a declaration stating both
`lowestReasoningEffort` and `acceptedReasoningEfforts`, on purpose. One key at a time sidesteps that
without a special case — and buys something better.

With exactly one key written and its forwarding missing, the builder receives **nothing**, and
`build()` already refuses that by name:

> A model capability declaration must state at least one of supportsSamplingParameters, … An entry
> that states none of them registers the same fail-open capabilities the model already had, so it
> would bind and do nothing …

Correct, and addressed to the wrong reader — an operator who wrote an empty entry, not a developer who
forgot a forwarding line. So the probe wraps the `forward(surface)` call and translates that one
refusal:

> `thinkingDialect` was written on `at.aimon.cli.config.ModelCapabilityConfig` (yaml key
> `llm.modelCapabilities.<model>.thinkingDialect`) and `LlmClientFactory.declarationOf` produced a
> declaration that states nothing at all — so that method does not read this key. Add
> `.thinkingDialect(capabilities.getThinkingDialect())` to it.

That message is the deliverable of this issue as much as the red is.

### 2.6 Check 1 — the #69 assertion, preserved and sharpened

The contract keeps a second, separate case per key: the surface has a bean property of that name with
**both** a read method and a write method. That is #69's guard verbatim in intent, moved from one
`containsAll` over the whole list to one case per key, so a failure names the missing key instead of
printing two lists.

It is kept rather than folded into the round trip even though the round trip subsumes it — writing
fails when there is no setter — because the two failures deserve different sentences. "This surface
has no key for `thinkingDialect`" (add the property) and "this surface has the key and the forwarding
drops it" (add the line) are different jobs for different people, and #69 and #82 are respectively
each one of them.

It also keeps the *read* method requirement, which the round trip alone would not: a write-only
property binds and leaves the forwarding nothing to read.

### 2.7 What this does not cover, and what still covers it

The probe writes through bean setters. It therefore says nothing about link (1) — that
`aimon.llm.model-capabilities.x.thinking-dialect=budgeted` relaxes to `ThinkingDialect.BUDGETED`, or
that `llm.modelCapabilities.x.thinkingDialect` is spelled the way Jackson expects. Those remain
covered exactly where they are now, per key and by hand:

| surface | what covers link (1) today |
|---|---|
| CLI | `LlmClientFactoryTest.ModelCapabilityDeclarations` (yaml-shaped objects through `openAiConfig` / `anthropicConfig` into the registry), plus Jackson's fail-on-unknown-field, which makes a *missing* CLI property loud on its own |
| starter | `AimonPropertiesValidationTest` (~30 references to `aimon.llm.model-capabilities.*`, driven as property strings through a real `ApplicationContextRunner`) |

Neither is weakened and neither is duplicated away — acceptance criterion 3. The generality gap that
remains (link (1) is guarded per key, by hand, so key nine gets no binder-level coverage
automatically) is O-2.

---

## 3. One guard or two — the decision, and the claim it overturns

**Decision: one contract, two subjects.** The shared half moves into a new unpublished module,
`aimon-llm-capability-testkit`; each surface module keeps a subclass. The reason is written in the
class javadoc of `AbstractModelCapabilityBindingContractTest` and in the new module's
`build.gradle.kts`, following the two testkits that already carry their rationale in exactly those two
places.

### 3.1 What the CLI guard's javadoc says today, and which half of it survives

`ModelCapabilityConfigBindingTest` states:

> It cannot live in `aimon-core`: that module cannot see this one or the starter, so a test there can
> only check the refusal message against the declaration type — which was already true when #69 was
> filed. This module and `aimon-spring-boot-starter` both depend on core, so each can see the list;
> the starter's half is `AimonPropertiesBindingCoverageTest`, and the pair is what makes "a key exists
> on the declaration and no surface binds it" fail a build rather than become an issue.

The first sentence is **true and unchanged**: `aimon-core` cannot see either surface, and the test
there is still a message-vs-declaration consistency check (`ModelCapabilityDeclarationTest.theRefusalMessageNamesEveryDeclarableKey`).

What is overturned is the step from that sentence to "therefore a pair". *Not core* does not mean
*twice*. This build already contains three modules that exist for precisely this shape — a contract
several modules must satisfy, described once, subclassed by each subject: `aimon-filesystem-testkit`,
`aimon-session-testkit`, `aimon-memory-testkit`. A fourth is not a new idea here; it is the idea this
repository already reached for three times, applied to the guard instead of to the subject.

### 3.2 The issue's stated reason for the duplication is not true today

The issue says the guards are "duplicated because the CLI binds enums and the starter binds strings".
Measured on `6c4d2e2`, both surfaces declare identical types for all eight keys:

```
ModelCapabilityConfig                    AimonProperties.ModelCapabilityProperties
  Boolean supportsSamplingParameters       Boolean supportsSamplingParameters
  Boolean supportsReasoningEffort          Boolean supportsReasoningEffort
  Boolean supportsToolsWithReasoning       Boolean supportsToolsWithReasoning
  Boolean supportsReasoningTraceRoundTrip  Boolean supportsReasoningTraceRoundTrip
  ReasoningEffort lowestReasoningEffort    ReasoningEffort lowestReasoningEffort
  List<ReasoningEffort> acceptedReasoning… List<ReasoningEffort> acceptedReasoning…
  ThinkingDialect thinkingDialect          ThinkingDialect thinkingDialect
  Boolean supportsReasoningSummary         Boolean supportsReasoningSummary
```

and `git log -S "private String lowestReasoningEffort" -- …/AimonProperties.java` returns nothing —
the starter has bound the enum since the surface was introduced (`6a07573`), for the reason its own
javadoc gives (the configuration processor records the type, so an IDE offers the constants and no
hand-written metadata hint is needed; `model-capability-config-key.md` R9 is the same argument, made
when the surface was designed).

Where the enum/string difference **is** real is one layer below: Boot converts a property *string*,
Jackson deserializes a yaml *scalar*. That difference lives entirely in link (1), which §2.1 keeps the
shared contract out of. So the premise is false at the layer that would have made a shared contract
impossible, and true only at a layer the contract does not touch. And should it become true again at
the surface — a future starter key bound as `String` — §2.3's coercion table already carries that row.

### 3.3 The argument for one, stated as this repository states it

`model-capability-config-key.md` §2.1 justified a single shared translator like this: the rules are
one thing, "두 곳에 복사되면 두 표면이 서로 다른 답을 하는 날이 오고, 그 어긋남은 조용하다". The
guard is the same shape of object. Two copies of a guard drift the same way two copies of a rule do,
and the drift is quieter still — a weakened guard reports success, which is the exact failure mode
#82 is about, one level up. Making both surfaces answer to one written statement turns "these two
guards say the same thing" from a review promise into a compile-time fact.

The concrete arithmetic: the shared half is roughly 180 lines — key discovery, two-value synthesis
with its loud-failure paths, the coercion table, the expected-declaration construction, the probe's
message. The per-surface half is four short overrides. Duplicating 180 lines of reflection to save one
subproject is the trade the instruction "do not force a merge that costs more than the duplication"
exists to prevent in the other direction; here the merge is cheaper than the duplication by an order
of magnitude.

### 3.4 Why a module rather than test fixtures or core

- **`java-test-fixtures` on `aimon-core` does not work in this build.** `aimon-filesystem-testkit`'s
  build script records the measurement: the publishing plugin (`com.vanniktech.maven.publish` 0.30.0)
  reacts to that plugin by calling a Gradle internal constructor removed in Gradle 9, and
  configuration fails outright with `NoSuchMethodError: ProjectDerivedCapability.<init>`.
- **No module in this build consumes another's test output.** `grep` for `testArtifacts`,
  `test.output`, `testFixtures` across every `*.kts`: zero hits. There is no established second
  mechanism to reuse.
- **`aimon-core`'s main sources are out** — a published module would gain JUnit and AssertJ on its
  compile classpath to hold a test helper.
- **`aimon-bootstrap` is out** for the same reason, and because neither surface's `LlmClient` is built
  by the stack (`model-capability-config-key.md` R10 already settled that boundary).

The new module is **not published** (`aimon.publishable` absent), which also keeps it out of
`aimon-bom` automatically, out of `PublishedModuleApiScopeTest` / `PublishedModuleLoggingBindingTest`
(both scoped to modules applying that plugin), and out of `CLAUDE.md`'s Module Structure list — that
list carries `aimon-memory-testkit` because it *is* published and omits the other two because they are
not.

### 3.5 What stays true after the merge

There is still **one test class per surface module**, so
`model-capability-config-key.md:429-430`'s sentence — "되풀이 방지는 표면 모듈마다 테스트 하나다
(`ModelCapabilityConfigBindingTest` · `AimonPropertiesBindingCoverageTest`) — 코어에는 쓸 수 없다"
— remains accurate, both class names included. What changes is that the two classes now share their
body. A dated pointer to this document is added there anyway (§10), because a reader arriving at that
line will want to know where the body went.

---

## 4. Concrete changes — by module and file

### 4.1 New: `modules/aimon-llm-capability-testkit`

`settings.gradle.kts` gains an `include(…)` entry with a comment in the register the neighbouring
testkit entries use, and the matching `project(":aimon-llm-capability-testkit").projectDir` line.

`modules/aimon-llm-capability-testkit/build.gradle.kts` — modelled on `aimon-session-testkit`'s, with
its own reasons rather than a copied comment:

```kotlin
plugins {
    id("aimon.java-conventions")
}

dependencies {
    // `api`: subclasses in aimon-cli and aimon-spring-boot-starter implement hooks returning
    // ModelCapabilityDeclaration and inherit JUnit-annotated methods, so both belong on their
    // compile classpath. Nothing here is published, so the "don't leak core through implementation
    // modules" rule has no POM to protect.
    api(project(":aimon-core"))

    // JUnit and AssertJ are compiled against by this module's *main* source set — see the same two
    // lines in aimon-filesystem-testkit for why the platform is named here rather than inherited.
    api(platform(libs.spring.boot.dependencies))
    api(libs.bundles.testing)
}
```

Main sources, package `at.aimon.llm.capability.testkit` (the `at.aimon.<domain>.testkit` shape the
other three use):

| Type | Responsibility |
|---|---|
| `DeclarableKeys` | Discovery. `names()` (the `@MethodSource` for both checks), `setterFor(key)`, `expectedDeclaration(key, value)`. Named so it cannot be read as the CLI's `declarationOf` |
| `ProbeValues` | Synthesis. `distinctPairFor(key)` and the §2.2 table; throws on an unknown type |
| `SurfaceWriter` | Coercion + bean write. The §2.3 table; throws on an unwritable pairing |
| `ModelCapabilityBindingProbe` | One round trip: write, forward, compare, and translate `build()`'s empty-declaration refusal into the message of §2.5. Callable outside JUnit, which is what lets §7.2 test the guard's teeth |
| `AbstractModelCapabilityBindingContractTest<S>` | The JUnit shell: two `@ParameterizedTest`s, four abstract hooks, and the class javadoc that carries §3's decision |

Checkstyle runs on this module's **main** sources (test sources are exempt build-wide, main sources
never are), so the three utility classes need private constructors (`HideUtilityClassConstructor`),
every public type needs a class javadoc (`JavadocType` scope=public), and lines stay under 120.

The four hooks:

```java
/** A fresh, empty instance of the configuration surface under test. */
protected abstract S newSurface();

/** Runs this surface's own hand-written forwarding — the step #82 is about — and returns its result. */
protected abstract ModelCapabilityDeclaration forward(S surface);

/** The key as an operator writes it, for the failure message: llm.modelCapabilities.<model>.thinkingDialect */
protected abstract String operatorKeyPath(String key);

/** Where the forwarding lives, for the failure message: "LlmClientFactory.declarationOf". */
protected abstract String forwardingLocation();
```

Plus the non-abstract escape hatch of §2.2, `Optional<List<Object>> valuesFor(String key)`, defaulting
to empty.

### 4.2 `aimon-cli`

**`modules/aimon-cli/src/main/java/at/aimon/cli/factory/LlmClientFactory.java:252`** — the only
production edit in this round: `private ModelCapabilityDeclaration declarationOf(…)` becomes
package-private, and its javadoc gains the sentence this module already writes twice:

> package-private for the reason `openAiConfig` is — the assembled thing is what a test needs to
> catch, and widening a published surface for test convenience is the trade `model-capability-config-key.md`
> R13 refused.

The sentence is written in Korean, like the two it echoes (`:65`, `:147`); the English above is its
meaning, not its text.

No behaviour changes: no call site moves, no signature changes, and `aimon-cli` applies no
`aimon.publishable`, so nothing about a published POM or API is touched.

**`modules/aimon-cli/build.gradle.kts`** — `testImplementation(project(":aimon-llm-capability-testkit"))`.

**`…/src/test/java/at/aimon/cli/config/ModelCapabilityConfigBindingTest.java` moves to
`…/src/test/java/at/aimon/cli/factory/ModelCapabilityConfigBindingTest.java`**, keeping the class name
so the two design-document citations of it stay true, and becoming:

```java
class ModelCapabilityConfigBindingTest
        extends AbstractModelCapabilityBindingContractTest<ModelCapabilityConfig> {

    private final LlmClientFactory factory = new LlmClientFactory();

    @Override protected ModelCapabilityConfig newSurface() { return new ModelCapabilityConfig(); }

    @Override protected ModelCapabilityDeclaration forward(ModelCapabilityConfig surface) {
        return factory.declarationOf("probe-model", surface);
    }

    @Override protected String operatorKeyPath(String key) {
        return "llm.modelCapabilities.<model>." + key;         // camelCase; the CLI's own spelling
    }

    @Override protected String forwardingLocation() { return "LlmClientFactory.declarationOf"; }
}
```

The move is what makes `forward` possible: `LlmClientFactory` is in `at.aimon.cli.factory`, and
`LlmClientFactoryTest` already lives there.

### 4.3 `aimon-spring-boot-starter`

**`build.gradle.kts`** — `testImplementation(project(":aimon-llm-capability-testkit"))`.

**`…/AimonPropertiesBindingCoverageTest.java`** stays where it is (same package as
`ModelCapabilityProperties`, so the package-private `toDeclaration()` is already reachable) and becomes
the mirror subclass, with `operatorKeyPath` returning
`"aimon.llm.model-capabilities.<model>." + kebab(key)`. The three-line `kebab` helper stays in this
subclass rather than in the testkit: it is Boot's naming rule, and the testkit should not learn one
surface's binder conventions.

No production edit is needed on this side.

### 4.4 Nothing changes in `aimon-core`

`ModelCapabilityDeclaration`, its builder and its `equals` are read, not modified.
`ModelCapabilityDeclarationTest.theRefusalMessageNamesEveryDeclarableKey` — including its
`assertThat(setters).hasSize(8)` tripwire — is left alone; §7.3 explains why that line matters to the
proof this round has to produce.

---

## 5. Data and interface shapes that change

| Shape | Before | After |
|---|---|---|
| `ModelCapabilityDeclaration` and its `Builder` | eight keys | **unchanged** |
| `ModelCapabilityConfig` (CLI surface) | eight bean properties | **unchanged** |
| `AimonProperties.ModelCapabilityProperties` (starter surface) | eight bean properties | **unchanged** |
| `LlmClientFactory.declarationOf(String, ModelCapabilityConfig)` | `private` | **package-private**; signature, body and behaviour unchanged |
| `AimonProperties.ModelCapabilityProperties.toDeclaration()` | package-private | **unchanged** |
| Both guards' public shape | two independent `@Test` classes | two subclasses of one contract; class names and modules unchanged |
| Gradle | 27 included projects | 28 — one new unpublished module |

No configuration key, wire name, DDL, property spelling or published API moves. No `rename-maps.md`
row and no `frozen-names.md` interaction.

---

## 6. Failure modes and how they are handled

| # | What goes wrong | What happens |
|---|---|---|
| **F1** | Key nine added to the builder and to both surfaces; no forwarding call | `build()` receives nothing and refuses; the probe catches that one refusal and rewrites it into §2.5's message, naming the key, the operator spelling, the surface class and the forwarding method. **Red on both surfaces.** This is #82 |
| **F2** | Key nine on the builder, no property on one surface | Check 1 fails for that surface, naming the key and the operator key path. Check 2's write also fails, with the same name. **Red on the surface that is missing it only** — which is the correct blast radius |
| **F3** | Key nine's type is not in the synthesis table | `ProbeValues` throws an `AssertionError` naming the key, the type, and `ProbeValues` as the file to extend. It is raised inside check 2's case **for that key only** — values are synthesised in the test method, not in the `@MethodSource` — so the other keys' cases and check 1 still run and still report honestly. **Never a skip** |
| **F4** | Key nine's type cannot yield two distinct values (single-constant enum) | Loud, with the reason from §B-3 in the message |
| **F5** | A surface's property type is one the coercion table cannot write | Loud, naming both the builder's type and the surface's |
| **F6** | The forwarding is cross-wired — a setter reads the wrong getter | The key arrives absent → whole-declaration equality fails |
| **F7** | The forwarding is cross-wired — one getter feeds two setters | The key arrives, and so does its twin → whole-declaration equality fails on the extra field. This is the case a per-getter assertion would have missed |
| **F8** | A forwarding starts defaulting or deriving a key | Red, deliberately (§2.4). The fix is a decision recorded in the contract, not a per-surface hook |
| **F9** | `Builder` gains a public single-argument fluent method that is *not* a key (`from(...)`, `copyOf(...)`) | Discovery treats it as a key and check 1 goes red on both surfaces. That is a false positive, and it is loud rather than silent; the resolution is either "it is a key, bind it" or an explicit, justified exclusion in `DeclarableKeys`. `ModelCapabilityDeclarationTest`'s `hasSize(8)` is the second tripwire that puts a human in front of this |
| **F10** | Two overloaded single-argument setters share a name | `DeclarableKeys` throws — the key would otherwise be ambiguous and one overload silently chosen |
| **F11** | A future `build()` rule couples two keys ("X requires Y") | One-key-at-a-time would go red. No hook is built for this now (O-3); the red is correct and the design decision belongs to whoever adds the rule |
| **F12** | `equals` stops comparing a key — including a ninth field added to the builder and forgotten in `equals` | The pre-check of §2.4 fails for that key, naming `equals` as the cause, before any round trip runs. Without it this would be the one silent weakening left in the design |

---

## 7. Test strategy

### 7.1 The guard itself

Two `@ParameterizedTest` cases per surface, one behaviour each, both driven by the same fully-qualified
`@MethodSource` reference so no inheritance-resolution subtlety is relied on. The source yields key
names only; check 2 obtains its two values inside the method, so a subclass's `valuesFor` override is
honoured and a synthesis failure stays confined to its own key (F3):

```java
@ParameterizedTest(name = "{0}")
@MethodSource("at.aimon.llm.capability.testkit.DeclarableKeys#names")
@DisplayName("the surface carries a readable and writable property for the key")
void theSurfaceCarriesTheKey(String key) { … }

@ParameterizedTest(name = "{0}")
@MethodSource("at.aimon.llm.capability.testkit.DeclarableKeys#names")
@DisplayName("the value written for the key reaches the declaration")
void theValueReachesTheDeclaration(String key) { … }   // both values; the failing one is named in the message
```

That is 8 + 8 cases per surface today, 32 in total, each named after the key it probes; the value
that failed is in the message. Two values of one key are one behaviour — "the value is carried" — so
they share a case rather than splitting it.
`@ParameterizedTest` is already used in 15 test classes here; `@TestFactory` is used in none, which is
why the dynamic alternative is not taken.

### 7.2 Testing the guard — the testkit gets its own test source set

The other three testkits have no tests of their own. This one should, and the reason is specific: this
contract's entire value is in whether it *fails*, and once both real surfaces are correct nothing else
in the tree ever exercises the failing path. Its own `src/test/java` holds:

- **a complete fake surface** — a local bean with the eight property names and a forwarding lambda that
  copies all eight — asserted to pass every key;
- **the same fake with one key dropped**, asserted to make `ModelCapabilityBindingProbe` throw, with
  the dropped key's name in the message. This is #82's defect, reproduced permanently and in-tree,
  rather than only in a scratch commit that is thrown away;
- **the same fake with one getter feeding two setters** (F7), asserted to throw;
- **`ProbeValues` unit cases**: the pair for each of the five supported shapes is distinct and of the
  right type; an unsupported type (`java.time.Duration`) throws and its message names the type;
- **`SurfaceWriter` unit cases** against a fake bean holding `String` and `List<String>` properties —
  which is where §2.3's enum→string rows get exercised, so the claim in §3.2 ("should the premise
  become true again, the table already carries it") is tested rather than asserted.

The module needs no `gradle/coverage-baselines.properties` entry; a module with no entry gets no rule,
which is what the other testkits already do.

### 7.3 The proof acceptance criterion 1 asks for

A scratch commit, shown red, then reverted. It has to be built carefully or the red proves the wrong
thing: `aimon-core` carries two hard-coded tripwires that a ninth key trips on its own
(`ModelCapabilityDeclarationTest.theRefusalMessageNamesEveryDeclarableKey` asserts
`setters.hasSize(8)` and asserts the refusal message contains every setter name). The scratch commit
must therefore add everything a real ninth key would have **except the two forwarding calls**:

1. `ModelCapabilityDeclaration`: field, `Optional` getter, `Builder` setter, an entry in
   `declaresAnything()`, the name in `build()`'s refusal message, and the field in `equals`/`hashCode`/`toString`
   (leaving it out of `equals` would trip §2.4's pre-check instead — a red for a different reason,
   which would muddy the evidence);
2. `ModelCapabilityDeclarationTest`: `hasSize(8)` → `hasSize(9)`;
3. `ModelCapabilityConfig` and `AimonProperties.ModelCapabilityProperties`: a field and a
   getter/setter pair each;
4. **and nothing in `declarationOf` / `toDeclaration`.**

Expected result: `./gradlew checkAll` red, with the failures being exactly
`ModelCapabilityConfigBindingTest.theValueReachesTheDeclaration` and
`AimonPropertiesBindingCoverageTest.theValueReachesTheDeclaration`, for both probe values, carrying
§2.5's message. Adding the two forwarding lines to that scratch commit turns it green — the second
half of the experiment, worth running because it is what isolates the guard from the tripwires. The
handoff records both runs' output, and `git revert` / branch deletion returns the tree.

`ModelCapabilities` needs no change in the scratch commit: `capabilities()`'s `resolve()` simply will
not mention the ninth field, and this contract asserts on the declaration.

### 7.4 The gate

`./gradlew format` then `./gradlew checkAll` on the final tree, which now also runs the new module's
`checkstyleMain`, `spotlessCheck` and `test` — the root aggregators address every non-`java-platform`
subproject by name, so the new module is picked up with no aggregator edit, and would break the root
build loudly if it forgot `aimon.java-conventions`.

---

## 8. Rejected alternatives

| # | Alternative | Why rejected |
|---|---|---|
| **A1** | **Keep two guards, duplicate the round trip into both.** | §3. The premise offered for the duplication is not true at the layer that matters (§3.2), the shared half is ~180 lines of reflection against ~4 lines of per-surface difference, and two copies of a guard drift exactly the way this repository already refused to let two copies of the translation rules drift (`model-capability-config-key.md` §2.1) — silently, and here with the drifted copy still reporting success |
| **A2** | **Assert at `ModelCapabilities` via the registry instead of at the declaration.** Needs no production edit at all: the CLI reaches it through `openAiConfig(config).getModelCapabilityRegistry()`, the starter through the public `AimonProperties.modelCapabilityRegistry(llm)` | Tempting, and genuinely safe on the "is the effect observable?" axis (§2.1). Rejected because the two surfaces' reachable end points are different methods on different types, so the two subclasses would be testing two chains under one contract's name; because it couples a binding guard to `withDefaultsExtendedBy`'s semantics, giving it a second reason to go red; and because the ladder pair collapses there (`ModelCapabilities` has no `lowestReasoningEffort` getter since `reasoning-effort-config-surface.md` K-2), so a per-key mapping would need a hand-maintained exception — the thing this guard exists to abolish |
| **A3** | **Make the forwarding reflective** so there is nothing to forget: have `declarationOf` / `toDeclaration` copy same-named properties automatically | Solves #82 by deletion, and is out of bounds — the task forbids changing production binding behaviour, and for good reasons of its own: it would silently swallow the `List`→`Set` conversion and the indexed null-rung refusals (#70) that both forwardings carry, and it would replace an explicit, greppable eight-line copy with a name coupling nothing states. Worth recording as a possibility for a later round, not this one |
| **A4** | **A text-reading guard in `aimon-core`**, in the idiom of `PublishedModuleApiScopeTest` — read the two modules' `.java` files and assert `declarationOf` mentions every key | Cheap and needs no new module, and it is the shape check again one level deeper: `.thinkingDialect(capabilities.getSupportsReasoningEffort())` mentions the key and passes. This repository is careful to write down what a text-reading check cannot see; here what it cannot see is the entire defect |
| **A5** | **Assert per-key through the declaration's `Optional` getters** rather than whole-object equality | §2.4. Needs a second name mapping that can go stale on its own, loses "nothing else arrived" (F7), and rests on a naming convention the type never promised, where `equals` is promised |
| **A6** | **One value per key instead of two** | §B-3. A hard-coded constant in the forwarding survives a one-value probe whenever the probe guessed that constant, and "the value is carried" is the property under test |
| **A7** | **Write all keys at once and compare one full declaration** | Not expressible: `build()` refuses the two ladder keys together, on purpose. It would also give up F1's best property — that with exactly one key written, a dropped forwarding produces a *refusal by name* rather than a subtly wrong object |
| **A8** | **Extend the round trip through the binder** (yaml text for the CLI, `withPropertyValues` for the starter) so the guard covers link (1) too | Strictly more coverage, and it makes every red ambiguous between "the name did not bind" and "the value was dropped"; it pushes value *rendering* into two notations, and the rendering rules are the one part that genuinely differs per surface — so the shared half would shrink to the part that is already shared. Registered as O-2 rather than dropped |
| **A9** | **`java-test-fixtures` on `aimon-core`** | Fails configuration in this build; measured and recorded in `aimon-filesystem-testkit/build.gradle.kts` (§3.4) |
| **A10** | **Put the contract in an existing testkit** (`aimon-session-testkit`, `aimon-memory-testkit`) | Wrong domain in both, and `aimon-memory-testkit` is published, which would put an LLM-config guard into a released artifact |
| **A11** | **A `skip` return for a key the generator cannot handle** | The defect under repair is a guard that reports success over a key nothing acts on. A skip is that, with a yellow icon |

---

## 9. Open questions

Listed rather than assumed. None blocks the build; O-1 and O-2 are the two that should be registered
in `docs/backlog/llm-config-surface-open-items.md` if they are not closed in this round, since that
register — not this document's §9 — is where this repository keeps what is open.

- **O-1 — link (3) has the same defect and nobody has asked.**
  `ModelCapabilityDeclaration.resolve(Builder)` (`:76-104`) is a third hand-written per-key
  forwarding, of exactly the shape #82 is about, and unlike the other two it lives in `aimon-core`,
  where a test can see both ends. A ninth key with a builder setter that `resolve` forgets would leave
  the declaration correct and the descriptor the client reads empty. The same probe would guard it in
  ~20 lines. Out of scope as written (#82 names two surfaces), and it is the obvious next key-nine
  hole. **Recommendation: raise it as a backlog item in this round rather than widening the change.**
- **O-2 — link (1) is still guarded per key, by hand.** §2.7. The binder step gets no automatic
  coverage for key nine on either surface. The CLI is partly protected by Jackson's fail-on-unknown;
  the starter is not protected at all (which is backlog `L-1`, still open). A8 is the shape a fix
  would take.
- **O-3 — no hook for a future cross-key `build()` rule.** F11. Deliberately not built (nothing needs
  it), so the day something does, the contract goes red and someone decides. Recorded so that red is
  legible.
- **O-4 — the probe cannot distinguish "the surface refused my synthetic value" from "the forwarding
  dropped it"** for a hypothetical key whose surface validates its input. Both are red, with different
  exceptions; the escape hatch is the per-key value override of §2.2. No such key exists today, and I
  have not designed a message that tells the two apart.
- **O-5 — should the CLI subclass keep its old package?** It moves from `at.aimon.cli.config` to
  `at.aimon.cli.factory` because that is where the forwarding it must call lives (§4.2). The class name
  is kept so the two design-document citations stay resolvable, but a reader looking for tests of
  `ModelCapabilityConfig` under `config/` will no longer find one. I judged the move cheaper than the
  alternatives (widening a second method, or splitting the subclass across two packages), but it is a
  judgement, not a fact.
- **O-6 — the discovery filter's false-positive surface (F9) is not fixed here.** A public
  single-argument fluent method on `Builder` that is not a declarable key would be treated as one.
  That exposure is inherited from the existing guards unchanged, and the two `aimon-core` tripwires
  make it loud rather than silent, so this round does not narrow it.

---

## 10. Bookkeeping

- **`docs/design/README.md`** gains a row in the `llm — 프로바이더 계약` table, written in Korean like
  its neighbours, pointing at this document.
- **`docs/design/llm/model-capability-config-key.md:429-430`** gains a short dated note (Korean,
  matching the dated correction blocks that file already carries) saying the pair of guards now shares
  one contract and where it lives. The sentence itself stays true (§3.5) and is not rewritten.
- **No `CHANGELOG.md` entry.** Nothing user-visible changes: no key, no default, no message an
  operator sees, no published artifact. If the build turns up a real forwarding bug — none is visible
  in either method today, both call all eight setters against the same-named getters — that is a
  behaviour change, needs an entry, and needs saying prominently in the handoff.
- **No `CLAUDE.md` Module Structure entry** for the new module: that list carries published modules,
  which is why `aimon-memory-testkit` is on it and the other two testkits are not (§3.4).
- **No translation work.** `docs/design/**` is not a translation target
  (`docs/project/documentation-guide.md` §5.1), and both files edited above live there. Run
  `python3 scripts/check-doc-links.py` for the new README row's link and anchor.
- **No `rename-maps.md` row** — nothing is renamed.

---

## 11. After the build

*Appended 2026-09-10, after implementation. Everything above this section is the body as approved, kept
byte-exact, with one exception made on precedent: the two header sentences that describe this document's own
state ("Status" and "§11 … is where this document is wrong") were brought up to date, as
`reasoning-effort-config-surface.md`'s header was. Three sources feed this section — the run's
`build/deviations.md`, the design review (`review-1.md`: PASS, no blocking findings, nine non-blocking), and what
was measured while building.*

**No forwarding bug was found.** `LlmClientFactory.declarationOf` and
`AimonProperties.ModelCapabilityProperties.toDeclaration()` both passed the round trip for all eight keys and both
values on the first run. The only production edit is §4.2's visibility change, and no behaviour changed.

### 11.1 What the design review found

The review measured rather than trusting this document. It ran a scratch probe reflectively against the real
classes on both modules' test classpaths — 8 keys discovered, `equals` distinguishing both values of every key,
16 of 16 round trips equal on both surfaces — and confirmed §3.2's identical-types claim, the `hasSize(8)` tripwire
at `ModelCapabilityDeclarationTest.java:119`, and that `declarationOf` was `private`. Its nine findings were errors
in what this document predicts or states, not in what it asks to be built. Two were routed to the build as
corrections to make first:

- **§2.5 never says how "that one refusal" is recognised, and on the CLI it never arrives as itself.**
  `declarationOf` rethrows the core's `IllegalArgumentException` inside a `ConfigurationException`, so a probe that
  catches by type translates on the starter only, and §7.3's "both reds carry §2.5's message" would be false. Acted
  on as D-1.
- **§7.2's "complete fake surface" is a third hand-kept key list.** It would have turned the testkit's own test red
  in the §7.3 experiment, kept the control run red, and made every future key an edit there too. Acted on as D-2.

The other seven were acted on as well: the coverage-floor reasoning (D-3), the task's `@Nested` convention (D-4), an
unvalidated `valuesFor` override being a silent skip (D-5), raw-class assignability in §2.3 (D-6), the old guard
javadoc contradicting §3's decision (D-7), the CLAUDE.md rule being misstated (D-8), and the sentence acceptance
criterion 3 needs about `allowZeroInvocations` (D-9).

### 11.2 Where the implementation departed

**D-1 — the refusal is recognised by its message, anywhere in the cause chain.** `ModelCapabilityBindingProbe`
captures the text of `ModelCapabilityDeclaration.builder().build()`'s refusal at run time and matches an
`IllegalArgumentException` carrying exactly that message at any depth. It never catches by type: anything else a
forwarding throws is reported with the key, the value and the surface, says outright that it is *not* a dropped
forwarding, and keeps the original as its cause — so the ladder-pair refusal, or a future cross-key rule, cannot be
misreported as "this method does not read the key". Both paths are pinned in the testkit's own tests, one of them
through a CLI-shaped wrapper.

**D-2 — the testkit's fake surface owns four keys.** `ModelCapabilityBindingProbeTest` tests the probe's teeth
against a fake carrying `supportsSamplingParameters`, `supportsReasoningEffort`, `thinkingDialect` and
`acceptedReasoningEfforts`, chosen for shape, and every case names its key. Nothing there enumerates
`DeclarableKeys.names()` against a fake, so a new key is not an edit site in this module; §11.3 shows it.

**D-3 — the module has a coverage floor.** §7.2's "needs no entry … which is what the other testkits already do"
misread why those have none: they have no tests. Measured locally at 192 of 219 lines (87.67%), the floor is
`aimon-llm-capability-testkit=86`, with a note that it was frozen from a laptop for the reason the playwright value
was safe — no tier but `test`. The conventions plugin's comment was updated so "testkit" no longer reads as "no
floor".

**D-4 — `@Nested`, and the report names that come with it.** The two checks are nested classes, `EveryKeyIsBound`
and `EveryValueIsForwarded`, as the task's convention and `AbstractPeerMemoryContractTest`'s precedent have it.
JUnit files an inherited nested class under its *declaring* class, so Gradle reports both surfaces' cases as
`AbstractModelCapabilityBindingContractTest$EveryValueIsForwarded` with the key as the case name — once under
`:aimon-cli:test`, once under `:aimon-spring-boot-starter:test`. §3.5's "a failure still names its module" holds
through the task and through the message, which names the surface class, its operator key path and the forwarding
method. §7.3's predicted test names do not hold in that form.

**D-5 — every pair is checked the same way.** Synthesised or overridden, a pair must be exactly two values, neither
`null`, both of the setter's type, distinct, accepted by the declaration, and told apart by `equals`. An override
returning an empty list is a failure, not a skip.

**D-6 — `SurfaceWriter` reads generic types.** Element types come from `getGenericParameterTypes()`, so an
`EnumSet<E>` written into a `Set<String>` takes the enum→name row, a `Collection<E>` target is reachable, and a
container the converted value is not an instance of fails loudly.

**D-7 — the subclass javadoc no longer argues for a pair.** Each says what #69 and #82 were on that surface and what
its seam is, and points at the base class for the one-contract argument.

**D-8 — no CLAUDE.md entry, for the precedent's reason.** §3.4 said that list "carries published modules";
`aimon-cli` is on it and is not published. The conclusion stands on the precedent instead: the two unpublished
testkits are not listed.

**D-9 — `names()` refuses an empty discovery itself.** Its javadoc records that JUnit 5.12's
`allowZeroInvocations = false` default is what kept #69's `isNotEmpty()` alive under `@MethodSource`, and the method
also throws, so the protection does not rest on an annotation attribute. §2.2 had discovery moving "unchanged in
behaviour"; this is the one strengthening.

**D-10 — a `List<E>` setter is probed with lists.** §2.2's table gave `EnumSet`s for `Set`, `List` and
`Collection` alike, and an `EnumSet` cannot be passed to a `List` parameter. No such key exists today.

**D-11 — the translated message names the call, not an expression.** §2.5's sample ends with "Add
`.thinkingDialect(capabilities.getThinkingDialect())`". The contract cannot know each surface's read expression — a
getter on a parameter on the CLI, a field in the starter — so it says "Add a `.thinkingDialect(...)` call to it that
reads this property."

**D-12 — bookkeeping §10 did not list.** `CONTRIBUTING.md` and its Korean twin list the unpublished testkits in
their module tree, so both gained a line. `PublishedModuleApiScopeTest`'s javadoc counted "the three testkits" and
`settings.gradle.kts` called the memory testkit published "unlike the two testkits above"; both were reworded. And
§10's "no `CHANGELOG.md` entry" did not survive reading the changelog: its `[Unreleased]` #69 section already
describes the two guards as asserting a property name, so one bullet now says what they assert.

**D-13 — the header.** Described in the note at the top of this section.

**D-14 — §2.7's coverage table is half wrong.** Counted while registering O-2. On the CLI, `LlmClientFactoryTest`
builds `ModelCapabilityConfig` through Java setters and never reaches Jackson; the per-key binder coverage is
`CliConfigLoaderTest`'s yaml, which does carry all eight keys. On the starter, `AimonPropertiesValidationTest` binds
five of the eight as property strings, and `supports-reasoning-effort`, `supports-tools-with-reasoning` and
`supports-reasoning-trace-round-trip` appear in no starter test in either spelling. Acceptance criterion 3 is
unaffected — nothing that existed was removed — but "covered per key" was not true on the starter, and the corrected
facts are what `L-14` carries.

**D-15 — the shared half is larger than §3.3 estimated.** The testkit's main sources are 472 non-blank,
non-comment lines (883 physical, most of the rest javadoc), against 26 and 29 in the two subclasses. The estimate was
"roughly 180". The difference is the loud-failure paths, the generic-aware conversions and pair validation — all of
which would otherwise exist twice — so §3.3's arithmetic gets stronger, not weaker.

### 11.3 Acceptance criterion 1, as run

The experiment of §7.3, run on 2026-09-10 without putting anything on this branch.

- **Where.** The uncommitted work was snapshotted into a commit object through a temporary index file
  (`GIT_INDEX_FILE`, `read-tree HEAD`, `add -A`, `write-tree`, `commit-tree`), so no ref, the real index and the
  shared stash were never touched, and that object was checked out as a detached worktree in a scratch directory.
  Every run below happened there.
- **The red commit.** A ninth `Boolean` key, added the way §7.3 lists it: on `ModelCapabilityDeclaration` a field,
  an `Optional` getter, a builder setter, its place in `declaresAnything()`, its name in the refusal message, and
  `equals` / `hashCode` / `toString`; `hasSize(8)` → `hasSize(9)`; a field and a getter/setter pair on
  `ModelCapabilityConfig` and on `AimonProperties.ModelCapabilityProperties`; and **nothing** in `declarationOf` or
  `toDeclaration()`. It was run through `./gradlew format` before committing, so formatting could not be the red.
- **Run 1 — `./gradlew checkAll --continue`: BUILD FAILED in 4m 42s.** Three tasks failed. Two were the guard:
  `:aimon-cli:test` (374 tests, 1 failed) and `:aimon-spring-boot-starter:test` (263 tests, 1 failed), each on
  `every declarable key's value reaches the declaration (#82) > <the new key>`, while
  `every declarable key has a property on this surface (#69)` passed for it on both, as it should. The third,
  `:aimon-core:checkstyleMain`, was the experiment's own fault — the added refusal-message line was 124 characters —
  and not the guard's.
- **Run 2 — the line rewrapped, the scratch commit amended: BUILD FAILED in 31s** (240 actionable tasks: 4 executed,
  236 up-to-date against run 1's identical inputs and the fix step's passing checkstyle). The failed tasks were
  exactly `:aimon-cli:test` and `:aimon-spring-boot-starter:test`, one case each. `aimon-core` with `hasSize(9)`, the
  testkit's own 43 tests and every other module were green — D-2 doing its job: the testkit's fake needed no edit.
- **What each red said.** On the CLI, through `ConfigurationException`'s cause chain (D-1): "`<key>` was written on
  at.aimon.cli.config.ModelCapabilityConfig (an operator writes it as `llm.modelCapabilities.<model>.<key>`) and
  LlmClientFactory.declarationOf produced a declaration that states nothing at all — so that method does not read
  this key. Add a `.<key>(...)` call to it that reads this property." The starter's, from the bare
  `IllegalArgumentException`, is the same sentence naming `AimonProperties$ModelCapabilityProperties`,
  `aimon.llm.model-capabilities.<model>.<kebab-key>` and `AimonProperties.ModelCapabilityProperties.toDeclaration`.
  Gradle's console line for each begins with the subclass's `@DisplayName`; the XML report files the case under the
  base class's nested class (D-4). Each surface reports one failing case rather than one per value: the case stops at
  the first value that fails, which §7.1's "two values of one key are one behaviour" implies and §7.3's "for both
  probe values" did not.
- **Control — the two forwarding calls added as a second scratch commit: BUILD SUCCESSFUL in 51s** (11 executed,
  229 up-to-date). Both surfaces' contract reports showed 9 cases per check, 0 failures, the new key among them.
- **Revert.** The scratch worktree was removed with `git worktree remove --force` and pruned. Afterwards the worktree
  list and the stash list were identical to before, the branch still pointed at `6c4d2e2`, `git status` listed the
  same files as before the experiment, no ref contained any of the scratch commits, and a search of this tree finds
  the new key's name nowhere. The unreferenced commit objects are left to garbage collection.

### 11.4 Open questions — where each one went

- **O-1 → backlog `L-13`.** Its consequence is outside this guard: a ninth key forgotten in `resolve(Builder)` leaves
  both surface guards green. Registering it confirmed production reach — `capabilities()` is read at
  `InMemoryModelCapabilityRegistry.java:477`, which both surfaces reach through `withDefaultsExtendedBy` — and found a
  partial tripwire this document did not mention: `hasSize(8)` puts a person in front of
  `ModelCapabilityDeclarationTest`, though not in front of the missing line.
- **O-2 → backlog `L-14`**, carrying D-14's corrected facts rather than §2.7's.
- **O-3 stays here.** A future cross-key `build()` rule turns the contract red, and after D-1 that red is legible by
  construction: the rule's own message arrives as the cause of an error that says it is not a dropped forwarding.
- **O-4 stays here, narrower.** A surface *setter* that refuses a synthetic value is now reported as exactly that,
  before any forwarding runs, and a forwarding that throws anything but the empty refusal is not called a dropped
  forwarding. What remains is a surface that validates inside its forwarding; none does.
- **O-5 stays here.** Implemented as designed, with the reason in the CLI subclass's javadoc; D-4 adds that the
  subclass name does not show in reports either way.
- **O-6 stays here**, unchanged and inherited.

The last four stay because none of them has a consequence outside this guard, which is the test the register applies
to what it takes in — its header for the #46 design admits only the open questions "이 국면 밖으로 결과가 나가는".
The two that pass it are in
[`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md), and each entry
there points back here.

### 11.5 Later departures

*Appended 2026-09-11 for #99. §4.1 is left as approved; this records where it stopped matching the tree.*

- **§4.1's build script names a catalog alias that no longer exists.** It declares
  `api(platform(libs.spring.boot.dependencies))`. #91 (`764f371`, merged as PR #95) replaced that line in this module,
  and in `aimon-filesystem-testkit` and `aimon-session-testkit`, with `api(platform(libs.junit.bom))`, and removed the
  `spring-boot-dependencies` entry from `gradle/libs.versions.toml`. Declared `api`, Spring Boot's platform reached
  every consumer's test classpath and raised versions the consumer ships. On the two consumers this document added it
  had moved eleven: nine on `aimon-cli` (§4.2), and Caffeine and `error_prone_annotations` on
  `aimon-spring-boot-starter` (§4.3). The reason is written once, next to `junit` in the catalog;
  `modules/aimon-llm-capability-testkit/build.gradle.kts` is the current script. What the platform's removal left
  behind on those two consumers, and the decision on each, is
  [`../testing/test-classpath-shipped-versions.md`](../testing/test-classpath-shipped-versions.md).
