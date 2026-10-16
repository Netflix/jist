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

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

final class PatchedSourceFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final Set<URI> sources;
    private final Location patchLocation;

    PatchedSourceFileManager(StandardJavaFileManager fileManager, Collection<JavaFileObject> sources, String moduleName) {
        super(fileManager);
        this.sources = sources.stream()
                .map(FileObject::toUri)
                .collect(Collectors.toSet());
        this.patchLocation = new PatchLocation(moduleName);
    }

    @Override
    public boolean hasLocation(Location location) {
        return location == StandardLocation.PATCH_MODULE_PATH || super.hasLocation(location);
    }

    @Override
    public Location getLocationForModule(Location location, JavaFileObject file) throws IOException {
        if (location == StandardLocation.PATCH_MODULE_PATH && sources.contains(file.toUri())) {
            return patchLocation;
        }
        return super.getLocationForModule(location, file);
    }

    @Override
    public Location getLocationForModule(Location location, String moduleName) throws IOException {
        if (location == StandardLocation.PATCH_MODULE_PATH && moduleName.equals(inferModuleName(patchLocation))) {
            return patchLocation;
        }
        return super.getLocationForModule(location, moduleName);
    }

    @Override
    public String inferModuleName(Location location) throws IOException {
        return location == patchLocation ? ((PatchLocation) patchLocation).moduleName() : super.inferModuleName(location);
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
            boolean recurse)
            throws IOException {
        return location == patchLocation ? List.of() : super.list(location, packageName, kinds, recurse);
    }

    private record PatchLocation(String moduleName) implements Location {
        @Override
        public String getName() {
            return "PATCH_MODULE_PATH[" + moduleName + "]";
        }

        @Override
        public boolean isOutputLocation() {
            return false;
        }

        @Override
        public boolean isModuleOrientedLocation() {
            return false;
        }
    }
}
