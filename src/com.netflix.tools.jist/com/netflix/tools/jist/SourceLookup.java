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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;

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
    private final List<SourcePaths.Root> descriptorPaths;
    private final Map<String, SourceUnit> cache = new HashMap<>();
    private Map<String, Path> ordinarySources;
    private Set<String> ordinarySourcePackages;

    private SourceLookup(SourcePaths explicit, SourcePaths system, List<Path> ordinarySourceDirectories, List<SourcePaths.Root> descriptorPaths) {
        this.explicit = explicit;
        this.system = system;
        this.ordinarySourceDirectories = ordinarySourceDirectories;
        this.descriptorPaths = descriptorPaths;
    }

    static SourceLookup open(List<Path> roots, Set<Path> patches, String system) {
        var explicit = new SourcePaths(roots);
        var descriptors = explicit.roots().stream().filter(root -> !patches.contains(root.location())).toList();
        var ordinary = roots.stream().filter(Files::isDirectory).toList();
        return new SourceLookup(explicit, systemSources(system), ordinary, descriptors);
    }

    List<Path> explicitPaths() {
        return ordinarySourceDirectories;
    }

    void prepareOrdinarySources() throws IOException {
        if (ordinarySources != null) {
            return;
        }
        var discovered = new LinkedHashMap<String, Path>();
        for (var root : ordinarySourceDirectories) {
            var normalizedRoot = root.toAbsolutePath().normalize();
            try (var files = Files.walk(normalizedRoot)) {
                var iterator = files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(JAVA_EXT))
                        .iterator();
                while (iterator.hasNext()) {
                    var source = iterator.next().toAbsolutePath().normalize();
                    var relativePath = normalizedRoot.relativize(source)
                            .toString()
                            .replace(File.separatorChar, '/');
                    discovered.putIfAbsent(relativePath, source);
                }
            }
        }
        ordinarySources = Map.copyOf(discovered);
        ordinarySourcePackages = ordinarySources.keySet()
                .stream()
                .map(path -> {
                    int slash = path.lastIndexOf('/');
                    return slash < 0 ? "" : path.substring(0, slash);
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean mayHaveOrdinarySource(String resourceName) {
        if (ordinarySourcePackages == null) {
            return true;
        }
        int slash = resourceName.lastIndexOf('/');
        return ordinarySourcePackages.contains(slash < 0 ? "" : resourceName.substring(0, slash));
    }

    boolean hasOrdinarySource(ClassModel model) {
        return ordinarySource(model) != null;
    }

    Path ordinarySource(ClassModel model) {
        if (model.isModuleInfo()) {
            return null;
        }
        var sourceFile = model.findAttribute(Attributes.sourceFile());
        if (sourceFile.isEmpty() || !sourceFile.get()
                .sourceFile()
                .stringValue()
                .endsWith(JAVA_EXT)) {
            return null;
        }
        var internalName = model.thisClass().asInternalName();
        int slash = internalName.lastIndexOf('/');
        var relativePath = (slash < 0 ? "" : internalName.substring(0, slash + 1))
                + sourceFile.get()
                            .sourceFile()
                            .stringValue();
        if (ordinarySources != null) {
            return ordinarySources.get(relativePath);
        }
        return ordinarySourceDirectories.stream()
                .map(root -> contained(root, relativePath))
                .filter(Objects::nonNull)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
    }

    SourceUnit find(String relativePath, String classLocation) throws IOException {
        return find(relativePath, classLocation, null);
    }

    SourceUnit find(String relativePath, String classLocation, String classResource) throws IOException {
        var classPrefix = jarEntryPrefix(classLocation, classResource);
        var source = classPrefix == null || classPrefix.isEmpty()
                ? null
                : find(explicit.roots(), classPrefix, relativePath);
        if (source == null && classPrefix != null && !classPrefix.isEmpty()) {
            source = findOneDirectoryBelow(explicit.roots(), classPrefix, relativePath);
        }
        if (source == null) {
            source = find(explicit.roots(), relativePath);
        }
        if (source == null) {
            source = findOneDirectoryBelow(explicit.roots(), "", relativePath);
        }
        var moduleName = jrtModuleName(classLocation);
        if (source == null && moduleName != null) {
            source = find(explicit.roots(), moduleName + "/" + relativePath);
        }
        if (source == null && moduleName != null) {
            source = find(system.roots(), moduleName + "/" + relativePath);
        }
        if (source == null) {
            source = find(system.roots(), relativePath);
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
        var source = find(descriptorPaths, moduleName + "/module-info.java");
        if (source == null) {
            for (var input : descriptorPaths) {
                var root = input.path();
                if (root.getFileName() != null && root.getFileName()
                        .toString()
                        .equals(moduleName)) {
                    source = read(root, "module-info.java");
                    if (source != null) {
                        break;
                    }
                }
            }
        }
        if (source == null && jrtModuleName(classLocation) == null) {
            source = find(descriptorPaths, "module-info.java");
        }
        if (source == null) {
            source = find(system.roots(), moduleName + "/module-info.java");
        }
        return source;
    }

    private SourceUnit find(List<SourcePaths.Root> roots, String relativePath) throws IOException {
        for (var root : roots) {
            var source = read(root.path(), relativePath);
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    private SourceUnit find(List<SourcePaths.Root> roots, String prefix, String relativePath) throws IOException {
        for (var root : roots) {
            var source = read(root.path().resolve(prefix), relativePath);
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    private SourceUnit findOneDirectoryBelow(List<SourcePaths.Root> roots, String prefix, String relativePath) throws IOException {
        for (var input : roots) {
            var root = input.path();
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
        var candidate = contained(root, relativePath);
        if (candidate == null || !Files.isRegularFile(candidate)) {
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

    private static Path contained(Path root, String relativePath) {
        var normalizedRoot = root.toAbsolutePath().normalize();
        var candidate = normalizedRoot.resolve(relativePath).normalize();
        return candidate.startsWith(normalizedRoot) ? candidate : null;
    }

    private static SourcePaths systemSources(String system) {
        if ("none".equals(system)) {
            return new SourcePaths(List.of());
        }
        var javaHome = system != null ? Path.of(system) : Path.of(System.getProperty("java.home"));
        for (var archive : List.of(javaHome.resolve("lib/src.zip"), javaHome.resolve("src.zip"))) {
            if (Files.isRegularFile(archive)) {
                return new SourcePaths(List.of(archive));
            }
        }
        return new SourcePaths(List.of());
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
