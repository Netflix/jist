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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Jist;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageQueryTest {

    private static final Path ROOT = Path.of("build/test-usage-query");
    private static final Path SOURCE_ROOT = ROOT.resolve("src");
    private static final Path CLASSES = ROOT.resolve("classes");
    private static final Path COMPILED_JAR = ROOT.resolve("usage.jar");
    private static final Path COMPILED_SOURCE_JAR = ROOT.resolve("usage-sources.jar");
    private static final Path TARGET = SOURCE_ROOT.resolve("com/example/Target.java");
    private static final Path CONSUMER = SOURCE_ROOT.resolve("com/example/Consumer.java");
    private static final Path INHERITED = SOURCE_ROOT.resolve("com/example/Inherited.java");
    private static final Path STATIC_USE = SOURCE_ROOT.resolve("com/example/StaticUse.java");
    private static final Path OVERRIDING = SOURCE_ROOT.resolve("com/example/Overriding.java");
    private static final Path SIGNATURE_ONLY = SOURCE_ROOT.resolve("com/example/SignatureOnly.java");
    private static final Path STATUS = SOURCE_ROOT.resolve("com/example/Status.java");
    private static final Path NESTED_USE = SOURCE_ROOT.resolve("com/example/NestedUse.java");
    private static final Path PENDING = SOURCE_ROOT.resolve("com/example/Pending.java");
    private static final Path PENDING_USE = SOURCE_ROOT.resolve("com/example/PendingUse.java");

    @BeforeAll
    static void compileSources() throws Exception {
        Files.createDirectories(TARGET.getParent());
        Files.createDirectories(CLASSES);
        Files.writeString(TARGET,
                """
                package com.example;

                public class Target {
                    public static int FIELD;
                    public Target() {}
                    public Target(int value) {}
                    public void run() {}
                    public void run(int value) {}
                    public static String make() { return ""; }
                    public void nestedOnly() {}
                    public void unused() {}
                }
                """);
        Files.writeString(CONSUMER,
                """
                package com.example;

                public class Consumer {
                    Target field = new Target();

                    /** Calls the target. */
                    void call(Target target) {
                        target.run();
                        target.run(1);
                        int value = Target.FIELD;
                        Target numbered = new Target(1);
                        new Other().run();
                        Runnable reference = target::run;
                        java.util.function.Supplier<Target> constructor = Target::new;
                        String ignored = "target.run()";
                    }

                    void twice(Target target) { target.run(); target.run(); }
                    void mixed(Target target) {
                        target.run(); new Other().run(); String text = "run";
                    }
                }

                class Other {
                    void run() {}
                }
                """);
        Files.writeString(INHERITED,
                """
                package com.example;

                class Inherited extends Target {
                    void callInherited() {
                        run();
                    }
                }
                """);
        Files.writeString(STATIC_USE,
                """
                package com.example;

                import static com.example.Target.FIELD;

                class StaticUse {
                    int value = FIELD;
                    static final String SMALL = Target.make();
                    static final String LARGE = Target.make();
                }
                """);
        Files.writeString(OVERRIDING,
                """
                package com.example;

                class Overriding extends Target {
                    @Override
                    public void run() {}

                    void invokeOverride() {
                        run();
                    }
                }
                """);
        Files.writeString(SIGNATURE_ONLY,
                """
                package com.example;

                class SignatureOnly {
                    Target field;

                    Target accept(Target value) {
                        return value;
                    }
                }
                """);
        Files.writeString(STATUS,
                """
                package com.example;
                enum Status { ON }
                """);
        Files.writeString(NESTED_USE,
                """
                package com.example;
                class NestedUse {
                    static class Nested {
                        void call(Target target) {
                            target.nestedOnly();
                        }
                    }
                }
                """);

        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
            var sources = fileManager.getJavaFileObjects(TARGET, CONSUMER, INHERITED, STATIC_USE, OVERRIDING, SIGNATURE_ONLY,
                    STATUS, NESTED_USE);
            assertTrue(compiler.getTask(null, fileManager, null, List.of("-d", CLASSES.toString()), null,
                                       sources)
                               .call());
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(COMPILED_JAR));
             var files = Files.walk(CLASSES)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(CLASSES.relativize(file)
                        .toString()
                        .replace(File.separatorChar, '/')));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(COMPILED_SOURCE_JAR))) {
            for (var source : List.of(TARGET, CONSUMER, INHERITED, STATIC_USE, OVERRIDING, SIGNATURE_ONLY,
                    STATUS, NESTED_USE)) {
                jar.putNextEntry(new JarEntry(SOURCE_ROOT.relativize(source)
                        .toString()
                        .replace(File.separatorChar, '/')));
                jar.write(Files.readAllBytes(source));
                jar.closeEntry();
            }
        }
        Files.writeString(PENDING,
                """
                package com.example;
                class Pending {
                    void work() {}
                }
                """);
        Files.writeString(PENDING_USE,
                """
                package com.example;
                class PendingUse {
                    void use(Pending pending) {
                        pending.work();
                    }
                }
                """);
    }

    @Test
    void reportsEverySemanticMethodOccurrence() throws Exception {
        var result = run("--usages", "--class-path", CLASSES.toString(), "--source-path",
                SOURCE_ROOT.toString(), "com.example.Target.run");

        assertEquals(0, result.exitCode(), result.error());
        var lines = usageRows(result.output());
        assertEquals(8, lines.size(), result.output());
        assertTrue(
                lines.contains(row("com.example.Consumer.call", CONSUMER, lineContaining(CONSUMER, "target.run();"))),
                result.output());
        assertTrue(
                lines.contains(row("com.example.Consumer.call", CONSUMER, lineContaining(CONSUMER, "target.run(1);"))),
                result.output());
        assertTrue(
                lines.contains(row("com.example.Consumer.call", CONSUMER, lineContaining(CONSUMER, "target::run"))),
                result.output());
        assertTrue(
                lines.contains(row("com.example.Inherited.callInherited", INHERITED, lineContaining(INHERITED, "run();"))),
                result.output());
        assertEquals(
                1,
                lines.stream()
                        .filter(row("com.example.Consumer.twice", CONSUMER, lineContaining(CONSUMER, "void twice"))::equals)
                        .count(),
                result.output());
        assertTrue(
                lines.contains(row("com.example.Overriding.run", OVERRIDING, lineContaining(OVERRIDING, "public void run"))),
                result.output());
        assertTrue(
                lines.contains(row("com.example.Overriding.invokeOverride", OVERRIDING, lineContaining(OVERRIDING, "run();"))),
                result.output());
        assertFalse(result.output().contains("ignored"),
                result.output());
    }

    @Test
    void sourceOccurrencesUseUnitCoordinatesWithoutHeadings() throws Exception {
        var result = run("--usages", "--class-path", CLASSES.toString(), "--source-path",
                SOURCE_ROOT.toString(), "com.example.Target.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Consumer(Consumer.java:" + lineContaining(CONSUMER, "target.run();") + "):         target.run();\n"),
                result.output());
        assertFalse(result.output().contains("class: "),
                result.output());
        assertFalse(result.output().contains("source: "),
                result.output());
    }

    @Test
    void colorHighlightsUsageNames() {
        var result = run("--usages", "--color", "always", "--no-heading", "--class-path",
                CLASSES.toString(), "--source-path", SOURCE_ROOT.toString(), "com.example.Target.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("target.\033[1;31mrun\033[0m();" + " new Other().run(); String text = \"run\";"),
                result.output());
        assertFalse(result.output().contains("Other().\033[1;31mrun\033[0m()"),
                result.output());
        assertFalse(result.output().contains("\"\033[1;31mrun\033[0m\""),
                result.output());
        assertTrue(result.output().contains("target.\033[1;31mrun\033[0m();" + " target.\033[1;31mrun\033[0m();"),
                result.output());
        assertTrue(result.output().contains("public void \033[1;31mrun\033[0m() {}"),
                result.output());
    }

    @Test
    void constructorHighlightingUsesAttributedNewTokens() {
        var result = run("--usages", "--color", "always", "--no-heading", "--class-path",
                CLASSES.toString(), "--source-path", SOURCE_ROOT.toString(), "com.example.Target.new");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("\033[1;31mnew\033[0m Target()"),
                result.output());
        assertTrue(result.output().contains("Target::\033[1;31mnew\033[0m"),
                result.output());
    }

    @Test
    void widenedSourceScopesHighlightOnlyAttributedOccurrences() {
        var result = run(
                "--usages",
                "--source",
                "unit",
                "--color",
                "always",
                "--no-heading",
                "--class-path",
                CLASSES.toString(),
                "--source-path",
                SOURCE_ROOT.toString(),
                "com.example.Target.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("target.\033[1;31mrun\033[0m();" + " new Other().run(); String text = \"run\";"),
                result.output());
        assertTrue(result.output().contains("new Other().run();"),
                result.output());
        assertFalse(result.output().contains("Other().\033[1;31mrun\033[0m()"),
                result.output());
        assertFalse(result.output().contains("\"\033[1;31mrun\033[0m\""),
                result.output());
    }

    @Test
    void sourceScopesWidenUsageLines() throws Exception {
        var definition = run("--usages", "--source", "definition", "--class-path", CLASSES.toString(),
                "--source-path", SOURCE_ROOT.toString(), "com.example.Target.run");
        var type = run("--usages", "--source", "type", "--class-path", CLASSES.toString(),
                "--source-path", SOURCE_ROOT.toString(), "com.example.Target.run");
        var unit = run("--usages", "--source", "unit", "--class-path", CLASSES.toString(),
                "--source-path", SOURCE_ROOT.toString(), "com.example.Target.run");

        assertEquals(0, definition.exitCode(), definition.error());
        assertFalse(definition.output().contains("/** Calls the target. */"),
                definition.output());
        assertTrue(definition.output().contains("void call(Target target) {"),
                definition.output());
        assertFalse(definition.output().contains("package com.example;"),
                definition.output());

        assertEquals(0, type.exitCode(), type.error());
        assertTrue(type.output().contains("public class Consumer {"),
                type.output());
        assertFalse(type.output().contains("class Other"),
                type.output());
        assertFalse(type.output().contains("package com.example;"),
                type.output());

        assertEquals(0, unit.exitCode(), unit.error());
        assertTrue(unit.output().contains("package com.example;"),
                unit.output());
        assertTrue(unit.output().contains("class Other"),
                unit.output());
    }

    @Test
    void bodyScopeKeepsUsageOutputAtTheClassFileLine() throws Exception {
        var result = run("--usages", "--source", "body", "--class-path", CLASSES.toString(),
                "--source-path", SOURCE_ROOT.toString(), "com.example.Target.nestedOnly");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                List.of(row("com.example.NestedUse.Nested.call", NESTED_USE, lineContaining(NESTED_USE, "target.nestedOnly()"))),
                usageRows(result.output()));
    }

    @Test
    void reportsCompiledMethodUsagesWithoutSourceInputs() throws Exception {
        var result = run("--usages", "--class-path", CLASSES.toString(), "com.example.Target.run");

        assertEquals(0, result.exitCode(), result.error());
        var rows = usageRows(result.output()).stream()
                .filter(line -> line.startsWith("com.example.Consumer\t"))
                .toList();
        assertEquals(5, rows.size(), result.output());
        assertTrue(
                rows.contains("com.example.Consumer\t" + lineContaining(CONSUMER, "target.run();")),
                result.output());
        assertEquals(
                1,
                rows.stream()
                        .filter(("com.example.Consumer\t" + lineContaining(CONSUMER, "void twice"))::equals)
                        .count(),
                result.output());
        var allRows = usageRows(result.output());
        assertTrue(
                allRows.contains("com.example.Inherited\t" + lineContaining(INHERITED, "run();")),
                result.output());
        assertTrue(
                allRows.contains("com.example.Overriding\t" + lineContaining(OVERRIDING, "public void run")),
                result.output());
        assertTrue(
                allRows.contains("com.example.Overriding\t" + lineContaining(OVERRIDING, "run();")),
                result.output());
    }

    @Test
    void compiledOnlyUsagesAreNotTextuallyHighlighted() {
        var result = run("--usages", "--color", "always", "--no-heading", "--class-path",
                CLASSES.toString(), "com.example.Target.run");

        assertEquals(0, result.exitCode(), result.error());
        assertFalse(result.output().contains("\033[1;31m"),
                result.output());
    }

    @Test
    void reportsCompiledTypeUsagesFromDeclarationDescriptors() throws Exception {
        var result = run("--usages", "--class-path", CLASSES.toString(), "com.example.Target");

        assertEquals(0, result.exitCode(), result.error());
        var rows = usageRows(result.output());
        assertTrue(
                rows.contains("com.example.SignatureOnly\tcom.example.SignatureOnly.field"),
                result.output());
        assertTrue(rows.stream().anyMatch(row -> row.startsWith("com.example.SignatureOnly\t") && !row.endsWith("SignatureOnly.field")),
                result.output());
    }

    @Test
    void readsArchiveSourceForConfirmedCompiledUsages() throws Exception {
        var result = run("--usages", "--system", "none", "--class-path", COMPILED_JAR.toString(),
                "--source-path", COMPILED_SOURCE_JAR.toString(), "com.example.Target.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.Consumer(Consumer.java:"),
                result.output());
        assertTrue(result.output().contains("target.run();"),
                result.output());
    }

    @Test
    void archiveSourceIsAttributedBeforeHighlighting() {
        var result = run(
                "--usages",
                "--color",
                "always",
                "--no-heading",
                "--system",
                "none",
                "--class-path",
                COMPILED_JAR.toString(),
                "--source-path",
                COMPILED_SOURCE_JAR.toString(),
                "com.example.Target.run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("target.\033[1;31mrun\033[0m();" + " new Other().run(); String text = \"run\";"),
                result.output());
        assertFalse(result.output().contains("Other().\033[1;31mrun\033[0m()"),
                result.output());
        assertFalse(result.output().contains("\"\033[1;31mrun\033[0m\""),
                result.output());
    }

    @Test
    void definitionScopeUsesFieldsReferencedFromAClassInitializer() throws Exception {
        var result = run(
                "--usages",
                "--source",
                "definition",
                "--system",
                "none",
                "--class-path",
                COMPILED_JAR.toString(),
                "--source-path",
                COMPILED_SOURCE_JAR.toString(),
                "com.example.Target.make");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "com.example.StaticUse(StaticUse.java:7):" + "     static final String SMALL = Target.make();\n" + "com.example.StaticUse(StaticUse.java:8):" + "     static final String LARGE = Target.make();\n",
                result.output());
    }

    @Test
    void recoversDeclarationLinesFromAttachedSource() throws Exception {
        var result = run("--usages", "--system", "none", "--class-path", COMPILED_JAR.toString(),
                "--source-path", COMPILED_SOURCE_JAR.toString(), "com.example.Target");

        assertEquals(0, result.exitCode(), result.error());
        var rows = usageRows(result.output());
        var fieldLine = lineContaining(SIGNATURE_ONLY, "Target field");
        var classLine = lineContaining(OVERRIDING, "class Overriding");
        assertTrue(rows.stream().anyMatch(row -> row.equals("com.example.SignatureOnly\t" + fieldLine)),
                result.output());
        assertTrue(rows.stream().anyMatch(row -> row.equals("com.example.Overriding\t" + classLine)),
                result.output());
    }

    @Test
    void suppressesCompilerGeneratedEnumMembers() {
        var result = run("--usages", "--class-path", CLASSES.toString(), "java.lang.String");

        assertEquals(0, result.exitCode(), result.error());
        assertFalse(usageRows(result.output()).stream().anyMatch(row -> row.contains("com.example.Status.valueOf") || row.contains("com.example.Status.new")),
                result.output());
    }

    @Test
    void nestedClassUsesItsSourceFileAttribute() {
        var result = run("--usages", "--class-path", CLASSES.toString(), "--source-path",
                SOURCE_ROOT.toString(), "com.example.Target.nestedOnly");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("com.example.NestedUse(NestedUse.java:5):"),
                result.output());
    }

    @Test
    void reportsFieldUsageFromStaticImportWithoutReportingTheImport() throws Exception {
        var result = run("--usages", "--class-path", CLASSES.toString(), "--source-path",
                SOURCE_ROOT.toString(), "com.example.Target.FIELD");

        assertEquals(0, result.exitCode(), result.error());
        var rows = usageRows(result.output());
        assertTrue(
                rows.contains(row("com.example.Consumer.call", CONSUMER, lineContaining(CONSUMER, "Target.FIELD"))),
                result.output());
        assertTrue(
                rows.contains(row("com.example.StaticUse.value", STATIC_USE, lineContaining(STATIC_USE, "value = FIELD"))),
                result.output());
        var importLine = lineContaining(STATIC_USE, "import static");
        assertFalse(rows.stream().anyMatch(row -> row.endsWith(":" + importLine)),
                result.output());
    }

    @Test
    void reportsConstructorOccurrences() throws Exception {
        var result = run("--usages", "--class-path", CLASSES.toString(), "--source-path",
                SOURCE_ROOT.toString(), "com.example.Target.new");

        assertEquals(0, result.exitCode(), result.error());
        var lines = usageRows(result.output());
        assertEquals(5, lines.size(), result.output());
        assertTrue(
                lines.contains(row("com.example.Overriding.new", OVERRIDING, lineContaining(OVERRIDING, "class Overriding"))),
                result.output());
        assertTrue(
                lines.contains(row("com.example.Inherited.new", INHERITED, lineContaining(INHERITED, "class Inherited"))),
                result.output());
        assertTrue(
                lines.contains(row("com.example.Consumer.field", CONSUMER, lineContaining(CONSUMER, "new Target()"))),
                result.output());
        assertTrue(
                lines.contains(row("com.example.Consumer.call", CONSUMER, lineContaining(CONSUMER, "new Target(1)"))),
                result.output());
        assertTrue(
                lines.contains(row("com.example.Consumer.call", CONSUMER, lineContaining(CONSUMER, "Target::new"))),
                result.output());
    }

    @Test
    void reportsClassUsagesIncludingInheritance() throws Exception {
        var result = run("--usages", "--class-path", CLASSES.toString(), "--source-path",
                SOURCE_ROOT.toString(), "com.example.Target");

        assertEquals(0, result.exitCode(), result.error());
        var rows = usageRows(result.output());
        assertTrue(
                rows.contains(row("com.example.Consumer.field", CONSUMER, lineContaining(CONSUMER, "Target field"))),
                result.output());
        assertTrue(
                rows.contains(row("com.example.Inherited", INHERITED, lineContaining(INHERITED, "extends Target"))),
                result.output());
        assertTrue(
                rows.contains(row("com.example.Overriding", OVERRIDING, lineContaining(OVERRIDING, "extends Target"))),
                result.output());
        assertFalse(
                result.output().contains(TARGET.toAbsolutePath()
                        .normalize()
                        .toString()),
                result.output());
    }

    @Test
    void zeroUsagesSucceedsWithEmptyOutput() {
        var result = run("--usages", "--class-path", CLASSES.toString(), "--source-path",
                SOURCE_ROOT.toString(), "com.example.Target.unused");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("", result.output());
    }

    @Test
    void sourceOnlyMemberCanBeUsedAsTheExactTarget() throws Exception {
        var result = run("--usages", "--class-path", CLASSES.toString(), "--source-path",
                SOURCE_ROOT.toString(), "com.example.Pending.work");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                List.of(row("com.example.PendingUse.use", PENDING_USE, lineContaining(PENDING_USE, "pending.work()"))),
                usageRows(result.output()));
    }

    @Test
    void missingUsageTargetFails() {
        var result = run("--usages", "--class-path", CLASSES.toString(), "--source-path",
                SOURCE_ROOT.toString(), "com.example.Target.missing");

        assertEquals(1, result.exitCode());
        assertEquals("", result.output());
        assertTrue(result.error().contains("Member not found: com.example.Target.missing"),
                result.error());
    }

    @Test
    void usagesRequiresAnExactTarget() {
        var result = run("--usages");

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("--usages requires an exact symbol"),
                result.error());
    }

    @Test
    void usageSymbolsMustBeFullyQualified() {
        var result = run("--usages", "--class-path", CLASSES.toString(), "Target.run");

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Class not found: Target.run"),
                result.error());
    }

    @Test
    void usagesRejectsSourceSignatureOutput() {
        var signature = run("--usages", "--source", "signature", "--class-path", CLASSES.toString(),
                "com.example.Target.run");
        var documentation = run("--usages", "--source", "doc", "--class-path", CLASSES.toString(),
                "com.example.Target.run");

        assertEquals(1, signature.exitCode());
        assertTrue(signature.error().contains("--usages accepts source body, definition, type, or unit"),
                signature.error());
        assertEquals(1, documentation.exitCode());
        assertTrue(documentation.error().contains("--usages accepts source body, definition, type, or unit"),
                documentation.error());
    }

    @Test
    void usagesRejectsSymbolKindFilters() {
        var result = run("--usages", "--kind", "method", "Target.run");

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("--usages and --kind are mutually exclusive"),
                result.error());
    }

    private static int lineContaining(Path source, String text) throws Exception {
        var lines = Files.readAllLines(source);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i + 1;
            }
        }
        throw new AssertionError("Missing line containing: " + text);
    }

    private static String row(String enclosingTarget, Path source, int line) {
        var fileName = source.getFileName().toString();
        var typeName = fileName.substring(0, fileName.lastIndexOf('.'));
        return "com.example." + typeName + "\t" + line;
    }

    private static List<String> usageRows(String output) {
        var result = new ArrayList<String>();
        for (var outputLine : output.lines().toList()) {
            var occurrence = Pattern.compile("^(.+)\\(([^:]+):(\\d+)\\):").matcher(outputLine);
            if (occurrence.find()) {
                result.add(occurrence.group(1) + "\t" + occurrence.group(3));
                continue;
            }
            int separator = outputLine.indexOf(": ");
            if (separator < 0) {
                throw new AssertionError("Invalid usage output: " + outputLine);
            }
            result.add(outputLine.substring(0, separator) + "\t" + outputLine.substring(separator + 2));
        }
        return List.copyOf(result);
    }

    private static Result run(String... args) {
        var output = new StringWriter();
        var error = new StringWriter();
        int exitCode = new Jist().run(new PrintWriter(output), new PrintWriter(error), args);
        return new Result(exitCode, output.toString(), error.toString());
    }

    private record Result(int exitCode, String output, String error) {}
}
