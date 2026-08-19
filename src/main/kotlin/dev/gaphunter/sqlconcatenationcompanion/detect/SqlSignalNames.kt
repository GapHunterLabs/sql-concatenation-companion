package dev.gaphunter.sqlconcatenationcompanion.detect

/**
 * The plugin's actual anti-false-positive design, documented here (not
 * just in the README) because it's the part most likely to need tuning
 * later -- same role as
 * `http-status-inline-companion`'s `HttpSignalNames` (see
 * `CONSTITUTION.md`'s brief for this plugin, which names that object
 * explicitly as the discipline to reuse).
 *
 * A candidate string (SQL-keyword-shaped, built via concatenation or
 * interpolation with a non-constant operand -- see
 * [SqlConcatenationScanner]) is only ever flagged when it also sits near
 * one of these "this is really about to run as SQL" signals. Without
 * this second layer, any string starting with a SQL keyword would light
 * up -- including log messages, doc comments, and test fixtures that are
 * never executed. See the README's "Detection heuristic" section for
 * worked examples of both sides.
 */
object SqlSignalNames {

    /**
     * Method/function *simple* names (case-insensitive) that, when the
     * candidate SQL-shaped expression is passed as one of their
     * arguments (or is the receiver being called on), are treated as
     * strong "this string is about to run as SQL" signal. Deliberately
     * name-based, not resolved-symbol-based (same principle as
     * `HttpSignalNames`): this plugin works the same whether the
     * receiver is `java.sql.Statement`, a Spring `JdbcTemplate`, a
     * hand-rolled DAO wrapper, or Python's `sqlite3`/`psycopg2`/`MySQLdb`
     * cursor, because no call is ever resolved to a specific type.
     *
     * Covers standard JDBC (`Statement.executeQuery`/`executeUpdate`/
     * `execute`), common ORM/helper conventions (`.query`, `.rawQuery`
     * -- Android's `SQLiteDatabase`), and Python DB-API 2.0's
     * `cursor.execute`/`executemany` (PEP 249 -- shared by sqlite3,
     * psycopg2, MySQLdb, and most other Python DB drivers).
     */
    private val SIGNAL_METHOD_NAMES = setOf(
        "executequery",
        "executeupdate",
        "execute",
        "executemany",
        "executebatch",
        "executelargeupdate",
        "rawquery",
        "query",
        "createquery",
        "createnativequery",
        "createsqlquery",
    )

    /**
     * Substrings (case-insensitive) in a receiver/variable name that make
     * a nearby `.execute(...)`-shaped call look like it's really a SQL
     * statement/cursor/connection object, not an unrelated `execute`
     * (e.g. `Runnable.execute()`, `ExecutorService.execute()`,
     * `Process.execute()`). Used as a secondary, softer signal -- see
     * [SqlConcatenationScanner] for exactly how the two combine.
     */
    private val SIGNAL_RECEIVER_SUBSTRINGS = listOf(
        "statement",
        "stmt",
        "cursor",
        "connection",
        "conn",
        "jdbctemplate",
        "session",
        "db",
        "database",
        "sql",
    )

    fun isSignalMethodName(simpleName: String): Boolean =
        simpleName.lowercase() in SIGNAL_METHOD_NAMES

    fun looksLikeSqlReceiverName(identifier: String): Boolean {
        val lower = identifier.lowercase()
        return SIGNAL_RECEIVER_SUBSTRINGS.any { lower.contains(it) }
    }
}
