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

        private static <T> T await(Future<T> future) throws IOException {
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
        if (!options.excludeApplicationClasses()) {
            if (readsUnnamedModule(options)) {
                for (var path : environment.classPathEntries()) {
                    if (environment.searches(path)) {
                        scanEntry(path, reader, consumer, _ -> true, executor);
                    }
                }
            }
            for (var module : environment.moduleInputs()) {
                if (environment.searches(module)) {
                    scanEntry(module.location(), reader, consumer, module::includesResource, executor);
                }
            }
        }
        var modules = environment.modules().systemPackages().keySet();
        if (options.excludeSystemModules() || "none".equals(options.system()) || modules.isEmpty()) {
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
                scanEntry(system.getPath("/modules", module), reader, consumer,
                        resource -> environment.includesSystemResource(module, resource), executor);
            }
        } finally {
            if (close) {
                system.close();
            }
        }
    }

    static byte[] readClass(Path root, String resource) throws IOException {
        if (Files.isDirectory(root)) {
            var file = root.resolve(resource);
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        if (Files.isRegularFile(root)) {
            try (var jar = ClassPaths.openArchive(root, true)) {
                var entry = jar.getJarEntry(resource);
                if (entry != null) {
                    try (var input = jar.getInputStream(entry)) {
                        return readAllBytes(input, entry.getSize());
                    }
                }
            }
        }
        return null;
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

    private static void scanEntry(Path path, Reader reader, Consumer consumer, Filter filter, ExecutorService executor)
            throws IOException, ToolException {
        try (var root = Root.open(path);
             var reads = new OrderedReads<ReadClass>(executor, value -> consumer.accept(value.bytes(), value.input()))) {
            root.forEach(false, input -> {
                if (filter.test(input.resource())) {
                    reads.submit(() -> {
                        var bytes = input.read(reader);
                        return bytes == null ? null : new ReadClass(bytes, input);
                    });
                }
            });
            reads.finish();
        }
    }

    record ClassInput(Path file, String resource, long size, String archiveOrigin, String physicalResource)
            implements Supplier<String> {
        @Override
        public String get() {
            return archiveOrigin == null ? unitLocation(file) : archiveOrigin + physicalResource;
        }

        byte[] read() throws IOException {
            return readClassFile(file, size < 0 ? Files.size(file) : size);
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
        private final JarFile jar;
        private final FileSystem archive;
        private final String origin;

        private Root(Path path, JarFile jar, FileSystem archive) {
            this.path = path;
            this.jar = jar;
            this.archive = archive;
            this.origin = jar == null ? null : "jar:" + path.toAbsolutePath().normalize().toUri() + "!/";
        }

        static Root open(Path path) throws IOException {
            if (!Files.isRegularFile(path)) {
                return new Root(path, null, null);
            }
            var jar = ClassPaths.openArchive(path, false);
            try {
                // ZipFile serializes payload reads; zipfs uses positional FileChannel reads.
                return new Root(path, jar, FileSystems.newFileSystem(path));
            } catch (IOException | RuntimeException | Error failure) {
                try (jar) {
                    throw failure;
                }
            }
        }

        boolean isArchive() {
            return jar != null;
        }

        private ClassInput input(JarEntry entry) {
            return new ClassInput(archive.getPath(entry.getRealName()), entry.getName(), entry.getSize(),
                    origin, entry.getRealName());
        }

        ClassInput find(String resource) throws IOException {
            if (jar != null) {
                var entry = jar.getJarEntry(resource);
                return entry == null ? null : input(entry);
            }
            var file = path.resolve(resource);
            return Files.isRegularFile(file) ? new ClassInput(file, resource, -1, null, null) : null;
        }

        void forEach(boolean metadataFirst, InputConsumer consumer) throws IOException, ToolException {
            if (jar != null) {
                var entries = jar.isMultiRelease() ? jar.versionedStream().iterator() : jar.entries().asIterator();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                        consumer.accept(input(entry));
                    }
                }
            } else if (Files.isDirectory(path)) {
                walkFiles(path, ".class", metadataFirst, file -> consumer.accept(new ClassInput(file,
                        path.relativize(file).toString().replace(File.separatorChar, '/'), -1, null, null)));
            }
        }

        @Override
        public void close() throws IOException {
            if (jar != null) {
                try (jar; archive) {
                    // Readers must finish or be cancelled and joined before closing their root.
                }
            }
        }
    }

    private static byte[] readClassFile(Path file, long size) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE) {
            return Files.readAllBytes(file);
        }
        var bytes = new byte[(int) size];
        try (var input = Files.newInputStream(file)) {
            int read = input.readNBytes(bytes, 0, bytes.length);
            if (read != bytes.length) {
                throw new EOFException("Expected " + bytes.length + " bytes for " + file + ", read " + read);
            }
        }
        return bytes;
    }

    @FunctionalInterface
    interface PathConsumer {
        void accept(Path path) throws IOException, ToolException;
    }

    private static final class MetadataVisitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MetadataVisitException(Exception cause) {
            super(cause);
        }
    }

    static void walkFiles(Path directory, String extension, PathConsumer consumer)
            throws IOException, ToolException {
        walkFiles(directory, extension, true, consumer);
    }

    private static void walkFiles(Path directory, String extension, boolean metadataFirst, PathConsumer consumer)
            throws IOException, ToolException {
        var root = Files.isSymbolicLink(directory) ? directory.toRealPath() : directory;
        PathConsumer visit = root.equals(directory) ? consumer
                : file -> consumer.accept(directory.resolve(root.relativize(file)));
        var metadata = metadataFirst ? List.of("module-info" + extension, "package-info" + extension) : List.<String>of();
        try (var files = Files.find(root, Integer.MAX_VALUE, (file, attributes) -> {
            if (attributes.isDirectory()) {
                try {
                    for (var name : metadata) {
                        var descriptor = file.resolve(name);
                        if (Files.isRegularFile(descriptor)) {
                            visit.accept(descriptor);
                        }
                    }
                } catch (IOException | ToolException failure) {
                    // Files.find's predicate cannot propagate the consumer's checked failures.
                    throw new MetadataVisitException(failure);
                }
                return false;
            }
            return file.toString().endsWith(extension)
                    && !metadata.contains(file.getFileName().toString())
                    && (attributes.isRegularFile() || attributes.isSymbolicLink() && Files.isRegularFile(file));
        })) {
            try {
                var iterator = files.iterator();
                while (iterator.hasNext()) {
                    visit.accept(iterator.next());
                }
            } catch (MetadataVisitException failure) {
                if (failure.getCause() instanceof IOException cause) {
                    throw cause;
                }
                throw (ToolException) failure.getCause();
            }
        }
    }
}
