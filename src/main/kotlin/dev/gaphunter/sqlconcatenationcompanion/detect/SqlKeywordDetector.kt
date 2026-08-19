package dev.gaphunter.sqlconcatenationcompanion.detect

/**
 * Does a string literal's text look like the start of a real SQL
 * statement? Deliberately narrow: only the standard DML/DDL leading
 * keywords, matched at the start of the (trimmed) text -- never "the
 * word SELECT appears anywhere", which would flag ordinary log messages
 * and prose ("selected 3 rows from users table", a comment explaining a
 * query). A leading keyword is real, common SQL-authoring style: every
 * example query in this plugin's own README starts this way.
 */
object SqlKeywordDetector {

    private val LEADING_KEYWORDS = listOf(
        "select", "insert", "update", "delete", "merge", "with",
    )

    private val KEYWORD_PATTERN = Regex(
        "^(" + LEADING_KEYWORDS.joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when [literalText] (the literal's own content, not including
     * its surrounding quotes) starts -- after trimming leading
     * whitespace -- with one of the standard SQL DML/DDL keywords
     * followed by a word boundary (so `"selection"` or `"deleted_at"`
     * never match, only real `SELECT `/`DELETE ` statements).
     */
    fun looksLikeSqlStart(literalText: String): Boolean =
        KEYWORD_PATTERN.containsMatchIn(literalText.trimStart())
}
