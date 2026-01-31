import utils.Vers.versionJupiter
import utils.signAndPublish

plugins {
  `java-library`
  id("java.common")
  id("com.vanniktech.maven.publish")
}

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter-api:${versionJupiter}")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${versionJupiter}")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

signAndPublish("peashooter", "Call tasks sequentially and prevent deadlocks.")
