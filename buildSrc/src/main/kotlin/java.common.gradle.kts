import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import utils.Props
import utils.Vers
import utils.Vers.versionAssertj
import utils.Vers.versionGoogleJavaFormat
import utils.Vers.versionJacoco
import utils.Vers.versionMockitoCore
import utils.Vers.versionMockitoInline
import utils.createConfiguration
import java.io.FileInputStream
import java.math.BigDecimal
import java.util.*

plugins {
  id("conventions.versioning")
  id("com.diffplug.spotless")
  idea
  jacoco
  java
}

val javaVersion = JavaVersion.VERSION_21

val properties = Properties()
Props.initialize(project)
Vers.initialize(project, properties)

java {
  sourceCompatibility = javaVersion
  targetCompatibility = javaVersion
}

repositories {
  mavenLocal()
  mavenCentral()
  maven { url = uri(Props.snapshotRepo) }
}

dependencies {
  testImplementation("org.assertj:assertj-core:${versionAssertj}")
  testImplementation("org.mockito:mockito-core:${versionMockitoCore}")
  testImplementation("org.mockito:mockito-inline:${versionMockitoInline}")
}

tasks.test {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<Javadoc>().configureEach {
  setSource(
    source.files.filter { file ->
      val path = file.invariantSeparatorsPath
      !path.contains("/build/generated/")
    },
  )
  (options as StandardJavadocDocletOptions).apply {
    addBooleanOption("Xdoclint:all", true)
    addBooleanOption("Werror", true)
  }
}

extensions.findByType<SpotlessExtension>()?.java {
  targetExclude("build/**/*")
  googleJavaFormat(versionGoogleJavaFormat)
}

tasks.named<Task>("check") {
  dependsOn(tasks.named("spotlessCheck"))
  dependsOn(tasks.named("javadoc"))
  dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

jacoco {
  toolVersion = versionJacoco
}

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
}

val hasUnitTests =
  sourceSets.test.get().allJava.files.any { file ->
    file.name.endsWith("Test.java") || file.name.endsWith("Tests.java")
  }

tasks.jacocoTestCoverageVerification {
  enabled = hasUnitTests
  dependsOn(tasks.jacocoTestReport)
  violationRules {
    rule {
      limit {
        minimum = BigDecimal.valueOf(Props.jacocoMinCoverage)
      }
    }
    rule {
      element = "CLASS"
      limit {
        counter = "LINE"
        value = "COVEREDRATIO"
        minimum = BigDecimal.valueOf(Props.jacocoMinCoverage)
      }
    }
  }
}

createConfiguration("outgoingClassDirs", "classDirs") {
  extendsFrom(configurations.implementation.get())
  isCanBeResolved = false
  isCanBeConsumed = true
  sourceSets.main.get().output.forEach {
    outgoing.artifact(it)
  }
}

createConfiguration("outgoingSourceDirs", "sourceDirs") {
  extendsFrom(configurations.implementation.get())
  isCanBeResolved = false
  isCanBeConsumed = true
  sourceSets.main.get().java.srcDirs.forEach {
    outgoing.artifact(it)
  }
}

createConfiguration("outgoingCoverageData", "coverageData") {
  extendsFrom(configurations.implementation.get())
  isCanBeResolved = false
  isCanBeConsumed = true
  outgoing.artifact(tasks.test.map {
    it.extensions.getByType<JacocoTaskExtension>().destinationFile!!
  })
}
