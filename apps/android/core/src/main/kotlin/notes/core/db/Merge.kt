package notes.core.db

import notes.core.markdown.Markdown

/**
 * §6.1 per-field merge against the last synced version: a field changed on
 * one side only takes that side; null when both changed it differently.
 */
internal object Merge {
    fun field(base: String?, mine: String, theirs: String, same: (String, String) -> Boolean): String? = when {
        base != null && same(mine, base) -> theirs
        same(theirs, mine) || (base != null && same(theirs, base)) -> mine
        else -> null
    }

    val sameText = { a: String, b: String -> a == b }

    /** Another client's Markdown style ("* a", "__b__") isn't an edit: compare the canonical form. */
    val sameMarkdown = { a: String, b: String -> a == b || Markdown.canonical(a) == Markdown.canonical(b) }
}
