/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.netflix.tools.jist.test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Jist;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassQueryTest {

    private static final Path ROOT = Path.of("build/test-class-query");
    private static final Path SOURCE = ROOT.resolve("src/com/example/Example.java");
    private static final Path CLASS = ROOT.resolve("classes/com/example/Example.class");
    private static final Path UNCOMPILED_SOURCE = ROOT.resolve("src/com/example/Uncompiled.java");
    private static final Path OVERLOAD_SOURCE = ROOT.resolve("src/com/example/Overloads.java");
    private static final Path SECONDARY_SOURCE = ROOT.resolve("src/com/example/Container.java");
    private static final Path SCOPED_ARGS = ROOT.resolve("main.args");
    private static final Path JAR = ROOT.resolve("example.jar");
    private static final Path SOURCE_JAR = ROOT.resolve("example-sources.jar");
    private static final Path NESTED_SOURCE_JAR = ROOT.resolve("example-nested-sources.jar");
    private static final Path MULTI_RELEASE_JAR = ROOT.resolve("versioned.jar");
    private static final Path MULTI_RELEASE_SOURCE_JAR = ROOT.resolve("versioned-sources.jar");
    private static final Path NON_JAVA_JAR = ROOT.resolve("example-non-java.jar");
    private static final Path NON_JAVA_SOURCE_JAR = ROOT.resolve("example-non-java-sources.jar");
    private static final Path MODULAR_SYSTEM_SOURCE = ROOT.resolve("system-sources.zip");
    private static final Path MODULE_SOURCE = ROOT.resolve("module-src/sample.module/modular/api/ModuleType.java");
    private static final Path MODULE_INFO_SOURCE = ROOT.resolve("module-src/sample.module/module-info.java");
    private static final Path MODULE_PACKAGE_INFO_SOURCE = ROOT.resolve("module-src/sample.module/modular/api/package-info.java");
    private static final Path HIDDEN_MODULE_SOURCE = ROOT.resolve("module-src/sample.module/modular/internal/HiddenType.java");
    private static final Path MODULE_CLASSES = ROOT.resolve("module-classes/sample.module");
    private static final Path MODULE_JAR = ROOT.resolve("sample.module.jar");
    private static final Path BACKING_SOURCE = ROOT.resolve("backing/com/example/Backing.java");
    private static final Path IMPORT_SOURCE = ROOT.resolve("src/com/example/Imports.java");
    private static final Path IMPORT_CLASS = ROOT.resolve("classes/com/example/Imports.class");
    private static final Path COLLISION_SOURCE = ROOT.resolve("src/com/example/Collisions.java");
    private static final Path COLLISION_CLASS = ROOT.resolve("classes/com/example/Collisions.class");
    private static final Path NESTED_SOURCE = ROOT.resolve("src/com/example/Outer.java");
    private static final Path AMBIGUOUS_ALPHA_SOURCE = ROOT.resolve("src/alpha/Duplicate.java");
    private static final Path AMBIGUOUS_BETA_SOURCE = ROOT.resolve("src/beta/Duplicate.java");
    private static final Path KINDS_SOURCE = ROOT.resolve("src/com/example/Kinds.java");
    private static final Path ESCAPED_SOURCE = ROOT.resolve("src/com/example/Escaped.java");
    private static final Path KOTLIN_METADATA_SOURCE = ROOT.resolve("src/kotlin/Metadata.java");
    private static final Path KOTLIN_SOURCE_DEBUG_EXTENSION_SOURCE = ROOT.resolve("src/kotlin/jvm/internal/SourceDebugExtension.java");
    private static final Path KOTLIN_SHAPED_SOURCE = ROOT.resolve("src/com/example/KotlinShaped.java");
    private static final Path UNCOMPILED_KINDS_SOURCE = ROOT.resolve("source-only/com/example/SourceKinds.java");

    @BeforeAll
    static void compileSource() throws Exception {
        Files.createDirectories(SOURCE.getParent());
        Files.createDirectories(CLASS.getParent());
        Files.writeString(SOURCE,
                """
                package com.example;

                /** Example documentation. */
                public class Example {
                    public static final int VALUE = 1;

                    /** Run documentation. */
                    public int run(int amount) {
                        return amount + 1;
                    }

                    protected void extensionPoint() {}

                    private void internal() {}
                }
                """);

        Files.writeString(OVERLOAD_SOURCE,
                """
                package com.example;

                public class Overloads {
                    /** First overload. */
                    public void run() {}

                    private void run(String ignored) {}

                    /** Second overload. */
                    public void run(int value) {}
                }
                """);
        Files.writeString(IMPORT_SOURCE,
                """
                package com.example;

                import java.time.Duration;
                import java.util.List;
                import java.util.Map;

                import static java.util.Objects.requireNonNull;

                public class Imports {
                    public Map<String, List<Integer>> values() {
                        return requireNonNull(null);
                    }
                }
                """);
        Files.writeString(COLLISION_SOURCE,
                """
                package com.example;

                public class Collisions {
                    public java.util.Date convert(java.sql.Date value) {
                        return null;
                    }
                }
                """);
        Files.writeString(NESTED_SOURCE,
                """
                package com.example;

                public class Outer {
                    public static class Nested {
                        public void work() {}
                    }
                }
                """);
        Files.createDirectories(AMBIGUOUS_ALPHA_SOURCE.getParent());
        Files.createDirectories(AMBIGUOUS_BETA_SOURCE.getParent());
        Files.writeString(AMBIGUOUS_ALPHA_SOURCE,
                """
                package alpha;
                public class Duplicate {}
                """);
        Files.writeString(AMBIGUOUS_BETA_SOURCE,
                """
                package beta;
                public class Duplicate {}
                """);
        Files.createDirectories(MODULE_SOURCE.getParent());
        Files.writeString(MODULE_INFO_SOURCE,
                """
                module sample.module {
                    exports modular.api;
                }
                """);
        Files.writeString(MODULE_SOURCE,
                """
                package modular.api;

                public class ModuleType {
                    public void act() {}
                }
                """);
        Files.writeString(MODULE_PACKAGE_INFO_SOURCE,
                """
                @Deprecated
                package modular.api;
                """);
        Files.createDirectories(HIDDEN_MODULE_SOURCE.getParent());
        Files.writeString(HIDDEN_MODULE_SOURCE,
                """
                package modular.internal;
                public class HiddenType {}
                """);
        Files.writeString(KINDS_SOURCE,
                """
                package com.example;

                public class Kinds {
                    public static class Ordinary {}
                    public interface Contract {}
                    public enum Choice { ONE }
                    public record Point(int x) {}
                    public @interface Marker {}
                }
                """);
        var controls = "\\0\\b\\t\\n\\f\\r\\\"'\\\\\\1\\177";
        Files.writeString(ESCAPED_SOURCE,
                """
                package com.example;

                @interface Encoded {
                    String text();
                    char character();
                }

                @Encoded(text = "%s", character = '\\n')
                public class Escaped {
                    public static final String TEXT = "%s";
                    public static final char CHARACTER = '\\n';
                }
                """
                        .formatted(controls, controls));
        Files.createDirectories(KOTLIN_METADATA_SOURCE.getParent());
        Files.writeString(KOTLIN_METADATA_SOURCE,
                """
                package kotlin;

                public @interface Metadata {
                    String[] d1();
                    String[] d2() default {};
                }
                """);
        Files.createDirectories(KOTLIN_SOURCE_DEBUG_EXTENSION_SOURCE.getParent());
        Files.writeString(KOTLIN_SOURCE_DEBUG_EXTENSION_SOURCE,
                """
                package kotlin.jvm.internal;

                public @interface SourceDebugExtension {
                    String[] value();
                }
                """);
        Files.writeString(KOTLIN_SHAPED_SOURCE,
                """
                package com.example;

                @kotlin.Metadata(
                    d1 = {"encoded compiler payload mentioning DgsQueryExecutor"},
                    d2 = {"more encoded compiler payload"})
                @kotlin.jvm.internal.SourceDebugExtension({"encoded SMAP payload"})
                public class KotlinShaped {}
                """);

        var compiler = ToolProvider.getSystemJavaCompiler();
        Files.writeString(SCOPED_ARGS,
                """
                --class-path
                %s
                --source-path
                %s
                """
                        .formatted(ROOT.resolve("classes"), ROOT.resolve("src")));
        try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
            var sources = fileManager.getJavaFileObjects(
                    SOURCE,
                    OVERLOAD_SOURCE,
                    IMPORT_SOURCE,
                    COLLISION_SOURCE,
                    NESTED_SOURCE,
                    AMBIGUOUS_ALPHA_SOURCE,
                    AMBIGUOUS_BETA_SOURCE,
                    KINDS_SOURCE,
                    ESCAPED_SOURCE,
                    KOTLIN_METADATA_SOURCE,
                    KOTLIN_SOURCE_DEBUG_EXTENSION_SOURCE,
                    KOTLIN_SHAPED_SOURCE);
            assertTrue(compiler.getTask(
                                       null,
                                       fileManager,
                                       null,
                                       List.of("-d", ROOT.resolve("classes").toString(),
                                               "-parameters"),
                                       null,
                                       sources)
                               .call());
            var moduleSources = fileManager.getJavaFileObjects(MODULE_INFO_SOURCE, MODULE_SOURCE, MODULE_PACKAGE_INFO_SOURCE, HIDDEN_MODULE_SOURCE);
            assertTrue(compiler.getTask(null, fileManager, null, List.of("-d", MODULE_CLASSES.toString()), null,
                                       moduleSources)
                               .call());
        }
        Files.writeString(UNCOMPILED_SOURCE,
                """
                package com.example;

                public class Uncompiled {
                    int value;

                    void pending() {
                        value++;
                    }
                }
                """);
        Files.createDirectories(UNCOMPILED_KINDS_SOURCE.getParent());
        Files.writeString(UNCOMPILED_KINDS_SOURCE,
                """
                package com.example;

                public class SourceKinds {
                    public record SourceRecord(int value) {}
                    public @interface SourceAnnotation {}
                }
                """);

        Files.createDirectories(BACKING_SOURCE.getParent());
        Files.writeString(BACKING_SOURCE,
                """
                package com.example;
                public class Backing {}
                """);

        Files.writeString(SECONDARY_SOURCE,
                """
                package com.example;

                public class Container {}

                class Secondary {
                    void secondaryWork() {}
                }
                """);

        try (var jar = new JarOutputStream(Files.newOutputStream(JAR))) {
            jar.putNextEntry(new JarEntry("com/example/Example.class"));
            jar.write(Files.readAllBytes(CLASS));
            jar.closeEntry();
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(SOURCE_JAR))) {
            jar.putNextEntry(new JarEntry("com/example/Example.java"));
            jar.write(Files.readAllBytes(SOURCE));
            jar.closeEntry();
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(NESTED_SOURCE_JAR))) {
            jar.putNextEntry(new JarEntry("main/com/example/Example.java"));
            jar.write(Files.readAllBytes(SOURCE));
            jar.closeEntry();
        }
        createMultiReleaseFixture(compiler);
        var classFile = ClassFile.of();
        var nonJavaClass = classFile.transformClass(classFile.parse(Files.readAllBytes(CLASS)),
                ClassTransform.dropping(SourceFileAttribute.class::isInstance).andThen(ClassTransform.endHandler(builder -> builder.with(SourceFileAttribute.of("Example.kt")))));
        try (var jar = new JarOutputStream(Files.newOutputStream(NON_JAVA_JAR))) {
            jar.putNextEntry(new JarEntry("com/example/Example.class"));
            jar.write(nonJavaClass);
            jar.closeEntry();
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(NON_JAVA_SOURCE_JAR))) {
            jar.putNextEntry(new JarEntry("com/example/Example.kt"));
            jar
                    .write("""
                    package com.example

                    class Example {
                        val value = 1

                        // Keep these lines aligned with Example.class debug information.
                        fun documentation() = Unit
                        fun run(amount: Int): Int {
                            return amount + 2
                        }
                    }
                    """
                            .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(MODULAR_SYSTEM_SOURCE))) {
            jar.putNextEntry(new JarEntry("java.base/java/lang/String.java"));
            jar
                    .write("""
                    package java.lang;
                    public final class String {
                        /** Repeats this string. */
                        public String repeat(int count) { return this; }
                    }
                    """
                            .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(MODULE_JAR));
             var files = Files.walk(MODULE_CLASSES)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(MODULE_CLASSES.relativize(file)
                        .toString()
                        .replace(File.separatorChar, '/')));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
    }

    private static void createMultiReleaseFixture(JavaCompiler compiler) throws IOException {
        var baseSource = ROOT.resolve("versioned-base-src/com/example/Versioned.java");
        var versionSource = ROOT.resolve("versioned-9-src/com/example/Versioned.java");
        var baseClasses = ROOT.resolve("versioned-base-classes");
        var versionClasses = ROOT.resolve("versioned-9-classes");
        Files.createDirectories(baseSource.getParent());
        Files.createDirectories(versionSource.getParent());
        Files.createDirectories(baseClasses);
        Files.createDirectories(versionClasses);
        Files.writeString(baseSource,
                """
                package com.example;
                public class Versioned {
                    public String marker() { return "base"; }
                }
                """);
        Files.writeString(versionSource,
                """
                package com.example;
                public class Versioned {
                    public String marker() { return "versioned"; }
                }
                """);
        assertEquals(
                0,
                compiler.run(null, null, null, "-d", baseClasses.toString(),
                        baseSource.toString()));
        assertEquals(
                0,
                compiler.run(null, null, null, "-d", versionClasses.toString(),
                        versionSource.toString()));

        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var jar = new JarOutputStream(Files.newOutputStream(MULTI_RELEASE_JAR), manifest)) {
            writeJarEntry(jar, "com/example/Versioned.class", baseClasses.resolve("com/example/Versioned.class"));
            writeJarEntry(jar, "META-INF/versions/9/com/example/Versioned.class", versionClasses.resolve("com/example/Versioned.class"));
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(MULTI_RELEASE_SOURCE_JAR))) {
            writeJarEntry(jar, "com/example/Versioned.java", baseSource);
            writeJarEntry(jar, "META-INF/versions/9/com/example/Versioned.java", versionSource);
        }
    }

    private static void writeJarEntry(JarOutputStream jar, String name, Path source) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(Files.readAllBytes(source));
        jar.closeEntry();
    }

    @Test
    void omittedTargetEnumeratesClasspathClassNames() {
        var result = run("--class-path", ROOT.resolve("classes")
                .toString());

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public class Example"),
                result.output());
    }

    @Test
    void omittedTargetCanEnumerateSourceSignatures() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "-k",
                "method",
                "-s",
                "signature");
        var defaultResult = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "-k",
                "method");
        var classFileResult = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "-k",
                "method",
                "-s",
                "none");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(result.output(), defaultResult.output());
        assertTrue(result.output().contains("com.example.Example(Example.java:8):     public int run(int amount) {"),
                result.output());
        assertEquals(
                1,
                result.output()
                      .lines()
                      .filter(line -> line.equals("com.example.Example(Example.java:8):     public int run(int amount) {"))
                      .count(),
                result.output());
        assertFalse(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
        assertTrue(classFileResult.output().contains("com.example.Example: public int run(int amount)"),
                classFileResult.output());
        assertFalse(classFileResult.output().contains("Example.java:"),
                classFileResult.output());
    }

    @Test
    void sourceContextIncludesVisibleOverloadsSeparatedByPrivateOverloads() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "--source",
                "doc",
                "com.example.Overloads.run");

        assertEquals(0, result.exitCode(), result.error());
        assertAll(
                () -> assertTrue(result.output().contains("First overload."),
                        result.output()),
                () -> assertTrue(result.output().contains("public void run()"),
                        result.output()),
                () -> assertTrue(result.output().contains("Second overload."),
                        result.output()),
                () -> assertTrue(result.output().contains("public void run(int value)"),
                        result.output()),
                () -> assertFalse(result.output().contains("private void run"),
                        result.output()));
    }

    @Test
    void kindFiltersClassFileTypes() {
        var expected = Map.of("class", "class Ordinary", "interface", "interface Contract", "enum", "enum Choice",
                "record", "record Point", "annotation", "@interface Marker");

        for (var entry : expected.entrySet()) {
            var result = run(
                    "--system",
                    "none",
                    "--class-path",
                    ROOT.resolve("classes").toString(),
                    "--kind",
                    entry.getKey());
            assertEquals(0, result.exitCode(), result.error());
            assertTrue(result.output().contains(entry.getValue()),
                    entry.getKey() + ": " + result.output());
        }
        var allTypes = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--kind",
                "type");
        for (var declaration : expected.values()) {
            assertTrue(allTypes.output().contains(declaration),
                    allTypes.output());
        }
    }

    @Test
    void kindsCanBeCombined() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "-k",
                "enum",
                "-k",
                "record");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("enum Choice"),
                result.output());
        assertTrue(result.output().contains("record Point"),
                result.output());
    }

    @Test
    void kindFiltersSourceOnlyTypes() {
        var result = run(
                "--system",
                "none",
                "--source-path",
                UNCOMPILED_KINDS_SOURCE.getParent()
                                       .getParent()
                                       .getParent()
                                       .toString(),
                "--kind",
                "record");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.SourceKinds(SourceKinds.java:4):     public record SourceRecord"),
                result.output());
    }

    @Test
    void unknownKindFailsWithAcceptedKinds() {
        var result = run("--kind", "trait");

        assertEquals(1, result.exitCode());
        assertEquals(
                "Error: --kind expects one of: " + "module, package, type, class, interface, enum, record, annotation, method, field\n",
                result.error());
    }

    @Test
    void omittedTargetEnumeratesModulePathClassesAndMembers() {
        var result = run("--module-path", MODULE_JAR.toString(), "--add-modules", "sample.module", "-k",
                "type", "-k", "method");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("modular.api.ModuleType: public class ModuleType"),
                result.output());
        assertTrue(result.output().contains("modular.api.ModuleType: public void act()"),
                result.output());
        assertFalse(result.output().contains("modular.internal.HiddenType"),
                result.output());
    }

    @Test
    void omittedTargetEnumeratesRuntimeImageClasses() {
        var result = run("-s", "none", "-k", "type");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("java.lang.String: public final class String"),
                result.output());
        assertFalse(result.output().contains("com.sun.org.apache.xpath.internal.operations.String"),
                result.output());
    }

    @Test
    void runtimeModuleMetadataPrecedesItsPackages() {
        var result = run("--limit-modules", "java.base", "-s", "none", "-l", "-k",
                "module", "-k", "package");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("module java.base",
                result.output()
                      .lines()
                      .findFirst()
                      .orElseThrow());
        assertTrue(result.output().contains("package java.lang\n"),
                result.output());
    }

    @Test
    void sourceOnlyDeclarationsParticipateInEnumeration() {
        var result = run(
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "-k",
                "type",
                "-k",
                "method",
                "-k",
                "field");

        assertEquals(0, result.exitCode(), result.error());
        assertAll(
                () -> assertTrue(result.output().contains("class Uncompiled"),
                        result.output()),
                () -> assertTrue(result.output().contains("int value"),
                        result.output()),
                () -> assertTrue(result.output().contains("void pending()"),
                        result.output()));
    }

    @Test
    void allSourcePathInputsParticipateInEnumeration() {
        var result = run("--source-path", ROOT.resolve("src") + File.pathSeparator + ROOT.resolve("backing"));

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Backing"),
                result.output());
        assertTrue(result.output().contains("com.example.Uncompiled"),
                result.output());
    }

    @Test
    void explicitVisibilityAlsoAppliesToSourceRendering() {
        var result = run("-public", UNCOMPILED_SOURCE.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("3:public class Uncompiled {\n", result.output());
    }

    @Test
    void exactLookupFallsBackToUncompiledSource() {
        var result = run(
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "--source",
                "definition",
                "com.example.Uncompiled.pending");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Uncompiled(Uncompiled.java:7):         value++;"),
                result.output());
    }

    @Test
    void enumeratedSecondaryTopLevelClassCanBeQueried() {
        var result = run(
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "com.example.Secondary.secondaryWork");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Container(Container.java:6):     void secondaryWork() {}"),
                result.output());
    }

    @Test
    void typeScopeUsesTheSelectedSecondaryTopLevelType() {
        var result = run(
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "--source",
                "type",
                "com.example.Secondary.secondaryWork");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("class Secondary {"),
                result.output());
        assertTrue(result.output().contains("void secondaryWork() {}"),
                result.output());
        assertFalse(result.output().contains("public class Container"),
                result.output());
    }

    @Test
    void completionUsesTheSelectedCompilationContextAndCollapsesOverloads() {
        var type = run(
                "__complete",
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source",
                "symbol",
                "Exa");
        var member = run("__complete", "--system", "none", "--class-path",
                ROOT.resolve("classes").toString(), "com.example.Example.r");

        assertEquals(0, type.exitCode(), type.error());
        assertTrue(type.output().contains("com.example.Example\tJava class\n"),
                type.output());
        assertEquals(0, member.exitCode(), member.error());
        assertEquals(
                1,
                member.output()
                      .lines()
                      .filter(line -> line.equals("com.example.Example.run\tJava method"))
                      .count(),
                member.output());
    }

    @Test
    void memberEnumerationWritesResultsBeforeScanningLaterClasses() throws IOException {
        var malformedJar = ROOT.resolve("malformed.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(malformedJar))) {
            jar.putNextEntry(new JarEntry("com/example/Example.class"));
            jar.write(Files.readAllBytes(CLASS));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("invalid/Broken.class"));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }

        var result = run("--class-path", malformedJar.toString(), "-k", "method");

        assertEquals(2, result.exitCode());
        assertTrue(result.output().startsWith("com.example.Example: public Example()"),
                result.output());
        assertTrue(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
    }

    @Test
    void methodsEnumeratesReusableMemberTargets() {
        var result = run("--class-path", ROOT.resolve("classes").toString(),
                "-k", "method");

        assertEquals(0, result.exitCode(), result.error());
        assertAll(
                () -> assertTrue(result.output().contains("public Example()"),
                        result.output()),
                () -> assertTrue(result.output().contains("public int run(int amount)"),
                        result.output()),
                () -> assertTrue(result.output().contains("protected void extensionPoint()"),
                        result.output()));
    }

    @Test
    void methodAndFieldKindsExcludeOwningTypes() {
        var methods = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--kind",
                "method");
        var fields = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--kind",
                "field");

        assertAll(
                () -> assertTrue(methods.output().contains("public int run(int amount)"),
                        methods.output()),
                () -> assertFalse(methods.output().contains("class Example"),
                        methods.output()),
                () -> assertTrue(fields.output().contains("public static final int VALUE"),
                        fields.output()),
                () -> assertFalse(fields.output().contains("class Example"),
                        fields.output()));
    }

    @Test
    void kindSearchAcceptsAnExactSymbolPrefixAndUsesDotAddresses() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--kind",
                "method",
                "com.example.Example");

        assertEquals(0, result.exitCode(), result.error());
        assertAll(
                () -> assertTrue(result.output().contains("public Example()"),
                        result.output()),
                () -> assertTrue(result.output().contains("public int run(int amount)"),
                        result.output()),
                () -> assertTrue(result.output().contains("protected void extensionPoint()"),
                        result.output()));
    }

    @Test
    void kindSearchRequiresDelimiterBoundedPrefix() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--kind",
                "type",
                "com.example.Kinds.Ord");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("", result.output());
    }

    @Test
    void implicitNamespacePrefixSearchesAllSymbolKinds() {
        var result = run("--system", "none", "--class-path",
                ROOT.resolve("classes").toString(), "com.example");

        assertEquals(0, result.exitCode(), result.error());
        assertAll(
                () -> assertTrue(result.output().contains("class Example"),
                        result.output()),
                () -> assertTrue(result.output().contains("int run(int amount)"),
                        result.output()),
                () -> assertTrue(result.output().contains("int VALUE"),
                        result.output()));
    }

    @Test
    void runtimeNamespacePrefixSearchesSymbols() {
        var result = run("-s", "none", "-k", "type", "java.lang");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("java.lang.String: public final class String"),
                result.output());
    }

    @Test
    void sourceScopesAcceptPackagePrefixes() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "-s",
                "definition",
                "-k",
                "class",
                "com.example");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example(Example.java:4): public class Example {"),
                result.output());
    }

    @Test
    void exactTypeSearchRendersAClassFilePresentation() {
        var result = run("-s", "none", "java.lang.String");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().startsWith("java.lang.String: public final class String"),
                result.output());
        assertTrue(result.output().contains("java.lang.String: public boolean isEmpty() { /* body omitted */ }"),
                result.output());
        assertFalse(result.output().contains("package java.lang;"),
                result.output());
    }

    @Test
    void matchOptionIsNotAccepted() {
        var result = run("--match", "Example");

        assertEquals(1, result.exitCode());
        assertEquals("Error: Unknown option: --match\n", result.error());
    }

    @Test
    void visibilityCanOverrideTheConsumedApiDefault() {
        var publicResult = run("--class-path", ROOT.resolve("classes").toString(),
                "-k", "method", "-public");
        var privateResult = run("--class-path", ROOT.resolve("classes").toString(),
                "-k", "method", "-private");

        assertAll(
                () -> assertFalse(publicResult.output().contains("extensionPoint()"),
                        publicResult.output()),
                () -> assertFalse(publicResult.output().contains("internal()"),
                        publicResult.output()),
                () -> assertTrue(privateResult.output().contains("internal()"),
                        privateResult.output()));
    }

    @Test
    void classRenderingUsesTheSameVisibilityPolicy() {
        var consumed = run("--class-path", ROOT.resolve("classes").toString(),
                "com.example.Example");
        var privateView = run("--class-path", ROOT.resolve("classes").toString(),
                "-private", "com.example.Example");

        assertAll(
                () -> assertFalse(consumed.output().contains("internal()"),
                        consumed.output()),
                () -> assertTrue(consumed.output().contains("extensionPoint()"),
                        consumed.output()),
                () -> assertTrue(privateView.output().contains("internal()"),
                        privateView.output()));
    }

    @Test
    void fieldsEnumeratesReusableMemberTargets() {
        var result = run("--class-path", ROOT.resolve("classes").toString(),
                "-k", "field");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("public static final int VALUE"),
                result.output());
    }

    @Test
    void legacyEnumerationSelectorsAreRejected() {
        var result = run("--methods");

        assertEquals(1, result.exitCode());
        assertEquals("Error: Unknown option: --methods\n", result.error());
    }

    @Test
    void documentationOptionIsNotAccepted() {
        var result = run("--class-path", ROOT.resolve("classes").toString(),
                "--docs");

        assertEquals(1, result.exitCode());
        assertEquals("Error: Unknown option: --docs\n", result.error());
    }

    @Test
    void definitionScopeEmitsLiteralSourceLinesWithoutAStub() {
        var result = run("--source-path", ROOT.resolve("src").toString(),
                "--source", "definition", IMPORT_CLASS + ".values");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Imports(Imports.java:11):         return requireNonNull(null);"),
                result.output());
        assertFalse(result.output().contains("package com.example;"),
                result.output());
    }

    @Test
    void classFileLocatorListsSymbols() {
        var result = run(COLLISION_CLASS.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Collisions: public Date convert(Date value)"),
                result.output());
    }

    @Test
    void classFileListsSymbolsWithoutIndexMetadata() {
        var result = run(CLASS.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public class Example"),
                result.output());
        assertTrue(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
    }

    @Test
    void warnsWhenSourceOutputFallsBackToClassFileInformation() {
        var result = run(CLASS + ".run");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "Warning: Source unavailable for com.example.Example; " + "using ClassFile information\n",
                result.error());
    }

    @Test
    void explicitCompiledDeclarationsDoNotWarnAboutUnavailableSource() {
        var result = run("--source", "none", CLASS + ".run");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("", result.error());
    }

    @Test
    void missingClassFileMemberFails() {
        var result = run(CLASS + ".missing");

        assertEquals(1, result.exitCode());
        assertEquals("", result.output());
        assertTrue(result.error().contains("Symbol not found: com.example.Example.missing"),
                result.error());
    }

    @Test
    void memberSelectionKeepsOnlyMatchingDeclarations() {
        var result = run(CLASS + ".run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
    }

    @Test
    void explicitArgumentFileProvidesCompilationContext() {
        var result = run("@" + SCOPED_ARGS, "--source", "definition", CLASS.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertAll(
                () -> assertTrue(result.output().contains("return amount + 1;"),
                        result.output()),
                () -> assertFalse(result.output().contains("package com.example;"),
                        result.output()));
    }

    @Test
    void simpleNamesMatchExactTypeNames() {
        var result = run("--class-path", ROOT.resolve("classes").toString(),
                "Example");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public class Example"),
                result.output());
        assertFalse(result.output().contains("public int run"),
                result.output());
    }

    @Test
    void simpleClassNameSupportsMemberQueries() {
        var result = run("--class-path", ROOT.resolve("classes").toString(),
                "com.example.Example.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
    }

    @Test
    void doubleColonMemberTargetsAreRejected() {
        var result = run("java.lang.String::isEmpty");

        assertEquals(1, result.exitCode());
        assertEquals(
                "Error: Invalid symbol target 'java.lang.String::isEmpty'; " + "use '.' between a type and member\n",
                result.error());
    }

    @Test
    void uniqueNestedSimpleClassNameResolvesFromClasspath() {
        var result = run("--class-path", ROOT.resolve("classes").toString(),
                "com.example.Outer.Nested");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Outer: public void work()"),
                result.output());
    }

    @Test
    void typeDefinitionEmitsOneSourceOrderedRange() {
        var result = run(
                "--class-path",
                ROOT.resolve("classes").toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "--source",
                "definition",
                "com.example.Outer");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                """
                com.example.Outer(Outer.java:3): public class Outer {
                com.example.Outer(Outer.java:4):     public static class Nested {
                com.example.Outer(Outer.java:5):         public void work() {}
                com.example.Outer(Outer.java:6):     }
                com.example.Outer(Outer.java:7): }
                """,
                result.output());
    }

    @Test
    void simpleNamesReturnEveryExactMatch() {
        var result = run("--class-path", ROOT.resolve("classes").toString(),
                "Duplicate");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("alpha.Duplicate: public class Duplicate"),
                result.output());
        assertTrue(result.output().contains("beta.Duplicate: public class Duplicate"),
                result.output());
    }

    @Test
    void simpleNamesMatchMembers() {
        var result = run("--class-path", ROOT.resolve("classes").toString(),
                "run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
        assertFalse(result.output().contains("extensionPoint"),
                result.output());
    }

    @Test
    void simpleNameQualifiedPathIdentifiesClassFileFallback() {
        var result = run("--system", "none", "--class-path", JAR.toString(), "--source",
                "none", "--qualified-path", "Example");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example(jar:file:"),
                result.output());
        assertTrue(result.output().contains("example.jar!/com/example/Example.class): public class Example"),
                result.output());
    }

    @Test
    void listPrintsAQualifiedClassFileHeader() {
        var result = run("--system", "none", "--class-path", JAR.toString(), "--source",
                "none", "-l", "--qualified-path", "Example");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().startsWith("class com.example.Example(jar:file:"),
                result.output());
        assertTrue(result.output().endsWith("example.jar!/com/example/Example.class)\n"),
                result.output());
        assertEquals(
                1,
                result.output()
                      .lines()
                      .count(),
                result.output());
    }

    @Test
    void listPrintsTheMatchedSymbolRatherThanItsOwningClass() {
        var result = run("--system", "none", "--class-path", JAR.toString(), "--source",
                "none", "-l", "-k", "field", "VALUE");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("field com.example.Example.VALUE\n", result.output());
    }

    @Test
    void listColorsKindsSeparatelyFromSymbols() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                JAR.toString(),
                "--source",
                "none",
                "-l",
                "--color",
                "always",
                "-k",
                "field",
                "VALUE");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("\033[36mfield\033[0m \033[1;35mcom.example.Example.VALUE\033[0m\n", result.output());
    }

    @Test
    void listDistinguishesMethodOverloadsWithQualifiedParameterTypes() {
        var result = run("-l", "--color", "never", "-k", "method", "java.lang.String.valueOf");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("method java.lang.String.valueOf(int)\n"),
                result.output());
        assertTrue(result.output().contains("method java.lang.String.valueOf(java.lang.Object)\n"),
                result.output());
    }

    @Test
    void listUsesTheConcreteTypeKind() {
        var result = run("-l", "--color", "never", "-s", "none", "-k",
                "interface", "java.util.List");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("interface java.util.List\n", result.output());
    }

    @Test
    void uniqueSimpleClassNameResolvesFromSourcePath() {
        var result = run("--source-path", ROOT.resolve("src").toString(),
                "com.example.Uncompiled.pending");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Uncompiled(Uncompiled.java:6):     void pending() {"),
                result.output());
    }

    @Test
    void uniqueSimpleClassNameResolvesFromJar() {
        var result = run("--class-path", JAR.toString(), "com.example.Example.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
    }

    @Test
    void uniqueSimpleClassNameResolvesFromModulePath() {
        var result = run("--module-path", MODULE_JAR.toString(), "--add-modules", "sample.module", "modular.api.ModuleType.act");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("modular.api.ModuleType: public void act()"),
                result.output());
    }

    @Test
    void unexportedModuleClassIsNotVisibleBySimpleName() {
        var result = run("--module-path", MODULE_JAR.toString(), "--add-modules", "sample.module", "modular.internal.HiddenType");

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("no symbol prefix matches"),
                result.error());
    }

    @Test
    void addExportsMakesModuleClassVisibleBySimpleName() {
        var result = run("--module-path", MODULE_JAR.toString(), "--add-modules", "sample.module", "--add-exports",
                "sample.module/modular.internal=ALL-UNNAMED", "modular.internal.HiddenType");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("modular.internal.HiddenType: public class HiddenType"),
                result.output());
    }

    @Test
    void modulePathResolvesFullyQualifiedClassName() {
        var result = run("--module-path", MODULE_JAR.toString(), "--add-modules", "sample.module", "modular.api.ModuleType.act");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("modular.api.ModuleType: public void act()"),
                result.output());
    }

    @Test
    void moduleAndPackageKindsExposeCompiledMetadata() {
        var module = run("--module-path", MODULE_JAR.toString(), "--add-modules", "sample.module", "--source",
                "doc", "--kind", "module", "sample.module");
        var packageResult = run("--module-path", MODULE_JAR.toString(), "--add-modules", "sample.module", "--source",
                "none", "--kind", "package", "modular.api");
        var direct = run("--source", "doc",
                MODULE_CLASSES.resolve("module-info.class").toString());
        var directPackage = run("--source", "doc",
                MODULE_CLASSES.resolve("modular/api/package-info.class").toString());
        var unfiltered = run("--module-path", MODULE_JAR.toString(), "--add-modules", "sample.module", "--source",
                "none", "sample.module");
        var ordered = run(
                "--module-path",
                MODULE_CLASSES.getParent().toString(),
                "--add-modules",
                "sample.module",
                "--limit-modules",
                "java.base",
                "--source",
                "none",
                "-l",
                "-k",
                "module",
                "-k",
                "package",
                "-k",
                "type");

        assertEquals(0, module.exitCode(), module.error());
        assertTrue(module.output().contains("sample.module: module sample.module {"),
                module.output());
        assertTrue(module.output().contains("exports modular.api;"),
                module.output());
        assertEquals(0, packageResult.exitCode(), packageResult.error());
        assertEquals("modular.api: @Deprecated package modular.api;\n", packageResult.output());
        assertEquals(0, direct.exitCode(), direct.error());
        assertTrue(direct.output().contains("sample.module: module sample.module {"),
                direct.output());
        assertEquals(0, directPackage.exitCode(), directPackage.error());
        assertEquals("modular.api: @Deprecated package modular.api;\n", directPackage.output());
        assertEquals(0, unfiltered.exitCode(), unfiltered.error());
        assertTrue(unfiltered.output().contains("sample.module: module sample.module {"),
                unfiltered.output());
        assertEquals(0, ordered.exitCode(), ordered.error());
        assertEquals(List.of("module sample.module", "package modular.api", "class modular.api.ModuleType"),
                ordered.output()
                       .lines()
                       .limit(3)
                       .toList());
    }

    @Test
    void packageKindIsSynthesizedWithoutPackageInfo() {
        var result = run("--class-path", ROOT.resolve("classes").toString(),
                "--source", "none", "--kind", "package", "com.example");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("com.example: package com.example;\n", result.output());
    }

    @Test
    void automaticModuleDescriptorsParticipateInModuleKind() {
        var result = run("--module-path", JAR.toString(), "--add-modules", "example", "--source",
                "none", "--kind", "module", "example");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("example: module example { exports com.example; }\n", result.output());
    }

    @Test
    void argumentFileClasspathResolvesFullyQualifiedClassName() {
        var result = run("@" + SCOPED_ARGS, "--source", "definition", "com.example.Example.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("return amount + 1;"),
                result.output());
    }

    @Test
    void simpleRuntimeClassNameIsNotResolved() {
        var result = run("String.isEmpty");

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("no symbol prefix matches"),
                result.error());
    }

    @Test
    void runtimeImageClassResolvesByName() {
        var result = run("-s", "none", "java.lang.String.isEmpty");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("java.lang.String: public boolean isEmpty() { /* body omitted */ }\n", result.output());
    }

    @Test
    void signatureScopePreservesGenericCallingShape() {
        var result = run("-s", "none", "java.util.Collections.emptyList");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "java.util.Collections: public static final <T> List<T> emptyList() { /* body omitted */ }\n",
                result.output());
    }

    @Test
    void typeSignaturesPreserveGenericCallingShape() {
        var result = run("-s", "none", "-k", "interface", "java.util.List");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "java.util.List: public abstract interface List<E> extends SequencedCollection<E> {\n",
                result.output());
    }

    @Test
    void typeHeadingsIdentifyClassFilesRatherThanDeclarationKinds() {
        var result = run("--heading", "--color", "never", "-s", "none", "-k",
                "interface", "java.util.List");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "class java.util.List\n" + "public abstract interface List<E> extends SequencedCollection<E> {\n",
                result.output());
    }

    @Test
    void signatureScopeIncludesDeclarationAnnotations() {
        var result = run("-s", "none", "java.lang.Thread.stop");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("@Deprecated(since = \"1.2\""),
                result.output());
        assertTrue(result.output().contains("public final void stop()"),
                result.output());
    }

    @Test
    void compiledDeclarationsOmitKotlinCompilerMetadata() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "-s",
                "none",
                "-k",
                "type",
                "com.example.KotlinShaped");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("com.example.KotlinShaped: public class KotlinShaped {\n", result.output());
    }

    @Test
    void compiledLiteralsEscapeControlCharacters() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                ROOT.resolve("classes").toString(),
                "-s",
                "none",
                "com.example.Escaped");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("text = \"\\000\\b\\t\\n\\f\\r\\\"'" + "\\\\\\001\\177\""),
                result.output());
        assertTrue(result.output().contains("character = '\\n'"),
                result.output());
        assertTrue(result.output().contains("String TEXT = \"\\000\\b\\t\\n\\f\\r\\\"'" + "\\\\\\001\\177\";"),
                result.output());
        assertTrue(result.output().contains("char CHARACTER = '\\n';"),
                result.output());
        assertFalse(result.output()
                          .chars()
                          .anyMatch(character -> character != '\n' && Character.isISOControl(character)),
                result.output());
        assertEquals(
                4,
                result.output()
                      .lines()
                      .count(),
                result.output());
    }

    @Test
    void signatureScopeUsesDebugParameterNamesWhenAvailable() {
        var result = run("-s", "none", "java.lang.String.resolveConstantDesc");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("resolveConstantDesc(MethodHandles.Lookup lookup)"),
                result.output());
    }

    @Test
    void explicitModularJdkSourceArchiveProvidesUnitScope() {
        var result = run("--source-path", MODULAR_SYSTEM_SOURCE.toString(),
                "--source", "unit", "java.lang.String.repeat");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("java.lang.String(String.java:3):     /** Repeats this string. */"),
                result.output());
    }

    @Test
    void jarClassQueryListsItsSymbolSubtree() {
        var result = run("--class-path", JAR.toString(), "com.example.Example");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
    }

    @Test
    void classpathJarResolvesFullyQualifiedClassName() {
        var result = run(
                "--class-path",
                JAR.toString(),
                "--source-path",
                ROOT.resolve("src").toString(),
                "--source",
                "definition",
                "com.example.Example.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("return amount + 1;"),
                result.output());
    }

    @Test
    void exactQueryReadsArchiveBackedSource() throws IOException {
        var result = run("--class-path", JAR.toString(), "--source-path", SOURCE_JAR.toString(),
                "--source", "definition", "com.example.Example.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example(Example.java:10):"),
                result.output());
        assertTrue(result.output().contains("return amount + 1;"),
                result.output());
    }

    @Test
    void exactQueryReadsSourceBelowOneArchiveDirectory() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                JAR.toString(),
                "--source-path",
                NESTED_SOURCE_JAR.toString(),
                "--qualified-path",
                "--source",
                "definition",
                "com.example.Example.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("example-nested-sources.jar!/main/com/example/Example.java"),
                result.output());
        assertTrue(result.output().contains("return amount + 1;"),
                result.output());
    }

    @Test
    void exactQueryReadsSourceForSelectedMultiReleaseClass() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                MULTI_RELEASE_JAR.toString(),
                "--source-path",
                MULTI_RELEASE_SOURCE_JAR.toString(),
                "--qualified-path",
                "--source",
                "definition",
                "com.example.Versioned.marker");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("versioned-sources.jar!/META-INF/versions/9/com/example/Versioned.java"),
                result.output());
        assertTrue(result.output().contains("return \"versioned\";"),
                result.output());
        assertFalse(result.output().contains("return \"base\";"),
                result.output());
    }

    @Test
    void exactQueryReadsNonJavaSourceWithoutRenderingItAsJava() throws IOException {
        var result = run("--system", "none", "--class-path", NON_JAVA_JAR.toString(), "--source-path",
                NON_JAVA_SOURCE_JAR.toString(), "--source", "definition", "com.example.Example.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
        assertFalse(result.output().contains("Example.kt:"),
                result.output());
    }

    @Test
    void bodyScopeReadsNonJavaSourceUsingClassFileLineNumbers() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                NON_JAVA_JAR.toString(),
                "--source-path",
                NON_JAVA_SOURCE_JAR.toString(),
                "--qualified-path",
                "--source",
                "body",
                "com.example.Example.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("example-non-java-sources.jar!/com/example/Example.kt:9)"),
                result.output());
        assertTrue(result.output().contains("return amount + 2"),
                result.output());
        assertFalse(result.output().contains("body omitted"),
                result.output());
    }

    @Test
    void unitScopeReadsTheEntireNonJavaSourceFile() {
        var result = run(
                "--system",
                "none",
                "--class-path",
                NON_JAVA_JAR.toString(),
                "--source-path",
                NON_JAVA_SOURCE_JAR.toString(),
                "--qualified-path",
                "--source",
                "unit",
                "com.example.Example");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("example-non-java-sources.jar!/com/example/Example.kt:1): package com.example"),
                result.output());
        assertTrue(result.output().contains("fun run(amount: Int): Int {"),
                result.output());
        assertTrue(result.output().contains("return amount + 2"),
                result.output());
        assertEquals(
                11,
                result.output()
                      .lines()
                      .count(),
                result.output());
    }

    @Test
    void qualifiedPathIdentifiesClassFileFallback() {
        var result = run("--system", "none", "--class-path", JAR.toString(), "--source",
                "none", "--qualified-path", "com.example.Example.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example(jar:file:"),
                result.output());
        assertTrue(result.output().contains("example.jar!/com/example/Example.class): public int run"),
                result.output());
    }

    @Test
    void classFileWithoutSourceStillListsSymbols() {
        var result = run("--class-path", JAR.toString(), "com.example.Example");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example: public int run(int amount)"),
                result.output());
    }

    @Test
    void sourceCanEnrichClassFileDeclarations() {
        var result = run("--source-path", ROOT.resolve("src").toString(),
                "--source", "unit", CLASS.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Example(Example.java:1): package com.example;"),
                result.output());
        assertTrue(result.output().contains("return amount + 1;"),
                result.output());
    }

    private static Result run(String... args) {
        var output = new StringWriter();
        var error = new StringWriter();
        int exitCode = new Jist().run(new PrintWriter(output), new PrintWriter(error), args);
        return new Result(exitCode, output.toString(), error.toString());
    }

    private record Result(int exitCode, String output, String error) {}
}
