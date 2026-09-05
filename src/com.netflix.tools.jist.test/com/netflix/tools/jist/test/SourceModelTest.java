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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Access;
import com.netflix.tools.jist.SourceModel;
import com.netflix.tools.jist.SourceModel.Attribution;
import com.netflix.tools.jist.SourceModel.SourceSpan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SourceModel}, which uses javac parsing to correlate
 * source declarations with ClassFile members and records documentation, source
 * ranges, and parameter names.
 *
 * <p>Each test uses a fixture exercising a different Java language construct.
 * Fixtures are compiled once; tests call {@code SourceModel.attributions()} directly.
 */
class SourceModelTest {

    private static final Path TEST_DIR = Path.of("build/test-source-model");
    private static final Path SRC_DIR = TEST_DIR.resolve("src");
    private static final Path CLASSES_DIR = TEST_DIR.resolve("classes");

    @BeforeAll
    static void compileFixtures() throws Exception {
        if (Files.isDirectory(TEST_DIR)) {
            deleteRecursively(TEST_DIR);
        }
        Files.createDirectories(SRC_DIR.resolve("com/example"));
        Files.createDirectories(CLASSES_DIR);

        writeFixture("Generics.java",
                """
                package com.example;

                import java.util.List;
                import java.util.Map;

                /**
                 * Generics doc.
                 */
                public class Generics<K extends Comparable<K>, V> {
                    private Map<String, List<Integer>> complexField;

                    /**
                     * Max doc.
                     */
                    public <T extends Comparable<? super T>> T max(T a, T b) {
                        return a.compareTo(b) >= 0 ? a : b;
                    }

                    public Map<K, List<V>> transform(Map<K, V> input) {
                        return null;
                    }
                }
                """);

        writeFixture("Point.java",
                """
                package com.example;

                /**
                 * Record doc.
                 */
                public record Point(int x, int y) {
                    /**
                     * Compact constructor doc.
                     */
                    public Point {
                        if (x < 0 || y < 0) throw new IllegalArgumentException();
                    }

                    public double distance() {
                        return Math.sqrt(x * x + y * y);
                    }
                }
                """);

        writeFixture("Processor.java",
                """
                package com.example;

                /**
                 * Interface doc.
                 */
                public interface Processor<T> {
                    /**
                     * Process doc.
                     */
                    T process(T input);

                    /**
                     * Default method doc.
                     */
                    default T processOrDefault(T input, T fallback) {
                        try { return process(input); }
                        catch (Exception e) { return fallback; }
                    }

                    static <T> Processor<T> identity() {
                        return t -> t;
                    }
                }
                """);

        writeFixture("Marker.java",
                """
                package com.example;

                import java.lang.annotation.*;

                /**
                 * Annotation doc.
                 */
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.METHOD})
                public @interface Marker {
                    /**
                     * Value doc.
                     */
                    String value() default "";

                    int priority() default 0;
                }
                """);

        writeFixture("Planet.java",
                """
                package com.example;

                /**
                 * Planet doc.
                 */
                public enum Planet {
                    /**
                     * Mercury doc.
                     */
                    MERCURY(3.303e+23, 2.4397e6) {
                        @Override public String category() { return "inner"; }
                    },

                    EARTH(5.976e+24, 6.37814e6) {
                        @Override public String category() { return "inner"; }
                    };

                    private final double mass;
                    private final double radius;

                    Planet(double mass, double radius) {
                        this.mass = mass;
                        this.radius = radius;
                    }

                    public abstract String category();

                    public double surfaceGravity() {
                        return 6.67300E-11 * mass / (radius * radius);
                    }
                }
                """);

        writeFixture("Shape.java",
                """
                package com.example;

                /**
                 * Shape doc.
                 */
                public sealed class Shape permits Shape.Circle, Shape.Rectangle {

                    /**
                     * Circle doc.
                     */
                    public static final class Circle extends Shape {
                        private final double radius;

                        public Circle(double radius) { this.radius = radius; }

                        public double area() { return Math.PI * radius * radius; }
                    }

                    /**
                     * Rectangle doc.
                     */
                    public static final class Rectangle extends Shape {
                        private final double width, height;

                        public Rectangle(double width, double height) {
                            this.width = width;
                            this.height = height;
                        }

                        public double area() { return width * height; }
                    }
                }
                """);

        writeFixture("TrickySyntax.java",
                """
                package com.example;

                import java.util.List;
                import java.util.Map;

                /**
                 * Tricky syntax doc.
                 */
                public class TrickySyntax {
                    int x, y, z;
                    String code = "class Foo { void bar() {} }";
                    String textBlock = \"""
                            public class Fake {
                                void method() {}
                            }
                            \""";
                    static final Map<String, Integer> MAP = Map.of(
                        "a", 1, "b", 2);
                    char brace = '{';
                    char quote = '"';

                    @SuppressWarnings("unchecked")
                    public <T> List<T> cast(List<?> list) {
                        return (List<T>) list;
                    }

                    public String getCode() {
                        return "class { interface enum record }";
                    }
                }
                """);

        writeFixture("Overloaded.java",
                """
                package com.example;

                public class Overloaded {
                    /**
                     * First add doc.
                     */
                    public int add(int a, int b) { return a + b; }

                    /**
                     * Second add doc.
                     */
                    public double add(double a, double b) { return a + b; }

                    public String add(String a, String b) { return a + b; }
                }
                """);

        writeFixture("UnicodeSyntax.java",
                """
                package com.example;

                /**
                 * Unicode syntax doc.
                 */
                public clUNICODE_A_ESCAPEss UnicodeSyntax {
                    /**
                     * Run doc.
                     */
                    public void rUNICODE_U_ESCAPEn(String value) {}
                }
                """
                        .replace("UNICODE_A_ESCAPE", "\\u0061")
                        .replace("UNICODE_U_ESCAPE", "\\u0075"));

        var javac = ToolProvider.getSystemJavaCompiler();
        try (var fm = javac.getStandardFileManager(null, null, null)) {
            var sourceFiles = new ArrayList<Path>();
            try (var walk = Files.walk(SRC_DIR)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(sourceFiles::add);
            }
            var sources = fm.getJavaFileObjects(sourceFiles.toArray(Path[]::new));
            var task = javac.getTask(null, fm, null, List.of("-d", CLASSES_DIR.toString(), "-parameters"), null,
                    sources);
            assertTrue(task.call(), "Fixture compilation failed");
        }
    }

