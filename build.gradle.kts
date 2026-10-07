plugins {
  base
}

tasks.register("publishToMavenLocal") {
  group = "publishing"
  description = "Publishes all Maven artifacts to the local Maven repository"
}

tasks.register("publish") {
  group = "publishing"
  description = "Publishes all Maven artifacts"
}

subprojects {
  plugins.withId("maven-publish") {
    val subPublish = tasks.named("publish")
    rootProject.tasks.named("publish") {
      dependsOn(subPublish)
    }
    val subPublishToMavenLocal = tasks.named("publishToMavenLocal")
    rootProject.tasks.named("publishToMavenLocal") {
      dependsOn(subPublishToMavenLocal)
    }
  }
}

tasks.register("coverageReport") {
  group = "verification"
  description = "Generate aggregated JaCoCo coverage report (delegates to :aggregation:coverage)"
  dependsOn(project(":aggregation").tasks.named("coverage"))
}
