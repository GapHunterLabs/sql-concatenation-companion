# SQL String Concatenation Companion

IntelliJ-family plugin. Flags SQL queries built by **unparameterized
string concatenation or interpolation** — the classic SQL-injection
shape — in Java, Kotlin, and Python files, and offers a quick-fix
toward parameterization. Deliberately narrow (not a full SAST scanner
like Qodana): one specific pattern, no configuration, tuned against false
positives on real code.

## Where it fits

- **IntelliJ IDEA with Database Tools** (Ultimate) already has a built-in
  data-flow inspection for Java and Kotlin, "Non-safe string is used as
  SQL". If you have it, you'll see both warnings on the same line; turn
  off whichever you prefer in Settings | Editor | Inspections.
- **Everywhere else** — IntelliJ IDEA without Database Tools, Android
  Studio (`SQLiteDatabase.rawQuery`), PyCharm — this plugin works on its
  own, with no server and no project build.
- **Python** uses the same check: f-strings, `.format()`, `%` and `+`
  building a query passed to `cursor.execute`.

## Detection heuristic (the actual anti-false-positive design)

**Two conditions, both required — see
[`SqlSignalNames.kt`](src/main/kotlin/dev/gaphunter/sqlconcatenationcompanion/detect/SqlSignalNames.kt)
and
[`SqlConcatenationScanner.kt`](src/main/kotlin/dev/gaphunter/sqlconcatenationcompanion/detect/SqlConcatenationScanner.kt)
for the real code:**

1. **Shape**: a string literal that looks like the start of a real SQL
   statement — DML (`SELECT`/`INSERT`/`UPDATE`/`DELETE`/`MERGE`/`WITH`)
   or DDL (`CREATE`/`ALTER`/`DROP`/`TRUNCATE`) at the start of the text,
   not "the word SELECT appears anywhere" — built with `+`, a Kotlin
   template, a Python f-string, `.format()` or `%`, with at least one
   value that could carry injected SQL. The whole `+` chain is read, so
   `"SELECT * FROM " + TABLE + " WHERE id = " + id` is judged by `id`.
2. **Context**: the result is used within ~200 characters of a call that
   looks like it executes SQL — `executeQuery`/`executeUpdate`/`execute`,
   `executeBatch`, `rawQuery`, `query`, `createQuery`/`createNativeQuery`,
   Python DB-API's `cursor.execute`/`executemany`.

Only `.java`, `.kt`, `.kts` and `.py` files are checked.

### What DOES trigger it

```java
stmt.executeQuery("SELECT * FROM users WHERE name = '" + name + "'");

// DDL is just as real a risk -- a per-tenant table name built this way.
stmt.executeUpdate("DROP TABLE tenant_" + tenantId);
```

```kotlin
db.rawQuery("SELECT MAX(freq) FROM $WORDS_TABLE WHERE $whereCondition", null)
```

```python
cursor.execute(f"SELECT * FROM users WHERE id = {user_id}")
cursor.execute("SELECT * FROM users WHERE name = '{}'".format(name))
cursor.execute("UPDATE users SET name = '%s' WHERE id = %s" % (name, user_id))
```

### What does NOT trigger it

Values that can't carry injected SQL, as far as the file's text shows —
the same values the IDE's data-flow inspection treats as safe:

```java
// Literals and constants (TABLE_USERS, UserContract.KEY_ID, const val).
db.rawQuery("SELECT * FROM " + TABLE_USERS + " WHERE " + KEY_ID + " = ?", new String[]{ id });

// Numbers: a parameter declared int/long/Integer..., Integer.parseInt(x), list.size().
boolean exists(int studentId) { ... "... WHERE id = " + studentId ... }

// A local last assigned a literal, or a call with only literal arguments.
final String first = "arsalan";
stmt.execute("INSERT INTO people VALUES ('" + first + "', '" + passHash("lee") + "')");

// A class's own name in JPQL.
em.createQuery("select p from " + entity.getSimpleName() + " p");
```

```python
# %d / %f placeholders: Python raises before a non-number reaches the query.
cursor.execute("SELECT * FROM nodes WHERE id = %d" % row_id)

# Already parameterized.
cursor.execute("SELECT * FROM users WHERE id = %s", (user_id,))
```

Also not reported: a SQL-shaped string with no execution call nearby (a
log message), and commented-out code.

### Measured on real code

On 271 public Java, Kotlin and Python files that execute SQL (one file
per repository), a random sample of 30 warnings had 28 real SQL built
from a variable. The 2 others were only safe through data flow: a name
checked against an allow-list two lines earlier, and a helper that
returns a fixed SQL fragment.

### Known, documented limitations

- **Plain-text scanning, not a real per-language parser.** Safe values
  are recognized from the text only: a local picked from two literals,
  or a value checked against an allow-list, still gets a warning. A
  number declared in another file isn't seen as a number.
- **`execute` and `query` are also generic names**
  (`ExecutorService.execute(...)`). An unrelated call within ~200
  characters of a SQL-shaped concatenation could produce a warning;
  accepted rather than losing Python DB-API coverage.
- **Single-expression concatenation/interpolation only.** A
  `StringBuilder` accumulating query parts across statements isn't
  followed.

## The quick-fix — honest scope

**What it actually does:** replaces the interpolated value inside the
SQL string with a `?` placeholder, and inserts a `TODO(sql-concat)`
comment naming the value that still needs to be bound as a real
parameter. Constants stay (`$TABLE_USERS` is usually a table or column
name, which can't be a bound parameter):

```kotlin
// Before
db.rawQuery("SELECT MAX(freq) FROM $WORDS_TABLE WHERE $whereCondition", null)

// After applying the quick-fix
db.rawQuery("SELECT MAX(freq) FROM $WORDS_TABLE WHERE ?", null)  // TODO(sql-concat): bind 'whereCondition' as a real PreparedStatement parameter, e.g. .setString(1, whereCondition)
```

**What it deliberately does NOT do:** it does not infer the value's real
type, does not work out its 1-based JDBC parameter index in a query with
several placeholders, and does not rewrite the surrounding code to
declare a `PreparedStatement` or a DB-API parameter tuple. The TODO
names the exact value to bind, so nothing is silently left out.

**Java/Kotlin `+`-concatenation case:** the variable sits *outside* the
string's quotes (`"..." + userId`), so there's no in-string marker to
swap for `?` without restructuring the expression — which could produce
code that no longer compiles. For this shape the fix inserts the TODO
comment only and leaves the expression untouched.

## Scope

Free, all of it — no paywall, nothing held back for a paid tier.

## Why built this way

- **Plain-text/regex detection, not per-language PSI.** No Python or
  Kotlin PSI dependency (neither is present in every IntelliJ-based IDE),
  and the same check runs on Java, Kotlin and Python.
- **`LocalInspectionTool.checkFile`, not `buildVisitor`.** Detection is
  a whole-document scan, not a walk of one language's PSI tree, which is
  what lets one inspection cover three grammars.
- **Leaf PSI anchoring for each `ProblemDescriptor`.** A descriptor
  anchored on a composite PSI node instead of a real leaf token is a
  known platform pitfall; the inspection always walks down to a leaf.
- **Document-level text edit for the quick-fix, not a PSI rewrite.**
  Editing the raw text of just the matched range keeps the change
  minimal and predictable.

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
