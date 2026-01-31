import utils.Vers.versionJupiter

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

mavenPublishing {
  coordinates(group.toString(), "peashooter", version.toString())

  publishToMavenCentral()
  signAllPublications()

  pom {
    name.set("peashooter")
    description.set("Call tasks sequentially and prevent deadlocks.")
    inceptionYear.set("2020")
    url.set("https://github.com/jinganix/peashooter")

    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
      }
    }

    developers {
      developer {
        id.set("gan.jin")
        name.set("JinGan")
        email.set("jinganix@gmail.com")
        url.set("https://github.com/jinganix/")
      }
    }

    scm {
      url.set("https://github.com/jinganix/peashooter")
      connection.set("scm:git:git://github.com/jinganix/peashooter.git")
      developerConnection.set("scm:git:ssh://git@github.com/jinganix/peashooter.git")
    }
  }
}
