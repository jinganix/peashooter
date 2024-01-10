/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * https://github.com/jinganix/peashooter
 */

package utils

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.Usage
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.the
import org.gradle.plugins.signing.SigningExtension
import java.io.File
import java.util.*

fun Project.signAndPublish(artifactId: String, configuration: Action<MavenPublication>) {
  val extension = project.the<PublishingExtension>()
  val publicationName = "[_-]+[a-zA-Z]".toRegex().replace(artifactId) {
    it.value.replace("_", "").replace("-", "")
      .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
  }
  val publication = extension.publications.create(publicationName, MavenPublication::class.java)
  publication.artifactId = artifactId
  publication.pom {
    name.set(publicationName)
    url.set("https://github.com/jinganix/peashooter")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
      }
    }
    developers {
      developer {
        id.set("gan.jin")
        name.set("JinGan")
        email.set("jinganix@gmail.com")
      }
    }
    scm {
      connection.set("scm:git:git://github.com/jinganix/peashooter.git")
      developerConnection.set("scm:git:ssh://github.com/jinganix/peashooter.git")
      url.set("https://github.com/jinganix/peashooter")
    }
  }
  extension.repositories {
    maven {
      name = "oss"
      val release = uri(Props.releaseRepo)
      val snapshot = uri(Props.snapshotRepo)
      url = if (version.toString().endsWith("SNAPSHOT")) snapshot else release
      credentials {
        username = System.getenv("NEXUS_REPO_USERNAME")
        password = System.getenv("NEXUS_REPO_PASSWORD")
      }
    }
  }
  configuration.execute(publication)
  val signingKey = System.getenv("GPG_SIGNING_KEY")
  if (signingKey != null) {
    val signing = the<SigningExtension>()
    val signingPassword = System.getenv("GPG_SIGNING_PASSWORD")
    signing.useInMemoryPgpKeys(signingKey, signingPassword)
    signing.sign(publication)
  }
}

fun Project.createConfiguration(
  name: String,
  docsType: String,
  configuration: Action<Configuration>
): Configuration {
  val conf = configurations.create(name) {
    isVisible = false
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
      attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(docsType))
    }
  }
  configuration.execute(conf)
  return conf
}

fun Project.extractDependencies(file: File): List<String> {
  val text = file.readText()
  val versionRegex = "(.*)\\$\\{?([\\w+]*)}?".toRegex()
  return "(implementation|testImplementation)\\(\"(.*)\"\\)".toRegex()
    .findAll(text)
    .map { it.groupValues[2] }
    .map {
      val matchResult = versionRegex.find(it) ?: return@map it
      val artifact = matchResult.groupValues[1]
      val property = matchResult.groupValues[2]
      "$artifact${project.property(property) as String}"
    }
    .toList()
}
