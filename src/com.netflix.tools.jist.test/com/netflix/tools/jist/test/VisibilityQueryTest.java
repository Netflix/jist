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

class VisibilityQueryTest {
    private static final Path ROOT = Path.of("build/test-visibility-query");
    private static final Path SOURCES = ROOT.resolve("src");
    private static final Path CLASSES = ROOT.resolve("classes");
    private static final Path OWNER = SOURCES.resolve("example/Owner.java");

    @BeforeAll
    static void compileFixture() throws Exception {
        Files.createDirectories(OWNER.getParent());
        Files.createDirectories(CLASSES);
        Files.writeString(OWNER, """
                package example;
                public class Owner {
                    public void publicMethod() {}
                    protected void protectedMethod() {}
                    void packageMethod() {}
                    private void privateMethod() {}
                    public int publicField;
                    protected int protectedField;
                    int packageField;
                    private int privateField;
                    protected static class ProtectedNested {}
                    private static class PrivateNested {
                        private void nestedPrivateMethod() {}
                    }
                }
                """);
        var root = OWNER.resolveSibling("Root.java");
        var hidden = OWNER.resolveSibling("Hidden.java");
        var leaf = OWNER.resolveSibling("Leaf.java");
        Files.writeString(root, "package example; public interface Root { void run(); }");
        Files.writeString(hidden, "package example; abstract class Hidden implements Root { public abstract void run(); }");
        Files.writeString(leaf, "package example; public class Leaf extends Hidden { public void run() {} }");
        var face = OWNER.resolveSibling("Face.java");
        Files.writeString(face, """
                package example;
                public interface Face {
                    void implicitPublic();
                    private void helper() {}
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-d", CLASSES.toString()), null,
                    manager.getJavaFileObjects(OWNER, root, hidden, leaf, face)).call());
        }
    }

    @Test
    void eachVisibilitySelectsOnlyThatLevelInCompiledAndSourceDeclarations() {
        for (boolean source : List.of(false, true)) {
            for (var visibility : List.of("public", "protected", "package", "private")) {
                var result = members(source, "--visibility", visibility);
                assertSuccess(result);
                assertEquals(List.of("method example.Owner." + visibility + "Method()"), result.output().lines().toList());
            }
        }
    }

    @Test
    void repeatedVisibilitySelectionsFormAUnion() {
        for (boolean source : List.of(false, true)) {
            var result = members(source, "--visibility", "public", "--visibility", "protected", "--visibility", "public");
            assertSuccess(result);
            assertEquals(2, result.output().lines().count(), result.output());
            assertTrue(result.output().contains("publicMethod"), result.output());
            assertTrue(result.output().contains("protectedMethod"), result.output());
            assertFalse(result.output().contains("privateMethod"), result.output());
        }
    }

    @Test
    void allIncludesEveryLevelRegardlessOfOptionOrder() {
        for (boolean source : List.of(false, true)) {
            for (var options : List.of(List.of("--visibility", "all", "--visibility", "public"),
                    List.of("--visibility", "private", "--visibility", "all"))) {
                var result = members(source, options.toArray(String[]::new));
                assertSuccess(result);
                assertEquals(4, result.output().lines().count(), result.output());
            }
        }
    }

    @Test
    void defaultsRemainPublicAndProtectedForLibrariesAndAllForProjectSource() {
        var compiled = members(false);
        assertSuccess(compiled);
        assertEquals(2, compiled.output().lines().count(), compiled.output());
        var source = members(true);
        assertSuccess(source);
        assertEquals(4, source.output().lines().count(), source.output());
    }

    @Test
    void privateFieldsAndNestedDeclarationsDoNotRequireSelectingTheirOwners() {
        for (boolean source : List.of(false, true)) {
            var arguments = context(source);
            arguments.addAll(List.of("--visibility", "private", "--kind", "field", "example.Owner"));
            var fields = run(arguments);
            assertSuccess(fields);
            assertEquals("field example.Owner.privateField\n", fields.output());
            arguments = context(source);
            arguments.addAll(List.of("--visibility", "private", "--kind", "method", "example.Owner.PrivateNested"));
            var nested = run(arguments);
            assertSuccess(nested);
            assertTrue(nested.output().contains("nestedPrivateMethod"), nested.output());
        }
    }

    @Test
    void sourceScopesCanWidenASelectedMemberToItsUnselectedOwner() {
        var arguments = context(true);
        arguments.remove("-l");
        arguments.addAll(List.of("--visibility", "private", "--kind", "method", "--source", "type", "example.Owner"));
        var result = run(arguments);
        assertSuccess(result);
        assertTrue(result.output().contains("public class Owner"), result.output());
        assertTrue(result.output().contains("private void privateMethod"), result.output());
    }

    @Test
    void hierarchyTraversalPassesThroughExcludedVisibilities() {
        for (boolean source : List.of(false, true)) {
            var arguments = context(source);
            arguments.addAll(List.of("--visibility", "public", "--subtypes", "example.Root"));
            var result = run(arguments);
            assertSuccess(result);
            assertEquals("class example.Leaf\n", result.output());
            arguments = context(source);
            arguments.addAll(List.of("--visibility", "public", "--overrides", "example.Root.run"));
            var methods = run(arguments);
            assertSuccess(methods);
            assertTrue(methods.output().contains("method example.Leaf.run()"), methods.output());
        }
    }

    @Test
    void implicitInterfaceVisibilityDoesNotHidePrivateMethods() {
        for (boolean source : List.of(false, true)) {
            var arguments = context(source);
            arguments.addAll(List.of("--visibility", "private", "--kind", "method", "example.Face"));
            var result = run(arguments);
            assertSuccess(result);
            assertEquals("method example.Face.helper()\n", result.output());
        }
        var result = run(List.of("--source-path", SOURCES.toString(), "--system", "none",
                "--source", "none", "--visibility", "public", "--kind", "method", "-l"));
        assertSuccess(result);
        assertTrue(result.output().contains("method example.Face.implicitPublic()\n"), result.output());
        assertFalse(result.output().contains("example.Face.helper"), result.output());
    }

    @Test
    void oldFlagsAreNotAliases() {
        for (var option : List.of("-public", "-protected", "-package", "-private")) {
            var result = run(List.of(option));
            assertEquals(1, result.code());
            assertTrue(result.error().contains("Unknown option: " + option), result.error());
            assertEquals(-1, new Jist().isSupportedOption(option));
        }
    }

    @Test
    void visibilityOptionsAreDiscoverableAndComplete() {
        assertEquals(1, new Jist().isSupportedOption("--visibility"));
        var result = run(List.of("__complete", "--visibility", "pr"));
        assertSuccess(result);
        assertEquals(List.of("private\tInclude declarations with this visibility", "protected\tInclude declarations with this visibility", ":0"),
                result.output().lines().toList());
        for (var options : List.of(List.of("--visibility"), List.of("--visibility", "internal"))) {
            var invalid = run(options);
            assertEquals(1, invalid.code());
            assertEquals("", invalid.output());
        }
    }

    private static Result members(boolean source, String... options) {
        var arguments = context(source);
        arguments.addAll(List.of("--kind", "method", "example.Owner"));
        arguments.addAll(List.of(options));
        var result = run(arguments);
        var methods = result.output().lines()
                .filter(line -> line.matches("method example\\.Owner\\.[a-z]+Method\\(\\)"))
                .toList();
        return new Result(result.code(), methods.isEmpty() ? "" : String.join("\n", methods) + "\n", result.error());
    }

    private static ArrayList<String> context(boolean source) {
        var arguments = new ArrayList<>(List.of("--limit-modules", "java.base", "-l"));
        if (source) {
            arguments.addAll(List.of("--source-path", SOURCES.toString()));
        } else {
            arguments.addAll(List.of("--class-path", CLASSES.toString()));
        }
        return arguments;
    }

    private static Result run(List<String> arguments) {
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
