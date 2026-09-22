package dev.gaphunter.sqlconcatenationcompanion.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.gaphunter.sqlconcatenationcompanion.detect.SqlConcatenationScanner
import dev.gaphunter.sqlconcatenationcompanion.quickfix.ParameterizeSqlFix
import dev.gaphunter.sqlconcatenationcompanion.review.ReviewPrompt

/**
 * Flags a SQL query built by unparameterized string concatenation/
 * interpolation and then executed, in Java, Kotlin, or Python source --
 * see [SqlConcatenationScanner] for the full two-layer heuristic
 * (SQL-keyword-shaped string + non-constant operand, AND a nearby
 * SQL-execution call).
 *
 * Same shape as `env-var-missing-companion`'s `MissingEnvVarInspection`:
 * `checkFile` (whole-document regex scan) rather than `buildVisitor`,
 * because detection is plain-text, not a PSI walk of one specific
 * language grammar -- registered in `plugin.xml` without a `language`
 * filter so it runs against Java, Kotlin, and Python files alike without
 * a per-ecosystem PSI dependency (see `build.gradle.kts`).
 */
class SqlConcatenationInspection : LocalInspectionTool() {

    companion object {
        /** Files larger than this are skipped -- avoids pathological regex cost on generated/minified files. */
        const val MAX_FILE_LENGTH = 500_000

        /**
         * The languages this plugin supports. Registered without a
         * `language` filter (no per-language PSI dependency), so the file
         * name is what keeps it out of Markdown, SQL scripts, docs and
         * every other file type -- a README's SQL examples aren't code.
         */
        private val SOURCE_EXTENSIONS = setOf("java", "kt", "kts", "py")
    }

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file.name.substringAfterLast('.', "").lowercase() !in SOURCE_EXTENSIONS) return null
        val text = file.text
        if (text.length > MAX_FILE_LENGTH) return null

        val matches = SqlConcatenationScanner.scan(text)
        if (matches.isEmpty()) return null

        val problems = mutableListOf<ProblemDescriptor>()
        for (match in matches) {
            ProgressManager.checkCanceled()
            val anchor = leafElementAt(file, match.startOffset) ?: continue
            val anchorStart = anchor.textRange.startOffset
            val anchorEnd = anchorStart + anchor.textLength

            // The match may span more text than a single leaf token (the
            // whole concatenation expression, not just its first
            // literal) -- clamp the highlighted range to the anchor leaf
            // itself, same defensive clamping already proven in
            // env-var-missing-companion's MissingEnvVarInspection, since
            // ProblemDescriptor's relative-TextRange overload requires
            // the range to fit inside the anchor element.
            val clampedEnd = match.endOffset.coerceAtMost(anchorEnd)
            if (clampedEnd <= anchorStart) continue
            val relativeRange = TextRange(0, clampedEnd - anchorStart)

            val varLabel = match.interpolatedName?.let { " '$it'" } ?: ""
            val message = "SQL query built by string concatenation/interpolation with an" +
                " unparameterized variable$varLabel, then executed -- vulnerable to SQL injection"

            problems += manager.createProblemDescriptor(
                anchor,
                relativeRange,
                message,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
                ParameterizeSqlFix(match.interpolatedName),
            )

            val path = file.virtualFile?.path
            if (path != null) {
                val lineNumber = file.viewProvider.document?.getLineNumber(match.startOffset) ?: -1
                ReviewPrompt.recordHit(file.project, "$path:$lineNumber")
            }
        }

        return if (problems.isEmpty()) null else problems.toTypedArray()
    }

    /**
     * Resolves a leaf PSI element covering [startOffset] -- never a
     * composite node, same documented platform gotcha as
     * `env-var-missing-companion` already handles: walks down to
     * `firstChild` until a true leaf is reached.
     */
    private fun leafElementAt(file: PsiFile, startOffset: Int): PsiElement? {
        if (startOffset < 0 || startOffset >= file.textLength) return null
        var element = file.findElementAt(startOffset) ?: return file
        while (element.firstChild != null) {
            element = element.firstChild
        }
        return element
    }
}
