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
final class ClassHierarchy {
    private static final ClassFile CLASS_FILE = ClassFile.of();

    private final Map<String, Node> types = new ConcurrentHashMap<>();

    static ClassHierarchy read(Options options, SearchEnvironment environment) throws IOException, ToolException {
        var hierarchy = new ClassHierarchy();
        FileSystem system = null;
        boolean closeSystem = false;
        try {
            var inputs = new ArrayList<Input>();
            if (!options.excludeApplicationClasses()) {
                if (readsUnnamedModule(options)) {
                    for (var path : parsePathList(options.classPath())) {
                        inputs.add(new Input(path, _ -> true));
                    }
                }
                for (var module : environment.moduleInputs()) {
                    inputs.add(new Input(module.location(), module::includesResource));
                }
            }
            var modules = environment.modules()
                    .systemPackages()
                    .keySet();
            if (!options.excludeSystemModules()
                    && !"none".equals(options.system())
                    && !modules.isEmpty()
                    && (options.system() == null || Files.isRegularFile(Path.of(options.system()).resolve("lib/modules")))) {
                if (options.system() == null) {
                    try {
                        system = FileSystems.getFileSystem(URI.create("jrt:/"));
                    } catch (FileSystemNotFoundException _) {
                        system = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of());
                        closeSystem = true;
                    }
                } else {
                    system = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", options.system()));
                    closeSystem = true;
                }
                for (var module : modules) {
                    var name = module;
                    inputs.add(new Input(system.getPath("/modules", name), resource -> environment.includesSystemResource(name, resource)));
                }
            }
            hierarchy.scan(inputs);
            return hierarchy;
        } finally {
            if (closeSystem) {
                system.close();
            }
        }
    }

    Set<String> subtypes(Set<String> targets, boolean direct) {
        return descendants(targets, direct, false);
    }

    Set<String> overrideOwners(Set<String> targets) {
        return descendants(targets, false, true);
    }

    private Set<String> descendants(Set<String> targets, boolean direct, boolean includeTargets) {
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
                                InputStream::readAllBytes,
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
                            Set<String> visiting) {
        var known = memo.get(candidate);
        if (known != null) {
            return known;
        }
        if (!visiting.add(candidate)) {
            return false;
        }
        boolean result = false;
        var node = types.get(candidate);
        if (node != null) {
            for (var parent : node.parents()) {
                if (targets.contains(parent) || reaches(parent, targets, memo, visiting)) {
                    result = true;
                    break;
                }
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
        var direct = new ArrayList<String>(model.interfaces().size() + 1);
        model.superclass().ifPresent(parent -> direct.add(displayName(parent.asInternalName())));
        model.interfaces().forEach(parent -> direct.add(displayName(parent.asInternalName())));
        var node = new Node(inputOrder, List.copyOf(direct));
        types.compute(UsageSearch.displayClassName(model),
                (_, existing) ->
                        existing == null || inputOrder < existing.inputOrder()
                                ? node
                                : existing);
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
