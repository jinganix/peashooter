import utils.Props.jacocoMinCoverage
import utils.createConfiguration
import utils.extractDependencies

plugins {
  id("java")
  jacoco
}

repositories {
  mavenLocal()
  mavenCentral()
  gradlePluginPortal()
}

val buildSrcDependencies = extractDependencies(file("${rootDir}/buildSrc/build.gradle.kts"))

dependencies {
  implementation(project(":lib"))

  buildSrcDependencies.forEach {
    testCompileOnly(it)
  }
}

configurations.implementation.get().dependencies.forEach {
  if (it is ModuleDependency) {
    it.isTransitive = false
  }
}

val incomingClassDirs = createConfiguration("incomingClassDirs", "classDirs") {
  extendsFrom(configurations.implementation.get())
  isCanBeResolved = true
  isCanBeConsumed = false
}

val incomingSourceDirs = createConfiguration("incomingSourceDirs", "sourceDirs") {
  extendsFrom(configurations.implementation.get())
  isCanBeResolved = true
  isCanBeConsumed = false
}

val incomingCoverageData = createConfiguration("incomingCoverageData", "coverageData") {
  extendsFrom(configurations.implementation.get())
  isCanBeResolved = true
  isCanBeConsumed = false
}

fun generateJacocoReport(base: JacocoReportBase) {
  base.additionalClassDirs(incomingClassDirs.incoming.artifactView {
    lenient(true)
  }.files.asFileTree.matching {
  })
  base.additionalSourceDirs(incomingSourceDirs.incoming.artifactView { lenient(true) }.files)
  base.executionData(incomingCoverageData.incoming.artifactView { lenient(true) }.files.filter { it.exists() })
}

val coverageVerification by tasks.registering(JacocoCoverageVerification::class) {
  group = "verification"
  generateJacocoReport(this)

  violationRules {
    rule { limit { minimum = BigDecimal.valueOf(jacocoMinCoverage) } }
  }
}

val coverage by tasks.registering(JacocoReport::class) {
  group = "verification"
  generateJacocoReport(this)

  reports {
    html.required.set(true)
    xml.required.set(true)
  }
}

tasks.check {
  dependsOn(coverageVerification)
}
