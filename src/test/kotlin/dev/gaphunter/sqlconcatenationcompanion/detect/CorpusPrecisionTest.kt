package dev.gaphunter.sqlconcatenationcompanion.detect

import org.junit.Test
import java.io.File

/**
 * Runs the scanner over a corpus of real Java/Kotlin/Python files that
 * execute SQL and writes every match, with its line, to
 * build/corpus-verdicts.txt for manual review. Skipped unless the
 * SQL_CONCAT_CORPUS environment variable points at a corpus directory
 * (public files from other repositories, never committed here).
 */
class CorpusPrecisionTest {

    @Test
    fun `matches on a real corpus`() {
        val corpus = System.getenv("SQL_CONCAT_CORPUS")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val files = corpus.listFiles { f -> f.extension in setOf("java", "kt", "py") }!!.sortedBy { it.name }
        val report = StringBuilder()
        val filesByExtension = files.groupingBy { it.extension }.eachCount()
        val hitsByExtension = sortedMapOf<String, Int>()
        val filesWithHits = sortedMapOf<String, Int>()
        for (file in files) {
            val text = file.readText().replace("\r\n", "\n")
            val matches = SqlConcatenationScanner.scan(text)
            if (matches.isEmpty()) continue
            hitsByExtension.merge(file.extension, matches.size, Int::plus)
            filesWithHits.merge(file.extension, 1, Int::plus)
            val lineStarts = text.indices.filter { it == 0 || text[it - 1] == '\n' }
            for (m in matches) {
                val line = lineStarts.indexOfLast { it <= m.startOffset }
                val lineText = text.substring(lineStarts[line]).substringBefore('\n').trim()
                report.append("${file.name}:${line + 1} | ${m.kind} | ${m.interpolatedName} | ${lineText.take(200)}\n")
            }
        }
        val summary = filesByExtension.keys.sorted().joinToString("\n") { ext ->
            "$ext: ${filesByExtension[ext]} files, ${filesWithHits[ext] ?: 0} with matches, ${hitsByExtension[ext] ?: 0} matches"
        }
        File("build").mkdirs()
        File("build/corpus-verdicts.txt").writeText(summary + "\n\n" + report)
        println(summary)
    }
}
