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

import com.netflix.tools.jist.CommandLine.Cardinality;
import com.netflix.tools.jist.CommandLine.Completion;
import com.netflix.tools.jist.CommandLine.CompletionRequest;
import com.netflix.tools.jist.CommandLine.ConfigurationException;
import com.netflix.tools.jist.CommandLine.ParsedArguments;
import com.netflix.tools.jist.CommandLine.ToolInvocation;
import com.netflix.tools.jist.CommandLine.ToolOption;

final class JistCommandLine {
    private static final JistCommandLine INSTANCE = new JistCommandLine();

    private final ToolOption classPath = option("--class-path", "PATH", "Where to find unnamed-module classes", "-classpath", "-cp");
    private final ToolOption modulePath = option("--module-path", "PATH", "Where to find application modules", "-p");
    private final ToolOption sourcePath = option("--source-path", "PATH", "Where to find source files", "-sourcepath");
    private final ToolOption moduleSourcePath = option("--module-source-path", "PATH", "Sources for multiple modules");
    private final ToolOption system = option("--system", "JDK|none", "System module location");
    private final ToolOption module = option("--module", "MODULE[,MODULE...]", "Selected compilation modules", "-m");
    private final ToolOption addModules = option("--add-modules", "MODULE[,MODULE...]", "Root modules");
    private final ToolOption addExports = option("--add-exports", "MODULE/PACKAGE=TARGET", "Add a module export");
    private final ToolOption addReads = option("--add-reads", "MODULE=TARGET", "Add a module read edge");
    private final ToolOption limitModules = option("--limit-modules", "MODULE[,MODULE...]", "Restrict observable modules");
    private final ToolOption release = option("--release", "RELEASE", "Compile for the specified Java release");
    private final ToolOption moduleVersion = option("--module-version", "VERSION", "Set the module version");
    private final ToolOption destination = option("-d", "DIRECTORY", "Write class files to DIRECTORY");
    private final ToolOption enablePreview = flag("--enable-preview", "Enable preview language features");
    private final ToolOption publicAccess = flag("-public", "Show public symbols");
    private final ToolOption protectedAccess = flag("-protected", "Show public and protected symbols");
    private final ToolOption packageAccess = flag("-package", "Exclude private symbols");
    private final ToolOption privateAccess = flag("-private", "Show all symbols");
    private final ToolOption kind = ToolOption.builder("--kind")
            .alias("-k")
            .argument("KIND")
            .choices("module", "package", "type", "class", "interface", "enum",
                    "record", "annotation", "method", "field")
            .description("Include symbols of this kind")
            .build();
    private final ToolOption usages = flag("--usages", "Find semantic usages of an exact symbol");
    private final ToolOption source = ToolOption.builder("--source")
            .alias("-s")
            .argument("SCOPE")
            .choices("none", "body", "signature", "doc", "definition", "symbol",
                    "type", "unit")
            .description("Read source at the selected scope")
            .build();
    private final ToolOption color = ToolOption.builder("--color")
            .argument("WHEN")
            .choices("auto", "always", "never")
            .description("Control colored output")
            .build();
    private final ToolOption heading = flag("--heading", "Group results under source headings");
    private final ToolOption noHeading = flag("--no-heading", "Keep one complete result per line");
    private final ToolOption listOnly = flag("-l", "Print matching symbols only");
    private final ToolOption qualifiedPath = flag("--qualified-path", "Show the full source or ClassFile path");
    private final ToolOption lineNumber = flag("--line-number", "Show source line numbers", "-n");
    private final ToolOption noLineNumber = flag("--no-line-number", "Suppress source line numbers", "-N");
    private final ToolOption breakMatches = flag("--break", "Separate non-consecutive source matches");
    private final ToolOption noBreak = flag("--no-break", "Do not separate non-consecutive source matches");
    private final ToolOption pretty = flag("--pretty", "Use headings and color");
    private final ToolOption help = flag("--help", "Print this help message", "-h", "-?");
    private final CommandLine commandLine = CommandLine.builder()
            .description("Search symbols and semantic relationships in the selected compilation context")
            .options(
                    classPath,
                    modulePath,
                    sourcePath,
                    moduleSourcePath,
                    system,
                    module,
                    addModules,
                    addExports,
                    addReads,
                    limitModules,
                    release,
                    moduleVersion,
                    destination,
                    enablePreview,
                    publicAccess,
                    protectedAccess,
                    packageAccess,
                    privateAccess,
                    kind,
                    usages,
                    source,
                    color,
                    heading,
                    noHeading,
                    listOnly,
                    qualifiedPath,
                    lineNumber,
                    noLineNumber,
                    breakMatches,
                    noBreak,
                    pretty,
                    help)
            .operand("SYMBOL|SOURCE-OR-CLASS-FILE", "Symbol prefix, source file, or class file", Cardinality.ZERO_OR_ONE)
            .argumentFiles()
            .javaToolOptions()
            .version(Jist.class.getModule())
            .completion()
            .build();

    private JistCommandLine() {}

    static JistCommandLine instance() {
        return INSTANCE;
    }

    int isSupportedOption(String option) {
        return commandLine.isSupportedOption(option);
    }

