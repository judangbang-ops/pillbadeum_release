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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "DidITake"

include(":shared")
include(":androidApp")

// 잠금 화면 알림 실험 앱 (TASK-025 준비, 검토 B6). 제품 빌드에는 넣지 않고
// -PlockscreenExperiment 를 줄 때만 넣음 (.github/workflows/lockscreen-experiment.yml)
if (providers.gradleProperty("lockscreenExperiment").isPresent) {
    include(":experiments:lockscreen")
}
