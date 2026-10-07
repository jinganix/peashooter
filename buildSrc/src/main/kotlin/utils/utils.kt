/*
 * Copyright (c) 2020 The Peashooter Authors, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.Usage
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named

/**
 * Reads [keys] as required Gradle properties (gradle.properties plus `-P` overrides) for this
 * project, throwing once with the full sorted list when any are missing or blank. Values are
 * resolved eagerly per project, so the result is an immutable snapshot with no shared state.
 */
internal fun Project.requiredValues(keys: List<String>, owner: String, noun: String): Map<String, String> {
  val resolved = keys.associateWith {
    providers.gradleProperty(it).orNull?.takeIf(String::isNotEmpty)
  }
  val missing = resolved.filterValues { it == null }.keys.sorted()
  if (missing.isNotEmpty()) {
    throw IllegalStateException(
      "$owner is missing $noun for: ${missing.joinToString()}. " +
        "Add them to gradle.properties with exactly these keys."
    )
  }
  @Suppress("UNCHECKED_CAST")
  return resolved as Map<String, String>
}

fun Project.createConfiguration(
  name: String,
  docsType: String,
  configuration: Action<Configuration>
): Configuration {
  val conf = configurations.create(name) {
    isCanBeResolved = false
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
      attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(docsType))
    }
  }
  configuration.execute(conf)
  return conf
}

fun Project.signAndPublish(artifactId: String, desc: String) {
  val extension = extensions.getByType<MavenPublishBaseExtension>()

  if (System.getenv("GITHUB_ACTIONS")?.toBoolean() == true) {
    extension.publishToMavenCentral()
    extension.signAllPublications()
  }

  // Exactly one publication owns these coordinates: the plugin's own (jar plus its runtime
  // dependencies). Creating a second, artifact-less publication with the same coordinates made
  // its empty POM (packaging=pom, no dependencies) overwrite the real one, so the published
  // coordinate resolved to nothing for Maven consumers. POM metadata goes on the plugin's
  // publication instead. `verifyPublishedPom` guards the generated POM.
  extension.coordinates(group.toString(), artifactId, version.toString())
  extension.pom {
    name.set(artifactId)
    url.set("https://github.com/jinganix/peashooter")
    description.set(desc)
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
}
