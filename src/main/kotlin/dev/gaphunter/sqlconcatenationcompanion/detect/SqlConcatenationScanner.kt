package dev.gaphunter.sqlconcatenationcompanion.detect

import com.intellij.openapi.progress.ProgressManager

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
 *    concatenation or string-template/f-string/`format`/`%` interpolation
 *    with at least one operand that could carry injected SQL -- not a
 *    literal, a constant, a number or a literal-only local ([isSafeOperand]).
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
 * also sits textually near a signal call. Commented-out lines are
 * skipped. Out of scope: a `StringBuilder` accumulating query parts
 * across statements, and values that are only safe through data flow (a
 * local picked from two literals, a name checked against an allow-list)
 * -- only single-expression concatenation and interpolation are detected.
 */
object SqlConcatenationScanner {

    /** How far past the end of the candidate expression to look for a signal call. */
    private const val CONTEXT_WINDOW_CHARS = 200

    // --- Java/Kotlin: "..." + expr + "..."  or  expr + "..." ------------
    // A SQL-keyword-shaped string literal followed by a `+` chain (see
    // scanJavaKotlinPlusConcat), or an operand right before one.
    //
    // Every literal pattern below is written in the "unrolled" form
    // (`"[^"\\\n]*(?:\\.[^"\\\n]*)*"`), never as an alternation under a
    // star (`"(?:[^"\\]|\\.)*"`): java.util.regex recurses once per
    // iteration of an alternation group, so on real files -- a stray quote
    // opening a span of thousands of characters -- the old form overflowed
    // the stack. Plain literals stay on one line; triple-quoted ones (Kotlin
    // raw strings, Java text blocks, Python) have their own lazy pattern.
    private const val TRIPLE_DOUBLE = "\"\"\"[\\s\\S]*?\"\"\""
    private const val TRIPLE_SINGLE = "'''[\\s\\S]*?'''"
    private val DOUBLE_QUOTED = """"[^"\\\n]*(?:\\.[^"\\\n]*)*""""
    private val SINGLE_QUOTED = """'[^'\\\n]*(?:\\.[^'\\\n]*)*'"""

    /** A Java/Kotlin/Python double-quoted literal, triple-quoted first so `"""` isn't read as `""` + `"`. */
    private val STRING_LITERAL = "$TRIPLE_DOUBLE|$DOUBLE_QUOTED"

    private val STRING_LITERAL_REGEX = Regex(STRING_LITERAL)

    /** Python also quotes with `'`. */
    private val PYTHON_STRING_LITERAL = "$TRIPLE_DOUBLE|$TRIPLE_SINGLE|$DOUBLE_QUOTED|$SINGLE_QUOTED"

    private val JAVA_PLUS_VAR_THEN_STRING = Regex(
        """([\p{L}_][\p{L}\p{N}_.]*(?:\([^)]*\))?)\s*\+\s*($STRING_LITERAL)"""
    )

    // --- Kotlin: string template interpolating a variable -----------------
    // "${expr}" or bare "$name" inside a double-quoted or raw string.
    private val KOTLIN_TEMPLATE_STRING = Regex(
        "$TRIPLE_DOUBLE|" + """"[^"\\$\n]*(?:(?:\\.|\$\{[^}\n]*}|\$)[^"\\$\n]*)*""""
    )
    private val KOTLIN_TEMPLATE_INTERPOLATION = Regex(
        """\$\{([^}]*)}|\$([\p{L}_][\p{L}\p{N}_]*)"""
    )

    // --- Python: f-string (also rf/fr, and triple-quoted) --------------
    private val PYTHON_FSTRING = Regex(
        """(?<![A-Za-z0-9_])(?:[rR][fF]|[fF][rR]?)(?:$PYTHON_STRING_LITERAL)"""
    )
    /** `{expr}`, not the `{{`/`}}` escapes for literal braces. */
    private val PYTHON_FSTRING_INTERPOLATION = Regex("""(?<!\{)\{([^{}]+)}(?!})""")

    // --- Python: "...".format(args) ------------------------------------
    private val PYTHON_FORMAT_CALL = Regex(
        """($PYTHON_STRING_LITERAL)\s*\.\s*format\s*\(\s*([^)]*)\)"""
    )

