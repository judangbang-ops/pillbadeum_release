plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// 버전 번호 (TASK-002, 독립 검토 A2). CI가 빌드한 커밋까지의 커밋 수(git rev-list --count HEAD)를
// PILLBADEUM_VERSION_CODE로 넘김. release.yml 한 번의 실행에서 Play용 AAB와 개인 APK의 원본(서명하지 않은 APK)이
// 함께 나와 같은 번호를 쓰고, main에 커밋이 쌓일수록 커짐. 같은 커밋을 다시 빌드하면 같은 번호임.
// 환경 변수가 없는 로컬 빌드는 1
val ciVersionCode: Int? = providers.environmentVariable("PILLBADEUM_VERSION_CODE").orNull?.let { raw ->
    raw.trim().toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
        ?: throw GradleException("PILLBADEUM_VERSION_CODE는 1~2100000000 사이의 정수여야 함 (지금 값: '$raw')")
}

// 릴리즈 서명 (TASK-002, REQ-062). 키 파일과 비밀번호는 저장소 밖에 두고 환경 변수로만 받음.
// - release.yml: main 푸시·v* 태그·수동 실행에서 GitHub Secrets의 업로드 키로 AAB에 서명함
// - ci.yml: PR에서는 그 실행에서만 쓰는 임시 키로 같은 경로를 시험함
// - 개인 APK: 서명하지 않은 릴리즈 APK를 동희님 PC에서 앱 서명 키로 서명함 (scripts/sign-personal-apk.ps1)
// 셋 다 없으면 서명하지 않은 릴리즈 빌드를 만들고, 일부만 있으면 빌드를 멈춤
val releaseSigning: Map<String, String?> =
    listOf("PILLBADEUM_KEYSTORE_FILE", "PILLBADEUM_KEYSTORE_PASSWORD", "PILLBADEUM_KEY_ALIAS")
        .associateWith { name -> providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() } }
val releaseSigningMissing: Set<String> = releaseSigning.filterValues { it == null }.keys
if (releaseSigningMissing.isNotEmpty() && releaseSigningMissing.size < releaseSigning.size) {
    throw GradleException("릴리즈 서명 환경 변수가 일부만 있음. 없는 것: ${releaseSigningMissing.joinToString()}")
}

android {
    namespace = "com.judangbang.pillbadeum"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.judangbang.pillbadeum"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = ciVersionCode ?: 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseSigningMissing.isEmpty()) {
            create("release") {
                storeFile = file(releaseSigning.getValue("PILLBADEUM_KEYSTORE_FILE")!!)
                storePassword = releaseSigning.getValue("PILLBADEUM_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("PILLBADEUM_KEY_ALIAS")
                // PKCS12 키 저장소는 키 비밀번호가 저장소 비밀번호와 같음
                keyPassword = releaseSigning.getValue("PILLBADEUM_KEYSTORE_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            // 개인 APK와 Play AAB 모두 이 변형임 (debuggable 아님, CI가 확인함).
            // 코드 축소(R8)는 1차에 끔. 켜려면 TASK-046에서 릴리즈 빌드로 핵심 흐름을 다시 시험함 (독립 검토 A3)
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.glance.appwidget)
    // 디버그 위젯 시험의 KeepWork(경우 FK)만 씀. Glance가 이미 가져오는 판이라 앱이 받는 판은 그대로
    debugImplementation(libs.androidx.work.runtime)
}
