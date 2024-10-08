plugins {
  id("java")
  id("org.jetbrains.intellij") version "1.9.0"
}

group = "com.intellij"
version = "223.0"

repositories {
  mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
  version.set("2022.2")
  type.set("IU") // Target IDE Platform

  plugins.set(listOf(
    "com.intellij.javaee",
    "com.intellij.jsp",
    "com.intellij.javaee.el",
    "com.intellij.javaee.ejb:222.3345.118",
    "com.intellij.jsf:222.3345.118"
  ))
}

tasks {
  // Set the JVM compatibility versions
  withType<JavaCompile> {
    sourceCompatibility = "11"
    targetCompatibility = "11"
  }

  patchPluginXml {
    sinceBuild.set("222")
    untilBuild.set("223.*")
  }

  signPlugin {
    certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
    privateKey.set(System.getenv("PRIVATE_KEY"))
    password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
  }

  publishPlugin {
    token.set(System.getenv("PUBLISH_TOKEN"))
  }
}
