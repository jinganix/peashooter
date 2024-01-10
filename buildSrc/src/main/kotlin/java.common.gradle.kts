import com.diffplug.gradle.spotless.SpotlessExtension
import utils.Props
import utils.Vers
import utils.Vers.versionAssertj
import utils.Vers.versionJacocoAgent
import utils.Vers.versionMockitoCore
import utils.Vers.versionMockitoInline
import utils.createConfiguration
import java.io.FileInputStream
import java.util.*

plugins {
  id("conventions.versioning")
  if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) id("com.diffplug.spotless")
  idea
  jacoco
  java
}

val javaVersion = if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) JavaVersion.VERSION_17 else JavaVersion.VERSION_1_8

val properties = Properties()
if (javaVersion == JavaVersion.VERSION_1_8) {
  FileInputStream(file("../gradle.java8.properties")).use(properties::load)
}
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
}

if (javaVersion == JavaVersion.VERSION_17) {
  extensions.findByType<SpotlessExtension>()?.java {
    targetExclude("build/**/*")
    googleJavaFormat()
  }

  tasks.check {
    dependsOn(tasks.findByName("spotlessCheck"))
  }
}

jacoco {
  toolVersion = versionJacocoAgent
}

tasks.jacocoTestReport {
  enabled = false
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
