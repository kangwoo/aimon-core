rootProject.name = "aimon-core"

include(
    // The bill of materials. A `java-platform`, so it is the one subproject that cannot apply
    // `aimon.java-conventions` (`java-platform` and `java-library` are mutually exclusive) — the root
    // aggregators skip platforms for that reason.
    "aimon-bom",
    "aimon-core",
    "aimon-bootstrap",
    "aimon-cli",
    "aimon-spring-boot-starter",
    "aimon-browser-playwright",
    "aimon-filesystem-gridfs",
    "aimon-filesystem-s3",
    // The shared VirtualFileSystem contract test. Not published — it exists so each backend is checked against
    // one description of the contract instead of its own.
    "aimon-filesystem-testkit",
    "aimon-knowledge-opensearch",
    "aimon-llm-anthropic",
    "aimon-llm-openai",
    "aimon-sandbox",
    "aimon-sandbox-docker",
    "aimon-sandbox-kubernetes",
    "aimon-rewake-webhook",
    "aimon-scheduling-quartz",
    "aimon-session-routing",
    // The shared multi-node contract suite for the session backends, the same idea as
    // `aimon-filesystem-testkit` above and not published for the same reasons.
    "aimon-session-testkit",
    "aimon-session-redis",
    "aimon-session-postgres",
    "aimon-session-mongodb",
    // The shared five-tier PeerMemory contract suite, the same idea as `aimon-filesystem-testkit` above and not
    // published for the same reasons. Its subjects are backends, not stores — the three modules below implement
    // stores and do not take part.
    "aimon-memory-testkit",
    "aimon-memory-postgres",
    "aimon-memory-file",
    "aimon-memory-mongodb",
    "aimon-workflow-graaljs",
    // Samples. Not published and not part of the framework — they are the only place in this build where an
    // *application* exists, and therefore the only place a claim about fat-jar packaging can be tested. They are
    // included flat, like every other project here, rather than nested under a `samples` container: a container
    // project would itself be a subproject without `aimon.java-conventions`, and the root aggregators call
    // `spotlessApply` / `checkstyleMain` / `test` on every subproject by name.
    "aimon-sample-app",
    "aimon-sample-skills-alpha",
    "aimon-sample-skills-beta",
)

project(":aimon-bom").projectDir = file("modules/aimon-bom")
project(":aimon-core").projectDir = file("modules/aimon-core")
project(":aimon-bootstrap").projectDir = file("modules/aimon-bootstrap")
project(":aimon-cli").projectDir = file("modules/aimon-cli")
project(":aimon-spring-boot-starter").projectDir = file("modules/aimon-spring-boot-starter")
project(":aimon-browser-playwright").projectDir = file("modules/aimon-browser-playwright")
project(":aimon-filesystem-gridfs").projectDir = file("modules/aimon-filesystem-gridfs")
project(":aimon-filesystem-s3").projectDir = file("modules/aimon-filesystem-s3")
project(":aimon-filesystem-testkit").projectDir = file("modules/aimon-filesystem-testkit")
project(":aimon-knowledge-opensearch").projectDir = file("modules/aimon-knowledge-opensearch")
project(":aimon-llm-anthropic").projectDir = file("modules/aimon-llm-anthropic")
project(":aimon-llm-openai").projectDir = file("modules/aimon-llm-openai")
project(":aimon-sandbox").projectDir = file("modules/aimon-sandbox")
project(":aimon-sandbox-docker").projectDir = file("modules/aimon-sandbox-docker")
project(":aimon-sandbox-kubernetes").projectDir = file("modules/aimon-sandbox-kubernetes")
project(":aimon-rewake-webhook").projectDir = file("modules/aimon-rewake-webhook")
project(":aimon-scheduling-quartz").projectDir = file("modules/aimon-scheduling-quartz")
project(":aimon-session-routing").projectDir = file("modules/aimon-session-routing")
project(":aimon-session-testkit").projectDir = file("modules/aimon-session-testkit")
project(":aimon-session-redis").projectDir = file("modules/aimon-session-redis")
project(":aimon-session-postgres").projectDir = file("modules/aimon-session-postgres")
project(":aimon-session-mongodb").projectDir = file("modules/aimon-session-mongodb")
project(":aimon-memory-testkit").projectDir = file("modules/aimon-memory-testkit")
project(":aimon-memory-postgres").projectDir = file("modules/aimon-memory-postgres")
project(":aimon-memory-file").projectDir = file("modules/aimon-memory-file")
project(":aimon-memory-mongodb").projectDir = file("modules/aimon-memory-mongodb")
project(":aimon-workflow-graaljs").projectDir = file("modules/aimon-workflow-graaljs")

project(":aimon-sample-app").projectDir = file("samples/aimon-sample-app")
project(":aimon-sample-skills-alpha").projectDir = file("samples/aimon-sample-skills-alpha")
project(":aimon-sample-skills-beta").projectDir = file("samples/aimon-sample-skills-beta")
