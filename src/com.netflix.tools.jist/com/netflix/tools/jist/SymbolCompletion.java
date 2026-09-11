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

import com.netflix.tools.jist.CommandLine.Completion;
import com.netflix.tools.jist.CommandLine.CompletionRequest;
import com.netflix.tools.jist.Output.SymbolOutput;

import static com.netflix.tools.jist.DeclarationSearch.defaultKinds;
import static com.netflix.tools.jist.UsageSearch.terminalName;

final class SymbolCompletion {
    private SymbolCompletion() {}

    static List<Completion> complete(CompletionRequest request) {
        try {
            var options = Options.parse(request.invocation()
                    .arguments()
                    .toArray(String[]::new));
            if (options.target() != null) {
                return List.of();
            }
            var kinds = options.kinds().isEmpty() ? defaultKinds() : options.kinds();
            var selected = options.withSource(null).withTargetAndKinds(parent(request.current()), kinds);
            var output = new CompletionOutput(request.current());
            new DeclarationSearch(new SearchEnvironment(selected)).listClasses(selected, output);
            return output.completions();
        } catch (IOException | ToolException _) {
            return List.of();
        }
    }

    private static String parent(String current) {
        int separator = current.lastIndexOf('.');
        return separator <= 0 ? null : current.substring(0, separator);
    }

    private static final class CompletionOutput extends PrintWriter implements SymbolOutput {
        private final String current;
        private final Map<String, Completion> completions = new TreeMap<>();

        private CompletionOutput(String current) {
            super(Writer.nullWriter());
            this.current = current;
        }

        @Override
        public boolean listOnly() {
            return true;
        }

        @Override
        public void symbol(SymbolKind kind, String name, String location) {
            String value = withoutParameters(name);
            if (value.startsWith(current) || terminalName(value).startsWith(current)) {
                completions.putIfAbsent(value, new Completion(value, kind.optionName()));
            }
        }

        private List<Completion> completions() {
            return List.copyOf(completions.values());
        }

        private static String withoutParameters(String symbol) {
            int parameters = symbol.indexOf('(');
            return parameters < 0 ? symbol : symbol.substring(0, parameters);
        }
    }
}
