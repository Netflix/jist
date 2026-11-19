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

/** Attributes physical inputs using the already resolved compilation module graph. */
final class OriginResolver {
    private record Module(String name, String version, String system) {}
    private record Root(String location, Module module) {}

    private final Map<String, Module> modules;
    private final List<Root> roots;

    OriginResolver(SearchEnvironment environment, Options options) throws IOException, ToolException {
        var resolution = environment.modules();
        var system = "none".equals(options.system()) ? null
                : Path.of(options.system() == null ? System.getProperty("java.home") : options.system())
                        .toAbsolutePath().normalize().toString();
        modules = resolution.descriptors().entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> new Module(entry.getKey(), entry.getValue().rawVersion().orElse(null),
                        resolution.systemPackages().containsKey(entry.getKey()) ? system : null)));
        var locations = new LinkedHashMap<String, Module>();
        for (var input : resolution.applicationModules()) {
            addRoot(locations, input.location(), modules.get(input.name()));
        }
        for (var input : environment.moduleSourceInputs()) {
            addRoot(locations, input.location(), modules.get(input.module()));
        }
        roots = locations.entrySet().stream()
                .map(entry -> new Root(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt((Root root) -> root.location().length()).reversed())
                .toList();
    }

    private static void addRoot(Map<String, Module> locations, Path path, Module module) {
        var normalized = path.toAbsolutePath().normalize();
        var location = normalized.toString();
        locations.putIfAbsent(location, module);
        locations.putIfAbsent(normalized.toUri().toString(), module);
        locations.putIfAbsent("jar:" + normalized.toUri() + "!/", module);
    }

    Output.ResultOrigin resolve(String classLocation, String sourceLocation, String sourceFile, Integer line) {
        var location = sourceLocation != null ? sourceLocation : classLocation;
        if (location == null) return null;
        var definition = classLocation != null ? classLocation : location;
        var moduleName = SearchEnvironment.jrtModuleName(definition);
        var module = moduleName == null ? moduleFor(definition) : modules.get(moduleName);
        var kind = module != null && module.system() != null ? "system"
                : definition.startsWith("jar:") ? "archive" : "file";
        String resource = null;
        if (definition.startsWith("jrt:/")) {
            var path = URI.create(definition).getPath();
            resource = path.substring(path.indexOf('/', 1) + 1);
        } else if (definition.startsWith("jar:")) {
            resource = definition.substring(definition.indexOf("!/") + 2);
        }
        return new Output.ResultOrigin(kind, location, classLocation,
                module == null ? null : module.system(), module == null ? null : module.name(),
                module == null ? null : module.version(), resource, sourceFile, line);
    }

    private Module moduleFor(String location) {
        for (var root : roots) {
            if (location.startsWith(root.location()) && (location.length() == root.location().length()
                    || root.location().endsWith("/") || root.location().endsWith(File.separator)
                    || location.charAt(root.location().length()) == File.separatorChar
                    || location.charAt(root.location().length()) == '/')) {
                return root.module();
            }
        }
        return null;
    }
}
