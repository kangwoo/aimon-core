// The model-capability binding contract, in a module of its own so both configuration surfaces answer to one
// description of it.
//
// The same shape as `aimon-filesystem-testkit` — see that build script for why `java-test-fixtures` on aimon-core is
// not an option. What differs is the subject. The other testkits describe a contract a *backend* satisfies; this one
// describes the step between a configuration surface and `ModelCapabilityDeclaration` that is written by hand, one
// line per key, on each surface — the step #82 found that neither of #69's two guards could see. The CLI's
// `ModelCapabilityConfig` and the starter's `AimonProperties.ModelCapabilityProperties` each subclass it.
//
// #69 kept those guards as two copies because the check cannot live in aimon-core, which sees neither surface. That is
// still true; it does not follow that the check needs two copies, since both surface modules can see a third. And two
// copies of a guard drift quietly: a weakened copy still reports success, which is the failure it exists to catch.
// The full argument is on `AbstractModelCapabilityBindingContractTest`.
//
// Deliberately NOT published: `aimon.publishable` is absent, so `aimon-bom` leaves it out on its own. Unlike the other
// three testkits it has tests of its own — its whole value is in whether it fails, and once both real surfaces forward
// every key, nothing else in the tree exercises the failing path.
plugins {
    id("aimon.java-conventions")
}

dependencies {
    // `api`, not `implementation`: the subclasses in aimon-cli and aimon-spring-boot-starter implement hooks returning
    // `ModelCapabilityDeclaration` and inherit JUnit-annotated nested classes, so core and the JUnit/AssertJ annotations
    // belong on their compile classpath. The "don't leak core through implementation modules" rule guards a published
    // POM's honesty; nothing here is published.
    api(project(":aimon-core"))

    // JUnit and AssertJ are compiled against by this module's *main* source set, so — as in the filesystem testkit —
    // the platform comes in here rather than relying on the one the conventions plugin puts on test configurations.
    api(platform(libs.spring.boot.dependencies))
    api(libs.bundles.testing)
}
