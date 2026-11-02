# Ketem

Source-level identifier obfuscation for Maven builds (`ch.qos.ketem`), plus a
service that turns obfuscated stack traces back into readable ones.

An identifier `id` becomes `_` + the first *n* hex digits (default 12) of
`MD5(salt + ":" + id)`. Since the mapping is deterministic, the salt plus the
set of original identifiers is enough to reverse it.

## What gets renamed

By default, only identifiers whose scope is a single source file:
local variables, parameters, pattern variables, private fields and private
methods. Public/protected API, class and package names are kept, so files are
processed independently and dependent code keeps compiling. Optionally,
package-private top-level classes can be renamed too (see below). Formatting is
kept as-is, so **line numbers in stack traces match the original sources**.

Skipped on purpose:

* serialization names (`serialVersionUID`, `readObject`, ...),
* record components and canonical record constructor parameters,
* members carrying annotations other than `@Override`, `@SuppressWarnings`,
  `@Deprecated`, `@SafeVarargs` (frameworks often access those reflectively),
* private names also used through an expression Ketem cannot attribute to a
  type in the same file (e.g. `other.count`), and private methods that share a
  name with a non-private method in the same file.

Known limitation: resolution is syntactic (no classpath). If a nested or
anonymous class *inherits* a field or method from an external supertype that
has the same name as a private member of the enclosing class, renaming can
change which one is used. Add such names to `excludedIdentifiers`, and the
same goes for names used reflectively (e.g. JUnit `@MethodSource("name")`).

### Removing comments (opt-in)

Javadoc and comments can reveal what renaming hides: original parameter names
(`@param divisor`), descriptions of private methods, `{@link Calculator}`.
`stripJavadocsAndComments` removes all of them: Javadoc (`/** ... */`, `///`),
`/* ... */` and `//` comments.

The comments' line breaks stay in place, so **line numbers do not change**.
Comments before the `package` declaration, typically a license header, are
always kept. In files without a package declaration, comments before the
first import or type are kept.

`stripScope` decides which files lose their comments:

| `stripScope` | Comments removed from |
|---|---|
| `obfuscated` (default) | obfuscated files only |
| `packages` | every file matching `packages`/`classes`, including files in `excludes`, which keep their identifiers |

Files outside `packages`/`classes` are never touched. If neither `packages`
nor `classes` is set, every file matches, so `packages` then applies to all
excluded files.

A `@deprecated` tag inside a removed Javadoc goes with it (the `@Deprecated`
annotation, being code, stays). Note that `maven-javadoc-plugin` would read
the processed sources too, so generate published API docs from the original
sources.

### Package-private top-level classes (opt-in)

With `renamePackagePrivateClasses` enabled, non-public top-level classes,
interfaces, enums and records are renamed as well, and so is their source
file: `Calculator.java` becomes `_4f455bcf8c2e.java`, and stack traces retrace
to `Calculator.divide(Calculator.java:11)`. Such a class is visible to every
file in its package, so Ketem first examines the whole package and keeps a
class name when:

* it is listed in `classNameExcludes` or `excludedIdentifiers`,
* a test source or an excluded source file in the same package mentions it
  (those files are not rewritten, so the old name must stay valid),
* the same name is also used in the package by a variable, parameter, enum
  constant, nested or local type, type parameter, or an import,
* it declares a `main` method, directly implements `Serializable` or
  `Externalizable`, or carries a framework-style annotation.

Each kept class is logged with the reason. The class's contents are still
obfuscated either way.

Class names are visible at runtime in ways Ketem cannot see. Put a class in
`classNameExcludes` when its name matters through:

* `Class.forName("...")`, `getClass().getName()`/`getSimpleName()`, or a
  record's `toString()` output,
* logger names, e.g. `LoggerFactory.getLogger(Calculator.class)`, so
  class-level logging configuration no longer matches (package-level
  configuration still does),
* resources and configuration files: `META-INF/services`, Spring XML,
  `logback.xml` and the like,
* previously serialized data of classes that are serializable through a
  superclass, e.g. exceptions,
* the same package split across modules.

Also note that test sources created after `process-sources` (e.g. generated
in `generate-test-sources`) are not examined.

## Maven usage

Requires Maven 3.9.10 or later (including Maven 4).