    @Test
    void generics() throws Exception {
        var attrs = attributions("com/example/Generics.java");

        assertAll(
                () -> assertHasDoc(attrs, "com.example.Generics"),
                () -> assertFound(attrs, "com.example.Generics#complexField"),
                () -> assertDocAndSrc(attrs, "com.example.Generics#max@0"),
                () -> assertHasSrc(attrs, "com.example.Generics#transform@0"),
                () -> assertEquals(List.of("a", "b"),
                        attrs.get("com.example.Generics#max@0").paramNames()),
                () -> assertEquals(List.of("input"),
                        attrs.get("com.example.Generics#transform@0").paramNames()));
    }

    @Test
    void records() throws Exception {
        var attrs = attributions("com/example/Point.java");

        assertAll(() -> assertHasDoc(attrs, "com.example.Point"), () -> assertDocAndSrc(attrs, "com.example.Point#<init>@0"),
                () -> assertHasSrc(attrs, "com.example.Point#distance@0"));
    }

    @Test
    void interfaces() throws Exception {
        var attrs = attributions("com/example/Processor.java");

        assertAll(() -> assertHasDoc(attrs, "com.example.Processor"), () -> assertHasDoc(attrs, "com.example.Processor#process@0"), () -> assertDocAndSrc(attrs, "com.example.Processor#processOrDefault@0"),
                () -> assertHasSrc(attrs, "com.example.Processor#identity@0"));
    }

