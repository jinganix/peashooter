import org.gradle.kotlin.dsl.the
import utils.VersExtension

plugins {
  id("java.library")
}

tasks.jar {
  manifest {
    // Matches the JPMS module name in src/main/java/module-info.java; ignored once modular,
    // kept so the jar still carries a stable name for classpath consumers.
    attributes["Automatic-Module-Name"] = "io.github.jinganix.peashooter"
  }
}

// module-info.java puts javadoc in module mode: resolve required modules from the module path.
tasks.withType<Javadoc>().configureEach {
  options.modulePath = sourceSets.main.get().compileClasspath.files.toList()
}

tasks.test {
  maxHeapSize = "2g"
  testLogging {
    events("FAILED")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

// Convention lint: source/structure checks that must not occupy unit tests or JaCoCo.
// Lives in src/conventionTest so `test` stays behavioral; wired into `check` as CI lint.
sourceSets {
  create("conventionTest") {
    java.srcDir("src/conventionTest/java")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
  }
}

configurations {
  named("conventionTestImplementation") {
    extendsFrom(configurations["testImplementation"])
  }
  named("conventionTestRuntimeOnly") {
    extendsFrom(configurations["testRuntimeOnly"])
  }
}

val conventionTest =
  tasks.register<Test>("conventionTest") {
    description = "Runs convention/structure lint (DisplayName, headers, encapsulation)"
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["conventionTest"].output.classesDirs
    classpath = sourceSets["conventionTest"].runtimeClasspath
    maxHeapSize = "1g"
    testLogging {
      events("FAILED")
      exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
  }

tasks.named("check") {
  dependsOn(conventionTest)
}

// java.library -> java.common registers this project's VersExtension.
val vers = the<VersExtension>()

dependencies {
  implementation("com.github.ben-manes.caffeine:caffeine:${vers.versionCaffeine}")
  testRuntimeOnly("io.netty:netty-resolver-dns:${vers.versionNetty}")
  implementation("org.slf4j:slf4j-api:${vers.versionSlf4j}")
  // JUnit and AssertJ come from the java.library/java.common conventions; only project-specific
  // test libraries are declared here.
  testImplementation("org.awaitility:awaitility:${vers.versionAwaitility}")
  testImplementation("ch.qos.logback:logback-classic:${vers.versionLogback}")
  testImplementation("org.junit.jupiter:junit-jupiter-params:${vers.versionJupiter}")
  testImplementation("org.redisson:redisson:${vers.versionRedisson}")
  testImplementation("org.testcontainers:testcontainers:${vers.versionTestContainers}")
}
