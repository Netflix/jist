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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Jist;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleResolutionTest {
    private static final Path ROOT = Path.of("build/test-module-resolution");
    private static final Path SOURCES = ROOT.resolve("src");
    private static final Path MODULES = ROOT.resolve("modules");

    @BeforeAll
    static void compileModules() throws Exception {
        write("alpha.module/module-info.java",
                """
                module alpha.module {
                    requires beta.module;
                    exports alpha.api;
                }
                """);
        write("alpha.module/alpha/api/Alpha.java",
                """
                package alpha.api;
                public class Alpha { public beta.api.Beta beta() { return null; } }
                """);
        write("alpha.module/alpha/internal/Hidden.java",
                """
                package alpha.internal;
                public class Hidden {}
                """);
        write("beta.module/module-info.java",
                """
                module beta.module { exports beta.api; }
                """);
        write("beta.module/beta/api/Beta.java",
                """
                package beta.api;
                public class Beta {}
                """);
        write("beta.module/beta/internal/Hidden.java",
                """
                package beta.internal;
                public class Hidden {}
                """);
        write("gamma.module/module-info.java",
                """
                module gamma.module { exports gamma.api; }
                """);
        write("gamma.module/gamma/api/Gamma.java",
                """
                package gamma.api;
                public class Gamma {}
                """);
        write("gamma.module/gamma/internal/Hidden.java",
                """
                package gamma.internal;
                public class Hidden {}
                """);

        Files.createDirectories(MODULES);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
            var sources = fileManager.getJavaFileObjectsFromPaths(Files.walk(SOURCES)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList());
            assertTrue(compiler.getTask(
                                       null,
                                       fileManager,
                                       null,
                                       List.of("--module-source-path", SOURCES.toString(), "-d", MODULES.toString()),
                                       null,
                                       sources)
                               .call());
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_ERR)
    void resolvesSourceModuleGraphOncePerInvocation() throws Exception {
        var sources = Path.of("build/test-single-module-resolution/src");
        var descriptor = sources.resolve("diagnostic.module/module-info.java");
        Files.createDirectories(descriptor.getParent());
        // Javac reports this malformed escape on each parse while still producing the module tree.
        Files.writeString(descriptor, "module diagnostic.module {} // \\u00G0\n");

        var diagnostics = new ByteArrayOutputStream();
        var originalError = System.err;
        Result result;
        try {
            System.setErr(new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
            result = run("--module-source-path", sources.toString(), "--module", "diagnostic.module", "--source",
                    "none", "java.lang.String");
        } finally {
            System.setErr(originalError);
        }

        var diagnosticOutput = diagnostics.toString(StandardCharsets.UTF_8);
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(1, diagnosticOutput.split("error: illegal unicode escape", -1).length - 1, diagnosticOutput);
    }

    @Test
    void selectedModuleReachesRequiredModulesButNotUnrelatedModules() {
        var dependency = run("--module-path", MODULES.toString(), "--module", "alpha.module", "-s",
                "none", "beta.api.Beta");
        var unrelated = run("--module-path", MODULES.toString(), "--module", "alpha.module", "-s",
                "none", "gamma.api.Gamma");

        assertEquals(0, dependency.exitCode(), dependency.error());
        assertEquals(1, unrelated.exitCode());
        assertTrue(unrelated.error().contains("no symbol prefix matches"),
                unrelated.error());
    }

    @Test
    void modulePathDoesNotRootModulesAndTheSelectedModuleSeesItsOwnPackages() {
        var unrooted = run("--module-path", MODULES.toString(), "-s", "none", "alpha.api.Alpha");
        var selectedInternal = run("--module-path", MODULES.toString(), "--module", "alpha.module", "-s",
                "none", "alpha.internal.Hidden");

        assertEquals(1, unrooted.exitCode());
        assertEquals(0, selectedInternal.exitCode(), selectedInternal.error());
    }

    @Test
    void selectedModuleListPreservesEachCompilationContext() {
        var firstInternal = run("--module-path", MODULES.toString(), "--module", "alpha.module,gamma.module", "-s",
                "none", "alpha.internal.Hidden");
        var secondInternal = run("--module-path", MODULES.toString(), "--module", "alpha.module,gamma.module", "-s",
                "none", "gamma.internal.Hidden");
        var exportWithoutRead = run("--module-path", MODULES.toString(), "--module", "alpha.module,gamma.module", "--add-exports",
                "beta.module/beta.internal=gamma.module", "-s", "none", "beta.internal.Hidden");
        var qualifiedExport = run(
                "--module-path",
                MODULES.toString(),
                "--module",
                "alpha.module,gamma.module",
                "--add-reads",
                "gamma.module=beta.module",
                "--add-exports",
                "beta.module/beta.internal=gamma.module",
                "-s",
                "none",
                "beta.internal.Hidden");
        var sourceOnly = run("--module-source-path", SOURCES.toString(), "--module", "alpha.module,gamma.module", "--source",
                "definition", "gamma.internal.Hidden");

        assertEquals(0, firstInternal.exitCode(), firstInternal.error());
        assertEquals(0, secondInternal.exitCode(), secondInternal.error());
        assertEquals(1, exportWithoutRead.exitCode());
        assertEquals(0, qualifiedExport.exitCode(), qualifiedExport.error());
        assertEquals(0, sourceOnly.exitCode(), sourceOnly.error());
        assertTrue(sourceOnly.output().contains("public class Hidden {}"),
                sourceOnly.output());
    }

    @Test
    void limitModulesConstrainsSystemLookup() {
        var excluded = run("--limit-modules", "java.base", "-s", "none", "java.sql.Driver");
        var included = run("--limit-modules", "java.base", "-s", "none", "java.lang.String");

        assertEquals(1, excluded.exitCode());
        assertEquals(0, included.exitCode(), included.error());
    }

    @Test
    void explicitSystemImageUsesItsModuleDescriptors() {
        var result = run("--system", System.getProperty("java.home"), "-s", "none", "java.lang.String.isEmpty");

        assertEquals(0, result.exitCode(), result.error());
    }

    @Test
    void dependencyInternalsRequireAnExportToTheSelectedModule() {
        var hidden = run("--module-path", MODULES.toString(), "--module", "alpha.module", "-s",
                "none", "beta.internal.Hidden");
        var exported = run("--module-path", MODULES.toString(), "--module", "alpha.module", "--add-exports",
                "beta.module/beta.internal=alpha.module", "-s", "none", "beta.internal.Hidden");
        var exportedElsewhere = run("--module-path", MODULES.toString(), "--module", "alpha.module", "--add-exports",
                "beta.module/beta.internal=gamma.module", "-s", "none", "beta.internal.Hidden");

        assertEquals(1, hidden.exitCode());
        assertEquals(0, exported.exitCode(), exported.error());
        assertEquals(1, exportedElsewhere.exitCode());
    }

    @Test
    void addedModulesStillRequireAReadEdgeFromTheSelectedModule() {
        var unreadable = run("--module-path", MODULES.toString(), "--module", "alpha.module", "--add-modules",
                "gamma.module", "-s", "none", "gamma.api.Gamma");
        var readable = run(
                "--module-path",
                MODULES.toString(),
                "--module",
                "alpha.module",
                "--add-modules",
                "gamma.module",
                "--add-reads",
                "alpha.module=gamma.module",
                "-s",
                "none",
                "gamma.api.Gamma");

        assertEquals(1, unreadable.exitCode());
        assertEquals(0, readable.exitCode(), readable.error());
    }

    @Test
    void selectedModulesReadTheClassPathOnlyThroughAllUnnamed() {
        var classPath = MODULES.resolve("gamma.module").toString();
        var unreadable = run("--class-path", classPath, "--module-path", MODULES.toString(), "--module",
                "alpha.module", "-s", "none", "gamma.api.Gamma");
        var readable = run(
                "--class-path",
                classPath,
                "--module-path",
                MODULES.toString(),
                "--module",
                "alpha.module",
                "--add-reads",
                "alpha.module=ALL-UNNAMED",
                "-s",
                "none",
                "gamma.api.Gamma");

        assertEquals(1, unreadable.exitCode());
        assertEquals(0, readable.exitCode(), readable.error());
    }

    @Test
    void moduleSourcePathFollowsTheResolvedModuleGraph() {
        var selected = run("--module-path", MODULES.toString(), "--module-source-path", SOURCES.toString(),
                "--module", "alpha.module", "--source", "definition", "alpha.api.Alpha.beta");
        var unrelated = run("--module-path", MODULES.toString(), "--module-source-path", SOURCES.toString(),
                "--module", "alpha.module", "--source", "definition", "gamma.api.Gamma");

        assertEquals(0, selected.exitCode(), selected.error());
        assertTrue(selected.output().contains("public beta.api.Beta beta() { return null; }"),
                selected.output());
        assertEquals(1, unrelated.exitCode());
    }

    @Test
    void selectedModuleCanResolveFromModuleSourcePathWithoutCompiledOutput() {
        var selected = run("--module-source-path", SOURCES.toString(), "--module", "alpha.module", "--source",
                "definition", "alpha.api.Alpha.beta");
        var unrelated = run("--module-source-path", SOURCES.toString(), "--module", "alpha.module", "--source",
                "definition", "gamma.api.Gamma");

        assertEquals(0, selected.exitCode(), selected.error());
        assertTrue(selected.output().contains("public beta.api.Beta beta() { return null; }"),
                selected.output());
        assertEquals(1, unrelated.exitCode());
    }

    private static void write(String relativePath, String source) throws Exception {
        var file = SOURCES.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static Result run(String... args) {
        var output = new StringWriter();
        var error = new StringWriter();
        int exitCode = new Jist().run(false, new PrintWriter(output), new PrintWriter(error), args);
        return new Result(exitCode, output.toString(), error.toString());
    }

    private record Result(int exitCode, String output, String error) {}
}
