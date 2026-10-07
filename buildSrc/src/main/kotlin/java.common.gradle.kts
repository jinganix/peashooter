import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import utils.createConfiguration
import utils.props
import utils.vers
import java.math.BigDecimal

plugins {
  id("conventions.versioning")
  id("com.diffplug.spotless")
  idea
  jacoco
  java
}

val javaVersion = JavaVersion.VERSION_21

val props = project.props()
val vers = project.vers()

java {
  sourceCompatibility = javaVersion
  targetCompatibility = javaVersion
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(21)
}

dependencies {
  testImplementation("org.assertj:assertj-core:${vers.versionAssertj}")
  testImplementation("org.mockito:mockito-core:${vers.versionMockitoCore}")
}

tasks.test {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<Javadoc>().configureEach {
  // Filtered view instead of a List<File>: keeping the FileTree preserves the source roots so
  // Gradle can fingerprint the task ("Cannot infer source root(s)" otherwise makes javadoc run
  // on every build).
  setSource(source.matching { exclude("**/build/generated/**") })
  (options as StandardJavadocDocletOptions).apply {
    addBooleanOption("Xdoclint:all", true)
    addBooleanOption("Werror", true)
  }
}

extensions.findByType<SpotlessExtension>()?.java {
  targetExclude("build/**/*")
  googleJavaFormat(vers.versionGoogleJavaFormat)
}

tasks.named<Task>("check") {
  dependsOn(tasks.named("spotlessCheck"))
  dependsOn(tasks.named("javadoc"))
  dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

jacoco {
  toolVersion = vers.versionJacoco
}

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
}

tasks.jacocoTestCoverageVerification {
  // Execution-time gate instead of a configuration-time file scan: the predicate runs when the
  // task would execute, so configuration stays file-system free and configuration-cache clean.
  // Modules without Test/Tests classes skip verification without disabling the task at config.
  onlyIf("no unit tests") {
    sourceSets.test.get().allJava.files.any { file ->
      file.name.endsWith("Test.java") || file.name.endsWith("Tests.java")
    }
  }
  dependsOn(tasks.jacocoTestReport)
  violationRules {
    // Aggregate-only threshold, matching :aggregation:coverageVerification. A per-class LINE
    // rule cannot be satisfied by thin delegating/defensive classes whose intentional cold
    // branches are not exercised, so it would make :lib:check (and `./gradlew build`) unattainable.
    rule {
      limit {
        minimum = BigDecimal.valueOf(props.jacocoMinCoverage)
      }
    }
  }
}

val classes = tasks.named("classes")

createConfiguration("outgoingClassDirs", "classDirs") {
  isCanBeResolved = false
  isCanBeConsumed = true
  sourceSets.main.get().output.forEach {
    outgoing.artifact(it) { builtBy(classes) }
  }
}

createConfiguration("outgoingSourceDirs", "sourceDirs") {
  isCanBeResolved = false
  isCanBeConsumed = true
  sourceSets.main.get().java.srcDirs.forEach {
    outgoing.artifact(it)
  }
}

createConfiguration("outgoingCoverageData", "coverageData") {
  isCanBeResolved = false
  isCanBeConsumed = true
  outgoing.artifact(tasks.test.map {
    it.extensions.getByType<JacocoTaskExtension>().destinationFile!!
  })
}
