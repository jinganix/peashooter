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

import freemarker.template.Configuration
import org.gradle.api.Project
import java.io.File

fun Project.generateSourceTask(input: File, output: File, data: Map<String, Any>) {
  val taskName = "generateSource"

  tasks.register(taskName) {
    doLast {
      val cfg = Configuration(Configuration.VERSION_2_3_32)

      cfg.setDirectoryForTemplateLoading(input.parentFile)
      val ftl = cfg.getTemplate(input.name)
      val outputDir = output.parentFile;
      if ((outputDir.exists() || outputDir.mkdirs()) && outputDir.exists()) {
        ftl.process(data, output.writer())
      }
    }
  }
}
