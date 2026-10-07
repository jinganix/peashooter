import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
  `kotlin-dsl`
}

val javaVersion = JavaVersion.VERSION_21

java {
  sourceCompatibility = javaVersion
  targetCompatibility = javaVersion
}

tasks.compileKotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
  }
}

repositories {
  gradlePluginPortal()
  mavenCentral()
}

val properties = Properties()
FileInputStream(file("../gradle.properties")).use(properties::load)

for (key in properties.stringPropertyNames()) {
  ext.set(key, properties.getProperty(key))
}

val versionGradleMavenPublishPlugin = project.property("versionGradleMavenPublishPlugin") as String
val versionGradleVersionsPlugin = project.property("versionGradleVersionsPlugin") as String
val versionSpotlessPluginGradle = project.property("versionSpotlessPluginGradle") as String

dependencies {
  implementation("com.vanniktech:gradle-maven-publish-plugin:${versionGradleMavenPublishPlugin}")
  implementation("com.diffplug.spotless:spotless-plugin-gradle:${versionSpotlessPluginGradle}")
  implementation("com.github.ben-manes:gradle-versions-plugin:${versionGradleVersionsPlugin}")
  implementation(kotlin("script-runtime"))
}
