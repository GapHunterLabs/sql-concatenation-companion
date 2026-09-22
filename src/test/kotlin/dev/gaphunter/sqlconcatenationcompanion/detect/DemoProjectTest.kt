package dev.gaphunter.sqlconcatenationcompanion.detect

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The demo project's files, read from disk: exactly the warnings its README tells a tester to expect. */
class DemoProjectTest {

    private fun warnings(path: String): Set<String> {
        val text = File(path).readText().replace("\r\n", "\n")
        return SqlConcatenationScanner.scan(text).map { match ->
            val line = text.substring(0, match.startOffset).count { it == '\n' } + 1
            "$line:${match.interpolatedName}"
        }.toSet()
    }

    @Test
    fun `java demo flags the two queries built from a String and nothing else`() {
        assertEquals(
            setOf("15:userId", "20:status"),
            warnings("demo/src/main/java/com/acmecorp/orders/OrderRepository.java"),
        )
    }

    @Test
    fun `python demo flags the f-string query only`() {
        assertEquals(setOf("3:customer"), warnings("demo/scripts/report.py"))
    }
}
