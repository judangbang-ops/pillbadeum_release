// 잠금 화면 알림 실험 앱 (TASK-025 준비, 2026-11-12 검토 B6). 제품 앱이 아님.
// 같은 알림을 공개 범위 방법별로 올려, 기기의 잠금 화면 알림 설정('내용 표시'·'내용 숨김')에 따라
// 잠금 화면에 약 이름과 버튼이 보이는지 에뮬레이터에서 봄. 의존성 없이 플랫폼 Notification API만 씀.
// 빌드: ./gradlew -PlockscreenExperiment :experiments:lockscreen:assembleDebug
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.judangbang.pillbadeum.lockexp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.judangbang.pillbadeum.lockexp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "experiment"
    }
}

kotlin {
    jvmToolchain(17)
}
