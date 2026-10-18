pluginManagement {
    repositories {
        // Local pre-fetched repository for the offline sandbox build. Only used
        // when the directory exists (local dev); on CI (GitHub Actions) it is
        // skipped and artifacts resolve from google()/mavenCentral().
        java.io.File("/home/hatch/workspace/maven-local").takeIf { it.exists() }?.let {
            maven { url = it.toURI() }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Resolve build plugins directly from Google/MavenCentral instead of the
    // Gradle Plugin Portal (unreachable marker artifacts in this environment).
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application" ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
                "org.jetbrains.kotlin.android" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
                "org.jetbrains.kotlin.plugin.compose" ->
                    useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${requested.version}")
                "com.google.devtools.ksp" ->
                    useModule("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        java.io.File("/home/hatch/workspace/maven-local").takeIf { it.exists() }?.let {
            maven { url = it.toURI() }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "AppCloner"
include(":app")
