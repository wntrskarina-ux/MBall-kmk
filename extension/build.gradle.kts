import java.io.File

plugins {
    id("com.android.application")
    id("kotlin-android")
}

// ---------------------------------------------------------------------------
// Extension metadata (read by Komikku / Mihon when the APK is loaded)
// ---------------------------------------------------------------------------
val extName = "MangaBall Enhanced"
val extClass = ".MangaBallFactory"
// CI passes an increasing build number so each release can update the previous one.
val extVersionCode = (System.getenv("EXT_VERSION_CODE") ?: "1").toInt()
val libVersion = "1.6" // API level of the extension library this APK is built against

/*
 * 0 = safe, 1 = mixed, 2 = explicit.
 *
 * This must stay 0. Both Komikku and Mihon refuse to load an extension whose content warning
 * is not 0 unless the user has enabled "Show NSFW sources" in Settings -> Browse, and the
 * extension then silently disappears from the list right after it is trusted:
 *
 *     if (!loadNsfwSource && isNsfw) return LoadResult.Error
 *
 * 18+ results are instead controlled inside the source itself ("Hide 18+ titles", which is
 * enabled by default and defaults the API to adult_mode=no_18).
 */
val contentWarning = 0
// ---------------------------------------------------------------------------

android {
    namespace = "eu.kanade.tachiyomi.extension.all.mangaball"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.all.mangaball"
        minSdk = 23
        targetSdk = 35
        versionCode = extVersionCode
        versionName = "$libVersion.$extVersionCode"

        manifestPlaceholders["appName"] = "Tachiyomi: $extName"
        manifestPlaceholders["extClass"] = extClass
        manifestPlaceholders["nsfw"] = contentWarning.toString()
        manifestPlaceholders["libVersion"] = libVersion
    }

    // The signing key lives in the repository (ci/mangaball.jks) so that every build is signed
    // with the same certificate. Without it the release APK would be unsigned and uninstallable.
    val signingKeyPath: String? = System.getenv("KEYSTORE_FILE")
    if (!signingKeyPath.isNullOrBlank() && File(signingKeyPath).exists()) {
        signingConfigs {
            create("release") {
                storeFile = File(signingKeyPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "tachiyomi-all.mangaball-enhanced-v${variant.versionName}.apk"
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Everything below is provided by the reader app at runtime. Nothing at all may be bundled
    // into the APK: the app's extension class loader is parent-LAST, so any class shipped here
    // shadows the app's own copy of it. That is fatal for the Kotlin standard library, whose
    // kotlin.coroutines.Continuation the app passes into our suspend functions - a bundled copy
    // makes every request throw java.lang.IncompatibleClassChangeError.
    // `kotlin.stdlib.default.dependency=false` in gradle.properties stops the Kotlin plugin from
    // adding stdlib as an implementation dependency; we add it back as compile-only so the code
    // still compiles against it.
    compileOnly(kotlin("stdlib"))
    compileOnly("com.github.mihonapp:tachiyomix:1.6.0") // extension API stubs
    compileOnly("com.squareup.okhttp3:okhttp:5.4.0")
    compileOnly("com.github.mihonapp:injekt:91edab2317")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("org.jsoup:jsoup:1.22.2")

    // Unit tests: JUnit plus the same API stubs so filter classes can be constructed. These are
    // test-only and are never packaged into the APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("stdlib"))
    testImplementation("com.github.mihonapp:tachiyomix:1.6.0")
}
