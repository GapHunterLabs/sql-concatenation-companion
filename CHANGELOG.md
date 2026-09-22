<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# SQL String Concatenation Companion Changelog

## [Unreleased]

## [0.3.0]

### Fixed

- The check could crash on real files: a stray quote (for example in a
  `'"'` character literal) followed by a long stretch of code overflowed
  the regular-expression stack, and the inspection failed on that file.
- Fewer false warnings, measured on 271 public Java, Kotlin and Python
  files that execute SQL: constants (`"SELECT * FROM " + TABLE_USERS +
  " WHERE id = ?"`, common in Android code), numbers (an `int` parameter,
  `Integer.parseInt(x)`, `%d` placeholders), locals last assigned a
  literal, calls with only literal arguments, a class's
  `getSimpleName()`, and commented-out code are no longer reported. In a
  random sample of 30 remaining warnings, 28 are real queries built from
  a variable.
- A concatenation is now read to its end: in
  `"SELECT * FROM " + TABLE + " WHERE id = " + id` the warning is about
  `id`, which the first constant used to hide. Queries split over several
  lines (`"SELECT ... " +` then `"WHERE x = '" + x`) are found too.
- The warning names the value to bind: `whereCondition` rather than the
  table constant before it, `titles.get(i)` rather than `get`, and
  non-ASCII names in full.
- The quick-fix no longer turns a table-name constant (`$WORDS_TABLE`)
  into a `?` placeholder, which would break the query.
- Only Java, Kotlin and Python files are checked: SQL examples in a
  README or other Markdown file were being reported.
- Kotlin raw strings, Java text blocks and Python triple-quoted strings
  over several lines are read as one string, and Python strings in
  single quotes are checked with `.format()` and `%` too.
- The description now lists the DDL keywords (`CREATE`/`ALTER`/`DROP`/
  `TRUNCATE`) checked since 0.2.0, and says where it overlaps with the
  built-in inspection of IntelliJ IDEA with Database Tools.

### Added

- A description of the inspection in Settings | Editor | Inspections.

## [0.2.0]

### Fixed

- Now recognizes DDL leading keywords (`CREATE`/`ALTER`/`DROP`/
  `TRUNCATE`), not just DML -- the detector's own doc comment already
  claimed "DML/DDL" coverage, but the keyword list only ever had DML
  keywords in it. A dynamically-built `DROP TABLE`/`CREATE TABLE`
  (e.g. a multi-tenant app building a per-tenant table name) is just
  as real an injection shape and was previously missed entirely.

## [0.1.1]

### Added

- Review/star CTA: after 10 distinct real findings, a one-time
  notification asks whether to rate the plugin on Marketplace, with a
  permanent "Don't ask again" option. Standard mechanism used
  catalog-wide since 2026-08-24, rolled out
  to this plugin now.

## [0.1.0]

### Added

- **Inspection that flags SQL queries built by unparameterized string
  concatenation or interpolation**, then executed -- the classic
  SQL-injection shape -- across Java, Kotlin, and Python.
- **Patterns detected**: Java/Kotlin `+` concatenation
  (`"SELECT ..." + userId`), Kotlin string templates
  (`"SELECT ... $userId"` / `"SELECT ... ${user.id}"`), Python f-strings
  (`f"SELECT ... {user_id}"`), `.format()` calls, and `%` string
  formatting -- each only flagged when the resulting string is used near
  a call that looks like it executes SQL (`Statement.executeQuery`/
  `executeUpdate`/`execute`, `cursor.execute`/`executemany`, `.query(...)`,
  `.rawQuery(...)`).
- **Two-layer anti-false-positive heuristic** (same discipline as
  `http-status-inline-companion`'s `HttpSignalNames`): a SQL-keyword-shaped
  string with a non-constant operand is only a *candidate*; it's only
  flagged when a real SQL-execution signal also appears nearby. Two
  constant string literals concatenated, a SQL-keyword string in a log
  message/comment with no execution call, and an already-parameterized
  query (`?` placeholders, DB-API parameter tuples) all produce no
  warning.
- **Quick-fix, honest scope**: replaces the interpolated value with a
  `?` placeholder and inserts a `TODO(sql-concat)` comment naming the
  variable that still needs to be bound as a real parameter. Does not
  infer the value's real type or its positional index across a
  multi-placeholder query -- documented as a real limitation, not
  papered over.
- **Scope, deliberate for v0.1**: single-expression concatenation/
  interpolation only. Indirect concatenation via a `StringBuilder`
  accumulating query parts across multiple lines, and framework-specific
  support (jOOQ, MyBatis, Kotlin Exposed), are deferred to a possible
  future v0.2.
- 100% static text analysis of files already open in the project -- no
  network call, no external process spawned.

[Unreleased]: https://github.com/GapHunterLabs/sql-concatenation-companion/compare/0.3.0...HEAD
[0.3.0]: https://github.com/GapHunterLabs/sql-concatenation-companion/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/GapHunterLabs/sql-concatenation-companion/compare/0.1.1...0.2.0
[0.1.1]: https://github.com/GapHunterLabs/sql-concatenation-companion/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/GapHunterLabs/sql-concatenation-companion/commits/0.1.0
