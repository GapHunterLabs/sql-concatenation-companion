<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# SQL String Concatenation Companion Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/GapHunterLabs/sql-concatenation-companion/compare/0.1.0...HEAD
[0.1.0]: https://github.com/GapHunterLabs/sql-concatenation-companion/commits/0.1.0
