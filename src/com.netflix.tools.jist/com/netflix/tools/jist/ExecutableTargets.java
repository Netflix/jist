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

final class ExecutableTargets extends PrintWriter implements Output.SymbolOutput {
    private final String target;
    private final String ownerSuffix;
    private final String member;
    private final Set<SymbolSelection> values = new LinkedHashSet<>();

    private ExecutableTargets(String target) {
        super(Writer.nullWriter());
        this.target = target;
        int separator = target.lastIndexOf('.');
        this.ownerSuffix = separator < 0 ? null : target.substring(0, separator);
        this.member = separator < 0 ? null : target.substring(separator + 1);
    }

    static List<SymbolSelection> resolve(Options options, SearchEnvironment environment, String target)
            throws IOException, ToolException {
        var selection = environment.resolveSymbolSelection(target);
        if (selection.member() != null || environment.classTargetExists(selection.path())) {
            return List.of(selection);
        }
        var targets = new ExecutableTargets(target);
        var declarationTarget = DeclarationSearch.simpleNameTarget(target) ? target : null;
        var targetOptions = options.executableTargetSearch(declarationTarget);
        new DeclarationSearch(new SearchEnvironment(targetOptions)).listClasses(targetOptions, targets);
        return List.copyOf(targets.values);
    }

    @Override
    public boolean listOnly() {
        return true;
    }

    @Override
    public void symbol(SymbolKind kind, String name, String location) {
        if (kind != SymbolKind.METHOD && !kind.isType()) {
            return;
        }
        int parameters = name.indexOf('(');
        var qualifiedName = parameters < 0 ? name : name.substring(0, parameters);
        if (kind.isType()) {
            if (matches(qualifiedName, target)) {
                values.add(new SymbolSelection(qualifiedName, null));
            }
            if (ownerSuffix != null && matches(qualifiedName, ownerSuffix)) {
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

    private static boolean matches(String name, String target) {
        return DeclarationSearch.simpleNameTarget(target)
                ? UsageSearch.terminalName(name).equals(target)
                : name.equals(target) || name.endsWith("." + target);
    }
}
