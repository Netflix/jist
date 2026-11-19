/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.tools.jist;

import module java.base;

final class SymbolTargets extends PrintWriter implements Output.SymbolOutput {
    private final String target;
    private final String ownerSuffix;
    private final String member;
    private final boolean inheritedMembers;
    private final Set<SymbolSelection> values = new LinkedHashSet<>();

    private SymbolTargets(String target, boolean inheritedMembers) {
        super(Writer.nullWriter());
        this.target = target;
        this.inheritedMembers = inheritedMembers;
        int separator = target.lastIndexOf('.');
        this.ownerSuffix = separator < 0 ? null : target.substring(0, separator);
        this.member = separator < 0 ? null : target.substring(separator + 1);
    }

    static List<SymbolSelection> resolve(Options options, SearchEnvironment environment, String target,
            Set<SymbolKind> kinds) throws IOException, ToolException {
        return resolve(options, environment, target, kinds, false);
    }

    static List<SymbolSelection> resolve(Options options, SearchEnvironment environment, String target,
            Set<SymbolKind> kinds, boolean inheritedMembers) throws IOException, ToolException {
        var selection = environment.resolveSymbolSelection(target, !Options.listsOnlyTypes(kinds));
        if (!DeclarationSearch.simpleNameTarget(target)
                && (selection.member() != null || environment.classTargetExists(selection.path()))) {
            return List.of(selection);
        }
        var targets = new SymbolTargets(target, inheritedMembers);
        var declarationTarget = inheritedMembers && !DeclarationSearch.simpleNameTarget(target)
                ? null : target.substring(target.lastIndexOf('.') + 1);
        var targetOptions = options.targetSearch(declarationTarget,
                kinds.isEmpty() ? EnumSet.of(SymbolKind.TYPE, SymbolKind.METHOD, SymbolKind.FIELD) : kinds);
        new DeclarationSearch(environment.targetSearch(declarationTarget, targetOptions.kinds()))
                .listClasses(targetOptions, targets, targets::mayMatchClassResource);
        return List.copyOf(targets.values);
    }

    @Override
    public boolean listOnly() {
        return true;
    }

    @Override
    public void symbol(SymbolKind kind, String name, Output.SymbolLocation location, Output.SourceRange source) {
        if (kind != SymbolKind.METHOD && kind != SymbolKind.FIELD && !kind.isType()) {
            return;
        }
        int parameters = name.indexOf('(');
        var qualifiedName = parameters < 0 ? name : name.substring(0, parameters);
        if (kind.isType()) {
            if (matches(qualifiedName, target)) {
                values.add(new SymbolSelection(qualifiedName, null));
            }
            if (ownerSuffix != null
                    && inheritedMembers
                    && matches(qualifiedName, ownerSuffix)) {
                values.add(new SymbolSelection(qualifiedName, member));
            }
            return;
        }
        if (!matches(qualifiedName, target)) {
            return;
        }
        int memberSeparator = qualifiedName.lastIndexOf('.');
        if (memberSeparator > 0) {
            values.add(new SymbolSelection(
                    qualifiedName.substring(0, memberSeparator),
                    qualifiedName.substring(memberSeparator + 1)));
        }
    }

    private boolean mayMatchClassResource(String resource) {
        int end = resource.length() - ".class".length();
        return ownerSuffix == null || matchesResourceSuffix(resource, end, target) || matchesResourceSuffix(resource, end, ownerSuffix);
    }

    private static boolean matches(String name, String target) {
        return name.equals(target) || name.endsWith("." + target);
    }

    // Resource separators encode nesting; symbol names can also contain literal dollar signs.
    private static boolean matchesResourceSuffix(String name, int end, String target) {
        int start = end - target.length();
        if (start < 0 || start > 0 && !separator(name.charAt(start - 1))) {
            return false;
        }
        for (int i = 0; i < target.length(); i++) {
            char actual = name.charAt(start + i);
            if ((separator(actual) ? '.' : actual) != target.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean separator(char value) {
        return value == '.' || value == '/' || value == '$';
    }
}
