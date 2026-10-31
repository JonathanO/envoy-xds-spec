dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// The harness's coordinates, net.woaf.jono.envoy:envoy-xds-testing: a build that includes this one depends on those.
rootProject.name = "envoy-xds-testing"
