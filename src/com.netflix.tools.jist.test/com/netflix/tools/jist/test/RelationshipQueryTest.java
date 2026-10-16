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
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationshipQueryTest {
    private static final Path ROOT = Path.of("build/test-relationship-query");
    private static final Path SOURCES = ROOT.resolve("src");
    private static final Path CLASSES = ROOT.resolve("classes");

    @BeforeAll
    static void compileFixtures() throws Exception {
        var directory = Files.createDirectories(SOURCES.resolve("example"));
        Files.createDirectories(CLASSES);
        var declarations = List.of(
                "public interface Root<T> { void run(T value); void run(int value); }",
                "public interface Branch extends Root<String> {}",
                "public abstract class Base implements Branch { public abstract void run(String value); public void run(int value) {} public static void helper() {} }",
                "public abstract class Inherited extends Base {}",
                "public class Leaf extends Inherited { public void run(String value) {} public void run(int value) {} public void run(double value) {} }",
                "public class Other implements Root<String> { public void run(String value) {} public void run(int value) {} }",
                "public class Unrelated { public void run(String value) {} }");
        var names = List.of("Root", "Branch", "Base", "Inherited", "Leaf", "Other",
                "Unrelated");
        var files = new ArrayList<Path>();
        for (int i = 0; i < names.size(); i++) {
            var file = directory.resolve(names.get(i) + ".java");
            Files.writeString(file, "package example;\n" + declarations.get(i) + "\n");
            files.add(file);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-d", CLASSES.toString()), null, manager.getJavaFileObjectsFromPaths(files))
                               .call());
        }
    }

    @Test
    void findsTransitiveSubtypesWithoutIncludingTheTarget() {
        var result = run("--subtypes", "example.Root");
        assertSuccess(result);
        assertEquals(
                List.of("class example.Base", "interface example.Branch", "class example.Inherited", "class example.Leaf", "class example.Other"),
                result.output()
                      .lines()
                      .toList());
    }

    @Test
    void findsOnlyExplicitParentsWithDirect() {
        var result = run("--subtypes", "example.Root", "--direct");
        assertSuccess(result);
        assertEquals(List.of("interface example.Branch", "class example.Other"),
                result.output()
                      .lines()
                      .toList());
    }

    @Test
    void unionsRepeatedAndMixedSelectionsWithoutDuplicates() {
        var result = run("--subtypes", "example.Root", "--subtypes", "example.Base", "--supertypes", "example.Leaf",
                "--supertypes", "example.Leaf");
        assertSuccess(result);
        var lines = result.output()
                          .lines()
                          .toList();
        assertEquals(lines.size(),
                lines.stream()
                     .distinct()
                     .count());
        assertTrue(lines.contains("interface example.Root"), result.output());
        assertTrue(lines.contains("class java.lang.Object"), result.output());
        assertTrue(lines.contains("class example.Leaf"), result.output());
        assertFalse(result.output().contains("Unrelated"),
                result.output());
    }

    @Test
    void directSupertypesExcludeTransitiveParents() {
        var result = run("--supertypes", "example.Leaf", "--direct");
        assertSuccess(result);
        assertEquals("class example.Inherited\n", result.output());
    }

    @Test
    void findsOverridingDeclarationsIncludingAbstractAndGenericOverrides() {
        var result = run("--overrides", "example.Root.run");
        assertSuccess(result);
        var lines = result.output()
                          .lines()
                          .toList();
        assertEquals(6, lines.size(), result.output());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("method example.Base.run(java.lang.String)")),
                result.output());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("method example.Leaf.run(java.lang.String)")),
                result.output());
        assertFalse(result.output().contains("double"),
                result.output());
        assertFalse(result.output().contains("Inherited.run"),
                result.output());
        assertFalse(result.output().contains("Unrelated"),
                result.output());
    }

    @Test
    void directOverridesAllowInheritedMethodsButNotInterveningOverrides() {
        var result = run("--overrides", "example.Base.run", "--direct");
        assertSuccess(result);
        assertEquals(
                2,
                result.output()
                      .lines()
                      .count(),
                result.output());
        assertTrue(result.output().contains("example.Leaf.run(java.lang.String)"),
                result.output());
        var root = run("--overrides", "example.Root.run", "--direct");
        assertSuccess(root);
        assertEquals(
                4,
                root.output()
                    .lines()
                    .count(),
                root.output());
        assertFalse(root.output().contains("Leaf.run"),
                root.output());
    }

    @Test
    void filtersRelationshipDeclarationsByKind() {
        var result = run("--subtypes", "example.Root", "--kind", "interface");
        assertSuccess(result);
        assertEquals("interface example.Branch\n", result.output());
    }

    @Test
    void emptyRelationshipsSucceed() {
        var result = run("--subtypes", "example.Leaf");
        assertSuccess(result);
        assertEquals("", result.output());
    }

    @Test
    void validatesAllSelectionsBeforeEmittingResults() {
        var result = run("--subtypes", "example.Root", "--subtypes", "example.Missing");
        assertEquals(1, result.code());
        assertEquals("", result.output());
        assertTrue(result.error().contains("example.Missing"),
                result.error());
    }

    @Test
    void rejectsMembersForTypeRelationshipsAndNonOverridableMembers() {
        for (var selection : List.of(List.of("--subtypes", "example.Root.run"), List.of("--overrides", "example.Base.helper"), List.of("--overrides", "example.Leaf.new"),
                List.of("--direct"))) {
            var result = run(selection.toArray(String[]::new));
            assertEquals(1, result.code(), selection + ": " + result.error());
            assertEquals("", result.output());
        }
    }

    @Test
    void relationshipOptionsAreDiscoverable() {
        var tool = new Jist();
        for (var name : List.of("--subtypes", "--supertypes", "--overrides")) {
            assertEquals(1, tool.isSupportedOption(name), name);
        }
        assertEquals(0, tool.isSupportedOption("--direct"));
    }

    @Test
    void rendersOnlySelectedCompiledDeclarations() {
        var result = render("--overrides", "example.Root.run", "--source", "none");
        assertSuccess(result);
        assertEquals(
                6,
                result.output()
                      .lines()
                      .count(),
                result.output());
        assertFalse(result.output().contains("double"),
                result.output());
        assertFalse(result.output().contains("class "),
                result.output());
        assertFalse(result.output().contains("helper"),
                result.output());
    }

    @Test
    void sourceOnlyRelationshipsUseTheSameSelections() {
        var result = run("--class-path", ROOT.resolve("empty").toString(),
                "--source-path", SOURCES.toString(), "--overrides", "example.Root.run");
        assertSuccess(result);
        assertEquals(
                6,
                result.output()
                      .lines()
                      .count(),
                result.output());
        assertFalse(result.output().contains("double"),
                result.output());
        var types = run("--class-path", ROOT.resolve("empty").toString(),
                "--source-path", SOURCES.toString(), "--subtypes", "example.Root");
        assertSuccess(types);
        assertEquals(
                5,
                types.output()
                     .lines()
                     .count(),
                types.output());
    }

    @Test
    void sourceNoneStillRendersMatchingSourceOnlyDeclarations() {
        var result = render(
                "--class-path",
                ROOT.resolve("empty").toString(),
                "--source-path",
                SOURCES.toString(),
                "--source",
                "none",
                "--overrides",
                "example.Root.run");
        assertSuccess(result);
        assertEquals(
                6,
                result.output()
                      .lines()
                      .count(),
                result.output());
        assertFalse(result.output().contains("double"),
                result.output());
    }

    @Test
    void rendersDefinitionsUsingExistingSourceScopes() {
        var result = render("--source-path", SOURCES.toString(), "--source", "definition", "--overrides",
                "example.Root.run");
        assertSuccess(result);
        assertTrue(result.output().contains("Leaf.java:2"),
                result.output());
        assertTrue(result.output().contains("Other.java:2"),
                result.output());
        assertFalse(result.output().contains("Unrelated.java"),
                result.output());
    }

    @Test
    void currentSourceRelationshipsReplaceStaleClassFiles(@TempDir Path source) throws Exception {
        var directory = Files.createDirectories(source.resolve("example"));
        Files.writeString(directory.resolve("Leaf.java"),
                """
                package example;
                public class Leaf extends Unrelated {
                    @Override public void run(String value) {}
                }
                """);
        var oldParent = run("--source-path", source.toString(), "--subtypes", "example.Root");
        assertSuccess(oldParent);
        assertFalse(oldParent.output().contains("Leaf"),
                oldParent.output());
        var newParent = run("--source-path", source.toString(), "--subtypes", "example.Unrelated");
        assertSuccess(newParent);
        assertEquals("class example.Leaf\n", newParent.output());
        var override = run("--source-path", source.toString(), "--overrides", "example.Unrelated.run");
        assertSuccess(override);
        assertEquals(
                1,
                override.output()
                        .lines()
                        .count(),
                override.output());
        assertTrue(override.output().contains("example.Leaf.run(String)"),
                override.output());
    }

    @Test
    void repeatedClassPathEntriesDoNotDuplicateDeclarations() {
        var result = run("--class-path", CLASSES + File.pathSeparator + CLASSES, "--overrides", "example.Root.run", "--overrides",
                "example.Base.run");
        assertSuccess(result);
        assertEquals(
                6,
                result.output()
                      .lines()
                      .count(),
                result.output());
    }

    @Test
    void searchesNamedModules(@TempDir Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("src/relations/example"));
        var descriptor = sources.getParent().resolve("module-info.java");
        Files.writeString(descriptor, "module relations { exports example; }");
        var files = new ArrayList<Path>(List.of(descriptor));
        try (var entries = Files.list(SOURCES.resolve("example"))) {
            for (var entry : entries.toList()) {
                var copy = sources.resolve(entry.getFileName());
                Files.copy(entry, copy);
                files.add(copy);
            }
        }
        var classes = directory.resolve("classes");
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-d", classes.toString()), null, manager.getJavaFileObjectsFromPaths(files))
                               .call());
        }
        var result = run(
                "--class-path",
                ROOT.resolve("empty").toString(),
                "--module-path",
                classes.toString(),
                "--module",
                "relations",
                "--limit-modules",
                "relations,java.base",
                "--overrides",
                "example.Root.run");
        assertSuccess(result);
        assertEquals(
                6,
                result.output()
                      .lines()
                      .count(),
                result.output());
        var sourceOnly = run(
                "--class-path",
                ROOT.resolve("empty").toString(),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "relations",
                "--limit-modules",
                "relations,java.base",
                "--subtypes",
                "example.Root");
        assertSuccess(sourceOnly);
        assertEquals(
                5,
                sourceOnly.output()
                          .lines()
                          .count(),
                sourceOnly.output());
    }

    @Test
    void searchesJarsAndReadsAttachedSourceWithoutExtraction(@TempDir Path directory) throws Exception {
        var classes = directory.resolve("classes.jar");
        var sources = directory.resolve("sources.jar");
        writeJar(classes, CLASSES);
        writeJar(sources, SOURCES);
        var result = run("--class-path", classes.toString(), "--source-path", sources.toString(),
                "--overrides", "example.Root.run");
        assertSuccess(result);
        assertEquals(
                6,
                result.output()
                      .lines()
                      .count(),
                result.output());
        var definitions = render("--class-path", classes.toString(), "--source-path", sources.toString(),
                "--source", "definition", "--overrides", "example.Root.run");
        assertSuccess(definitions);
        assertTrue(definitions.output().contains("Leaf.java:2"),
                definitions.output());
    }

    @Test
    void searchesAcrossSelectedSourceModules(@TempDir Path directory) throws Exception {
        var sources = directory.resolve("src");
        var contract = Files.createDirectories(sources.resolve("contract/example/api"));
        Files.writeString(contract.getParent()
                .getParent()
                .resolve("module-info.java"),
                "module contract { exports example.api; }");
        Files.writeString(contract.resolve("Contract.java"), "package example.api; public interface Contract { void run(); }");
        var implementation = Files.createDirectories(sources.resolve("implementation/example/impl"));
        Files.writeString(implementation.getParent()
                .getParent()
                .resolve("module-info.java"),
                "module implementation { requires contract; exports example.impl; }");
        Files.writeString(implementation.resolve("Implementation.java"), "package example.impl; public class Implementation implements example.api.Contract { public void run() {} }");
        var result = run(
                "--class-path",
                ROOT.resolve("empty").toString(),
                "--module-source-path",
                sources.toString(),
                "--module",
                "contract,implementation",
                "--limit-modules",
                "contract,implementation,java.base",
                "--overrides",
                "example.api.Contract.run");
        assertSuccess(result);
        assertEquals("method example.impl.Implementation.run()\n", result.output());
    }

    @Test
    void nonRelationshipKindsProduceNoResults() {
        for (var kind : List.of("module", "package", "field")) {
            var result = run("--subtypes", "example.Root", "--kind", kind);
            assertSuccess(result);
            assertEquals("", result.output(), kind);
        }
    }

    @Test
    void combinesTypeAndMethodSelections() {
        var result = run("--subtypes", "example.Root", "--overrides", "example.Root.run");
        assertSuccess(result);
        assertEquals(
                11,
                result.output()
                      .lines()
                      .count(),
                result.output());
        assertEquals(
                11,
                result.output()
                      .lines()
                      .distinct()
                      .count(),
                result.output());
    }

    @Test
    void sourceOnlyBodyFallsBackToTheSelectedSignatures() {
        var result = render(
                "--class-path",
                ROOT.resolve("empty").toString(),
                "--source-path",
                SOURCES.toString(),
                "--source",
                "body",
                "--overrides",
                "example.Root.run");
        assertSuccess(result);
        assertEquals(
                3,
                result.output()
                      .lines()
                      .count(),
                result.output());
        assertTrue(result.output().contains("Leaf.java"),
                result.output());
        assertFalse(result.output().contains("Unrelated.java"),
                result.output());
    }

    @Test
    void findsNestedAndSecondarySourceTypes(@TempDir Path source) throws Exception {
        var directory = Files.createDirectories(source.resolve("example"));
        Files.writeString(directory.resolve("Holder.java"),
                """
                package example;
                public class Holder {
                    public static class Nested extends Base {
                        public void run(String value) {}
                    }
                }
                class Secondary extends Base {
                    public void run(String value) {}
                }
                """);
        var result = run("--source-path", source.toString(), "--subtypes", "example.Base", "--visibility", "all");
        assertSuccess(result);
        assertTrue(result.output().contains("class example.Holder.Nested\n"),
                result.output());
        assertTrue(result.output().contains("class example.Secondary\n"),
                result.output());
        var methods = run("--source-path", source.toString(), "--overrides", "example.Base.run", "--visibility", "all");
        assertSuccess(methods);
        assertTrue(methods.output().contains("method example.Holder.Nested.run(String)\n"),
                methods.output());
        assertTrue(methods.output().contains("method example.Secondary.run(String)\n"),
                methods.output());
        var parents = run("--source-path", source.toString(), "--supertypes", "example.Secondary", "--visibility", "all");
        assertSuccess(parents);
        assertTrue(parents.output().contains("class example.Base\n"), parents.output());
    }

    @Test
    void completesRelationshipTargets() {
        for (var option : List.of("--subtypes", "--supertypes", "--overrides")) {
            var output = new StringWriter();
            var error = new StringWriter();
            var current = option.equals("--overrides") ? "example.Root.ru" : "example.Ro";
            int code = new Jist().run(
                    new PrintWriter(output),
                    new PrintWriter(error),
                    "__complete",
                    "--class-path",
                    CLASSES.toString(),
                    "--limit-modules",
                    "java.base",
                    option,
                    current);
            assertEquals(0, code, error.toString());
            assertTrue(output.toString().contains(option.equals("--overrides") ? "example.Root.run\tmethod\n" : "example.Root\tinterface\n"),
                    output.toString());
            assertEquals("", error.toString());
        }
    }

    @Test
    void excludesPrivateAndPackageInaccessibleOverrides(@TempDir Path source) throws Exception {
        var parent = Files.createDirectories(source.resolve("parent"));
        Files.writeString(parent.resolve("Parent.java"),
                """
                package parent;
                public class Parent {
                    void run() {}
                    private void hidden() {}
                }
                """);
        var child = Files.createDirectories(source.resolve("child"));
        Files.writeString(child.resolve("Child.java"),
                """
                package child;
                public class Child extends parent.Parent {
                    public void run() {}
                    public void hidden() {}
                }
                """);
        var result = run("--source-path", source.toString(), "--visibility", "all", "--overrides", "parent.Parent.run");
        assertSuccess(result);
        assertEquals("", result.output());
        var hidden = run("--source-path", source.toString(), "--overrides", "parent.Parent.hidden");
        assertEquals(1, hidden.code());
        assertEquals("", hidden.output());
        assertTrue(hidden.error().contains("No overridable method"),
                hidden.error());
    }

    @Test
    void unresolvedSourceParentsAreErrorsRatherThanEmptyResults(@TempDir Path source) throws Exception {
        var directory = Files.createDirectories(source.resolve("broken"));
        Files.writeString(directory.resolve("Broken.java"), "package broken; public class Broken extends Missing {}");
        var result = run("--source-path", source.toString(), "--supertypes", "broken.Broken");
        assertEquals(2, result.code(), result.error());
        assertEquals("", result.output());
        assertTrue(result.error().contains("Cannot resolve relationships"),
                result.error());
    }

    private static void writeJar(Path target, Path root) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(target));
             var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(root.relativize(file)
                        .toString()
                        .replace(File.separatorChar, '/')));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
    }

    private static Result run(String... arguments) {
        var options = new ArrayList<>(List.of("-l"));
        options.addAll(List.of(arguments));
        return render(options.toArray(String[]::new));
    }

    private static Result render(String... arguments) {
        var options = new ArrayList<>(List.of("--class-path", CLASSES.toString(), "--limit-modules", "java.base"));
        options.addAll(List.of(arguments));
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), options.toArray(String[]::new));
        return new Result(code, output.toString(), error.toString());
    }

    private static void assertSuccess(Result result) {
        assertEquals(0, result.code(), result.error());
        assertEquals("", result.error());
    }

    private record Result(int code, String output, String error) {}
}
