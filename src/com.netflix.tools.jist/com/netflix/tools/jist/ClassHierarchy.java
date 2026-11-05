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

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;

import com.netflix.tools.jist.CompiledInputs.Filter;

import static com.netflix.tools.jist.SearchEnvironment.parsePathList;
import static com.netflix.tools.jist.SearchEnvironment.readsUnnamedModule;

/** Minimal transient type hierarchy read directly from the selected ClassFiles. */
final class ClassHierarchy implements Closeable {
    private static final ClassFile CLASS_FILE = ClassFile.of();

    private final Map<String, Node> types = new ConcurrentHashMap<>();
    private final Map<String, List<String>> resolvedParents = new HashMap<>();
    private final SearchEnvironment environment;
    private final SourceLookup sources;
    private final Set<String> sourceTypes = ConcurrentHashMap.newKeySet();
    private final Set<Path> sourceFiles = ConcurrentHashMap.newKeySet();
    private final URLClassLoader classPath;
    private FileSystem system;
    private boolean closeSystem;

    private ClassHierarchy(Options options, SearchEnvironment environment) throws IOException, ToolException {
        this.environment = environment;
        this.sources = environment.sources();
        var urls = new ArrayList<URL>();
        if (readsUnnamedModule(options)) {
            for (var entry : parsePathList(options.classPath())) {
                urls.add(entry.toAbsolutePath().normalize().toUri().toURL());
            }
        }
        classPath = new URLClassLoader(urls.toArray(URL[]::new), null);
    }

    static ClassHierarchy read(Options options, SearchEnvironment environment) throws IOException, ToolException {
        var hierarchy = new ClassHierarchy(options, environment);
        try {
            hierarchy.sources.prepareOrdinarySources();
            var inputs = new ArrayList<Input>();
            if (!options.excludeApplicationClasses()) {
                if (readsUnnamedModule(options)) {
                    for (var path : parsePathList(options.classPath())) {
                        if (environment.searches(path)) {
                            inputs.add(new Input(path, _ -> true));
                        }
                    }
                }
                for (var module : environment.moduleInputs()) {
                    if (environment.searches(module)) {
                        inputs.add(new Input(module.location(), module::includesResource));
                    }
                }
            }
            var modules = environment.modules()
                    .systemPackages()
                    .keySet();
            if (!"none".equals(options.system())
                    && !modules.isEmpty()
                    && (options.system() == null || Files.isRegularFile(Path.of(options.system()).resolve("lib/modules")))) {
                if (options.system() == null) {
                    try {
                        hierarchy.system = FileSystems.getFileSystem(URI.create("jrt:/"));
                    } catch (FileSystemNotFoundException _) {
                        hierarchy.system = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of());
                        hierarchy.closeSystem = true;
                    }
                } else {
                    hierarchy.system = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", options.system()));
                    hierarchy.closeSystem = true;
                }
                if (!options.excludeSystemModules()) {
                    for (var module : modules) {
                        var name = module;
                        inputs.add(new Input(hierarchy.system.getPath("/modules", name), resource -> environment.includesSystemResource(name, resource)));
                    }
                }
            }
            hierarchy.scan(inputs);
            return hierarchy;
        } catch (IOException | ToolException | RuntimeException | Error e) {
            hierarchy.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try (sources; classPath) {
            if (closeSystem) {
                system.close();
            }
        }
    }

    Set<String> subtypes(Set<String> targets, boolean direct) throws IOException, ToolException {
        return descendants(targets, direct, false);
    }

    Set<String> overrideOwners(Set<String> targets) throws IOException, ToolException {
        return descendants(targets, false, true);
    }

    Set<Path> sourceFiles() {
        return Set.copyOf(sourceFiles);
    }

    /** Compiled candidates whose ancestry must account for current directory source. */
    Set<String> sourceAffected() throws IOException, ToolException {
        if (sources.explicitPaths().isEmpty()) {
            return Set.of();
        }
        var matches = new HashSet<String>();
        var memo = new HashMap<String, Boolean>();
        for (var candidate : types.keySet()) {
            if (reachesSource(candidate, memo, new HashSet<>())) {
                matches.add(candidate);
            }
        }
        return Set.copyOf(matches);
    }

    private boolean reachesSource(String candidate, Map<String, Boolean> memo, Set<String> visiting)
            throws IOException, ToolException {
        var known = memo.get(candidate);
        if (known != null) {
            return known;
        }
        if (!visiting.add(candidate)) {
            return false;
        }
        var parents = parents(candidate);
        boolean result = sourceTypes.contains(candidate);
        if (!result) {
            for (var parent : parents) {
                if (reachesSource(parent, memo, visiting)) {
                    result = true;
                    break;
                }
            }
        }
        visiting.remove(candidate);
        memo.put(candidate, result);
        return result;
    }

    private Set<String> descendants(Set<String> targets, boolean direct, boolean includeTargets) throws IOException, ToolException {
        var matches = new LinkedHashSet<String>();
        var memo = new HashMap<String, Boolean>();
        for (var candidate : types.keySet()) {
            if ((includeTargets || !targets.contains(candidate))
                    && (direct ? types.get(candidate)
                                      .parents()
                                      .stream()
                                      .anyMatch(targets::contains)
                            : reaches(candidate, targets, memo, new HashSet<>()))) {
                matches.add(candidate);
            }
        }
        return Set.copyOf(matches);
    }

