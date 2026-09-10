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
import java.nio.file.Files;
import java.nio.file.Path;

import com.netflix.tools.jist.Jist;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceQueryTest {

    private static final Path SOURCE = Path.of("build/test-source-query/com/example/Example.java");
    private static final Path PACKAGE_INFO = SOURCE.getParent().resolve("package-info.java");
    private static final Path MODULE_INFO = Path.of("build/test-source-query/module-info.java");
    private static final Path SYMBOL_SOURCE = SOURCE.getParent().resolve("SourceSelection.java");
    private static final Path NO_INFO = Path.of("build/test-source-query/com/noinfo/NoInfo.java");

    @BeforeAll
    static void writeSource() throws Exception {
        Files.createDirectories(SOURCE.getParent());
        Files.writeString(SOURCE,
                """
                package com.example;

                /** Example documentation. */
                public class Example {
                    /** Value documentation. */
                    private final int value = 42;

                    /** Run documentation. */
                    public int run(int amount) {
                        return value + amount;
                    }
                }
                """);
        Files.writeString(SYMBOL_SOURCE,
                """
                package com.example;

                import java.util.List;

                public class SourceSelection {
                    /** Field documentation. */
                    int value = 1;

                    static class Nested {
                        void nested() {
                            System.out.println("nested");
                        }
                    }

                    void run() {
                        System.out.println("run");
                    }
                }
                """);
        Files.writeString(PACKAGE_INFO,
                """
                /** Example package documentation. */

                @Deprecated
                package com.example;
                """);
        Files.writeString(MODULE_INFO,
                """
                /** Example module documentation. */

                @Deprecated
                module example.module {
                    exports com.example;
                }
                """);
        Files.createDirectories(NO_INFO.getParent());
        Files.writeString(NO_INFO,
                """
                package com.noinfo;
                public class NoInfo {}
                """);
    }

    @Test
    void sourceLocatorOmitsRedundantCompilationUnitContext() {
        var result = run("--kind", "method", SOURCE.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("9:    public int run(int amount) {\n", result.output());
    }

    @Test
    void sourceLocatorCanRetainCompilationUnitContextExplicitly() {
        var result = run("--no-heading", "--kind", "method", SOURCE.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("com.example.Example(Example.java:9):     public int run(int amount) {\n", result.output());
    }

    @Test
    void sourceLocatorListsDeclarationsInSourceOrder() {
        var result = run(SOURCE.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertAll(
                () -> assertTrue(result.output().contains("4:public class Example {")),
                () -> assertTrue(result.output().contains("9:    public int run(int amount) {")),
                () -> assertTrue(result.output().contains("6:    private final int value = 42;")));
    }

    @Test
    void missingSourceMemberFails() {
        var result = run(SOURCE + ".missing");

        assertEquals(1, result.exitCode());
        assertEquals("", result.output());
        assertTrue(result.error().contains("Symbol not found: com.example.Example.missing"),
                result.error());
    }

    @Test
    void memberSelectionKeepsOnlyMatchingSymbols() {
        var result = run(SOURCE + ".run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("9:    public int run(int amount) {"),
                result.output());
    }

    @Test
    void documentationOptionIsRejected() {
        var result = run("--docs", SOURCE + ".run");

        assertEquals(1, result.exitCode());
        assertEquals("Error: Unknown option: --docs\n", result.error());
    }

    @Test
    void unknownSourceScopeListsTheEnumValues() {
        var result = run("--source", "summary", SOURCE + ".run");

        assertEquals(1, result.exitCode());
        assertEquals(
                "Error: --source expects one of: none, body, signature, doc, definition, " + "symbol, type, unit\n",
                result.error());
    }

    @Test
    void symbolScopeSelectsSourceExtentFromTheSymbolKindAndNesting() {
        var sourcePath = Path.of("build/test-source-query").toString();
        var topLevel = run("--source-path", sourcePath, "--source", "symbol", "com.example.SourceSelection");
        var nested = run("--source-path", sourcePath, "--source", "symbol", "com.example.SourceSelection.Nested");
        var method = run("--source-path", sourcePath, "--source", "symbol", "com.example.SourceSelection.run");
        var field = run("--source-path", sourcePath, "--source", "symbol", "com.example.SourceSelection.value");

        assertAll(
                () -> assertEquals(0, topLevel.exitCode(), topLevel.error()),
                () -> assertTrue(topLevel.output().contains("import java.util.List;"),
                        topLevel.output()),
                () -> assertEquals(0, nested.exitCode(), nested.error()),
                () -> assertTrue(nested.output().contains("static class Nested {"),
                        nested.output()),
                () -> assertTrue(nested.output().contains("System.out.println(\"nested\")"),
                        nested.output()),
                () -> assertFalse(nested.output().contains("import java.util.List"),
                        nested.output()),
                () -> assertFalse(nested.output().contains("System.out.println(\"run\")"),
                        nested.output()),
                () -> assertEquals(0, method.exitCode(), method.error()),
                () -> assertTrue(method.output().contains("void run() {"),
                        method.output()),
                () -> assertTrue(method.output().contains("System.out.println(\"run\")"),
                        method.output()),
                () -> assertFalse(method.output().contains("class SourceSelection"),
                        method.output()),
                () -> assertEquals(0, field.exitCode(), field.error()),
                () -> assertTrue(field.output().contains("int value = 1;"),
                        field.output()),
                () -> assertFalse(field.output().contains("Field documentation"),
                        field.output()));
    }

    @Test
    void sourceScopesAreProgressive() {
        var signature = run("--source", "signature", SOURCE + ".run");
        var documentation = run("--source", "doc", SOURCE + ".run");
        var definition = run("--source", "definition", SOURCE + ".run");
        var unit = run("-s", "unit", SOURCE + ".run");

        assertAll(
                () -> assertEquals(0, signature.exitCode(), signature.error()),
                () -> assertTrue(signature.output().contains("9:    public int run(int amount) {"),
                        signature.output()),
                () -> assertFalse(signature.output().contains("Run documentation"),
                        signature.output()),
                () -> assertFalse(signature.output().contains("return value + amount"),
                        signature.output()),
                () -> assertEquals(0, documentation.exitCode(), documentation.error()),
                () -> assertTrue(documentation.output().contains("Run documentation"),
                        documentation.output()),
                () -> assertTrue(documentation.output().contains("9:    public int run(int amount) {"),
                        documentation.output()),
                () -> assertFalse(documentation.output().contains("return value + amount"),
                        documentation.output()),
                () -> assertEquals(0, definition.exitCode(), definition.error()),
                () -> assertTrue(definition.output().contains("9:    public int run(int amount) {"),
                        definition.output()),
                () -> assertTrue(definition.output().contains("10:        return value + amount;"),
                        definition.output()),
                () -> assertTrue(definition.output().contains("Run documentation"),
                        definition.output()),
                () -> assertEquals(0, unit.exitCode(), unit.error()),
                () -> assertTrue(unit.output().contains("1:package com.example;"),
                        unit.output()),
                () -> assertTrue(unit.output().contains("/** Example documentation. */"),
                        unit.output()));
    }

    @Test
    void typeScopeUsesTheTopLevelTypeLexicalExtent() {
        var definition = run("--source", "definition", SOURCE + ".run");
        var type = run("--source", "type", SOURCE + ".run");
        var unit = run("--source", "unit", SOURCE + ".run");

        assertAll(
                () -> assertEquals(0, definition.exitCode(), definition.error()),
                () -> assertTrue(definition.output().contains("Run documentation"),
                        definition.output()),
                () -> assertEquals(0, type.exitCode(), type.error()),
                () -> assertTrue(type.output().contains("4:public class Example {"),
                        type.output()),
                () -> assertTrue(type.output().contains("/** Run documentation. */"),
                        type.output()),
                () -> assertTrue(type.output().contains("Example documentation"),
                        type.output()),
                () -> assertFalse(type.output().contains("package com.example"),
                        type.output()),
                () -> assertEquals(0, unit.exitCode(), unit.error()),
                () -> assertTrue(unit.output().contains("Example documentation"),
                        unit.output()),
                () -> assertTrue(unit.output().contains("package com.example"),
                        unit.output()));
    }

    @Test
    void prettyOutputGroupsAndColorsSourceLines() {
        var result = run("--pretty", "--source", "signature", SOURCE + ".run");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "\033[36mclass\033[0m " + "\033[1;35mcom.example.Example(Example.java)\033[0m\n" + "\033[32m9\033[0m\033[36m:\033[0m" + "    public int run(int amount) {\n",
                result.output());
    }

    @Test
    void terminalStatusControlsDefaultFormatting() {
        var result = run(true, "--source", "signature", SOURCE + ".run");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "\033[32m9\033[0m\033[36m:\033[0m" + "    public int run(int amount) {\n",
                result.output());
    }

    @Test
    void qualifiedPathExpandsInteractiveSourceHeadings() {
        var result = run("--heading", "--color", "never", "--qualified-path", "--source", "signature",
                SOURCE + ".run");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "class com.example.Example(" + SOURCE.toAbsolutePath().normalize() + ")\n" + "9:    public int run(int amount) {\n",
                result.output());
    }

    @Test
    void listPrintsOnlyKindAndSymbol() {
        var result = run("-l", "--color", "never", PACKAGE_INFO.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("package com.example\n", result.output());
    }

    @Test
    void listIncludesOnlyARequestedQualifiedLocation() {
        var result = run("-l", "--color", "never", "--qualified-path", SOURCE + ".run");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "method com.example.Example.run(int)(" + SOURCE.toAbsolutePath().normalize() + ")\n",
                result.output());
    }

    @Test
    void listDistinguishesSourceOnlyMethodOverloads() throws Exception {
        var source = SOURCE.getParent().resolve("Overloads.java");
        Files.writeString(source,
                """
                package com.example;
                public class Overloads {
                    void parse(String value) {}
                    void parse(int[] values) {}
                }
                """);

        var result = run("-l", "--color", "never", source + ".parse");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "method com.example.Overloads.parse(String)\n" + "method com.example.Overloads.parse(int[])\n",
                result.output());
    }

    @Test
    void lineNumbersCanBeSuppressedAndNonConsecutiveMatchesSeparated() {
        var result = run("--source", "doc", "--no-line-number", "--break", SOURCE.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                """
                /** Example documentation. */
                public class Example {
                    /** Value documentation. */
                    private final int value = 42;

                    /** Run documentation. */
                    public int run(int amount) {
                """,
                result.output());
    }

    @Test
    void headingsCanOmitLineNumbers() {
        var result = run("--heading", "--color", "never", "--no-line-number", "--source", "signature",
                SOURCE + ".run");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                """
                class com.example.Example(Example.java)
                    public int run(int amount) {
                """,
                result.output());
    }

    @Test
    void documentationIncludesLinesBeforeItsSignature() throws Exception {
        var source = SOURCE.getParent().resolve("Gap.java");
        Files.writeString(source,
                """
                package com.example;

                /** Gap documentation. */

                @Deprecated
                public class Gap {}
                """);

        var result = run("--source", "doc", source.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "3:/** Gap documentation. */\n" + "4:\n" + "5:@Deprecated\n" + "6:public class Gap {}\n",
                result.output());
    }

    @Test
    void packageMetadataUsesTheDeclaredPackageAsItsSymbol() {
        var result = run("--source", "doc", PACKAGE_INFO.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                "1:/** Example package documentation. */\n" + "2:\n" + "3:@Deprecated\n" + "4:package com.example;\n",
                result.output());
    }

    @Test
    void moduleMetadataUsesTheDeclaredModuleAsItsSymbol() {
        var documentation = run("--source", "doc", MODULE_INFO.toString());
        var definition = run("--source", "definition", MODULE_INFO.toString());

        assertEquals(0, documentation.exitCode(), documentation.error());
        assertEquals(
                "1:/** Example module documentation. */\n" + "2:\n" + "3:@Deprecated\n" + "4:module example.module {\n",
                documentation.output());
        assertEquals(0, definition.exitCode(), definition.error());
        assertTrue(definition.output().contains("5:    exports com.example;"),
                definition.output());
    }

    @Test
    void packagesWithoutPackageInfoUseASynthesizedDeclaration() {
        var result = run("--source-path", Path.of("build/test-source-query").toString(),
                "--source", "doc", "--kind", "package", "com.noinfo");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("com.noinfo: package com.noinfo;\n", result.output());
    }

    private static Result run(String... args) {
        var output = new StringWriter();
        var error = new StringWriter();
        int exitCode = new Jist().run(false, new PrintWriter(output), new PrintWriter(error), args);
        return new Result(exitCode, output.toString(), error.toString());
    }

    private static Result run(boolean terminal, String... args) {
        var output = new StringWriter();
        var error = new StringWriter();
        int exitCode = new Jist().run(terminal, new PrintWriter(output), new PrintWriter(error), args);
        return new Result(exitCode, output.toString(), error.toString());
    }

    private record Result(int exitCode, String output, String error) {}
}