    @Test
    void annotationTypes() throws Exception {
        var attrs = attributions("com/example/Marker.java");

        assertAll(() -> assertHasDoc(attrs, "com.example.Marker"), () -> assertDocAndSrc(attrs, "com.example.Marker#value@0"),
                () -> assertFound(attrs, "com.example.Marker#priority@0"));
    }

    @Test
    void enumsWithBodies() throws Exception {
        var attrs = attributions("com/example/Planet.java");

        assertAll("class and enum constants", () -> assertHasDoc(attrs, "com.example.Planet"), () -> assertHasDoc(attrs, "com.example.Planet#MERCURY"),
                () -> assertFound(attrs, "com.example.Planet#EARTH"));

        assertAll("fields and methods", () -> assertFound(attrs, "com.example.Planet#mass"), () -> assertFound(attrs, "com.example.Planet#radius"),
                () -> assertHasSrc(attrs, "com.example.Planet#<init>@0"), () -> assertFound(attrs, "com.example.Planet#category@0"), () -> assertHasSrc(attrs, "com.example.Planet#surfaceGravity@0"));
    }

    @Test
    void sealedClasses() throws Exception {
        var attrs = attributions("com/example/Shape.java");

        assertAll("outer class and inner classes found", () -> assertHasDoc(attrs, "com.example.Shape"), () -> assertHasDoc(attrs, "com.example.Shape.Circle"),
                () -> assertHasDoc(attrs, "com.example.Shape.Rectangle"));

        assertAll("Circle members", () -> assertFound(attrs, "com.example.Shape.Circle#radius"), () -> assertHasSrc(attrs, "com.example.Shape.Circle#<init>@0"),
                () -> assertHasSrc(attrs, "com.example.Shape.Circle#area@0"));

        assertAll("Rectangle multi-declarator field and members", () -> assertFound(attrs, "com.example.Shape.Rectangle#width"), () -> assertFound(attrs, "com.example.Shape.Rectangle#height"),
                () -> assertHasSrc(attrs, "com.example.Shape.Rectangle#<init>@0"), () -> assertHasSrc(attrs, "com.example.Shape.Rectangle#area@0"));
    }

    @Test
    void trickySyntax() throws Exception {
        var attrs = attributions("com/example/TrickySyntax.java");

        assertAll("multi-declarator fields", () -> assertFound(attrs, "com.example.TrickySyntax#x"), () -> assertFound(attrs, "com.example.TrickySyntax#y"),
                () -> assertFound(attrs, "com.example.TrickySyntax#z"));

        assertAll(
                "fields with tricky initializers",
                () -> assertFound(attrs, "com.example.TrickySyntax#code"),
                () -> assertFound(attrs, "com.example.TrickySyntax#textBlock"),
                () -> assertFound(attrs, "com.example.TrickySyntax#MAP"),
                () -> assertFound(attrs, "com.example.TrickySyntax#brace"),
                () -> assertFound(attrs, "com.example.TrickySyntax#quote"));

        assertAll("methods through syntactic noise", () -> assertHasSrc(attrs, "com.example.TrickySyntax#cast@0"),
                () -> assertHasSrc(attrs, "com.example.TrickySyntax#getCode@0"));
    }

    @Test
    void unicodeEscapesAreProcessedBeforeAttribution() throws Exception {
        var attrs = attributions("com/example/UnicodeSyntax.java");

        assertAll(
                () -> assertEquals(3, attrs.get("com.example.UnicodeSyntax").docStart()),
                () -> assertEquals(5, attrs.get("com.example.UnicodeSyntax").docEnd()),
                () -> assertEquals(7, attrs.get("com.example.UnicodeSyntax#run@0").docStart()),
                () -> assertEquals(9, attrs.get("com.example.UnicodeSyntax#run@0").docEnd()),
                () -> assertEquals(10, attrs.get("com.example.UnicodeSyntax#run@0").srcStart()),
                () -> assertEquals(10, attrs.get("com.example.UnicodeSyntax#run@0").srcEnd()),
                () -> assertEquals(List.of("value"),
                        attrs.get("com.example.UnicodeSyntax#run@0").paramNames()));
    }

