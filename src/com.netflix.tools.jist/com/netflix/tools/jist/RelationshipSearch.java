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
    private final Options options;
    private final SearchEnvironment environment;
    private final boolean direct;
    private JavaAnalysis analysis;
    private Elements elements;
    private final Set<String> subtypes = new HashSet<>();
    private final Set<String> supertypes = new HashSet<>();
    private final List<ExecutableElement> overrides = new ArrayList<>();
    private Set<String> compiledSubtypes = Set.of();
    private Set<String> compiledOverrideOwners = Set.of();
    private Set<String> sourceAffected = Set.of();

    private RelationshipSearch(Options options, SearchEnvironment environment) {
        this.options = options;
        this.environment = environment;
        this.direct = options.direct();
    }

    private JavaAnalysis analysis() throws IOException, ToolException {
        if (analysis == null) {
            analysis = JavaAnalysis.open(options, environment, List.of());
            elements = analysis.elements();
        }
        return analysis;
    }

    static RelationshipSearch open(Options options, SearchEnvironment environment) throws IOException, ToolException {
        var search = new RelationshipSearch(options, environment);
        try {
            for (var name : options.subtypes()) {
                if (!environment.classTargetExists(name)) {
                    throw new ToolException(1, "Type not found: " + name);
                }
                search.subtypes.add(name);
            }
            for (var name : options.supertypes()) {
                search.parents(search.analysis().resolveType(name), search.supertypes, !options.direct());
            }
            for (var name : options.overrides()) {
                boolean found = false;
                for (var selection : ExecutableTargets.resolve(options, environment, name)) {
                    if (selection.member() == null) {
                        continue;
                    }
                    var type = search.analysis().resolveType(selection.path());
                    var methods = search.elements.getAllMembers(type).stream()
                            .filter(element -> element.getKind() == ElementKind.METHOD)
                            .map(ExecutableElement.class::cast)
                            .filter(method -> method.getSimpleName().contentEquals(selection.member()))
                            .filter(RelationshipSearch::overridable)
                            .toList();
                    search.overrides.addAll(methods);
                    found |= !methods.isEmpty();
                }
                if (!found) {
                    throw new ToolException(1, "No overridable method: " + name);
                }
            }
            if (!search.subtypes.isEmpty() || !search.overrides.isEmpty()) {
                try (var hierarchy = ClassHierarchy.read(options, environment)) {
                    search.sourceAffected = hierarchy.sourceAffected();
                    if (!search.sourceAffected.isEmpty()) {
                        for (var source : hierarchy.sourceFiles().stream().sorted().toList()) {
                            search.analysis().readSourceFile(source);
                        }
                    }
                    if (!search.subtypes.isEmpty()) {
                        search.compiledSubtypes = hierarchy.subtypes(search.subtypes, options.direct());
                    }
                    if (!search.overrides.isEmpty()) {
                        var owners = search.overrides.stream()
                                .map(method -> (TypeElement) method.getEnclosingElement())
                                .map(type -> type.getQualifiedName().toString())
                                .collect(Collectors.toUnmodifiableSet());
                        search.compiledOverrideOwners = hierarchy.overrideOwners(owners);
                    }
                }
            }
            if (search.analysis != null) {
                search.analysis.checkDiagnostics();
            }
            return search;
        } catch (IOException | ToolException | RuntimeException | Error e) {
            search.close();
            throw e;
        }
    }

    boolean hasOnlyExactSupertypes() {
        return subtypes.isEmpty() && overrides.isEmpty() && !supertypes.isEmpty();
    }

    List<String> exactSupertypes() {
        return supertypes.stream().sorted().toList();
    }

    void readSourceUnit(String name) throws IOException, ToolException {
        analysis().readSourceUnit(name);
    }

    boolean mayMatchCompiledClass(String name) {
        return compiledSubtypes.contains(name) || compiledOverrideOwners.contains(name) || supertypes.contains(name)
                || sourceAffected.contains(name);
    }

    boolean matches(String key, SymbolKind kind, boolean compiled) throws IOException, ToolException {
        boolean result = matchesDeclaration(key, kind, compiled);
        if (analysis != null) {
            analysis.checkDiagnostics();
        }
        return result;
    }

    private boolean matchesDeclaration(String key, SymbolKind kind, boolean compiled) throws IOException, ToolException {
        int member = key.indexOf('#');
        if (member < 0) {
            if (kind == SymbolKind.MODULE || kind == SymbolKind.PACKAGE) {
                return false;
            }
            if (supertypes.contains(key)) {
                return true;
            }
            if (compiled && !sourceAffected.contains(key)) {
                return compiledSubtypes.contains(key);
            }
            if (subtypes.isEmpty()) {
                return false;
            }
            var candidate = analysis().elements().getTypeElement(key);
            if (candidate == null) {
                return false;
            }
            var ancestors = new HashSet<String>();
            parents(candidate, ancestors, !direct);
            return subtypes.stream().anyMatch(ancestors::contains);
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
        var ownerName = key.substring(0, member);
        if (compiled && !compiledOverrideOwners.contains(ownerName) && !sourceAffected.contains(ownerName)) {
            return false;
        }
        var owner = elements.getTypeElement(ownerName);
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
        if (analysis != null) {
            analysis.close();
        }
    }
}
