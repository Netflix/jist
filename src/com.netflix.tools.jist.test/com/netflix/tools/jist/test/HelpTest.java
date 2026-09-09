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

package com.netflix.tools.jist.test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.tools.OptionChecker;

import com.netflix.tools.jist.Access;
import com.netflix.tools.jist.Jist;
import com.netflix.tools.jist.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTest {

    @Test
    void implementationPackageIsExportedOnlyToTests() {
        var module = Jist.class.getModule();
        var packageName = Jist.class.getPackageName();

        assertFalse(module.isExported(packageName));
        assertTrue(module.isExported(packageName, getClass().getModule()));
    }

    @Test
    void providerDeclaresItsResolutionProjection() throws Exception {
        try (var input = Jist.class.getModule().getResourceAsStream("META-INF/com.netflix.tools/tools/jist.properties")) {
            assertNotNull(input);
            var properties = new Properties();
            properties.load(input);
            assertEquals(Set.of("type", "options", "compile-time", "warmup"), properties.stringPropertyNames());
            assertEquals("list", properties.getProperty("type"));
            assertEquals(
                    Set.of("module-path", "module-source-path", "module=list", "release", "enable-preview", "add-exports"),
                    Set.of(properties.getProperty("options").split(",")));
            assertEquals("true", properties.getProperty("compile-time"));
            assertEquals("--aot-warmup", properties.getProperty("warmup"));
        }
    }

    @Test
    void providesDelegatedCompletionThroughTheStandardOptionCheckerConvention() {
        var jist = new Jist();
        var checker = assertInstanceOf(OptionChecker.class, jist);
        var output = new StringWriter();
        var error = new StringWriter();

        assertEquals(0, checker.isSupportedOption("__complete"));
        assertEquals(1, checker.isSupportedOption("--source"));
        assertEquals(1, checker.isSupportedOption("-classpath"));
        assertEquals(0, checker.isSupportedOption("-public"));
        assertEquals(0, checker.isSupportedOption("--version"));

        int exitCode = jist.run(new PrintWriter(output, true), new PrintWriter(error, true), "__complete", "--source",
                "si");

        assertEquals(0, exitCode, error.toString());
        assertEquals(List.of("signature\tRead source at the selected scope", ":0"),
                output.toString()
                      .lines()
                      .toList());
        assertEquals("", error.toString());
    }

    @Test
    void completesSymbolOperandsSemantically() {
        var output = new StringWriter();
        var error = new StringWriter();

        int exitCode = new Jist().run(new PrintWriter(output, true), new PrintWriter(error, true), "__complete", "--source",
                "symbol", "Str");

        assertEquals(0, exitCode, error.toString());
        assertTrue(output.toString()
                         .lines()
                         .anyMatch(line -> line.startsWith("java.lang.String\t")),
                output.toString());
        assertEquals("", error.toString());
    }

    @Test
    void conflictingOptionsRetainLastOptionWinsSemantics() throws Exception {
        var options = Options.parse(
                new String[] {"-private", "-public", "--no-heading", "--heading", "--no-line-number", "--line-number",
                        "--break", "--no-break", "--pretty", "--color", "never"});

        assertEquals(Access.PUBLIC, options.access());
        assertEquals(Boolean.TRUE, options.heading());
        assertTrue(options.lineNumber());
        assertFalse(options.breakMatches());
        assertEquals("NEVER", String.valueOf(options.color()));
    }

    @Test
    void performsAotWarmup() {
        var output = new StringWriter();
        var error = new StringWriter();

        int exitCode = new Jist().run(new PrintWriter(output), new PrintWriter(error), "--aot-warmup");

        assertEquals(0, exitCode, error.toString());
        assertEquals("", output.toString());
        assertEquals("", error.toString());
    }

    @Test
    void optionsWithMissingValuesReportUsageErrors() {
        for (var option : new String[] {
            "--class-path",
            "-classpath",
            "-cp",
            "--module-path",
            "-p",
            "--source-path",
            "-sourcepath",
            "--module-source-path",
            "--system",
            "--module",
            "-m",
            "--add-modules",
            "--add-exports",
            "--add-reads",
            "--limit-modules",
            "--release",
            "--module-version",
            "-d"
        }) {
            var output = new StringWriter();
            var error = new StringWriter();

            int exitCode = new Jist().run(new PrintWriter(output), new PrintWriter(error), option);

            assertEquals(1, exitCode, option);
            assertEquals("", output.toString(), option);
            assertEquals("Error: " + option + " requires a value\n", error.toString(), option);
        }
    }

    @Test
    void printsVersion() {
        var output = new StringWriter();
        var error = new StringWriter();
        String version = Jist.class.getModule()
                .getDescriptor()
                .rawVersion()
                .orElse("dev");

        int exitCode = new Jist().run(new PrintWriter(output), new PrintWriter(error), "--version");

        assertEquals(0, exitCode);
        assertEquals("jist " + version + "\n", output.toString());
        assertEquals("", error.toString());
    }

    @Test
    void helpIsFormattedAndWrittenToStandardOutput() {
        var output = new StringWriter();
        var error = new StringWriter();

        int exitCode = new Jist().run(new PrintWriter(output), new PrintWriter(error), "--help");

        assertEquals(0, exitCode);
        assertEquals(
                """
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
                  --version                               Print version information
                """,
                output.toString());
        assertEquals("", error.toString());
    }
}
