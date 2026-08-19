package dev.gaphunter.sqlconcatenationcompanion.detect

/**
 * Finds real "SQL query built by unparameterized string concatenation/
 * interpolation, then executed" occurrences in Java, Kotlin, and Python
 * source text -- plain-text/regex analysis, same "hand-rolled over plain
 * text" principle already proven catalog-wide (see `build.gradle.kts`
 * for why no per-language PSI dependency is taken here).
 *
 * **Two layers, both required, same discipline as
 * `http-status-inline-companion`'s `HttpSignalNames`:**
 *
 * 1. **Shape**: a string literal whose text looks like the start of a
 *    real SQL statement ([SqlKeywordDetector]), combined via `+`
 *    concatenation or string-template/f-string interpolation with an
 *    operand that is NOT itself a constant string literal.
 * 2. **Context**: the resulting expression is used within
 *    [CONTEXT_WINDOW_CHARS] characters of a call that looks like it
 *    executes SQL ([SqlSignalNames]) -- either the expression is passed
 *    directly as a call argument, or it's assigned to a variable that
 *    is then, textually nearby, passed to such a call.
 *
 * **What does NOT trigger (see README "Detection heuristic" for the
 * full worked list):**
 * - Two constant string literals concatenated (`"SELECT * FROM " +
 *   "users"`) -- no variable operand, so layer 1 never matches.
 * - A SQL-keyword-shaped string with no nearby execution signal (a log
 *   message, a doc comment, a test fixture that's never passed to
 *   `execute`/`query`/`cursor.execute`) -- layer 2 filters these out.
 * - A value already parameterized correctly (`?` placeholders with
 *   `PreparedStatement.setX(...)`, Python DB-API's `cursor.execute(query,
 *   (param,))` tuple form) -- there's no `+`/interpolation building the
 *   query text itself in these cases, so layer 1 never matches.
 *
 * **Known, documented limitation** (same class of limitation as
 * `env-var-missing-companion`'s `EnvVarReferenceScanner`): this is
 * plain-text scanning, not a real per-language lexer, so a string
 * literal or comment whose *text* happens to contain the working syntax
 * verbatim (e.g. a code example quoted inside a block comment) is
 * indistinguishable from real code and could still be flagged if it
 * also sits textually near a signal call. In practice this is rare.
 * Deliberately out of v0.1 scope (see README "v0.1 scope"): indirect
 * concatenation via a `StringBuilder` accumulating query parts across
 * multiple statements/lines -- only single-expression concatenation and
 * interpolation are detected.
 */
object SqlConcatenationScanner {

    /** How far past the end of the candidate expression to look for a signal call. */
    private const val CONTEXT_WINDOW_CHARS = 200

    // --- Java/Kotlin: "..." + expr  or  expr + "..." ---------------------
    // Captures a SQL-keyword-shaped string literal immediately followed
    // by `+` and a non-string-literal operand, OR the reverse order. Kept
    // to a single `+` hop (the immediate neighbor) rather than chasing a
    // whole chain -- see README for why this is enough for the common
    // case in practice (the keyword-bearing literal is almost always
    // adjacent to the first interpolated value).
    private val STRING_LITERAL = """"(?:[^"\\]|\\.)*""""
    private val JAVA_PLUS_STRING_THEN_VAR = Regex(
        """($STRING_LITERAL)\s*\+\s*([A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?)"""
    )
    private val JAVA_PLUS_VAR_THEN_STRING = Regex(
        """([A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?)\s*\+\s*($STRING_LITERAL)"""
    )

    // --- Kotlin: string template interpolating a variable -----------------
    // "${expr}" or bare "$name" inside a double-quoted string.
    private val KOTLIN_TEMPLATE_STRING = Regex(
        """"((?:[^"\\$]|\\.|\$\{[^}]*}|\$[A-Za-z_][A-Za-z0-9_]*)*)""""
    )
    private val KOTLIN_TEMPLATE_INTERPOLATION = Regex(
        """\$\{\s*([A-Za-z_][A-Za-z0-9_.]*)[^}]*}|\$([A-Za-z_][A-Za-z0-9_]*)"""
    )

    // --- Python: f-string ---------------------------------------------
    private val PYTHON_FSTRING = Regex(
        """[fF](['"])((?:(?!\1)[^\\]|\\.)*)\1"""
    )
    private val PYTHON_FSTRING_INTERPOLATION = Regex("""\{\s*([A-Za-z_][A-Za-z0-9_.]*)[^}]*}""")

    // --- Python: "...".format(args) ------------------------------------
    private val PYTHON_FORMAT_CALL = Regex(
        """($STRING_LITERAL)\s*\.\s*format\s*\(\s*([^)]*)\)"""
    )

    // --- Python: "..." % (args) or "..." % var -------------------------
    private val PYTHON_PERCENT_FORMAT = Regex(
        """($STRING_LITERAL)\s*%\s*([A-Za-z_][A-Za-z0-9_.]*|\([^)]*\))"""
    )

