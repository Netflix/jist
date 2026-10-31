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
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallQueryTest {
    private static final Path ROOT = Path.of("build/test-call-query");
    private static final Path SOURCES = ROOT.resolve("src");
    private static final Path CLASSES = ROOT.resolve("classes");

    @BeforeAll
    static void compileFixtures() throws Exception {
        var directory = Files.createDirectories(SOURCES.resolve("example"));
        Files.createDirectories(CLASSES);
        var target = directory.resolve("Target.java");
        Files.writeString(target, """
                package example;
                public class Target {
                    public Target() {}
                    public void touch() {}
                    public void touch(int value) {}
                    public static void make() {}
                    public void unused() {}
                }
                """);
        var caller = directory.resolve("Caller.java");
        Files.writeString(caller, """
                package example;
                public class Caller {
                    public void run(Target target) {
                        target.touch();
                        target.touch(1);
                        Target.make();
                        new Target();
                    }
                    public void twice(Target target) { target.touch(); target.touch(); }
                    public Runnable reference(Target target) { return target::touch; }
                    public void lambda(Target target) { Runnable task = () -> target.touch(); }
                    private void hidden(Target target) { target.touch(); }
                    public void unrelated(Other other) { other.touch(); }
                    public void inherited(Inherited target) { target.unused(); }
                    public static class Nested {
                        public void run(Target target) { target.unused(); }
                    }
                }
                class Other { void touch() {} }
                class Inherited extends Target {}
                """);
        var override = directory.resolve("Override.java");
        Files.writeString(override, """
                package example;
                public class Override extends Target {
                    @java.lang.Override public void touch() {}
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-g", "-d", CLASSES.toString()), null,
                    manager.getJavaFileObjects(target, caller, override)).call());
        }
    }

    @Test
    void callersContainInvocationSitesAndTheirEnclosingExecutables() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callers", "example.Target.touch", "--visibility", "all");
            assertSuccess(result);
            assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.touch()"), result.output());
            assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.touch(int)"), result.output());
            assertTrue(result.output().contains("example.Caller.twice(example.Target) -> example.Target.touch()"), result.output());
            assertTrue(result.output().contains("Caller.java:4"), result.output());
            assertFalse(result.output().contains("example.Caller.reference"), result.output());
            assertFalse(result.output().contains("example.Caller.unrelated"), result.output());
            assertFalse(result.output().contains("example.Override.touch() ->"), result.output());
        }
    }

    @Test
    void typeCallersPreserveEachCallerToCalleeRelationship() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callers", "example.Target", "--visibility", "all");
            assertSuccess(result);
            assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.touch()"), result.output());
            assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.touch(int)"), result.output());
            assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.make()"), result.output());
            assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.new()"), result.output());
            assertTrue(result.output().contains("example.Caller.inherited(example.Inherited) -> example.Target.unused()"), result.output());
        }
    }

    @Test
    void calleesIncludeMethodsAndConstructorsButNotParameterTypesOrReferences() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callees", "example.Caller.run");
            assertSuccess(result);
            assertEquals(4, result.output().lines().count(), result.output());
            assertTrue(result.output().contains("-> example.Target.touch()"), result.output());
            assertTrue(result.output().contains("-> example.Target.touch(int)"), result.output());
            assertTrue(result.output().contains("-> example.Target.make()"), result.output());
            assertTrue(result.output().contains("-> example.Target.new()"), result.output());
            var reference = run(source, "--callees", "example.Caller.reference");
            assertSuccess(reference);
            assertFalse(reference.output().contains("-> example.Target.touch"), reference.output());
        }
    }

    @Test
    void typeCalleesPreserveEachCallerToCalleeRelationship() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callees", "example.Caller", "--visibility", "all");
            assertSuccess(result);
            assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.touch()"), result.output());
            assertTrue(result.output().contains("example.Caller.twice(example.Target) -> example.Target.touch()"), result.output());
            assertTrue(result.output().contains("example.Caller.lambda(example.Target) -> example.Target.touch()"), result.output());
            assertTrue(result.output().contains("example.Caller.unrelated(example.Other) -> example.Other.touch()"), result.output());
            assertFalse(result.output().contains("example.Caller.Nested.run"), result.output());
        }
    }

    @Test
    void callersAndCalleesCanBeRepeatedAndCombined() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callers", "example.Target.make", "--callees", "example.Caller.run",
                    "--callers", "example.Target.make", "--callees", "example.Caller.run");
            assertSuccess(result);
            assertEquals(4, result.output().lines().count(), result.output());
            assertEquals(4, result.output().lines().distinct().count(), result.output());
        }
    }

    @Test
    void listOnlyReturnsMatchingExecutableDeclarationsOnce() {
        for (boolean source : List.of(false, true)) {
            var callers = run(source, "--callers", "example.Target.touch", "--visibility", "all", "-l");
            assertSuccess(callers);
            assertEquals(4, callers.output().lines().count(), callers.output());
            assertTrue(callers.output().contains("method example.Caller.run(example.Target)\n"), callers.output());
            assertFalse(callers.output().contains("->"), callers.output());
            var callees = run(source, "--callees", "example.Caller.run", "-l");
            assertSuccess(callees);
            assertEquals(4, callees.output().lines().count(), callees.output());
            assertTrue(callees.output().contains("method example.Target.new()\n"), callees.output());
        }
    }

    @Test
    void visibilityFiltersResultsRatherThanTheSelectedTarget() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callers", "example.Target.touch", "--visibility", "private", "-l");
            assertSuccess(result);
            assertEquals("method example.Caller.hidden(example.Target)\n", result.output());
        }
    }

    @Test
    void lambdaBodyCallsBelongToTheirLexicallyEnclosingExecutable() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callees", "example.Caller.lambda");
            assertSuccess(result);
            assertEquals(1, result.output().lines().count(), result.output());
            assertTrue(result.output().contains("example.Caller.lambda(example.Target) -> example.Target.touch()"), result.output());
            assertFalse(result.output().contains("lambda$"), result.output());
        }
    }

    @Test
    void sourceScopesWidenTheCallSiteWithoutLosingTheRelationship() {
        var result = run(true, "--callers", "example.Target.make", "--source", "definition");
        assertSuccess(result);
        assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.make()"), result.output());
        assertTrue(result.output().contains("public void run(Target target)"), result.output());
        assertTrue(result.output().contains("new Target();"), result.output());
        assertFalse(result.output().contains("public void unrelated"), result.output());
    }

    @Test
    void nestedCallersUseTheOwningCompilationUnit() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callers", "example.Target.unused");
            assertSuccess(result);
            assertTrue(result.output().startsWith("example.Caller(Caller.java:"), result.output());
            assertTrue(result.output().contains("example.Caller.Nested.run(example.Target) ->"), result.output());
        }
    }

    @Test
    void inheritedCallsResolveToTheDeclaringType() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callers", "example.Target.unused");
            assertSuccess(result);
            assertTrue(result.output().contains("example.Caller.inherited(example.Inherited) -> example.Target.unused()"), result.output());
        }
    }

    @Test
    void constructorSelectionsFindAllocations() {
        for (boolean source : List.of(false, true)) {
            var result = run(source, "--callers", "example.Target.new");
            assertSuccess(result);
            assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.new()"), result.output());
        }
    }

    @Test
    void compiledCallsCanUseSourceArchivesWithoutExtractingThem() throws Exception {
        var archive = ROOT.resolve("sources.jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(archive))) {
            jar.putNextEntry(new java.util.jar.JarEntry("example/Caller.java"));
            Files.copy(SOURCES.resolve("example/Caller.java"), jar);
            jar.closeEntry();
        }
        var result = run(false, "--source-path", archive.toString(), "--callers", "example.Target.make", "--source", "definition");
        assertSuccess(result);
        assertTrue(result.output().contains("-> example.Target.make()"), result.output());
        assertTrue(result.output().contains("public void run(Target target)"), result.output());
        assertTrue(result.output().contains("new Target();"), result.output());
        assertFalse(result.output().contains("public void unrelated"), result.output());
    }

    @Test
    void unrelatedSourceErrorsDoNotPreventCompiledCallSearch(@TempDir Path source) throws Exception {
        var directory = Files.createDirectories(source.resolve("unrelated"));
        Files.writeString(directory.resolve("Broken.java"),
                "package unrelated; public class Broken { MissingType value; }");
        var result = invoke(List.of(
                "--limit-modules", "java.base",
                "--class-path", CLASSES.toString(),
                "--source-path", source.toString(),
                "--callers", "example.Target.make"));
        assertSuccess(result);
        assertTrue(result.output().contains("example.Caller.run(example.Target) -> example.Target.make()"),
                result.output());
    }

    @Test
    void missingDebugInformationDoesNotInventSourceCoordinates(@TempDir Path classes) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null);
                var files = Files.walk(SOURCES)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-g:none", "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromPaths(files.filter(path -> path.toString().endsWith(".java")).toList())).call());
        }
        var result = run(false, "--class-path", classes.toString(), "--callers", "example.Target.make", "--source", "none");
        assertSuccess(result);
        assertEquals("example.Caller: example.Caller.run(example.Target) -> example.Target.make()\n", result.output());
        var callees = run(false, "--class-path", classes.toString(), "--callees", "example.Caller.run", "-l");
        assertSuccess(callees);
        assertEquals(4, callees.output().lines().count(), callees.output());
    }

    @Test
    void invalidSelectionsAreRejectedBeforeAnyOutput() {
        for (var arguments : List.of(
                List.of("--callers", "example.Target.make", "--callees", "example.Caller.missing"),
                List.of("--callees", "example.Missing.run"))) {
            var result = run(false, arguments.toArray(String[]::new));
            assertEquals(1, result.code(), result.error());
            assertEquals("", result.output());
        }
    }

    @Test
    void optionsAreDiscoverableAndCompleteMethodTargets() {
        for (var name : List.of("--callers", "--callees")) {
            assertEquals(1, new Jist().isSupportedOption(name), name);
            var result = invoke(List.of("__complete", "--class-path", CLASSES.toString(), name, "example.Target.to"));
            assertSuccess(result);
            assertTrue(result.output().contains("example.Target.touch\tmethod\n"), result.output());
        }
    }

    private static Result run(boolean source, String... arguments) {
        var options = new ArrayList<>(List.of("--limit-modules", "java.base"));
        options.addAll(source ? List.of("--source-path", SOURCES.toString()) : List.of("--class-path", CLASSES.toString()));
        options.addAll(List.of(arguments));
        return invoke(options);
    }

    private static Result invoke(List<String> arguments) {
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), arguments.toArray(String[]::new));
        return new Result(code, output.toString(), error.toString());
    }

    private static void assertSuccess(Result result) {
        assertEquals(0, result.code(), result.error());
        assertEquals("", result.error());
    }

    private record Result(int code, String output, String error) {}
}
