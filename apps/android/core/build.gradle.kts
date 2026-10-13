plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tables)
    api(libs.sqlite.bundled)
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
}

tasks.test {
    // Fixtures shared with the web app and the server.
    systemProperty("notes.sharedDir", rootProject.file("../../shared").absolutePath)
    inputs.dir(rootProject.file("../../shared/markdown-fixtures"))
}
