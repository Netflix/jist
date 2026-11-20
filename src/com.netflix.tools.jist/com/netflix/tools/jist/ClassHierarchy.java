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

import static com.netflix.tools.jist.SearchEnvironment.readsUnnamedModule;

/** Minimal transient type hierarchy read directly from the selected ClassFiles. */
final class ClassHierarchy implements Closeable {
    private static final ClassFile CLASS_FILE = ClassFile.of();

    private final Map<String, List<String>> types = new HashMap<>();
    private final Map<String, List<String>> resolvedParents = new HashMap<>();
    private final Map<String, List<PreparedClass>> compiledInputs = new HashMap<>();
    private final SearchEnvironment environment;
    private final SourceLookup sources;
    private final Set<String> sourceTypes = new HashSet<>();
    private final Set<Path> sourceFiles = new HashSet<>();
    private final List<Path> classPath;
    private Path system;
    private Map<String, Set<String>> systemPackages;

    private ClassHierarchy(Options options, SearchEnvironment environment) throws IOException, ToolException {
        this.environment = environment;
        this.sources = environment.sources();
        classPath = readsUnnamedModule(options) ? environment.classPathEntries() : List.of();
    }

    static ClassHierarchy read(Options options, SearchEnvironment environment) throws IOException, ToolException {
        environment.prepareClassPath();
        var hierarchy = new ClassHierarchy(options, environment);
        try {
            hierarchy.sources.prepareOrdinarySources();
            var inputs = new ArrayList<Input>();
            if (!options.excludeApplicationClasses()) {
                if (readsUnnamedModule(options)) {
                    for (var path : environment.classPathEntries()) {
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
            if (!modules.isEmpty()) {
                hierarchy.system = environment.systemModules();
                if (hierarchy.system != null && !options.excludeSystemModules()) {
                    for (var module : modules) {
                        inputs.add(new Input(hierarchy.system.resolve(module),
                                resource -> environment.includesSystemResource(module, resource)));
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
        sources.close();
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
        var visiting = new HashSet<String>();
        for (var candidate : types.keySet()) {
            if (reachesSource(candidate, memo, visiting)) {
                matches.add(displayName(candidate));
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
        var visiting = new HashSet<String>();
        for (var candidate : types.keySet()) {
            if ((includeTargets || !targets.contains(displayName(candidate)))
                    && (direct ? types.get(candidate)
                                      .stream()
                                      .anyMatch(parent -> targets.contains(displayName(parent)))
                            : reaches(candidate, targets, memo, visiting))) {
                matches.add(displayName(candidate));
            }
        }
        return Set.copyOf(matches);
    }

    Map<String, List<String>> compiledResources(Predicate<String> matches) {
        var selected = new HashMap<String, List<String>>();
        compiledInputs.forEach((root, classes) -> {
            var resources = classes.stream()
                    .filter(candidate -> matches.test(displayName(candidate.name())))
                    .map(PreparedClass::resource)
                    .toList();
            if (!resources.isEmpty()) {
                selected.put(root, resources);
            }
        });
        return Map.copyOf(selected);
    }

    private void scan(List<Input> inputs) throws IOException, ToolException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var input : inputs) {
                var classes = new ArrayList<PreparedClass>();
                var root = environment.compiledInput(input.path());
                try (var reads = new CompiledInputs.OrderedReads<PreparedClass>(executor, prepared -> {
                    add(prepared);
                    classes.add(prepared);
                })) {
                    root.forEach(false, resource -> {
                        if (input.filter().test(resource.resource())) {
                            reads.submit(() -> prepare(CLASS_FILE.parse(resource.read()), resource.resource()));
                        }
                    });
                    reads.finish();
                }
                // The selected JRT filesystem may be reopened before declaration output.
                compiledInputs.put(SearchEnvironment.unitLocation(input.path()), classes);
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
            if (targets.contains(displayName(parent)) || reaches(parent, targets, memo, visiting)) {
                result = true;
                break;
            }
        }
        visiting.remove(candidate);
        memo.put(candidate, result);
        return result;
    }

    private PreparedClass prepare(ClassModel model, String resource) {
        if (model.isModuleInfo()) {
            return null;
        }
        var internalName = model.thisClass().asInternalName();
        var source = sources.mayHaveOrdinarySource(internalName) ? sources.ordinarySource(model) : null;
        return new PreparedClass(internalName.replace('/', '.'), parents(model), source, resource);
    }

    private void add(PreparedClass prepared) {
        rememberSource(prepared);
        types.putIfAbsent(prepared.name(), prepared.parents());
    }

    private List<String> parents(String name) throws IOException, ToolException {
        var scanned = types.get(name);
        if (scanned != null) {
            return scanned;
        }
        var known = resolvedParents.get(name);
        if (known != null) {
            return known;
        }
        var resource = name.replace('.', '/') + ".class";
        var model = classPathClass(resource);
        if (model == null) {
            model = moduleClass(resource);
            if (model == null) {
                model = systemClass(resource);
            }
        }
        List<String> parents;
        if (model != null) {
            var prepared = prepare(model, null);
            parents = prepared == null ? List.of() : prepared.parents();
            if (prepared != null) {
                rememberSource(prepared);
            }
        } else {
            parents = List.of();
            if (sources.mayHaveOrdinarySource(resource)) {
                var source = environment.resolveSourceFile(name);
                if (source != null) {
                    sourceTypes.add(name);
                    sourceFiles.add(source);
                }
            }
        }
        resolvedParents.put(name, parents);
        return parents;
    }

    private void rememberSource(PreparedClass prepared) {
        if (prepared.source() != null) {
            sourceTypes.add(prepared.name());
            sourceFiles.add(prepared.source());
        }
    }

    private ClassModel classPathClass(String resource) throws IOException {
        // Resolve resources without loading classes or applying javac's --release to multi-release jars.
        for (var path : classPath) {
            var input = environment.compiledInput(path).find(resource);
            if (input != null) {
                return CLASS_FILE.parse(input.read());
            }
        }
        return null;
    }

    private ClassModel moduleClass(String resource) throws IOException, ToolException {
        for (var module : environment.moduleInputs()) {
            if (!module.includesResource(resource)) {
                continue;
            }
            var input = environment.compiledInput(module.location()).find(resource);
            if (input != null) {
                return CLASS_FILE.parse(input.read());
            }
        }
        return null;
    }

    private ClassModel systemClass(String resource) throws IOException, ToolException {
        if (system == null) {
            return null;
        }
        if (systemPackages == null) {
            systemPackages = environment.systemModulePackages();
        }
        int slash = resource.lastIndexOf('/');
        var packageName = slash < 0 ? "" : resource.substring(0, slash).replace('/', '.');
        for (var module : systemPackages.entrySet()) {
            if (!module.getValue().contains(packageName)
                    || !environment.includesSystemResource(module.getKey(), resource)) {
                continue;
            }
            var file = system.resolve(module.getKey()).resolve(resource);
            if (Files.isRegularFile(file)) {
                return CLASS_FILE.parse(Files.readAllBytes(file));
            }
        }
        return null;
    }

    private static List<String> parents(ClassModel model) {
        var parents = new ArrayList<String>(model.interfaces().size() + 1);
        model.superclass().ifPresent(parent -> parents.add(parent.asInternalName().replace('/', '.')));
        model.interfaces().forEach(parent -> parents.add(parent.asInternalName().replace('/', '.')));
        return List.copyOf(parents);
    }

    private static String displayName(String binaryName) {
        return binaryName.replace('$', '.');
    }

    private record Input(Path path, Filter filter) {}

    private record PreparedClass(String name, List<String> parents, Path source, String resource) {}
}
