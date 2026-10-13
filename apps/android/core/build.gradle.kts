plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tables)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.serialization.json)
}

tasks.test {
    // Fixtures shared with the web app and the server.
    systemProperty("notes.sharedDir", rootProject.file("../../shared").absolutePath)
    inputs.dir(rootProject.file("../../shared/markdown-fixtures"))
}
