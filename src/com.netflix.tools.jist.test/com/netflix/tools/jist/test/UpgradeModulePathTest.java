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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeModulePathTest {
    private static final Path ROOT = Path.of("build/test-upgrade-module-path");
    private static final Path UPGRADE = ROOT.resolve("upgrade");
    private static final Path MODULES = ROOT.resolve("modules");
    private static final Path SOURCES = ROOT.resolve("src");

    @BeforeAll
    static void compileModules() throws Exception {
        for (var variant : List.of("upgrade", "ordinary")) {
            var sources = ROOT.resolve(variant + "-src");
            write(sources.resolve("java.logging/module-info.java"), "module java.logging { exports upgrade.api; }");
            if (variant.equals("upgrade")) {
                write(sources.resolve("java.logging/upgrade/api/Replacement.java"), """
                        package upgrade.api;
                        public class Replacement {
                            public void target() {}
                            public String caller() { return "value".repeat(2); }
                        }
                        """);
            } else {
                write(sources.resolve("java.logging/upgrade/api/Fallback.java"), "package upgrade.api; public class Fallback {}");
            }
            var output = variant.equals("upgrade") ? UPGRADE : MODULES;
            Files.createDirectories(output);
            var compiler = ToolProvider.getSystemJavaCompiler();
            try (var manager = compiler.getStandardFileManager(null, null, null);
                    var files = Files.walk(sources)) {
                assertTrue(compiler.getTask(null, manager, null,
                        List.of("-g", "--module-source-path", sources.toString(), "-d", output.toString()), null,
                        manager.getJavaFileObjectsFromPaths(files.filter(path -> path.toString().endsWith(".java")).toList())).call());
            }
        }
        write(SOURCES.resolve("application/module-info.java"), "module application { requires java.logging; }");
        write(SOURCES.resolve("application/application/Caller.java"), """
                package application;
                public class Caller {
                    public void run() { new upgrade.api.Replacement().target(); }
                }
                """);
    }

    @Test
    void upgradePathTakesPrecedenceOverSystemAndOrdinaryModulePaths() {
        for (var selection : List.of("--module", "--add-modules")) {
            var result = run("--upgrade-module-path", UPGRADE.toString(), "--module-path", MODULES.toString(),
                    selection, "java.logging", "--source", "none", "--kind", "type", "-l", "upgrade.api");
            assertEquals(0, result.code(), result.error());
            assertEquals("class upgrade.api.Replacement\n", result.output());
            var original = run("--upgrade-module-path", UPGRADE.toString(), selection, "java.logging", "--source", "none",
                    "--kind", "type", "-l", "java.util.logging");
            assertEquals(1, original.code(), original.error());
            assertEquals("", original.output());
            assertTrue(original.error().contains("no symbol prefix matches"), original.error());
        }
    }

    @Test
    void ordinaryModulePathDoesNotReplaceASystemModule() {
        var result = run("--module-path", MODULES.toString(), "--module", "java.logging", "--source", "none", "-l",
                "--kind", "type", "java.util.logging.Logger");
        assertEquals(0, result.code(), result.error());
        assertEquals("class java.util.logging.Logger\n", result.output());
        var masked = run("--module-path", MODULES.toString(), "--module", "java.logging", "--source", "none", "-l",
                "--kind", "type", "upgrade.api.Fallback");
        assertEquals(1, masked.code(), masked.output());
    }

    @Test
    void compiledCallersAndAttributionUseTheUpgradedModule() {
        var result = run("--upgrade-module-path", UPGRADE.toString(), "--module", "java.logging",
                "--source", "none", "-l", "--callers", "java.lang.String.repeat");
        assertEquals(0, result.code(), result.error());
        assertTrue(result.output().contains("method upgrade.api.Replacement.caller()\n"), result.output());
        assertFalse(result.output().contains("java.util.logging"), result.output());
    }

    @Test
    void sourceAttributionResolvesTypesFromTheUpgradePath() {
        var result = run("--upgrade-module-path", UPGRADE.toString(), "--module-source-path", SOURCES.toString(),
                "--module", "application", "--limit-modules", "java.base,java.logging,application",
                "--source", "none", "-l", "--callers", "upgrade.api.Replacement.target");
        assertEquals(0, result.code(), result.error());
        assertEquals("method application.Caller.run()\n", result.output());
    }

    @Test
    void compilationContextsDoNotEnforceRuntimeModuleHashes() throws Exception {
        var sources = ROOT.resolve("hash-src");
        var compiled = ROOT.resolve("hash-modules");
        var jars = Files.createDirectories(ROOT.resolve("hashed"));
        write(sources.resolve("hash.base/module-info.java"), "module hash.base {}");
        write(sources.resolve("hash.application/module-info.java"), "module hash.application { requires hash.base; exports hash.api; }");
        write(sources.resolve("hash.application/hash/api/Original.java"), "package hash.api; public class Original {}");
        compile(sources, compiled, List.of("--module-source-path", sources.toString()));
        var upgradedSources = ROOT.resolve("hash-upgrade-src");
        var upgraded = ROOT.resolve("hash-upgrade");
        write(upgradedSources.resolve("hash.application/module-info.java"), "module hash.application { requires hash.base; exports hash.api; }");
        write(upgradedSources.resolve("hash.application/hash/api/Replacement.java"), "package hash.api; public class Replacement {}");
        compile(upgradedSources, upgraded, List.of("--module-path", compiled.toString(), "--module-source-path", upgradedSources.toString()));
        var jar = java.util.spi.ToolProvider.findFirst("jar").orElseThrow();
        var output = new StringWriter();
        var error = new StringWriter();
        assertEquals(0, jar.run(new PrintWriter(output), new PrintWriter(error), "--create", "--file", jars.resolve("application.jar").toString(),
                "-C", compiled.resolve("hash.application").toString(), "."), error.toString());
        assertEquals(0, jar.run(new PrintWriter(output), new PrintWriter(error), "--create", "--file", jars.resolve("base.jar").toString(),
                "--module-path", jars.toString(), "--hash-modules", "hash.application", "-C", compiled.resolve("hash.base").toString(), "."), error.toString());
        var result = run("--module-path", jars.toString(), "--upgrade-module-path", upgraded.toString(), "--module", "hash.application",
                "--limit-modules", "java.base,hash.base,hash.application", "--source", "none", "--kind", "type", "-l", "hash.api");
        assertEquals(0, result.code(), result.error());
        assertEquals("class hash.api.Replacement\n", result.output());
    }

    private static void compile(Path sources, Path output, List<String> options) throws Exception {
        Files.createDirectories(output);
        var arguments = new ArrayList<>(options);
        arguments.addAll(List.of("-d", output.toString()));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null); var files = Files.walk(sources)) {
            assertTrue(compiler.getTask(null, manager, null, arguments, null,
                    manager.getJavaFileObjectsFromPaths(files.filter(path -> path.toString().endsWith(".java")).toList())).call());
        }
    }

    @Test
    void upgradePathIsDiscoverableAndUsedForCompletion() {
        assertEquals(1, new Jist().isSupportedOption("--upgrade-module-path"));
        var output = new StringWriter();
        var error = new StringWriter();
        int result = new Jist().run(new PrintWriter(output), new PrintWriter(error), "__complete", "--upgrade-module-path",
                UPGRADE.toString(), "--module", "java.logging", "--kind", "type", "upgrade.api.Re");
        assertEquals(0, result, error.toString());
        assertTrue(output.toString().contains("upgrade.api.Replacement\tclass\n"), output.toString());
    }

    private static void write(Path file, String source) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static Result run(String... options) {
        var arguments = new ArrayList<>(List.of("--limit-modules", "java.base,java.logging"));
        arguments.addAll(List.of(options));
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), arguments.toArray(String[]::new));
        return new Result(code, output.toString(), error.toString());
    }

    private record Result(int code, String output, String error) {}
}
