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


/** Matches declaration relationships in one compilation context. */
final class RelationshipSearch implements Closeable {
    private final JavaAnalysis analysis;
    private final Elements elements;
    private final boolean direct;
    private final List<TypeElement> subtypes = new ArrayList<>();
    private final Set<String> supertypes = new HashSet<>();
    private final List<ExecutableElement> overrides = new ArrayList<>();

    private RelationshipSearch(JavaAnalysis analysis, boolean direct) {
        this.analysis = analysis;
        this.elements = analysis.elements();
        this.direct = direct;
    }

    static RelationshipSearch open(Options options, SearchEnvironment environment) throws IOException, ToolException {
        var analysis = JavaAnalysis.open(options, environment, List.of());
        try {
            var search = new RelationshipSearch(analysis, options.direct());
            for (var name : options.subtypes()) {
                search.subtypes.add(analysis.resolveType(name));
            }
            for (var name : options.supertypes()) {
                search.parents(analysis.resolveType(name), search.supertypes, !options.direct());
            }
            for (var name : options.overrides()) {
                var selection = environment.resolveSymbolSelection(name);
                if (selection.member() == null) {
                    throw new ToolException(1, "--overrides requires a method: " + name);
                }
                var type = analysis.resolveType(selection.path());
                var methods = search.elements.getAllMembers(type).stream()
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
            analysis.checkDiagnostics();
            return search;
        } catch (IOException | ToolException | RuntimeException | Error e) {
            analysis.close();
            throw e;
        }
    }

    boolean hasOnlyExactSupertypes() {
        return subtypes.isEmpty() && overrides.isEmpty() && !supertypes.isEmpty();
    }

    List<String> exactSupertypes() {
        return supertypes.stream().sorted().toList();
    }

    void readSourceUnit(String name) throws ToolException {
        analysis.readSourceUnit(name);
    }

    boolean matches(String key, SymbolKind kind) throws ToolException {
        boolean result = matchesDeclaration(key, kind);
        analysis.checkDiagnostics();
        return result;
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
        analysis.close();
    }
}
