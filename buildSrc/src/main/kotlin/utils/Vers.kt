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

import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType

/**
 * Per-project dependency versions. Registered as a project extension (see [vers]), so every
 * project owns an independent immutable instance: no shared mutable state, safe under
 * `org.gradle.parallel=true` and the configuration cache.
 */
open class VersExtension(project: Project) {
  private val required =
    project.requiredValues(
      listOf(
        "versionAssertj",
        "versionAwaitility",
        "versionCaffeine",
        "versionGoogleJavaFormat",
        "versionGradleMavenPublishPlugin",
        "versionGradleVersionsPlugin",
        "versionJacoco",
        "versionJupiter",
        "versionLogback",
        "versionMockitoCore",
        "versionNetty",
        "versionRedisson",
        "versionSlf4j",
        "versionSpotlessPluginGradle",
        "versionTestContainers",
      ),
      "Vers",
      "versions",
    )

  val versionAssertj: String by required
  val versionAwaitility: String by required
  val versionCaffeine: String by required
  val versionGoogleJavaFormat: String by required
  val versionGradleMavenPublishPlugin: String by required
  val versionGradleVersionsPlugin: String by required
  val versionJacoco: String by required
  val versionJupiter: String by required
  val versionLogback: String by required
  val versionMockitoCore: String by required
  val versionNetty: String by required
  val versionRedisson: String by required
  val versionSlf4j: String by required
  val versionSpotlessPluginGradle: String by required
  val versionTestContainers: String by required
}

/** Returns this project's [VersExtension], creating and freezing it on first call. */
fun Project.vers(): VersExtension =
  extensions.findByType<VersExtension>() ?: extensions.create("vers", this)
