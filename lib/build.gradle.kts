import utils.Vers.versionAssertj
import utils.Vers.versionAwaitility
import utils.Vers.versionJupiter
import utils.Vers.versionSlf4j
import utils.signAndPublish

plugins {
  id("java.library")
}

dependencies {
  implementation("org.slf4j:slf4j-api:${versionSlf4j}")
  testImplementation("org.assertj:assertj-core:${versionAssertj}")
  testImplementation("org.awaitility:awaitility:${versionAwaitility}")
  testImplementation("org.junit.jupiter:junit-jupiter-api:${versionJupiter}")
  testImplementation("org.junit.jupiter:junit-jupiter-params:${versionJupiter}")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${versionJupiter}")
}

signAndPublish("peashooter") {
  from(components["java"])
  pom {
    description.set("Call tasks sequentially and prevent deadlocks")
  }
}

generateSourceTask(
  projectDir.resolve("src/templates/ExecutorForTests.java.ftl"),
  projectDir.resolve("src/test/java/io/github/jinganix/peashooter/ExecutorForTests.java"),
  mapOf("java19" to JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_19))
)
tasks.compileTestJava { dependsOn("generateSource") }
