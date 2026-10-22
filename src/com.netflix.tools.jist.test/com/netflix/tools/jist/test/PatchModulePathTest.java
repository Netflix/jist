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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchModulePathTest {
    private static final Path ROOT = Path.of("build/test-patch-module-path");
    private static final Path SOURCES = ROOT.resolve("src");
    private static final Path MODULES = ROOT.resolve("modules");
    private static final Path PATCH_SOURCES = ROOT.resolve("patch-src");
    private static final Path PATCH = ROOT.resolve("patch");
    private static final Path SECOND = ROOT.resolve("second");
    private static final Path ARCHIVE = ROOT.resolve("patch.jar");
    private static final Path CALLER_SOURCES = ROOT.resolve("caller-src");
    private static final Path NESTED = ROOT.resolve("nested");
    private static final Path SYSTEM = ROOT.resolve("system");

    @BeforeAll
    static void compileModules() throws Exception {
        write(SOURCES.resolve("library/module-info.java"), "module library { exports library.api; }");
        write(SOURCES.resolve("library/library/api/Target.java"), """
                package library.api;
                public class Target {
                    public void original() {}
                    public String run() { return "original".strip(); }
                    public static class Nested { public void originalNested() {} }
                }
                """);
        write(SOURCES.resolve("library/library/api/Unchanged.java"), "package library.api; public class Unchanged {}");
        write(SOURCES.resolve("application/module-info.java"), "module application { requires library; }");
        write(SOURCES.resolve("application/application/Unused.java"), "package application; public class Unused {}");
        compile(SOURCES, MODULES, List.of("--module-source-path", SOURCES.toString()));
        write(PATCH_SOURCES.resolve("library/api/Target.java"), """
                package library.api;
                public class Target {
                    public void changed() {}
                    public String run() { return "patched".repeat(2); }
                    public static class Nested { public void changedNested() {} }
                }
                """);
        write(PATCH_SOURCES.resolve("patch/internal/Added.java"), """
                package patch.internal;
                public class Added {
                    public String run() { return "new package".repeat(2); }
                }
                """);
        compile(PATCH_SOURCES, PATCH, List.of("--module-path", MODULES.toString(), "--add-modules", "library",
                "--patch-module", "library=" + PATCH_SOURCES));
        Files.createDirectories(NESTED.resolve("library/api"));
        Files.copy(PATCH.resolve("library/api/Target$Nested.class"), NESTED.resolve("library/api/Target$Nested.class"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var systemSources = ROOT.resolve("system-src");
        write(systemSources.resolve("java/util/logging/Logger.java"), """
                package java.util.logging;
                public class Logger {
                    public String changed() { return "patched".repeat(2); }
                }
                """);
        compile(systemSources, SYSTEM, List.of("--patch-module", "java.logging=" + systemSources));
        var secondSources = ROOT.resolve("second-src");
        write(secondSources.resolve("library/api/Target.java"), "package library.api; public class Target { public void second() {} }");
        compile(secondSources, SECOND, List.of("--module-path", MODULES.toString(), "--add-modules", "library",
                "--patch-module", "library=" + secondSources));
        try (var jar = new JarOutputStream(Files.newOutputStream(ARCHIVE)); var files = Files.walk(PATCH)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(PATCH.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        write(CALLER_SOURCES.resolve("application/module-info.java"), "module application { requires library; }");
        write(CALLER_SOURCES.resolve("application/application/Caller.java"), """
                package application;
                public class Caller {
                    public void call() { new library.api.Target().changed(); }
                }
                """);
    }

    @Test
    void patchedClassesReplaceOriginalDeclarationsAndKeepOtherClasses() {
        for (var path : List.of(PATCH.toString(), ARCHIVE.toString(), PATCH + File.pathSeparator + SECOND)) {
            var result = run("--patch-module", "library=" + path, "--module", "library", "--source", "none", "-l", "--kind", "method", "library.api.Target");
            success(result);
            assertTrue(result.output().contains("method library.api.Target.changed()\n"), result.output());
            assertFalse(result.output().contains("original"), result.output());
            assertFalse(result.output().contains("second"), result.output());
            var unchanged = run("--patch-module", "library=" + path, "--module", "library", "--source", "none", "-l", "--kind", "type", "library.api.Unchanged");
            success(unchanged);
            assertEquals("class library.api.Unchanged\n", unchanged.output());
        }
    }

    @Test
    void compiledCallersUsePatchPrecedenceAndNewPackagesInTheSelectedModule() {
        var result = run("--patch-module", "library=" + PATCH, "--module", "library", "--source", "none", "-l", "--callers", "java.lang.String.repeat");
        success(result);
        assertTrue(result.output().contains("method library.api.Target.run()\n"), result.output());
        assertTrue(result.output().contains("method patch.internal.Added.run()\n"), result.output());
        var original = run("--patch-module", "library=" + PATCH, "--module", "library", "--source", "none", "-l", "--callers", "java.lang.String.strip");
        success(original);
        assertFalse(original.output().contains("library.api.Target.run"), original.output());
    }

    @Test
    void patchSourcesParticipateInAttributionAndDeclarationSearch() {
        var result = run("--patch-module", "library=" + PATCH_SOURCES, "--module", "library", "--source", "none", "-l", "--kind", "method", "library.api.Target");
        success(result);
        assertTrue(result.output().contains("method library.api.Target.changed()\n"), result.output());
        assertFalse(result.output().contains("original"), result.output());
        var sourceCaller = run("--patch-module", "library=" + PATCH, "--module-source-path", CALLER_SOURCES.toString(),
                "--module", "application", "--source", "none", "-l", "--callers", "library.api.Target.changed");
        success(sourceCaller);
        assertEquals("method application.Caller.call()\n", sourceCaller.output());
    }

    @Test
    void patchingDoesNotRootAModuleOrExportItsNewPackages() {
        var unrooted = run("--patch-module", "library=" + PATCH, "--source", "none", "-l", "--kind", "type", "library.api.Target");
        assertEquals(1, unrooted.code(), unrooted.error());
        var hidden = run("--patch-module", "library=" + PATCH, "--module", "application", "--source", "none", "-l", "--kind", "type", "patch.internal");
        assertEquals(1, hidden.code(), hidden.error());
        var exported = run("--patch-module", "library=" + PATCH, "--module", "application", "--add-exports", "library/patch.internal=application",
                "--source", "none", "-l", "--kind", "type", "patch.internal");
        success(exported);
        assertEquals("class patch.internal.Added\n", exported.output());
    }

    @Test
    void patchModuleDescriptorsAreIgnored() throws Exception {
        var sources = ROOT.resolve("descriptor-src");
        write(sources.resolve("module-info.java"), "module library { exports wrong.packageName; }");
        var result = run("--patch-module", "library=" + sources, "--module", "library", "--kind", "module", "--source", "definition", "library");
        assertEquals(0, result.code(), result.error());
        assertTrue(result.output().contains("exports library.api;"), result.output());
        assertFalse(result.output().contains("wrong.packageName"), result.output());
    }

    @Test
    void sourcePatchesAreVisibleOnlyInTheSelectedCompilationContext() {
        var hidden = run("--patch-module", "library=" + PATCH_SOURCES, "--module", "application",
                "--source", "none", "-l", "--kind", "type", "patch.internal.Added");
        assertEquals(1, hidden.code(), hidden.output());
        var callers = run("--patch-module", "library=" + PATCH_SOURCES, "--module", "library",
                "--source", "none", "-l", "--callers", "java.lang.String.repeat");
        success(callers);
        assertTrue(callers.output().contains("method library.api.Target.run()\n"), callers.output());
        assertTrue(callers.output().contains("method patch.internal.Added.run()\n"), callers.output());
    }

    @Test
    void sourcePatchesHideOriginalSourceUsages() {
        var original = run("--patch-module", "library=" + PATCH_SOURCES, "--source-path", SOURCES.resolve("library").toString(),
                "--module", "library", "--source", "none", "-l", "--callers", "java.lang.String.strip");
        success(original);
        assertFalse(original.output().contains("library.api.Target.run"), original.output());
    }

    @Test
    void nestedPatchesReplaceOnlyTheirOwnClassFiles() {
        var result = run("--patch-module", "library=" + NESTED, "--module", "library", "--source", "none", "library.api.Target");
        success(result);
        assertTrue(result.output().contains("void original()"), result.output());
        assertTrue(result.output().contains("void changedNested()"), result.output());
        assertFalse(result.output().contains("originalNested"), result.output());
    }

    @Test
    void patchSourcesTakePrecedenceOverOrdinarySources() {
        var result = run("--patch-module", "library=" + PATCH_SOURCES, "--source-path", SOURCES.resolve("library").toString(),
                "--module", "library", "--kind", "method", "library.api.Target");
        success(result);
        assertTrue(result.output().contains("void changed()"), result.output());
        assertFalse(result.output().contains("void original()"), result.output());
    }

    @Test
    void systemPatchesReplaceJrtDeclarationsAndCallers() {
        var result = run("--patch-module", "java.logging=" + SYSTEM, "--module", "java.logging", "--limit-modules", "java.base,java.logging",
                "--source", "none", "-l", "--kind", "method", "java.util.logging.Logger");
        success(result);
        assertTrue(result.output().contains("method java.util.logging.Logger.changed()\n"), result.output());
        assertFalse(result.output().contains("getName"), result.output());
        var callers = run("--patch-module", "java.logging=" + SYSTEM, "--module", "java.logging", "--limit-modules", "java.base,java.logging",
                "--source", "none", "-l", "--callers", "java.lang.String.repeat");
        success(callers);
        assertTrue(callers.output().contains("method java.util.logging.Logger.changed()\n"), callers.output());
    }

    @Test
    void patchOptionsAreRepeatableAndUsedForCompletion() {
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), "__complete", "--module-path", MODULES.toString(),
                "--module", "library", "--patch-module", "library=" + PATCH, "--patch-module", "java.logging=" + SYSTEM,
                "--kind", "method", "library.api.Target.ch");
        assertEquals(0, code, error.toString());
        assertTrue(output.toString().contains("library.api.Target.changed\tmethod\n"), output.toString());
    }

    private static void write(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private static void compile(Path sources, Path output, List<String> options) throws Exception {
        Files.createDirectories(output);
        var arguments = new ArrayList<>(options);
        arguments.addAll(List.of("-g", "-d", output.toString()));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null); var files = Files.walk(sources)) {
            assertTrue(compiler.getTask(null, manager, null, arguments, null,
                    manager.getJavaFileObjectsFromPaths(files.filter(path -> path.toString().endsWith(".java")).toList())).call());
        }
    }

    private static Result run(String... options) {
        var arguments = new ArrayList<>(List.of("--module-path", MODULES.toString(), "--limit-modules", "java.base,library,application"));
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
