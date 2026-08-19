# SQL String Concatenation Companion

IntelliJ-family plugin. Flags SQL queries built by **unparameterized
string concatenation or interpolation** — the classic SQL-injection
shape — across Java, Kotlin, and Python, and offers a real quick-fix
toward parameterization. Deliberately narrow (not a full SAST scanner
like Qodana): one specific pattern, near-zero configuration, tuned hard
against false positives.

## Why it exists

An original idea, not a port of an existing competitor — validated
against `CONSTITUTION.md` §1's "Plan B permanente" discipline before
being built: (1) confirmed no plugin in this catalog does exactly this,
and Qodana (968K downloads) is a full general-purpose SAST scanner —
much heavier and broader than a single, zero-config pattern check; (2)
confirmed buildable in the ~10-day budget with techniques this catalog
already has proven — plain-text/regex scanning of source (same
principle as `env-var-missing-companion`'s `EnvVarReferenceScanner` and
`dockerfile-layer-size-companion`'s Dockerfile parser) and a
`LocalInspectionTool` + `LocalQuickFix`, the exact mechanism
`env-var-missing-companion` already proved in this catalog (reused
directly, not reinvented). Same "apuesta consciente sin ancla de
mercado" treatment as Refactor Simulator / Test Scaffold Companion /
Env Var Missing Companion: v0.1 ships free, no time/marketing
investment disproportionate to real demand signal until there's
evidence of adoption.

## Detection heuristic (the actual anti-false-positive design)

**Two conditions, both required — see
[`SqlSignalNames.kt`](src/main/kotlin/dev/gaphunter/sqlconcatenationcompanion/detect/SqlSignalNames.kt)
and
[`SqlConcatenationScanner.kt`](src/main/kotlin/dev/gaphunter/sqlconcatenationcompanion/detect/SqlConcatenationScanner.kt)
for the real code, same discipline already proven by
`http-status-inline-companion`'s `HttpSignalNames`:**

1. **Shape**: a string literal that looks like the start of a real SQL
   statement (`SELECT`/`INSERT`/`UPDATE`/`DELETE`/`MERGE`/`WITH`,
   matched at the start of the text — not "the word SELECT appears
   anywhere"), combined via `+` concatenation or string-template/
   f-string interpolation with an operand that is **not itself a
   constant string literal**.
2. **Context**: the resulting string is used within ~200 characters of
   a call that looks like it executes SQL — `Statement.executeQuery`/
   `executeUpdate`/`execute`, Python DB-API's `cursor.execute`/
   `executemany` (shared by `sqlite3`, `psycopg2`, `MySQLdb`, and most
   other Python drivers), and common ORM/helper conventions
   (`.query(...)`, `.rawQuery(...)` — Android's `SQLiteDatabase`).

### What DOES trigger it

```java
// Java: + concatenation with a variable, executed
stmt.executeQuery("SELECT * FROM users WHERE id = " + userId);
```

```kotlin
// Kotlin: string template interpolating a variable, executed
connection.executeQuery("SELECT * FROM users WHERE id = $userId")
db.query("SELECT * FROM users WHERE id = ${user.id}")
```

```python
# Python: f-string, executed via cursor.execute
cursor.execute(f"SELECT * FROM users WHERE id = {user_id}")

# Python: .format() / % formatting, executed
cursor.execute("SELECT * FROM users WHERE id = {}".format(user_id))
cursor.execute("SELECT * FROM users WHERE id = %s" % user_id)
```

### What does NOT trigger it

```java
// Two constant string literals concatenated -- just code formatting,
// no variable involved, no injection risk.
stmt.executeQuery("SELECT * FROM " + "users");
```

```java
// SQL-keyword-shaped string with no nearby execution call -- a log
// message. Never passed to anything that runs SQL.
logger.info("SELECT * FROM users WHERE id = " + userId);
```

```python
# Already parameterized correctly -- a '?'/'%s' placeholder plus a
# real DB-API parameter tuple, or PreparedStatement.setX(...) in
# Java/Kotlin. No concatenation/interpolation building the query text
# itself, so the shape condition never matches.
cursor.execute("SELECT * FROM users WHERE id = %s", (user_id,))
```

```java
PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
ps.setInt(1, userId);
ps.executeQuery();
```

### Known, documented limitations

- **Plain-text scanning, not a real per-language lexer** (same class of
  limitation as `env-var-missing-companion`'s scanner): a string
  literal or comment whose *text* happens to contain the working syntax
  verbatim (e.g. a code example quoted inside a block comment) is
  indistinguishable from real code and could still be flagged if it
  also sits textually near a signal call. In practice this is rare.
- **`execute` is a real Python DB-API convention but also a generic
  name** (`Runnable`/`ExecutorService.execute(...)`). An unrelated
  `.execute(...)` call sitting within ~200 characters of an unrelated
  SQL-shaped concatenation elsewhere in the same file could in theory
  produce a false positive. Accepted as a documented trade-off rather
  than dropping "execute" from the signal set and losing real Python
  DB-API coverage — see the test named exactly for this case in
  `SqlConcatenationScannerTest`.
- **Single-expression concatenation/interpolation only.** Indirect
  concatenation via a `StringBuilder` accumulating query parts across
  multiple lines is deliberately out of v0.1 scope (see below).

## The quick-fix — honest scope

**What it actually does:** replaces the interpolated value inside the
SQL string with a `?` placeholder, and inserts a `TODO(sql-concat)`
comment naming the variable that still needs to be bound as a real
parameter:

```kotlin
// Before
connection.executeQuery("SELECT * FROM users WHERE id = $userId")

// After applying the quick-fix
connection.executeQuery("SELECT * FROM users WHERE id = ?")  // TODO(sql-concat): bind 'userId' as a real PreparedStatement parameter, e.g. .setString(1, userId)
```

**What it deliberately does NOT do** — stated honestly rather than
oversold: it does not infer the interpolated value's real type, does
not figure out its correct 1-based JDBC parameter index across a query
with more than one placeholder, and does not rewrite surrounding code
to declare/use a real `PreparedStatement` object or Python DB-API
parameter tuple. Building correct type inference and positional-index
tracking across Java/Kotlin/Python is real, non-trivial work explicitly
out of this plugin's ~10-day v0.1 budget. The TODO comment always names
the exact variable that needs real binding, so nothing is silently left
out — the developer finishes the wiring, the plugin removes the
injection-shaped text and points at exactly what's left to do.

**Java/Kotlin `+`-concatenation case:** the interpolated variable sits
*outside* the string's quotes entirely (`"..." + userId`), so there's
no in-string marker to swap for `?` without also restructuring the
surrounding expression — which this fix deliberately avoids (a
structural rewrite risks producing code that no longer type-checks).
For this shape, the fix's fallback is to insert the TODO comment only,
leaving the original concatenation expression untouched and
syntactically valid.

## v0.1 scope

Free, all of it — no paywall, nothing held back for a future tier, and
**no market anchor** (no confirmed paying competitor with real
complaints in this exact niche — see `CONSTITUTION.md` §1 "Plan B
permanente"). Treated with the same discipline as every other
originally-generated idea in this catalog: no disproportionate time or
marketing investment before real adoption signal.

Deferred to a possible future v0.2 Pro tier (not started, not
promised):
- Support for more frameworks — jOOQ, MyBatis, Kotlin Exposed.
- Detection of more subtle patterns — indirect concatenation via a
  `StringBuilder`/`StringBuffer` accumulating query parts across
  multiple lines instead of a single expression.

## Why built this way

- **Plain-text/regex detection, not per-language PSI.** Reuses the
  exact "hand-rolled over plain text" principle already proven by
  `env-var-missing-companion`: no Python/JavaScript PSI dependency
  needed (neither is guaranteed present in every IntelliJ Platform
  edition this catalog targets), and the inspection runs against
  Java/Kotlin/Python alike without a per-ecosystem PSI grammar.
- **`LocalInspectionTool.checkFile`, not `buildVisitor`.** Detection is
  a whole-document regex scan, not a PSI-node-by-PSI-node walk of one
  specific language grammar — the right fit for a check that spans
  three different languages with three different grammars, and what
  lets the inspection apply without a `language` filter in `plugin.xml`.
- **Leaf PSI anchoring for each `ProblemDescriptor`.** A
  `ProblemDescriptor` anchored on a composite PSI node instead of a
  real leaf token is a documented platform gotcha (`SDK_GOTCHAS.md`
  §20) — this inspection always walks down to a true leaf element
  before creating a descriptor.
- **Document-level text edit for the quick-fix, not a PSI rewrite.**
  Consistent with the whole plugin being plain-text detection, and
  safer: a naive PSI replacement risks producing an expression that no
  longer type-checks. Editing the raw document text for just the
  matched range keeps the change minimal and mechanically predictable.

## Enterprise / Team Licensing

Need enterprise features, custom rules, or team licensing? Contact us
at **gaphunterlabs@gmail.com**.

## Development

```
./gradlew test           # unit tests
./gradlew buildPlugin    # generates build/distributions/*.zip
./gradlew verifyPlugin   # checks compatibility against real IDEs
```

## License

Apache-2.0. See `LICENSE`.
