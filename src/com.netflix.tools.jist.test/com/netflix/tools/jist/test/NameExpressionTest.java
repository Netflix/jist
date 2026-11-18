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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Jist;
import com.netflix.tools.json.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameExpressionTest {
    @TempDir
    static Path root;
    private static Path sources;
    private static Path classes;
    private static Path archive;

    @BeforeAll
    static void fixture() throws Exception {
        sources = Files.createDirectories(root.resolve("src/sample"));
        classes = Files.createDirectories(root.resolve("classes"));
        var owner = sources.resolve("Counter.java");
        Files.writeString(owner, """
                package sample;
                public class Counter {
                    public int itemCount;
                    public Counter() {}
                    public int getItemCount() { return itemCount; }
                    public int getItemCount(int offset) { return itemCount + offset; }
                    public int GetItemCount() { return itemCount; }
                    public int unrelated() { return getItemCount(); }
                    public static class CountEntry {
                        public void nestedWork() {}
                    }
                    public static class countEntry {}
                    public record CountRecord(int value) {}
                }
                """);
        var other = sources.resolve("Other.java");
        Files.writeString(other, """
                package sample;
                public class Other {
                    public int getItemCount() { return 1; }
                    public Counter unrelated(Counter input) { return input; }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-g", "-d", classes.toString(),
                owner.toString(), other.toString()));
        archive = root.resolve("sources.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(archive))) {
            for (var file : List.of(owner, other)) {
                jar.putNextEntry(new JarEntry("sample/" + file.getFileName()));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
        sources = sources.getParent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void expressionsSearchOwnNamesAndPreserveOverloads(String input) {
        var result = run(input, "-l", "-e", "ItemCount");
        assertEquals(List.of("method sample.Counter.GetItemCount()", "method sample.Counter.getItemCount()",
                "method sample.Counter.getItemCount(int)", "method sample.Other.getItemCount()"), result.lines());
        assertEquals(List.of(), run(input, "-l", "--kind", "method", "-e", "Counter").lines());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void expressionsComposeWithQualifiedOwnerAndKind(String input) {
        assertEquals(List.of("method sample.Counter.getItemCount()", "method sample.Counter.getItemCount(int)"),
                run(input, "-l", "--kind", "method", "sample.Counter", "-e", "^get.*Count$").lines());
        assertEquals(List.of("field sample.Counter.itemCount"),
                run(input, "-l", "--kind", "field", "sample.Counter", "-e", "Count$").lines());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void ignoreCasePreservesCanonicalIdentities(String input) {
        assertEquals(List.of("class sample.Counter.CountEntry", "class sample.Counter.countEntry"),
                run(input, "-l", "--kind", "type", "-i", "-e", "^countentry$").lines());
        assertEquals(List.of("method sample.Counter.GetItemCount()", "method sample.Counter.getItemCount()",
                "method sample.Counter.getItemCount(int)"),
                run(input, "-l", "--ignore-case", "sample.Counter", "-e", "^getitemcount$").lines());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void repeatedExpressionsFormAUnion(String input) {
        assertEquals(List.of("field sample.Counter.itemCount", "method sample.Counter.GetItemCount()",
                "method sample.Counter.getItemCount()", "method sample.Counter.getItemCount(int)"),
                run(input, "-l", "sample.Counter", "-e", "^itemCount$", "-e", "^getItemCount$",
                        "-e", "Count$").lines());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached"})
    void sourceDepthDoesNotChangeNameSelection(String input) {
        var scopes = input.equals("attached") ? List.of("signature", "doc", "definition", "body")
                : List.of("signature", "doc", "definition");
        for (var scope : scopes) {
            var output = run(input, "sample.Counter", "-e", "^getItemCount$", "--source", scope).output();
            assertTrue(output.contains("getItemCount"), output);
            assertFalse(output.contains("GetItemCount"), output);
            assertFalse(output.contains("unrelated"), output);
            assertFalse(output.contains("class Counter"), output);
        }
        var wider = run(input, "sample.Counter", "-e", "^getItemCount$", "--source", "type").output();
        assertTrue(wider.contains("class Counter"), wider);
        assertTrue(wider.contains("unrelated"), wider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void nestedRecordsAreDiscoverable(String input) {
        assertEquals(List.of("record sample.Counter.CountRecord"),
                run(input, "-l", "--kind", "record", "-e", "Record$").lines());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached"})
    void nestedSelectionCanExpandToItsCompilationUnit(String input) {
        var result = run(input, "-e", "^nestedWork$", "--source", "unit");
        assertTrue(result.output().contains("class Counter"), result.output());
        assertTrue(result.output().contains("nestedWork"), result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void listingHasTheSameSelectionAtEverySourceDepth(String input) {
        var expected = List.of("method sample.Counter.getItemCount()", "method sample.Counter.getItemCount(int)");
        for (var scope : List.of("none", "signature", "doc", "definition", "body", "symbol", "type", "unit")) {
            assertEquals(expected, run(input, "-l", "sample.Counter", "-e", "^getItemCount$",
                    "--source", scope).lines(), scope);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void ignoreCaseAlsoAppliesToLiteralSelectors(String input) {
        assertEquals(List.of("class sample.Counter"),
                run(input, "-l", "--kind", "class", "-i", "counter").lines());
        var expected = List.of("method sample.Counter.GetItemCount()", "method sample.Counter.getItemCount()",
                "method sample.Counter.getItemCount(int)");
        assertEquals(expected, run(input, "-l", "-i", "sample.counter", "-e", "^getitemcount$").lines());
        assertEquals(expected, run(input, "-l", "-i", "sample.counter.getitemcount").lines());
        assertEquals(List.of("method sample.Counter.GetItemCount()", "method sample.Counter.getItemCount()",
                "method sample.Counter.getItemCount(int)", "method sample.Other.getItemCount()"),
                run(input, "-l", "-i", "getitemcount").lines());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void simpleOwnerCanConstrainAnExpression(String input) {
        assertEquals(List.of("method sample.Counter.getItemCount()", "method sample.Counter.getItemCount(int)"),
                run(input, "-l", "Counter", "-e", "^getItemCount$").lines());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void constructorsUseTheirSearchName(String input) {
        assertEquals(List.of("method sample.Counter.new()"),
                run(input, "-l", "sample.Counter.new", "-e", "^new$").lines());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void jsonUsesTheSameSelectionAndPreservesSourceRanges(String input) {
        var output = Json.parse(run(input, "-l", "--format", "json", "sample.Counter",
                "-e", "^getItemCount$").output());
        assertEquals(0, output.get("exitCode").asInt());
        assertTrue(output.get("diagnostics").asList().isEmpty());
        var results = output.get("results").asList();
        assertEquals(2, results.size());
        for (var result : results) {
            assertTrue(result.get("text").asString().startsWith("method sample.Counter.getItemCount("));
            if (!input.equals("compiled")) {
                assertTrue(result.get("source").get("lineCount").asInt() > 0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "attached", "compiled"})
    void nameMatchingIsIndependentOfDefaultLocale(String input) {
        var previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals(List.of("method sample.Counter.GetItemCount()", "method sample.Counter.getItemCount()",
                    "method sample.Counter.getItemCount(int)"),
                    run(input, "-l", "sample.Counter", "-i", "-e", "^getitemcount$").lines());
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void expressionsAreAdvertisedThroughCompletion() {
        assertTrue(invoke("__complete", "--reg").output().contains("--regexp"));
        assertTrue(invoke("__complete", "--ignore-").output().contains("--ignore-case"));
    }

    @Test
    void expressionsAlsoFilterExplicitSourceAndClassFiles() {
        var expected = List.of("method sample.Counter.getItemCount()", "method sample.Counter.getItemCount(int)");
        assertEquals(expected, run("source", "-l", sources.resolve("sample/Counter.java").toString(),
                "-e", "^getItemCount$").lines());
        assertEquals(expected, run("compiled", "-l", classes.resolve("sample/Counter.class").toString(),
                "-e", "^getItemCount$").lines());
    }

    @Test
    void supportedOptionsExposeExpressionAndCaseControls() {
        var tool = new Jist();
        assertEquals(1, tool.isSupportedOption("-e"));
        assertEquals(1, tool.isSupportedOption("--regexp"));
        assertEquals(0, tool.isSupportedOption("-i"));
        assertEquals(0, tool.isSupportedOption("--ignore-case"));
    }

    private static Result run(String input, String... options) {
        var args = new ArrayList<>(List.of("--exclude-system-modules", "--visibility", "all"));
        switch (input) {
            case "source" -> args.addAll(List.of("--source-path", sources.toString()));
            case "attached" -> args.addAll(List.of("--class-path", classes.toString(),
                    "--source-path", archive.toString()));
            case "compiled" -> args.addAll(List.of("--class-path", classes.toString()));
            default -> throw new IllegalArgumentException(input);
        }
        args.addAll(List.of(options));
        return invoke(args.toArray(String[]::new));
    }

    private static Result invoke(String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        int status = new Jist().run(new PrintWriter(out), new PrintWriter(err), args);
        assertEquals(0, status, err.toString());
        return new Result(out.toString());
    }

    private record Result(String output) {
        List<String> lines() {
            return output.lines().sorted().toList();
        }
    }
}
