# jist

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jist)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jist)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jist` provides high-performance, source-aware search of Java class symbols for a given class or module path.

Search targets can be an exact simple name, a qualified namespace prefix, a source file, or a ClassFile:

```console
$ jist java.lang.String.intern
java.lang.String(String.java:4711):     public native String intern();
```

Capabilities include:

- Find modules, packages, types, methods, constructors, and fields
- Search the JDK without additional configuration
- Search project source and compiled dependencies using standard Java path and module options
- Show literal source signatures, documentation, definitions, enclosing types, or complete source files
- Find semantic usages of an exact type or member, including overriding methods
- Find caller and callee edges for matching executables or every executable declared by a matching type
- Produce terminal-friendly, stable line-oriented, or streaming JSON output

Searches use exact names rather than fuzzy matches. A simple name matches that complete terminal name wherever it is visible. A qualified name selects that exact delimiter-bounded namespace and the symbols beneath it. List mode also accepts `*` wildcards for declaration-name matching. Relationship options accept a delimiter-bounded type or member suffix, such as `String.repeat`. There is no ranking, typo correction, or implicit resolution of other partially qualified names.

> [!IMPORTANT]
> This tool is currently in preview. Please share feedback for any of the tools in [Discussions](https://github.com/Netflix/ja/discussions).

## Installation

> [!NOTE]
> Netflix engineers should use the internally bundled toolchain rather than installing this tool separately.

Follow the `ja` [Installation Guide](https://github.com/Netflix/ja#installation) to install the bundled tools, including `jist`.

For standalone use, resolve the modular JAR and its dependencies from [Maven Central](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jist). JDK 25 or later is required. Run module `com.netflix.tools.jist` with the resolved modules on the module path:

```sh
java --module-path <resolved-modules> --module com.netflix.tools.jist java.lang.String
```

JMOD artifacts are also published for building custom runtime images.

## Quick start

Find every visible symbol with a complete simple name:

```sh
jist HttpClient
```

List declarations whose terminal names contain a fragment:

```sh
jist -l --source none '*Queue*'
```

Select an exact package, type, or member prefix with a qualified name:

```sh
jist java.lang.String.valueOf
```

Find semantic references to an exact type or member:

```sh
jist --usages java.lang.Integer.MAX_VALUE
```

Find calls to methods matching a simple name or type/member suffix:

```sh
jist --callers repeat
jist --callers String.repeat
```

Find calls to any method or constructor declared by a matching type:

```sh
jist --callers String
```

Show source documentation and the complete declaration:

```sh
jist --source definition java.lang.String.length
```

Stream qualified, machine-readable results:

```sh
jist --format json java.lang.String.length
```

`--release` applies javac's Java SE API signatures when Jist attributes project or attached source. It does not change the ClassFiles Jist searches or select historical JDK implementation source; when matching source is unavailable, Jist falls back to ClassFile signatures.

A Java file on a directory source path takes precedence over matching ClassFiles, so searches reflect current project source without requiring compilation. Source archives remain attached source for their matching ClassFiles.

`--exclude-path <path>` narrows searches by excluding input roots. Separate multiple paths with the platform path separator, as with `--class-path` and `--module-path`. Paths are matched by normalized absolute location, not by a recursive pattern. Excluded inputs remain available for type resolution, relationship targets, ancestor traversal, and source attachment.

## Documentation

The [wiki](https://github.com/Netflix/jist/wiki) covers:

- [Searching](https://github.com/Netflix/jist/wiki/Searching)
- [Reading source](https://github.com/Netflix/jist/wiki/Reading-Source)
- [Controlling output](https://github.com/Netflix/jist/wiki/Controlling-Output)
- [Project integration](https://github.com/Netflix/jist/wiki/Project-Integration)
