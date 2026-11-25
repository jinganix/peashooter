rootProject.name = "peashooter"
include(":lib")
include(":aggregation")

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral {
      mavenContent { releasesOnly() }
    }
    // Opt-in: only added when the property exists, so clean checkouts and offline
    // builds work without it. Add -PsnapshotRepo=<url> when a -SNAPSHOT dep is needed.
    providers.gradleProperty("snapshotRepo").orNull?.let { repoUrl ->
      maven {
        url = uri(repoUrl)
        mavenContent { snapshotsOnly() }
      }
    }
  }
}
