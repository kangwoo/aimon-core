// A resources-only module: it carries skills for the `sample` agent bundle and no code at all.
//
// That is the point. SBS-10 has to prove that a skill reaches the agent because it was *on the class path*,
// not because some class in the same artifact pulled it in. A module with a `src/main/java` would leave that
// ambiguous; this one cannot.
//
// It applies the shared conventions plugin even though it compiles nothing, because the root aggregators
// (`format`, `checkFormat`, `checkStyle`, `checkAll`) iterate `subprojects` and call those task names on every
// one of them — a subproject without the plugin breaks the root build. It is deliberately **not**
// `aimon.publishable`: samples are proof, not product.
plugins {
    id("aimon.java-conventions")
}
