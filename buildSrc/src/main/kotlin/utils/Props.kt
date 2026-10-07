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
 * Per-project build coordinates. Registered as a project extension (see [props]), so every
 * project owns an independent immutable instance: no shared mutable state, safe under
 * `org.gradle.parallel=true` and the configuration cache.
 */
open class PropsExtension(project: Project) {
  private val required = project.requiredValues(listOf("group", "version"), "Props", "values")

  val group: String by required
  val version: String by required
  val jacocoMinCoverage: Double =
    project.providers.gradleProperty("jacocoMinCoverage").orNull?.toDouble() ?: 0.9
}

/** Returns this project's [PropsExtension], creating and freezing it on first call. */
fun Project.props(): PropsExtension =
  extensions.findByType<PropsExtension>() ?: extensions.create("props", this)
