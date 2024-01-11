import utils.Vers.versionAssertj
import utils.Vers.versionAwaitility
import utils.Vers.versionSlf4j

plugins {
  id("java.library")
}

dependencies {
  implementation("org.slf4j:slf4j-api:${versionSlf4j}")
  testImplementation("org.assertj:assertj-core:${versionAssertj}")
  testImplementation("org.awaitility:awaitility:${versionAwaitility}")
  testImplementation(libs.junit.jupiter)
}
