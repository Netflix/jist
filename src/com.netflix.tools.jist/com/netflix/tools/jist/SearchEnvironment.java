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

    SearchEnvironment(Options options) {
        this.options = options;
    }

    ModuleResolution modules() throws IOException, ToolException {
        if (resolvedModules == null)
            resolvedModules = computeModuleResolution(options);
        return resolvedModules;
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
                       java.lang.module.ModuleDescriptor descriptor) {}

    record ModuleSourceInput(Path location, Set<String> visiblePackages) {}

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
        var applicationReferences = new LinkedHashMap<>(compiledReferences);
        sourceModuleReferences(opts).forEach(applicationReferences::putIfAbsent);
        var systemReferences = systemModuleReferences(opts.system());
        if (opts.limitModules() != null) {
            var observableSystem = requirementClosure(splitModuleNames(opts.limitModules()), systemReferences);
            selectedModules(opts).stream()
                    .filter(systemReferences::containsKey)
                    .forEach(observableSystem::add);
            splitModuleNames(opts.addModules()).stream()
                    .filter(systemReferences::containsKey)
                    .forEach(observableSystem::add);
            systemReferences.keySet().retainAll(observableSystem);
        }

        var observable = new LinkedHashMap<String, java.lang.module.ModuleReference>();
        observable.putAll(systemReferences);
        applicationReferences.forEach(observable::putIfAbsent);
        var finder = new MapModuleFinder(observable);
        var roots = moduleRoots(opts, applicationReferences.keySet(), systemReferences.keySet());
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
                                                     module.reference().descriptor()));
                }
            } else if (systemReferences.containsKey(module.name())) {
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
                                          java.util.function.Function.identity(),
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
        return new java.lang.module.ModuleReference(descriptor, URI.create("jrt:/" + descriptor.name())) {
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
        var opts = options;
        if (opts.moduleSourcePaths().isEmpty())
            return List.of();
        var configured = ModuleSourcePaths.parse(opts.moduleSourcePaths());
        var inputs = new LinkedHashMap<Path, Set<String>>();
        var modules = modules();
        for (var module : modules.applicationPackages().entrySet()) {
            for (var root : configured.forModule(module.getKey())) {
                inputs.put(root,
                           selectedModules(opts).contains(module.getKey())
                                   ? null
                                   : module.getValue());
            }
        }
        return inputs.entrySet().stream()
                .map(entry ->
                        new ModuleSourceInput(entry.getKey(), entry.getValue()))
                .toList();
    }

    List<Path> sourceRoots() throws IOException, ToolException {
        var roots = new LinkedHashSet<Path>();
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
                    if (Files.isRegularFile(sourceFile) && sourceDeclaresClass(compiler, fileManager, sourceFile, binaryName)) {
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
                        if (sourceDeclaresClass(compiler, fileManager, sourceFile, binaryName)) {
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
                        if (visiblePackages == null || !isVisibleModuleClass(candidate, visiblePackages))
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
        var matches = new ArrayList<Map.Entry<String, ResolvedClass>>();
        for (var module : moduleInputs()) {
            var resolved = resolveClass(binaryName, List.of(module.location()));
            if (resolved == null)
                continue;
            var visible =
                    resolved.classes().stream()
                            .anyMatch(model -> {
                                var internalName = model.thisClass().asInternalName();
                                var displayName = internalName.replace('/', '.').replace('$', '.');
                                var packageEnd = internalName.lastIndexOf('/');
                                var packageName = packageEnd < 0
                                ? ""
                                : internalName.substring(0, packageEnd).replace('/', '.');
                                return displayName.equals(binaryName) && module.visiblePackages().contains(packageName);
                            });
            if (visible)
                matches.add(Map.entry(module.name(), resolved));
        }
        if (matches.size() > 1) {
            throw new ToolException(1,
                                    "Class "
                                            + binaryName
                                            + " is provided by multiple modules:\n  "
                                            + matches.stream()
                                                    .map(Map.Entry::getKey)
                                                    .sorted()
                                                    .collect(Collectors.joining("\n  ")));
        }
        return matches.isEmpty() ? null : matches.getFirst().getValue();
    }

    static ResolvedClass resolveClass(String binaryName, List<Path> entries) throws IOException {
        var candidates = classFileCandidates(binaryName);
        for (var entry : entries) {
            if (Files.isDirectory(entry)) {
                for (var candidate : candidates) {
                    var classFile = entry.resolve(candidate);
                    if (Files.isRegularFile(classFile)) {
                        return new ResolvedClass(readClassGroup(classFile), unitLocation(classFile));
                    }
                }
            } else if (Files.isRegularFile(entry)) {
                try (var jar = new JarFile(entry.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                    for (var candidate : candidates) {
                        var selectedEntry = jar.getJarEntry(candidate);
                        if (selectedEntry == null)
                            continue;
                        var outerName = candidate.substring(0, candidate.length() - CLASS_EXT.length());
                        int dollar = outerName.indexOf('$');
                        if (dollar >= 0)
                            outerName = outerName.substring(0, dollar);
                        var resolvedOuterName = outerName;
                        var outerEntry = jar.getJarEntry(resolvedOuterName + CLASS_EXT);
                        var prefix = resolvedOuterName + "$";
                        var names =
                                jar.stream()
                                        .map(JarEntry::getName)
                                        .filter(name ->
                                                name.equals(resolvedOuterName + CLASS_EXT) || name.startsWith(prefix) && name.endsWith(CLASS_EXT))
                                        .sorted()
                                        .toList();
                        var classes = new ArrayList<ClassModel>(names.size());
                        for (var name : names) {
                            try (var input = jar.getInputStream(jar.getJarEntry(name))) {
                                classes.add(ClassFile.of().parse(input.readAllBytes()));
                            }
                        }
                        return new ResolvedClass(classes,
                                                 "jar:"
                                                         + entry.toAbsolutePath()
                                                                 .normalize()
                                                                 .toUri()
                                                         + "!/"
                                                         + (outerEntry != null ? outerEntry : selectedEntry).getRealName());
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
