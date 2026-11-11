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
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Jist;
import com.netflix.tools.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonOutputTest {
    @Test
    void successfulSearchReportsItsExitCode() {
        var result = run("--source", "none", "java.lang.String.length");

        assertEquals(0, result.code(), result.error());
        assertEquals("", result.error());
        var output = Json.parse(result.output());
        assertEquals("jist", output.get("format").asString());
        assertEquals(1, output.get("version").asInt());
        assertEquals(0, output.get("exitCode").asInt());
        assertTrue(output.get("diagnostics").asList().isEmpty());
        assertFalse(output.get("results").asList().isEmpty());
    }

    @Test
    void missingTargetsHaveDistinctDiagnostics() {
        for (var arguments : List.of(
                List.of("missing.Type"),
                List.of("MissingType"),
                List.of("missing/Type.java"),
                List.of("--referenced-types", "missing/Type.class"),
                List.of("--callers", "missing.Type.method"),
                List.of("--supertypes", "missing.Type"),
                List.of("--subtypes", "missing.Type"),
                List.of("--overrides", "java.lang.String.missing"),
                List.of("--usages", "java.lang.String.missing"))) {
            var result = run(arguments.toArray(String[]::new));

            assertFailure(result, 1, "not_found");
            assertTrue(Json.parse(result.output()).get("results").asList().isEmpty(), result.output());
        }
    }

    @Test
    void invalidQueriesAreNotReportedAsMissingTargets() {
        var result = run("--direct", "java.lang.String");

        assertFailure(result, 1, "error");
        assertEquals("Error: --direct requires a relationship selection\n", result.error());
    }

    @Test
    void emptySearchIsSuccessful() {
        var result = run("--exclude-system-modules", "--callers", "java.lang.String.length");

        assertEquals(0, result.code(), result.error());
        var output = Json.parse(result.output());
        assertEquals(0, output.get("exitCode").asInt());
        assertTrue(output.get("results").asList().isEmpty());
        assertTrue(output.get("diagnostics").asList().isEmpty());
    }

    @Test
    void resolutionFailuresAreNotReportedAsMissingTargets() {
        var result = run("--add-modules", "missing.module", "java.lang.String");

        assertFailure(result, 1, "error");
        assertTrue(result.error().contains("Cannot resolve modules"), result.error());
    }

    @Test
    void unexpectedExceptionsAreIncludedWithoutReplacingTheStackTrace(@TempDir Path directory) throws Exception {
        var file = directory.resolve("Broken.class");
        Files.writeString(file, "not a ClassFile");

        var result = run(file.toString());

        assertEquals(2, result.code(), result.error());
        var output = Json.parse(result.output());
        assertEquals(2, output.get("exitCode").asInt());
        var diagnostic = output.get("diagnostics").asList().getFirst();
        assertEquals("error", diagnostic.get("kind").asString());
        assertTrue(result.error().startsWith(diagnostic.get("message").asString() + "\n"), result.error());
        assertTrue(result.error().contains("\tat "), result.error());
    }

    @Test
    void unreadableArchivesReportSearchErrors(@TempDir Path directory) throws Exception {
        var archive = directory.resolve("broken\"archive.jar");
        Files.writeString(archive, "not an archive");

        var result = run("--class-path", archive.toString(), "--source", "none", "example.Type");

        assertFailure(result, 2, "error");
        assertTrue(result.error().contains(archive.toString()), result.error());
    }

    @Test
    void missingDependenciesAreNotReportedAsMissingTargets(@TempDir Path directory) throws Exception {
        var factory = directory.resolve("Factory.java");
        Files.writeString(factory, """
                package optional;
                public class Factory {
                    public static java.time.Clock systemUTC() { return null; }
                }
                """);
        var caller = directory.resolve("Caller.java");
        Files.writeString(caller, """
                package example;
                public class Caller {
                    public java.time.Clock clock() { return optional.Factory.systemUTC(); }
                }
                """);
        var classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-g", "-d", classes.toString()), null,
                    manager.getJavaFileObjects(factory, caller)).call());
        }
        Files.delete(classes.resolve("optional/Factory.class"));

        var result = run("--class-path", classes.toString(), "--exclude-system-modules",
                "--callers", "java.time.Clock.systemUTC");

        assertFailure(result, 1, "error");
        assertTrue(result.error().contains("optional.Factory"), result.error());
    }

    @Test
    void searchFailuresPreserveAlreadyEmittedResults(@TempDir Path directory) throws Exception {
        var caller = directory.resolve("Caller.java");
        Files.writeString(caller, """
                package example;
                public class Caller {
                    public java.time.Clock clock() { return java.time.Clock.systemUTC(); }
                }
                """);
        var classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-g", "-d", classes.toString()), null,
                    manager.getJavaFileObjects(caller)).call());
        }
        var sources = Files.createDirectories(directory.resolve("src/example"));
        Files.writeString(sources.resolve("Broken.java"), """
                package example;
                class Broken {
                    Missing value;
                    java.time.Clock clock() { return java.time.Clock.systemUTC(); }
                }
                """);

        var result = run("--class-path", classes.toString(), "--source-path", directory.resolve("src").toString(),
                "--exclude-system-modules", "--callers", "java.time.Clock.systemUTC");

        assertFailure(result, 2, "error");
        var rows = Json.parse(result.output()).get("results").asList();
        assertEquals(1, rows.size(), result.output());
        assertTrue(rows.getFirst().get("text").asString().contains(
                "example.Caller.clock() -> java.time.Clock.systemUTC()"), result.output());
    }

    private static void assertFailure(Result result, int exitCode, String kind) {
        assertEquals(exitCode, result.code(), result.error());
        var output = Json.parse(result.output());
        assertEquals(exitCode, output.get("exitCode").asInt());
        var diagnostics = output.get("diagnostics").asList();
        assertEquals(1, diagnostics.size(), result.output());
        var diagnostic = diagnostics.getFirst();
        assertEquals(kind, diagnostic.get("kind").asString());
        assertEquals("Error: " + diagnostic.get("message").asString() + "\n", result.error());
    }

    private static Result run(String... arguments) {
        var options = new ArrayList<>(List.of("--limit-modules", "java.base", "--format", "json"));
        options.addAll(List.of(arguments));
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), options.toArray(String[]::new));
        return new Result(code, output.toString(), error.toString());
    }

    private record Result(int code, String output, String error) {}
}
