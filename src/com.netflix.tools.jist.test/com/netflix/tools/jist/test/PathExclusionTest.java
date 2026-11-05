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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathExclusionTest {
    private static final Path ROOT = Path.of("build/test-path-exclusion");
    private static final Path DEPENDENCY_SOURCE = ROOT.resolve("dependency-src");
    private static final Path PROJECT_SOURCE = ROOT.resolve("project-src");
    private static final Path MODULES = ROOT.resolve("modules");
    private static final Path DEPENDENCY = MODULES.resolve("example.dependency");
    private static final Path PROJECT = ROOT.resolve("project");
    private static final Path ARCHIVE = ROOT.resolve("dependency.jar");

    @BeforeAll
    static void compileFixtures() throws Exception {
        var descriptor = ROOT.resolve("module-info.java");
        var target = DEPENDENCY_SOURCE.resolve("dependency/Target.java");
        var caller = PROJECT_SOURCE.resolve("project/Caller.java");
        var root = DEPENDENCY_SOURCE.resolve("dependency/Root.java");
        var parent = DEPENDENCY_SOURCE.resolve("internal/Parent.java");
        Files.createDirectories(target.getParent());
        Files.createDirectories(caller.getParent());
        Files.createDirectories(parent.getParent());
        Files.writeString(descriptor, "module example.dependency { exports dependency; }");
        Files.writeString(root, "package dependency; public interface Root { void run(); }");
        Files.writeString(parent, "package internal; public abstract class Parent implements dependency.Root {}");
        Files.writeString(target, """
                package dependency;
                public class Target extends internal.Parent {
                    public void run() {}
                }
                """);
        Files.writeString(caller, """
                package project;
                public class Caller extends dependency.Target {
                    public void call(dependency.Target target) {
                        target.run();
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-g", "-d", DEPENDENCY.toString()), null,
                    manager.getJavaFileObjects(descriptor, root, parent, target)).call());
        }
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null,
                    List.of("-g", "--class-path", DEPENDENCY.toString(), "-d", PROJECT.toString()), null,
                    manager.getJavaFileObjects(caller)).call());
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(ARCHIVE)); var files = Files.walk(DEPENDENCY)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(DEPENDENCY.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
    }

    @Test
    void enumeratesOnlyIncludedDirectoriesAndArchives() {
        for (var dependency : List.of(DEPENDENCY, ARCHIVE)) {
            var options = List.of("--class-path", dependency + File.pathSeparator + PROJECT,
                    "--exclude-path", dependency.toAbsolutePath().getParent().resolve(".").resolve(dependency.getFileName()).toString());
            assertEquals("class project.Caller\n", run(options, "-l", "--kind", "class"));
            assertEquals("project.Caller: public class Caller extends Target {\n", run(options, "--source", "none", "--kind", "class"));
        }
    }

    @Test
    void excludedPathsStillResolveCallerAndUsageTargets() {
        for (var dependency : List.of(DEPENDENCY, ARCHIVE)) {
            var options = List.of("--class-path", dependency + File.pathSeparator + PROJECT,
                    "--exclude-path", dependency.toString());
            assertEquals("method project.Caller.call(dependency.Target)\n", run(options, "-l", "--callers", "Target.run"));
            var usages = run(options, "--source-path", PROJECT_SOURCE.toString(), "--usages", "dependency.Target.run");
            assertTrue(usages.contains("project.Caller(Caller.java:4):         target.run();"), usages);
        }
    }

    @Test
    void excludedSourceRootsStillResolveSourceCalls() {
        var options = List.of("--source-path", DEPENDENCY_SOURCE + File.pathSeparator + PROJECT_SOURCE,
                "--exclude-path", DEPENDENCY_SOURCE.toString());
        assertEquals("method project.Caller.call(dependency.Target)\n", run(options, "-l", "--callers", "Target.run"));
        assertEquals("class project.Caller\n", run(options, "-l", "--kind", "class"));
    }

    @Test
    void excludedSourceRootsStillAttachSourceToIncludedClassFiles() {
        var result = run(List.of("--class-path", ARCHIVE.toString(), "--source-path", DEPENDENCY_SOURCE.toString(),
                "--exclude-path", DEPENDENCY_SOURCE.toString()), "dependency.Target.run");
        assertEquals("dependency.Target(Target.java:3):     public void run() {}\n", result);
    }

    @Test
    void excludesModuleEntriesAndContainersWithoutChangingResolution() {
        for (var excluded : List.of(DEPENDENCY, MODULES, ARCHIVE)) {
            var options = List.of("--class-path", PROJECT.toString(), "--module-path", excluded.toString(),
                    "--add-modules", "example.dependency", "--exclude-path", excluded.toString());
            assertEquals("class project.Caller\n", run(options, "-l", "--kind", "class"));
            assertEquals("method project.Caller.call(dependency.Target)\n", run(options, "-l", "--callers", "Target.run"));
            assertEquals("class project.Caller\n", run(options, "-l", "--subtypes", "dependency.Root"));
        }
    }

    private static String run(List<String> options, String... arguments) {
        var selected = new ArrayList<>(List.of("--class-path", ROOT.resolve("empty").toString(),
                "--limit-modules", "java.base", "--exclude-system-modules"));
        selected.addAll(options);
        selected.addAll(List.of(arguments));
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jist().run(new PrintWriter(output), new PrintWriter(error), selected.toArray(String[]::new));
        assertEquals(0, code, error.toString());
        assertEquals("", error.toString());
        return output.toString();
    }
}
