plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        // Room's KMP database constructor is an expect object; expect/actual classes are Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.zmanlab.diditake.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    // JVM target: fast unit tests now, rule reuse on a Kotlin backend later.
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)

            api(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)

            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    // Room 3 generates code per target (KSP only).
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
}

// Workaround (google/ksp#2476): with the AGP KMP library plugin on AGP 8.x, KSP does not hook its
// Android output into compileAndroidMain, so Room's generated `actual DidITakeDatabaseConstructor`
// is missing ("expect ... has no actual"). Verified 2026-11-11: removing these lines breaks
// :androidApp:assembleDebug. Drop them once KSP/AGP wire it themselves.
kotlin.sourceSets.named("androidMain") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/android/androidMain/kotlin"))
}
tasks.matching { it.name == "compileAndroidMain" }.configureEach { dependsOn("kspAndroidMain") }

room3 {
    // Exported schema JSON is committed; each schema version gets a migration test later.
    schemaDirectory("$projectDir/schemas")
}
