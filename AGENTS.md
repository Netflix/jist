# Jist development guide

Jist is a symbol search and semantic relationship tool for Java compilation contexts.

## User-facing model

- No prefix enumerates all symbols. A prefix enumerates symbols beneath that exact,
  delimiter-bounded qualified name.
- `-k`/`--kind` filters enumeration by `module`, `package`, `type`, `class`, `interface`, `enum`,
  `record`, `annotation`, `method`, or `field`. `type` is the union of the five concrete type kinds.
  Repeating
  the option forms a union.
- `--usages SYMBOL` finds semantic references to an exact type or member in compiled inputs and
  project source.
- One qualified prefix, exact usage symbol, source file, or ClassFile locator is accepted per
  invocation.
- A target without a dot matches every symbol whose terminal simple name is exactly that target.
  A target containing a dot is an exact, delimiter-bounded qualified prefix. Search does not perform
  completion, substring matching, fuzzy scoring, ranking, typo correction, or implicit resolution of
  partially qualified names.
- Default output uses literal source signatures when source is available and compiled signatures as
  its fallback. Both are anchored by the owning compilation unit.
- `-s`/`--source none|body|signature|doc|definition|symbol|type|unit` selects source detail. `none`
  does not read source, `body` prints attached-source lines named by selected methods' ClassFile
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
  deterministic ClassFile presentation without claiming source line numbers. `--source none` and
  symbol-only listings do not warn.
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

ClassFiles are authoritative for compiled signatures. Source provides exact definition, top-level
type, and compilation-unit ranges. Source-only declarations participate
directly in search and exact usage lookup.

JDK classes resolve from the runtime image. `<java.home>/lib/src.zip` is read on demand when
available. Archive source stays in memory. Runtime ClassFile locations use `jrt:/` URIs.

## Output invariants

- Search output is left-anchored by the owning compilation unit, not the selected member symbol.
- ClassFile presentation rows use `UNIT: SIGNATURE`; source rows use
  `UNIT(SourceFile.java:LINE): SOURCE_LINE`.
- Redirected output keeps one complete result per line when a query can span compilation units. A
  default explicit source-file locator omits its redundant unit/file context and emits
  `LINE: SOURCE`; explicit `--heading`, `--no-heading`, or `--qualified-path` retains context.
  Terminal output groups consecutive results under `UNIT` or `UNIT(SourceFile.java)` headings and
  colors headings and line numbers. Highlight
  only javac-attributed source token ranges in usage results; never infer highlights by matching text.
  ClassFile-only usages without attributed ranges and declaration output are not highlighted.
  `--qualified-path` replaces the short source filename with its project path or archive URI and
  qualifies compiled fallback rows with their ClassFile origin. `--no-line-number` removes source
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

Each tool-specific argument file contains `--class-path`, `--source-path`, module options, and
`--system` as applicable. Named source sets also include their `--module` and compiled output on the
module path. Project directories do not imply the `main` source set. Compilation tasks finalize
`writeJavaToolOptions`; no stubs, indexes, caches, or eager source trees are generated.

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
