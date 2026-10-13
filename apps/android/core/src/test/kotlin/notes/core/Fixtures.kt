package notes.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The cross-platform fixtures in shared/markdown-fixtures. */
object Fixtures {
    @Serializable
    data class Case(
        val name: String,
        val markdown: String,
        val text: String? = null,
        val title: String = "",
        val tags: List<String>? = null
    )

    @Serializable
    private data class Suite(val cases: List<Case>)

    private val json = Json { ignoreUnknownKeys = true }

    val roundtrip: List<Case> by lazy { load("roundtrip.json") }
    val plaintext: List<Case> by lazy { load("plaintext.json") }
    val tags: List<Case> by lazy { load("tags.json") }

    private fun load(name: String): List<Case> {
        val dir = System.getProperty("notes.sharedDir") ?: error("notes.sharedDir not set (run through Gradle)")
        return json.decodeFromString<Suite>(File(dir, "markdown-fixtures/$name").readText()).cases
    }
}
