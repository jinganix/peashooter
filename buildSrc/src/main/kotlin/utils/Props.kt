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

import org.gradle.api.Project
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties

object Props {
  private var initialized = false

  lateinit var group: String
  lateinit var version: String
  var jacocoMinCoverage: Double = 1.0
  var releaseRepo: String = ""
  var snapshotRepo: String = ""

  fun initialize(project: Project) {
    if (initialized) {
      return
    }
    this::class.memberProperties.forEach {
      val key = it.name
      if (project.hasProperty(key)) {
        if (it is KMutableProperty<*>) {
          val value = project.property(key) as String
          when {
            (it.returnType.classifier == Int::class) -> {
              it.setter.call(this, value.toInt())
            }

            (it.returnType.classifier == Long::class) -> {
              it.setter.call(this, value.toLong())
            }

            (it.returnType.classifier == Float::class) -> {
              it.setter.call(this, value.toFloat())
            }

            (it.returnType.classifier == Double::class) -> {
              it.setter.call(this, value.toDouble())
            }

            (it.returnType.classifier == Boolean::class) -> {
              it.setter.call(this, value.toBoolean())
            }

            else -> {
              it.setter.call(this, value)
            }
          }
        }
      }
    }
    initialized = true
  }
}
