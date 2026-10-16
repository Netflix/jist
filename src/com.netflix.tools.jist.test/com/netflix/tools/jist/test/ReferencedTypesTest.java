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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferencedTypesTest {
    @TempDir Path directory;

    @Test
    void resolvesUsedTypesWithoutExpandingImports() throws Exception {
        var file = source("Example.java", """
                import java.nio.file.*;
                import java.util.*;
                import java.io.File;
                class Example {
                    List<Path> paths;
                    List<Path> more;
                }
                """);
        var result = run(file);
        success(result);
        assertEquals(List.of("interface java.util.List", "interface java.nio.file.Path"), result.output().lines().toList());
        assertEquals(result.output(), run(file, "-l").output());
    }

    @Test
    void includesImplicitImportsNestedTypesAndGenericBounds() throws Exception {
        var file = source("Example.java", """
                import java.util.*;
                class Example<T extends Number> {
                    Map.Entry<String, T>[] entries;
                    Class<?> type = String.class;
                }
                """);
        var result = run(file);
        success(result);
        var rows = result.output().lines().toList();
        assertTrue(rows.contains("class java.lang.Number"), result.output());
        assertTrue(rows.contains("interface java.util.Map.Entry"), result.output());
        assertTrue(rows.contains("class java.lang.String"), result.output());
        assertTrue(rows.contains("class java.lang.Class"), result.output());
        assertEquals(rows.size(), rows.stream().distinct().count());
        assertFalse(result.output().contains("java.lang.Object"), result.output());
    }

    @Test
    void includesOwnersOfStaticallyImportedMembers() throws Exception {
        var file = source("Example.java", """
                import static java.util.Collections.*;
                import static java.util.concurrent.TimeUnit.*;
                class Example {
                    void run() { emptyList(); SECONDS.toString(); }
                }
                """);
        var result = run(file);
        success(result);
        assertTrue(result.output().contains("class java.util.Collections\n"), result.output());
        assertTrue(result.output().contains("enum java.util.concurrent.TimeUnit\n"), result.output());
        assertFalse(result.output().contains("java.util.List"), result.output());
    }

    @Test
    void ignoresDeclarationsCommentsStringsAndInferredVariableTypes() throws Exception {
        var file = source("Example.java", """
                // java.io.File
                class Example {
                    void run() { var name = "java.nio.file.Path"; int size = 1; }
                }
                """);
        var result = run(file);
        success(result);
        assertEquals("", result.output());
    }

    @Test
    void preservesTypeKindsAndSupportsKindFilters() throws Exception {
        var file = source("Example.java", """
                @Deprecated
                record Example(java.time.DayOfWeek day, java.lang.Runnable task) {}
                """);
        var result = run(file);
        success(result);
        assertTrue(result.output().contains("annotation java.lang.Deprecated\n"), result.output());
        assertTrue(result.output().contains("enum java.time.DayOfWeek\n"), result.output());
        assertTrue(result.output().contains("interface java.lang.Runnable\n"), result.output());
        assertFalse(result.output().contains("java.lang.Record"), result.output());
        assertEquals("enum java.time.DayOfWeek\n", run(file, "--kind", "enum").output());
    }

    @Test
    void resolvesProjectSourcesWithoutGeneratingClassFiles() throws Exception {
        var dependency = source("example/Dependency.java", "package example; public class Dependency {}");
        var file = source("Example.java", "import example.*; class Example { Dependency value; }");
        var result = run(file, "--source-path", directory.toString());
        success(result);
        assertEquals("class example.Dependency\n", result.output());
        assertFalse(Files.exists(dependency.resolveSibling("Dependency.class")));
        assertFalse(Files.exists(file.resolveSibling("Example.class")));
    }

    @Test
    void resolvesClasspathDependencies() throws Exception {
        var dependency = source("example/Dependency.java", "package example; public class Dependency {}");
        var classes = directory.resolve("classes");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), dependency.toString()));
        var file = source("Example.java", "import example.*; class Example { Dependency value; }");
        var result = run(file, "--class-path", classes.toString());
        success(result);
        assertEquals("class example.Dependency\n", result.output());
    }

    @Test
    void resolvesTypesAcrossSourceModules() throws Exception {
        source("src/library/module-info.java", "module library { exports example.api; }");
        source("src/library/example/api/Dependency.java", "package example.api; public class Dependency {}");
        source("src/application/module-info.java", "module application { requires library; }");
        var file = source("src/application/example/app/Example.java", "package example.app; import example.api.*; public class Example { Dependency value; }");
        var result = run(file, "--module-source-path", directory.resolve("src").toString(), "--module", "application",
                "--limit-modules", "java.base,application,library");
        success(result);
        assertEquals("class example.api.Dependency\n", result.output());
        try (var files = Files.walk(directory)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".class")));
        }
    }

    @Test
    void ambiguousWildcardImportsFailRatherThanGuessing() throws Exception {
        var file = source("Example.java", "import java.util.*; import java.sql.*; class Example { Date date; }");
        var result = run(file, "--limit-modules", "java.base,java.sql");
        assertEquals(2, result.code(), result.error());
        assertEquals("", result.output());
        assertTrue(result.error().contains("ambiguous"), result.error());
    }

    @Test
    void unusedWildcardImportsProduceNoResults() throws Exception {
        var result = run(source("Example.java", "import java.util.*; class Example {}"));
        success(result);
        assertEquals("", result.output());
    }

    @Test
    void resolvesAnnotationBoundsAndExplicitClassLiterals() throws Exception {
        var file = source("Example.java", """
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @interface Marker { Class<?> value(); }
                @Marker(java.nio.file.Path.class)
                class Example {}
                """);
        var result = run(file);
        success(result);
        assertTrue(result.output().contains("annotation java.lang.annotation.Retention\n"), result.output());
        assertTrue(result.output().contains("enum java.lang.annotation.RetentionPolicy\n"), result.output());
        assertTrue(result.output().contains("interface java.nio.file.Path\n"), result.output());
        assertTrue(result.output().contains("annotation Marker\n"), result.output());
    }

    @Test
    void classFilesProvideTheBaselineWithoutSourcesOrDebugInformation() throws Exception {
        var file = source("example/Example.java", """
                package example;
                import java.util.*;
                import java.nio.file.Path;
                @Deprecated
                public class Example {
                    List<Path> paths;
                    public String read() throws java.io.IOException { return "not.a.RealClass"; }
                }
                """);
        var classes = compile(file);
        var result = run(classes.resolve("example/Example.class"), "--class-path", classes.toString());
        success(result);
        assertTrue(result.output().contains("interface java.util.List\n"), result.output());
        assertTrue(result.output().contains("interface java.nio.file.Path\n"), result.output());
        assertTrue(result.output().contains("class java.lang.String\n"), result.output());
        assertTrue(result.output().contains("class java.io.IOException\n"), result.output());
        assertTrue(result.output().contains("annotation java.lang.Deprecated\n"), result.output());
        assertFalse(result.output().contains("not.a.RealClass"), result.output());
        assertFalse(result.output().contains("class example.Example"), result.output());
        assertEquals(result.output(), run(Path.of("example.Example"), "--class-path", classes.toString()).output());
    }

    @Test
    void attachedSourcesSupplementRatherThanReplaceCompiledReferences() throws Exception {
        var file = source("example/Example.java", """
                package example;
                @SuppressWarnings("unused")
                public class Example { java.util.List<String> values; }
                """);
        var classes = compile(file);
        var withoutSource = run(Path.of("example.Example"), "--class-path", classes.toString());
        success(withoutSource);
        assertFalse(withoutSource.output().contains("java.lang.SuppressWarnings"), withoutSource.output());
        var sources = directory.resolve("sources.jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(sources))) {
            jar.putNextEntry(new java.util.jar.JarEntry("example/Example.java"));
            Files.copy(file, jar);
            jar.closeEntry();
        }
        var withSource = run(Path.of("example.Example"), "--class-path", classes.toString(), "--source-path", sources.toString());
        success(withSource);
        assertTrue(withSource.output().contains("annotation java.lang.SuppressWarnings\n"), withSource.output());
        assertTrue(withoutSource.output().lines().allMatch(withSource.output()::contains), withSource.output());
        var none = run(Path.of("example.Example"), "--class-path", classes.toString(), "--source-path", sources.toString(), "--source", "none");
        success(none);
        assertEquals(withoutSource.output(), none.output());
    }

    @Test
    void sourceFileLocatorsUseCompiledFactsWhenAvailable() throws Exception {
        var file = source("example/Example.java", "package example; public class Example { java.nio.file.Path path; }");
        var classes = compile(file);
        Files.writeString(file, "package example; public class Example { Missing missing; }");
        var result = run(file, "--class-path", classes.toString());
        success(result);
        assertTrue(result.output().contains("interface java.nio.file.Path\n"), result.output());
    }

    @Test
    void classpathJarsAndNamedModulesUseTheSameCompiledQuery() throws Exception {
        var file = source("example/Example.java", "package example; @SuppressWarnings(\"unused\") public class Example { java.nio.file.Path path; }");
        var classes = compile(file);
        var jar = directory.resolve("example.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("example/Example.class"));
            Files.copy(classes.resolve("example/Example.class"), output);
            output.closeEntry();
        }
        var classpath = run(Path.of("example.Example"), "--class-path", jar.toString(), "--source", "none");
        success(classpath);
        assertTrue(classpath.output().contains("interface java.nio.file.Path\n"), classpath.output());
        var descriptor = source("module-info.java", "module example.module { exports example; }");
        var modules = directory.resolve("modules");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g:none", "-d", modules.toString(), descriptor.toString(), file.toString()));
        var module = run(Path.of("example.Example"), "--module-path", modules.toString(), "--module", "example.module",
                "--limit-modules", "java.base,example.module", "--source", "none");
        success(module);
        assertEquals(classpath.output(), module.output());
        var enriched = run(Path.of("example.Example"), "--module-path", modules.toString(), "--module", "example.module",
                "--limit-modules", "java.base,example.module", "--source-path", directory.toString());
        success(enriched);
        assertTrue(enriched.output().contains("annotation java.lang.SuppressWarnings\n"), enriched.output());
    }

    @Test
    void compiledLocalGenericSignaturesAreReferencesToo() throws Exception {
        var file = source("example/Example.java", """
                package example;
                public class Example {
                    void run() { java.util.List<java.nio.file.Path> paths = null; }
                }
                """);
        var classes = directory.resolve("debug-classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), file.toString()));
        var result = run(Path.of("example.Example"), "--class-path", classes.toString(), "--source", "none");
        success(result);
        assertTrue(result.output().contains("interface java.nio.file.Path\n"), result.output());
    }

    private Path compile(Path file) {
        var classes = directory.resolve("classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g:none", "-d", classes.toString(), file.toString()));
        return classes;
    }

    @Test
    void unresolvedTypesFailBeforePrintingPartialResults() throws Exception {
        var file = source("Example.java", "class Example { String text; Missing value; }");
        var result = run(file);
        assertEquals(2, result.code(), result.error());
        assertEquals("", result.output());
        assertTrue(result.error().contains("Missing"), result.error());
    }

    @Test
    void rejectsMissingFilesAndConflictingQueries() throws Exception {
        var file = source("Example.java", "class Example {}");
        for (var options : List.of(List.of("java.lang.String"), List.of("--usages"), List.of("--subtypes", "java.lang.Object"),
                List.of("--source", "definition"))) {
            var result = run(file, options.toArray(String[]::new));
            assertEquals(1, result.code(), result.error());
            assertEquals("", result.output());
        }
        var missing = run(directory.resolve("Missing.java"));
        assertEquals(1, missing.code(), missing.error());
        assertEquals("", missing.output());
        assertEquals(1, new Jist().isSupportedOption("--referenced-types"));
    }

    private Path source(String name, String text) throws Exception {
        var file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private static Result run(Path file, String... options) {
        var arguments = new ArrayList<>(List.of("--limit-modules", "java.base", "--referenced-types", file.toString()));
        arguments.addAll(List.of(options));
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), arguments.toArray(String[]::new));
        return new Result(code, output.toString(), error.toString());
    }

    private static void success(Result result) {
        assertEquals(0, result.code(), result.error());
        assertEquals("", result.error());
    }

    private record Result(int code, String output, String error) {}
}
