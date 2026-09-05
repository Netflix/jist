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

import java.util.spi.ToolProvider;
import javax.tools.OptionChecker;

import com.netflix.tools.jist.Output.InteractivePrintWriter;
import com.netflix.tools.jist.Output.SourceWarningPrintWriter;
import com.netflix.tools.jist.Output.SourceWarnings;

import static com.netflix.tools.jist.DeclarationSearch.defaultKinds;
import static com.netflix.tools.jist.DeclarationSearch.simpleNameTarget;

/**
 * Searches Java symbols, source context, and semantic relationships.
 *
 * <p>Implements {@link java.util.spi.ToolProvider} for in-process use via
 * {@code ToolProvider.findFirst("jist")}.
 */
public final class Jist implements ToolProvider, OptionChecker {
    private static final JistCommandLine COMMAND_LINE = JistCommandLine.instance();

    public Jist() {}

    @Override
    public String name() {
        return "jist";
    }

    @Override
    public int isSupportedOption(String option) {
        return COMMAND_LINE.isSupportedOption(option);
    }

    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        var completion = COMMAND_LINE.runCompletion(out, err, args);
        if (completion.isPresent()) {
            return completion.orElseThrow();
        }
        if (args.length == 1 && args[0].equals("--aot-warmup")) {
            return warmup(err);
        }
        try {
            var opts = Options.parse(args);
            if (opts.help()) {
                Options.printUsage(out);
                return 0;
            }
            if (!opts.usages()) {
                opts = opts.withSource(
                        opts.source() == SourceScope.NONE && opts.target() == null && !opts.qualifiedPath()
                                ? null
                                : opts.source() != null ? opts.source() : SourceScope.SIGNATURE);
            }
            out = formattedOutput(opts, out, err);
            var environment = new SearchEnvironment(opts);
            var declarations = new DeclarationSearch(environment);
            if (opts.target() != null) {
                if (opts.usages()) {
                    if (simpleNameTarget(opts.target())) {
                        throw new ToolException(1, "--usages requires an exact qualified symbol");
                    }
                    if (!opts.kinds().isEmpty()) {
                        throw new ToolException(1, "--usages and --kind are mutually exclusive");
                    }
                    if (opts.source() == SourceScope.SIGNATURE
                            || opts.source() == SourceScope.DOC
                            || opts.source() == SourceScope.SYMBOL
                            || opts.source() == SourceScope.NONE) {
                        throw new ToolException(1, "--usages accepts source body, definition, type, or unit");
                    }
                    new UsageSearch(environment).listUsages(opts, out, err);
                    return 0;
                }
                if (simpleNameTarget(opts.target())) {
                    var listing = opts.kinds().isEmpty() ? opts.withKinds(defaultKinds()) : opts;
                    if (opts.source() != null) {
                        declarations.searchSourceContexts(listing, out);
                    } else {
                        declarations.searchSymbols(listing, new SymbolSelection(opts.target(), null), out);
                    }
                    return 0;
                }
                if (opts.source() != null) {
                    var sourceQuery = environment.resolveSymbolSelection(opts.target());
                    var file = Path.of(sourceQuery.path());
                    if (sourceQuery.member() == null && !Files.isRegularFile(file) && !environment.classTargetExists(sourceQuery.path())) {
                        declarations.searchSourceContexts(opts, out);
                        return 0;
                    }
                }
                if (!opts.kinds().isEmpty()) {
                    if (opts.source() != null) {
                        declarations.printSymbolContext(opts, environment.resolveSymbolSelection(opts.target()), out, err);
                    } else {
                        declarations.listClasses(opts, out);
                    }
                    return 0;
                }
                var query = environment.resolveSymbolSelection(opts.target());
                if (opts.source() != null) {
                    declarations.printSymbolContext(opts, query, out, err);
                } else if (query.path().endsWith(JAVA_EXT) || query.path().endsWith(CLASS_EXT)) {
                    declarations.searchFileSymbols(opts, query, out);
                } else {
                    declarations.searchSymbols(opts, query, out);
                }
                return 0;
            }
            if (opts.usages()) {
                throw new ToolException(1, "--usages requires an exact symbol");
            }
            if (opts.source() != null) {
                var listing = opts.kinds().isEmpty() ? opts.withKinds(defaultKinds()) : opts;
                declarations.listSourceContexts(listing, out);
                return 0;
            }
            var listing = opts.kinds().isEmpty() ? opts.withKinds(defaultKinds()) : opts;
            declarations.listClasses(listing, out);
            return 0;
        } catch (ToolException e) {
            err.println("Error: " + e.getMessage());
            return e.exitCode;
        } catch (Exception e) {
            e.printStackTrace(err);
            return 2;
        }
    }

    public static void main(String[] args) throws IOException {
        var jist = new Jist();
        int exitCode = jist.run(new PrintWriter(System.out, true), new PrintWriter(System.err, true), args);
        System.exit(exitCode);
    }

    private static PrintWriter formattedOutput(Options opts, PrintWriter out, PrintWriter err) {
        var terminal = System.console() != null;
        var compactFile = compactSourceFileOutput(opts);
        var heading = !compactFile
                && (opts.listOnly()
                        || (opts.heading() != null ? opts.heading() : terminal));
        var color = switch (opts.color()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO ->
                    terminal && System.getenv("NO_COLOR") == null && !"dumb".equals(System.getenv("TERM"));
        };
        var warnings = new SourceWarnings(err);
        return compactFile
                        || heading
                        || color
                        || opts.qualifiedPath()
                        || !opts.lineNumber()
                        || opts.breakMatches()
                ? new InteractivePrintWriter(
                        out,
                        heading,
                        color,
                        opts.listOnly(),
                        opts.qualifiedPath(),
                        opts.lineNumber(),
                        opts.breakMatches(),
                        compactFile,
                        warnings)
                : new SourceWarningPrintWriter(out, warnings);
    }

    private static boolean compactSourceFileOutput(Options opts) {
        if (opts.target() == null
                || opts.heading() != null
                || opts.qualifiedPath()
                || opts.listOnly()) {
            return false;
        }
        int member = opts.target().indexOf(JAVA_EXT + ".");
        var file = member >= 0 ? opts.target().substring(0, member + JAVA_EXT.length()) : opts.target();
        return file.endsWith(JAVA_EXT) && Files.isRegularFile(Path.of(file));
    }

    private int warmup(PrintWriter err) {
        return run(new PrintWriter(OutputStream.nullOutputStream()), err, "java.lang.String.isEmpty");
    }

    private static final String CLASS_EXT = ".class";
    private static final String JAVA_EXT = ".java";
}
