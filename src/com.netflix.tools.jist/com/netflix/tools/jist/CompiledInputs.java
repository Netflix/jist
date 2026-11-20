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

import static com.netflix.tools.jist.SearchEnvironment.readsUnnamedModule;
import static com.netflix.tools.jist.SearchEnvironment.unitLocation;

/** Streams ClassFiles from the selected compilation inputs. */
final class CompiledInputs {
    private static final int CLASS_FILE_CAPACITY = Runtime.getRuntime().availableProcessors();

    @FunctionalInterface
    interface Reader {
        byte[] read(InputStream input, long size) throws IOException;
    }

    @FunctionalInterface
    interface Consumer {
        void accept(byte[] bytes, Supplier<String> location) throws IOException, ToolException;
    }

    @FunctionalInterface
    interface Filter {
        boolean test(String resource) throws IOException, ToolException;
    }

    @FunctionalInterface
    interface ResultConsumer<T> {
        void accept(T value) throws IOException, ToolException;
    }

    static class OrderedReads<T> implements AutoCloseable {
        private final ExecutorService executor;
        private final ResultConsumer<T> consumer;
        private final Deque<Future<T>> pending = new ArrayDeque<>();
        private final Semaphore capacity = new Semaphore(CLASS_FILE_CAPACITY);
        private final Semaphore completed = new Semaphore(0);
        private boolean finished;

        OrderedReads(ExecutorService executor, ResultConsumer<T> consumer) {
            this.executor = executor;
            this.consumer = consumer;
        }

        void submit(Callable<T> reader) throws IOException, ToolException {
            reserve();
            var future = new FutureTask<T>(() -> {
                var value = reader.call();
                if (value == null) {
                    // Rejected entries retain no ClassFile while awaiting their ordered turn.
                    capacity.release();
                }
                return value;
            }) {
                @Override
                protected void done() {
                    // Wake the producer only after the Future publishes its completed state.
                    completed.release();
                }
            };
            pending.addLast(future);
            executor.execute(future);
        }

        void add(T value) throws IOException, ToolException {
            if (value == null) {
                return;
            }
            if (pending.isEmpty()) {
                consumer.accept(value);
            } else {
                reserve();
                pending.addLast(CompletableFuture.completedFuture(value));
            }
        }

        private void reserve() throws IOException, ToolException {
            // Reserve in submission order so later retained results cannot starve the oldest reader.
            while (true) {
                completed.drainPermits();
                while (!pending.isEmpty() && pending.getFirst().isDone()) {
                    emitFirst();
                    if (capacity.tryAcquire()) {
                        return;
                    }
                }
                if (capacity.tryAcquire()) {
                    return;
                }
                try {
                    completed.acquire();
                } catch (InterruptedException failure) {
                    throw interruptedRead(failure);
                }
            }
        }

        void finish() throws IOException, ToolException {
            while (!pending.isEmpty()) {
                emitFirst();
            }
            finished = true;
        }

        private void emitFirst() throws IOException, ToolException {
            var value = await(pending.removeFirst());
            if (value != null) {
                try {
                    consumer.accept(value);
                } finally {
                    capacity.release();
                }
            }
        }

        private static InterruptedIOException interruptedRead(InterruptedException failure) {
            Thread.currentThread().interrupt();
            var interrupted = new InterruptedIOException("Interrupted while reading ClassFiles");
            interrupted.initCause(failure);
            return interrupted;
        }

        static <T> T await(Future<T> future) throws IOException {
            try {
                return future.get();
            } catch (InterruptedException failure) {
                throw interruptedRead(failure);
            } catch (ExecutionException failure) {
                var cause = failure.getCause();
                if (cause instanceof IOException exception) {
                    throw exception;
                }
                if (cause instanceof RuntimeException exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IOException("Failed to read ClassFile", cause);
            }
        }

        @Override
        public void close() {
            if (!finished) {
                pending.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                executor.close();
            }
        }
    }

