import java.time.Duration

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

// The real notes-server, built once per change to its sources; sync tests start a fresh instance per test.
val serverDir = rootProject.file("../../server")
val serverBin = layout.buildDirectory.file("notes-server/notes-server")
val buildNotesServer = tasks.register<Exec>("buildNotesServer") {
    workingDir = serverDir
    commandLine("go", "build", "-o", serverBin.get().asFile.absolutePath, "./cmd/notes-server")
    inputs.files(fileTree(serverDir) { include("**/*.go", "**/*.sql", "go.mod", "go.sum") })
        .withPropertyName("serverSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(serverBin)
}

tasks.test {
    // A hung server or sync loop fails the run instead of stalling it.
    timeout.set(Duration.ofMinutes(5))
    dependsOn(buildNotesServer)
    systemProperty("notes.serverBin", serverBin.get().asFile.absolutePath)
    // More seeds for RandomSyncTest: ./gradlew :core:test -Pnotes.seeds=1,2,3
    providers.gradleProperty("notes.seeds").orNull?.let { systemProperty("notes.seeds", it) }
    // Fixtures shared with the web app and the server.
    systemProperty("notes.sharedDir", rootProject.file("../../shared").absolutePath)
    inputs.dir(rootProject.file("../../shared/markdown-fixtures"))
}
