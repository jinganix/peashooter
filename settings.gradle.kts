rootProject.name = "peashooter"
include(":lib")
include(":aggregation")

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral {
      mavenContent { releasesOnly() }
    }
    maven {
      url =
        uri(
          providers.gradleProperty("snapshotRepo").orNull
            ?: throw GradleException("Missing required gradle property: snapshotRepo")
        )
      mavenContent { snapshotsOnly() }
    }
  }
}
