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

/** Owns and prepares compiled input views for one query. */
final class ClassPaths implements Closeable {
    private final Map<Path, Opening> roots = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(Runtime.getRuntime().availableProcessors());

    private final class Opening extends FutureTask<CompiledInputs.Root> {
        private volatile boolean failureObserved;

        Opening(Path path) {
            super(() -> {
                capacity.acquire();
                try {
                    return CompiledInputs.Root.open(path);
                } finally {
                    capacity.release();
                }
            });
        }

        @Override
        public CompiledInputs.Root get() throws InterruptedException, ExecutionException {
            try {
                return super.get();
            } catch (ExecutionException failure) {
                failureObserved = true;
                throw failure;
            }
        }
    }

    void prepare(Collection<Path> entries) {
        entries.forEach(path -> prepare(path, true));
    }

    private Opening prepare(Path path, boolean background) {
        var key = path.toAbsolutePath().normalize();
        return roots.computeIfAbsent(key, _ -> {
            var opening = new Opening(path);
            if (background) executor.execute(opening);
            else opening.run();
            return opening;
        });
    }

    CompiledInputs.Root root(Path path) throws IOException {
        return CompiledInputs.OrderedReads.await(prepare(path, false));
    }

    @Override
    public void close() throws IOException {
        executor.close();
        IOException failure = null;
        // Finish every open before closing views, including after a failed query.
        for (var input : roots.values()) {
            if (input.state() != Future.State.SUCCESS) continue;
            try {
                input.resultNow().close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        // Failures already delivered to the search must not be thrown again by cleanup.
        for (var input : roots.values()) {
            if (input.state() != Future.State.FAILED || input.failureObserved) continue;
            try {
                CompiledInputs.OrderedReads.await(input);
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        roots.clear();
        if (failure != null) throw failure;
    }

    static FileSystem openArchive(Path path) throws IOException {
        return FileSystems.newFileSystem(path, Map.of("releaseVersion", Runtime.version()));
    }
}
