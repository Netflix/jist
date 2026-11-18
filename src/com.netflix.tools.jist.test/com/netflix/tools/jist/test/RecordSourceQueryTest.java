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
import java.util.stream.Collectors;
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

class RecordSourceQueryTest {
    @TempDir
    static Path root;
    private static Path classes;
    private static Path sources;

    @BeforeAll
    static void fixture() throws Exception {
        var source = Files.createDirectories(root.resolve("src/sample/records")).resolve("Records.java");
        classes = Files.createDirectories(root.resolve("classes"));
        var text = """
                package sample.records;
                public class Records {
                    public Records() {}
                    private record Implicit(int value, String text) {}
                    public record Empty() {}
                    public record Explicit(int value) {
                        /** Validate the value. */
                        public Explicit {
                            if (value < 0) throw new IllegalArgumentException();
                        }
                        /** Read the value. */
                        public int value() { return value + 1; }
                        /** Describe the value. */
                        public String toString() { return "explicit"; }
                        /** Hash the value. */
                        public int hashCode() { return value; }
                        /** Compare the value. */
                        public boolean equals(Object other) { return other == this; }
                    }
                    public record Incomplete(int value) {
                        public String toString(int radix) { return Integer.toString(value, radix); }
                        public int value(int increment) { return value + increment; }
                        public void extra() {}
                    }
                }
                """;
        Files.writeString(source, text);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-parameters", "-d", classes.toString(), source.toString()));
        var attached = text.lines()
                .filter(line -> !line.contains("toString(int radix)")
                        && !line.contains("value(int increment)") && !line.contains("void extra()"))
                .collect(Collectors.joining("\n", "", "\n"));
        sources = root.resolve("sources.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(sources))) {
            jar.putNextEntry(new JarEntry("sample/records/Records.java"));
            jar.write(attached.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "doc"})
    void implicitRecordMembersUseCompiledDeclarationsWithoutWarning(String scope) {
        for (var member : List.of("value", "text", "toString", "hashCode", "equals", "new")) {
            var result = run(true, "--visibility", "all", "--kind", "method", "--source", scope,
                    "sample.records.Records.Implicit." + member);
            assertEquals(0, result.code(), result.error());
            assertEquals("", result.error(), member);
            assertTrue(result.output().startsWith("sample.records.Records: "), result.output());
            assertTrue(result.output().contains(member.equals("new") ? "Implicit(" : member + "("), result.output());
        }
        var empty = run(true, "sample.records.Records.Empty.new");
        assertEquals(0, empty.code(), empty.error());
        assertEquals("", empty.error());
        assertTrue(empty.output().contains("Empty()"), empty.output());
    }

    @Test
    void enumerationOfImplicitRecordMethodsIsQuiet() {
        for (var selection : List.of(List.<String>of("sample.records.Records.Implicit"),
                List.of("-e", "^text$"))) {
            var arguments = new ArrayList<>(List.of("--kind", "method"));
            arguments.addAll(selection);
            var result = run(true, arguments.toArray(String[]::new));
            assertEquals(0, result.code(), result.error());
            assertEquals("", result.error());
            assertTrue(result.output().contains("text()"), result.output());
        }
    }

    @Test
    void implicitRecordMembersDoNotClaimSourceRanges() {
        var result = run(true, "--format", "json", "sample.records.Records.Implicit.text");
        assertEquals(0, result.code(), result.error());
        assertEquals("", result.error());
        var output = Json.parse(result.output());
        var results = output.get("results").asList();
        assertEquals(1, results.size());
        assertTrue(results.getFirst().get("text").asString().contains("text()"));
        assertFalse(result.output().contains("\"source\":"), result.output());
        assertEquals(0, output.get("diagnostics").asList().size());
    }

    @Test
    void explicitRecordImplementationsRetainTheirSourceAndDocumentation() {
        for (var member : List.of("value", "toString", "hashCode", "equals", "new")) {
            var result = run(true, "--source", "doc", "sample.records.Records.Explicit." + member);
            assertEquals(0, result.code(), result.error());
            assertEquals("", result.error());
            assertTrue(result.output().contains("(Records.java:"), result.output());
            assertTrue(result.output().contains("/**"), result.output());
            assertFalse(result.output().contains("sample.records.Records: "), result.output());
        }
    }

    @Test
    void unavailableRecordSourceStillWarns() {
        var result = run(false, "sample.records.Records.Implicit.text");
        assertEquals(0, result.code(), result.error());
        assertEquals("Warning: Source unavailable for sample.records.Records; using ClassFile information\n", result.error());
        assertTrue(result.output().contains("text()"), result.output());
    }

    @Test
    void unmatchedOrdinaryRecordMethodsStillWarn() {
        for (var member : List.of("toString", "value", "extra")) {
            var result = run(true, "sample.records.Records.Incomplete." + member);
            assertEquals(0, result.code(), result.error());
            assertEquals("Warning: Source unavailable for sample.records.Records; using ClassFile information\n", result.error());
            assertTrue(result.output().contains(member + "("), result.output());
        }
    }

    private static Result run(boolean attached, String... selection) {
        var arguments = new ArrayList<>(List.of("--system", "none", "--class-path", classes.toString()));
        if (attached) {
            arguments.addAll(List.of("--source-path", sources.toString()));
        }
        arguments.addAll(List.of(selection));
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), arguments.toArray(String[]::new));
        return new Result(code, output.toString(), error.toString());
    }

    private record Result(int code, String output, String error) {}
}
