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

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;

final class ReferencedTypes {
    static void list(Options options, SearchEnvironment environment, PrintWriter out) throws IOException, ToolException {
        var target = options.referencedTypes();
        var file = Path.of(target).toAbsolutePath().normalize();
        Path sourceFile = null;
        SearchEnvironment.ResolvedClass compiled;
        if (Files.isRegularFile(file) && target.endsWith(".class")) {
            compiled = new SearchEnvironment.ResolvedClass(SearchEnvironment.readClassGroup(file), SearchEnvironment.unitLocation(file));
        } else if (Files.isRegularFile(file) && target.endsWith(".java")) {
            sourceFile = file;
            compiled = null;
            var structure = SourceModel.parse(Files.readAllBytes(file)).structure(file.getFileName().toString(), Visibility.ALL, false);
            for (var name : structure.classNames()) {
                compiled = resolve(options, environment, name);
                if (compiled != null) break;
            }
        } else {
            compiled = resolve(options, environment, target);
            if (compiled == null) sourceFile = environment.resolveSourceFile(target);
        }
        var results = new LinkedHashMap<String, SymbolKind>();
        if (compiled == null) {
            if (sourceFile == null) throw new ToolException(1, "Class or Java source not found: " + target);
            try (var analysis = JavaAnalysis.open(options, environment, List.of(sourceFile))) {
                for (var unit : analysis.units()) {
                    if (unit.getSourceFile().toUri().equals(sourceFile.toUri())) {
                        new Scanner(options, analysis, unit, results).scan(unit, null);
                    }
                }
                analysis.checkDiagnostics();
            }
        } else {
            try (var analysis = JavaAnalysis.open(options, environment, List.of());
                    var sources = SourceLookup.open(options.sourcePath(), environment.moduleSourceRoots(), options.system())) {
                for (var name : ClassReferences.collect(compiled.classes())) {
                    var referenced = resolve(options, environment, name);
                    var model = referenced == null ? null : referenced.classes().stream()
                            .filter(candidate -> UsageSearch.displayClassName(candidate).equals(name)).findFirst().orElse(null);
                    if (model != null) {
                        var kind = SymbolKind.classKind(model);
                        if ((options.kinds().isEmpty() || options.kinds().contains(SymbolKind.TYPE) || options.kinds().contains(kind))
                                && (options.visibility() == null || options.visibility().visible(ClassFileRenderer.listedClassFlags(model)))) {
                            results.putIfAbsent(name, kind);
                        }
                    } else {
                        var type = analysis.elements().getTypeElement(name);
                        if (type == null) throw new ToolException(2, "Cannot resolve referenced type: " + name);
                        add(options, type, results);
                    }
                }
                if (options.source() != SourceScope.NONE) {
                    var visited = new HashSet<String>();
                    for (var model : compiled.classes()) {
                        var name = model.thisClass().asInternalName();
                        int slash = name.lastIndexOf('/');
                        var sourceName = model.findAttribute(Attributes.sourceFile()).map(value -> value.sourceFile().stringValue())
                                .orElse(name.substring(slash + 1).split("\\$")[0] + ".java");
                        var relative = (slash < 0 ? "" : name.substring(0, slash + 1)) + sourceName;
                        SourceUnit source = sourceFile == null ? sources.find(relative, compiled.location(), name + ".class")
                                : new SourceUnit(sourceFile.toUri().toString(), Files.readAllBytes(sourceFile), true, sourceFile, null);
                        if (source == null || !source.javaSource() || !visited.add(source.origin())) continue;
                        var type = analysis.elements().getTypeElement(name.replace('/', '.').replace('$', '.'));
                        var module = type == null ? null : analysis.elements().getModuleOf(type);
                        var moduleName = module == null || module.isUnnamed() ? null : module.getQualifiedName().toString();
                        supplement(options, environment, source, relative.substring(0, relative.length() - 5).replace('/', '.'), moduleName, results);
                    }
                }
            }
        }
        results.forEach((name, kind) -> out.println(kind.optionName() + " " + name));
        out.flush();
    }

    private static SearchEnvironment.ResolvedClass resolve(Options options, SearchEnvironment environment, String name)
            throws IOException, ToolException {
        var compiled = SearchEnvironment.readsUnnamedModule(options)
                ? SearchEnvironment.resolveClass(name, SearchEnvironment.parsePathList(options.classPath())) : null;
        if (compiled == null) compiled = environment.resolveModuleClass(name);
        if (compiled == null) compiled = environment.resolveSystemClass(name);
        return compiled;
    }

    private static void supplement(Options options, SearchEnvironment environment, SourceUnit source, String binaryName,
            String moduleName, Map<String, SymbolKind> results) throws IOException, ToolException {
        try (var analysis = JavaAnalysis.openAttached(environment, source, binaryName, moduleName)) {
            for (var unit : analysis.units()) {
                new Scanner(options, analysis, unit, results).scan(unit, null);
            }
        } catch (ToolException e) {
            if (e.exitCode != 2) throw e;
            // Optional source attribution must not discard ClassFile-confirmed references.
        }
    }

    private static void add(Options options, TypeElement type, Map<String, SymbolKind> results) {
        var name = type.getQualifiedName().toString();
        if (name.isEmpty()) return;
        var kind = switch (type.getKind()) {
            case CLASS -> SymbolKind.CLASS;
            case INTERFACE -> SymbolKind.INTERFACE;
            case ENUM -> SymbolKind.ENUM;
            case RECORD -> SymbolKind.RECORD;
            case ANNOTATION_TYPE -> SymbolKind.ANNOTATION;
            default -> throw new AssertionError(type.getKind());
        };
        if (!options.kinds().isEmpty() && !options.kinds().contains(SymbolKind.TYPE) && !options.kinds().contains(kind)) return;
        if (options.visibility() != null && !options.visibility().visibleModifiers(type.getModifiers(), false)) return;
        results.putIfAbsent(name, kind);
    }

    private static final class Scanner extends TreePathScanner<Void, Void> {
        private final Options options;
        private final JavaAnalysis analysis;
        private final CompilationUnitTree unit;
        private final Map<String, SymbolKind> results;

        Scanner(Options options, JavaAnalysis analysis, CompilationUnitTree unit, Map<String, SymbolKind> results) {
            this.options = options;
            this.analysis = analysis;
            this.unit = unit;
            this.results = results;
        }

        @Override
        public Void visitImport(ImportTree tree, Void unused) {
            return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            reference(tree);
            return super.visitIdentifier(tree, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            reference(tree);
            return super.visitMemberSelect(tree, unused);
        }

        private void reference(Tree tree) {
            var positions = analysis.trees().getSourcePositions();
            if (positions.getStartPosition(unit, tree) < 0 || positions.getEndPosition(unit, tree) < 0) {
                return;
            }
            var element = analysis.trees().getElement(getCurrentPath());
            if (element instanceof TypeElement type) {
                emit(type);
            } else if (element != null && member(element.getKind()) && element.getEnclosingElement() instanceof TypeElement owner) {
                emit(owner);
            }
        }

        private static boolean member(ElementKind kind) {
            return kind == ElementKind.METHOD || kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT;
        }

        private void emit(TypeElement type) {
            add(options, type, results);
        }
    }
}
