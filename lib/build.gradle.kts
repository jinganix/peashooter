import utils.Vers.versionAssertj
import utils.Vers.versionAwaitility
import utils.Vers.versionCaffeine
import utils.Vers.versionJupiter
import utils.Vers.versionNetty
import utils.Vers.versionRedisson
import utils.Vers.versionSlf4j
import utils.Vers.versionTestContainers
import utils.signAndPublish

plugins {
  id("java.library")
}

dependencies {
  implementation("com.github.ben-manes.caffeine:caffeine:$versionCaffeine")
  implementation("io.netty:netty-resolver-dns-native-macos:${versionNetty}:osx-aarch_64")
  implementation("org.slf4j:slf4j-api:${versionSlf4j}")
  testImplementation("org.assertj:assertj-core:${versionAssertj}")
  testImplementation("org.awaitility:awaitility:${versionAwaitility}")
  testImplementation("org.junit.jupiter:junit-jupiter-api:${versionJupiter}")
  testImplementation("org.junit.jupiter:junit-jupiter-params:${versionJupiter}")
  testImplementation("org.redisson:redisson:$versionRedisson")
  testImplementation("org.testcontainers:testcontainers:$versionTestContainers")
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
  projectDir.resolve("src/test/java/io/github/jinganix/peashooter/executor/ExecutorForTests.java"),
  mapOf("java19" to JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_24))
)
tasks.compileTestJava { dependsOn("generateSource") }
