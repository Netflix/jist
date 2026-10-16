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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.nio.file.LinkOption;
import java.util.Map.Entry;
import javax.lang.model.element.Modifier;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Output.CountingPrintWriter;
import com.netflix.tools.jist.Output.OriginOutput;
import com.netflix.tools.jist.Output.SourceWarningOutput;
import com.netflix.tools.jist.Output.SymbolOutput;
import com.netflix.tools.jist.SearchEnvironment.ModuleInput;
import com.netflix.tools.jist.SourceModel.Attribution;
import com.netflix.tools.jist.SourceModel.SourceSpan;
import com.netflix.tools.jist.SourceModel.SourceStructure;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;

import static com.netflix.tools.jist.ClassFileRenderer.appendDeclarationAnnotations;
import static com.netflix.tools.jist.ClassFileRenderer.compiledModuleName;
import static com.netflix.tools.jist.ClassFileRenderer.javaType;
import static com.netflix.tools.jist.ClassFileRenderer.listedClassFlags;
import static com.netflix.tools.jist.ClassFileRenderer.metadataKind;
import static com.netflix.tools.jist.ClassFileRenderer.renderAutomaticModuleDeclaration;
import static com.netflix.tools.jist.ClassFileRenderer.renderFieldDeclaration;
import static com.netflix.tools.jist.ClassFileRenderer.renderMethodDeclaration;
import static com.netflix.tools.jist.ClassFileRenderer.renderModuleDeclaration;
import static com.netflix.tools.jist.ClassFileRenderer.renderTypeDeclaration;
import static com.netflix.tools.jist.SearchEnvironment.classFileCandidates;
import static com.netflix.tools.jist.SearchEnvironment.isExportedClassResource;
import static com.netflix.tools.jist.SearchEnvironment.isVisibleModuleClass;
import static com.netflix.tools.jist.SearchEnvironment.listedClassName;
import static com.netflix.tools.jist.SearchEnvironment.parsePathList;
import static com.netflix.tools.jist.SearchEnvironment.readClassGroup;
import static com.netflix.tools.jist.SearchEnvironment.readsUnnamedModule;
import static com.netflix.tools.jist.SearchEnvironment.resolveClass;
import static com.netflix.tools.jist.SearchEnvironment.unitLocation;

/** Finds declarations and renders source context for them. */
final class DeclarationSearch {
    private final SearchEnvironment environment;
    private final RelationshipSearch relationships;
    private final Set<String> emittedRelationships = new HashSet<>();

    DeclarationSearch(SearchEnvironment environment) {
        this(environment, null);
    }

    DeclarationSearch(SearchEnvironment environment, RelationshipSearch relationships) {
        this.environment = environment;
        this.relationships = relationships;
    }

    void listClasses(Options opts, PrintWriter out) throws IOException, ToolException {
        listClasses(opts, out,
                opts.visibility() != null ? opts.visibility() : Visibility.LIBRARY);
    }

    private void listClasses(Options opts, PrintWriter out, Visibility visibility) throws IOException, ToolException {
        var emittedPackages = new HashSet<String>();
        if (readsUnnamedModule(opts)) {
            listClassPathEntries(opts, out, parsePathList(opts.classPath()), visibility, emittedPackages);
        }
        listModulePathEntries(opts, out, visibility, emittedPackages);
        listSystemSymbols(opts, out, visibility, emittedPackages);
        listSourceSymbols(opts, out, emittedPackages);
        out.flush();
    }

    @FunctionalInterface
    private interface PathConsumer {
        void accept(Path path) throws IOException, ToolException;
    }