    fun scan(text: String): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        results += scanJavaKotlinPlusConcat(text)
        results += scanKotlinStringTemplates(text)
        results += scanPythonFStrings(text)
        results += scanPythonFormatCalls(text)
        results += scanPythonPercentFormat(text)
        return results
            .distinctBy { it.startOffset to it.endOffset }
            .sortedBy { it.startOffset }
            .filter { hasNearbySignal(text, it.endOffset) }
    }

    private fun scanJavaKotlinPlusConcat(text: String): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()

        for (match in JAVA_PLUS_STRING_THEN_VAR.findAll(text)) {
            val literal = match.groups[1]!!
            val varName = match.groups[2]!!.value
            if (isConstantStringLiteral(varName)) continue
            if (!SqlKeywordDetector.looksLikeSqlStart(stripQuotes(literal.value))) continue
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.JAVA_KOTLIN_PLUS_CONCAT,
                startOffset = literal.range.first,
                endOffset = match.range.last + 1,
                interpolatedName = simpleNameOf(varName),
            )
        }

        for (match in JAVA_PLUS_VAR_THEN_STRING.findAll(text)) {
            val varName = match.groups[1]!!.value
            val literal = match.groups[2]!!
            if (isConstantStringLiteral(varName)) continue
            if (varName.lowercase() in RESERVED_WORDS) continue
            if (!SqlKeywordDetector.looksLikeSqlStart(stripQuotes(literal.value))) continue
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.JAVA_KOTLIN_PLUS_CONCAT,
                startOffset = match.range.first,
                endOffset = literal.range.last + 1,
                interpolatedName = simpleNameOf(varName),
            )
        }

        return results
    }

    private fun scanKotlinStringTemplates(text: String): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        for (match in KOTLIN_TEMPLATE_STRING.findAll(text)) {
            val whole = match.value
            val inner = match.groups[1]!!.value
            val interpolation = KOTLIN_TEMPLATE_INTERPOLATION.find(inner) ?: continue
            if (!SqlKeywordDetector.looksLikeSqlStart(inner)) continue
            val name = interpolation.groups[1]?.value ?: interpolation.groups[2]?.value
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.KOTLIN_STRING_TEMPLATE,
                startOffset = match.range.first,
                endOffset = match.range.first + whole.length,
                interpolatedName = name,
            )
        }
        return results
    }

    private fun scanPythonFStrings(text: String): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        for (match in PYTHON_FSTRING.findAll(text)) {
            val inner = match.groups[2]!!.value
            val interpolation = PYTHON_FSTRING_INTERPOLATION.find(inner) ?: continue
            if (!SqlKeywordDetector.looksLikeSqlStart(inner)) continue
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.PYTHON_FSTRING,
                startOffset = match.range.first,
                endOffset = match.range.last + 1,
                interpolatedName = interpolation.groups[1]?.value,
            )
        }
        return results
    }

    private fun scanPythonFormatCalls(text: String): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        for (match in PYTHON_FORMAT_CALL.findAll(text)) {
            val literal = match.groups[1]!!
            val args = match.groups[2]!!.value.trim()
            if (args.isEmpty()) continue
            if (!SqlKeywordDetector.looksLikeSqlStart(stripQuotes(literal.value))) continue
            val firstArg = args.split(",").first().trim()
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.PYTHON_FORMAT_CALL,
                startOffset = match.range.first,
                endOffset = match.range.last + 1,
                interpolatedName = simpleNameOf(firstArg).takeIf { it.isNotEmpty() },
            )
        }
        return results
    }

    private fun scanPythonPercentFormat(text: String): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        for (match in PYTHON_PERCENT_FORMAT.findAll(text)) {
            val literal = match.groups[1]!!
            val rhs = match.groups[2]!!.value
            if (!SqlKeywordDetector.looksLikeSqlStart(stripQuotes(literal.value))) continue
            val firstArg = rhs.removePrefix("(").removeSuffix(")").split(",").first().trim()
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.PYTHON_PERCENT_FORMAT,
                startOffset = match.range.first,
                endOffset = match.range.last + 1,
                interpolatedName = simpleNameOf(firstArg).takeIf { it.isNotEmpty() },
            )
        }
        return results
    }

    /**
     * True when a real SQL-execution signal ([SqlSignalNames]) appears
     * within [CONTEXT_WINDOW_CHARS] characters after the candidate
     * expression -- covers both "passed directly as a call argument"
     * (`stmt.executeQuery("SELECT ..." + id)`) and "assigned to a
     * variable, then executed a couple of lines later"
     * (`val sql = "SELECT ..." + id; stmt.executeQuery(sql)`).
     */
    private fun hasNearbySignal(text: String, afterOffset: Int): Boolean {
        val windowEnd = (afterOffset + CONTEXT_WINDOW_CHARS).coerceAtMost(text.length)
        val forwardWindow = text.substring(afterOffset, windowEnd)
        if (containsSignalCall(forwardWindow)) return true

        // Also check immediately before the expression, for the case
        // where the call wraps the expression as its argument, e.g.
        // `stmt.executeQuery("SELECT ..." + id)` -- the signal call name
        // appears *before* the string literal's start offset.
        val windowStart = (afterOffset - CONTEXT_WINDOW_CHARS).coerceAtLeast(0)
        val backwardWindow = text.substring(windowStart, afterOffset)
        return containsSignalCall(backwardWindow)
    }

    /** Any `name(` call-shaped token in [window] whose simple name is a known SQL-execution signal. */
    private fun containsSignalCall(window: String): Boolean =
        CALL_SHAPE_PATTERN.findAll(window).any { SqlSignalNames.isSignalMethodName(it.groupValues[1]) }

    private val CALL_SHAPE_PATTERN = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

    private fun stripQuotes(literalWithQuotes: String): String =
        literalWithQuotes.removeSurrounding("\"")

    private fun isConstantStringLiteral(candidate: String): Boolean =
        candidate.startsWith("\"")

    private fun simpleNameOf(expr: String): String {
        val withoutCall = expr.substringBefore("(")
        return withoutCall.substringAfterLast(".")
    }

    private val RESERVED_WORDS = setOf("return", "throw", "new", "yield")
}
