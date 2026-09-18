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
- Produce terminal-friendly output or stable line-oriented output for other tools

Searches use exact names rather than fuzzy matches. A simple name matches that complete terminal name wherever it is visible. A qualified name selects that exact delimiter-bounded namespace and the symbols beneath it. There is no substring matching, ranking, typo correction, or implicit resolution of partially qualified names.

> [!IMPORTANT]
> This tool is currently in preview. Please share feedback for any of the tools in [Discussions](https://github.com/Netflix/ja/discussions).

## Installation

> [!NOTE]
> Netflix engineers should use the internally bundled toolchain rather than installing this tool separately.

Follow the `ja` [Installation Guide](https://github.com/Netflix/ja#installation) to install the bundled tools, including `jist`.

For standalone use, `jar` and `jmod` artifacts are available on Maven Central. JDK 25 or later is required.

## Quick start

Find every visible symbol with a complete simple name:

```sh
jist HttpClient
```

Select an exact package, type, or member prefix with a qualified name:

```sh
jist java.lang.String.valueOf
```

Find semantic references to an exact type or member:

```sh
jist --usages java.lang.Integer.MAX_VALUE
```

Show source documentation and the complete declaration:

```sh
jist --source definition java.lang.String.length
```

## Documentation

The [wiki](https://github.com/Netflix/jist/wiki) covers:

- [Searching](https://github.com/Netflix/jist/wiki/Searching)
- [Reading source](https://github.com/Netflix/jist/wiki/Reading-Source)
- [Controlling output](https://github.com/Netflix/jist/wiki/Controlling-Output)
- [Project integration](https://github.com/Netflix/jist/wiki/Project-Integration)
