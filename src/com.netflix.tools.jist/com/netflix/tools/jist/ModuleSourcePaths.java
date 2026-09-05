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

import java.util.regex.Pattern;

public record ModuleSourcePaths(Map<String, List<Path>> modulePaths, List<ModuleSourcePattern> patterns) {

    public static ModuleSourcePaths parse(List<String> specs) throws ToolException {
        var modulePaths = new LinkedHashMap<String, List<Path>>();
        List<ModuleSourcePattern> patterns = null;
        for (var spec : specs) {
            int eq = spec.indexOf('=');
            var moduleName = eq > 0 ? spec.substring(0, eq) : "";
            if (moduleName.matches("[\\p{Alnum}$_.]+")) {
                if (modulePaths.containsKey(moduleName)) {
                    throw new ToolException(1, "--module-source-path specified more than once for module " + moduleName);
                }
                var paths = new ArrayList<Path>();
                for (var value : spec.substring(eq + 1).split(Pattern.quote(File.pathSeparator))) {
                    final Path path;
                    try {
                        path = Path.of(value);
                    } catch (InvalidPathException e) {
                        throw new ToolException(1, "Invalid module source path: " + value);
                    }
                    if (!Files.isDirectory(path)) {
                        throw new ToolException(1, "Module source path is not a directory: " + path);
                    }
                    paths.add(path);
                }
                modulePaths.put(moduleName, List.copyOf(paths));
            } else {
                if (patterns != null) {
                    throw new ToolException(1, "--module-source-path specified more than once with a pattern argument");
                }
                patterns = parsePattern(spec);
            }
        }
        return new ModuleSourcePaths(Map.copyOf(modulePaths),
                patterns != null ? List.copyOf(patterns) : List.of());
    }

    public List<Path> forModule(String moduleName) {
        var explicit = modulePaths.get(moduleName);
        if (explicit != null) {
            return explicit;
        }

        var paths = new ArrayList<Path>();
        for (var pattern : patterns) {
            var path = pattern.pathFor(moduleName);
            if (Files.isDirectory(path)) {
                paths.add(path);
            }
        }
        if (paths.stream().noneMatch(p -> Files.isRegularFile(p.resolve("module-info.java")))) {
            return List.of();
        }
        return List.copyOf(paths);
    }

    private static List<ModuleSourcePattern> parsePattern(String value) throws ToolException {
        var expanded = new ArrayList<String>();
        for (var segment : value.split(Pattern.quote(File.pathSeparator))) {
            expandBraces(segment, expanded);
        }

        var result = new ArrayList<ModuleSourcePattern>();
        for (var segment : expanded) {
            int star = segment.indexOf('*');
            if (star < 0) {
                result.add(new ModuleSourcePattern(path(segment), null));
                continue;
            }
            if (star == 0 || !isSeparator(segment.charAt(star - 1))) {
                throw new ToolException(1, "Illegal use of * in " + segment);
            }
            int afterStar = star + 1;
            Path suffix = null;
            if (afterStar < segment.length()) {
                if (!isSeparator(segment.charAt(afterStar)) || segment.indexOf('*', afterStar) >= 0) {
                    throw new ToolException(1, "Illegal use of * in " + segment);
                }
                suffix = path(segment.substring(afterStar + 1));
            }
            result.add(new ModuleSourcePattern(path(segment.substring(0, star - 1)), suffix));
        }
        return result;
    }

    private static Path path(String value) throws ToolException {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new ToolException(1, "Invalid module source path: " + value);
        }
    }

    private static boolean isSeparator(char ch) {
        return ch == File.separatorChar || ch == '/';
    }

    private static void expandBraces(String value, List<String> result) throws ToolException {
        int open = value.indexOf('{');
        if (open < 0) {
            if (value.indexOf('}') >= 0) {
                throw new ToolException(1, "Mismatched braces in module source path");
            }
            result.add(value);
            return;
        }

        int depth = 1;
        int close = -1;
        for (int i = open + 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            throw new ToolException(1, "Mismatched braces in module source path");
        }

        var prefix = value.substring(0, open);
        var choices = value.substring(open + 1, close);
        var suffix = value.substring(close + 1);
        int choiceStart = 0;
        depth = 0;
        for (int i = 0; i <= choices.length(); i++) {
            char ch = i < choices.length() ? choices.charAt(i) : ',';
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                expandBraces(prefix + choices.substring(choiceStart, i) + suffix, result);
                choiceStart = i + 1;
            }
        }
    }
}

record ModuleSourcePattern(Path prefix, Path suffix) {
    Path pathFor(String moduleName) {
        var path = prefix.resolve(moduleName);
        return suffix != null ? path.resolve(suffix) : path;
    }
}
