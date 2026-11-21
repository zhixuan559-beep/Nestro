pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // WSO2's public Maven Central mirror is a fallback for the PDFBox Android AAR.
        maven {
            url = uri("https://maven.wso2.org/nexus/content/repositories/central/")
            content { includeGroup("com.tom-roush") }
        }
    }
}
rootProject.name = "Nestro"
include(":app")
