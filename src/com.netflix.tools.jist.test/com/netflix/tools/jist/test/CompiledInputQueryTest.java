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
                    expected.add(name);
                    locations.add("jar:" + archive.toUri() + "!/" + resource);
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
    void relationshipScansReadSelectedMultiReleaseEntries(@TempDir Path directory) throws Exception {
        var archive = directory.resolve("versioned.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var jar = new JarOutputStream(Files.newOutputStream(archive), manifest)) {
            for (var version : List.of("", "META-INF/versions/9/")) {
                jar.putNextEntry(new JarEntry(version + "ordered/Versioned.class"));
                jar.write(caller("ordered.Versioned", version.isEmpty() ? "systemDefaultZone" : "systemUTC", ""));
                jar.closeEntry();
            }
        }
        for (var query : List.of("--callers", "--usages")) {
            var rows = query(List.of(archive), query);
            assertEquals(1, rows.size());
            assertTrue(rows.getFirst().get("text").asString().contains("ordered.Versioned.clock"));
            assertEquals("jar:" + archive.toUri() + "!/META-INF/versions/9/ordered/Versioned.class",
                    rows.getFirst().get("origin").get("location").asString());
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
            var text = row.get("text").asString();
            return "Warning: Source unavailable for " + text.substring(0, text.indexOf(':'))
                    + "; using ClassFile information\n";
        }).collect(java.util.stream.Collectors.joining()) : "";
        assertEquals(warnings, error.toString());
        return rows;
    }
}
