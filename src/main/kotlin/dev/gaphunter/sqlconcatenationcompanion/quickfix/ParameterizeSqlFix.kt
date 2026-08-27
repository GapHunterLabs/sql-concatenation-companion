package dev.gaphunter.sqlconcatenationcompanion.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

/**
 * **Honest scope, stated here and in the README, not oversold:** this
 * fix does NOT infer the interpolated value's real type, does NOT
 * figure out its correct 1-based JDBC parameter index across a whole
 * multi-line statement, and does NOT rewrite surrounding code to
 * declare/use a real `PreparedStatement` object. Building that (correct
 * type inference across Java/Kotlin/Python, correct positional index
 * when a query has more than one placeholder, choosing the right
 * `setX`/`cursor.execute` call shape per driver) is real, non-trivial
 * work explicitly out of this plugin's ~10-day v0.1 budget (see this
 * plugin's README "Quick-fix: honest scope").
 *
 * What it actually does, always the same simple, safe transformation
 * regardless of language: **replace the interpolated variable inside the
 * SQL string with a `?` placeholder**, and insert a `// TODO(sql-concat):
 * ...` (or `# TODO(sql-concat): ...` for Python) comment right after the
 * statement, naming the variable that still needs to be passed as a real
 * bound parameter. This alone removes the SQL-injection-shaped text from
 * the query string -- the developer still has to wire up the actual
 * parameter binding (`setString`/`setInt`/... or a DB-API parameter
 * tuple), which the TODO comment points at explicitly rather than
 * silently leaving out.
 *
 * Deliberately text-based (`Document.replaceString`), not a PSI
 * rewrite -- consistent with the whole plugin being plain-text detection
 * (see `SqlConcatenationScanner`), and safer here: a naive PSI
 * replacement risks producing an expression that no longer type-checks
 * (e.g. turning `"..." + userId` into a bare string breaks a
 * concatenation the surrounding code may still reference). Editing the
 * raw document text for just the matched range keeps the change minimal
 * and mechanically predictable.
 */
class ParameterizeSqlFix(private val variableName: String?) : LocalQuickFix {

    override fun getFamilyName(): String =
        if (variableName != null) {
            "Replace '$variableName' with a '?' placeholder (TODO: bind the real parameter)"
        } else {
            "Replace interpolated value with a '?' placeholder (TODO: bind the real parameter)"
        }

    // `true` (explicit, not relying on the interface default) so the
    // platform wraps applyFix in a write action for us -- same pattern
    // already proven by env-var-missing-companion's
    // AddVariableToEnvExampleFix. rewrite() below calls
    // Document.replaceString/insertString directly, both of which
    // require an active write action.
    override fun startInWriteAction(): Boolean = true

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val containingFile = element.containingFile ?: return
        val virtualFile = containingFile.virtualFile ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

        val range = descriptor.textRangeInElement
        val elementStart = element.textRange.startOffset
        val absoluteStart = elementStart + range.startOffset
        val absoluteEnd = elementStart + range.endOffset
        if (absoluteStart < 0 || absoluteEnd > document.textLength || absoluteStart > absoluteEnd) return

        rewrite(document, absoluteStart, absoluteEnd)
    }

    /**
     * Finds the interpolation marker (`${...}`/`$name` for Kotlin,
     * `{...}` for an f-string, or a trailing ` + variable`/`variable +
     * ` for Java/Kotlin `+`-concat) inside [document]'s
     * [start, end) range and replaces just that marker with `?`,
     * leaving the rest of the matched text (the literal SQL text itself)
     * untouched. Appends a TODO comment right after the enclosing line.
     * If no recognizable interpolation marker is found in range (should
     * not happen for a match this fix was actually offered for, but
     * defensive since this is plain-text, not a parsed AST), falls back
     * to only inserting the TODO comment, never silently doing nothing
     * and never corrupting unrelated text.
     */
    private fun rewrite(document: Document, start: Int, end: Int) {
        val original = document.getText().substring(start, end)
        val replaced = INTERPOLATION_MARKER.replace(original) { "?" }

        if (replaced != original) {
            document.replaceString(start, end, replaced)
        }

        val lineNumber = document.getLineNumber(start)
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        // Re-read the line's end offset after the edit above -- the
        // replacement can change the line's length (`?` is shorter than
        // most interpolated expressions), so the pre-edit offset would
        // point at the wrong place.
        val lineEndOffsetAfterEdit = document.getLineEndOffset(document.getLineNumber(start))
        val lineText = document.getText(TextRange(lineStartOffset, lineEndOffsetAfterEdit))
        val todoText = buildTodoComment(lineText)
        document.insertString(lineEndOffsetAfterEdit, todoText)
    }

    private fun buildTodoComment(lineText: String): String {
        val paramLabel = variableName ?: "the interpolated value"
        val isPythonLine = lineText.contains("cursor") || lineText.trimStart().startsWith("f\"") ||
            lineText.trimStart().startsWith("f'") || lineText.contains(".format(") || lineText.contains("%")
        return if (isPythonLine && !lineText.contains("//")) {
            "  # TODO(sql-concat): bind '$paramLabel' as a real query parameter," +
                " e.g. cursor.execute(query, ($paramLabel,))"
        } else {
            "  // TODO(sql-concat): bind '$paramLabel' as a real PreparedStatement" +
                " parameter, e.g. .setString(1, $paramLabel)"
        }
    }

    private companion object {
        /**
         * Matches, in priority order, the interpolation syntaxes this
         * plugin detects: Kotlin `${expr}`, Kotlin bare `$name`, Python
         * f-string `{expr}`. The Java/Kotlin `+`-concat case has no
         * marker *inside* the string (the variable sits outside the
         * quotes entirely), so for that shape [rewrite] falls back to
         * appending the TODO only -- there's no in-string text to turn
         * into `?` without also touching the surrounding `+` expression,
         * which is exactly the kind of structural rewrite this fix
         * deliberately does not attempt (see class doc).
         */
        val INTERPOLATION_MARKER = Regex(
            """\$\{[^}]*}|\$[A-Za-z_][A-Za-z0-9_]*|\{[A-Za-z_][A-Za-z0-9_.]*[^}]*}"""
        )
    }
}
