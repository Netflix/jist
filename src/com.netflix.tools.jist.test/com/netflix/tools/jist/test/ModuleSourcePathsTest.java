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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.netflix.tools.jist.ModuleSourcePaths;
import com.netflix.tools.jist.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleSourcePathsTest {
    @Test
    void acceptsCompilationCapabilityArguments() throws Exception {
        var options = Options.parse(new String[] {"--module", "com.example.app", "--release", "25", "--module-version", "1.0",
                "-d", "classes", "--enable-preview"});

        assertEquals("com.example.app", options.module());
    }

    @TempDir
    Path tempDir;

    @Test
    void canonicalRootResolvesModuleDirectory() throws Exception {
        var sourceRoot = tempDir.resolve("src");
        var moduleRoot = moduleRoot(sourceRoot.resolve("com.example.foo"), "com.example.foo");

        var paths = ModuleSourcePaths.parse(List.of(sourceRoot.toString()));

        assertEquals(List.of(moduleRoot), paths.forModule("com.example.foo"));
        assertTrue(paths.forModule("com.example.missing")
                        .isEmpty());
    }

    @Test
    void wildcardAndBracePatternsResolveAllSourceRoots() throws Exception {
        var first = moduleRoot(tempDir.resolve("first/com.example.foo/main/java"), "com.example.foo");
        var second = tempDir.resolve("second/com.example.foo/main/java");
        Files.createDirectories(second);
        var pattern = tempDir
                + File.separator
                + "{first,second}"
                + File.separator
                + "*"
                + File.separator
                + "main"
                + File.separator
                + "java";

        var paths = ModuleSourcePaths.parse(List.of(pattern));

        assertEquals(List.of(first, second), paths.forModule("com.example.foo"));
    }

    @Test
    void moduleSpecificPathsOverridePattern() throws Exception {
        var patternRoot = tempDir.resolve("pattern");
        moduleRoot(patternRoot.resolve("com.example.foo"), "com.example.foo");
        var first = tempDir.resolve("specific-first");
        var second = tempDir.resolve("specific-second");
        Files.createDirectories(first);
        Files.createDirectories(second);

        var paths = ModuleSourcePaths.parse(List.of(patternRoot.toString(), "com.example.foo=" + first + File.pathSeparator + second));

        assertEquals(List.of(first, second), paths.forModule("com.example.foo"));
    }

    @Test
    void repeatedModuleSpecificOptionsResolveDifferentModules() throws Exception {
        var foo = tempDir.resolve("foo");
        var bar = tempDir.resolve("bar");
        Files.createDirectories(foo);
        Files.createDirectories(bar);

        var paths = ModuleSourcePaths.parse(List.of("com.example.foo=" + foo, "com.example.bar=" + bar));

        assertEquals(List.of(foo), paths.forModule("com.example.foo"));
        assertEquals(List.of(bar), paths.forModule("com.example.bar"));
    }

    @Test
    void optionsRetainRepeatedAndEqualsModuleSourcePaths() throws Exception {
        var options = Options.parse(new String[] {"--module-source-path", "com.example.foo=foo", "--module-source-path=com.example.bar=bar"});

        assertEquals(List.of("com.example.foo=foo", "com.example.bar=bar"), options.moduleSourcePaths());
    }

    @Test
    void rejectsDuplicateModuleSpecificOptions() throws Exception {
        var first = tempDir.resolve("first");
        var second = tempDir.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);

        var error = assertThrows(Exception.class,
                () -> ModuleSourcePaths.parse(List.of("com.example.foo=" + first, "com.example.foo=" + second)));

        assertTrue(error.getMessage()
                        .contains("more than once for module com.example.foo"));
    }

    @Test
    void rejectsMultiplePatternOptions() throws Exception {
        var first = tempDir.resolve("first");
        var second = tempDir.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);

        var error = assertThrows(Exception.class,
                () -> ModuleSourcePaths.parse(List.of(first.toString(), second.toString())));

        assertTrue(error.getMessage()
                        .contains("more than once with a pattern argument"));
    }

    @Test
    void rejectsWildcardOutsideAPathComponent() {
        var error = assertThrows(Exception.class,
                () -> ModuleSourcePaths.parse(List.of(tempDir.resolve("prefix*").toString())));

        assertTrue(error.getMessage()
                        .contains("Illegal use of *"));
    }

    private static Path moduleRoot(Path root, String moduleName) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("module-info.java"), "module " + moduleName + " {}\n");
        return root;
    }
}
