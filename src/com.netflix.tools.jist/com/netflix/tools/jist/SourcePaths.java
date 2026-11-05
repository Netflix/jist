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

final class SourcePaths implements Closeable {
    private final List<Root> roots;

    SourcePaths(List<Path> paths) {
        roots = paths.stream().map(Root::new).toList();
    }

    List<Root> roots() {
        return roots;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (var root : roots) {
            try {
                root.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    static final class Root implements Closeable {
        private final Path location;
        private Path path;
        private FileSystem archive;

        Root(Path location) {
            this.location = location;
        }

        Path location() {
            return location;
        }

        Path path() throws IOException {
            if (path == null) {
                var name = location.toString();
                if ((name.endsWith(".jar") || name.endsWith(".zip")) && Files.isRegularFile(location)) {
                    archive = FileSystems.newFileSystem(location);
                    path = archive.getPath("/");
                } else {
                    path = location;
                }
            }
            return path;
        }

        @Override
        public void close() throws IOException {
            if (archive != null) {
                archive.close();
            }
        }
    }
}
