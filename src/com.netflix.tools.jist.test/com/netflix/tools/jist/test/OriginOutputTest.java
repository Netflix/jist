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
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Jist;
import com.netflix.tools.json.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginOutputTest {
    @TempDir
    static Path directory;
    private static Path sources;
    private static Path modular;
    private static Path automatic;
    private static Path unversioned;
    private static Path attached;

    @BeforeAll
    static void compileLibrary() throws Exception {
        sources = Files.createDirectories(directory.resolve("src"));
        var descriptor = sources.resolve("module-info.java");
        Files.writeString(descriptor, "module example.lib { exports example; }\n");
        var widget = sources.resolve("example/Widget.java");
        Files.createDirectories(widget.getParent());
        Files.writeString(widget, """
                package example;
                public class Widget {
                    public void run() { System.out.println("hello"); }
                    public void run(int count) {}
                }
                """);
        var classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null,
                    List.of("-g", "--module-version", "1.2.3", "-d", classes.toString()), null,
                    manager.getJavaFileObjects(descriptor, widget)).call());
        }
        modular = directory.resolve("library-1.2.3.jar");
        archive(modular, classes, List.of("module-info.class", "example/Widget.class"));
        automatic = directory.resolve("library-2.0.jar");
        archive(automatic, classes, List.of("example/Widget.class"));
        attached = directory.resolve("library-1.2.3-sources.jar");
        archive(attached, sources, List.of("module-info.java", "example/Widget.java"));
        var unversionedClasses = directory.resolve("unversioned-classes");
        assertEquals(0, compiler.run(null, null, null, "-d", unversionedClasses.toString(), descriptor.toString(), widget.toString()));
        unversioned = directory.resolve("library-7.8.9.jar");
        archive(unversioned, unversionedClasses, List.of("module-info.class", "example/Widget.class"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "signature"})
    void classPathOriginRetainsTheDefiningArchiveWithAttachedSource(String scope) {
        var result = json("-l", "--source", scope, "--class-path", modular.toString(),
                "--source-path", attached.toString(), "--kind", "class", "example.Widget");

        assertEquals(0, result.code(), result.error());
        var row = Json.parse(result.output()).get("results").asList().getFirst();
        assertEquals("example.Widget", row.get("symbol").asString());
        assertEquals("class example.Widget(library-1.2.3.jar)", row.get("text").asString());
        var origin = row.get("origin");
        assertEquals("archive", origin.get("kind").asString());
        assertTrue(origin.get("classLocation").asString().contains("library-1.2.3.jar!/example/Widget.class"), result.output());
        assertTrue(origin.tryGet("module").isEmpty(), result.output());
        if (scope.equals("signature")) {
            assertTrue(row.get("source").get("location").asString().contains("library-1.2.3-sources.jar!/example/Widget.java"), result.output());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "signature"})
    void modulePathOriginUsesTheResolvedDescriptor(String scope) {
        var result = json("-l", "--source", scope, "--module-path", modular.toString(),
                "--add-modules", "example.lib", "--source-path", attached.toString(),
                "--kind", "class", "example.Widget");

        assertEquals(0, result.code(), result.error());
        var row = Json.parse(result.output()).get("results").asList().getFirst();
        assertEquals("example.Widget", row.get("symbol").asString());
        assertEquals("class example.lib@1.2.3/example.Widget(library-1.2.3.jar)", row.get("text").asString());
        var origin = row.get("origin");
        assertEquals("example.lib", origin.get("module").asString());
        assertEquals("1.2.3", origin.get("version").asString());
        assertTrue(origin.get("classLocation").asString().contains("library-1.2.3.jar!/example/Widget.class"), result.output());
    }

    @Test
    void automaticModuleUsesItsResolvedIdentity() {
        var result = json("-l", "--source", "none", "--module-path", automatic.toString(),
                "--add-modules", "library", "--kind", "class", "example.Widget");

        assertEquals(0, result.code(), result.error());
        var row = Json.parse(result.output()).get("results").asList().getFirst();
        var descriptor = ModuleFinder.of(automatic).find("library").orElseThrow().descriptor();
        assertEquals(descriptor.name(), row.get("origin").get("module").asString());
        assertEquals(descriptor.rawVersion().orElseThrow(), row.get("origin").get("version").asString());
        assertEquals("class library@2.0/example.Widget(library-2.0.jar)", row.get("text").asString());
    }

    @Test
    void automaticModuleDescriptionsRetainTheirOrigins() {
        var result = json("--source", "none", "--module-path", automatic.toString(),
                "--add-modules", "library", "--kind", "module", "library");

        assertEquals(0, result.code(), result.error());
        var row = Json.parse(result.output()).get("results").asList().getFirst();
        assertEquals("library", row.get("origin").get("module").asString());
        assertEquals("2.0", row.get("origin").get("version").asString());
        assertEquals("library@2.0(library-2.0.jar): module library { exports example; }", row.get("text").asString());
    }

    @Test
    void unversionedModulesUseTheDescriptorRatherThanTheArchiveVersion() {
        var result = json("-l", "--source", "none", "--module-path", unversioned.toString(),
                "--add-modules", "example.lib", "--kind", "class", "example.Widget");

        assertEquals(0, result.code(), result.error());
        var row = Json.parse(result.output()).get("results").asList().getFirst();
        assertEquals("example.lib", row.get("origin").get("module").asString());
        assertTrue(row.get("origin").tryGet("version").isEmpty(), result.output());
        assertEquals("class example.lib/example.Widget(library-7.8.9.jar)", row.get("text").asString());
    }

    @Test
    void overloadedMembersKeepUndecoratedIdentities() {
        var result = json("-l", "--source", "none", "--class-path", modular.toString(),
                "--kind", "method", "example.Widget.run");

        assertEquals(0, result.code(), result.error());
        var rows = Json.parse(result.output()).get("results").asList();
        assertEquals(List.of("example.Widget.run()", "example.Widget.run(int)"),
                rows.stream().map(row -> row.get("symbol").asString()).toList());
        assertTrue(rows.stream().allMatch(row -> row.get("text").asString().contains("library-1.2.3.jar")), result.output());
    }

    @Test
    void systemOriginIncludesTheSelectedImageModuleVersion() {
        var result = json("-l", "--source", "none", "--system", System.getProperty("java.home"),
                "--kind", "class", "java.lang.String");

        assertEquals(0, result.code(), result.error());
        var row = Json.parse(result.output()).get("results").asList().getFirst();
        var version = ModuleFinder.ofSystem().find("java.base").orElseThrow().descriptor().rawVersion().orElseThrow();
        assertEquals("java.base", row.get("origin").get("module").asString());
        assertEquals(version, row.get("origin").get("version").asString());
        assertEquals("class java.base@" + version + "/java.lang.String", row.get("text").asString());
    }

    @Test
    void sourceModuleRetainsItsNamedIdentity() {
        var result = json("-l", "--source", "signature", "--module-source-path", "example.lib=" + sources,
                "--module", "example.lib", "--kind", "class", "example.Widget");

        assertEquals(0, result.code(), result.error());
        var row = Json.parse(result.output()).get("results").asList().getFirst();
        assertEquals("example.lib", row.get("origin").get("module").asString());
        assertEquals("class example.lib/example.Widget", row.get("text").asString());
    }

    @Test
    void plainAndJsonListingsUseTheSameCompactReference() {
        var options = new String[] {"-l", "--source", "none", "--class-path", modular.toString(),
                "--kind", "class", "example.Widget"};
        var plain = run(options);
        var structured = json(options);

        assertEquals(0, plain.code(), plain.error());
        assertEquals(0, structured.code(), structured.error());
        assertEquals("class example.Widget(library-1.2.3.jar)\n", plain.output());
        assertEquals(Json.parse(structured.output()).get("results").asList().getFirst().get("text").asString() + "\n", plain.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enumerationRetainsTheInputOrigin(boolean modulePath) {
        var options = new ArrayList<>(List.of("-l", "--source", "none", "--exclude-system-modules", "--kind", "class"));
        options.addAll(modulePath
                ? List.of("--module-path", modular.toString(), "--add-modules", "example.lib")
                : List.of("--class-path", modular.toString()));
        var result = json(options.toArray(String[]::new));

        assertEquals(0, result.code(), result.error());
        var row = Json.parse(result.output()).get("results").asList().getFirst();
        assertEquals("example.Widget", row.get("symbol").asString());
        assertEquals(modulePath ? "class example.lib@1.2.3/example.Widget(library-1.2.3.jar)" : "class example.Widget(library-1.2.3.jar)",
                row.get("text").asString());
        assertTrue(row.get("origin").get("classLocation").asString().contains("library-1.2.3.jar!/example/Widget.class"), result.output());
    }

    @Test
    void referencedTypesRetainTheirOwnOrigins() throws Exception {
        var source = directory.resolve("UseWidget.java");
        Files.writeString(source, "class UseWidget { example.Widget widget; String name; }\n");
        var result = json("--source", "none", "--class-path", modular.toString(), "--referenced-types", source.toString());

        assertEquals(0, result.code(), result.error());
        var rows = Json.parse(result.output()).get("results").asList();
        var widget = rows.stream().filter(row -> row.get("symbol").asString().equals("example.Widget")).findFirst().orElseThrow();
        assertEquals("class example.Widget(library-1.2.3.jar)", widget.get("text").asString());
        assertTrue(widget.get("origin").get("classLocation").asString().contains("library-1.2.3.jar!/example/Widget.class"), result.output());
        var string = rows.stream().filter(row -> row.get("symbol").asString().equals("java.lang.String")).findFirst().orElseThrow();
        assertEquals("java.base", string.get("origin").get("module").asString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void calleeListingsRetainTheCalleeOrigin(boolean source) throws Exception {
        var options = new ArrayList<>(List.of("-l", "--source", "none", "--class-path", modular.toString(),
                "--exclude-system-modules", "--callees", "example.Widget.run"));
        if (source) {
            var ordinarySources = Files.createDirectories(directory.resolve("ordinary/example"));
            Files.copy(sources.resolve("example/Widget.java"), ordinarySources.resolve("Widget.java"));
            options.addAll(List.of("--source-path", ordinarySources.getParent().toString()));
        }
        var result = json(options.toArray(String[]::new));

        assertEquals(0, result.code(), result.error());
        var row = Json.parse(result.output()).get("results").asList().stream()
                .filter(value -> value.get("symbol").asString().equals("java.io.PrintStream.println(java.lang.String)"))
                .findFirst().orElseThrow();
        assertEquals("java.base", row.get("origin").get("module").asString());
        assertEquals("jrt:/java.base/java/io/PrintStream.class", row.get("origin").get("classLocation").asString());
    }

    @Test
    void detailedOriginsRetainTheFullResolutionLocation() {
        var result = run("-l", "--show-origin", "--source", "none", "--class-path", modular.toString(),
                "--kind", "class", "example.Widget");

        assertEquals(0, result.code(), result.error());
        assertEquals("class example.Widget(jar:" + modular.toUri() + "!/example/Widget.class)\n", result.output());
    }

    private static void archive(Path path, Path root, List<String> entries) throws Exception {
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries) {
                jar.putNextEntry(new JarEntry(entry));
                Files.copy(root.resolve(entry), jar);
                jar.closeEntry();
            }
        }
    }

    private static Result json(String... arguments) {
        var options = new ArrayList<>(List.of("--format", "json"));
        options.addAll(List.of(arguments));
        return run(options.toArray(String[]::new));
    }

    private static Result run(String... arguments) {
        var options = new ArrayList<>(List.of("--limit-modules", "java.base"));
        options.addAll(List.of(arguments));
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), options.toArray(String[]::new));
        return new Result(code, output.toString(), error.toString());
    }

    private record Result(int code, String output, String error) {}
}
