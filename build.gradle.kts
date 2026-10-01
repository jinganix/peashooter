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
  afterEvaluate {
    if (tasks.findByName("publishToMavenLocal") != null) {
      rootProject.tasks.named("publishToMavenLocal") {
        dependsOn(tasks.named("publishToMavenLocal"))
      }
    }
    if (tasks.findByName("publish") != null) {
      rootProject.tasks.named("publish") {
        dependsOn(tasks.named("publish"))
      }
    }
  }
}

val jacocoProjects =
  listOf(
    ":lib",
  )

tasks.register("jacocoReport") {
  group = "verification"
  description = "Generate JaCoCo XML reports for all Java subprojects with unit tests"
  dependsOn(jacocoProjects.map { project(it).tasks.named("jacocoTestReport") })
}

tasks.register("coverageReport") {
  group = "verification"
  description = "Generate coverage reports for all subprojects with tests"
  dependsOn(tasks.named("jacocoReport"))
}
