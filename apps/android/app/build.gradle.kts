plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Robolectric runs the Android build of the bundled SQLite driver, which loads
// "sqliteJni" from java.library.path; take the host's copy from the JVM artifact.
val hostSqlite: Configuration by configurations.creating { isTransitive = false }
val hostPlatform = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
    when {
        "mac" in os -> "osx_$arch"
        "windows" in os -> "windows_$arch"
        else -> "linux_$arch"
    }
}
interface ArchiveAccess {
    @get:Inject val archives: ArchiveOperations
}
val hostSqliteNatives = tasks.register<Sync>("hostSqliteNatives") {
    // Locals only: the configuration cache can't serialise lambdas that capture the script.
    val archives = objects.newInstance<ArchiveAccess>().archives
    val natives = "natives/$hostPlatform/*"
    from(hostSqlite.elements.map { jars -> jars.map { archives.zipTree(it) } }) {
        include(natives)
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("hostSqlite"))
}

android {
    namespace = "notes.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "notes.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(hostSqliteNatives)
            it.systemProperty("java.library.path", hostSqliteNatives.get().destinationDir.absolutePath)
            // Fixtures shared with the web app and the server.
            it.systemProperty("notes.sharedDir", rootProject.file("../../shared").absolutePath)
            // Robolectric reaches into JDK internals (FileDescriptor, SharedSecrets) that are closed by default.
            it.jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED"
            )
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Version bumps are deliberate and go through the catalog.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.work.runtime)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    hostSqlite(libs.sqlite.bundled.jvm)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.test.core)
}