    // --- Python: "..." % (args) or "..." % var -------------------------
    private val PYTHON_PERCENT_FORMAT = Regex(
        """($PYTHON_STRING_LITERAL)\s*%\s*([\p{L}_][\p{L}\p{N}_.]*|\([^)]*\))"""
    )
    /** The conversion character of each `%` placeholder: `%s`, `%(name)d`, `%5.2f`, `%%`. */
    private val PERCENT_CONVERSION = Regex("""%(?:\([^)]*\))?[-#0 +]*(?:\d+|\*)?(?:\.\d+)?([A-Za-z%])""")
    private val NUMERIC_CONVERSIONS = setOf("d", "i", "u", "f", "F", "e", "E", "g", "G", "x", "X", "o")

    /** One `+ "literal"` or `+ operand` link of a concatenation chain, read from a given position. */
    private val CHAIN_LINK = Regex("""\s*\+\s*(?:($STRING_LITERAL)|([\p{L}_][\p{L}\p{N}_.]*(?:\([^)]*\))?))""")

    /** Java, Kotlin and Python all allow non-ASCII letters (`Id_inscripción`). */
    private val IDENTIFIER = Regex("""[\p{L}_][\p{L}\p{N}_]*""")
    private val CONSTANT_NAME = Regex("""[A-Z][A-Z0-9_]+""")

    /** How far back to look for an operand's declaration: about a long method. */
    private const val LOOKBACK_CHARS = 5_000
    private val NUMERIC_TYPES = setOf(
        "int", "long", "short", "byte", "double", "float", "boolean",
        "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean",
        "Int", "UInt", "ULong", "bool",
    )
    /** Calls whose result can't carry injected SQL: numbers, and a class's own name. */
    private val SAFE_CALLS = setOf(
        "int", "float", "len", "parseInt", "parseLong", "parseShort", "parseDouble", "parseFloat",
        "size", "length", "toInt", "toLong", "toDouble", "getSimpleName", "getCanonicalName",
    )

    /** A whole string or number literal, on its own. */
    private val LITERAL_VALUE = Regex(""""[^"\\\n]*(?:\\.[^"\\\n]*)*"|'[^'\\\n]*(?:\\.[^'\\\n]*)*'|-?\d+(?:\.\d+)?[LlFfDd]?""")
    private val COMMENT_STARTS = listOf("//", "#", "/*", "*")
    private val STRING_CONVERSIONS = setOf("str", "valueOf", "toString")

