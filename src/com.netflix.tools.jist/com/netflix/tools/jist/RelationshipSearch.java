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

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic.Kind;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.SourceModel.StringJavaFileObject;
import com.sun.source.util.JavacTask;

/** Matches declaration relationships in one compilation context. */
final class RelationshipSearch implements Closeable {
    private final StandardJavaFileManager fileManager;
    private final Elements elements;
    private final DiagnosticCollector<JavaFileObject> diagnostics;
    private final boolean direct;
    private final List<TypeElement> subtypes = new ArrayList<>();
    private final Set<String> supertypes = new HashSet<>();
    private final List<ExecutableElement> overrides = new ArrayList<>();

    private RelationshipSearch(StandardJavaFileManager fileManager, Elements elements, DiagnosticCollector<JavaFileObject> diagnostics,
            boolean direct) {
        this.fileManager = fileManager;
        this.elements = elements;
        this.diagnostics = diagnostics;
        this.direct = direct;
    }

    static RelationshipSearch open(Options options, SearchEnvironment environment) throws IOException, ToolException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        try {
            var arguments = new ArrayList<>(environment.compilerOptions(true, false));
            arguments.add("-Xprefer:source");
            var descriptors = environment.moduleSourceInputs()
                    .stream()
                    .map(input -> input.location().resolve("module-info.java"))
                    .filter(Files::isRegularFile)
                    .toList();
            if (descriptors.size() > 1) {
                int sourcePath = arguments.indexOf("--source-path");
                if (sourcePath >= 0) {
                    arguments.subList(sourcePath, sourcePath + 2).clear();
                }
                for (var path : options.moduleSourcePaths()) {
                    arguments.addAll(List.of("--module-source-path", path));
                }
                // javac requires an output location for multiple modules; analyze never writes ClassFiles.
                manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(Path.of(System.getProperty("java.io.tmpdir"))));
            }
            Iterable<? extends JavaFileObject> units = descriptors.isEmpty() ? List.of(new StringJavaFileObject("__JistRelationships.java", "".toCharArray())) : manager.getJavaFileObjectsFromPaths(descriptors);
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, arguments, null, units);
            task.parse();
            task.analyze();
            var search = new RelationshipSearch(manager, task.getElements(), diagnostics, options.direct());
            search.checkDiagnostics();
            for (var name : options.subtypes()) {
                search.subtypes.add(search.resolveType(name, environment));
            }
            for (var name : options.supertypes()) {
                var type = search.resolveType(name, environment);
                search.parents(type, search.supertypes, !options.direct());
            }
            for (var name : options.overrides()) {
                var selection = environment.resolveSymbolSelection(name);
                if (selection.member() == null) {
                    throw new ToolException(1, "--overrides requires a method: " + name);
                }
                var type = search.resolveType(selection.path(), environment);
                var methods = search.elements.getAllMembers(type)
                                    .stream()
                                    .filter(element -> element.getKind() == ElementKind.METHOD)
                                    .map(ExecutableElement.class::cast)
                                    .filter(method -> method.getSimpleName().contentEquals(selection.member()))
                                    .filter(RelationshipSearch::overridable)
                                    .toList();
                if (methods.isEmpty()) {
                    throw new ToolException(1, "No overridable method: " + name);
                }
                search.overrides.addAll(methods);
            }
            search.checkDiagnostics();
            return search;
        } catch (IOException | ToolException | RuntimeException | Error e) {
            manager.close();
            throw e;
        }
    }

    private TypeElement resolveType(String name, SearchEnvironment environment) throws IOException, ToolException {
        var selection = environment.resolveSymbolSelection(name);
        if (selection.member() != null || !environment.classTargetExists(name)) {
            throw new ToolException(1, "Type not found: " + name);
        }
        var source = environment.resolveSourceFile(name);
        if (source != null) {
            var fileName = source.getFileName().toString();
            var structure = SourceModel.parse(Files.readAllBytes(source)).structure(fileName, Visibility.ALL, false);
            readSourceUnit(DeclarationSearch.sourceUnitName(structure, fileName));
        }
        var type = elements.getTypeElement(name);
        if (type == null) {
            throw new ToolException(1, "Cannot resolve type: " + name);
        }
        return type;
    }

    void readSourceUnit(String name) throws ToolException {
        elements.getTypeElement(name);
        checkDiagnostics();
    }

    boolean matches(String key, SymbolKind kind) throws ToolException {
        boolean result = matchesDeclaration(key, kind);
        checkDiagnostics();
        return result;
    }

    private void checkDiagnostics() throws ToolException {
        for (var diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Kind.ERROR) {
                throw new ToolException(2, "Cannot resolve relationships: " + diagnostic.getMessage(Locale.ROOT));
            }
        }
    }

    private boolean matchesDeclaration(String key, SymbolKind kind) {
        int member = key.indexOf('#');
        if (member < 0) {
            if (kind == SymbolKind.MODULE || kind == SymbolKind.PACKAGE) {
                return false;
            }
            if (supertypes.contains(key)) {
                return true;
            }
            if (subtypes.isEmpty()) {
                return false;
            }
            var candidate = elements.getTypeElement(key);
            if (candidate == null) {
                return false;
            }
            var ancestors = new HashSet<String>();
            parents(candidate, ancestors, !direct);
            return subtypes.stream().anyMatch(target -> ancestors.contains(target.getQualifiedName().toString()));
        }
        if (kind != SymbolKind.METHOD || overrides.isEmpty()) {
            return false;
        }
        int ordinal = key.lastIndexOf('@');
        if (ordinal < member) {
            return false;
        }
        var name = key.substring(member + 1, ordinal);
        if (overrides.stream().noneMatch(target -> target.getSimpleName().contentEquals(name))) {
            return false;
        }
        var owner = elements.getTypeElement(key.substring(0, member));
        if (owner == null) {
            return false;
        }
        var methods = owner.getEnclosedElements()
                           .stream()
                           .filter(element -> element.getKind() == ElementKind.METHOD)
                           .map(ExecutableElement.class::cast)
                           .filter(method -> method.getSimpleName().contentEquals(name))
                           .toList();
        int index = Integer.parseInt(key.substring(ordinal + 1));
        if (index >= methods.size()) {
            return false;
        }
        var method = methods.get(index);
        for (var target : overrides) {
            if (elements.overrides(method, target, owner) && (!direct || !interveningOverride(method, target, owner))) {
                return true;
            }
        }
        return false;
    }

    private boolean interveningOverride(ExecutableElement method, ExecutableElement target, TypeElement owner) {
        var ancestors = new HashSet<String>();
        parents(owner, ancestors, true);
        for (var name : ancestors) {
            var ancestor = elements.getTypeElement(name);
            if (ancestor == null) {
                continue;
            }
            for (var element : ancestor.getEnclosedElements()) {
                if (element instanceof ExecutableElement intermediate && elements.overrides(method, intermediate, owner) && elements.overrides(intermediate, target, ancestor)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void parents(TypeElement type, Set<String> names, boolean transitive) {
        var parents = new ArrayList<TypeMirror>(type.getInterfaces());
        if (type.getSuperclass() instanceof DeclaredType superclass) {
            parents.add(superclass);
        }
        for (var parent : parents) {
            if (parent instanceof DeclaredType declared
                    && declared.asElement() instanceof TypeElement element
                    && names.add(element.getQualifiedName().toString())
                    && transitive) {
                parents(element, names, true);
            }
        }
    }

    private static boolean overridable(ExecutableElement method) {
        var modifiers = method.getModifiers();
        return !modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.PRIVATE) && !modifiers.contains(Modifier.FINAL);
    }

    @Override
    public void close() throws IOException {
        fileManager.close();
    }
}