```xml
<plugin>
  <groupId>ch.qos.ketem</groupId>
  <artifactId>ketem-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution><goals><goal>obfuscate</goal></goals></execution>
  </executions>
  <configuration>
    <packages><package>com.acme</package></packages>          <!-- includes sub-packages -->
    <classes><class>org.other.Special</class></classes>
    <excludes><exclude>com.acme.api</exclude></excludes>      <!-- package or class -->
    <excludedIdentifiers><excludedIdentifier>handler</excludedIdentifier></excludedIdentifiers>
    <stripJavadocsAndComments>true</stripJavadocsAndComments>  <!-- opt-in -->
    <stripScope>packages</stripScope>                        <!-- or obfuscated -->
    <renamePackagePrivateClasses>true</renamePackagePrivateClasses>  <!-- opt-in -->
    <classNameExcludes>                                       <!-- class or package -->
      <classNameExclude>com.acme.plugins.LoadedByName</classNameExclude>
    </classNameExcludes>
  </configuration>
</plugin>
```

With neither `packages` nor `classes`, every file is selected. Pass the salt
with `-Dketem.salt=...` (or a `settings.xml` profile) instead of committing it.

`obfuscate` runs in `process-sources`. It writes all compile source roots to
`target/ketem/src` (the selected files obfuscated, everything else copied as-is) and
compiles from there. It also writes `private/ketem-identifiers-<version>.txt` in
the module's directory, listing the renamed original identifiers (but not the
salt). It lives outside `target/` so `mvn clean` keeps it. To write it
elsewhere, set `dictionaryFile` (or `-Dketem.dictionaryFile=...`).

In Eclipse, the plugin ships `META-INF/m2e/lifecycle-mapping-metadata.xml`,
which tells m2e to skip `obfuscate`, so the IDE builds and debugs against the
original sources without any extra configuration.

### Keep `private/` out of version control and releases

The dictionary lists every original private identifier, which reveals much of
what the obfuscated code does. Store it somewhere private (a secret store, or a
private CI artifact keyed by version), never in a public repository or alongside
deployed artifacts.

Ketem does not enforce this: it never edits ignore files. Add the folder to
your `.gitignore` yourself. For a single module, put this in the module's
`.gitignore`:

```gitignore
private/
```

In a multi-module build, one entry in the root `.gitignore` covers every module,
since a pattern without a leading slash matches at any depth:

```gitignore
# Ketem identifier dictionaries (see ketem README)
private/
```

If you changed `dictionaryFile`, ignore that location instead. To check that
the rule works, run `git check-ignore -v private/ketem-identifiers-1.0.txt`.

| Parameter | Property | Default |
|---|---|---|
| `salt` | `ketem.salt` | required |
| `hashLength` | `ketem.hashLength` | 12 (6–32) |
| `languageLevel` | `ketem.languageLevel` | from `maven.compiler.release`, else `maven.compiler.source`, else `JAVA_21` |
| `dictionaryFile` | `ketem.dictionaryFile` | `${project.basedir}/private/ketem-identifiers-${project.version}.txt` |
| `outputDirectory` | | `target/ketem/src` |
| `stripJavadocsAndComments` | `ketem.stripJavadocsAndComments` | false |
| `stripScope` | `ketem.stripScope` | `obfuscated` (or `packages`) |
| `renamePackagePrivateClasses` | `ketem.renamePackagePrivateClasses` | false |
| `classNameExcludes` | | none |
| `skip` | `ketem.skip` | false |

## Retracing stack traces

```sh
# CLI (salt from --salt or $KETEM_SALT; trace from file or stdin)
java -jar ketem-core-0.1.0-SNAPSHOT-retrace.jar --salt S --identifiers private/ketem-identifiers-1.0.txt crash.txt

# No dictionary? Harvest identifiers from the original sources instead
java -jar ketem-core-0.1.0-SNAPSHOT-retrace.jar --salt S --sources src/main/java < crash.txt

# HTTP service: POST /retrace with the trace as body; GET / serves a paste form
java -jar ketem-core-0.1.0-SNAPSHOT-retrace.jar --salt S --identifiers private/ketem-identifiers-1.0.txt --serve 8080
curl --data-binary @crash.txt http://127.0.0.1:8080/retrace

# Maven
mvn ketem:retrace -Dketem.salt=S -Dketem.trace=crash.txt [-Dketem.output=out.txt]
```

Synthetic names such as `lambda$_d27761fea300$0` are retraced too.
The service binds to `127.0.0.1` by default; use `--bind` to change it.
