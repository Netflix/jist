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
import java.util.HashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Jist;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolSelectionTest {
    private static final Path ROOT = Path.of("build/test-symbol-selection");
    private static final Path SOURCES = ROOT.resolve("src");
    private static final Path CLASSES = ROOT.resolve("classes");
    private static final Path ARCHIVE = ROOT.resolve("sources.jar");
    private static final Path UNRELATED = ROOT.resolve("unrelated");

    @BeforeAll
    static void fixture() throws Exception {
        Files.createDirectories(SOURCES.resolve("sample"));
        Files.createDirectories(CLASSES);
        Files.createDirectories(UNRELATED.resolve("broken"));
        Files.write(UNRELATED.resolve("broken/Unrelated.class"), new byte[] {0});
        Files.writeString(SOURCES.resolve("sample/Companions.java"), """
                package sample;
                class CompanionSelection { String value; }
                class \\u0045scapedSelection { String value; }
                """);
        Files.writeString(SOURCES.resolve("sample/Dollar$Selection.java"), """
                package sample;
                public class Dollar$Selection { String value; }
                """);
        Files.writeString(SOURCES.resolve("sample/ResolutionOwner.java"), """
                package sample;
                public class ResolutionOwner { String unrelated; }
                """);
        Files.createDirectories(SOURCES.resolve("alternate/sample"));
        Files.writeString(SOURCES.resolve("alternate/sample/ResolutionOwner.java"), """
                package alternate.sample;
                public class ResolutionOwner {
                    public static class ResolutionLeaf extends java.util.ArrayList<String> {
                        java.math.BigInteger actual;
                    }
                }
                """);
        Files.writeString(SOURCES.resolve("sample/SelectionBase.java"), """
                package sample;
                public interface SelectionBase {
                    void perform();
                }
                """);
        Files.writeString(SOURCES.resolve("sample/SelectionSubject.java"), """
                package sample;
                /** Subject documentation. */
                public class SelectionSubject implements SelectionBase {
                    /** Value documentation. */
                    public static int VALUE;
                    public SelectionSubject() {}
                    /** Perform documentation. */
                    public void perform() { System.out.println(VALUE); }
                    public void perform(int count) { perform(); }
                    public String dollar$Work() { return "value"; }
                    public static class NestedSelection {
                        public String nestedWork() { return "nested"; }
                    }
                }
                """);
        Files.writeString(SOURCES.resolve("sample/SelectionChild.java"), """
                package sample;
                public class SelectionChild extends SelectionSubject {}
                """);
        Files.writeString(SOURCES.resolve("sample/SelectionConsumer.java"), """
                package sample;
                public class SelectionConsumer {
                    public void invoke(SelectionSubject value) {
                        value.perform();
                        value.perform(1);
                        int copy = SelectionSubject.VALUE;
                    }
                }
                """);
        for (var namespace : List.of("alpha", "beta")) {
            Files.createDirectories(SOURCES.resolve(namespace));
            Files.writeString(SOURCES.resolve(namespace + "/DuplicateSelection.java"),
                    "package " + namespace + "; public class DuplicateSelection { public int VALUE; }");
        }
        Files.writeString(SOURCES.resolve("sample/DuplicateConsumer.java"), """
                package sample;
                public class DuplicateConsumer {
                    public int use(alpha.DuplicateSelection first, beta.DuplicateSelection second) {
                        return first.VALUE + second.VALUE;
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-g", "-d", CLASSES.toString()), null,
                    manager.getJavaFileObjectsFromPaths(sourceFiles())).call());
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(ARCHIVE))) {
            for (var file : sourceFiles()) {
                jar.putNextEntry(new JarEntry(SOURCES.relativize(file).toString().replace(File.separatorChar, '/')));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
    }

    private static List<Path> sourceFiles() throws Exception {
        try (var files = Files.walk(SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    static Stream<Arguments> selections() {
        return Stream.of("source", "attached", "compiled").flatMap(input -> Stream.of(
                Arguments.of(input, List.of("--kind", "method", "--kind", "field", "--source", "doc"),
                        "SelectionSubject.perform", "sample.SelectionSubject.perform", List.of("perform")),
                Arguments.of(input, List.of("--kind", "method", "--kind", "field", "--source", "doc"),
                        "SelectionSubject.VALUE", "sample.SelectionSubject.VALUE", List.of("VALUE")),
                Arguments.of(input, List.of("-l", "--show-origin", "--source", "none"),
                        "SelectionSubject.VALUE", "sample.SelectionSubject.VALUE", List.of("VALUE")),
                Arguments.of(input, List.of("--usages"),
                        "SelectionSubject", "sample.SelectionSubject", List.of("SelectionConsumer")),
                Arguments.of(input, List.of("--usages"),
                        "SelectionSubject.perform", "sample.SelectionSubject.perform", List.of("SelectionConsumer")),
                Arguments.of(input, List.of("--usages"),
                        "SelectionSubject.VALUE", "sample.SelectionSubject.VALUE", List.of("SelectionConsumer")),
                Arguments.of(input, List.of("--callers"),
                        "SelectionChild.perform", "sample.SelectionChild.perform", List.of("SelectionConsumer")),
                Arguments.of(input, List.of("--supertypes"),
                        "SelectionSubject", "sample.SelectionSubject", List.of("SelectionBase")),
                Arguments.of(input, List.of("--subtypes"),
                        "SelectionBase", "sample.SelectionBase", List.of("SelectionSubject")),
                Arguments.of(input, List.of("--overrides"),
                        "SelectionBase.perform", "sample.SelectionBase.perform", List.of("SelectionSubject")),
                Arguments.of(input, List.of("--callers"),
                        "SelectionSubject.perform", "sample.SelectionSubject.perform", List.of("SelectionConsumer")),
                Arguments.of(input, List.of("--callees"),
                        "SelectionSubject.perform", "sample.SelectionSubject.perform", List.of("println")),
                Arguments.of(input, List.of("-l", "--kind", "method", "--source", "none"),
                        "SelectionSubject.dollar$Work", "sample.SelectionSubject.dollar$Work", List.of("dollar$Work")),
                Arguments.of(input, List.of("--referenced-types"),
                        "SelectionSubject", "sample.SelectionSubject", List.of("java.lang.System", "sample.SelectionBase")),
                Arguments.of(input, List.of("--referenced-types"),
                        "SelectionSubject.NestedSelection", "sample.SelectionSubject.NestedSelection", List.of("java.lang.String")),
                Arguments.of(input, List.of("--kind", "method", "--source", "definition"),
                        "SelectionSubject.new", "sample.SelectionSubject.new", List.of("SelectionSubject()")),
                Arguments.of(input, List.of("--kind", "method", "--source", "definition"),
                        "SelectionSubject.NestedSelection.nestedWork", "sample.SelectionSubject.NestedSelection.nestedWork",
                        List.of("nestedWork"))));
    }

    @ParameterizedTest
    @MethodSource("selections")
    void simpleAndQualifiedSubjectsHaveTheSameSemanticResults(String input, List<String> options,
            String simple, String qualified, List<String> expected) {
        var exact = run(input, options, qualified);
        assertEquals(0, exact.status(), exact.error());
        for (var fragment : expected) {
            assertTrue(exact.output().contains(fragment), fragment + "\n" + exact.output());
        }
        var discovered = run(input, options, simple);
        assertEquals(0, discovered.status(), discovered.error());
        assertEquals(exact.output(), discovered.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void packageDescriptionsUseTheDeclaredPackage(String input) {
        var result = run(input, List.of("--kind", "package"), "sample");
        assertEquals(0, result.status(), result.error());
        assertEquals("sample: package sample;\nalternate.sample: package alternate.sample;\n", result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void sourceFreeTypeDescriptionsContainTheirApi(String input) {
        var result = run(input, List.of("--source", "none", "--kind", "type", "--kind", "method", "--kind", "field"),
                "sample.SelectionSubject");
        assertEquals(0, result.status(), result.error());
        for (var fragment : List.of("SelectionSubject", "perform", "VALUE", "NestedSelection")) {
            assertTrue(result.output().contains(fragment), fragment + "\n" + result.output());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void ambiguousUsagesAreAUnionWithoutDuplicateRows(String input) {
        for (var target : List.of("DuplicateSelection", "DuplicateSelection.VALUE")) {
            var expected = new HashSet<String>();
            for (var namespace : List.of("alpha", "beta")) {
                var exact = run(input, List.of("--usages"), namespace + "." + target);
                assertEquals(0, exact.status(), exact.error());
                expected.addAll(exact.output().lines().toList());
            }
            var result = run(input, List.of("--usages"), target);
            assertEquals(0, result.status(), result.error());
            assertTrue(result.output().contains("DuplicateConsumer"), result.output());
            assertEquals(expected, new HashSet<>(result.output().lines().toList()));
            assertEquals(expected.size(), result.output().lines().count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CompanionSelection", "EscapedSelection"})
    void resolvesSecondarySourceTypesWithLiteralOrEscapedNames(String name) {
        var result = run("source", List.of("--referenced-types"), "sample." + name);
        assertEquals(0, result.status(), result.error());
        assertTrue(result.output().contains("class java.lang.String"), result.output());
    }

    @ParameterizedTest
    @CsvSource({
            "time.Clock, class java.time.Instant",
            "util.Map.Entry, interface java.util.Comparator"
    })
    void resolvesSystemTypeSuffixesIncludingNestedTypes(String target, String expected) {
        var result = run("source", List.of("--referenced-types"), target);
        assertEquals(0, result.status(), result.error());
        assertTrue(result.output().contains(expected), result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void typeOnlySelectionsResolveNestedSuffixesInsteadOfMembers(String input) {
        for (var options : List.of(List.of("--referenced-types"), List.of("--supertypes"),
                List.of("--kind", "type", "--source", "signature"))) {
            var exact = run(input, options, "alternate.sample.ResolutionOwner.ResolutionLeaf");
            assertEquals(0, exact.status(), exact.error());
            assertTrue(exact.output().contains(options.contains("--referenced-types") ? "java.math.BigInteger" : "ArrayList"),
                    exact.output());
            var result = run(input, options, "sample.ResolutionOwner.ResolutionLeaf");
            assertEquals(0, result.status(), result.error());
            assertEquals(exact.output(), result.output());
        }
    }

    @Test
    void resolvesSourceTypeNamesContainingDollarSigns() {
        var exact = run("source", List.of("--referenced-types"), "sample.Dollar$Selection");
        assertEquals(0, exact.status(), exact.error());
        assertTrue(exact.output().contains("class java.lang.String"), exact.output());
        var result = run("source", List.of("--referenced-types"), "Dollar$Selection");
        assertEquals(0, result.status(), result.error());
        assertEquals(exact.output(), result.output());
    }

    static Stream<Arguments> narrowSelections() {
        return Stream.of("source", "attached", "compiled").flatMap(input -> Stream.of(
                Arguments.of(input, List.of("--kind", "method", "--source", "definition"),
                        "SelectionSubject.perform", "sample.SelectionSubject.perform", "perform"),
                Arguments.of(input, List.of("--referenced-types"),
                        "SelectionSubject.NestedSelection", "sample.SelectionSubject.NestedSelection", "java.lang.String"),
                Arguments.of(input, List.of("--supertypes"),
                        "SelectionSubject", "sample.SelectionSubject", "SelectionBase")));
    }

    @ParameterizedTest
    @MethodSource("narrowSelections")
    void selectedSymbolsResolveWithoutParsingUnrelatedClasses(String input, List<String> options,
            String suffix, String qualified, String expected) {
        var exact = run(input, options, qualified, UNRELATED);
        assertEquals(0, exact.status(), exact.error());
        assertTrue(exact.output().contains(expected), exact.output());
        var result = run(input, options, suffix, UNRELATED);
        assertEquals(0, result.status(), result.error());
        assertEquals(exact.output(), result.output());
    }

    private static Result run(String input, List<String> options, String target) {
        return run(input, options, target, null);
    }

    private static Result run(String input, List<String> options, String target, Path extraClasses) {
        var args = new ArrayList<>(List.of("--exclude-system-modules"));
        var classes = new ArrayList<String>();
        switch (input) {
            case "source" -> args.addAll(List.of("--source-path", SOURCES.toString()));
            case "attached" -> {
                classes.add(CLASSES.toString());
                args.addAll(List.of("--source-path", ARCHIVE.toString()));
            }
            case "compiled" -> classes.add(CLASSES.toString());
            default -> throw new IllegalArgumentException(input);
        }
        if (extraClasses != null) classes.add(extraClasses.toString());
        if (!classes.isEmpty()) args.addAll(List.of("--class-path", String.join(File.pathSeparator, classes)));
        args.addAll(options);
        args.add(target);
        var output = new StringWriter();
        var error = new StringWriter();
        int status = new Jist().run(new PrintWriter(output), new PrintWriter(error), args.toArray(String[]::new));
        return new Result(status, output.toString(), error.toString());
    }

    private record Result(int status, String output, String error) {}
}
