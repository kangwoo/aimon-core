// Test support shared by the provider modules' tests — today `CountingHttpServer`, the local server the OpenAI and
// Anthropic SDK-retry tests count HTTP attempts against. Until it moved here each module carried its own copy,
// identical but for the package.
//
// A module of its own rather than `java-test-fixtures` on one provider module, for the reason in
// `aimon-filesystem-testkit`'s build script: the publishing plugin reacts to that plugin by calling a Gradle internal
// removed in Gradle 9, and both provider modules are published. What differs from the other testkits is that this
// module is not a contract suite: it describes no contract, has no tests of its own and, being plain JDK sockets,
// depends on nothing — not aimon-core, and not JUnit, so it needs none of the platform the other testkits declare.
//
// Deliberately NOT published: `aimon.publishable` is absent, so `aimon-bom` leaves it out on its own. Its consumers
// take it on `testImplementation`, so it reaches no POM.
plugins {
    id("aimon.java-conventions")
}
