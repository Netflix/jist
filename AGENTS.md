# Jist development guide

Jist is a symbol search and semantic relationship tool for Java compilation contexts.

## User-facing model

- No prefix enumerates all symbols. A prefix enumerates symbols beneath that exact,
  delimiter-bounded qualified name.
- `-k`/`--kind` filters enumeration by `module`, `package`, `type`, `class`, `interface`, `enum`,
  `record`, `annotation`, `method`, or `field`. `type` is the union of the five concrete type kinds.
  Repeating
  the option forms a union.
- `--usages SYMBOL` finds semantic references to selected types or members in compiled inputs and
  project source. Exact simple names and delimiter-bounded suffixes resolve to all matching declarations.
- `--callers` and `--callees` accept executable or type targets by qualified name, exact simple
  name, or delimiter-bounded suffix. A type selects its declared methods and constructors, excluding
  inherited executables. Multiple matches form a union. Default output preserves each `caller ->
  callee` edge; `-l` intentionally lists only the matching executable symbols.
- `--exclude-path` accepts a platform-path-separator-delimited list of input roots to omit from searches. Excluded inputs remain available for resolution and source attachment. Hierarchy searches resolve excluded ancestors on demand rather than bulk-scanning those roots.
- One qualified prefix, symbol, source file, or ClassFile locator is accepted per invocation.
- A target without a dot matches every symbol whose terminal simple name is exactly that target.
  A declaration target containing a dot is an exact, delimiter-bounded qualified prefix. Usage and
  relationship targets also accept delimiter-bounded type or member suffixes; referenced-type queries
  accept type suffixes. Source descriptions resolve suffixes when no qualified prefix matches. Search does not perform
  completion, fuzzy scoring, ranking, or typo correction. `-e`/`--regexp` explicitly searches declaration
  simple names using Java regular expressions, with unanchored matching and a union of repeated
  expressions. A positional name combined with `-e` constrains the owner or namespace. Match the
  declaration's own simple name (`new` for constructors), never its owner or rendered text.
  `-i`/`--ignore-case` changes declaration-name comparison only; preserve every matching identity and
  its original spelling. Neither option applies to relationship targets. Selection must be identical
  across source depths and output formats. An expression with no matches succeeds with empty output.
- Default output uses literal source signatures when source is available and compiled signatures as
  its fallback. A Java file on a directory source path supersedes matching ClassFiles so uncompiled
  project changes are visible. Archive source enriches the matching ClassFile instead. Both are
  anchored by the owning compilation unit.
- `-s`/`--source none|body|signature|doc|definition|symbol|type|unit` selects source detail. `none`
  prints symbols without source text, `body` prints attached-source lines named by selected methods' ClassFile
  line-number tables, `signature` is the default literal source header, `doc` spans from associated
  Javadoc through the end of the signature (including intervening source lines), `definition` is
  associated Javadoc plus syntax-tree extent, `symbol` selects a complete compilation unit for a
  top-level type, a definition for a nested type or executable, and a signature for other symbols,
  `type` is the outermost enclosing top-level type plus its Javadoc, and `unit` is the complete
  attached source file, including non-Java source. Source scopes apply to
  exact symbols, qualified package
  prefixes, and no-prefix enumeration.
- Module symbols are resolved module descriptors. Package symbols are observable packages;
  `package-info.java` supplies literal documentation and annotations when present, otherwise render
  a synthesized `package NAME;` declaration. Do not synthesize parent packages.
- Jist never associates ordinary comments with declarations. They appear only when lexically
  contained by the selected definition, symbol, type, or unit range.
- If source output is unavailable, warn once per compilation unit on standard error and fall back to
  deterministic ClassFile presentation without claiming source line numbers. When a record's source
  is available, implicitly declared accessors, canonical constructors, and object methods use that
  fallback without warning if no explicit declaration matches. `--source none` and symbol-only
  listings do not warn.
- Command-line preparation activates project compilation options from the nearest applicable
  `.java-tool-options/jist.args` file. No other project metadata discovery is performed.
- `--module-path` defines observable application modules but does not root all of them. `--module`
  selects one or more comma-separated compilation modules; `--add-modules` adds roots. Resolve
  `requires` edges before searching. A symbol is visible when it is visible from at least one
  selected compilation module, including that module's own packages, qualified exports to that
  module, and its `--add-reads` and `--add-exports` edges. Class-path entries belong to the unnamed
  module and are visible when at least one selected named module reads them through
  `--add-reads M=ALL-UNNAMED`.

Usage output uses the same source-line format:

```text
UNIT(SourceFile.java:LINE): SOURCE_LINE
```

