plugins {
  id("aimon.java-conventions")
  id("aimon.publishable")
}

dependencies {
  implementation(project(":aimon-core"))

  // GraalJS (JDK-17 line): Polyglot embedding API (graal-sdk) + JS language (brings truffle-api).
  // Kept at implementation scope so org.graalvm types never leak transitively to consumers.
  implementation(libs.graaljs.polyglot)
  implementation(libs.graaljs.js)

  implementation(libs.slf4j.api)
  implementation(libs.jackson.databind)

  // Fence org.graalvm.* imports to this module.
  testImplementation(libs.archunit.junit5)
}