    /** `int id,` / `String name =` / `for (long n : ...)`: type, then name. */
    private val PREFIX_DECLARATION = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s+([\p{L}_][\p{L}\p{N}_]*)\s*[,;=):]""")
    /** `id: Int` (Kotlin), `id: int` (Python annotations): name, then type. */
    private val SUFFIX_DECLARATION = Regex("""\b([\p{L}_][\p{L}\p{N}_]*)\s*:\s*([A-Za-z_][A-Za-z0-9_]*)""")
    /** `id = <rest of the statement>`, not `==`. */
    private val ASSIGNMENT = Regex("""\b([\p{L}_][\p{L}\p{N}_]*)\s*=(?!=)\s*([^\n;]*)""")

    /** Words that can sit before a name without declaring it (`return id;`, `else id = 1`). */
    private val NOT_A_TYPE = setOf("return", "throw", "new", "else", "case", "yield", "in", "is", "as", "not", "and", "or", "val", "var")

    fun scan(text: String): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        val names = NameIndex(text)
        results += scanJavaKotlinPlusConcat(text, names)
        results += scanKotlinStringTemplates(text, names)
        results += scanPythonFStrings(text, names)
        results += scanPythonFormatCalls(text, names)
        results += scanPythonPercentFormat(text, names)
        return results
            .distinctBy { it.startOffset to it.endOffset }
            .sortedBy { it.startOffset }
            .filter { !isCommentedOut(text, it.startOffset) && hasNearbySignal(text, it.startOffset, it.endOffset) }
    }

    /**
     * `"SELECT ..." + a + " WHERE id = " + b`: starts at a SQL-shaped
     * literal and walks the whole `+` chain, so a constant right after the
     * keyword (`"SELECT * FROM " + TABLE + " WHERE id = " + id`) neither
     * triggers the warning nor hides the variable further along. The name
     * reported is the first operand that isn't safe ([isSafeOperand]).
     */
    private fun scanJavaKotlinPlusConcat(text: String, names: NameIndex): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()

        for (literal in STRING_LITERAL_REGEX.findAll(text)) {
            ProgressManager.checkCanceled()
            if (!SqlKeywordDetector.looksLikeSqlStart(stripQuotes(literal.value))) continue
            var pos = literal.range.last + 1
            var unsafe: String? = null
            val link = CHAIN_LINK.toPattern().matcher(text)
            while (link.region(pos, text.length).lookingAt()) {
                val operand = link.group(2)
                if (operand != null && unsafe == null && !isSafeOperand(names,operand, literal.range.first)) {
                    unsafe = operand
                }
                pos = link.end()
            }
            if (unsafe == null) continue
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.JAVA_KOTLIN_PLUS_CONCAT,
                startOffset = literal.range.first,
                endOffset = pos,
                interpolatedName = simpleNameOf(unwrapStringConversion(unsafe)),
            )
        }

        for (match in JAVA_PLUS_VAR_THEN_STRING.findAll(text)) {
            ProgressManager.checkCanceled()
            val varName = match.groups[1]!!.value
            val literal = match.groups[2]!!
            if (isConstantStringLiteral(varName)) continue
            if (varName.lowercase() in RESERVED_WORDS) continue
            if (!SqlKeywordDetector.looksLikeSqlStart(stripQuotes(literal.value))) continue
            if (isSafeOperand(names,varName, match.range.first)) continue
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.JAVA_KOTLIN_PLUS_CONCAT,
                startOffset = match.range.first,
                endOffset = literal.range.last + 1,
                interpolatedName = simpleNameOf(unwrapStringConversion(varName)),
            )
        }

        return results
    }

    private fun scanKotlinStringTemplates(text: String, names: NameIndex): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        for (match in KOTLIN_TEMPLATE_STRING.findAll(text)) {
            ProgressManager.checkCanceled()
            val whole = match.value
            val inner = stripQuotes(whole)
            if (!SqlKeywordDetector.looksLikeSqlStart(inner)) continue
            val unsafe = KOTLIN_TEMPLATE_INTERPOLATION.findAll(inner)
                .map { (it.groups[1]?.value ?: it.groups[2]!!.value).trim() }
                .firstOrNull { !isSafeOperand(names,it, match.range.first) }
                ?: continue
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.KOTLIN_STRING_TEMPLATE,
                startOffset = match.range.first,
                endOffset = match.range.first + whole.length,
                interpolatedName = leadingName(unsafe),
            )
        }
        return results
    }

    private fun scanPythonFStrings(text: String, names: NameIndex): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        for (match in PYTHON_FSTRING.findAll(text)) {
            ProgressManager.checkCanceled()
            val inner = stripQuotes(match.value.trimStart('r', 'R', 'f', 'F'))
            if (!SqlKeywordDetector.looksLikeSqlStart(inner)) continue
            val unsafe = PYTHON_FSTRING_INTERPOLATION.findAll(inner)
                .map { it.groups[1]!!.value.substringBefore('!').substringBefore(':').trim() }
                .firstOrNull { it.isNotEmpty() && !isSafeOperand(names,it, match.range.first) }
                ?: continue
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.PYTHON_FSTRING,
                startOffset = match.range.first,
                endOffset = match.range.last + 1,
                interpolatedName = leadingName(unsafe),
            )
        }
        return results
    }

    private fun scanPythonFormatCalls(text: String, names: NameIndex): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        for (match in PYTHON_FORMAT_CALL.findAll(text)) {
            ProgressManager.checkCanceled()
            val literal = match.groups[1]!!
            val args = match.groups[2]!!.value.trim()
            if (args.isEmpty()) continue
            if (!SqlKeywordDetector.looksLikeSqlStart(stripQuotes(literal.value))) continue
            val unsafe = args.split(",").map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !isSafeOperand(names,it, match.range.first) }
                ?: continue
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.PYTHON_FORMAT_CALL,
                startOffset = match.range.first,
                endOffset = match.range.last + 1,
                interpolatedName = simpleNameOf(unwrapStringConversion(unsafe)).takeIf { it.isNotEmpty() },
            )
        }
        return results
    }

    private fun scanPythonPercentFormat(text: String, names: NameIndex): List<SqlConcatenationMatch> {
        val results = mutableListOf<SqlConcatenationMatch>()
        for (match in PYTHON_PERCENT_FORMAT.findAll(text)) {
            ProgressManager.checkCanceled()
            val literal = match.groups[1]!!
            val rhs = match.groups[2]!!.value
            val format = stripQuotes(literal.value)
            if (!SqlKeywordDetector.looksLikeSqlStart(format)) continue
            // `%d`/`%f` only: Python raises before a non-number reaches the query.
            val conversions = PERCENT_CONVERSION.findAll(format).map { it.groupValues[1] }.filter { it != "%" }.toList()
            if (conversions.isNotEmpty() && conversions.all { it in NUMERIC_CONVERSIONS }) continue
            val unsafe = rhs.removePrefix("(").removeSuffix(")").split(",").map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !isSafeOperand(names,it, match.range.first) }
                ?: continue
            results += SqlConcatenationMatch(
                kind = SqlConcatenationKind.PYTHON_PERCENT_FORMAT,
                startOffset = match.range.first,
                endOffset = match.range.last + 1,
                interpolatedName = simpleNameOf(unwrapStringConversion(unsafe)).takeIf { it.isNotEmpty() },
            )
        }
        return results
    }

    /**
     * An operand that can't carry injected SQL, judged from the text alone
     * (the same values the IDE's own data-flow SQL inspection treats as
     * safe, where plain text can see them):
     * - a constant by naming convention (`TABLE_USERS`, `Contract.KEY_ID`,
     *   Kotlin `const val`, Python module constants);
     * - a number: `int(x)`, `Integer.parseInt(x)`, `list.size()`, `len(x)`,
     *   or a plain name whose nearest declaration before the query in this
     *   file has a numeric or boolean type (`int id`, `id: Int`, `id: int`).
     */
    private fun isSafeOperand(names: NameIndex, operand: String, queryStart: Int): Boolean {
        val expr = operand.trim()
        if ('(' in expr) {
            val callee = expr.substringBefore('(').trim()
            if (callee.substringAfterLast('.') in SAFE_CALLS) return true
            // `passHash("lee")`: no receiver and only literal arguments.
            val args = expr.substringAfter('(').substringBeforeLast(')').split(',').map { it.trim() }
            return '.' !in callee && args.isNotEmpty() && args.all { LITERAL_VALUE.matches(it) }
        }
        if (isConstantName(expr.substringAfterLast('.'))) return true
        if (!IDENTIFIER.matches(expr)) return false
        // `final String var1 = "arsalan";`: last set to a literal before the query.
        return names.typeBefore(expr, queryStart) in NUMERIC_TYPES || names.assignedLiteralBefore(expr, queryStart)
    }

    /**
     * Every declaration (`int id`, `id: Int`, `id: int`) and assignment
     * (`id = ...`) in the file, read once per scan: checking an operand is
     * then a lookup of the last one within [LOOKBACK_CHARS] before the
     * query, not a regex pass over that stretch of text for each operand.
     */
    private class NameIndex(text: String) {
        private val types = HashMap<String, MutableList<Pair<Int, String>>>()
        private val assignments = HashMap<String, MutableList<Pair<Int, Boolean>>>()

        init {
            for (m in PREFIX_DECLARATION.findAll(text)) {
                ProgressManager.checkCanceled()
                if (m.groupValues[1] !in NOT_A_TYPE) types.getOrPut(m.groupValues[2]) { mutableListOf() } += m.range.first to m.groupValues[1]
            }
            for (m in SUFFIX_DECLARATION.findAll(text)) {
                ProgressManager.checkCanceled()
                types.getOrPut(m.groupValues[1]) { mutableListOf() } += m.range.first to m.groupValues[2]
            }
            types.values.forEach { entries -> entries.sortBy { it.first } }
            for (m in ASSIGNMENT.findAll(text)) {
                ProgressManager.checkCanceled()
                assignments.getOrPut(m.groupValues[1]) { mutableListOf() } += m.range.first to LITERAL_VALUE.matches(m.groupValues[2].trim())
            }
        }

        fun typeBefore(name: String, before: Int): String? = lastBefore(types[name], before)

        fun assignedLiteralBefore(name: String, before: Int): Boolean = lastBefore(assignments[name], before) == true

        /** Entries are sorted by offset: binary search for the last one before [before], if within reach. */
        private fun <T> lastBefore(entries: List<Pair<Int, T>>?, before: Int): T? {
            if (entries.isNullOrEmpty()) return null
            val insertion = entries.binarySearchBy(before) { it.first }
            val index = (if (insertion >= 0) insertion else -insertion - 1) - 1
            val entry = entries.getOrNull(index) ?: return null
            return entry.second.takeIf { entry.first >= before - LOOKBACK_CHARS }
        }
    }

    /** A line comment (`//`, `#`) or a block-comment opening or continuation line before [offset] on its line. */
    private fun isCommentedOut(text: String, offset: Int): Boolean {
        val prefix = text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset).trimStart()
        return COMMENT_STARTS.any { prefix.startsWith(it) }
    }

    /** `TABLE_USERS`, `KEY_ID`, `ID`: at least two characters, upper case, digits and underscores. */
    fun isConstantName(name: String): Boolean = CONSTANT_NAME.matches(name)

    /** `str(id)` / `String.valueOf(id)` name the value inside, not the conversion. */
    private fun unwrapStringConversion(expr: String): String {
        val call = expr.substringBefore('(').substringAfterLast('.').trim()
        return if (call in STRING_CONVERSIONS && expr.endsWith(")")) {
            expr.substringAfter('(').removeSuffix(")").trim()
        } else {
            expr
        }
    }

    /** `user.id` from `user.id.lowercase()`: the dotted name an interpolation starts with, or null. */
    private fun leadingName(expr: String): String? =
        Regex("""^[\p{L}_][\p{L}\p{N}_.]*""").find(expr)?.value?.trimEnd('.')

    /**
     * True when a real SQL-execution signal ([SqlSignalNames]) appears
     * within [CONTEXT_WINDOW_CHARS] characters after the candidate
     * expression -- covers both "passed directly as a call argument"
     * (`stmt.executeQuery("SELECT ..." + id)`) and "assigned to a
     * variable, then executed a couple of lines later"
     * (`val sql = "SELECT ..." + id; stmt.executeQuery(sql)`).
     */
    private fun hasNearbySignal(text: String, startOffset: Int, endOffset: Int): Boolean {
        val windowEnd = (endOffset + CONTEXT_WINDOW_CHARS).coerceAtMost(text.length)
        val forwardWindow = text.substring(endOffset, windowEnd)
        if (containsSignalCall(forwardWindow)) return true

        // Also check before the expression (and inside it), for the case
        // where the call wraps the expression as its argument, e.g.
        // `stmt.executeQuery("SELECT ..." + id)` -- the signal call name
        // appears *before* the string literal's start offset. Measured from
        // the start, so a long concatenation chain can't push the wrapping
        // call out of the window.
        val windowStart = (startOffset - CONTEXT_WINDOW_CHARS).coerceAtLeast(0)
        val backwardWindow = text.substring(windowStart, endOffset)
        return containsSignalCall(backwardWindow)
    }

    /** Any `name(` call-shaped token in [window] whose simple name is a known SQL-execution signal. */
    private fun containsSignalCall(window: String): Boolean =
        CALL_SHAPE_PATTERN.findAll(window).any { SqlSignalNames.isSignalMethodName(it.groupValues[1]) }

    private val CALL_SHAPE_PATTERN = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

    private fun stripQuotes(literalWithQuotes: String): String =
        listOf("\"\"\"", "'''", "\"", "'")
            .firstOrNull { literalWithQuotes.length >= 2 * it.length && literalWithQuotes.startsWith(it) && literalWithQuotes.endsWith(it) }
            ?.let { literalWithQuotes.substring(it.length, literalWithQuotes.length - it.length) }
            ?: literalWithQuotes

    private fun isConstantStringLiteral(candidate: String): Boolean =
        candidate.startsWith("\"")

    /**
     * The name shown in the warning and the quick-fix's TODO: the last
     * segment of a dotted name (`r.frame` -> `frame`), but a short call in
     * full (`ac.get(i)`, `user.getID()`), since the method name alone
     * (`get`) says nothing about which value to bind.
     */
    private fun simpleNameOf(expr: String): String {
        val trimmed = expr.trim()
        if ('(' in trimmed && trimmed.length <= MAX_CALL_NAME_LENGTH) return trimmed
        val withoutCall = trimmed.substringBefore("(")
        return withoutCall.substringAfterLast(".")
    }

    private const val MAX_CALL_NAME_LENGTH = 40

    private val RESERVED_WORDS = setOf("return", "throw", "new", "yield")
}
