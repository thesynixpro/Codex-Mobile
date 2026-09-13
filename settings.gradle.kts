pluginManagement {
  repositories {
    // Ensure Gradle can resolve community and Google plugins (KSP is published to Maven/Google)
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

rootProject.name = "Codex Mobile"

include(":app")