    @Test
    void overloadedMethods() throws Exception {
        var attrs = attributions("com/example/Overloaded.java");

        assertAll(() -> assertDocAndSrc(attrs, "com.example.Overloaded#add@0"),
                () -> assertDocAndSrc(attrs, "com.example.Overloaded#add@1"), () -> assertHasSrc(attrs, "com.example.Overloaded#add@2"));
    }

    @Test
    void attributionRetainsExactSourceSpans() throws Exception {
        var unicodeSource = Files.readString(SRC_DIR.resolve("com/example/UnicodeSyntax.java"));
        var unicode = attributions("com/example/UnicodeSyntax.java");
        var run = unicode.get("com.example.UnicodeSyntax#run@0");

        assertEquals("/**\n     * Run doc.\n     */", slice(unicodeSource, run.documentation()));
        assertEquals("{}", slice(unicodeSource, run.implementation()));
        assertTrue(slice(unicodeSource, run.declaration()).contains("r\\u0075n(String value) {}"));

        var trickySource = Files.readString(SRC_DIR.resolve("com/example/TrickySyntax.java"));
        var tricky = attributions("com/example/TrickySyntax.java");
        assertEquals("\"class Foo { void bar() {} }\"", slice(trickySource, tricky.get("com.example.TrickySyntax#code")
                .implementation()));
    }

    @Test
    void lineCount() throws Exception {
        var bytes = Files.readAllBytes(SRC_DIR.resolve("com/example/Generics.java"));
        var model = SourceModel.parse(bytes);
        var content = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(content.split("\n", -1).length, model.lineCount());
    }

    private static Map<String, Attribution> attributions(String sourceRelPath) throws Exception {
        var sourceBytes = Files.readAllBytes(SRC_DIR.resolve(sourceRelPath));
        var model = SourceModel.parse(sourceBytes);

        var baseName = sourceRelPath.replace(".java", "");
        var classes = new ArrayList<ClassModel>();
        try (var walk = Files.walk(CLASSES_DIR)) {
            walk.filter(p -> {
                var rel = CLASSES_DIR.relativize(p)
                                     .toString()
                                     .replace('\\', '/');
                if (!rel.endsWith(".class")) {
                    return false;
                }
                var name = rel.substring(0, rel.length() - ".class".length());
                return name.equals(baseName) || name.startsWith(baseName + "$");
            })
                .forEach(p -> {
                try {
                    classes.add(ClassFile.of()
                            .parse(Files.readAllBytes(p)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        return model.attributions(classes, Access.PRIVATE);
    }

    private static String slice(String source, SourceSpan span) {
        return source.substring(span.startOffset(), span.endOffset());
    }

    private static void assertFound(Map<String, Attribution> attrs, String key) {
        assertNotNull(attrs.get(key), () -> "attribution missing: " + key + " (keys: " + attrs.keySet() + ")");
    }

    private static void assertHasDoc(Map<String, Attribution> attrs, String key) {
        var attr = attrs.get(key);
        assertNotNull(attr, () -> "attribution missing: " + key);
        assertTrue(attr.hasDoc(), () -> key + " should have doc");
        assertTrue(attr.docStart() <= attr.docEnd(), () -> key
                + " doc range invalid: "
                + attr.docStart()
                + "-"
                + attr.docEnd());
    }

    private static void assertHasSrc(Map<String, Attribution> attrs, String key) {
        var attr = attrs.get(key);
        assertNotNull(attr, () -> "attribution missing: " + key);
        assertTrue(attr.hasSrc(), () -> key + " should have source range");
        assertTrue(attr.srcStart() <= attr.srcEnd(), () -> key
                + " src range invalid: "
                + attr.srcStart()
                + "-"
                + attr.srcEnd());
    }

    private static void assertDocAndSrc(Map<String, Attribution> attrs, String key) {
        assertHasDoc(attrs, key);
        assertHasSrc(attrs, key);
    }

    private static void writeFixture(String name, String content) throws IOException {
        Files.writeString(SRC_DIR.resolve("com/example/" + name), content);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
