import utils.createConfiguration

plugins {
  id("java")
  jacoco
}

// Match the tool version used by the modules that produced the exec data, so the report and
// verification parse it with the same JaCoCo release instead of Gradle's bundled default.
jacoco {
  toolVersion = project.property("versionJacoco") as String
}

val incomingClassDirs = createConfiguration("incomingClassDirs", "classDirs") {
  isCanBeResolved = true
  isCanBeConsumed = false
}

val incomingSourceDirs = createConfiguration("incomingSourceDirs", "sourceDirs") {
  isCanBeResolved = true
  isCanBeConsumed = false
}

val incomingCoverageData = createConfiguration("incomingCoverageData", "coverageData") {
  isCanBeResolved = true
  isCanBeConsumed = false
}

dependencies {
  incomingClassDirs(project(":lib"))
  incomingSourceDirs(project(":lib"))
  incomingCoverageData(project(":lib"))
}

fun generateJacocoReport(base: JacocoReportBase) {
  base.additionalClassDirs(incomingClassDirs.incoming.artifactView {}.files)
  base.additionalSourceDirs(incomingSourceDirs.incoming.artifactView {}.files)
  base.executionData(incomingCoverageData.incoming.artifactView {}.files.filter { it.exists() })
}

val jacocoMinCoverage = BigDecimal(project.property("jacocoMinCoverage").toString())

val coverageVerification = tasks.register<JacocoCoverageVerification>("coverageVerification") {
  group = "verification"
  generateJacocoReport(this)

  violationRules {
    rule { limit { minimum = jacocoMinCoverage } }
  }
}

val coverage = tasks.register<JacocoReport>("coverage") {
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
