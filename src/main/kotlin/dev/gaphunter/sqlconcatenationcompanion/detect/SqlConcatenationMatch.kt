package dev.gaphunter.sqlconcatenationcompanion.detect

/** Which source-language shape triggered the match -- drives quick-fix wording. */
enum class SqlConcatenationKind {
    JAVA_KOTLIN_PLUS_CONCAT,
    KOTLIN_STRING_TEMPLATE,
    PYTHON_FSTRING,
    PYTHON_FORMAT_CALL,
    PYTHON_PERCENT_FORMAT,
}

/**
 * One confirmed finding: a SQL-shaped string built by concatenation or
 * interpolation with a non-constant operand, sitting near a real
 * SQL-execution call (see [SqlSignalNames]).
 *
 * @property startOffset start of the whole suspicious expression (the
 *   opening quote of the first/leading string literal), for anchoring
 *   the warning.
 * @property endOffset end of the whole suspicious expression.
 * @property interpolatedName best-effort variable/expression name found
 *   interpolated into the query -- used by the quick-fix message; falls
 *   back to a generic placeholder when it can't be extracted cleanly
 *   (e.g. a non-trivial expression rather than a bare identifier).
 */
data class SqlConcatenationMatch(
    val kind: SqlConcatenationKind,
    val startOffset: Int,
    val endOffset: Int,
    val interpolatedName: String?,
)
