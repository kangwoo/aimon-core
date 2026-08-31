plugins {
    id("aimon.java-conventions")
    id("aimon.publishable")
}

dependencies {
    implementation(project(":aimon-core"))

    // AWS SDK
    implementation(libs.aws.s3)

    testImplementation(libs.testcontainers.localstack)
}
