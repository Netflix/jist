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

package com.netflix.tools.jist;

import module java.base;

import static com.netflix.tools.jist.SearchEnvironment.expandSourcePaths;
import static com.netflix.tools.jist.SearchEnvironment.jrtModuleName;
import static com.netflix.tools.jist.SearchEnvironment.unitLocation;

/**
 * Locates configured, attached, and system source without extracting archives.
 */
final class SourceLookup implements Closeable {
    private static final String JAVA_EXT = ".java";

    private final SourcePaths explicit;
    private final SourcePaths system;
    private final List<Path> ordinarySourceDirectories;
    private final Map<String, SourceUnit> cache = new HashMap<>();

    private SourceLookup(SourcePaths explicit, SourcePaths system, List<Path> ordinarySourceDirectories) {
        this.explicit = explicit;
        this.system = system;
        this.ordinarySourceDirectories = ordinarySourceDirectories;
    }

    static SourceLookup open(String sourcePath, List<Path> moduleSourceRoots, String system) {
        var configured = expandSourcePaths(sourcePath);
        var explicitPaths = new ArrayList<Path>(configured.paths());
        explicitPaths.addAll(moduleSourceRoots);
        var explicit = new SourcePaths(List.copyOf(explicitPaths), configured.openFileSystems());
        var ordinary = new ArrayList<Path>();
        if (sourcePath != null) {
            Arrays.stream(sourcePath.split(File.pathSeparator))
                    .map(Path::of)
                    .filter(Files::isDirectory)
                    .forEach(ordinary::add);
        }
        ordinary.addAll(moduleSourceRoots);
        return new SourceLookup(explicit, openSystemSources(system), ordinary);
    }

    List<Path> explicitPaths() {
        return ordinarySourceDirectories;
    }

    SourceUnit find(String relativePath, String classLocation) throws IOException {
        return find(relativePath, classLocation, null);
    }

    SourceUnit find(String relativePath, String classLocation, String classResource) throws IOException {
        var classPrefix = jarEntryPrefix(classLocation, classResource);
        var source = classPrefix == null || classPrefix.isEmpty()
                ? null
                : find(explicit.paths(), classPrefix, relativePath);
        if (source == null && classPrefix != null && !classPrefix.isEmpty()) {
            source = findOneDirectoryBelow(explicit.paths(), classPrefix, relativePath);
        }
        if (source == null) {
            source = find(explicit.paths(), relativePath);
        }
        if (source == null) {
            source = findOneDirectoryBelow(explicit.paths(), "", relativePath);
        }
        var moduleName = jrtModuleName(classLocation);
        if (source == null && moduleName != null) {
            source = find(explicit.paths(), moduleName + "/" + relativePath);
        }
        if (source == null && moduleName != null) {
            source = find(system.paths(), moduleName + "/" + relativePath);
        }
        if (source == null) {
            source = find(system.paths(), relativePath);
        }
        return source;
    }

    private static String jarEntryPrefix(String classLocation, String classResource) {
        if (classLocation == null || classResource == null) {
            return null;
        }
        int entryStart = classLocation.indexOf("!/");
        if (entryStart < 0) {
            return null;
        }
        var entry = classLocation.substring(entryStart + 2);
        return entry.endsWith(classResource) ? entry.substring(0, entry.length() - classResource.length()) : null;
    }

    SourceUnit findModuleInfo(String moduleName, String classLocation) throws IOException {
        var source = find(explicit.paths(), moduleName + "/module-info.java");
        if (source == null) {
            for (var root : explicit.paths()) {
                if (root.getFileName() != null && root.getFileName()
                        .toString()
                        .equals(moduleName)) {
                    source = find(List.of(root), "module-info.java");
                    if (source != null) {
                        break;
                    }
                }
            }
        }
        if (source == null && jrtModuleName(classLocation) == null) {
            source = find(explicit.paths(), "module-info.java");
        }
        if (source == null) {
            source = find(system.paths(), moduleName + "/module-info.java");
        }
        return source;
    }

    private SourceUnit find(List<Path> roots, String relativePath) throws IOException {
        for (var root : roots) {
            var source = read(root, relativePath);
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    private SourceUnit find(List<Path> roots, String prefix, String relativePath) throws IOException {
        for (var root : roots) {
            var source = read(root.resolve(prefix), relativePath);
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    private SourceUnit findOneDirectoryBelow(List<Path> roots, String prefix, String relativePath) throws IOException {
        for (var root : roots) {
            if (!"jar".equals(root.getFileSystem()
                                  .provider()
                                  .getScheme())) {
                continue;
            }
            var parent = root.resolve(prefix);
            if (!Files.isDirectory(parent)) {
                continue;
            }
            try (var children = Files.list(parent)) {
                for (var child : children.filter(Files::isDirectory)
                        .sorted()
                        .toList()) {
                    var source = read(child, relativePath);
                    if (source != null) {
                        return source;
                    }
                }
            }
        }
        return null;
    }

    private SourceUnit read(Path root, String relativePath) throws IOException {
        var candidate = root.resolve(relativePath);
        if (!Files.isRegularFile(candidate)) {
            return null;
        }
        var origin = unitLocation(candidate);
        var source = cache.get(origin);
        if (source == null) {
            source = new SourceUnit(origin, Files.readAllBytes(candidate), relativePath.endsWith(JAVA_EXT), candidate,
                    root);
            cache.put(origin, source);
        }
        return source;
    }

    private static SourcePaths openSystemSources(String system) {
        if ("none".equals(system)) {
            return new SourcePaths(List.of(), List.of());
        }
        var javaHome = system != null ? Path.of(system) : Path.of(System.getProperty("java.home"));
        for (var archive : List.of(javaHome.resolve("lib/src.zip"), javaHome.resolve("src.zip"))) {
            if (Files.isRegularFile(archive)) {
                return expandSourcePaths(archive.toString());
            }
        }
        return new SourcePaths(List.of(), List.of());
    }

    @Override
    public void close() throws IOException {
        try {
            explicit.close();
        } finally {
            system.close();
        }
    }
}
