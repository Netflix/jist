# jist

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.jist)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.jist)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

`jist` provides high performance, source aware search of Java class symbols for a given class or module path.

The tool accepts exact simple name, a qualified namespace prefix, a source file, or a ClassFile:

```console
$ jist java.lang.String.intern
java.lang.String(String.java:4711):     public native String intern();
```

We can:

- Find modules, packages, types, methods, constructors, and fields
- Search the JDK without additional configuration
- Search project source and compiled dependencies using standard Java path and module options
- Show literal source signatures, documentation, definitions, enclosing types, or complete source files
- Find semantic usages of an exact type or member, including overriding methods
- Produce terminal-friendly output or stable line-oriented output for other tools

> [!IMPORTANT]
> This tool is currently in preview. We are collecting all preview feedback in the [`ja` repository](https://github.com/Netflix/ja): use [Issues](https://github.com/Netflix/ja/issues) to report problems and [Discussions](https://github.com/Netflix/ja/discussions) for feedback, questions, and suggestions.

## Installation

> [!NOTE]
> Netflix engineers should use the internally bundled toolchain rather than installing this tool separately.

Follow the `ja` [Installation Guide](https://github.com/Netflix/ja#installation) to install the bundled tools, including `jist`.

For standalone use, `jar` and `jmod` artifacts are available on Maven Central. We require JDK 25 or later.

## Find declarations

A simple name finds every visible symbol whose complete simple name matches:

```sh
jist HttpClient
jist isEmpty
jist MAX_VALUE
```

A qualified name selects an exact package, type, or member prefix:

```sh
jist java.net.http
jist java.lang.String
jist java.lang.String.valueOf
jist java.lang.Integer.MAX_VALUE
```

A package name includes the packages and symbols beneath it. A type name includes the type and its members. A method name includes all overloads. Use `new` for constructors:

```sh
jist java.lang.String.new
```

Run without a target to enumerate every visible symbol:

```sh
jist
```

You can also inspect one source file or ClassFile directly:

```sh
jist path/to/Example.java
jist path/to/Example.class
```

We show literal source signatures when source is available and fall back to a declaration reconstructed from the ClassFile:

```console
$ jist java.lang.String.isEmpty
java.lang.String(String.java:1600):     public boolean isEmpty() {
```

The name before the colon is the compilation unit that owns the declaration. Source output adds its filename and line number in parentheses. Nested types and their members remain anchored by their top-level compilation unit:

```text
java.util.Map: public abstract interface Entry<K, V> {
```

## Search a project

We search the running JDK without setup. To search a project, provide the same classes, modules, and sources used to compile it. We accept the corresponding standard Java options:

```sh
jist --class-path build/classes:libs/example.jar com.example
jist --source-path src/main/java com.example
jist --module-path build/modules:libs/example.jar --module com.example.app com.example
jist --module-source-path src --module com.example.app com.example
jist --system /path/to/jdk java.lang
```

Class-path entries belong to the unnamed module. `--module` selects one or more comma-separated compilation modules. `--module-path` makes application modules observable, but does not make every module a root. `--add-modules` adds roots, and we follow their resolved `requires` edges:

```sh
jist --module-path path/to/modules --add-modules ALL-MODULE-PATH com.example
```

We evaluate visibility from the selected compilation modules and apply `--limit-modules`, `--add-reads`, and `--add-exports` to the resolved graph. A class-path entry is visible to a selected named module only when that module reads the unnamed module, for example through `--add-reads M=ALL-UNNAMED`.

### Interoperability

The included Gradle init script can publish the options for each source set:

```sh
./gradlew --init-script /path/to/jist/gradle/jist.init.gradle writeJavaToolOptions
```

It writes a `jist.args` file beneath each mirrored source-set scope in a `.java-tool-options` directory. The native launchers can then supply the applicable tool-specific arguments to `jist`.

## Read source

Use `-s` or `--source` to choose how much source to show:

| Scope | Output |
|---|---|
| `none` | Compiled declaration without reading source |
| `body` | Attached-source lines named by a compiled method's line-number table |
| `signature` | Literal source declaration header (default) |
| `doc` | Associated Javadoc through the end of the declaration header |
| `definition` | Associated Javadoc and the complete declaration |
| `symbol` | Source appropriate to the selected symbol |
| `type` | Complete outermost top-level type and its Javadoc |
| `unit` | Complete attached source file |

For example, show the Javadoc and implementation of `String.length()`:

```console
$ jist --source definition java.lang.String.length
java.lang.String(String.java:1574):     /**
java.lang.String(String.java:1575):      * Returns the length of this string.
java.lang.String(String.java:1576):      *
java.lang.String(String.java:1581):      */
java.lang.String(String.java:1582):     public int length() {
java.lang.String(String.java:1583):         return value.length >> coder();
java.lang.String(String.java:1584):     }
```

`symbol` emits the complete compilation unit for a top-level type, the complete declaration for a nested type or executable, and the signature for other symbols.

`body` uses the selected methods' ClassFile line-number tables. It can therefore show attached non-Java source, such as Kotlin, without trying to infer language-specific declaration boundaries. Abstract or native methods and ClassFiles without line-number tables fall back to compiled declarations. `unit` also supports complete attached non-Java source files.

When requested source is unavailable, we warn once per compilation unit on standard error and print deterministic ClassFile output instead. Use `--source none` when compiled declarations are intended. We omit compiler-generated Kotlin metadata annotations from Java-shaped declarations and retain ordinary declaration annotations and values.

## Find usages

Pass one exact type or member to `--usages`:

```sh
jist --usages com.example.SessionManager.create
jist --usages java.lang.Integer.MAX_VALUE
jist --usages java.lang.String.new
```

A method name includes all overloads. Method usages include overriding declarations and references resolved to overrides. Each result shows the source line containing the reference:

```text
com.example.LoginService(LoginService.java:74):         return sessionManager.create(userId);
```

Widen each match to its containing declaration, top-level type, or source file when more context is useful:

```sh
jist --usages com.example.SessionManager.create --source definition
jist --usages com.example.SessionManager.create --source type
jist --usages com.example.SessionManager.create --source unit
```

Zero usages is a successful empty result. When source text is unavailable, we print the enclosing symbol and any debug coordinates available from the ClassFile.

## Filter symbols

Use `-k` or `--kind` to include only particular declaration kinds:

```sh
jist -k module java.base
jist -k package java.lang
jist -k type java.lang
jist -k class com.example
jist -k interface java.util
jist -k enum java.time
jist -k record com.example
jist -k annotation java.lang
jist -k method java.lang.String
jist -k field java.lang.Integer
```

`type` is the union of classes, interfaces, enums, records, and annotations. Repeat `--kind` to include more than one kind.

Module declarations come from resolved module descriptors. Packages use `package-info.java` documentation and annotations when available, and otherwise appear as synthesized `package NAME;` declarations. We do not synthesize unobservable parent packages.

We show public and protected library declarations by default. Choose another visibility level when needed:

```text
-public       Public declarations
-protected    Public and protected declarations
-package      Public, protected, and package declarations
-private      All declarations
```

## Control output

On a terminal, we group consecutive results beneath compilation-unit headings and color symbol kinds, names, and line numbers:

```text
class java.lang.String(String.java)
1600:    public boolean isEmpty() {
1601:        return value.length == 0;
1602:    }
```

Redirected output keeps one complete result on each line so it composes with shell tools:

```sh
jist -k method java.lang.String | grep valueOf
jist -k type java.net | sort -u | fzf
```

Use `--pretty` to force terminal presentation. `--heading`, `--no-heading`, and `--color auto|always|never` control grouping and color independently.

An explicit source-file target omits its redundant unit and filename by default:

```text
12:    public void run() {
```

Use `--heading`, `--no-heading`, or `--qualified-path` to retain file context. `--qualified-path` shows a project path or archive URI for source output and the ClassFile origin for compiled output.

Additional output controls include:

- `-l` prints one kind and qualified name for each matching symbol
- `-N` or `--no-line-number` removes source line numbers
- `--break` inserts a blank line between non-consecutive source matches
- `--source doc -N --break` produces compact API documentation

Usage searches highlight only source token ranges attributed by javac to the requested symbol. Declarations and ClassFile-only usages are not highlighted.

## Help

```sh
jist --help
jist --version
```

## Build and test

Build and test `jist` with JDK 25 or later:

```sh
ja test
./build.sh
```

Install the resulting tool into the active JDK image:

```sh
./install.sh --force
```
