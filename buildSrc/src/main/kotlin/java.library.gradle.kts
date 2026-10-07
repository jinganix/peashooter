import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.kotlin.dsl.the
import org.gradle.plugins.signing.Sign
import utils.VersExtension
import utils.signAndPublish

plugins {
  `java-library`
  `maven-publish`
  id("java.common")
  id("com.vanniktech.maven.publish")
  signing
}

// java.common registers this project's VersExtension; the<> fails fast if it is absent.
val vers = the<VersExtension>()

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter-api:${vers.versionJupiter}")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${vers.versionJupiter}")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:${vers.versionJupiter}")
}

signAndPublish("peashooter", "Call tasks sequentially and prevent deadlocks.")

// A published library must resolve for Maven consumers too. Gradle module metadata can look
// perfect while the POM is empty: `pom` packaging or a missing dependency makes the published
// coordinate resolve to nothing. Two publications with the same coordinates overwrite each
// other's POM, so this guard reads the generated POM artifacts themselves.
val verifyPublishedPom =
  tasks.register("verifyPublishedPom") {
    group = "verification"
    description = "Fails when a published POM is not a single jar-packaged module with its runtime deps"
    val publications = layout.buildDirectory.dir("publications")
    // The directory also holds the Gradle module metadata and (when signing is enabled on CI)
    // the .asc signatures; all producers are declared so the input carries no implicit task
    // dependency.
    dependsOn(tasks.withType<GenerateMavenPom>())
    dependsOn(tasks.withType<GenerateModuleMetadata>())
    dependsOn(tasks.withType<Sign>())
    inputs.dir(publications).withPropertyName("publications")
    doLast {
      val poms =
        (publications.get().asFile.listFiles() ?: emptyArray())
          .map { it.resolve("pom-default.xml") }
          .filter { it.isFile }
          .sortedBy { it.parentFile.name }
      check(poms.size == 1) {
        "Expected exactly one generated POM, found ${poms.map { it.parentFile.name }}: " +
          "publications with equal coordinates overwrite each other's POM"
      }
      val pom = poms.single()
      val text = pom.readText()
      // Gradle omits the Maven default (`jar`) packaging; an explicit non-jar packaging would
      // make Maven resolve no artifact at all.
      val packaging = Regex("<packaging>([^<]*)</packaging>").find(text)?.groupValues?.get(1)
      check(packaging == null || packaging == "jar") {
        "Published POM must be jar-packaged, but declared '$packaging': $pom"
      }
      for (dependency in listOf("caffeine", "slf4j-api")) {
        check(text.contains("<artifactId>$dependency</artifactId>")) {
          "Published POM is missing runtime dependency '$dependency': $pom"
        }
      }
    }
  }

tasks.named("check") { dependsOn(verifyPublishedPom) }
tasks.matching { it.name.startsWith("publish") }.configureEach { dependsOn(verifyPublishedPom) }
