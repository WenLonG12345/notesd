package notes.core.db

/** Search query building and excerpt markers, ported from the server (internal/search/search.go). */
object Search {
    const val TITLE_WEIGHT = 10.0
    const val CONTENT_WEIGHT = 1.0
    const val MAX_TERMS = 16

    /** Private-use code points around each match in an excerpt; note text can't contain a marker by accident. */
    const val MARK_OPEN = ""
    const val MARK_CLOSE = ""

    /**
     * Free-form input → an FTS5 MATCH expression: each word becomes a quoted
     * prefix term, ANDed, so FTS5 syntax in the input is inert and no input
     * can produce a query error. "" when there is nothing to search for.
     */
    fun buildQuery(input: String): String {
        val words = ArrayList<String>()
        val word = StringBuilder()
        var i = 0
        while (i <= input.length) {
            val cp = if (i < input.length) input.codePointAt(i) else -1
            if (cp >= 0 && isWordChar(cp)) {
                word.appendCodePoint(cp)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
            i += if (cp >= 0) Character.charCount(cp) else 1
        }
        // Words hold only letters, digits and combining marks, never a quote; quoting makes OR and NEAR literal.
        return words.take(MAX_TERMS).joinToString(" ") { "\"$it\"*" }
    }

    private fun isWordChar(cp: Int) =
        Character.isLetter(cp) || Character.isDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()
}
