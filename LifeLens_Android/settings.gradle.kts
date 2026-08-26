fun readDotEnv(): Map<String, String> {
    val file = rootDir.resolve(".env")
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val (key, value) = line.split('=', limit = 2)
            key.trim() to value.trim().trim('"', '\'')
        }
}

val dotEnv = readDotEnv()

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "token"
                password = System.getenv("GITHUB_TOKEN") ?: dotEnv["GITHUB_TOKEN"].orEmpty()
            }
        }
    }
}

rootProject.name = "Kimchi_R1"
include(":app")