    static void scan(Options options, SearchEnvironment environment, Reader reader, Consumer consumer)
            throws IOException, ToolException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            scan(options, environment, reader, consumer, executor);
        }
    }

    private static void scan(Options options, SearchEnvironment environment, Reader reader, Consumer consumer,
                             ExecutorService executor) throws IOException, ToolException {
        environment.prepareClassPath();
        if (!options.excludeApplicationClasses()) {
            if (readsUnnamedModule(options)) {
                for (var path : environment.classPathEntries()) {
                    if (environment.searches(path)) {
                        scanEntry(environment.compiledInput(path), reader, consumer, _ -> true, executor);
                    }
                }
            }
            for (var module : environment.moduleInputs()) {
                if (environment.searches(module)) {
                    scanEntry(environment.compiledInput(module.location()), reader, consumer, module::includesResource, executor);
                }
            }
        }
        var modules = environment.modules().systemPackages().keySet();
        if (options.excludeSystemModules() || modules.isEmpty()) {
            return;
        }
        var system = environment.systemModules();
        if (system == null) {
            return;
        }
        for (var module : modules) {
            scanEntry(environment.compiledInput(system.resolve(module)), reader, consumer,
                    resource -> environment.includesSystemResource(module, resource), executor);
        }
    }

    static byte[] readAllBytes(InputStream input, long size) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE) {
            return input.readAllBytes();
        }
        var bytes = new byte[(int) size];
        int read = input.readNBytes(bytes, 0, bytes.length);
        if (read != bytes.length) {
            throw new EOFException("Expected " + bytes.length + " bytes, read " + read);
        }
        return bytes;
    }

    private record ReadClass(byte[] bytes, ClassInput input) {}

    private static void scanEntry(Root root, Reader reader, Consumer consumer, Filter filter, ExecutorService executor)
            throws IOException, ToolException {
        try (var reads = new OrderedReads<ReadClass>(executor, value -> consumer.accept(value.bytes(), value.input()))) {
            root.forEach(false, filter, input -> reads.submit(() -> {
                var bytes = input.read(reader);
                return bytes == null ? null : new ReadClass(bytes, input);
            }));
            reads.finish();
        }
    }

    record ClassInput(Path file, String resource, long size) implements Supplier<String> {
        @Override
        public String get() {
            return unitLocation(file);
        }

        byte[] read() throws IOException {
            return read(CompiledInputs::readAllBytes);
        }

        byte[] read(Reader reader) throws IOException {
            try (var input = Files.newInputStream(file)) {
                return reader.read(input, size < 0 ? Files.size(file) : size);
            }
        }
    }

    @FunctionalInterface
    interface InputConsumer {
        void accept(ClassInput input) throws IOException, ToolException;
    }

    static final class Root implements AutoCloseable {
        private final Path path;
        private final FileSystem archive;

        private Root(Path path, FileSystem archive) {
            this.path = path;
            this.archive = archive;
        }

        static Root open(Path path) throws IOException {
            if (!Files.isRegularFile(path)) {
                return new Root(path, null);
            }
            try {
                var archive = ClassPaths.openArchive(path);
                return new Root(archive.getPath("/"), archive);
            } catch (ZipException | ProviderNotFoundException failure) {
                var name = path.getFileName().toString();
                if (name.endsWith(".jar") || name.endsWith(".zip")) {
                    throw new IOException("Cannot read archive " + path + ": " + failure.getMessage(), failure);
                }
                // Class paths may contain non-archive dependency artifacts.
                return new Root(path, null);
            } catch (IOException failure) {
                throw new IOException("Cannot read class-path entry " + path + ": " + failure.getMessage(), failure);
            }
        }

        Path path() {
            return path;
        }

        boolean isArchive() {
            return archive != null;
        }

        String location() {
            return unitLocation(path);
        }

        ClassInput find(String resource) throws IOException {
            var file = path.resolve(resource);
            return Files.isRegularFile(file) ? new ClassInput(file, resource, -1) : null;
        }

        void forEach(boolean metadataFirst, InputConsumer consumer) throws IOException, ToolException {
            forEach(metadataFirst, resource -> true, consumer);
        }

        void forEach(boolean metadataFirst, Filter filter, InputConsumer consumer) throws IOException, ToolException {
            if (!Files.isDirectory(path)) return;
            var prefix = path.toString();
            int firstCharacter = prefix.length() + (!prefix.isEmpty() && path.getFileName() != null ? 1 : 0);
            var versions = isArchive() ? path.resolve("META-INF/versions") : null;
            walkFiles(path, ".class", metadataFirst, directory -> !directory.equals(versions), (file, attributes) -> {
                var resource = file.toString().substring(firstCharacter).replace(File.separatorChar, '/');
                if (filter.test(resource)) {
                    consumer.accept(new ClassInput(file, resource, attributes.size()));
                }
            });
        }

        void forEachPackage(Collection<String> packages, Filter filter, InputConsumer consumer) throws IOException, ToolException {
            for (var name : packages.stream().sorted().toList()) {
                var prefix = name.isEmpty() ? "" : name.replace('.', '/') + "/";
                var directory = path.resolve(prefix);
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (var files = Files.newDirectoryStream(directory)) {
                    for (var file : files) {
                        var resource = prefix + file.getFileName();
                        if (resource.endsWith(".class") && filter.test(resource)) {
                            var input = find(resource);
                            if (input != null) consumer.accept(input);
                        }
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (archive != null) archive.close();
        }
    }

    @FunctionalInterface
    interface PathConsumer {
        void accept(Path path) throws IOException, ToolException;
    }

    @FunctionalInterface
    private interface FileConsumer {
        void accept(Path path, BasicFileAttributes attributes) throws IOException, ToolException;
    }

    private static final class ToolVisitException extends IOException {
        private static final long serialVersionUID = 1L;

        ToolVisitException(ToolException cause) {
            super(cause);
        }
    }

    static void walkFiles(Path directory, String extension, PathConsumer consumer)
            throws IOException, ToolException {
        walkFiles(directory, extension, true, _ -> true, (file, _) -> consumer.accept(file));
    }

    private static void walkFiles(Path directory, String extension, boolean metadataFirst,
            Predicate<Path> descend, FileConsumer consumer) throws IOException, ToolException {
        var root = Files.isSymbolicLink(directory) ? directory.toRealPath() : directory;
        FileConsumer visit = root.equals(directory) ? consumer
                : (file, attributes) -> consumer.accept(directory.resolve(root.relativize(file)), attributes);
        var metadata = metadataFirst ? List.of("module-info" + extension, "package-info" + extension) : List.<String>of();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (!descend.test(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    for (var name : metadata) {
                        var descriptor = directory.resolve(name);
                        if (Files.isRegularFile(descriptor)) {
                            emit(descriptor, Files.readAttributes(descriptor, BasicFileAttributes.class));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (file.toString().endsWith(extension)
                            && (metadata.isEmpty() || !metadata.contains(file.getFileName().toString()))) {
                        if (attributes.isSymbolicLink() && Files.isRegularFile(file)) {
                            attributes = Files.readAttributes(file, BasicFileAttributes.class);
                        }
                        if (attributes.isRegularFile()) {
                            emit(file, attributes);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                private void emit(Path file, BasicFileAttributes attributes) throws IOException {
                    try {
                        visit.accept(file, attributes);
                    } catch (ToolException failure) {
                        throw new ToolVisitException(failure);
                    }
                }
            });
        } catch (ToolVisitException failure) {
            throw (ToolException) failure.getCause();
        }
    }
}
