import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.hegel)
    testImplementation(libs.assertj)
    testImplementation(libs.gson)
    testImplementation(libs.protobuf.java.util)
    testImplementation(libs.envoy.api)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 22 // Hegel calls its native engine through the FFM API
    options.encoding = "utf-8"
    options.compilerArgs.addAll(listOf("-Xlint", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED") // Hegel
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.test {
    filter.excludeTestsMatching("*.DifferentialFuzzTest")
}

// Runs generated vectors through every port's replay mode, comparing what they send:
//   ../conformance/gradlew fuzz -Pports=java=/path/to/replay,go=/path/to/replay [-PfuzzCases=1000]
tasks.register<Test>("fuzz") {
    description = "Runs generated vectors through the replay modes -Pports names, and compares what they send."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*.DifferentialFuzzTest")
    outputs.upToDateWhen { false } // the ports aren't inputs Gradle knows of
    val ports = providers.gradleProperty("ports")
    val cases = providers.gradleProperty("fuzzCases").orElse("1000")
    val out = layout.buildDirectory.dir("fuzz")
    doFirst {
        systemProperty("fuzz.ports", ports.orNull ?: throw GradleException("Name the ports: -Pports=java=/path/to/replay,go=/path/to/replay"))
        systemProperty("fuzz.cases", cases.get())
        systemProperty("fuzz.out", out.get().asFile.absolutePath)
    }
}
