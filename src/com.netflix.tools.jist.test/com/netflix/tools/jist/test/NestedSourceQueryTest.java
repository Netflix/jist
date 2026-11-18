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
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedSourceQueryTest {
    @TempDir
    static Path root;
    private static Path classes;
    private static Path sources;

    @BeforeAll
    static void fixture() throws Exception {
        var source = Files.createDirectories(root.resolve("src/sample/api")).resolve("Outer.java");
        classes = Files.createDirectories(root.resolve("classes"));
        Files.writeString(source, """
                package sample.api;
                public class Outer {
                    public Outer() {}
                    private enum State {
                        READY;
                        public String label() { return "ready"; }
                    }
                    public static class Nested {
                        /** A nested value. */
                        public int value;
                        /** Work without an amount. */
                        public void work() {}
                        /** Work with an amount. */
                        public void work(int amount) {}
                        public static class Deeper {
                            public int details() { return 1; }
                        }
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-parameters", "-d", classes.toString(), source.toString()));
        sources = root.resolve("sources.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(sources))) {
            jar.putNextEntry(new JarEntry("sample/api/Outer.java"));
            Files.copy(source, jar);
            jar.closeEntry();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "doc", "definition"})
    void nestedMethodsRetainSourceAtEveryDeclarationDepth(String scope) {
        for (var selection : List.of(List.<String>of(), List.of("sample.api"), List.of("sample.api.Outer.Nested"),
                List.of("sample.api.Outer.Nested.work"), List.of("work"), List.of("-e", "^work$"))) {
            var arguments = new ArrayList<>(List.of("--kind", "method", "--source", scope));
            arguments.addAll(selection);
            var output = run(arguments);
            assertTrue(output.contains("Outer(Outer.java:12):         public void work() {}"), output);
            assertTrue(output.contains("Outer(Outer.java:14):         public void work(int amount) {}"), output);
            if (!scope.equals("signature")) {
                assertTrue(output.contains("Work without an amount."), output);
                assertTrue(output.contains("Work with an amount."), output);
            }
        }
    }

    @Test
    void membersOfUnselectedNestedTypesStillHaveSource() {
        var output = run(List.of("--kind", "method", "label"));
        assertTrue(output.contains("Outer(Outer.java:6):         public String label()"), output);
        var field = run(List.of("--kind", "field", "READY"));
        assertTrue(field.contains("Outer(Outer.java:5):         READY;"), field);
    }

    @Test
    void deeperNestedMembersRetainSource() {
        var output = run(List.of("--kind", "method", "details"));
        assertTrue(output.contains("Outer(Outer.java:16):             public int details()"), output);
    }

    @Test
    void nestedFieldsAndMethodsPublishTheirSourceRanges() {
        for (var name : List.of("value", "work")) {
            var output = Json.parse(run(List.of("-l", "--format", "json", "-e", "^" + name + "$")));
            var results = output.get("results").asList();
            assertEquals(name.equals("value") ? 1 : 2, results.size());
            for (var result : results) {
                assertEquals(19, result.get("source").get("lineCount").asInt());
                assertTrue(result.get("source").get("range").get("startLine").asInt() > 0);
                assertTrue(result.get("text").asString().contains("sample.api.Outer.Nested." + name));
            }
        }
    }

    private static String run(List<String> selection) {
        var arguments = new ArrayList<>(List.of("--system", "none", "--class-path", classes.toString(),
                "--source-path", sources.toString()));
        arguments.addAll(selection);
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), arguments.toArray(String[]::new));
        assertEquals(0, code, error.toString());
        assertEquals("", error.toString());
        return output.toString();
    }
}