    OptionalInt runVersion(PrintWriter out, String... arguments) {
        return commandLine.runVersion("jist", out, arguments);
    }

    OptionalInt runCompletion(PrintWriter out, PrintWriter err,
            Function<CompletionRequest, List<Completion>> completer, Path workingDirectory,
            String... arguments) {
        try {
            return commandLine.runCompletion(out, err, request -> {
                var prepared = commandLine.prepare("jist", request.invocation(), err);
                return completer.apply(new CompletionRequest(prepared, request.current()));
            }, new ToolInvocation(workingDirectory, List.of(arguments)));
        } catch (ConfigurationException e) {
            err.println("Error: " + parseMessage(e.getMessage()));
            return OptionalInt.of(1);
        }
    }

    List<Completion> complete(CompletionRequest request) {
        return commandLine.complete(request);
    }

    boolean completesOptionValue(CompletionRequest request) {
        var arguments = request.invocation().arguments();
        if (arguments.isEmpty()) {
            return false;
        }
        int optionsEnd = arguments.lastIndexOf("--");
        if (optionsEnd >= 0) {
            return false;
        }
        return commandLine.isSupportedOption(arguments.getLast()) > 0;
    }

    Options parse(String... arguments) throws ToolException {
        try {
            return parse(commandLine.parse(arguments));
        } catch (ConfigurationException e) {
            throw new ToolException(1, parseMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            throw new ToolException(1, parseMessage(e.getMessage()));
        }
    }

    Options parse(Path workingDirectory, PrintWriter diagnostics, String... arguments) throws ToolException {
        try {
            return parse(commandLine.parse("jist", new ToolInvocation(workingDirectory, List.of(arguments)),
                    diagnostics));
        } catch (ConfigurationException e) {
            throw new ToolException(1, parseMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            throw new ToolException(1, parseMessage(e.getMessage()));
        }
    }

    private Options parse(ParsedArguments parsed) throws ToolException {
        String target = parsed.operands().isEmpty() ? null : parsed.operands().getFirst();
        if (target != null && target.contains("::")) {
            throw new ToolException(1, "Invalid symbol target '" + target + "'; use '.' between a type and member");
        }

        var kinds = new LinkedHashSet<SymbolKind>();
        for (String value : parsed.values(kind)) {
            kinds.add(SymbolKind.parse(value));
        }
        String selectedSource = last(parsed, source);
        SourceScope sourceScope = selectedSource == null ? null : SourceScope.parse(selectedSource);
        Access selectedAccess = null;
        ColorMode colorMode = ColorMode.AUTO;
        Boolean selectedHeading = null;
        boolean selectedLineNumber = true;
        boolean selectedBreakMatches = false;
        var colorValues = parsed.values(color).iterator();
        for (ToolOption occurrence : parsed.optionOccurrences()) {
            if (occurrence == publicAccess) {
                selectedAccess = Access.PUBLIC;
            } else if (occurrence == protectedAccess) {
                selectedAccess = Access.PROTECTED;
            } else if (occurrence == packageAccess) {
                selectedAccess = Access.PACKAGE;
            } else if (occurrence == privateAccess) {
                selectedAccess = Access.PRIVATE;
            } else if (occurrence == color) {
                colorMode = ColorMode.parse(colorValues.next());
            } else if (occurrence == heading) {
                selectedHeading = Boolean.TRUE;
            } else if (occurrence == noHeading) {
                selectedHeading = Boolean.FALSE;
            } else if (occurrence == lineNumber) {
                selectedLineNumber = true;
            } else if (occurrence == noLineNumber) {
                selectedLineNumber = false;
            } else if (occurrence == breakMatches) {
                selectedBreakMatches = true;
            } else if (occurrence == noBreak) {
                selectedBreakMatches = false;
            } else if (occurrence == pretty) {
                colorMode = ColorMode.ALWAYS;
                selectedHeading = Boolean.TRUE;
            }
        }

        return new Options(
                last(parsed, classPath),
                last(parsed, sourcePath),
                last(parsed, modulePath),
                parsed.values(moduleSourcePath),
                target,
                last(parsed, system),
                last(parsed, module),
                merge(parsed.values(addModules)),
                parsed.values(addExports),
                parsed.values(addReads),
                last(parsed, limitModules),
                selectedAccess,
                Set.copyOf(kinds),
                parsed.contains(usages),
                sourceScope,
                colorMode,
                selectedHeading,
                parsed.contains(listOnly),
                parsed.contains(qualifiedPath),
                selectedLineNumber,
                selectedBreakMatches,
                parsed.contains(help));
    }

    private static String last(ParsedArguments parsed, ToolOption option) {
        List<String> values = parsed.values(option);
        return values.isEmpty() ? null : values.getLast();
    }

    private static String merge(List<String> values) {
        return values.isEmpty() ? null : String.join(",", values);
    }

    private static String parseMessage(String message) {
        int requires = message.indexOf(" requires ");
        return requires < 0 ? message : message.substring(0, requires) + " requires a value";
    }

    private static ToolOption flag(String name, String description, String... aliases) {
        return ToolOption.flag(name, description, aliases);
    }

    private static ToolOption option(String name, String argument, String description,
            String... aliases) {
        return ToolOption.option(name, argument, description, aliases);
    }
}
