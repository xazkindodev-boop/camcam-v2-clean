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
        maven {
            name = "ffmpegKitNextLocal"
            url = uri(
                layout.settingsDirectory.dir(
                    "third_party/ffmpeg-kit-next-maven"
                )
            )
        }
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://artifactory.appodeal.com/appodeal-public/")
    }
}

rootProject.name = "xCam"
include(":app")
 