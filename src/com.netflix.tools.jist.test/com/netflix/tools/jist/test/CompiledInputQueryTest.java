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
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import com.netflix.tools.jist.Jist;
import com.netflix.tools.json.Json;
import com.netflix.tools.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledInputQueryTest {
    @Test
    void relationshipScansPreserveInputOrderAndOrigins(@TempDir Path directory) throws Exception {
        var roots = new ArrayList<Path>();
        var expected = new ArrayList<String>();
        var locations = new ArrayList<String>();
        int count = Runtime.getRuntime().availableProcessors() * 2 + 5;
        for (int rootIndex = 0; rootIndex < 2; rootIndex++) {
            var archive = directory.resolve("input " + rootIndex + " # %.jar");
            roots.add(archive);
            try (var jar = new JarOutputStream(Files.newOutputStream(archive))) {
                for (int index = count - 1; index >= 0; index--) {
                    var name = "ordered.Input" + rootIndex + "Type" + index;
                    var resource = name.replace('.', '/') + ".class";
                    jar.putNextEntry(new JarEntry(resource));
                    jar.write(caller(name, "systemUTC", index % 2 == 0 ? "x".repeat(24_000) : ""));
                    jar.closeEntry();
                }
            }
            try (var fileSystem = FileSystems.newFileSystem(archive);
                 var files = Files.walk(fileSystem.getPath("/"))) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    expected.add("ordered." + file.getFileName().toString().replace(".class", ""));
                    locations.add(file.toUri().toString());
                }
            }
        }
        var classes = Files.createDirectories(directory.resolve("class files # %/ordered"));
        for (int index = count - 1; index >= 0; index--) {
            var name = "ordered.DirectoryType" + index;
            Files.write(classes.resolve("DirectoryType" + index + ".class"), caller(name, "systemUTC", ""));
        }
        roots.add(classes.getParent());
        try (var files = Files.walk(classes)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                expected.add("ordered." + file.getFileName().toString().replace(".class", ""));
                locations.add(file.toString());
            }
        }
        for (var query : List.of("--callers", "--usages")) {
            var rows = query(roots, query);
            assertEquals(expected.size(), rows.size());
            for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                assertTrue(row.get("text").asString().contains(expected.get(index) + ".clock"), row.toString());
                assertEquals(locations.get(index), row.get("origin").get("location").asString());
            }
        }
    }

    @Test
    void explodedInputsRetainResourcesAcrossRootSpellings(@TempDir Path directory) throws Exception {
        var root = Files.createDirectories(directory.resolve("class files \u00e9/ordered")).getParent();
        Files.write(root.resolve("ordered/Caller.class"), caller("ordered.Caller", "systemUTC", ""));
        var relative = Path.of("").toAbsolutePath().relativize(root);
        for (var input : List.of(root, relative, root.resolve("."), relative.resolve("."))) {
            for (var query : List.of("--callers", "--usages")) {
                var rows = query(List.of(input), query);
                assertEquals(1, rows.size());
                assertTrue(rows.getFirst().get("text").asString().contains("ordered.Caller.clock"));
                assertEquals(root.resolve("ordered/Caller.class").toString(),
                        rows.getFirst().get("origin").get("location").asString());
            }
        }
    }

    @Test
    void relationshipScansFollowClassFileAndRootLinks(@TempDir Path directory) throws Exception {
        var target = directory.resolve("Caller.class");
        Files.write(target, caller("ordered.Caller", "systemUTC", "x".repeat(24_000)));
        var root = Files.createDirectories(directory.resolve("classes/ordered")).getParent();
        Files.createSymbolicLink(root.resolve("ordered/Caller.class"), target);
        var linkedRoot = Files.createSymbolicLink(directory.resolve("linked-classes"), root);
        for (var input : List.of(root, linkedRoot)) {
            for (var query : List.of("--callers", "--usages")) {
                var rows = query(List.of(input), query);
                assertEquals(1, rows.size());
                assertTrue(rows.getFirst().get("text").asString().contains("ordered.Caller.clock"));
                assertEquals(input.resolve("ordered/Caller.class").toString(),
                        rows.getFirst().get("origin").get("location").asString());
            }
        }
    }

    @Test
    void embeddedQueriesKeepImplicitAndExplicitSystemImagesUsable() throws Exception {
        var tool = new Jist();
        for (var explicit : List.of(false, true)) {
            var arguments = new ArrayList<>(List.of("--source", "none", "--supertypes", "java.util.ArrayList"));
            if (explicit) arguments.addAll(List.of("--system", System.getProperty("java.home")));
            for (int iteration = 0; iteration < 2; iteration++) {
                var output = new StringWriter();
                var error = new StringWriter();
                int status = tool.run(new PrintWriter(output), new PrintWriter(error), arguments.toArray(String[]::new));
                assertEquals(0, status, error.toString());
                assertEquals("", error.toString());
                assertTrue(output.toString().contains("java.util.AbstractList"), output.toString());
            }
        }
        var system = java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
        assertTrue(Files.size(system.getPath("/modules/java.base/java/lang/Object.class")) > 0);
    }

    @Test
    void declarationScansPreserveMemberOrderAndVisibilityAcrossArchives(@TempDir Path directory) throws Exception {
        var roots = new ArrayList<String>();
        var visible = new ArrayList<String>();
        var all = new ArrayList<String>();
        int count = Runtime.getRuntime().availableProcessors() * 2 + 5;
        for (int rootIndex = 0; rootIndex < 2; rootIndex++) {
            var archive = directory.resolve("members-" + rootIndex + ".jar");
            roots.add(archive.toString());
            try (var jar = new JarOutputStream(Files.newOutputStream(archive))) {
                for (int index = count - 1; index >= 0; index--) {
                    var name = "ordered.Input" + rootIndex + "Type" + index;
                    jar.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
                    jar.write(ClassFile.of().build(ClassDesc.of(name), builder -> builder.withFlags(ClassFile.ACC_PUBLIC)
                            .withField("hit", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC)
                            .withField("hot", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE)
                            .withMethodBody("hit", MethodTypeDesc.of(ConstantDescs.CD_void),
                                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.return_())
                            .withMethodBody("hit", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int),
                                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.return_())));
                    jar.closeEntry();
                }
            }
            try (var fileSystem = FileSystems.newFileSystem(archive);
                 var files = Files.walk(fileSystem.getPath("/"))) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    var name = "ordered." + file.getFileName().toString().replace(".class", "");
                    var origin = "(" + archive.getFileName() + ")";
                    var fields = List.of("field " + name + ".hit" + origin);
                    var methods = List.of("method " + name + ".hit()" + origin, "method " + name + ".hit(int)" + origin);
                    visible.addAll(fields);
                    visible.addAll(methods);
                    all.addAll(fields);
                    all.add("field " + name + ".hot" + origin);
                    all.addAll(methods);
                }
            }
        }
        for (var visibility : List.of("public", "all")) {
            var output = new StringWriter();
            var error = new StringWriter();
            int status = new Jist().run(new PrintWriter(output), new PrintWriter(error),
                    "--limit-modules", "java.base", "--exclude-system-modules", "--class-path", String.join(File.pathSeparator, roots),
                    "--source", "none", "--visibility", visibility, "-l", "h*t");
            assertEquals(0, status, error.toString());
            assertEquals("", error.toString());
            assertEquals(visibility.equals("all") ? all : visible, output.toString().lines().toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void archiveScansReadSelectedMultiReleaseEntries(boolean multiRelease, @TempDir Path directory) throws Exception {
        var archive = directory.resolve("versioned.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", Boolean.toString(multiRelease));
        int feature = Runtime.version().feature();
        try (var jar = new JarOutputStream(Files.newOutputStream(archive), manifest)) {
            for (int version : List.of(0, 9, feature, feature + 1)) {
                var prefix = version == 0 ? "" : "META-INF/versions/" + version + "/";
                jar.putNextEntry(new JarEntry(prefix + "ordered/Versioned.class"));
                jar.write(caller("ordered.Versioned", version == 0 || version == feature ? "systemUTC" : "systemDefaultZone", ""));
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("META-INF/versions/" + feature + "/ordered/VersionOnly.class"));
            jar.write(caller("ordered.VersionOnly", "systemUTC", ""));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/versions/" + (feature + 1) + "/ordered/FutureOnly.class"));
            jar.write(caller("ordered.FutureOnly", "systemUTC", ""));
            jar.closeEntry();
        }
        var expectedNames = multiRelease ? List.of("Versioned", "VersionOnly") : List.of("Versioned");
        for (var query : List.of("--callers", "--usages")) {
            var rows = query(List.of(archive), query);
            assertEquals(expectedNames.size(), rows.size());
            for (var name : expectedNames) {
                var matches = rows.stream().filter(row -> row.get("text").asString().contains("ordered." + name + ".clock")).toList();
                assertEquals(1, matches.size());
                assertEquals("jar:" + archive.toUri() + "!/" + (multiRelease ? "META-INF/versions/" + feature + "/" : "")
                                + "ordered/" + name + ".class",
                        matches.getFirst().get("origin").get("location").asString());
            }
        }
        var output = new StringWriter();
        var error = new StringWriter();
        int status = new Jist().run(new PrintWriter(output), new PrintWriter(error),
                "--system", "none", "--class-path", archive.toString(), "--source", "none", "-l", "--kind", "class");
        assertEquals(0, status, error.toString());
        assertEquals(expectedNames.stream().map(name -> "class ordered." + name + "(versioned.jar)").sorted().toList(),
                output.toString().lines().sorted().toList());
        for (var name : expectedNames) {
            output = new StringWriter();
            error = new StringWriter();
            status = new Jist().run(new PrintWriter(output), new PrintWriter(error),
                    "--limit-modules", "java.base", "--exclude-system-modules", "--class-path", archive.toString(),
                    "--source", "none", "-l", "--callees", "ordered." + name + ".clock");
            assertEquals(0, status, error.toString());
            var system = "java.base@" + Object.class.getModule().getDescriptor().rawVersion().orElseThrow() + "/";
            assertEquals("method " + system + "java.time.Clock.systemUTC()\n", output.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateArchiveNamesUseOneCompleteSelectedEntry(boolean largerFirst, @TempDir Path directory) throws Exception {
        var archive = directory.resolve("duplicate.jar");
        var original = "ordered/Duplicate.class";
        var temporary = "ordered/Duplicatf.class";
        try (var jar = new JarOutputStream(Files.newOutputStream(archive))) {
            jar.putNextEntry(new JarEntry(original));
            jar.write(caller("ordered.Duplicate", "systemDefaultZone", largerFirst ? "x".repeat(24_000) : ""));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(temporary));
            jar.write(caller("ordered.Duplicate", "systemUTC", largerFirst ? "" : "x".repeat(24_000)));
            jar.closeEntry();
        }
        var bytes = Files.readAllBytes(archive);
        var oldName = temporary.getBytes(StandardCharsets.UTF_8);
        var newName = original.getBytes(StandardCharsets.UTF_8);
        int renamed = 0;
        for (int offset = 0; offset <= bytes.length - oldName.length; offset++) {
            if (java.util.Arrays.equals(bytes, offset, offset + oldName.length, oldName, 0, oldName.length)) {
                System.arraycopy(newName, 0, bytes, offset, newName.length);
                renamed++;
            }
        }
        assertEquals(2, renamed, "Rename the local and central-directory filenames without modifying ClassFiles");
        Files.write(archive, bytes);

        for (var query : List.of("--callers", "--usages")) {
            var rows = query(List.of(archive), query);
            assertEquals(1, rows.size());
            assertTrue(rows.getFirst().get("text").asString().contains("ordered.Duplicate.clock"));
        }
        for (var scope : List.of("none", "signature")) {
            var output = new StringWriter();
            var error = new StringWriter();
            int status = new Jist().run(new PrintWriter(output), new PrintWriter(error),
                    "--limit-modules", "java.base", "--exclude-system-modules", "--class-path", archive.toString(),
                    "--source", scope, "-l", "--kind", "method", "-e", "clock");
            assertEquals(0, status, error.toString());
            assertEquals("method ordered.Duplicate.clock()(duplicate.jar)\n", output.toString());
        }
        var output = new StringWriter();
        var error = new StringWriter();
        int status = new Jist().run(new PrintWriter(output), new PrintWriter(error),
                "--limit-modules", "java.base", "--class-path", archive.toString(), "--source", "none", "-l",
                "--kind", "class", "ordered.Duplicate");
        assertEquals(0, status, error.toString());
        assertEquals("class ordered.Duplicate(duplicate.jar)\n", output.toString());
    }

    @Test
    void embeddedQueriesObserveReplacedArchives(@TempDir Path directory) throws Exception {
        var archive = directory.resolve("replaced.jar");
        var tool = new Jist();
        for (var name : List.of("First", "Second", "Third")) {
            try (var jar = new JarOutputStream(Files.newOutputStream(archive))) {
                jar.putNextEntry(new JarEntry("ordered/" + name + ".class"));
                jar.write(caller("ordered." + name, "systemUTC", ""));
                jar.closeEntry();
            }
            var output = new StringWriter();
            var error = new StringWriter();
            int status = tool.run(new PrintWriter(output), new PrintWriter(error),
                    "--limit-modules", "java.base", "--exclude-system-modules", "--class-path", archive.toString(),
                    "--source", "none", "-l", "--callers", "java.time.Clock.systemUTC");
            assertEquals(0, status, error.toString());
            assertEquals("", error.toString());
            assertEquals("method ordered." + name + ".clock()(replaced.jar)\n", output.toString());
        }
    }

    private static byte[] caller(String name, String method, String padding) {
        var clock = ClassDesc.of("java.time.Clock");
        return ClassFile.of().build(ClassDesc.of(name), builder -> builder.withFlags(ClassFile.ACC_PUBLIC)
                .withField("PADDING", ConstantDescs.CD_String,
                        field -> field.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
                                .with(java.lang.classfile.attribute.ConstantValueAttribute.of(padding)))
                .withMethodBody("clock", MethodTypeDesc.of(clock), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code.invokestatic(clock, method, MethodTypeDesc.of(clock)).areturn()));
    }

    private static List<JsonValue> query(List<Path> inputs, String query) {
        var output = new StringWriter();
        var error = new StringWriter();
        var path = inputs.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        int status = new Jist().run(new PrintWriter(output), new PrintWriter(error),
                "--limit-modules", "java.base", "--exclude-system-modules", "--class-path", path,
                "--source", query.equals("--usages") ? "body" : "none", "--format", "json", query, "java.time.Clock.systemUTC");
        assertEquals(0, status, error.toString());
        var rows = Json.parse(output.toString()).get("results").asList();
        var warnings = query.equals("--usages") ? rows.stream().map(row -> {
            return "Warning: Source unavailable for " + row.get("unit").asString()
                    + "; using ClassFile information\n";
        }).collect(java.util.stream.Collectors.joining()) : "";
        assertEquals(warnings, error.toString());
        return rows;
    }
}