With no source text, the right side is the enclosing symbol and available debug coordinates remain
on the left. `--source definition|type|unit` widens usage matches; signature and doc source are not
valid for a usage. Zero usages is a successful empty result. Method usage includes overriding declarations and
references resolved to overrides. Usage lookup scans ClassFiles first, then uses lexical filtering
and javac attribution to supplement project source. When highlighting is active, confirmed attached
archive source is attributed in memory as each confirmed source unit is discovered. Present module
sources to javac as patches so dependencies resolve from ClassFiles rather than recursively parsing
the archive; do not extract source.
It does not persist a reverse-reference index.

## Input pipelines

ClassFiles use `java.lang.classfile.ClassModel`. Source structure uses javac parsing, `Trees`, and
compiler source positions without type attribution. Exact source-only usage matching uses attribution
only after a lexical candidate check and stops javac after `ATTR`. Do not introduce a separate Java
language model or compile source into temporary ClassFiles for rendering.

```text
ClassFile -> ClassModel -> symbol records -> optional source context
.java     -> JavacTask parse/analyze -> symbol records and source spans
```

A Java file on a directory source path is authoritative over matching ClassFiles. Otherwise,
ClassFiles are authoritative for compiled signatures and attached archive source provides exact
definition, top-level type, and compilation-unit ranges. Source-only declarations participate
directly in search and exact usage lookup.

JDK classes resolve from the selected system image. `<java.home>/lib/src.zip` is read on demand when available. `--release` applies javac's Java SE API signatures during source attribution; it does not change the searched ClassFiles or imply matching historical JDK implementation source. Archive source stays in memory. Runtime ClassFile locations use `jrt:/` URIs.

## Output invariants

- Search output is left-anchored by the owning compilation unit, not the selected member symbol.
- ClassFile presentation rows use `UNIT: SIGNATURE`; source rows use `UNIT(SourceFile.java:LINE): SOURCE_LINE`. Compact provenance is included by default: named modules qualify units and listed symbols with their resolved `module@version/` identity, and archive names identify containing binaries. Class-path entries remain unnamed. Attached sources retain the defining ClassFile origin independently of their source location. Module versions, including system versions, come from the selected compilation context's resolved descriptors.
- Redirected output keeps one complete result per line when a query can span compilation units. A
  default explicit source-file locator omits its redundant unit/file context and emits
  `LINE: SOURCE`; explicit `--heading`, `--no-heading`, or `--show-origin` retains context.
  Terminal output groups consecutive results under `UNIT` or `UNIT(SourceFile.java)` headings and
  colors headings and line numbers. Highlight
  only javac-attributed source token ranges in usage results; never infer highlights by matching text.
  ClassFile-only usages without attributed ranges and declaration output are not highlighted.
  `--format json` emits a streaming versioned envelope whose results contain complete output rows
  and structured origins without headings, color, or blank breaks. Listings expose undecorated `symbol` and `kind`; presentation rows expose the owning `unit`. `origin.classLocation` preserves the defining ClassFile separately from attached-source locations. Consumers must not parse rendered text to recover identities. The envelope ends with `exitCode`
  and `diagnostics`; failed searches retain partial rows. Diagnostic kind `not_found` distinguishes
  absent targets from invalid queries and search failures (`error`), independently of the exit code.
  `--show-origin` expands compact archive and source locations to their full paths or URIs while retaining module qualification. `--no-line-number` removes source
  coordinates and `--break` inserts one blank line between
  non-consecutive source matches. Output formatting must remain streaming and must not collect
  results by file.
- Source definitions and types are literal syntax-tree ranges sliced from checked javac source
  positions. Unit scope emits each source line exactly once.
- Synthetic methods, bridge methods, static initializers, anonymous classes, and local classes are
  excluded from discovery. Compiler-generated Kotlin metadata annotations are omitted from
  Java-shaped declarations; preserve ordinary declaration annotations and their values.
- Search forward-scans the readable unnamed module, reachable application modules, JDK modules, and
  source paths and emits immediately. Do not build a global symbol collection.

## Project option generation

`gradle/jist.init.gradle` writes mirrored source-set scopes:

```text
<project>/.java-tool-options/<project-relative-source-set>/jist.args
```

Each tool-specific argument file contains `--class-path`, `--source-path`, module options, and either `--release` or `--system` as applicable. Named source sets also include their `--module` and compiled output on the module path. Project directories do not imply the `main` source set. Compilation tasks finalize `writeJavaToolOptions`; no stubs, indexes, caches, or eager source trees are generated.

## Build and tests

Jist requires JDK 25 or newer.

```sh
ja test
./build.sh
./install.sh --force
git diff --check
```

Defect fixes should begin with a failing regression test. Prefer focused tests during development and
run the full suite before finishing.
