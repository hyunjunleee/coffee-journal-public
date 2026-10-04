plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.roborazzi)
}

// Version from the checked-out commit, the same on a local machine and in CI (even in a shallow clone): the code is
// the minutes between 2026-01-01 and the commit time, so every later commit installs over an earlier build.
val commitEpochSeconds: Long? = runCatching {
    providers.exec { commandLine("git", "log", "-1", "--format=%ct") }.standardOutput.asText.get().trim().toLong()
}.getOrNull()
val commitShortSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("local")

// Release signing with the project's fixed key, when it is provided (CI secrets or a local keystore); otherwise the
// release APK stays unsigned. Debug builds keep the machine's debug key.
fun signingValue(env: String, property: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() } ?: providers.gradleProperty(property).orNull
val releaseKeystore: String? = signingValue("COFFEEJOURNAL_KEYSTORE_FILE", "coffeejournal.keystore.file")

android {
    namespace = "com.coffeejournal.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.coffeejournal.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = commitEpochSeconds?.let { ((it - 1_767_225_600L) / 60L).toInt().coerceAtLeast(2) } ?: 1
        versionName = "1.6.0 ($commitShortSha)"
        // Phones and tablets only: the x86 / x86_64 builds of the native libraries (MapLibre, SQLite) serve emulators
        // and a few Chromebooks, and would add about 10 MB to the one sideloaded APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("COFFEEJOURNAL_KEYSTORE_PASSWORD", "coffeejournal.keystore.password")
                keyAlias = signingValue("COFFEEJOURNAL_KEY_ALIAS", "coffeejournal.key.alias")
                keyPassword = signingValue("COFFEEJOURNAL_KEY_PASSWORD", "coffeejournal.key.password")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // One APK for every phone (it is sideloaded), so it carries the detail map's native library (MapLibre) for both
        // ARM ABIs: about 22 MB stored uncompressed (the default since minSdk 23). Compressed, the APK is several MB
        // smaller than that and the installer extracts only the device's own ABI.
        jniLibs { useLegacyPackaging = true }
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                // Robolectric fetches its android-all jars from Maven Central at run time; a build machine that set a
                // mirror (settings.gradle.kts) hands it to Robolectric too.
                providers.gradleProperty("coffeejournal.mavenCentralMirror").orNull?.let { mirror ->
                    test.systemProperty("robolectric.dependency.repo.url", mirror.trimEnd('/'))
                    test.systemProperty("robolectric.dependency.repo.id", "mavenCentralMirror")
                }
                test.maxHeapSize = "2g"
            }
        }
    }
}

roborazzi {
    outputDir.set(file("screenshots"))
}

// The production database driver (BundledSQLiteDriver) loads a native SQLite. Its Android build cannot run on the
// test JVM, so the host build's library is unpacked here and handed to the driver through its documented system
// properties; tests that use the real platform module then run the same driver as the app.
val sqliteHostNatives: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}
val hostSqlite: Pair<String, String> = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase().let { "aarch64" in it || "arm64" in it }) "arm64" else "x64"
    if ("mac" in os) "osx_$arch" to "libsqliteJni.dylib" else "linux_$arch" to "libsqliteJni.so"
}
val unpackSqliteHostNatives by tasks.registering(Sync::class) {
    from({ sqliteHostNatives.map { zipTree(it) } }) { include("natives/${hostSqlite.first}/**") }
    into(layout.buildDirectory.dir("sqlite-host"))
}
val sqliteHostDir = layout.buildDirectory.dir("sqlite-host/natives/${hostSqlite.first}")
tasks.withType<Test>().configureEach {
    dependsOn(unpackSqliteHostNatives)
    // a failing test prints its whole stack trace, causes included, in the build log that CI keeps
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
    systemProperty("androidx.sqlite.driver.bundled.path", sqliteHostDir.get().asFile.absolutePath)
    systemProperty("androidx.sqlite.driver.bundled.name", hostSqlite.second)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    // the widget and the reminder worker read dates through the shared rules
    implementation(libs.kotlinx.datetime)

    // JVM screenshot tests (Robolectric renders the real Compose screens without an emulator)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.material3)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.sqlite.framework)
    testImplementation(libs.room.runtime)
    testImplementation(libs.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.navigation.compose)
    testImplementation(libs.work.testing)
    testImplementation(libs.glance.appwidget.testing)
    // the test application gives Coil (used by the shared module) an image loader that stays on the main thread
    testImplementation(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    sqliteHostNatives(libs.sqlite.bundled.jvm)
}

// In-app list of the libraries shipped in the APK (출처 · 라이선스 screen), kept in step with the dependencies.
apply(from = rootProject.file("gradle/third-party-notices.gradle.kts"))

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
