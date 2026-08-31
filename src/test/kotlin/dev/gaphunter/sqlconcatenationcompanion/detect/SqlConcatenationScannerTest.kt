package dev.gaphunter.sqlconcatenationcompanion.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlConcatenationScannerTest {

    @Test
    fun `java plus concat with variable and nearby executeQuery signal matches`() {
        val text = """stmt.executeQuery("SELECT * FROM users WHERE id = " + userId);"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
        assertEquals(SqlConcatenationKind.JAVA_KOTLIN_PLUS_CONCAT, matches.single().kind)
        assertEquals("userId", matches.single().interpolatedName)
    }

    @Test
    fun `java plus concat assigned to a variable then executed nearby still matches`() {
        val text = """
            String sql = "SELECT * FROM users WHERE id = " + userId;
            ResultSet rs = stmt.executeQuery(sql);
        """.trimIndent()
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
    }

    @Test
    fun `java plus concat building a dynamic DROP TABLE statement matches`() {
        val text = """stmt.executeUpdate("DROP TABLE tenant_" + tenantId);"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
        assertEquals("tenantId", matches.single().interpolatedName)
    }

    @Test
    fun `java plus concat building a dynamic CREATE TABLE statement matches`() {
        val text = """stmt.execute("CREATE TABLE schema_" + schemaName + " (id INT)");"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
    }

    @Test
    fun `two constant string literals concatenated does not match`() {
        val text = """stmt.executeQuery("SELECT * FROM " + "users");"""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `sql keyword string in a log message with no execution call does not match`() {
        val text = """logger.info("SELECT * FROM users WHERE id = " + userId);"""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `sql keyword string in a comment does not match`() {
        val text = """// example: "SELECT * FROM users WHERE id = " + userId"""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `already parameterized prepared statement with question mark does not match`() {
        val text = """
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
            ps.setInt(1, userId);
            ps.executeQuery();
        """.trimIndent()
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `kotlin string template interpolating a variable in an executed query matches`() {
        val text = """connection.executeQuery("SELECT * FROM users WHERE id = ${'$'}userId")"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
        assertEquals(SqlConcatenationKind.KOTLIN_STRING_TEMPLATE, matches.single().kind)
        assertEquals("userId", matches.single().interpolatedName)
    }

    @Test
    fun `kotlin string template with braces interpolation matches`() {
        val text = """db.query("SELECT * FROM users WHERE id = ${'$'}{user.id}")"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
        assertEquals("user.id", matches.single().interpolatedName)
    }

    @Test
    fun `kotlin string template with no interpolation at all does not match`() {
        val text = """db.query("SELECT * FROM users WHERE active = true")"""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `python f-string in cursor execute matches`() {
        val text = """cursor.execute(f"SELECT * FROM users WHERE id = {user_id}")"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
        assertEquals(SqlConcatenationKind.PYTHON_FSTRING, matches.single().kind)
        assertEquals("user_id", matches.single().interpolatedName)
    }

    @Test
    fun `python f-string with no signal call nearby does not match`() {
        val text = """query_description = f"SELECT * FROM users WHERE id = {user_id}""""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `python format call in cursor execute matches`() {
        val text = """cursor.execute("SELECT * FROM users WHERE id = {}".format(user_id))"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
        assertEquals(SqlConcatenationKind.PYTHON_FORMAT_CALL, matches.single().kind)
    }

    @Test
    fun `python percent format in cursor execute matches`() {
        val text = """cursor.execute("SELECT * FROM users WHERE id = %s" % user_id)"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
        assertEquals(SqlConcatenationKind.PYTHON_PERCENT_FORMAT, matches.single().kind)
    }

    @Test
    fun `python parameterized cursor execute with tuple does not match`() {
        val text = """cursor.execute("SELECT * FROM users WHERE id = %s", (user_id,))"""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `python parameterized cursor execute with question mark placeholder does not match`() {
        val text = """cursor.execute("SELECT * FROM users WHERE id = ?", (user_id,))"""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `insert delete and update keywords are also detected`() {
        val insert = SqlConcatenationScanner.scan(
            """stmt.executeUpdate("INSERT INTO logs VALUES (" + entry + ")");"""
        )
        val delete = SqlConcatenationScanner.scan(
            """stmt.executeUpdate("DELETE FROM users WHERE id = " + userId);"""
        )
        val update = SqlConcatenationScanner.scan(
            """stmt.executeUpdate("UPDATE users SET name = " + name);"""
        )
        assertTrue(insert.isNotEmpty())
        assertTrue(delete.isNotEmpty())
        assertTrue(update.isNotEmpty())
    }

    @Test
    fun `unrelated executor execute call is not treated as a sql signal by name alone`() {
        // "execute" is in the signal set (Python DB-API convention), so
        // this documents a known, accepted trade-off: a Runnable/Executor
        // .execute(...) sitting textually near an unrelated SQL-shaped
        // string built elsewhere in the same short window could produce a
        // false positive. In practice this requires both an unrelated
        // execute() call AND a real SQL-keyword string with a variable
        // operand within 200 chars -- rare enough in real code to accept
        // as a documented limitation (see README) rather than drop
        // "execute" from the signal set and lose real Python DB-API
        // coverage.
        val text = """executorService.execute(runnable);"""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `rawQuery android convention matches`() {
        val text = """db.rawQuery("SELECT * FROM users WHERE id = " + userId, null);"""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isNotEmpty())
    }
}
