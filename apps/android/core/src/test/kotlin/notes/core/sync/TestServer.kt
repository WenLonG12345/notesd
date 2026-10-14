package notes.core.sync

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A real notesd (built by the buildNotesd Gradle task) on a free
 * port with an empty data dir, its owner account already created. Restarts
 * keep the port, so clients keep their URL.
 */
class TestServer : AutoCloseable {
    val dataDir: File = Files.createTempDirectory("notesd").toFile()
    private var process: Process? = null
    private var listen = "127.0.0.1:0"
    lateinit var url: HttpUrl
        private set

    init {
        val token = start() ?: error("no setup token in the server log")
        runBlocking {
            check(ApiClient(url).setup(token, USERNAME, PASSWORD) is ApiResult.Ok) { "owner setup failed" }
        }
    }

    /** Starts the server; returns the setup token if it printed one. */
    private fun start(): String? {
        val bin = System.getProperty("notes.serverBin") ?: error("notes.serverBin not set: run through Gradle")
        val p = ProcessBuilder(bin, "--data-dir", dataDir.path, "--listen", listen)
            .redirectErrorStream(true)
            .start()
        process = p
        val reader = p.inputStream.bufferedReader()
        var token: String? = null
        val log = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: error("notesd exited:\n$log")
            log.appendLine(line)
            Regex("setup_token=(\\S+)").find(line)?.let { token = it.groupValues[1] }
            val addr = Regex("listen=(\\S+)").find(line)?.groupValues?.get(1) ?: continue
            listen = addr
            url = "http://$addr/".toHttpUrl()
            break
        }
        // Keep draining the log so the server never blocks on a full pipe.
        Thread { reader.useLines { lines -> lines.forEach { } } }.apply { isDaemon = true }.start()
        return token
    }

    fun stop() {
        process?.let {
            it.destroy()
            it.waitFor(10, TimeUnit.SECONDS)
        }
        process = null
    }

    fun restart() {
        stop()
        start()
    }

    /** A copy of the data dir, for restoring later as if from a backup. Stop the server first. */
    fun snapshot(): File {
        val copy = Files.createTempDirectory("notes-backup").toFile()
        dataDir.copyRecursively(copy, overwrite = true)
        return copy
    }

    fun restore(backup: File) {
        dataDir.deleteRecursively()
        backup.copyRecursively(dataDir, overwrite = true)
    }

    override fun close() {
        stop()
        dataDir.deleteRecursively()
    }

    companion object {
        const val USERNAME = "owner"
        const val PASSWORD = "correct horse"
    }
}
