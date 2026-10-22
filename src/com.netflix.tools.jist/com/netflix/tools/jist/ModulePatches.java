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

/** Ordered patch locations; a patch does not replace the module's descriptor or add a root. */
final class ModulePatches {
    private ModulePatches() {}

    static Map<String, List<Path>> parse(List<String> values) throws ToolException {
        var patches = new LinkedHashMap<String, List<Path>>();
        for (var value : values) {
            int equals = value.indexOf('=');
            if (equals <= 0 || equals == value.length() - 1) {
                throw new ToolException(1, "Invalid --patch-module value: " + value);
            }
            var name = value.substring(0, equals);
            if (patches.putIfAbsent(name, SearchEnvironment.parsePathList(value.substring(equals + 1))) != null) {
                throw new ToolException(1, "--patch-module specified more than once for module " + name);
            }
        }
        return Collections.unmodifiableMap(patches);
    }

    static boolean contains(List<Path> roots, String resource) throws IOException {
        if (resource.equals("module-info.class") || resource.equals("module-info.java"))
            return false;
        var compiled = resource.endsWith(".java") ? resource.substring(0, resource.length() - 5) + ".class" : resource;
        var source = resource.endsWith(".class") ? resource.substring(0, resource.length() - 6) + ".java" : resource;
        int nested = source.indexOf('$');
        var unit = nested < 0 ? source : source.substring(0, nested) + ".java";
        for (var root : roots) {
            if (Files.isDirectory(root)) {
                if (Files.isRegularFile(root.resolve(compiled)) || Files.isRegularFile(root.resolve(source)) || Files.isRegularFile(root.resolve(unit)))
                    return true;
            } else if (Files.isRegularFile(root)) {
                try (var jar = new JarFile(root.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                    if (jar.getJarEntry(compiled) != null || jar.getJarEntry(source) != null || jar.getJarEntry(unit) != null)
                        return true;
                }
            }
        }
        return false;
    }
}
