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
    fun `a constant after the keyword does not hide the variable further along the chain`() {
        val text = """stmt.executeQuery("SELECT * FROM " + TABLE_USERS + " WHERE id = " + userId);"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(listOf("userId"), matches.map { it.interpolatedName })
    }

    @Test
    fun `android query built only from constants with a bound parameter does not match`() {
        val text = """
            Cursor c = db.rawQuery("SELECT * FROM " + TABLE_USERS + " WHERE " + UserContract.KEY_ID + " = ?",
                new String[]{ id });
        """.trimIndent()
        assertTrue(SqlConcatenationScanner.scan(text).isEmpty())
    }

    @Test
    fun `an int parameter concatenated into the query does not match`() {
        val text = """
            public boolean studentExists(int studentId) {
                String q = "SELECT COUNT(*) FROM Student WHERE studentId = " + studentId;
                ResultSet rs = statement.executeQuery(q);
        """.trimIndent()
        assertTrue(SqlConcatenationScanner.scan(text).isEmpty())
    }

    @Test
    fun `a String parameter concatenated into the query still matches`() {
        val text = """
            public boolean bookExists(String isbn) {
                String q = "SELECT * FROM Books WHERE isbn = '" + isbn + "'";
                ResultSet rs = statement.executeQuery(q);
        """.trimIndent()
        assertEquals(listOf("isbn"), SqlConcatenationScanner.scan(text).map { it.interpolatedName })
    }

    @Test
    fun `a kotlin Int in a template does not match`() {
        val text = "fun find(id: Int) = db.rawQuery(\"SELECT * FROM users WHERE id = \$id\", null)"
        assertTrue(SqlConcatenationScanner.scan(text).isEmpty())
    }

    @Test
    fun `a kotlin template names the variable, not the table constant before it`() {
        val text = "database.rawQuery(\"SELECT MAX(freq) FROM \$WORDS_TABLE_NAME WHERE \$whereCondition\", null)"
        assertEquals(listOf("whereCondition"), SqlConcatenationScanner.scan(text).map { it.interpolatedName })
    }

    @Test
    fun `a kotlin template with only constants does not match`() {
        val text = "database.rawQuery(\"SELECT id FROM \$SESSIONS_TABLE_NAME WHERE day = ?\", arrayOf(day))"
        assertTrue(SqlConcatenationScanner.scan(text).isEmpty())
    }

    @Test
    fun `python percent format with only numeric placeholders does not match`() {
        val text = """cursor.execute("SELECT * FROM nodes WHERE id = %d AND ratio > %.2f" % (row[0], ratio))"""
        assertTrue(SqlConcatenationScanner.scan(text).isEmpty())
    }

    @Test
    fun `python str conversion names the value inside it`() {
        val text = """
            query = "DELETE FROM Tweet WHERE id='" + str(tweet_id) + "'"
            cursor.execute(query)
        """.trimIndent()
        assertEquals(listOf("tweet_id"), SqlConcatenationScanner.scan(text).map { it.interpolatedName })
    }

    @Test
    fun `a long concatenation chain inside executeUpdate still matches`() {
        val text = "stm.executeUpdate(\"INSERT INTO books (isbn, title, genre, author_id, publisher, year) VALUES ('\" + isbn +" +
            " \"', '\" + title + \"', '\" + genre + \"', '\" + authorId + \"', '\" + publisher + \"', '\" + year + \"');\");"
        assertEquals(listOf("isbn"), SqlConcatenationScanner.scan(text).map { it.interpolatedName })
    }

    @Test
    fun `a non-ASCII identifier is named in full`() {
        val text = """s.execute("UPDATE Inscripcion SET pago = 'T' WHERE (Id = '" + Id_inscripción + "')");"""
        assertEquals(listOf("Id_inscripción"), SqlConcatenationScanner.scan(text).map { it.interpolatedName })
    }

    @Test
    fun `a short call is named in full, not by its method name`() {
        val text = """Cursor c = db.rawQuery("SELECT mlink FROM mtab WHERE mtitle = '" + titles.get(i) + "'", null);"""
        assertEquals(listOf("titles.get(i)"), SqlConcatenationScanner.scan(text).map { it.interpolatedName })
    }

    @Test
    fun `commented-out code does not match`() {
        val text = """
            // rs = stmt.executeQuery("SELECT short_value FROM fma where frame=" + o.frame);
            # cursor.execute("SELECT * FROM users WHERE id = %s" % user_id)
        """.trimIndent()
        assertTrue(SqlConcatenationScanner.scan(text).isEmpty())
    }

    @Test
    fun `a class simple name in a JPQL query does not match`() {
        val text = """Query q = em.createQuery("select p from " + entity.getSimpleName() + " p where p.state = :state");"""
        assertTrue(SqlConcatenationScanner.scan(text).isEmpty())
    }

    @Test
    fun `a function called with only literal arguments does not match`() {
        val text = """stmt.execute("insert into STUDENTS values ('0418','S.Jack',3.5,'" + passHash("jack") + "', 3)");"""
        assertTrue(SqlConcatenationScanner.scan(text).isEmpty())
    }

    @Test
    fun `a local last assigned a literal does not match`() {
        val text = """
            final String first = "arsalan";
            PreparedStatement posted = con.prepareStatement("INSERT INTO people(first) VALUES ('" + first + "')");
            posted.executeUpdate();
        """.trimIndent()
        assertTrue(SqlConcatenationScanner.scan(text).isEmpty())
    }

    @Test
    fun `a local reassigned from input after its literal still matches`() {
        val text = """
            String state = "CA";
            state = reader.nextLine();
            rs = stmt.executeQuery("select jno from roster where state = '" + state + "'");
        """.trimIndent()
        assertEquals(listOf("state"), SqlConcatenationScanner.scan(text).map { it.interpolatedName })
    }

    @Test
    fun `a large generated file with thousands of queries scans within budget`() {
        val method = """
            |    public void find%d(String name, int id) {
            |        String sql = "SELECT * FROM " + TABLE + " WHERE name = '" + name + "' AND id = " + id;
            |        rs = stmt.executeQuery(sql);
            |        cursor.execute(f"SELECT * FROM t WHERE a = {name}")
            |    }
            |""".trimMargin()
        val text = (1..3_000).joinToString("\n") { method.format(it) }
        assertTrue(text.length > 400_000)
        val started = System.nanoTime()
        val matches = SqlConcatenationScanner.scan(text)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(6_000, matches.size)
        assertTrue("took $millis ms", millis < 3_000)
    }

    @Test
    fun `a stray quote followed by a long span does not overflow the stack`() {
        val filler = "x = a + b; // \\\" \n".repeat(20_000)
        val text = "char q = '\"';\n$filler stmt.executeQuery(\"SELECT * FROM users WHERE id = \" + userId);"
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(listOf("userId"), matches.map { it.interpolatedName })
    }

    @Test
    fun `kotlin raw string over several lines with a template matches`() {
        val text = "val rows = db.query(\"\"\"\n    SELECT * FROM users\n    WHERE id = \$userId\n\"\"\")"
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
        assertEquals("userId", matches.single().interpolatedName)
    }

    @Test
    fun `python triple-quoted f-string matches`() {
        val text = "cursor.execute(f\"\"\"\n    SELECT * FROM users\n    WHERE id = {user_id}\n\"\"\")"
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(listOf("user_id"), matches.map { it.interpolatedName })
    }

    @Test
    fun `python single-quoted percent format matches`() {
        val text = """cursor.execute('SELECT * FROM users WHERE id = %s' % user_id)"""
        val matches = SqlConcatenationScanner.scan(text)
        assertEquals(1, matches.size)
        assertEquals(SqlConcatenationKind.PYTHON_PERCENT_FORMAT, matches.single().kind)
    }

    @Test
    fun `rawQuery android convention matches`() {
        val text = """db.rawQuery("SELECT * FROM users WHERE id = " + userId, null);"""
        val matches = SqlConcatenationScanner.scan(text)
        assertTrue(matches.isNotEmpty())
    }
}
