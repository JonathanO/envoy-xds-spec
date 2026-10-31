// The differential fuzzer: see docs/FUZZING.md. It shares conformance/'s Gradle wrapper and version catalog:
//   cd fuzz && ../conformance/gradlew fuzz -Pports=java=/path/to/replay,go=/path/to/replay
rootProject.name = "envoy-xds-fuzz"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../conformance/gradle/libs.versions.toml"))
        }
    }
}
