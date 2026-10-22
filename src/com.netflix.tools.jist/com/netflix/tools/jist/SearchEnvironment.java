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

import java.lang.classfile.ClassModel;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;

/** Resolves search paths, modules, source units, and exact classes. */
final class SearchEnvironment {
    private static final Map<Path, Map<String, java.lang.module.ModuleReference>> SYSTEM_MODULES = new java.util.concurrent.ConcurrentHashMap<>();

    private final Options options;
    private ModuleResolution resolvedModules;
    private Map<String, List<Path>> parsedPatches;
    private List<ModuleSourceInput> resolvedSourceInputs;

    SearchEnvironment(Options options) {
        this.options = options;
    }

    ModuleResolution modules() throws IOException, ToolException {
        if (resolvedModules == null)
            resolvedModules = computeModuleResolution(options);
        return resolvedModules;
    }

    private Map<String, List<Path>> patches() throws ToolException {
        if (parsedPatches == null)
            parsedPatches = ModulePatches.parse(options.patchModules());
        return parsedPatches;
    }

    List<String> compilerOptions(boolean enablePreview, boolean preserveNoSystem) throws IOException, ToolException {
        var opts = options;
        var result = new ArrayList<String>();
        result.add("-proc:none");
        result.add("-implicit:none");
        result.add("-XDshould-stop.at=ATTR");
        if (enablePreview) {
            result.addAll(List.of("--enable-preview", "--source",
                    Integer.toString(Runtime.version().feature())));
        }
        if (opts.modulePath() != null && !opts.modulePath().isBlank()) {
            result.addAll(List.of("--module-path", opts.modulePath()));
        }
        if (opts.upgradeModulePath() != null && !opts.upgradeModulePath().isBlank()) {
            result.addAll(List.of("--upgrade-module-path", opts.upgradeModulePath()));
        }
        if (opts.classPath() != null && !opts.classPath().isBlank()) {
            result.addAll(List.of("--class-path", opts.classPath()));
        }
        var selected = selectedModules(opts);
        if (!selected.isEmpty()) {
            result.addAll(List.of("--add-modules", String.join(",", selected)));
        }
        if (opts.addModules() != null) {
            result.addAll(List.of("--add-modules", opts.addModules()));
        }
        for (var value : opts.addExports()) {
            result.addAll(List.of("--add-exports", value));
        }
        for (var value : opts.addReads()) {
            result.addAll(List.of("--add-reads", value));
        }
        if (opts.limitModules() != null) {
            result.addAll(List.of("--limit-modules", opts.limitModules()));
        }
        var patches = new LinkedHashMap<>(patches());
        var directories = parsePathList(opts.sourcePath()).stream().filter(Files::isDirectory).toList();
        if (!directories.isEmpty()) {
            if (selected.size() == 1 && opts.moduleSourcePaths().isEmpty()) {
                var roots = new LinkedHashSet<>(patches.getOrDefault(selected.getFirst(), List.of()));
                roots.addAll(directories);
                patches.put(selected.getFirst(), List.copyOf(roots));
            } else {
                result.addAll(List.of("--source-path", directories.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))));
            }
        }
        for (var patch : patches.entrySet()) {
            result.addAll(List.of("--patch-module", patch.getKey() + "="
                    + patch.getValue().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))));
        }
        if (opts.system() != null && (preserveNoSystem || !opts.system().equals("none"))) {
            result.addAll(List.of("--system", opts.system()));
        }
        return List.copyOf(result);
    }

    List<String> sourceCompilerOptions(StandardJavaFileManager manager, boolean enablePreview, boolean preserveNoSystem)
            throws IOException, ToolException {
        var arguments = new ArrayList<>(compilerOptions(enablePreview, preserveNoSystem));
        if (!options.moduleSourcePaths().isEmpty()) {
            int sourcePath = arguments.indexOf("--source-path");
            if (sourcePath >= 0) arguments.subList(sourcePath, sourcePath + 2).clear();
            for (var path : options.moduleSourcePaths()) {
                arguments.addAll(List.of("--module-source-path", path));
            }
            // javac requires an output location with --module-source-path; attribution never writes ClassFiles.
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(Path.of(System.getProperty("java.io.tmpdir"))));
        }
        return List.copyOf(arguments);
    }

    static String listedClassName(String resourceName) {
        if (!resourceName.endsWith(CLASS_EXT) || resourceName.startsWith(META_INF))
            return null;
        var internalName = resourceName.substring(0, resourceName.length() - CLASS_EXT.length());
        for (int dollar = internalName.indexOf('$');
             dollar >= 0;
             dollar = internalName.indexOf('$', dollar + 1)) {
            if (dollar + 1 >= internalName.length()
                    || Character.isDigit(internalName.charAt(dollar + 1))) {
                return null;
            }
        }
        return internalName.replace('/', '.').replace('$', '.');
    }

    private static final class SourceClassFinder extends TreePathScanner<Void, Void> {
        private final String target;
        private final Deque<String> classNames = new ArrayDeque<>();
        private String packageName = "";
        private boolean found;

        SourceClassFinder(String target) {
            this.target = target;
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree tree, Void unused) {
            packageName = tree.getPackageName() == null
                    ? ""
                    : tree.getPackageName().toString();
            return super.visitCompilationUnit(tree, unused);
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            if (found)
                return null;
            var parent = getCurrentPath().getParentPath();
            if (parent == null || !(parent.getLeaf() instanceof CompilationUnitTree || parent.getLeaf() instanceof ClassTree))
                return null;
            var simpleName = tree.getSimpleName().toString();
            if (simpleName.isEmpty())
                return null;
            var className = classNames.isEmpty()
                    ? packageName.isEmpty() ? simpleName : packageName + "." + simpleName
                    : classNames.getLast() + "." + simpleName;
            if (className.equals(target)) {
                found = true;
                return null;
            }
            classNames.addLast(className);
            try {
                return super.visitClass(tree, unused);
            } finally {
                classNames.removeLast();
            }
        }
    }

    static String unitLocation(Path path) {
        return path.getFileSystem() == FileSystems.getDefault()
                ? path.toAbsolutePath()
                        .normalize()
                        .toString()
                : path.toUri().toString();
    }

    SymbolSelection resolveSymbolSelection(String target) throws IOException, ToolException {
        var opts = options;
        if (target.contains("::")) {
            throw new ToolException(1, "Invalid symbol target '" + target + "'; use '.' between a type and member");
        }
        if (Files.isRegularFile(Path.of(target)))
            return new SymbolSelection(target, null);
        for (var extension : List.of(JAVA_EXT, CLASS_EXT)) {
            var marker = extension + ".";
            int separator = target.lastIndexOf(marker);
            if (separator >= 0) {
                var path = target.substring(0, separator + extension.length());
                return new SymbolSelection(path, target.substring(separator + marker.length()));
            }
        }
        if (classTargetExists(target))
            return new SymbolSelection(target, null);
        int separator = target.lastIndexOf('.');
        if (separator > 0) {
            var owner = target.substring(0, separator);
            if (classTargetExists(owner)) {
                return new SymbolSelection(owner, target.substring(separator + 1));
            }
        }
        return new SymbolSelection(target, null);
    }

    boolean classTargetExists(String target) throws IOException, ToolException {
        var opts = options;
        if (readsUnnamedModule(opts)
                        && resolveClass(target, parsePathList(opts.classPath())) != null
                || resolveModuleClass(target) != null
                || resolveSystemClass(target) != null
                || resolveSourceFile(target) != null)
            return true;
        return false;
    }

    record ModuleInput(String name,
                       Path location,
                       Set<String> visiblePackages,
                       java.lang.module.ModuleDescriptor descriptor,
                       List<Path> precedingPatches,
                       boolean patch,
                       boolean selected) {
        boolean includesResource(String resource) throws IOException {
            return !(patch && resource.equals("module-info.class")) && !ModulePatches.contains(precedingPatches, resource);
        }

        boolean visibleResource(String resource) throws IOException {
            return includesResource(resource) && (selected || isVisibleModuleClass(resource, visiblePackages));
        }
    }

    record ModuleSourceInput(Path location, Set<String> visiblePackages, List<Path> precedingPatches, boolean patch) {
        boolean includesResource(String resource) throws IOException {
            if (resource.equals("module-info.java")) return !patch;
            var slash = resource.lastIndexOf('/');
            var packageName = slash < 0 ? "" : resource.substring(0, slash).replace('/', '.');
            return (visiblePackages == null || visiblePackages.contains(packageName))
                    && !ModulePatches.contains(precedingPatches, resource);
        }
    }

    record ModuleResolution(List<ModuleInput> applicationModules,
                            Map<String, Set<String>> applicationPackages,
                            Map<String, Set<String>> systemPackages) {}

    List<ModuleInput> moduleInputs() throws IOException, ToolException {
        return modules().applicationModules();
    }

    private static ModuleResolution computeModuleResolution(Options opts) throws IOException, ToolException {
        var entries = parsePathList(opts.modulePath());
        var applicationFinder = java.lang.module.ModuleFinder.of(entries.toArray(Path[]::new));
        var compiledReferences = referencesByName(applicationFinder);
        var upgradeEntries = parsePathList(opts.upgradeModulePath());
        var upgrades = referencesByName(java.lang.module.ModuleFinder.of(upgradeEntries.toArray(Path[]::new)));
        compiledReferences.putAll(upgrades);
        var applicationReferences = new LinkedHashMap<>(compiledReferences);
        sourceModuleReferences(opts).forEach(applicationReferences::putIfAbsent);
        var systemReferences = systemModuleReferences(opts.system());
        var observable = new LinkedHashMap<String, java.lang.module.ModuleReference>();
        observable.putAll(systemReferences);
        applicationReferences.forEach(observable::putIfAbsent);
        observable.putAll(upgrades);
        if (opts.limitModules() != null) {
            var limitRoots = splitModuleNames(opts.limitModules());
            limitRoots.addAll(selectedModules(opts));
            limitRoots.addAll(splitModuleNames(opts.addModules()));
            var limited = requirementClosure(limitRoots, observable);
            observable.keySet().retainAll(limited);
        }
        var finder = new MapModuleFinder(observable);
        var applicationNames = new LinkedHashSet<>(applicationReferences.keySet());
        applicationNames.retainAll(observable.keySet());
        var systemNames = new LinkedHashSet<>(systemReferences.keySet());
        systemNames.retainAll(observable.keySet());
        var roots = moduleRoots(opts, applicationNames, systemNames);
        if (roots.isEmpty())
            return new ModuleResolution(List.of(),
                                        Map.of(),
                                        Map.of());

        java.lang.module.Configuration configuration;
        try {
            configuration = java.lang.module.Configuration.empty().resolve(finder, java.lang.module.ModuleFinder.of(), roots);
            while (true) {
                var resolvedNames =
                        configuration.modules().stream()
                                .map(java.lang.module.ResolvedModule::name)
                                .collect(Collectors.toUnmodifiableSet());
                var staticRoots =
                        configuration.modules().stream()
                                .flatMap(module ->
                                        module.reference().descriptor().requires().stream())
                                .filter(requirement -> requirement.modifiers().contains(java.lang.module.ModuleDescriptor.Requires.Modifier.STATIC))
                                .map(java.lang.module.ModuleDescriptor.Requires::name)
                                .filter(observable::containsKey)
                                .filter(name -> !resolvedNames.contains(name))
                                .toList();
                if (staticRoots.isEmpty())
                    break;
                roots.addAll(staticRoots);
                configuration = java.lang.module.Configuration.empty().resolve(finder, java.lang.module.ModuleFinder.of(), roots);
            }
        } catch (java.lang.module.FindException | java.lang.module.ResolutionException e) {
            throw new ToolException(1, "Cannot resolve modules: " + e.getMessage());
        }

        var readable = readableModules(opts, configuration);
        var patches = ModulePatches.parse(opts.patchModules());
        var applications = new ArrayList<ModuleInput>();
        var applicationPackages = new TreeMap<String, Set<String>>();
        var systems = new TreeMap<String, Set<String>>();
        for (var module :
                configuration.modules().stream()
                        .sorted(Comparator.comparing(java.lang.module.ResolvedModule::name))
                        .toList()) {
            if (!readable.contains(module.name()))
                continue;
            var visiblePackages = visiblePackages(opts, configuration, module.reference().descriptor());
            var patchRoots = patches.getOrDefault(module.name(), List.of());
            for (int i = 0; i < patchRoots.size(); i++) {
                applications.add(new ModuleInput(module.name(), patchRoots.get(i), visiblePackages,
                        module.reference().descriptor(), patchRoots.subList(0, i), true, selectedModules(opts).contains(module.name())));
            }
            if (!patchRoots.isEmpty()) applicationPackages.put(module.name(), visiblePackages);
            if (applicationReferences.containsKey(module.name())) {
                applicationPackages.put(module.name(), visiblePackages);
                var location =
                        module.reference()
                                .location()
                                .orElse(null);
                if (compiledReferences.containsKey(module.name())
                        && location != null
                        && "file".equals(location.getScheme())) {
                    applications.add(new ModuleInput(module.name(),
                                                     Path.of(location),
                                                     visiblePackages,
                                                     module.reference().descriptor(), patchRoots, false, selectedModules(opts).contains(module.name())));
                }
            }
            if (systemReferences.get(module.name()) == module.reference()) {
                systems.put(module.name(), visiblePackages);
            }
        }
        return new ModuleResolution(List.copyOf(applications),
                                    Map.copyOf(applicationPackages),
                                    Map.copyOf(systems));
    }

    private static Map<String, java.lang.module.ModuleReference> referencesByName(java.lang.module.ModuleFinder finder) {
        return finder.findAll().stream()
                .collect(Collectors.toMap(reference -> reference.descriptor().name(),
                                          reference -> descriptorReference(reference.descriptor(), reference.location().orElse(null)),
                                          (first, _) -> first,
                                          LinkedHashMap::new));
    }

    private static Map<String, java.lang.module.ModuleReference> sourceModuleReferences(Options opts) throws IOException, ToolException {
        if (opts.moduleSourcePaths().isEmpty())
            return Map.of();
        var sources = ModuleSourcePaths.parse(opts.moduleSourcePaths());
        var pending = new ArrayDeque<String>();
        pending.addAll(selectedModules(opts));
        for (var name : splitModuleNames(opts.addModules())) {
            if (!name.startsWith("ALL-"))
                pending.add(name);
        }
        sources.modulePaths()
                .keySet()
                .forEach(pending::add);

        var references = new LinkedHashMap<String, java.lang.module.ModuleReference>();
        while (!pending.isEmpty()) {
            var expectedName = pending.removeFirst();
            if (references.containsKey(expectedName))
                continue;
            var roots = sources.forModule(expectedName);
            var moduleInfo =
                    roots.stream()
                            .map(root -> root.resolve("module-info.java"))
                            .filter(Files::isRegularFile)
                            .findFirst()
                            .orElse(null);
            if (moduleInfo == null)
                continue;
            var descriptor = parseSourceModuleDescriptor(moduleInfo);
            if (!descriptor.name().equals(expectedName)) {
                throw new ToolException(1, "Module source path for " + expectedName + " declares " + descriptor.name());
            }
            references.put(expectedName,
                    new java.lang.module.ModuleReference(descriptor, URI.create("source:///" + expectedName)) {
                        @Override
                        public java.lang.module.ModuleReader open() {
                            throw new UnsupportedOperationException();
                        }
                    });
            descriptor.requires().stream()
                    .map(java.lang.module.ModuleDescriptor.Requires::name)
                    .forEach(pending::addLast);
        }
        return Map.copyOf(references);
    }

    private static java.lang.module.ModuleDescriptor parseSourceModuleDescriptor(Path moduleInfo) throws IOException, ToolException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task =
                    (JavacTask) compiler.getTask(null,
                                                 fileManager,
                                                 null,
                                                 List.of("-proc:none"),
                                                 null,
                                                 fileManager.getJavaFileObjects(moduleInfo));
            var units = task.parse().iterator();
            var module = units.hasNext()
                    ? units.next().getModule()
                    : null;
            if (module == null) {
                throw new ToolException(1, "No module declaration in " + moduleInfo);
            }
            var builder = module.getModuleType() == com.sun.source.tree.ModuleTree.ModuleKind.OPEN
                    ? java.lang.module.ModuleDescriptor.newOpenModule(module.getName().toString())
                    : java.lang.module.ModuleDescriptor.newModule(module.getName().toString());
            for (var directive : module.getDirectives()) {
                switch (directive.getKind()) {
                    case REQUIRES -> {
                        var requires = (com.sun.source.tree.RequiresTree) directive;
                        var modifiers = EnumSet.noneOf(java.lang.module.ModuleDescriptor.Requires.Modifier.class);
                        if (requires.isStatic())
                            modifiers.add(java.lang.module.ModuleDescriptor.Requires.Modifier.STATIC);
                        if (requires.isTransitive())
                            modifiers.add(java.lang.module.ModuleDescriptor.Requires.Modifier.TRANSITIVE);
                        builder.requires(modifiers, requires.getModuleName().toString());
                    }
                    case EXPORTS -> {
                        var exports = (com.sun.source.tree.ExportsTree) directive;
                        var targets = exports.getModuleNames();
                        if (targets == null || targets.isEmpty()) {
                            builder.exports(exports.getPackageName().toString());
                        } else {
                            builder.exports(exports.getPackageName().toString(),
                                            targets.stream()
                                                    .map(Object::toString)
                                                    .collect(Collectors.toUnmodifiableSet()));
                        }
                    }
                    case OPENS -> {
                        var opens = (com.sun.source.tree.OpensTree) directive;
                        var targets = opens.getModuleNames();
                        if (targets == null || targets.isEmpty()) {
                            builder.opens(opens.getPackageName().toString());
                        } else {
                            builder.opens(opens.getPackageName().toString(),
                                          targets.stream()
                                                  .map(Object::toString)
                                                  .collect(Collectors.toUnmodifiableSet()));
                        }
                    }
                    case USES -> builder.uses(((com.sun.source.tree.UsesTree) directive).getServiceName().toString());
                    case PROVIDES -> {
                        var provides = (com.sun.source.tree.ProvidesTree) directive;
                        builder.provides(provides.getServiceName().toString(),
                                         provides.getImplementationNames().stream()
                                                 .map(Object::toString)
                                                 .toList());
                    }
                    default -> throw new ToolException(1, "Unsupported module directive in " + moduleInfo + ": " + directive);
                }
            }
            return builder.build();
        }
    }

    private static Map<String, java.lang.module.ModuleReference> systemModuleReferences(String system) throws IOException {
        if ("none".equals(system))
            return new LinkedHashMap<>();
        if (system == null)
            return referencesByName(java.lang.module.ModuleFinder.ofSystem());
        var javaHome =
                Path.of(system)
                        .toAbsolutePath()
                        .normalize();
        try {
            return new LinkedHashMap<>(SYSTEM_MODULES.computeIfAbsent(javaHome,
                            home -> {
                                try {
                                    return Map.copyOf(loadSystemModuleReferences(home));
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Map<String, java.lang.module.ModuleReference> loadSystemModuleReferences(Path javaHome) throws IOException {
        var jmods = javaHome.resolve("jmods");
        if (Files.isDirectory(jmods)) {
            var references = new LinkedHashMap<String, java.lang.module.ModuleReference>();
            try (var files = Files.list(jmods)) {
                for (var jmod :
                        files.filter(path ->
                                        path.getFileName()
                                                .toString()
                                                .endsWith(".jmod"))
                                .sorted()
                                .toList()) {
                    try (var archive = new ZipFile(jmod.toFile())) {
                        var moduleInfo = archive.getEntry("classes/module-info.class");
                        if (moduleInfo == null)
                            continue;
                        try (var input = archive.getInputStream(moduleInfo)) {
                            var descriptor = java.lang.module.ModuleDescriptor.read(input);
                            references.put(descriptor.name(), descriptorReference(descriptor));
                        }
                    }
                }
            }
            return references;
        }
        if (!Files.isRegularFile(javaHome.resolve("lib/modules"))) {
            return new LinkedHashMap<>();
        }
        var references = new LinkedHashMap<String, java.lang.module.ModuleReference>();
        try (var fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", javaHome.toString()))) {
            try (var modules = Files.list(fileSystem.getPath("/modules"))) {
                for (var module : modules.toList()) {
                    var moduleInfo = module.resolve("module-info.class");
                    if (!Files.isRegularFile(moduleInfo))
                        continue;
                    try (var input = Files.newInputStream(moduleInfo)) {
                        var descriptor = java.lang.module.ModuleDescriptor.read(input);
                        references.put(descriptor.name(), descriptorReference(descriptor));
                    }
                }
            }
        }
        return references;
    }

    private static java.lang.module.ModuleReference descriptorReference(java.lang.module.ModuleDescriptor descriptor) {
        return descriptorReference(descriptor, URI.create("jrt:/" + descriptor.name()));
    }

    /**
     * Resolves compilation graphs from descriptors without enforcing runtime module hashes.
     * ClassFiles and sources are read separately from the resolved locations.
     */
    private static java.lang.module.ModuleReference descriptorReference(java.lang.module.ModuleDescriptor descriptor, URI location) {
        return new java.lang.module.ModuleReference(descriptor, location) {
            @Override
            public java.lang.module.ModuleReader open() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Set<String> requirementClosure(Set<String> roots, Map<String, java.lang.module.ModuleReference> modules) {
        var closure = new LinkedHashSet<String>();
        var pending = new ArrayDeque<String>(roots);
        while (!pending.isEmpty()) {
            var name = pending.removeFirst();
            if (!closure.add(name))
                continue;
            var reference = modules.get(name);
            if (reference == null)
                continue;
            reference.descriptor().requires().stream()
                    .map(java.lang.module.ModuleDescriptor.Requires::name)
                    .forEach(pending::addLast);
        }
        return closure;
    }

    private static LinkedHashSet<String> moduleRoots(Options opts,
            Set<String> applicationModules,
            Set<String> systemModules) {
        var roots = new LinkedHashSet<>(selectedModules(opts));
        if (roots.isEmpty() && systemModules.contains("java.se")) {
            roots.add("java.se");
        } else if (roots.isEmpty() && systemModules.contains("java.base")) {
            roots.add("java.base");
        }
        var added = splitModuleNames(opts.addModules());
        if (added.remove("ALL-MODULE-PATH"))
            roots.addAll(applicationModules);
        if (added.remove("ALL-SYSTEM"))
            roots.addAll(systemModules);
        roots.addAll(added);
        return roots;
    }

    static LinkedHashSet<String> selectedModules(Options opts) {
        return splitModuleNames(opts.module());
    }

    private static LinkedHashSet<String> splitModuleNames(String value) {
        if (value == null || value.isBlank())
            return new LinkedHashSet<>();
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> readableModules(Options opts, java.lang.module.Configuration configuration) {
        var selected = selectedModules(opts);
        if (selected.isEmpty()) {
            return configuration.modules().stream()
                    .map(java.lang.module.ResolvedModule::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        var readable = new LinkedHashSet<String>();
        for (var name : selected) {
            configuration.findModule(name)
            .ifPresent(subject -> {
                subject.reads().stream()
                        .map(java.lang.module.ResolvedModule::name)
                        .forEach(readable::add);
                readable.add(name);
            });
        }
        for (var value : opts.addReads()) {
            var equals = value.indexOf('=');
            if (equals <= 0 || !selected.contains(value.substring(0, equals)))
                continue;
            for (var target : value.substring(equals + 1).split(",")) {
                if (configuration.findModule(target).isPresent())
                    readable.add(target);
            }
        }
        return readable;
    }

    static boolean readsUnnamedModule(Options opts) {
        var selected = selectedModules(opts);
        if (selected.isEmpty())
            return true;
        return opts.addReads().stream()
                .anyMatch(value -> {
                    var equals = value.indexOf('=');
                    if (equals <= 0 || !selected.contains(value.substring(0, equals)))
                        return false;
                    return Arrays.stream(value.substring(equals + 1).split(","))
                            .map(String::strip)
                            .anyMatch("ALL-UNNAMED"::equals);
                });
    }

    private static Set<String> visiblePackages(Options opts,
            java.lang.module.Configuration configuration,
            java.lang.module.ModuleDescriptor descriptor) {
        var selected = selectedModules(opts);
        if (selected.contains(descriptor.name()))
            return descriptor.packages();
        var packages = new HashSet<String>();
        if (descriptor.isAutomatic()) {
            packages.addAll(descriptor.packages());
        } else {
            descriptor.exports().stream()
                    .filter(exported ->
                            selected.isEmpty()
                            ? !exported.isQualified()
                            : selected.stream()
                                    .anyMatch(subject ->
                                            reads(configuration, opts, subject, descriptor.name())
                                                    && (!exported.isQualified() || exported.targets().contains(subject))))
                    .map(java.lang.module.ModuleDescriptor.Exports::source)
                    .forEach(packages::add);
        }
        packages.addAll(addedExportPackages(opts, configuration, descriptor.name()));
        return Set.copyOf(packages);
    }

    private static Set<String> addedExportPackages(Options opts, java.lang.module.Configuration configuration, String moduleName) {
        var selected = selectedModules(opts);
        var packages = new HashSet<String>();
        for (var value : opts.addExports()) {
            var slash = value.indexOf('/');
            var equals = value.indexOf('=', slash + 1);
            if (slash <= 0
                    || equals <= slash + 1
                    || !value.substring(0, slash).equals(moduleName))
                continue;
            var targets = splitModuleNames(value.substring(equals + 1));
            boolean visible = selected.isEmpty()
                    ? targets.contains("ALL-UNNAMED")
                    : selected.stream()
                                    .anyMatch(subject ->
                                            targets.contains(subject) && reads(configuration, opts, subject, moduleName));
            if (visible)
                packages.add(value.substring(slash + 1, equals));
        }
        return packages;
    }

    private static boolean reads(java.lang.module.Configuration configuration,
                                 Options opts,
                                 String source,
                                 String target) {
        if (source.equals(target))
            return true;
        var resolved = configuration.findModule(source);
        if (resolved.isPresent()
                && resolved.orElseThrow().reads().stream()
                        .anyMatch(module -> module.name().equals(target)))
            return true;
        return opts.addReads().stream()
                .anyMatch(value -> {
                    var equals = value.indexOf('=');
                    return equals > 0
                            && value.substring(0, equals).equals(source)
                            && splitModuleNames(value.substring(equals + 1)).contains(target);
                });
    }

    private static final class MapModuleFinder implements java.lang.module.ModuleFinder {
        private final Map<String, java.lang.module.ModuleReference> modules;

        MapModuleFinder(Map<String, java.lang.module.ModuleReference> modules) {
            this.modules = Map.copyOf(modules);
        }

        @Override
        public Optional<java.lang.module.ModuleReference> find(String name) {
            return Optional.ofNullable(modules.get(name));
        }

        @Override
        public Set<java.lang.module.ModuleReference> findAll() {
            return Set.copyOf(modules.values());
        }
    }

    static boolean isVisibleModuleClass(String resourceName, Set<String> visiblePackages) {
        var className = listedClassName(resourceName);
        if (className == null)
            return false;
        if (className.equals("module-info"))
            return true;
        var packageEnd = resourceName.lastIndexOf('/');
        return packageEnd >= 0
                && visiblePackages.contains(resourceName.substring(0, packageEnd).replace('/', '.'));
    }

    static boolean isExportedClassResource(String resourceName, Set<String> exportedPackages) {
        if (resourceName.equals("module-info.class"))
            return true;
        var packageEnd = resourceName.lastIndexOf('/');
        return packageEnd >= 0
                && exportedPackages.contains(resourceName.substring(0, packageEnd).replace('/', '.'));
    }

    List<Path> moduleSourceRoots() throws IOException, ToolException {
        return moduleSourceInputs().stream()
                .map(ModuleSourceInput::location)
                .toList();
    }

    List<ModuleSourceInput> moduleSourceInputs() throws IOException, ToolException {
        if (resolvedSourceInputs == null)
            resolvedSourceInputs = computeModuleSourceInputs();
        return resolvedSourceInputs;
    }

    private List<ModuleSourceInput> computeModuleSourceInputs() throws IOException, ToolException {
        var configured = ModuleSourcePaths.parse(options.moduleSourcePaths());
        var inputs = new LinkedHashMap<Path, ModuleSourceInput>();
        var modules = modules();
        var packages = new TreeMap<>(modules.systemPackages());
        packages.putAll(modules.applicationPackages());
        var patches = patches();
        for (var module : packages.entrySet()) {
            var visible = selectedModules(options).contains(module.getKey()) ? null : module.getValue();
            var roots = patches.getOrDefault(module.getKey(), List.of());
            for (int i = 0; i < roots.size(); i++) {
                var root = roots.get(i);
                inputs.putIfAbsent(root, new ModuleSourceInput(root, visible, roots.subList(0, i), true));
            }
        }
        for (var module : modules.applicationPackages().entrySet()) {
            var visible = selectedModules(options).contains(module.getKey()) ? null : module.getValue();
            for (var root : configured.forModule(module.getKey())) {
                inputs.putIfAbsent(root, new ModuleSourceInput(root, visible, patches.getOrDefault(module.getKey(), List.of()), false));
            }
        }
        return List.copyOf(inputs.values());
    }

    boolean includesSystemResource(String module, String resource) throws IOException, ToolException {
        return !ModulePatches.contains(patches().getOrDefault(module, List.of()), resource);
    }

    boolean includesSource(Path root, Path source) throws IOException, ToolException {
        var normalized = root.toAbsolutePath().normalize();
        var resource = root.relativize(source).toString().replace(File.separatorChar, '/');
        for (var input : moduleSourceInputs()) {
            if (input.location().toAbsolutePath().normalize().equals(normalized)) {
                return input.includesResource(resource);
            }
        }
        var roots = moduleSourceInputs().stream().filter(ModuleSourceInput::patch).map(ModuleSourceInput::location).toList();
        return !ModulePatches.contains(roots, resource);
    }

    SourceLookup sources() throws IOException, ToolException {
        var patches = moduleSourceInputs().stream().filter(ModuleSourceInput::patch)
                .map(ModuleSourceInput::location).collect(Collectors.toUnmodifiableSet());
        return SourceLookup.open(sourceRoots(), patches, options.system());
    }

    List<Path> sourceRoots() throws IOException, ToolException {
        var roots = new LinkedHashSet<Path>();
        roots.addAll(moduleSourceInputs().stream().filter(ModuleSourceInput::patch).map(ModuleSourceInput::location).toList());
        roots.addAll(parsePathList(options.sourcePath()));
        roots.addAll(moduleSourceRoots());
        return List.copyOf(roots);
    }

    Path resolveSourceFile(String binaryName) throws IOException, ToolException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            for (var entry : sourceRoots()) {
                if (!Files.isDirectory(entry))
                    continue;
                for (var candidate : sourceFileCandidates(binaryName)) {
                    var sourceFile = entry.resolve(candidate);
                    if (Files.isRegularFile(sourceFile) && includesSource(entry, sourceFile) && sourceDeclaresClass(compiler, fileManager, sourceFile, binaryName)) {
                        return sourceFile;
                    }
                }
                try (var files = Files.walk(entry)) {
                    var iterator =
                            files.filter(Files::isRegularFile)
                                    .filter(path -> path.toString().endsWith(JAVA_EXT))
                                    .iterator();
                    while (iterator.hasNext()) {
                        var sourceFile = iterator.next();
                        if (includesSource(entry, sourceFile) && sourceDeclaresClass(compiler, fileManager, sourceFile, binaryName)) {
                            return sourceFile;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean sourceDeclaresClass(JavaCompiler compiler, StandardJavaFileManager fileManager, Path sourceFile, String binaryName) throws IOException {
        var task =
                (JavacTask) compiler.getTask(null,
                                             fileManager,
                                             null,
                                             List.of("-proc:none"),
                                             null,
                                             fileManager.getJavaFileObjects(sourceFile));
        var units = task.parse().iterator();
        if (!units.hasNext())
            return false;
        var finder = new SourceClassFinder(binaryName);
        finder.scan(units.next(), null);
        return finder.found;
    }

    private static List<String> sourceFileCandidates(String binaryName) {
        var candidates = new ArrayList<String>();
        if (!binaryName.contains("."))
            candidates.add(binaryName + JAVA_EXT);
        for (int split = binaryName.lastIndexOf('.');
             split > 0;
             split = binaryName.lastIndexOf('.', split - 1)) {
            var className = binaryName.substring(split + 1);
            int nested = className.indexOf('.');
            if (nested >= 0)
                className = className.substring(0, nested);
            var candidate =
                    binaryName.substring(0, split).replace('.', '/')
                            + "/"
                            + className
                            + JAVA_EXT;
            if (!candidates.contains(candidate))
                candidates.add(candidate);
        }
        return candidates;
    }

    record ResolvedClass(List<ClassModel> classes, String location) {}

    ResolvedClass resolveSystemClass(String binaryName) throws IOException, ToolException {
        var opts = options;
        var system = opts.system();
        if ("none".equals(system))
            return null;
        var systemPackages = modules().systemPackages();
        if (systemPackages.isEmpty())
            return null;
        FileSystem fileSystem;
        boolean close;
        if (system == null) {
            try {
                fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"));
                close = false;
            } catch (FileSystemNotFoundException _) {
                fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of());
                close = true;
            }
        } else {
            var javaHome = Path.of(system);
            if (!Files.isRegularFile(javaHome.resolve("lib/modules")))
                return null;
            fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", system));
            close = true;
        }

        try {
            var modules = fileSystem.getPath("/modules");
            if (!Files.isDirectory(modules))
                return null;
            for (var candidate : classFileCandidates(binaryName)) {
                try (var moduleDirectories = Files.list(modules)) {
                    var iterator = moduleDirectories.iterator();
                    while (iterator.hasNext()) {
                        var module = iterator.next();
                        var visiblePackages = systemPackages.get(module.getFileName().toString());
                        if (visiblePackages == null || !isVisibleModuleClass(candidate, visiblePackages)
                                || !includesSystemResource(module.getFileName().toString(), candidate))
                            continue;
                        var classFile = module.resolve(candidate);
                        if (Files.isRegularFile(classFile)) {
                            return new ResolvedClass(readClassGroup(classFile), unitLocation(classFile));
                        }
                    }
                }
            }
            return null;
        } finally {
            if (close)
                fileSystem.close();
        }
    }

    ResolvedClass resolveModuleClass(String binaryName) throws IOException, ToolException {
        var matches = new LinkedHashMap<String, ResolvedClass>();
        var inputs = moduleInputs().stream().collect(Collectors.groupingBy(ModuleInput::name, LinkedHashMap::new, Collectors.toList()));
        for (var module : inputs.entrySet()) {
            for (var candidate : classFileCandidates(binaryName)) {
                var classes = new LinkedHashMap<String, ClassModel>();
                String location = null;
                for (var input : module.getValue()) {
                    var group = readClassGroup(input.location(), candidate, input.patch());
                    if (group == null)
                        continue;
                    for (var model : group.classes()) {
                        var name = model.thisClass().asInternalName();
                        if (input.visibleResource(name + CLASS_EXT)) {
                            classes.putIfAbsent(name, model);
                            if (name.replace('/', '.').replace('$', '.').equals(binaryName)) {
                                location = group.location();
                            }
                        }
                    }
                }
                if (modules().systemPackages().containsKey(module.getKey())) {
                    var original = readSystemClassGroup(module.getKey(), candidate);
                    if (original != null) {
                        for (var model : original.classes()) {
                            var name = model.thisClass().asInternalName();
                            if (isVisibleModuleClass(name + CLASS_EXT, modules().systemPackages().get(module.getKey()))
                                    && includesSystemResource(module.getKey(), name + CLASS_EXT)) {
                                classes.putIfAbsent(name, model);
                                if (name.replace('/', '.').replace('$', '.').equals(binaryName)) {
                                    location = original.location();
                                }
                            }
                        }
                    }
                }
                if (location != null) {
                    matches.put(module.getKey(), new ResolvedClass(List.copyOf(classes.values()), location));
                    break;
                }
            }
        }
        if (matches.size() > 1) {
            throw new ToolException(1, "Class " + binaryName + " is provided by multiple modules:\n  "
                    + matches.keySet().stream().sorted().collect(Collectors.joining("\n  ")));
        }
        return matches.isEmpty() ? null : matches.values().iterator().next();
    }

    private ResolvedClass readSystemClassGroup(String module, String candidate) throws IOException {
        if (options.system() == null) {
            return readClassGroup(FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules", module), candidate, false);
        }
        if (!Files.isRegularFile(Path.of(options.system()).resolve("lib/modules"))) {
            return null;
        }
        try (var system = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", options.system()))) {
            return readClassGroup(system.getPath("/modules", module), candidate, false);
        }
    }

    /** Reads a top-level type and its nested classes, including patches containing only a nested ClassFile. */
    private static ResolvedClass readClassGroup(Path entry, String candidate, boolean patch) throws IOException {
        var outer = candidate.substring(0, candidate.length() - CLASS_EXT.length());
        int nested = outer.indexOf('$');
        if (nested >= 0) outer = outer.substring(0, nested);
        var outerName = outer;
        var prefix = outer + "$";
        if (Files.isDirectory(entry)) {
            var classFile = entry.resolve(candidate);
            if (Files.isRegularFile(classFile)) {
                return new ResolvedClass(readClassGroup(classFile), unitLocation(classFile));
            }
            var outerFile = entry.resolve(outerName + CLASS_EXT);
            if (Files.isRegularFile(outerFile)) {
                return new ResolvedClass(readClassGroup(outerFile), unitLocation(outerFile));
            }
            var parent = classFile.getParent();
            if (!patch || !Files.isDirectory(parent)) return null;
            try (var files = Files.list(parent)) {
                var first = files.filter(Files::isRegularFile)
                        .filter(file -> {
                            var resource = entry.relativize(file).toString().replace(File.separatorChar, '/');
                            return resource.equals(outerName + CLASS_EXT) || resource.startsWith(prefix) && resource.endsWith(CLASS_EXT);
                        })
                        .sorted()
                        .findFirst();
                if (first.isPresent()) {
                    var selected = Files.isRegularFile(classFile) ? classFile : first.orElseThrow();
                    return new ResolvedClass(readClassGroup(selected), unitLocation(selected));
                }
            }
        } else if (Files.isRegularFile(entry)) {
            try (var jar = new JarFile(entry.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                if (!patch && jar.getJarEntry(candidate) == null && jar.getJarEntry(outerName + CLASS_EXT) == null) return null;
                var entries = jar.versionedStream()
                        .filter(file -> !file.isDirectory())
                        .filter(file -> file.getName().equals(outerName + CLASS_EXT)
                                || file.getName().startsWith(prefix) && file.getName().endsWith(CLASS_EXT))
                        .sorted(Comparator.comparing(JarEntry::getName))
                        .toList();
                if (!entries.isEmpty()) {
                    var classes = new ArrayList<ClassModel>();
                    for (var file : entries) {
                        try (var input = jar.getInputStream(file)) {
                            classes.add(ClassFile.of().parse(input.readAllBytes()));
                        }
                    }
                    var selected = jar.getJarEntry(outerName + CLASS_EXT);
                    if (selected == null) selected = jar.getJarEntry(candidate);
                    if (selected == null) selected = entries.getFirst();
                    return new ResolvedClass(List.copyOf(classes), "jar:" + entry.toAbsolutePath().normalize().toUri() + "!/"
                            + selected.getRealName());
                }
            }
        }
        return null;
    }

    static ResolvedClass resolveClass(String binaryName, List<Path> entries) throws IOException {
        var candidates = classFileCandidates(binaryName);
        for (var entry : entries) {
            if (Files.isDirectory(entry)) {
                for (var candidate : candidates) {
                    var file = entry.resolve(candidate);
                    if (Files.isRegularFile(file)) {
                        return new ResolvedClass(readClassGroup(file), unitLocation(file));
                    }
                }
            } else if (Files.isRegularFile(entry)) {
                try (var jar = new JarFile(entry.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                    for (var candidate : candidates) {
                        if (jar.getJarEntry(candidate) != null) {
                            return readClassGroup(entry, candidate, false);
                        }
                    }
                }
            }
        }
        return null;
    }

    static List<String> classFileCandidates(String binaryName) {
        var candidates = new ArrayList<String>();
        candidates.add(binaryName.replace('.', '/') + CLASS_EXT);
        for (int split = binaryName.lastIndexOf('.');
             split > 0;
             split = binaryName.lastIndexOf('.', split - 1)) {
            candidates.add(binaryName.substring(0, split).replace('.', '/')
                                   + "/"
                                   + binaryName.substring(split + 1).replace('.', '$')
                                   + CLASS_EXT);
        }
        return candidates;
    }

    static List<ClassModel> readClassGroup(Path target) throws IOException {
        var selected = ClassFile.of().parse(Files.readAllBytes(target));
        var internalName = selected.thisClass().asInternalName();
        var outerInternalName = internalName.contains("$")
                ? internalName.substring(0, internalName.indexOf('$'))
                : internalName;
        var outerSimpleName = outerInternalName.substring(outerInternalName.lastIndexOf('/') + 1);
        var classes = new ArrayList<ClassModel>();
        try (var siblings = Files.list(target.getParent())) {
            for (var sibling :
                    siblings.filter(Files::isRegularFile)
                            .filter(path -> {
                                var name = path.getFileName().toString();
                                return name.equals(outerSimpleName + CLASS_EXT)
                                        || name.startsWith(outerSimpleName + "$") && name.endsWith(CLASS_EXT);
                            })
                            .sorted()
                            .toList()) {
                classes.add(ClassFile.of().parse(Files.readAllBytes(sibling)));
            }
        }
        return classes.isEmpty() ? List.of(selected) : classes;
    }

    private static Stream<Path> expandPath(Path path) throws IOException {
        var fileName = path.getFileName();
        if (fileName != null && fileName.toString().equals("*")) {
            var directory = path.getParent() != null ? path.getParent() : Path.of(".");
            if (!Files.isDirectory(directory))
                return Stream.empty();
            try (var listing = Files.list(directory)) {
                return listing
                        .filter(Files::isRegularFile)
                        .filter(candidate ->
                                candidate.getFileName()
                                        .toString()
                                        .toLowerCase(Locale.ROOT)
                                        .endsWith(".jar"))
                        .sorted()
                        .toList()
                        .stream();
            }
        }
        return Stream.of(path);
    }

    static List<Path> parsePathList(String paths) {
        if (paths == null)
            return List.of();
        var result = new ArrayList<Path>();
        for (var part : paths.split(File.pathSeparator)) {
            var path = Path.of(part);
            try {
                for (var expanded : expandPath(path).toList())
                    result.add(expanded);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return result;
    }

    static SourcePaths expandSourcePaths(String sourcePath) {
        if (sourcePath == null)
            return new SourcePaths(List.of(), List.of());
        var paths = new ArrayList<Path>();
        var opened = new ArrayList<FileSystem>();
        for (var part : sourcePath.split(File.pathSeparator)) {
            var p = Path.of(part);
            if ((p.toString().endsWith(".jar")
                            || p.toString().endsWith(".zip"))
                    && Files.isRegularFile(p)) {
                try {
                    var fs = FileSystems.newFileSystem(p);
                    opened.add(fs);
                    paths.add(fs.getPath("/"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                paths.add(p);
            }
        }
        return new SourcePaths(List.copyOf(paths), List.copyOf(opened));
    }

    static String jrtModuleName(String location) {
        if (location == null || !location.startsWith("jrt:/"))
            return null;
        var path = URI.create(location).getPath();
        int end = path.indexOf('/', 1);
        return end > 1 ? path.substring(1, end) : null;
    }

    private static final String CLASS_EXT = ".class";
    private static final String JAVA_EXT = ".java";
    private static final String META_INF = "META-INF/";
}
