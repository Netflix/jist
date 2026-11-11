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
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Jist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassPathQueryTest {
    @Test
    void ignoresNonArchiveEntriesAcrossQueryModes(@TempDir Path directory) throws Exception {
        var fixture = fixture(directory);
        var pom = directory.resolve("dependency.pom");
        Files.writeString(pom, "<project/>");
        var classPath = pom + File.pathSeparator + fixture.classes() + File.pathSeparator + pom;
        for (var query : List.of(
                List.of("example.Sample"),
                List.of("-l", "--kind", "type", "Sample"),
                List.of("--callers", "java.time.Clock.systemUTC"),
                List.of("--usages", "java.time.Clock.systemUTC"),
                List.of("--referenced-types", "example.Sample"),
                List.of("--subtypes", "java.lang.Object"))) {
            var result = run(classPath, fixture.sources(), query);
            assertEquals(0, result.code(), result.error());
            assertFalse(result.output().isBlank(), query.toString());
            assertEquals("", result.error());
        }
    }

    @Test
    void aClassPathContainingOnlyAnIgnoredFileCanStillResolveSystemClasses(@TempDir Path directory) throws Exception {
        var pom = directory.resolve("dependency.pom");
        Files.writeString(pom, "<project/>");
        var result = run(pom.toString(), directory.resolve("no-sources"), List.of("java.lang.String.intern"));
        assertEquals(0, result.code(), result.error());
        assertTrue(result.output().contains("intern()"), result.output());
        assertEquals("", result.error());
    }

    @Test
    void acceptsArchivesWithoutAConventionalExtension(@TempDir Path directory) throws Exception {
        var fixture = fixture(directory);
        var archive = directory.resolve("dependency.data");
        try (var jar = new JarOutputStream(Files.newOutputStream(archive))) {
            jar.putNextEntry(new JarEntry("example/Sample.class"));
            Files.copy(fixture.classes().resolve("example/Sample.class"), jar);
            jar.closeEntry();
        }
        var result = run(archive.toString(), fixture.sources(), List.of("--callers", "java.time.Clock.systemUTC"));
        assertEquals(0, result.code(), result.error());
        assertTrue(result.output().contains("example.Sample.clock() -> java.time.Clock.systemUTC()"), result.output());
        assertEquals("", result.error());
    }

    @Test
    void namesCorruptArchivesInDiagnostics(@TempDir Path directory) throws Exception {
        var fixture = fixture(directory);
        for (var extension : List.of(".jar", ".zip")) {
            var archive = directory.resolve("corrupt" + extension);
            Files.writeString(archive, "not an archive");
            for (var query : List.of(List.of("example.Sample"), List.of("-l", "--kind", "type", "Sample"),
                    List.of("--callers", "java.time.Clock.systemUTC"))) {
                var result = run(archive + File.pathSeparator + fixture.classes(), fixture.sources(), query);
                assertEquals(2, result.code(), result.error());
                assertTrue(result.error().startsWith("Error:"), result.error());
                assertTrue(result.error().contains(archive.toString()), result.error());
                assertFalse(result.error().contains("\tat "), result.error());
            }
        }
    }

    private static Fixture fixture(Path directory) throws Exception {
        var source = directory.resolve("src/example/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                public class Sample {
                    public java.time.Clock clock() { return java.time.Clock.systemUTC(); }
                }
                """);
        var classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-g", "-d", classes.toString()), null,
                    manager.getJavaFileObjects(source)).call());
        }
        var sources = directory.resolve("sources.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(sources))) {
            jar.putNextEntry(new JarEntry("example/Sample.java"));
            Files.copy(source, jar);
            jar.closeEntry();
        }
        return new Fixture(classes, sources);
    }

    private static Result run(String classPath, Path sources, List<String> query) {
        var arguments = new ArrayList<>(List.of("--limit-modules", "java.base", "--exclude-system-modules",
                "--class-path", classPath, "--source-path", sources.toString()));
        arguments.addAll(query);
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), arguments.toArray(String[]::new));
        return new Result(code, output.toString(), error.toString());
    }

    private record Fixture(Path classes, Path sources) {}
    private record Result(int code, String output, String error) {}
}
