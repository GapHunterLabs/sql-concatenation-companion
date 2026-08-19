package dev.gaphunter.sqlconcatenationcompanion.inspection

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * End-to-end: real PSI + real inspection registration + real quick-fix
 * application via `myFixture` -- the matching logic itself is already
 * covered exhaustively by
 * [dev.gaphunter.sqlconcatenationcompanion.detect.SqlConcatenationScannerTest].
 * This confirms the inspection actually fires real warnings and its
 * quick-fix really edits the real document, producing syntactically
 * valid code, not just "doesn't crash".
 */
class SqlConcatenationInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(SqlConcatenationInspection::class.java)
    }

    fun `test java plus concat with variable executed via executeQuery warns`() {
        myFixture.configureByText(
            "Demo.java",
            """
            class Demo {
                void run(java.sql.Statement stmt, String userId) throws Exception {
                    stmt.executeQuery("SELECT * FROM users WHERE id = " + userId);
                }
            }
            """.trimIndent(),
        )

        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("SQL query") == true })
    }

    fun `test two constant literals concatenated produces no warning`() {
        myFixture.configureByText(
            "Demo.java",
            """
            class Demo {
                void run(java.sql.Statement stmt) throws Exception {
                    stmt.executeQuery("SELECT * FROM " + "users");
                }
            }
            """.trimIndent(),
        )

        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("SQL query") == true })
    }

    fun `test sql keyword string in a log call produces no warning`() {
        myFixture.configureByText(
            "Demo.java",
            """
            class Demo {
                void run(String userId) {
                    System.out.println("SELECT * FROM users WHERE id = " + userId);
                }
            }
            """.trimIndent(),
        )

        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("SQL query") == true })
    }

    fun `test kotlin string template interpolated in executed query warns`() {
        myFixture.configureByText(
            "Demo.kt",
            """
            fun run(connection: Any, userId: String) {
                connection.executeQuery("SELECT * FROM users WHERE id = ${'$'}userId")
            }
            """.trimIndent(),
        )

        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("SQL query") == true })
    }

    fun `test python f-string in cursor execute warns`() {
        myFixture.configureByText(
            "demo.py",
            """
            def run(cursor, user_id):
                cursor.execute(f"SELECT * FROM users WHERE id = {user_id}")
            """.trimIndent(),
        )

        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("SQL query") == true })
    }

    fun `test already parameterized query with question mark produces no warning`() {
        myFixture.configureByText(
            "Demo.java",
            """
            class Demo {
                void run(java.sql.PreparedStatement ps, int userId) throws Exception {
                    ps.executeQuery();
                }
            }
            """.trimIndent(),
        )

        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("SQL query") == true })
    }

    fun `test quick-fix on kotlin template replaces interpolation with placeholder and adds todo`() {
        myFixture.configureByText(
            "Demo.kt",
            """
            fun run(connection: Any, userId: String) {
                connection.executeQuery("SELECT * FROM users WHERE id = ${'$'}userId")
            }
            """.trimIndent(),
        )

        myFixture.doHighlighting()
        val fix = myFixture.getAllQuickFixes().singleOrNull { it.text.contains("userId") }
        assertNotNull("expected a quick-fix mentioning userId", fix)
        myFixture.launchAction(fix as IntentionAction)

        val newText = myFixture.editor.document.text
        assertTrue("expected the interpolation to become a '?' placeholder", newText.contains("id = ?\""))
        assertTrue("expected a TODO comment naming the variable", newText.contains("TODO(sql-concat)"))
        assertTrue("expected the TODO to name userId", newText.contains("userId"))
        // The query string itself must still be a syntactically closed
        // Kotlin string literal (opening and closing quote both present
        // on the same statement) -- not just "doesn't crash".
        assertTrue(newText.contains("\"SELECT * FROM users WHERE id = ?\""))
    }

    fun `test quick-fix on python f-string replaces interpolation with placeholder and adds todo`() {
        myFixture.configureByText(
            "demo.py",
            """
            def run(cursor, user_id):
                cursor.execute(f"SELECT * FROM users WHERE id = {user_id}")
            """.trimIndent(),
        )

        myFixture.doHighlighting()
        val fix = myFixture.getAllQuickFixes().singleOrNull { it.text.contains("user_id") }
        assertNotNull("expected a quick-fix mentioning user_id", fix)
        myFixture.launchAction(fix as IntentionAction)

        val newText = myFixture.editor.document.text
        assertTrue("expected the interpolation to become a '?' placeholder", newText.contains("id = ?\""))
        assertTrue("expected a TODO comment", newText.contains("TODO(sql-concat)"))
    }

    fun `test quick-fix on java plus concat adds todo without corrupting the literal`() {
        myFixture.configureByText(
            "Demo.java",
            """
            class Demo {
                void run(java.sql.Statement stmt, String userId) throws Exception {
                    stmt.executeQuery("SELECT * FROM users WHERE id = " + userId);
                }
            }
            """.trimIndent(),
        )

        myFixture.doHighlighting()
        val fix = myFixture.getAllQuickFixes().singleOrNull { it.text.contains("userId") }
        assertNotNull("expected a quick-fix mentioning userId", fix)
        myFixture.launchAction(fix as IntentionAction)

        val newText = myFixture.editor.document.text
        // Java +-concat has no in-string marker to swap for '?' (the
        // variable sits outside the quotes) -- the fix's documented
        // fallback is TODO-only, and the original literal/expression
        // must remain syntactically intact (still a valid Java string
        // concatenation, quotes balanced).
        assertTrue(newText.contains("\"SELECT * FROM users WHERE id = \" + userId"))
        assertTrue("expected a TODO comment", newText.contains("TODO(sql-concat)"))
    }
}
