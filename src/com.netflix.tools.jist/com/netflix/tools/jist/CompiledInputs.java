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

import static com.netflix.tools.jist.SearchEnvironment.parsePathList;
import static com.netflix.tools.jist.SearchEnvironment.readsUnnamedModule;
import static com.netflix.tools.jist.SearchEnvironment.unitLocation;

/** Streams ClassFiles from the selected compilation inputs. */
final class CompiledInputs {
    @FunctionalInterface
    interface Reader {
        byte[] read(InputStream input) throws IOException;
    }

    @FunctionalInterface
    interface Consumer {
        void accept(byte[] bytes, String location) throws IOException, ToolException;
    }

    static void scan(Options options, SearchEnvironment environment, Reader reader, Consumer consumer)
            throws IOException, ToolException {
        if (readsUnnamedModule(options)) {
            for (var path : parsePathList(options.classPath())) {
                scanEntry(path, reader, consumer);
            }
        }
        for (var module : environment.moduleInputs()) {
            scanEntry(module.location(), reader, consumer);
        }
        var modules = environment.modules().systemPackages().keySet();
        if ("none".equals(options.system()) || modules.isEmpty()) {
            return;
        }
        if (options.system() != null && !Files.isRegularFile(Path.of(options.system()).resolve("lib/modules"))) {
            return;
        }
        FileSystem system;
        boolean close;
        if (options.system() == null) {
            try {
                system = FileSystems.getFileSystem(URI.create("jrt:/"));
                close = false;
            } catch (FileSystemNotFoundException _) {
                system = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of());
                close = true;
            }
        } else {
            system = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", options.system()));
            close = true;
        }
        try {
            for (var module : modules) {
                scanEntry(system.getPath("/modules", module), reader, consumer);
            }
        } finally {
            if (close) {
                system.close();
            }
        }
    }

    private static void scanEntry(Path path, Reader reader, Consumer consumer) throws IOException, ToolException {
        if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                var iterator = files.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".class"))
                        .iterator();
                while (iterator.hasNext()) {
                    var file = iterator.next();
                    try (var input = Files.newInputStream(file)) {
                        var bytes = reader.read(input);
                        if (bytes != null) {
                            consumer.accept(bytes, unitLocation(file));
                        }
                    }
                }
            }
        } else if (Files.isRegularFile(path)) {
            try (var jar = new JarFile(path.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                var iterator = jar.versionedStream()
                        .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                        .iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    try (var input = jar.getInputStream(entry)) {
                        var bytes = reader.read(input);
                        if (bytes != null) {
                            consumer.accept(bytes, "jar:" + path.toAbsolutePath().normalize().toUri() + "!/" + entry.getRealName());
                        }
                    }
                }
            }
        }
    }
}
