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

import com.sun.source.tree.ClassTree;

public record Options(
        String classPath,
        String sourcePath,
        String modulePath,
        List<String> moduleSourcePaths,
        String target,
        String system,
        String module,
        String addModules,
        List<String> addExports,
        List<String> addReads,
        String limitModules,
        Access access,
        Set<SymbolKind> kinds,
        boolean usages,
        SourceScope source,
        ColorMode color,
        Boolean heading,
        boolean listOnly,
        boolean qualifiedPath,
        boolean lineNumber,
        boolean breakMatches,
        boolean help) {

    boolean listsMethods() {
        return kinds.contains(SymbolKind.METHOD);
    }

    boolean listsFields() {
        return kinds.contains(SymbolKind.FIELD);
    }

    boolean listsModules() {
        return kinds.contains(SymbolKind.MODULE);
    }

    boolean listsPackages() {
        return kinds.contains(SymbolKind.PACKAGE);
    }

    boolean listsType(ClassModel model) {
        if (kinds.isEmpty()) {
            return true;
        }
        return kinds.stream().anyMatch(kind -> kind.matches(model));
    }

    boolean listsType(ClassTree tree) {
        if (kinds.isEmpty()) {
            return true;
        }
        return kinds.stream().anyMatch(kind -> kind.matches(tree));
    }

    boolean requiresClassModel() {
        return listsMethods() || listsFields() || !kinds.isEmpty();
    }

    Options withKinds(Set<SymbolKind> selectedKinds) {
        return withTargetAndKinds(target, selectedKinds);
    }

    Options withSource(SourceScope selectedSource) {
        return new Options(
                classPath,
                sourcePath,
                modulePath,
                moduleSourcePaths,
                target,
                system,
                module,
                addModules,
                addExports,
                addReads,
                limitModules,
                access,
                kinds,
                usages,
                selectedSource,
                color,
                heading,
                listOnly,
                qualifiedPath,
                lineNumber,
                breakMatches,
                help);
    }

    Options withTargetAndKinds(String selectedTarget, Set<SymbolKind> selectedKinds) {
        return new Options(
                classPath,
                sourcePath,
                modulePath,
                moduleSourcePaths,
                selectedTarget,
                system,
                module,
                addModules,
                addExports,
                addReads,
                limitModules,
                access,
                Set.copyOf(selectedKinds),
                usages,
                source,
                color,
                heading,
                listOnly,
                qualifiedPath,
                lineNumber,
                breakMatches,
                help);
    }

    public static Options parse(String[] args) throws ToolException {
        return JistCommandLine.instance().parse(args);
    }

    static void printUsage(PrintWriter out) {
        out
                .print("""
                Usage: jist [options] [<symbol|source-or-class-file>]

                Search symbols and semantic relationships in the selected compilation context.

                Input:
                  <symbol|source-or-class-file>           Exact simple name, qualified symbol
                                                            prefix, source file, or class file
                  @<file>                                 Read options from file
                  -cp, -classpath, --class-path <path>    Where to find unnamed-module classes
                  -p, --module-path <path>                Where to find application modules
                  -sourcepath, --source-path <path>       Where to find source files
                                                            (directories and source jars, searched
                                                            in order as with javac)
                  --module-source-path <path>             Sources for multiple modules
                                                            (root, wildcard pattern, or M=path as with javac)

                Module resolution:
                  --system <jdk>|none                     System module location
                                                            (default: running JDK)
                  -m, --module <M,...>                    Selected compilation modules
                  --add-modules <M,...>                   Root modules (default: java.se)
                                                            (also ALL-SYSTEM, ALL-MODULE-PATH)
                  --add-exports <M/P=T>                   Export package P in module M to T
                  --add-reads <M=T>                       Add a read edge from module M to T
                  --limit-modules <M,...>                 Restrict all observable modules

                Visibility:
                  -public                                 Show public symbols
                  -protected                              Show public and protected symbols
                  -package                                Exclude private symbols
                  -private                                Show all symbols

                Search:
                  -k, --kind <kind>                       Include symbols of this kind; repeatable
                                                            module, package, type, class, interface,
                                                            enum, record, annotation, method, or field

                Relationships:
                  --usages                                Find semantic usages of an exact symbol

                Output:
                  --heading                               Group results under source headings
                  --no-heading                            Keep one complete result per line
                  --color <when>                          Color: auto, always, or never
                  --pretty                                Use headings and color
                  -l                                      Print matching symbols only
                  --qualified-path                        Show the full source or ClassFile path
                  -N, --no-line-number                    Suppress source line numbers
                  --break                                 Separate non-consecutive source matches

                Source enrichment:
                  -s, --source <scope>                    Read source at the selected scope
                                                            none, body, signature (default), doc,
                                                            definition, symbol, type, or unit
                  -h, -?, --help                          Print this help message
                """);
        out.flush();
    }
}
