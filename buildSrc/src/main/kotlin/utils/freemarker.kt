import freemarker.template.Configuration
import org.gradle.api.Project
import java.io.File

fun Project.generateSourceTask(input: File, output: File, data: Map<String, Any>) {
  val taskName = "generateSource"

  task(taskName) {
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
