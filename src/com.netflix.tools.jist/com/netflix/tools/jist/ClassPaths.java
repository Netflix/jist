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

/** Filters non-archive files from class paths. */
final class ClassPaths {
    static List<Path> prepare(String classPath) throws ToolException {
        var entries = new ArrayList<Path>();
        for (var path : SearchEnvironment.parsePathList(classPath)) {
            var name = path.getFileName();
            if (Files.isRegularFile(path)) {
                boolean archiveName = name != null
                        && (name.toString().endsWith(".jar") || name.toString().endsWith(".zip"));
                try (var archive = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                    // Unconventional extensions can still contain valid archives.
                } catch (ZipException e) {
                    if (archiveName) {
                        throw new ToolException(2, "Cannot read archive " + path + ": " + e.getMessage());
                    }
                    continue;
                } catch (IOException e) {
                    throw new ToolException(2, "Cannot read class-path entry " + path + ": " + e.getMessage());
                }
            }
            entries.add(path);
        }
        return List.copyOf(entries);
    }

    static JarFile openArchive(Path path, boolean verify) throws IOException {
        try {
            return new JarFile(path.toFile(), verify, ZipFile.OPEN_READ, Runtime.version());
        } catch (IOException e) {
            throw new IOException("Cannot read archive " + path + ": " + e.getMessage(), e);
        }
    }
}