    private void scan(List<Input> inputs) throws IOException, ToolException {
        var permits = new Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors()));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>(inputs.size());
            for (int index = 0; index < inputs.size(); index++) {
                int inputOrder = index;
                var input = inputs.get(index);
                tasks.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        CompiledInputs.scanEntry(
                                input.path(),
                                CompiledInputs::readAllBytes,
                                (bytes, _) -> add(inputOrder, CLASS_FILE.parse(bytes)),
                                input.filter());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            for (var task : tasks) {
                await(task);
            }
        }
    }

    private boolean reaches(String candidate, Set<String> targets, Map<String, Boolean> memo,
                            Set<String> visiting) throws IOException, ToolException {
        var known = memo.get(candidate);
        if (known != null) {
            return known;
        }
        if (!visiting.add(candidate)) {
            return false;
        }
        boolean result = false;
        for (var parent : parents(candidate)) {
            if (targets.contains(parent) || reaches(parent, targets, memo, visiting)) {
                result = true;
                break;
            }
        }
        visiting.remove(candidate);
        memo.put(candidate, result);
        return result;
    }

    private void add(int inputOrder, ClassModel model) {
        if (model.isModuleInfo()) {
            return;
        }
        rememberSource(model);
        var node = new Node(inputOrder, parents(model));
        types.compute(UsageSearch.displayClassName(model),
                (_, existing) ->
                        existing == null || inputOrder < existing.inputOrder()
                                ? node
                                : existing);
    }

    private List<String> parents(String name) throws IOException, ToolException {
        var node = types.get(name);
        if (node != null) {
            return node.parents();
        }
        var known = resolvedParents.get(name);
        if (known != null) {
            return known;
        }
        var model = classPathClass(name);
        List<String> parents;
        if (model != null) {
            parents = parents(model);
        } else {
            model = moduleClass(name);
            if (model == null) {
                model = systemClass(name);
            }
            parents = model == null ? List.of() : parents(model);
        }
        if (model != null) {
            rememberSource(model);
        } else if (SearchEnvironment.classFileCandidates(name).stream().anyMatch(sources::mayHaveOrdinarySource)) {
            var source = environment.resolveSourceFile(name);
            if (source != null) {
                sourceTypes.add(name);
                sourceFiles.add(source);
            }
        }
        resolvedParents.put(name, parents);
        return parents;
    }

    private void rememberSource(ClassModel model) {
        if (sources.mayHaveOrdinarySource(model.thisClass().asInternalName())) {
            var source = sources.ordinarySource(model);
            if (source != null) {
                sourceTypes.add(UsageSearch.displayClassName(model));
                sourceFiles.add(source);
            }
        }
    }

    private ClassModel classPathClass(String name) throws IOException {
        // Resolve resources without loading classes or applying javac's --release to multi-release jars.
        for (var candidate : SearchEnvironment.classFileCandidates(name)) {
            var resource = classPath.findResource(candidate);
            if (resource == null) {
                continue;
            }
            var connection = resource.openConnection();
            connection.setUseCaches(false);
            try (var input = connection.getInputStream()) {
                return CLASS_FILE.parse(CompiledInputs.readAllBytes(input, connection.getContentLengthLong()));
            }
        }
        return null;
    }

    private ClassModel moduleClass(String name) throws IOException, ToolException {
        for (var candidate : SearchEnvironment.classFileCandidates(name)) {
            for (var module : environment.moduleInputs()) {
                if (!module.includesResource(candidate)) {
                    continue;
                }
                var bytes = CompiledInputs.readClass(module.location(), candidate);
                if (bytes != null) {
                    return CLASS_FILE.parse(bytes);
                }
            }
        }
        return null;
    }

    private ClassModel systemClass(String name) throws IOException, ToolException {
        if (system == null) {
            return null;
        }
        for (var candidate : SearchEnvironment.classFileCandidates(name)) {
            for (var module : environment.modules().systemPackages().keySet()) {
                if (!environment.includesSystemResource(module, candidate)) {
                    continue;
                }
                var file = system.getPath("/modules", module, candidate);
                if (Files.isRegularFile(file)) {
                    return CLASS_FILE.parse(Files.readAllBytes(file));
                }
            }
        }
        return null;
    }

    private static List<String> parents(ClassModel model) {
        var parents = new ArrayList<String>(model.interfaces().size() + 1);
        model.superclass().ifPresent(parent -> parents.add(displayName(parent.asInternalName())));
        model.interfaces().forEach(parent -> parents.add(displayName(parent.asInternalName())));
        return List.copyOf(parents);
    }

    private static void await(Future<?> task) throws IOException, ToolException {
        try {
            task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var interrupted = new InterruptedIOException("Interrupted while reading ClassFiles");
            interrupted.initCause(e);
            throw interrupted;
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof IOException failure) {
                throw failure;
            }
            if (cause instanceof ToolException failure) {
                throw failure;
            }
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            if (cause instanceof Error failure) {
                throw failure;
            }
            throw new IOException("Failed to read ClassFile hierarchy", cause);
        }
    }

    private static String displayName(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    private record Input(Path path, Filter filter) {}

    private record Node(int inputOrder, List<String> parents) {}
}
