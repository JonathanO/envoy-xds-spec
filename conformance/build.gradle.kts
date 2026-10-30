// The conformance harness: test a control plane against a real Envoy. Java 17 bytecode, Error Prone with NullAway,
// and Checkstyle, as in java-envoy-xds.

import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    `java-library`
    checkstyle
    alias(libs.plugins.errorprone)
}

group = "net.woaf.jono.envoy"
description = "Test a control plane against a real Envoy: record the xDS conversation and check it, Envoy's stats and its log"

// Deliberately independent of any xDS library, so any control plane can use it.
dependencies {
    api(libs.guava)
    api(libs.protobuf.java)
    api(libs.grpc.api)
    api(libs.assertj)
    api(libs.testcontainers)
    api(libs.junit.jupiter.api) // for the conformance suite
    api(libs.hamcrest) // for XdsMatchers
    api(libs.jspecify)
    implementation(libs.awaitility)
    implementation(libs.errorprone.annotations)
    implementation(libs.protobuf.java.util)
    implementation(libs.slf4j.api)
    compileOnly(libs.spotbugs.annotations) // EnvoyContainer's @SuppressFBWarnings
    compileOnly(libs.envoy.api) // Users are expected to provide a compatible envoy api

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.envoy.api)
    testImplementation(libs.grpc.stub)
    testImplementation(libs.gson)
    testRuntimeOnly(libs.grpc.netty.shaded)
    testRuntimeOnly(libs.logback.classic) // so Testcontainers' logs reach the test output
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)
}

tasks.withType<JavaCompile>().configureEach {
    // Error Prone needs JDK 21+ to run; keep the bytecode at 17.
    options.release = 17
    options.encoding = "utf-8"
    options.errorprone.disableWarningsInGeneratedCode = true
    options.compilerArgs.add("-Werror")
}

tasks.compileJava {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint", "-Xlint:-processing"))
    // Every main package is @NullMarked (see its package-info.java), and NullAway checks it strictly.
    options.errorprone {
        error("NullAway")
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:CheckOptionalEmptiness", "true")
        option("NullAway:AcknowledgeRestrictiveAnnotations", "true")
        error("NonFinalStaticField", "RedundantNullCheck", "ThrowSpecificExceptions")
    }
}

tasks.compileTestJava {
    // Tests aren't @NullMarked, and often call a method for its side effect alone.
    options.errorprone.disable("NullAway", "CheckReturnValue")
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true) // every public API documented
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.STARTED, TestLogEvent.FAILED, TestLogEvent.SKIPPED, TestLogEvent.PASSED, TestLogEvent.STANDARD_ERROR)
        showExceptions = true
        showStackTraces = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.withType<Checkstyle>().configureEach {
    config = resources.text.fromFile(file("google_checks.xml"))
    maxWarnings = 0
}

// The conformance suite against a port's conformance server: see docs/CONFORMANCE.md in the spec.
//   ./gradlew conformance -Pserver=/path/to/server [-PenvoyVersions=1.36,1.40] [-PtestParallelism=4]
tasks.register<Test>("conformance") {
    description = "Runs the conformance suite against the conformance server -Pserver names, on real Envoys."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("*.ExternalServerConformanceTest")
    outputs.upToDateWhen { false } // the server isn't an input Gradle knows of
    val server = providers.gradleProperty("server")
    val envoyVersions = providers.gradleProperty("envoyVersions")
    val parallelism = providers.gradleProperty("testParallelism").orElse("4")
    doFirst {
        val path = server.orNull ?: throw GradleException("Name the conformance server: -Pserver=/path/to/server")
        systemProperty("conformance.server", file(path).absolutePath)
        envoyVersions.orNull?.let { systemProperty("envoyVersions", it) }
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
        systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", parallelism.get())
    }
}