    private static void walkFilesInDeclarationOrder(Path directory, PathConsumer consumer) throws IOException, ToolException {
        List<Path> entries;
        try (var children = Files.list(directory)) {
            entries = children.sorted(Comparator.comparingInt(DeclarationSearch::declarationPathOrder).thenComparing(path -> path.getFileName().toString())).toList();
        }
        for (var entry : entries) {
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                walkFilesInDeclarationOrder(entry, consumer);
            } else if (Files.isRegularFile(entry)) {
                consumer.accept(entry);
            }
        }
    }

    private static int declarationPathOrder(Path path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return 3;
        }
        var name = path.getFileName().toString();
        if (name.equals("module-info.class") || name.equals("module-info.java")) {
            return 0;
        }
        if (name.equals("package-info.class") || name.equals("package-info.java")) {
            return 1;
        }
        return 2;
    }

    void listSourceContexts(Options opts, PrintWriter out) throws IOException, ToolException {
        var visibility = opts.visibility() != null ? opts.visibility() : Visibility.LIBRARY;
        var emittedSources = new HashSet<String>();
        var emittedPackages = new HashSet<String>();
        var parsedSources = new HashMap<String, EnumeratedSource>();
        try (var sources = SourceLookup.open(opts.sourcePath(), environment.moduleSourceRoots(), opts.system())) {
            if (readsUnnamedModule(opts)) {
                for (var entry : parsePathList(opts.classPath())) {
                    listSourceContextEntry(opts, out, entry, null, null, visibility,
                            sources, emittedSources, emittedPackages, parsedSources);
                }
            }
            for (var module : environment.moduleInputs()) {
                if (relationships == null
                        && module.descriptor().isAutomatic()
                        && (opts.kinds().isEmpty() || opts.listsModules())
                        && matchesListedPrefix(opts, module.name())) {
                    if (!printListSymbol(out, SymbolKind.MODULE, module.name(), unitLocation(module.location()))) {
                        out.println(module.name() + ": " + renderAutomaticModuleDeclaration(module.descriptor()));
                    }
                }
                listSourceContextEntry(
                        opts,
                        out,
                        module.location(),
                        module.name(),
                        module.visiblePackages(),
                        visibility,
                        sources,
                        emittedSources,
                        emittedPackages,
                        parsedSources);
                if (opts.kinds().isEmpty() || opts.listsPackages()) {
                    for (var packageName : module.visiblePackages().stream()
                            .sorted()
                            .toList()) {
                        if (emittedPackages.add(packageName) && matchesListedPrefix(opts, packageName)) {
                            emitPackageSourceContext(opts, out, packageName, null, unitLocation(module.location()),
                                    sources, emittedSources);
                        }
                    }
                }
            }
            listSystemSourceContexts(opts, out, visibility, sources, emittedSources, emittedPackages,
                    parsedSources);
            listUncompiledSourceContexts(opts, out, sources, emittedSources, emittedPackages);
        }
        out.flush();
    }

    void searchSourceContexts(Options opts, PrintWriter out) throws IOException, ToolException {
        var symbols = new CountingPrintWriter(out);
        listSourceContexts(opts, symbols);
        if (symbols.count() == 0) {
            throw new ToolException(1,
                    simpleNameTarget(opts.target()) ? "Symbol not found: " + opts.target() : "Class not found: " + opts.target() + "; no symbol prefix matches");
        }
    }

    private void listSourceContextEntry(
            Options opts,
            PrintWriter out,
            Path entry,
            String moduleName,
            Set<String> visiblePackages,
            Visibility visibility,
            SourceLookup sources,
            Set<String> emittedSources,
            Set<String> emittedPackages,
            Map<String, EnumeratedSource> parsedSources)
            throws IOException, ToolException {
        if (Files.isDirectory(entry)) {
            walkFilesInDeclarationOrder(entry,
                    classFile -> {
                        if (!classFile.toString().endsWith(CLASS_EXT)) {
                            return;
                        }
                        var resourceName = entry.relativize(classFile)
                                                .toString()
                                                .replace(File.separatorChar, '/');
                        if (!visibleClassResource(resourceName, moduleName, visiblePackages, opts)) {
                            return;
                        }
                        emitClassSourceContext(
                                opts,
                                out,
                                ClassFile.of().parse(Files.readAllBytes(classFile)),
                                unitLocation(classFile),
                                visibility,
                                sources,
                                emittedSources,
                                emittedPackages,
                                parsedSources);
                    });
        } else if (Files.isRegularFile(entry)) {
            try (var jar = new JarFile(entry.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                var iterator = jar.versionedStream()
                                  .filter(candidate -> !candidate.isDirectory())
                                  .filter(candidate -> visibleClassResource(candidate.getName(), moduleName, visiblePackages, opts))
                                  .iterator();
                while (iterator.hasNext()) {
                    var jarEntry = iterator.next();
                    try (var input = jar.getInputStream(jarEntry)) {
                        emitClassSourceContext(
                                opts,
                                out,
                                ClassFile.of().parse(input.readAllBytes()),
                                "jar:" + entry.toAbsolutePath()
                                              .normalize()
                                              .toUri()
                                        + "!/" + jarEntry.getRealName(),
                                visibility,
                                sources,
                                emittedSources,
                                emittedPackages,
                                parsedSources);
                    }
                }
            }
        }
    }

    private static boolean visibleClassResource(String resourceName, String moduleName, Set<String> visiblePackages,
            Options opts) {
        var className = listedClassName(resourceName);
        if (className == null) {
            return false;
        }
        var symbol = className.equals("module-info")
                ? moduleName
                : className.endsWith(".package-info") ? packageName(className) : className;
        if (symbol == null
                || opts.target() != null && !matchesListedPrefix(opts, symbol) && !(simpleNameTarget(opts.target()) && (opts.listsMethods() || opts.listsFields()))) {
            return false;
        }
        return visiblePackages == null || isVisibleModuleClass(resourceName, visiblePackages);
    }

    private void listSystemSourceContexts(
            Options opts,
            PrintWriter out,
            Visibility visibility,
            SourceLookup sources,
            Set<String> emittedSources,
            Set<String> emittedPackages,
            Map<String, EnumeratedSource> parsedSources)
            throws IOException, ToolException {
        var system = opts.system();
        if ("none".equals(system)) {
            return;
        }
        var systemPackages = environment.modules().systemPackages();
        if (systemPackages.isEmpty()) {
            return;
        }
        if (system != null) {
            var javaHome = Path.of(system);
            if (!Files.isRegularFile(javaHome.resolve("lib/modules"))) {
                return;
            }
        }
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
            fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", system));
            close = true;
        }
        try {
            var modules = fileSystem.getPath("/modules");
            if (!Files.isDirectory(modules)) {
                return;
            }
            try (var moduleDirectories = Files.list(modules)) {
                for (var module : moduleDirectories.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                    var visiblePackages = systemPackages.get(module.getFileName()
                            .toString());
                    if (visiblePackages == null) {
                        continue;
                    }
                    walkFilesInDeclarationOrder(module, classFile -> {
                        if (!visibleClassResource(
                                module.relativize(classFile).toString(),
                                module.getFileName().toString(),
                                visiblePackages,
                                opts)) {
                            return;
                        }
                        emitClassSourceContext(
                                opts,
                                out,
                                ClassFile.of().parse(Files.readAllBytes(classFile)),
                                unitLocation(classFile),
                                visibility,
                                sources,
                                emittedSources,
                                emittedPackages,
                                parsedSources);
                    });
                    if (opts.kinds().isEmpty() || opts.listsPackages()) {
                        for (var packageName : visiblePackages.stream()
                                .sorted()
                                .toList()) {
                            if (emittedPackages.add(packageName) && matchesListedPrefix(opts, packageName)) {
                                emitPackageSourceContext(opts, out, packageName, null, unitLocation(module.resolve("module-info.class")),
                                        sources, emittedSources);
                            }
                        }
                    }
                }
            }
        } finally {
            if (close) {
                fileSystem.close();
            }
        }
    }

    private void emitClassSourceContext(
            Options opts,
            PrintWriter out,
            ClassModel model,
            String classLocation,
            Visibility visibility,
            SourceLookup sources,
            Set<String> emittedSources,
            Set<String> emittedPackages,
            Map<String, EnumeratedSource> parsedSources)
            throws IOException, ToolException {
        var metadataKind = metadataKind(model, UsageSearch.displayClassName(model));
        if (relationships != null && (metadataKind != null || sources.hasOrdinarySource(model))) {
            return;
        }
        if (metadataKind == SymbolKind.MODULE) {
            if (opts.kinds().isEmpty() || opts.listsModules()) {
                emitModuleSourceContext(opts, out, model, classLocation, sources, emittedSources);
            }
            return;
        }
        var packageName = model.thisClass()
                               .asSymbol()
                               .packageName();
        if (!packageName.isEmpty()
                && (opts.kinds().isEmpty() || opts.listsPackages())
                && emittedPackages.add(packageName)
                && matchesListedPrefix(opts, packageName)) {
            emitPackageSourceContext(
                    opts,
                    out,
                    packageName,
                    metadataKind == SymbolKind.PACKAGE ? model : null,
                    classLocation,
                    sources,
                    emittedSources);
        }
        if (metadataKind == SymbolKind.PACKAGE) {
            return;
        }
        try {
            listedClassFlags(model);
        } catch (IllegalArgumentException _) {
            return;
        }
        var className = UsageSearch.displayClassName(model);
        if (relationships != null && relationshipMatches(contextMatches(List.of(model), Map.of(), opts, visibility),
                false)
                .isEmpty()) {
            return;
        }
        if (simpleNameTarget(opts.target()) && contextMatches(List.of(model), Map.of(), opts, visibility).isEmpty()) {
            return;
        }
        var unitName = ClassFileRenderer.compilationUnitName(model, className);
        var sourceFileName = model.findAttribute(Attributes.sourceFile())
                .map(attribute -> attribute.sourceFile().stringValue())
                .orElse(unitName.substring(unitName.lastIndexOf('.') + 1) + JAVA_EXT);
        var internalName = model.thisClass().asInternalName();
        int slash = internalName.lastIndexOf('/');
        var relativePath = (slash < 0 ? "" : internalName.substring(0, slash + 1)) + sourceFileName;
        var source = opts.source() == SourceScope.NONE ? null : sources.find(relativePath, classLocation, internalName + CLASS_EXT);
        final SymbolSource symbolSource;
        final List<ContextMatch> matches;
        if (source != null
                && (source.javaSource() || opts.source() == SourceScope.BODY || opts.source() == SourceScope.UNIT)) {
            if (opts.source().conciseDeclaration()) {
                emittedSources.add(source.origin());
            } else if (!emittedSources.add(source.origin())) {
                return;
            }
            var parsed = parsedSources.get(source.origin());
            if (parsed == null) {
                var sourceModel = SourceModel.parse(source.bytes());
                parsed = new EnumeratedSource(sourceModel,
                        source.javaSource() ? sourceModel.structure(sourceFileName, visibility) : new SourceStructure("", List.of(), Map.of(),
                                Map.of(), Map.of(), Set.of()));
                parsedSources.put(source.origin(), parsed);
            }
            symbolSource = new SymbolSource(
                    classLocation,
                    source.origin(),
                    unitName,
                    sourceFileName,
                    parsed.model(),
                    parsed.structure().attribution(),
                    List.of(model));
            matches = opts.listOnly()
                            || opts.source() == SourceScope.BODY
                            || opts.source() == SourceScope.UNIT
                            || opts.source().conciseDeclaration()
                    ? contextMatches(
                            List.of(model),
                            parsed.structure().attribution(),
                            opts,
                            visibility)
                    : contextMatches(parsed.structure(), opts);
        } else {
            symbolSource = new SymbolSource(classLocation, null, unitName, sourceFileName, null,
                    Map.of(), List.of(model));
            matches = contextMatches(List.of(model), Map.of(), opts, visibility);
        }
        if (!matches.isEmpty()) {
            printSymbolContext(symbolSource, matches, opts, out);
        }
    }

    private void emitModuleSourceContext(Options opts, PrintWriter out, ClassModel model,
            String classLocation, SourceLookup sources, Set<String> emittedSources)
            throws IOException, ToolException {
        var moduleName = compiledModuleName(model);
        if (moduleName == null || !matchesListedPrefix(opts, moduleName)) {
            return;
        }
        var source = sources.findModuleInfo(moduleName, classLocation);
        if (source != null && source.javaSource()) {
            var sourceModel = SourceModel.parse(source.bytes());
            var structure = sourceModel.structure("module-info.java", Visibility.ALL);
            var symbolSource = new SymbolSource(classLocation, source.origin(), moduleName, "module-info.java", sourceModel,
                    structure.attribution(), List.of(model));
            var matches = contextMatches(structure, opts);
            if (!matches.isEmpty()) {
                emittedSources.add(source.origin());
                printSymbolContext(symbolSource, matches, opts, out);
                return;
            }
        }
        if (!printListSymbol(out, SymbolKind.MODULE, moduleName, classLocation)) {
            warnSourceUnavailable(out, moduleName);
            out.println(moduleName + ": " + renderModuleDeclaration(model));
        }
    }

    private void emitPackageSourceContext(
            Options opts,
            PrintWriter out,
            String packageName,
            ClassModel packageInfo,
            String classLocation,
            SourceLookup sources,
            Set<String> emittedSources)
            throws IOException, ToolException {
        var relativePath = packageName.replace('.', '/') + "/package-info.java";
        var source = sources.find(relativePath, classLocation);
        if (source != null && source.javaSource()) {
            var sourceModel = SourceModel.parse(source.bytes());
            var structure = sourceModel.structure("package-info.java", Visibility.ALL);
            var symbolSource = new SymbolSource(
                    classLocation,
                    source.origin(),
                    packageName,
                    "package-info.java",
                    sourceModel,
                    structure.attribution(),
                    packageInfo != null ? List.of(packageInfo) : List.of());
            var matches = contextMatches(structure, opts);
            if (!matches.isEmpty()) {
                emittedSources.add(source.origin());
                printSymbolContext(symbolSource, matches, opts, out);
                return;
            }
        }
        var declaration = new StringBuilder();
        if (packageInfo != null) {
            appendDeclarationAnnotations(declaration, packageInfo);
        }
        declaration.append("package ")
                   .append(packageName)
                   .append(';');
        if (!printListSymbol(out, SymbolKind.PACKAGE, packageName, classLocation)) {
            warnSourceUnavailable(out, packageName);
            out.println(packageName + ": " + declaration);
        }
    }

    private record EnumeratedSource(SourceModel model, SourceStructure structure) {}

    private void listUncompiledSourceContexts(Options opts, PrintWriter out, SourceLookup sources,
            Set<String> emittedSources, Set<String> emittedPackages)
            throws IOException, ToolException {
        var visibility = opts.visibility() != null ? opts.visibility() : Visibility.ALL;
        var ordinaryRoots = parsePathList(opts.sourcePath()).stream()
                .filter(Files::isDirectory)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
        var modulePackages = new HashMap<Path, Set<String>>();
        for (var input : environment.moduleSourceInputs()) {
            modulePackages.put(input.location()
                                    .toAbsolutePath()
                                    .normalize(),
                    input.visiblePackages());
        }
        for (var root : sources.explicitPaths()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            var normalizedRoot = root.toAbsolutePath().normalize();
            var visiblePackages = ordinaryRoots.contains(normalizedRoot) ? null : modulePackages.get(normalizedRoot);
            walkFilesInDeclarationOrder(
                    root,
                    sourceFile -> {
                        if (!sourceFile.toString().endsWith(JAVA_EXT)) {
                            return;
                        }
                        var origin = unitLocation(sourceFile);
                        if (!emittedSources.add(origin)) {
                            return;
                        }
                        var model = SourceModel.parse(Files.readAllBytes(sourceFile));
                        var fileName = sourceFile.getFileName().toString();
                        var structure = model.structure(fileName, visibility);
                        if (structure.classNames().isEmpty() && structure.packageName().isEmpty()) {
                            return;
                        }
                        if (visiblePackages != null && !visiblePackages.contains(structure.packageName()) && structure.classNames().stream()
                                .noneMatch(name ->
                                        structure.kinds().get(name) == SymbolKind.MODULE || visiblePackages.contains(structure.kinds().get(name) == SymbolKind.PACKAGE ? name : packageName(name)))) {
                            return;
                        }
                        if (opts.kinds().isEmpty() || opts.listsPackages()) {
                            var declaredPackages = new LinkedHashSet<String>();
                            if (!structure.packageName().isEmpty()) {
                                declaredPackages.add(structure.packageName());
                            }
                            structure.classNames().stream()
                                    .filter(name -> structure.kinds().get(name) != SymbolKind.MODULE)
                                    .map(name -> structure.kinds().get(name) == SymbolKind.PACKAGE ? name : packageName(name))
                                    .filter(name -> !name.isEmpty())
                                    .forEach(declaredPackages::add);
                            for (var packageName : declaredPackages) {
                                if (!emittedPackages.add(packageName) || !matchesListedPrefix(opts, packageName)) {
                                    continue;
                                }
                                var packageInfo = root.resolve(packageName.replace('.', File.separatorChar)).resolve("package-info.java");
                                if (!Files.isRegularFile(packageInfo) || sourceFile.equals(packageInfo)) {
                                    if (!sourceFile.equals(packageInfo)) {
                                        out.println(packageName + ": package " + packageName + ";");
                                    }
                                } else {
                                    emittedPackages.remove(packageName);
                                }
                            }
                        }
                        var unitName = sourceUnitName(structure, fileName);
                        if (relationships != null) {
                            relationships.readSourceUnit(unitName);
                        }
                        var matches = contextMatches(structure, opts);
                        if (matches.isEmpty()) {
                            return;
                        }
                        printSymbolContext(
                                new SymbolSource(null, origin, unitName, fileName, model,
                                        structure.attribution(), List.of()),
                                matches,
                                opts,
                                out);
                    });
        }
    }

    private static String packageName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? "" : className.substring(0, separator);
    }

    static Set<SymbolKind> defaultKinds() {
        return EnumSet.of(SymbolKind.MODULE, SymbolKind.PACKAGE, SymbolKind.TYPE, SymbolKind.METHOD, SymbolKind.FIELD);
    }

    private static String compilationUnitName(String className, String sourceFileName) {
        int packageEnd = className.lastIndexOf('.');
        int extension = sourceFileName.lastIndexOf('.');
        var simpleName = extension > 0 ? sourceFileName.substring(0, extension) : sourceFileName;
        return packageEnd < 0 ? simpleName : className.substring(0, packageEnd + 1) + simpleName;
    }

    static String sourceUnitName(SourceStructure structure, String sourceFileName) {
        var name = structure.classNames().getFirst();
        var kind = structure.kinds().get(name);
        return kind == SymbolKind.MODULE || kind == SymbolKind.PACKAGE
                ? name
                : compilationUnitName(name, sourceFileName);
    }

    void searchSymbols(Options opts, SymbolSelection query, PrintWriter out) throws IOException, ToolException {
        var symbols = new CountingPrintWriter(out);
        var listing = opts.kinds().isEmpty() ? opts.withTargetAndKinds(opts.target(), defaultKinds()) : opts;
        listClasses(listing, symbols);
        if (symbols.count() == 0) {
            throw new ToolException(1,
                    simpleNameTarget(opts.target()) ? "Symbol not found: " + query.path() : "Class not found: " + query.path() + "; no symbol prefix matches");
        }
    }

    void searchFileSymbols(Options opts, SymbolSelection query, PrintWriter out) throws IOException, ToolException {
        var path = Path.of(query.path());
        if (!Files.isRegularFile(path)) {
            throw new ToolException(1, "File locator does not exist: " + query.path());
        }
        var selected = opts.withTargetAndKinds(null,
                opts.kinds().isEmpty() ? defaultKinds() : opts.kinds());
        var visibility = opts.visibility() != null ? opts.visibility() : Visibility.ALL;
        if (query.path().endsWith(JAVA_EXT)) {
            var symbols = new CountingPrintWriter(out);
            var symbolOut = symbols;
            var compiler = ToolProvider.getSystemJavaCompiler();
            try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
                var task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("-proc:none"), null,
                        fileManager.getJavaFileObjects(path));
                var units = task.parse().iterator();
                if (units.hasNext()) {
                    var unit = units.next();
                    if (query.member() != null) {
                        var names = sourceClassNames(unit);
                        if (names.isEmpty()) {
                            throw new ToolException(1, "No symbols found in " + path);
                        }
                        selected = selected.withTargetAndKinds(names.getFirst() + "." + query.member(), selected.kinds());
                    }
                    new SourceSymbolScanner(symbolOut, selected, List.of()).scan(unit, null);
                }
            }
            if (symbols.count() == 0) {
                throw new ToolException(1, "Member not found: " + path + "." + query.member());
            }
        } else if (query.path().endsWith(CLASS_EXT)) {
            var models = readClassGroup(path);
            var outer = models.stream()
                    .filter(model -> !model.thisClass()
                                           .asInternalName()
                                           .contains("$"))
                    .findFirst()
                    .orElse(models.getFirst());
            if (query.member() != null) {
                selected = selected.withTargetAndKinds(attributionKey(outer).replace('$', '.') + "." + query.member(), selected.kinds());
            }
            var symbols = new CountingPrintWriter(out);
            var symbolOut = symbols;
            for (var model : models) {
                addListedSymbols(symbolOut, attributionKey(model).replace('$', '.'),
                        model, selected, visibility);
            }
            if (symbols.count() == 0) {
                throw new ToolException(1, "Member not found: " + attributionKey(outer).replace('$', '.') + "." + query.member());
            }
        } else {
            throw new ToolException(1, "Unsupported file locator: " + query.path());
        }
        out.flush();
    }

    private void listSystemSymbols(Options opts, PrintWriter out, Visibility visibility,
            Set<String> emittedPackages)
            throws IOException, ToolException {
        var system = opts.system();
        if ("none".equals(system)) {
            return;
        }
        var systemPackages = environment.modules().systemPackages();
        if (systemPackages.isEmpty()) {
            return;
        }
        if (system != null) {
            var javaHome = Path.of(system);
            if (!Files.isRegularFile(javaHome.resolve("lib/modules"))) {
                return;
            }
        }
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
            fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", system));
            close = true;
        }
        try {
            var modules = fileSystem.getPath("/modules");
            if (!Files.isDirectory(modules)) {
                return;
            }
            try (var moduleDirectories = Files.list(modules)) {
                for (var module : moduleDirectories.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                    var exportedPackages = systemPackages.get(module.getFileName()
                            .toString());
                    if (exportedPackages == null) {
                        continue;
                    }
                    walkFilesInDeclarationOrder(module,
                            file -> {
                                var resourceName = module.relativize(file).toString();
                                if (!isExportedClassResource(resourceName, exportedPackages)) {
                                    return;
                                }
                                addListedSymbols(
                                        out,
                                        resourceName,
                                        opts.requiresClassModel() || !visibility.includesAll()
                                                ? Files.readAllBytes(file)
                                                : null,
                                        opts,
                                        visibility,
                                        emittedPackages);
                            });
                    emitSynthesizedPackages(out, opts, exportedPackages, emittedPackages);
                }
            }
        } finally {
            if (close) {
                fileSystem.close();
            }
        }
    }

    private void listClassPathEntries(Options opts, PrintWriter out, List<Path> entries,
            Visibility visibility, Set<String> emittedPackages)
            throws IOException, ToolException {
        for (var entry : entries) {
            if (Files.isDirectory(entry)) {
                walkFilesInDeclarationOrder(entry,
                        file -> {
                            var resourceName = entry.relativize(file)
                                                    .toString()
                                                    .replace(File.separatorChar, '/');
                            if ("module-info".equals(listedClassName(resourceName))) {
                                return;
                            }
                            addListedSymbols(
                                    out,
                                    resourceName,
                                    opts.requiresClassModel() || !visibility.includesAll()
                                            ? Files.readAllBytes(file)
                                            : null,
                                    opts,
                                    visibility,
                                    emittedPackages);
                        });
            } else if (Files.isRegularFile(entry)) {
                try (var jar = new JarFile(entry.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                    var iterator = jar.versionedStream()
                                      .filter(candidate -> !candidate.isDirectory())
                                      .iterator();
                    while (iterator.hasNext()) {
                        var jarEntry = iterator.next();
                        var className = listedClassName(jarEntry.getName());
                        if (className == null || className.equals("module-info")) {
                            continue;
                        }
                        byte[] bytes = null;
                        if (opts.requiresClassModel() || !visibility.includesAll()) {
                            try (var input = jar.getInputStream(jarEntry)) {
                                bytes = input.readAllBytes();
                            }
                        }
                        addListedSymbols(out, jarEntry.getName(), bytes, opts, visibility,
                                emittedPackages);
                    }
                }
            }
        }
    }

    private static void emitSynthesizedPackages(PrintWriter out, Options opts, Collection<String> packages,
            Set<String> emittedPackages) {
        if (!opts.listsPackages()) {
            return;
        }
        packages.stream()
                .sorted()
                .forEach(packageName -> {
                    if (emittedPackages.add(packageName) && matchesListedPrefix(opts, packageName)) {
                        if (!printListSymbol(out, SymbolKind.PACKAGE, packageName, null)) {
                            out.println(packageName + ": package " + packageName + ";");
                        }
                    }
                });
    }

    private void listModulePathEntries(Options opts, PrintWriter out, Visibility visibility,
            Set<String> emittedPackages)
            throws IOException, ToolException {
        for (var module : environment.moduleInputs()) {
            var entry = module.location();
            if (module.descriptor().isAutomatic() && opts.listsModules() && matchesListedPrefix(opts, module.name())) {
                if (!printListSymbol(out, SymbolKind.MODULE, module.name(), null)) {
                    out.println(module.name() + ": " + renderAutomaticModuleDeclaration(module.descriptor()));
                }
            }
            if (Files.isDirectory(entry)) {
                walkFilesInDeclarationOrder(entry,
                        file -> {
                            var resourceName = entry.relativize(file)
                                                    .toString()
                                                    .replace(File.separatorChar, '/');
                            if (!isVisibleModuleClass(resourceName, module.visiblePackages())) {
                                return;
                            }
                            addListedSymbols(
                                    out,
                                    resourceName,
                                    opts.requiresClassModel() || !visibility.includesAll()
                                            ? Files.readAllBytes(file)
                                            : null,
                                    opts,
                                    visibility,
                                    emittedPackages);
                        });
            } else if (Files.isRegularFile(entry)) {
                try (var jar = new JarFile(entry.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                    var iterator = jar.versionedStream()
                                      .filter(candidate -> !candidate.isDirectory())
                                      .iterator();
                    while (iterator.hasNext()) {
                        var jarEntry = iterator.next();
                        if (!isVisibleModuleClass(jarEntry.getName(), module.visiblePackages())) {
                            continue;
                        }
                        byte[] bytes = null;
                        if (opts.requiresClassModel() || !visibility.includesAll()) {
                            try (var input = jar.getInputStream(jarEntry)) {
                                bytes = input.readAllBytes();
                            }
                        }
                        addListedSymbols(out, jarEntry.getName(), bytes, opts, visibility,
                                emittedPackages);
                    }
                }
            }
            emitSynthesizedPackages(out, opts, module.visiblePackages(), emittedPackages);
        }
    }

    private void listSourceSymbols(Options opts, PrintWriter out, Set<String> emittedPackages) throws IOException, ToolException {
        var compiledEntries = new ArrayList<Path>();
        if (readsUnnamedModule(opts)) {
            compiledEntries.addAll(parsePathList(opts.classPath()));
        }
        environment.moduleInputs().stream()
                .map(ModuleInput::location)
                .forEach(compiledEntries::add);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            for (var sourceRoot : environment.sourceRoots()) {
                if (!Files.isDirectory(sourceRoot)) {
                    continue;
                }
                walkFilesInDeclarationOrder(sourceRoot,
                        sourceFile -> {
                            if (!sourceFile.toString().endsWith(JAVA_EXT)) {
                                return;
                            }
                            var task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("-proc:none"), null,
                                    fileManager.getJavaFileObjects(sourceFile));
                            var units = task.parse().iterator();
                            if (units.hasNext()) {
                                new SourceSymbolScanner(out, opts, compiledEntries, emittedPackages).scan(units.next(), null);
                            }
                        });
            }
        }
    }

    private static void addListedSymbols(PrintWriter out, String resourceName, byte[] bytes,
            Options opts, Visibility visibility, Set<String> emittedPackages) {
        var className = listedClassName(resourceName);
        if (className == null) {
            return;
        }
        ClassModel model = null;
        if (bytes != null) {
            model = ClassFile.of().parse(bytes);
        }
        addListedSymbols(out, className, model, opts, visibility, emittedPackages);
    }

    private static void addListedSymbols(PrintWriter out, String className, ClassModel model,
            Options opts, Visibility visibility) {
        addListedSymbols(out, className, model, opts, visibility,
                new HashSet<>());
    }

    private static void addListedSymbols(PrintWriter out, String className, ClassModel model,
            Options opts, Visibility visibility, Set<String> emittedPackages) {
        var metadataKind = metadataKind(model, className);
        var packageName = model != null && !model.isModuleInfo()
                ? model.thisClass()
                       .asSymbol()
                       .packageName()
                : packageName(className);
        if (!packageName.isEmpty()
                && opts.listsPackages()
                && emittedPackages.add(packageName)
                && matchesListedPrefix(opts, packageName)) {
            var declaration = new StringBuilder();
            if (metadataKind == SymbolKind.PACKAGE && model != null) {
                appendDeclarationAnnotations(declaration, model);
            }
            declaration.append("package ")
                       .append(packageName)
                       .append(';');
            printListedSymbol(out, opts, SymbolKind.PACKAGE, packageName, packageName,
                    declaration.toString());
        }
        if (metadataKind == SymbolKind.PACKAGE) {
            return;
        }
        if (metadataKind == SymbolKind.MODULE) {
            if (model != null && opts.listsModules()) {
                var moduleName = compiledModuleName(model);
                if (moduleName != null && matchesListedPrefix(opts, moduleName)) {
                    printListedSymbol(out, opts, SymbolKind.MODULE, moduleName, moduleName,
                            renderModuleDeclaration(model));
                }
            }
            return;
        }
        boolean visibleType;
        try {
            visibleType = model == null || visibility.visible(listedClassFlags(model));
        } catch (IllegalArgumentException _) {
            // VM-generated JDK holder classes can carry member-only visibility bits.
            return;
        }
        var unitName = ClassFileRenderer.compilationUnitName(model, className);
        if (visibleType && opts.listsType(model) && matchesListedPrefix(opts, className)) {
            printListedSymbol(out, opts, SymbolKind.classKind(model), unitName, className,
                    renderTypeDeclaration(model));
        }
        if (!opts.listsMethods() && !opts.listsFields()) {
            return;
        }

        if (opts.listsFields()) {
            for (int i = 0; i < model.fields().size(); i++) {
                var field = model.fields().get(i);
                if (!field.flags().has(AccessFlag.SYNTHETIC) && visibility.visible(field.flags()) && firstFieldNamed(model, i,
                        field.fieldName().stringValue())) {
                    var symbol = listedMemberTarget(opts, className,
                            field.fieldName().stringValue());
                    if (matchesListedPrefix(opts, symbol)) {
                        printListedSymbol(out, opts, SymbolKind.FIELD, unitName, symbol,
                                renderFieldDeclaration(field));
                    }
                }
            }
        }
        if (opts.listsMethods()) {
            for (int i = 0; i < model.methods().size(); i++) {
                var method = model.methods().get(i);
                if (method.flags().has(AccessFlag.SYNTHETIC) || method.flags().has(AccessFlag.BRIDGE) || !visibility.visible(method.flags())) {
                    continue;
                }
                var name = method.methodName().stringValue();
                if (ConstantDescs.CLASS_INIT_NAME.equals(name)) {
                    continue;
                }
                var queryName = ConstantDescs.INIT_NAME.equals(name) ? "new" : name;
                var symbol = listedMemberTarget(opts, className, queryName);
                if (matchesListedPrefix(opts, symbol)) {
                    printListedSymbol(out, opts, SymbolKind.METHOD, unitName, symbol,
                            listedMethodSignature(className, method), renderMethodDeclaration(model, method));
                }
            }
        }
    }

    private static void printListedSymbol(PrintWriter out, Options opts, SymbolKind kind,
            String unitName, String target, String text) {
        printListedSymbol(out, opts, kind, unitName, target, target,
                text);
    }

    private static void printListedSymbol(
            PrintWriter out,
            Options opts,
            SymbolKind kind,
            String unitName,
            String target,
            String listedName,
            String text) {
        if (!matchesListedPrefix(opts, target)) {
            return;
        }
        if (printListSymbol(out, kind, listedName, null)) {
            return;
        }
        out.println(unitName + ": " + text);
    }

    private static boolean printListSymbol(PrintWriter out, SymbolKind kind, String name,
            String location) {
        if (!(out instanceof SymbolOutput output) || !output.listOnly()) {
            return false;
        }
        output.symbol(kind, name, location);
        return true;
    }

    private static String listedMethodSignature(String owner, MethodModel method) {
        var name = method.methodName().equalsString(ConstantDescs.INIT_NAME) ? "new" : method.methodName().stringValue();
        var descriptor = method.methodTypeSymbol();
        var out = new StringBuilder(owner)
                .append('.')
                .append(name)
                .append('(');
        for (int i = 0; i < descriptor.parameterCount(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(qualifiedJavaType(descriptor.parameterType(i)));
        }
        return out.append(')').toString();
    }

    private static String qualifiedJavaType(ClassDesc type) {
        var descriptor = type.descriptorString();
        int dimensions = 0;
        while (descriptor.charAt(dimensions) == '[') {
            dimensions++;
        }
        var component = descriptor.substring(dimensions);
        String name = component.charAt(0) == 'L' ? component.substring(1, component.length() - 1)
                .replace('/', '.')
                .replace('$', '.')
                : javaType(component);
        return name + "[]".repeat(dimensions);
    }

    private static String listedMemberTarget(Options opts, String className, String memberName) {
        return className + "." + memberName;
    }

    private static boolean matchesListedPrefix(Options opts, String symbol) {
        if (opts.target() == null) {
            return true;
        }
        var prefix = opts.target();
        if (simpleNameTarget(prefix)) {
            return UsageSearch.terminalName(symbol).equals(prefix);
        }
        return symbol.equals(prefix) || symbol.startsWith(prefix + ".");
    }

    static boolean simpleNameTarget(String target) {
        return target != null && !target.contains(".");
    }

    private static boolean firstFieldNamed(ClassModel model, int index, String name) {
        for (int i = 0; i < index; i++) {
            if (model.fields()
                     .get(i)
                     .fieldName()
                     .equalsString(name)) {
                return false;
            }
        }
        return true;
    }

    private static boolean firstMethodNamed(ClassModel model, int index, String name,
            Visibility visibility) {
        for (int i = 0; i < index; i++) {
            var method = model.methods().get(i);
            if (method.flags().has(AccessFlag.SYNTHETIC) || method.flags().has(AccessFlag.BRIDGE) || !visibility.visible(method.flags())) {
                continue;
            }
            var previous = method.methodName().stringValue();
            if (ConstantDescs.CLASS_INIT_NAME.equals(previous)) {
                continue;
            }
            var queryName = ConstantDescs.INIT_NAME.equals(previous) ? "new" : previous;
            if (queryName.equals(name)) {
                return false;
            }
        }
        return true;
    }

    private static boolean noFieldNamed(ClassModel model, String name, Visibility visibility) {
        return model.fields().stream()
                .filter(field -> !field.flags().has(AccessFlag.SYNTHETIC))
                .filter(field -> visibility.visible(field.flags()))
                .noneMatch(field -> field.fieldName().equalsString(name));
    }

    private static final class SourceSymbolScanner extends TreePathScanner<Void, Void> {
        private final PrintWriter out;
        private final Options opts;
        private final List<Path> compiledEntries;
        private final Set<String> emittedPackages;
        private final Predicate<String> selectedDeclaration;
        private final Deque<String> classNames = new ArrayDeque<>();
        private final Deque<Boolean> emittedClasses = new ArrayDeque<>();
        private String packageName = "";
        private String unitName = "";

        SourceSymbolScanner(PrintWriter out, Options opts, List<Path> compiledEntries) {
            this(out, opts, compiledEntries, new HashSet<>());
        }

        SourceSymbolScanner(PrintWriter out, Options opts, List<Path> compiledEntries,
                            Set<String> emittedPackages) {
            this(out, opts, compiledEntries, emittedPackages, key -> true);
        }

        SourceSymbolScanner(PrintWriter out, Options opts, List<Path> compiledEntries,
                            Set<String> emittedPackages, Predicate<String> selectedDeclaration) {
            this.out = out;
            this.opts = opts;
            this.compiledEntries = compiledEntries;
            this.emittedPackages = emittedPackages;
            this.selectedDeclaration = selectedDeclaration;
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree tree, Void unused) {
            packageName = tree.getPackageName() == null ? "" : tree.getPackageName().toString();
            var sourceName = Path.of(tree.getSourceFile().getName())
                    .getFileName()
                    .toString();
            int extension = sourceName.lastIndexOf('.');
            var baseName = extension > 0 ? sourceName.substring(0, extension) : sourceName;
            unitName = packageName.isEmpty() ? baseName : packageName + "." + baseName;
            var sourcePath = Path.of(tree.getSourceFile()
                    .getName());
            var packageInfo = sourcePath.getParent() != null ? sourcePath.getParent().resolve("package-info.java") : null;
            if (!packageName.isEmpty()
                    && opts.listsPackages()
                    && (sourceName.equals("package-info.java") || packageInfo == null || !Files.isRegularFile(packageInfo))
                    && emittedPackages.add(packageName)
                    && matchesListedPrefix(opts, packageName)) {
                var declaration = new StringBuilder();
                tree.getPackageAnnotations().forEach(annotation -> declaration.append(annotation).append(' '));
                declaration.append("package ")
                           .append(packageName)
                           .append(';');
                printListedSymbol(out, opts, SymbolKind.PACKAGE, packageName, packageName,
                        declaration.toString());
            }
            return super.visitCompilationUnit(tree, unused);
        }

        @Override
        public Void visitModule(ModuleTree tree, Void unused) {
            var moduleName = tree.getName().toString();
            if (opts.listsModules() && matchesListedPrefix(opts, moduleName)) {
                printListedSymbol(out, opts, SymbolKind.MODULE, moduleName, moduleName,
                        compactSourceLine(tree.toString()));
            }
            return super.visitModule(tree, unused);
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            var parent = getCurrentPath().getParentPath();
            if (parent == null || !(parent.getLeaf() instanceof CompilationUnitTree || parent.getLeaf() instanceof ClassTree)) {
                return null;
            }
            var simpleName = tree.getSimpleName().toString();
            if (simpleName.isEmpty()) {
                return null;
            }
            var className = classNames.isEmpty()
                    ? packageName.isEmpty() ? simpleName : packageName + "." + simpleName
                    : classNames.getLast() + "." + simpleName;
            var visibility = opts.visibility() != null ? opts.visibility() : Visibility.ALL;
            var enclosingVisible = emittedClasses.isEmpty() || emittedClasses.getLast();
            var sourceOnly = enclosingVisible && !hasCompiledClass(className, compiledEntries);
            boolean implicitlyPublic = parent.getLeaf() instanceof ClassTree enclosing
                    && (enclosing.getKind() == Kind.INTERFACE || enclosing.getKind() == Kind.ANNOTATION_TYPE);
            if (sourceOnly && opts.listsType(tree) && selectedDeclaration.test(className)
                    && visibility.visibleModifiers(tree.getModifiers().getFlags(), implicitlyPublic)) {
                printListedSymbol(out, opts, SymbolKind.classKind(tree), unitName, className,
                        renderSourceTypeDeclaration(tree));
            }
            classNames.addLast(className);
            emittedClasses.addLast(sourceOnly);
            try {
                return super.visitClass(tree, unused);
            } finally {
                emittedClasses.removeLast();
                classNames.removeLast();
            }
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            if (opts.listsFields()
                    && currentClassIsEmitted()
                    && visibleMember(tree.getModifiers().getFlags())
                    && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree owner
                    && firstSourceFieldNamed(owner, tree)) {
                var symbol = listedMemberTarget(opts, classNames.getLast(),
                        tree.getName().toString());
                printListedSymbol(out, opts, SymbolKind.FIELD, unitName, symbol,
                        compactSourceLine(tree.toString()) + ";");
            }
            return super.visitVariable(tree, unused);
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            if (opts.listsMethods()
                    && currentClassIsEmitted()
                    && visibleMember(tree.getModifiers().getFlags())
                    && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree owner) {
                var name = tree.getName().contentEquals(ConstantDescs.INIT_NAME) ? "new" : tree.getName().toString();
                var keyName = tree.getName().toString();
                int ordinal = (int) owner.getMembers()
                        .stream()
                        .takeWhile(member -> member != tree)
                        .filter(member -> member instanceof MethodTree method && method.getName().contentEquals(keyName))
                        .count();
                if (selectedDeclaration.test(attributionKey(classNames.getLast(), keyName, ordinal))) {
                    var symbol = listedMemberTarget(opts, classNames.getLast(), name);
                    printListedSymbol(out, opts, SymbolKind.METHOD, unitName, symbol,
                            listedSourceMethodSignature(classNames.getLast(), tree), renderSourceMethodDeclaration(tree));
                }
            }
            return super.visitMethod(tree, unused);
        }

        private boolean currentClassIsEmitted() {
            return !emittedClasses.isEmpty() && emittedClasses.getLast();
        }

        private boolean visibleMember(Set<Modifier> modifiers) {
            var visibility = opts.visibility() != null ? opts.visibility() : Visibility.ALL;
            var parent = getCurrentPath().getParentPath();
            boolean implicitlyPublic = parent.getLeaf() instanceof ClassTree owner
                    && (owner.getKind() == Kind.INTERFACE || owner.getKind() == Kind.ANNOTATION_TYPE);
            return visibility.visibleModifiers(modifiers, implicitlyPublic);
        }

        private static String listedSourceMethodSignature(String owner, MethodTree method) {
            var name = method.getName().contentEquals(ConstantDescs.INIT_NAME) ? "new" : method.getName().toString();
            return method.getParameters().stream()
                    .map(parameter -> parameter.getType().toString())
                    .collect(Collectors.joining(",", owner + "." + name + "(", ")"));
        }

        private static boolean firstSourceFieldNamed(ClassTree owner, VariableTree selected) {
            for (var member : owner.getMembers()) {
                if (member == selected) {
                    return true;
                }
                if (member instanceof VariableTree field && field.getName().contentEquals(selected.getName())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean firstSourceMethodNamed(ClassTree owner, MethodTree selected, String selectedName) {
            for (var member : owner.getMembers()) {
                if (member == selected) {
                    return true;
                }
                if (member instanceof MethodTree method) {
                    var name = method.getName().contentEquals(ConstantDescs.INIT_NAME) ? "new" : method.getName().toString();
                    if (name.equals(selectedName)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static boolean noSourceFieldNamed(ClassTree owner, String name) {
            for (var member : owner.getMembers()) {
                if (member instanceof VariableTree field && field.getName().contentEquals(name)) {
                    return false;
                }
            }
            return true;
        }

        private static String renderSourceTypeDeclaration(ClassTree tree) {
            var out = new StringBuilder();
            for (var modifier : tree.getModifiers().getFlags()) {
                out.append(modifier.toString()).append(' ');
            }
            out.append(switch (tree.getKind()) {
                case INTERFACE -> "interface ";
                case ENUM -> "enum ";
                case RECORD -> "record ";
                case ANNOTATION_TYPE -> "@interface ";
                default -> "class ";
            })
               .append(tree.getSimpleName());
            if (!tree.getTypeParameters().isEmpty()) {
                out.append('<');
                for (int i = 0; i < tree.getTypeParameters().size(); i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(tree.getTypeParameters()
                                   .get(i));
                }
                out.append('>');
            }
            if (tree.getKind() == Kind.RECORD) {
                var text = tree.toString();
                int name = text.indexOf(tree.getSimpleName()
                        .toString());
                int open = text.indexOf('(', name);
                int close = open >= 0 ? text.indexOf(')', open) : -1;
                if (open >= 0 && close >= open) {
                    out.append(text, open, close + 1);
                }
            }
            if (tree.getExtendsClause() != null) {
                out.append(" extends ").append(tree.getExtendsClause());
            }
            if (!tree.getImplementsClause().isEmpty()) {
                out.append(tree.getKind() == Kind.INTERFACE ? " extends " : " implements ");
                for (int i = 0; i < tree.getImplementsClause().size(); i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(tree.getImplementsClause()
                                   .get(i));
                }
            }
            return compactSourceLine(out.append(" {")
                    .toString());
        }

        private static String renderSourceMethodDeclaration(MethodTree tree) {
            var text = tree.toString();
            if (tree.getBody() == null) {
                return compactSourceLine(text);
            }
            var body = tree.getBody().toString();
            int bodyStart = text.lastIndexOf(body);
            if (bodyStart < 0) {
                bodyStart = text.indexOf('{');
            }
            return compactSourceLine(text.substring(0, bodyStart)) + " { /* body omitted */ }";
        }

        private static String compactSourceLine(String text) {
            return text.strip().replaceAll("\\s+", " ");
        }
    }

    private static boolean hasCompiledClass(String binaryName, List<Path> compiledEntries) {
        for (var entry : compiledEntries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }
            for (var candidate : classFileCandidates(binaryName)) {
                if (Files.isRegularFile(entry.resolve(candidate))) {
                    return true;
                }
            }
        }
        return false;
    }

    private record SymbolSource(
            String classLocation,
            String sourceLocation,
            String unitName,
            String sourceFileName,
            SourceModel model,
            Map<String, Attribution> attribution,
            List<ClassModel> classes) {}

    private record ContextMatch(String key, String symbol, SymbolKind kind,
            String listedName, Attribution attribution) {}

    void printSymbolContext(Options opts, SymbolSelection query, PrintWriter out,
                            PrintWriter err)
            throws IOException, ToolException {
        var file = Path.of(query.path());
        if (Files.isRegularFile(file) && query.path().endsWith(CLASS_EXT)) {
            var classes = readClassGroup(file);
            var outer = classes.stream()
                    .filter(model -> !model.thisClass()
                                           .asInternalName()
                                           .contains("$"))
                    .findFirst()
                    .orElse(classes.getFirst());
            if (metadataKind(outer, UsageSearch.displayClassName(outer)) != null) {
                try (var sources = SourceLookup.open(opts.sourcePath(), environment.moduleSourceRoots(), opts.system())) {
                    emitClassSourceContext(
                            opts.withTargetAndKinds(null, opts.kinds()),
                            out,
                            outer,
                            unitLocation(file),
                            Visibility.ALL,
                            sources,
                            new HashSet<>(),
                            new HashSet<>(),
                            new HashMap<>());
                }
                return;
            }
            var owner = attributionKey(outer).replace('$', '.');
            var selected = opts.withTargetAndKinds(query.member() != null ? owner + "." + query.member() : owner,
                    opts.kinds());
            var visibility = opts.visibility() != null ? opts.visibility() : Visibility.ALL;
            var source = symbolSource(classes, visibility, unitLocation(file), opts);
            printSymbolContext(source, contextMatches(classes, source.attribution(), selected, visibility), selected, out);
            return;
        }
        if (Files.isRegularFile(file) && query.path().endsWith(JAVA_EXT)) {
            var bytes = Files.readAllBytes(file);
            var model = SourceModel.parse(bytes);
            var visibility = opts.visibility() != null ? opts.visibility() : Visibility.ALL;
            var structure = model.structure(file.getFileName()
                    .toString(),
                    visibility);
            var names = structure.classNames();
            if (names.isEmpty()) {
                throw new ToolException(1, "No symbols found in " + file);
            }
            var owner = names.getFirst();
            var selected = opts.withTargetAndKinds(query.member() != null ? owner + "." + query.member() : owner,
                    opts.kinds());
            var attribution = structure.attribution();
            var source = new SymbolSource(
                    null,
                    unitLocation(file),
                    owner,
                    file.getFileName().toString(),
                    model,
                    attribution,
                    List.of());
            printSymbolContext(source, contextMatches(structure, selected), selected, out);
            return;
        }
        var binaryName = query.path();
        var resolved = readsUnnamedModule(opts) ? resolveClass(binaryName, parsePathList(opts.classPath())) : null;
        if (resolved == null) {
            resolved = environment.resolveModuleClass(binaryName);
        }
        if (resolved == null) {
            resolved = environment.resolveSystemClass(binaryName);
        }
        var visibility = opts.visibility() != null
                ? opts.visibility()
                : resolved != null ? Visibility.LIBRARY : Visibility.ALL;
        final SymbolSource source;
        final List<ContextMatch> matches;
        if (resolved != null) {
            source = symbolSource(resolved.classes(), visibility, resolved.location(), opts);
            matches = contextMatches(resolved.classes(), source.attribution(), opts, visibility);
        } else {
            var sourceFile = environment.resolveSourceFile(binaryName);
            if (sourceFile == null) {
                throw new ToolException(1, "Class not found: " + binaryName);
            }
            var bytes = Files.readAllBytes(sourceFile);
            var model = SourceModel.parse(bytes);
            var structure = model.structure(sourceFile.getFileName()
                    .toString(),
                    visibility);
            var attribution = structure.attribution();
            source = new SymbolSource(
                    null,
                    unitLocation(sourceFile),
                    structure.classNames().getFirst(),
                    sourceFile.getFileName().toString(),
                    model,
                    attribution,
                    List.of());
            matches = contextMatches(structure, opts);
        }
        printSymbolContext(source, matches, opts, out);
    }

    private static void printClassDeclarations(List<ClassModel> classes, Options opts, Visibility visibility,
            PrintWriter out) {
        var selected = opts.kinds().isEmpty() ? opts.withKinds(defaultKinds()) : opts;
        for (var model : classes.stream()
                .sorted(Comparator.comparing(model -> model.thisClass()
                        .asInternalName()
                        .contains("$")))
                .toList()) {
            addListedSymbols(out, attributionKey(model).replace('$', '.'), model, selected, visibility);
        }
    }

    private static void printSourceDeclarations(Path sourceFile, Options opts, PrintWriter out,
            Set<String> selectedKeys)
            throws IOException {
        var selected = opts.kinds().isEmpty() ? opts.withKinds(defaultKinds()) : opts;
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("-proc:none"), null,
                    fileManager.getJavaFileObjects(sourceFile));
            var units = task.parse().iterator();
            if (units.hasNext()) {
                new SourceSymbolScanner(out, selected, List.of(), new HashSet<>(), selectedKeys::contains)
                        .scan(units.next(), null);
            }
        }
    }

    private void printSymbolContext(SymbolSource source, List<ContextMatch> matches, Options opts,
            PrintWriter out)
            throws IOException, ToolException {
        if (relationships != null) {
            matches = relationshipMatches(matches, true);
            if (matches.isEmpty()) {
                return;
            }
        }
        if (matches.isEmpty()) {
            if (!opts.kinds().isEmpty()) {
                return;
            }
            throw new ToolException(1, "Symbol not found: " + opts.target());
        }
        if (out instanceof SymbolOutput output && output.listOnly()) {
            var location = source.sourceLocation() != null ? source.sourceLocation() : source.classLocation();
            for (var match : matches.stream()
                    .sorted(Comparator.comparingInt(candidate -> {
                        int offset = candidate.attribution()
                                              .signature()
                                              .startOffset();
                        return offset >= 0 ? offset : Integer.MAX_VALUE;
                    }))
                    .toList()) {
                output.symbol(match.kind(), match.listedName(), location);
            }
            out.flush();
            return;
        }
        if (out instanceof OriginOutput output) {
            output.classOrigin(source.unitName(), source.classLocation());
        }
        var model = source.model();
        if (relationships != null && opts.source() == SourceScope.NONE && source.classes().isEmpty()) {
            printSourceDeclarations(Path.of(source.sourceLocation()), opts, out,
                    matches.stream()
                           .map(ContextMatch::key)
                           .collect(Collectors.toSet()));
            return;
        }
        if (opts.source() == SourceScope.BODY && model != null && printBodyLines(source, matches, opts, model, out)) {
            out.flush();
            return;
        }
        if (opts.source() == SourceScope.UNIT && model != null) {
            for (int line = 1; line <= model.contentLineCount(); line++) {
                printSourceLine(out, source.unitName(), source.sourceFileName(),
                        source.sourceLocation(), line, model.line(line));
            }
            out.flush();
            return;
        }
        if ((opts.source() == SourceScope.SYMBOL || opts.source() == SourceScope.TYPE || opts.source() == SourceScope.UNIT)
                && model == null
                && !source.classes().isEmpty()) {
            warnSourceUnavailable(out, source.unitName());
            printVirtualUnit(source.unitName(), source.classes(), out);
            out.flush();
            return;
        }
        var emitted = new BitSet();
        var spans = new ArrayList<SourceSpan>();
        if (opts.source() == SourceScope.SYMBOL && matches.stream().anyMatch(match -> isTopLevelType(source.attribution(), match))) {
            spans.add(SourceSpan.lines(1, model.contentLineCount()));
        } else if (opts.source() == SourceScope.SYMBOL) {
            for (var match : matches) {
                if (isType(match.kind()) || match.kind() == SymbolKind.METHOD) {
                    spans.add(match.attribution()
                                   .documentation());
                    spans.add(match.attribution()
                                   .declaration());
                } else {
                    spans.add(match.attribution()
                                   .signature());
                }
            }
        } else if (opts.source() == SourceScope.TYPE) {
            for (var match : matches) {
                var type = enclosingTopLevelTypeAttribution(source.attribution(), match.symbol());
                spans.add(type.documentation());
                spans.add(type.declaration());
            }
        } else if (opts.source() == SourceScope.DEFINITION) {
            for (var match : matches) {
                spans.add(match.attribution()
                               .documentation());
                spans.add(match.attribution()
                               .declaration());
            }
        } else if (opts.source() == SourceScope.DOC) {
            for (var match : matches) {
                spans.add(
                        SourceSpan.covering(match.attribution().documentation(),
                                match.attribution().signature()));
            }
        } else if (opts.source() == SourceScope.SIGNATURE
                || relationships != null && opts.source() == SourceScope.BODY && source.classes().isEmpty()) {
            for (var match : matches) {
                spans.add(match.attribution()
                               .signature());
            }
        }
        spans.removeIf(span -> !span.present());
        spans.sort(Comparator.comparingInt(SourceSpan::startLine)
                .thenComparing(Comparator.comparingInt(SourceSpan::endLine)
                        .reversed()));
        for (var span : spans) {
            printContextSpan(out, source.unitName(), source.sourceFileName(),
                    source.sourceLocation(), span, model, emitted);
        }
        if (emitted.isEmpty() && !source.classes().isEmpty()) {
            if (opts.source() != SourceScope.NONE) {
                warnSourceUnavailable(out, source.unitName());
            }
            var visibility = opts.visibility() != null ? opts.visibility() : Visibility.LIBRARY;
            if (relationships == null) {
                printClassDeclarations(source.classes(), opts, visibility, out);
            } else {
                printMatchedClassDeclarations(source, matches, out);
            }
        }
        out.flush();
    }

    private List<ContextMatch> relationshipMatches(List<ContextMatch> matches, boolean deduplicate) throws ToolException {
        var selected = new ArrayList<ContextMatch>();
        for (var match : matches) {
            if (relationships.matches(match.key(), match.kind()) && (!deduplicate || emittedRelationships.add(match.key()))) {
                selected.add(match);
            }
        }
        return selected;
    }

    private static void printMatchedClassDeclarations(SymbolSource source, List<ContextMatch> matches, PrintWriter out) {
        var keys = matches.stream()
                          .map(ContextMatch::key)
                          .collect(Collectors.toSet());
        for (var model : source.classes()) {
            var owner = attributionKey(model).replace('$', '.');
            if (keys.contains(owner)) {
                out.println(source.unitName() + ": " + renderTypeDeclaration(model));
            }
            var ordinals = new HashMap<String, Integer>();
            for (var method : model.methods()) {
                var name = method.methodName().stringValue();
                int ordinal = ordinals.merge(name, 1, Integer::sum) - 1;
                if (keys.contains(attributionKey(owner, name, ordinal))) {
                    out.println(source.unitName() + ": " + renderMethodDeclaration(model, method));
                }
            }
        }
    }

    static void warnSourceUnavailable(PrintWriter out, String unit) {
        if (out instanceof SourceWarningOutput warnings) {
            warnings.sourceUnavailable(unit);
        }
    }

    private static boolean isType(SymbolKind kind) {
        return switch (kind) {
            case TYPE, CLASS, INTERFACE, ENUM, RECORD, ANNOTATION ->
                    true;
            case MODULE, PACKAGE, METHOD, FIELD -> false;
        };
    }

    private static boolean isTopLevelType(Map<String, Attribution> attribution, ContextMatch match) {
        if (!isType(match.kind()) || !match.attribution()
                .declaration()
                .present()) {
            return false;
        }
        var declaration = match.attribution().declaration();
        return attribution.entrySet().stream()
                .filter(entry -> !entry.getKey().contains("#"))
                .filter(entry -> !entry.getKey().equals(match.symbol()))
                .map(Entry::getValue)
                .map(Attribution::declaration)
                .filter(SourceSpan::present)
                .noneMatch(candidate -> candidate.startOffset() < declaration.startOffset() && candidate.endOffset() > declaration.endOffset());
    }

    private static boolean printBodyLines(SymbolSource source, List<ContextMatch> matches, Options opts,
            SourceModel sourceModel, PrintWriter out) {
        var selected = opts.kinds().isEmpty() ? opts.withKinds(defaultKinds()) : opts;
        var visibility = opts.visibility() != null ? opts.visibility() : Visibility.LIBRARY;
        var lines = new BitSet();
        for (var model : source.classes()) {
            var owner = attributionKey(model).replace('$', '.');
            var selectedType = selected.listsType(model)
                    && matchesListedPrefix(selected, owner)
                    && (!opts.relationships() || matches.stream().anyMatch(match -> match.key().equals(owner)));
            for (var method : model.methods()) {
                var name = method.methodName().stringValue();
                if (method.flags().has(AccessFlag.SYNTHETIC)
                        || method.flags().has(AccessFlag.BRIDGE)
                        || !visibility.visible(method.flags())
                        || ConstantDescs.CLASS_INIT_NAME.equals(name)) {
                    continue;
                }
                if (model.flags().has(AccessFlag.ENUM) && (name.equals("values") || name.equals("valueOf"))) {
                    continue;
                }
                var publicName = ConstantDescs.INIT_NAME.equals(name) ? "new" : name;
                var symbol = owner + "." + publicName;
                if (!selectedType
                        && (!selected.listsMethods()
                                || !matchesListedPrefix(selected, symbol)
                                || opts.relationships() && matches.stream().noneMatch(match -> match.listedName().equals(listedMethodSignature(owner, method))))) {
                    continue;
                }
                var code = method.findAttribute(Attributes.code()).orElse(null);
                if (code == null) {
                    continue;
                }
                var table = code.findAttribute(Attributes.lineNumberTable()).orElse(null);
                if (table == null) {
                    continue;
                }
                for (var line : table.lineNumbers()) {
                    if (line.lineNumber() > 0 && line.lineNumber() <= sourceModel.contentLineCount()) {
                        lines.set(line.lineNumber());
                    }
                }
            }
        }
        for (int line = lines.nextSetBit(0);
             line >= 0;
             line = lines.nextSetBit(line + 1)) {
            printSourceLine(out, source.unitName(), source.sourceFileName(),
                    source.sourceLocation(), line, sourceModel.line(line));
        }
        return !lines.isEmpty();
    }

    static Attribution enclosingTopLevelTypeAttribution(Map<String, Attribution> attribution, String symbol) {
        return attribution.entrySet().stream()
                .filter(entry -> !entry.getKey().contains("#"))
                .filter(entry ->
                        symbol.equals(entry.getKey()) || symbol.startsWith(entry.getKey() + "."))
                .filter(entry -> entry.getValue()
                                      .declaration()
                                      .present())
                .min(Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Entry::getValue)
                .orElse(Attribution.EMPTY);
    }

    private static List<String> sourceClassNames(CompilationUnitTree unit) {
        var names = new ArrayList<String>();
        new TreePathScanner<Void, String>() {
            @Override
            public Void visitCompilationUnit(CompilationUnitTree tree, String unused) {
                var packageName = tree.getPackageName() == null ? "" : tree.getPackageName().toString();
                return super.visitCompilationUnit(tree, packageName);
            }

            @Override
            public Void visitClass(ClassTree tree, String owner) {
                var parent = getCurrentPath().getParentPath();
                if (parent == null || !(parent.getLeaf() instanceof CompilationUnitTree || parent.getLeaf() instanceof ClassTree)) {
                    return null;
                }
                var simpleName = tree.getSimpleName().toString();
                if (simpleName.isEmpty()) {
                    return null;
                }
                var name = owner == null || owner.isEmpty()
                        ? simpleName
                        : owner + "." + simpleName;
                names.add(name);
                return super.visitClass(tree, name);
            }
        }.scan(unit, "");
        return List.copyOf(names);
    }

    private static void printContextSpan(
            PrintWriter out,
            String unitName,
            String sourceFileName,
            String sourceLocation,
            SourceSpan span,
            SourceModel model,
            BitSet emitted) {
        if (!span.present() || model == null) {
            return;
        }
        for (int line = span.startLine();
             line <= span.endLine() && line <= model.contentLineCount();
             line++) {
            if (!emitted.get(line)) {
                emitted.set(line);
                printSourceLine(out, unitName, sourceFileName, sourceLocation, line,
                        model.line(line));
            }
        }
    }

    static void printSourceLine(PrintWriter out, String unitName, String sourceFileName,
            String sourceLocation, int line, String text) {
        if (out instanceof OriginOutput output) {
            output.sourceOrigin(unitName, sourceFileName, sourceLocation);
        }
        out.println(unitName
                + "("
                + sourceFileName
                + ":"
                + line
                + "): "
                + text);
    }

    private static void printVirtualUnit(String unitName, List<ClassModel> classes, PrintWriter out) {
        int packageEnd = unitName.lastIndexOf('.');
        if (packageEnd > 0) {
            out.println(unitName + ": package " + unitName.substring(0, packageEnd) + ";");
            out.println(unitName + ":");
        }
        for (var model : classes.stream()
                .sorted(Comparator.comparing(candidate -> candidate.thisClass()
                        .asInternalName()
                        .contains("$")))
                .toList()) {
            out.println(unitName + ": " + renderTypeDeclaration(model));
            if (model.flags().has(AccessFlag.ENUM)) {
                for (var field : model.fields()) {
                    if (field.flags().has(AccessFlag.ENUM)) {
                        out.println(unitName + ":     " + field.fieldName().stringValue() + ",");
                    }
                }
            }
            for (var field : model.fields()) {
                if (field.flags().has(AccessFlag.SYNTHETIC) || field.flags().has(AccessFlag.ENUM)) {
                    continue;
                }
                out.println(unitName + ":     " + renderFieldDeclaration(field));
            }
            for (var method : model.methods()) {
                var name = method.methodName().stringValue();
                if (method.flags().has(AccessFlag.SYNTHETIC)
                        || method.flags().has(AccessFlag.BRIDGE)
                        || ConstantDescs.CLASS_INIT_NAME.equals(name)
                        || model.flags().has(AccessFlag.ENUM) && (name.equals("values") || name.equals("valueOf"))) {
                    continue;
                }
                out.println(unitName + ":     " + renderMethodDeclaration(model, method));
            }
            out.println(unitName + ": }");
        }
    }

    private static List<ContextMatch> contextMatches(List<ClassModel> classes, Map<String, Attribution> attribution, Options opts,
            Visibility visibility) {
        var selected = opts.kinds().isEmpty() ? opts.withKinds(defaultKinds()) : opts;
        var matches = new ArrayList<ContextMatch>();
        for (var model : classes) {
            var owner = attributionKey(model).replace('$', '.');
            if (selected.listsType(model) && visibility.visible(listedClassFlags(model)) && matchesListedPrefix(selected, owner)) {
                matches.add(new ContextMatch(owner, owner, SymbolKind.classKind(model), owner,
                        attribution.getOrDefault(owner, Attribution.EMPTY)));
            }
            if (selected.listsFields()) {
                for (var field : model.fields()) {
                    if (field.flags().has(AccessFlag.SYNTHETIC) || !visibility.visible(field.flags())) {
                        continue;
                    }
                    var symbol = owner + "." + field.fieldName().stringValue();
                    if (matchesListedPrefix(selected, symbol)) {
                        matches.add(new ContextMatch(attributionKey(model, field), symbol, SymbolKind.FIELD, symbol,
                                attribution.getOrDefault(attributionKey(model, field), Attribution.EMPTY)));
                    }
                }
            }
            if (selected.listsMethods()) {
                var ordinals = new HashMap<String, Integer>();
                for (var method : model.methods()) {
                    var name = method.methodName().stringValue();
                    var keyName = ConstantDescs.INIT_NAME.equals(name) ? "<init>" : name;
                    int ordinal = ordinals.merge(keyName, 0, Integer::sum);
                    ordinals.put(keyName, ordinal + 1);
                    if (method.flags().has(AccessFlag.SYNTHETIC)
                            || method.flags().has(AccessFlag.BRIDGE)
                            || !visibility.visible(method.flags())
                            || ConstantDescs.CLASS_INIT_NAME.equals(name)) {
                        continue;
                    }
                    if (model.flags().has(AccessFlag.ENUM) && (name.equals("values") || name.equals("valueOf"))) {
                        continue;
                    }
                    var publicName = ConstantDescs.INIT_NAME.equals(name) ? "new" : name;
                    var symbol = owner + "." + publicName;
                    if (matchesListedPrefix(selected, symbol)) {
                        matches.add(new ContextMatch(attributionKey(owner, keyName, ordinal), symbol, SymbolKind.METHOD, listedMethodSignature(owner, method),
                                attribution.getOrDefault(attributionKey(attributionKey(model), keyName, ordinal), Attribution.EMPTY)));
                    }
                }
            }
        }
        return matches;
    }

    private static List<ContextMatch> contextMatches(SourceStructure structure, Options opts) {
        return contextMatches(structure.attribution(), structure.kinds(), structure.listedNames(),
                structure.selectedSymbols(), opts);
    }

    private static List<ContextMatch> contextMatches(Map<String, Attribution> attribution, Map<String, SymbolKind> kinds, Map<String, String> listedNames,
            Set<String> selectedSymbols, Options opts) {
        var matches = new ArrayList<ContextMatch>();
        for (var entry : attribution.entrySet()) {
            var key = entry.getKey();
            if (!selectedSymbols.contains(key)) {
                continue;
            }
            int hash = key.indexOf('#');
            String symbol;
            SymbolKind kind;
            if (hash < 0) {
                symbol = key;
                kind = kinds.getOrDefault(key, SymbolKind.TYPE);
            } else {
                var member = key.substring(hash + 1);
                int ordinal = member.lastIndexOf('@');
                if (ordinal >= 0) {
                    member = member.substring(0, ordinal);
                    symbol = key.substring(0, hash)
                             + "."
                             + (member.equals("<init>") ? "new" : member);
                    kind = SymbolKind.METHOD;
                } else {
                    symbol = key.substring(0, hash) + "." + member;
                    kind = SymbolKind.FIELD;
                }
            }
            var typeKind = switch (kind) {
                case TYPE, CLASS, INTERFACE, ENUM, RECORD, ANNOTATION ->
                        true;
                case MODULE, PACKAGE, METHOD, FIELD -> false;
            };
            if ((opts.kinds().isEmpty()
                            || opts.kinds().contains(kind)
                            || typeKind && opts.kinds().contains(SymbolKind.TYPE))
                    && matchesListedPrefix(opts, symbol)) {
                matches.add(new ContextMatch(key, symbol, kind, listedNames.getOrDefault(key, symbol),
                        entry.getValue()));
            }
        }
        return matches;
    }

    private SymbolSource symbolSource(List<ClassModel> classes, Visibility visibility, String classLocation,
            Options opts)
            throws IOException, ToolException {
        var outer = classes.stream()
                .filter(model -> !model.thisClass()
                                       .asInternalName()
                                       .contains("$"))
                .findFirst()
                .orElse(classes.getFirst());
        var outerInternalName = outer.thisClass().asInternalName();
        var unitName = ClassFileRenderer.compilationUnitName(outer, outerInternalName.replace('/', '.')
                .replace('$', '.'));
        var declaredSourceFile = outer.findAttribute(Attributes.sourceFile()).stream()
                .map(attribute -> attribute.sourceFile().stringValue())
                .findFirst()
                .orElse(unitName.substring(unitName.lastIndexOf('.') + 1) + JAVA_EXT);
        if (opts.source() == SourceScope.NONE) {
            return new SymbolSource(classLocation, null, unitName, declaredSourceFile, null,
                    Map.of(), classes);
        }
        try (var sources = SourceLookup.open(opts.sourcePath(), environment.moduleSourceRoots(), opts.system())) {
            var sourceFile = outer.findAttribute(Attributes.sourceFile());
            if (sourceFile.isPresent()) {
                int slash = outerInternalName.lastIndexOf('/');
                var relativePath = (slash < 0 ? "" : outerInternalName.substring(0, slash + 1))
                        + sourceFile.get()
                                    .sourceFile()
                                    .stringValue();
                var source = sources.find(relativePath, classLocation, outerInternalName + CLASS_EXT);
                if (source != null) {
                    if (source.javaSource()) {
                        var model = SourceModel.parse(source.bytes());
                        return new SymbolSource(classLocation, source.origin(), unitName, declaredSourceFile, model,
                                model.attributions(classes, visibility), classes);
                    }
                    return new SymbolSource(
                            classLocation,
                            source.origin(),
                            unitName,
                            declaredSourceFile,
                            opts.source() == SourceScope.BODY || opts.source() == SourceScope.UNIT
                                    ? SourceModel.parse(source.bytes())
                                    : null,
                            Map.of(),
                            classes);
                }
            }
        }
        var systemSource = findSystemSource(outerInternalName, classLocation, opts.system());
        if (systemSource == null) {
            return new SymbolSource(classLocation, null, unitName, declaredSourceFile, null,
                    Map.of(), classes);
        }
        var location = systemSource.origin();
        var model = SourceModel.parse(systemSource.bytes());
        return new SymbolSource(classLocation, location, unitName, declaredSourceFile, model,
                model.attributions(classes, visibility), classes);
    }

    private record SourceContent(byte[] bytes, String relativePath, String origin) {}

    private static SourceContent findSystemSource(String internalName, String classLocation, String system) throws IOException {
        return findSystemSourceByRelativePath(internalName + JAVA_EXT, classLocation, system);
    }

    private static SourceContent findSystemSourceByRelativePath(String sourceRelativePath, String classLocation, String system) throws IOException {
        if (classLocation == null || "none".equals(system)) {
            return null;
        }
        var javaHome = system != null ? Path.of(system) : Path.of(System.getProperty("java.home"));
        String sourceEntry;
        if (classLocation.startsWith("jrt:/")) {
            var path = URI.create(classLocation).getPath();
            int moduleEnd = path.indexOf('/', 1);
            if (moduleEnd < 0) {
                return null;
            }
            sourceEntry = path.substring(1, moduleEnd) + "/" + sourceRelativePath;
        } else if (system != null && classLocation.startsWith("jar:")) {
            sourceEntry = sourceRelativePath;
        } else {
            return null;
        }

        for (var sourceArchive : List.of(javaHome.resolve("lib/src.zip"), javaHome.resolve("src.zip"))) {
            if (!Files.isRegularFile(sourceArchive)) {
                continue;
            }
            try (var sourceFileSystem = FileSystems.newFileSystem(sourceArchive)) {
                var sourceFile = sourceFileSystem.getPath("/" + sourceEntry);
                if (Files.isRegularFile(sourceFile)) {
                    return new SourceContent(Files.readAllBytes(sourceFile), sourceEntry, "jar:" + sourceArchive.toAbsolutePath()
                            .normalize()
                            .toUri()
                            + "!/" + sourceEntry);
                }
            }
        }
        return null;
    }

    private static boolean hasClassMember(List<ClassModel> classes, ClassModel outer, String member,
            Visibility visibility) {
        for (var field : outer.fields()) {
            if (!field.flags().has(AccessFlag.SYNTHETIC) && visibility.visible(field.flags()) && field.fieldName().equalsString(member)) {
                return true;
            }
        }
        boolean isEnum = outer.flags().has(AccessFlag.ENUM);
        for (var method : outer.methods()) {
            if (method.flags().has(AccessFlag.SYNTHETIC) || method.flags().has(AccessFlag.BRIDGE) || !visibility.visible(method.flags())) {
                continue;
            }
            var name = method.methodName().stringValue();
            if (ConstantDescs.CLASS_INIT_NAME.equals(name)) {
                continue;
            }
            if (isEnum && (name.equals("values") || name.equals("valueOf"))) {
                continue;
            }
            if (ConstantDescs.INIT_NAME.equals(name) ? member.equals("new") : member.equals(name)) {
                return true;
            }
        }
        var outerName = outer.thisClass().asInternalName();
        for (var model : classes) {
            var name = model.thisClass().asInternalName();
            if (name.startsWith(outerName + "$")
                    && name.indexOf('$', outerName.length() + 1) < 0
                    && name.substring(outerName.length() + 1).equals(member)
                    && visibility.visible(listedClassFlags(model))) {
                return true;
            }
        }
        return false;
    }

    private static String attributionKey(ClassModel cm) {
        var cd = cm.thisClass().asSymbol();
        return cd.packageName().isEmpty() ? cd.displayName() : cd.packageName() + "." + cd.displayName();
    }

    private static String attributionKey(String className, String methodName, int ordinal) {
        return SourceModel.attributionKey(className, methodName, ordinal);
    }

    private static String attributionKey(ClassModel cm, FieldModel field) {
        return attributionKey(cm) + "#" + field.fieldName().stringValue();
    }

    private static final int RENDER_BUF_SIZE = 16384;
    private static final String CLASS_EXT = ".class";
    private static final String JAVA_EXT = ".java";
    private static final String META_INF = "META-INF/";
}
