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

class ModuleCallQueryTest {
    private static final Path ROOT = Path.of("build/test-module-call-query");
    private static final Path SOURCES = ROOT.resolve("src");
    private static final Path MODULES = ROOT.resolve("modules");

    @BeforeAll
    static void compileModules() throws Exception {
        write("library/module-info.java", "module library { exports library.api; exports library.friend to application; }");
        write("library/library/api/Target.java", """
                package library.api;
                public class Target {
                    public static void touch() {}
                    public static void run() { touch(); library.internal.Hidden.hidden(); }
                }
                """);
        write("library/library/internal/Hidden.java", """
                package library.internal;
                public class Hidden {
                    public static void hidden() { library.api.Target.touch(); }
                }
                """);
        write("library/library/friend/Friend.java", """
                package library.friend;
                public class Friend {
                    public static void run() { library.api.Target.touch(); }
                }
                """);
        write("application/module-info.java", "module application { requires library; }");
        write("application/application/internal/Caller.java", """
                package application.internal;
                public class Caller {
                    public static void run() { library.api.Target.touch(); }
                }
                """);
        write("unrelated/module-info.java", "module unrelated { requires library; exports unrelated.api; }");
        write("unrelated/unrelated/api/Other.java", """
                package unrelated.api;
                public class Other {
                    public static void run() { library.api.Target.touch(); }
                }
                """);
        Files.createDirectories(MODULES);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null);
                var files = Files.walk(SOURCES)) {
            assertTrue(compiler.getTask(null, manager, null,
                    List.of("-g", "--module-source-path", SOURCES.toString(), "-d", MODULES.toString()), null,
                    manager.getJavaFileObjectsFromPaths(files.filter(path -> path.toString().endsWith(".java")).toList())).call());
        }
    }

    @Test
    void unnamedContextSeesOnlyUnqualifiedExports() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--add-modules", "library", "--callers", "library.api.Target.touch");
            success(result);
            assertEquals("method library.api.Target.run()\n", result.output());
        }
    }

    @Test
    void selectedModuleSeesItsOwnPackagesAndQualifiedExports() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--module", "application", "--callers", "library.api.Target.touch");
            success(result);
            assertTrue(result.output().contains("method application.internal.Caller.run()\n"), result.output());
            assertTrue(result.output().contains("method library.friend.Friend.run()\n"), result.output());
            assertFalse(result.output().contains("library.internal.Hidden"), result.output());
            assertFalse(result.output().contains("unrelated.api.Other"), result.output());
        }
    }

    @Test
    void addedExportsMustTargetTheCompilationContext() {
        for (boolean source : List.of(false, true)) {
            var visible = run(source, "--module", "application", "--add-exports", "library/library.internal=application",
                    "--callers", "library.api.Target.touch");
            success(visible);
            assertTrue(visible.output().contains("method library.internal.Hidden.hidden()\n"), visible.output());
            var hidden = run(source, "--module", "application", "--add-exports", "library/library.internal=unrelated",
                    "--callers", "library.api.Target.touch");
            success(hidden);
            assertFalse(hidden.output().contains("library.internal.Hidden"), hidden.output());
        }
    }

    @Test
    void addingAnUnreadModuleDoesNotIncludeItsCallers() {
        for (boolean source : List.of(false, true)) {
            var unread = run(source, "--module", "application", "--add-modules", "unrelated",
                    "--callers", "library.api.Target.touch");
            success(unread);
            assertFalse(unread.output().contains("unrelated.api.Other"), unread.output());
            var read = run(source, "--module", "application", "--add-modules", "unrelated", "--add-reads", "application=unrelated",
                    "--callers", "library.api.Target.touch");
            success(read);
            assertTrue(read.output().contains("method unrelated.api.Other.run()\n"), read.output());
        }
    }

    @Test
    void selectingSeveralModulesPreservesEachModulesOwnPackages() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--module", "application,library", "--callers", "library.api.Target.touch");
            success(result);
            assertTrue(result.output().contains("method application.internal.Caller.run()\n"), result.output());
            assertTrue(result.output().contains("method library.internal.Hidden.hidden()\n"), result.output());
        }
    }

    @Test
    void calleesRespectPackageVisibilityToo() {
        for (boolean source : List.of(false, true)) {
            var hidden = run(source, "--module", "application", "--callees", "library.api.Target.run");
            success(hidden);
            assertEquals("method library.api.Target.touch()\n", hidden.output());
            var visible = run(source, "--module", "library", "--callees", "library.api.Target.run");
            success(visible);
            assertTrue(visible.output().contains("method library.internal.Hidden.hidden()\n"), visible.output());
        }
    }

    @Test
    void selectedModulesNeedAReadEdgeToClassPathCallers() {
        var options = List.of("--module-path", MODULES.toString(), "--class-path", MODULES.resolve("unrelated").toString(),
                "--module", "application", "--limit-modules", "java.base,application,library", "--callers", "library.api.Target.touch", "-l");
        var hidden = invoke(options);
        success(hidden);
        assertFalse(hidden.output().contains("unrelated.api.Other"), hidden.output());
        var arguments = new ArrayList<>(options);
        arguments.addAll(List.of("--add-reads", "application=ALL-UNNAMED"));
        var visible = invoke(arguments);
        success(visible);
        assertTrue(visible.output().contains("method unrelated.api.Other.run()\n"), visible.output());
    }

    @Test
    void declarationVisibilityDoesNotOverrideModuleExports() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--module", "application", "--visibility", "all", "--callers", "library.api.Target.touch", "--no-heading");
            success(result);
            assertFalse(result.output().contains("library.internal.Hidden"), result.output());
        }
    }

    @Test
    void jdkCallersExcludeConcealedPackagesUnlessExported() {
        var hidden = invoke(List.of("--limit-modules", "java.base", "--callers", "java.lang.String.repeat", "-l"));
        success(hidden);
        assertTrue(hidden.output().contains("java.lang.String.indent(int)"), hidden.output());
        assertFalse(hidden.output().contains("jdk.internal."), hidden.output());
        var visible = invoke(List.of("--limit-modules", "java.base", "--add-exports", "java.base/jdk.internal.constant=ALL-UNNAMED",
                "--callers", "java.lang.String.repeat", "-l"));
        success(visible);
        assertTrue(visible.output().contains("jdk.internal.constant.ArrayClassDescImpl.displayName()"), visible.output());
    }

    private static void write(String name, String text) throws Exception {
        var file = SOURCES.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Result run(boolean source, String... options) {
        var arguments = new ArrayList<>(List.of("--limit-modules", "java.base,library,application,unrelated", "-l"));
        arguments.addAll(source ? List.of("--module-source-path", SOURCES.toString()) : List.of("--module-path", MODULES.toString()));
        arguments.addAll(List.of(options));
        return invoke(arguments);
    }

    private static Result invoke(List<String> arguments) {
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
