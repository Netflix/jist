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
import javax.lang.model.element.Modifier;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/**
 * Parsed representation of Java source content and declaration positions.
 * <p>
 * The source model records where declarations appear. ClassFiles remain
 * authoritative for what declarations exist and for their compiled signatures.
 * Source parsing does not perform type attribution.
 */
public final class SourceModel {

    /** Decode UTF-8 bytes and count source lines. */
    public static SourceModel parse(byte[] bytes) {
        var decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        final char[] source;
        if (decoded.hasArray() && decoded.arrayOffset() == 0 && decoded.remaining() == decoded.array().length) {
            source = decoded.array();
        } else {
            source = new char[decoded.remaining()];
            decoded.get(source);
        }
        int lineCount = 1;
        for (var ch : source) {
            if (ch == '\n') {
                lineCount++;
            }
        }
        var lineStarts = new int[lineCount];
        int line = 1;
        for (int i = 0; i < source.length; i++) {
            if (source[i] == '\n') {
                lineStarts[line++] = i + 1;
            }
        }
        return new SourceModel(source, lineStarts);
    }

    private static final String JAVA_EXT = ".java";

    private final char[] source;
    private final int[] lineStarts;

    private SourceModel(char[] source, int[] lineStarts) {
        this.source = source;
        this.lineStarts = lineStarts;
    }

    public int lineCount() {
        return lineStarts.length;
    }

    int contentLineCount() {
        return source.length > 0 && source[source.length - 1] == '\n'
                ? lineStarts.length - 1
                : lineStarts.length;
    }

    String line(int lineNumber) {
        int index = lineNumber - 1;
        int start = lineStarts[index];
        int end = index + 1 < lineStarts.length ? lineStarts[index + 1] - 1 : source.length;
        if (end > start && source[end - 1] == '\r') {
            end--;
        }
        return new String(source, start, end - start);
    }

    private static int indexOf(char[] source, String value, int from,
            int end) {
        int limit = end - value.length();
        outer:
        for (int i = from; i <= limit; i++) {
            for (int j = 0; j < value.length(); j++) {
                if (source[i + j] != value.charAt(j)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public Map<String, Attribution> attributions(List<ClassModel> classes, Access access) {
        if (classes.isEmpty()) {
            return Map.of();
        }

        var outer = classes.stream()
                .filter(cm -> !cm.thisClass()
                                 .asInternalName()
                                 .contains("$"))
                .findFirst()
                .orElse(null);
        if (outer == null) {
            return Map.of();
        }

        var sourceName = outer.thisClass().asInternalName() + JAVA_EXT;
        return structure(sourceName, access).attribution();
    }

    SourceStructure structure(String sourceName, Access access) {
        return structure(sourceName, access, true);
    }

    SourceStructure structure(String sourceName, Access access, boolean documentation) {
        var compiler = ToolProvider.getSystemJavaCompiler();

        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var file = new StringJavaFileObject(sourceName, source);
            var task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("-proc:none", "-parameters"), null,
                    List.of(file));
            var trees = documentation ? DocTrees.instance(task) : Trees.instance(task);
            var parsed = task.parse().iterator();
            if (!parsed.hasNext()) {
                return new SourceStructure("", List.of(), Map.of(),
                        Map.of(), Map.of());
            }
            var unit = parsed.next();
            var scanner = new AttributionScanner(unit, trees, documentation, access, source, sourceName);
            scanner.scan(unit, null);
            return new SourceStructure(
                    unit.getPackageName() != null ? unit.getPackageName().toString() : "",
                    List.copyOf(scanner.classNames),
                    Map.copyOf(scanner.result),
                    Map.copyOf(scanner.kinds),
                    Map.copyOf(scanner.listedNames));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record SourceStructure(String packageName, List<String> classNames, Map<String, Attribution> attribution,
                           Map<String, SymbolKind> kinds, Map<String, String> listedNames) {}

    static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final char[] source;

        StringJavaFileObject(String internalName, char[] source) {
            super(URI.create("string:///" + internalName), JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return CharBuffer.wrap(source);
        }
    }

    private static final class AttributionScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final boolean documentation;
        private final Access access;
        private final char[] source;
        private final Map<String, Attribution> result = new HashMap<>();
        private final Map<String, SymbolKind> kinds = new HashMap<>();
        private final Map<String, String> listedNames = new HashMap<>();
        private final List<String> classNames = new ArrayList<>();
        private String className;
        private boolean implicitPublicMembers;
        private Map<String, Integer> methodOrdinals;
        private final boolean packageInfo;

        AttributionScanner(CompilationUnitTree unit, Trees trees, boolean documentation,
                           Access access, char[] source, String sourceName) {
            this.unit = unit;
            this.trees = trees;
            this.documentation = documentation;
            this.access = access;
            this.source = source;
            this.packageInfo = Path.of(sourceName)
                    .getFileName()
                    .toString()
                    .equals("package-info.java");
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree tree, Void unused) {
            if (packageInfo && tree.getPackageName() != null) {
                var name = tree.getPackageName().toString();
                var signature = packageSignatureRange(tree);
                var documentation = docRange(getCurrentPath());
                if (!documentation.present()) {
                    documentation = leadingDocumentationRange(signature);
                }
                classNames.add(name);
                result.put(name, Attribution.of(documentation, signature, signature, SourceSpan.NONE, List.of()));
                kinds.put(name, SymbolKind.PACKAGE);
                listedNames.put(name, name);
            }
            return super.visitCompilationUnit(tree, unused);
        }

        @Override
        public Void visitModule(ModuleTree tree, Void unused) {
            var name = tree.getName().toString();
            classNames.add(name);
            result.put(
                    name,
                    Attribution.of(docRange(getCurrentPath()), moduleSignatureRange(tree), sourceRange(tree),
                            SourceSpan.NONE, List.of()));
            kinds.put(name, SymbolKind.MODULE);
            listedNames.put(name, name);
            return super.visitModule(tree, unused);
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            var path = getCurrentPath();
            if (!isTopLevelOrMember(path)) {
                return null;
            }
            var previousClass = className;
            var simpleName = tree.getSimpleName().toString();
            if (simpleName.isEmpty()) {
                return null;
            }
            if (!visible(tree.getModifiers()
                             .getFlags(),
                    implicitPublicMembers)) {
                return null;
            }
            var binaryName = previousClass != null
                    ? previousClass + "." + simpleName
                    : unit.getPackageName() == null ? simpleName : unit.getPackageName() + "." + simpleName;
            classNames.add(binaryName);

            var previousImplicitPublic = implicitPublicMembers;
            var previousOrdinals = methodOrdinals;
            className = binaryName;
            implicitPublicMembers = tree.getKind() == Tree.Kind.INTERFACE || tree.getKind() == Tree.Kind.ANNOTATION_TYPE;
            methodOrdinals = new HashMap<>();

            var doc = docRange(path);
            result.put(
                    className,
                    Attribution.of(doc, signatureRange(tree), sourceRange(tree), SourceSpan.NONE,
                            List.of()));
            kinds.put(className, SymbolKind.classKind(tree));
            listedNames.put(className, className);
            super.visitClass(tree, unused);

            className = previousClass;
            implicitPublicMembers = previousImplicitPublic;
            methodOrdinals = previousOrdinals;
            return null;
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            if (className == null || !isDirectClassMember(getCurrentPath())) {
                return null;
            }

            var methodName = tree.getName().contentEquals("<init>") ? "<init>" : tree.getName().toString();
            int ordinal = methodOrdinals.merge(methodName, 0, Integer::sum);
            methodOrdinals.put(methodName, ordinal + 1);
            if (!visible(tree.getModifiers()
                             .getFlags(),
                    implicitPublicMembers)) {
                return null;
            }
            var params = tree.getParameters().stream()
                    .map(parameter -> parameter.getName().toString())
                    .toList();
            var key = attributionKey(className, methodName, ordinal);
            result.put(key, attribution(getCurrentPath(), tree, params));
            kinds.put(key, SymbolKind.METHOD);
            var publicName = methodName.equals("<init>") ? "new" : methodName;
            listedNames.put(key,
                    tree.getParameters().stream()
                            .map(parameter -> parameter.getType().toString())
                            .collect(Collectors.joining(",", className + "." + publicName + "(", ")")));
            return null;
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            if (className == null || !isDirectClassMember(getCurrentPath())) {
                return null;
            }
            if (!visible(tree.getModifiers()
                             .getFlags(),
                    implicitPublicMembers)) {
                return null;
            }

            var key = className + "#" + tree.getName();
            result.put(key,
                    attribution(getCurrentPath(), tree, List.of()));
            kinds.put(key, SymbolKind.FIELD);
            listedNames.put(key, className + "." + tree.getName());
            return null;
        }

        private Attribution attribution(TreePath path, Tree tree, List<String> parameters) {
            return Attribution.of(docRange(path), signatureRange(tree), sourceRange(tree),
                    implementationRange(tree), parameters);
        }

        private SourceSpan signatureRange(Tree tree) {
            var positions = trees.getSourcePositions();
            long start = positions.getStartPosition(unit, tree);
            if (start < 0) {
                return SourceSpan.NONE;
            }
            if (tree instanceof MethodTree method && method.getBody() != null) {
                long bodyStart = positions.getStartPosition(unit, method.getBody());
                return lineRange(start, bodyStart >= 0 ? bodyStart + 1 : start);
            }
            if (tree instanceof ClassTree type) {
                var name = type.getSimpleName().toString();
                int nameStart = indexOf(source, name, Math.toIntExact(start), source.length);
                if (nameStart >= 0) {
                    for (int i = nameStart + name.length(); i < source.length; i++) {
                        if (source[i] == '{') {
                            return lineRange(start, i + 1L);
                        }
                    }
                }
            }
            return sourceRange(tree);
        }

        private SourceSpan moduleSignatureRange(ModuleTree tree) {
            var positions = trees.getSourcePositions();
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            if (start < 0 || end <= start) {
                return SourceSpan.NONE;
            }
            long nameEnd = positions.getEndPosition(unit, tree.getName());
            int headerStart = nameEnd >= start ? Math.toIntExact(nameEnd) : Math.toIntExact(start);
            for (int i = headerStart; i < end; i++) {
                if (source[i] == '{') {
                    return lineRange(start, i + 1L);
                }
            }
            return sourceRange(tree);
        }

        private SourceSpan packageSignatureRange(CompilationUnitTree tree) {
            var positions = trees.getSourcePositions();
            long nameStart = positions.getStartPosition(unit, tree.getPackageName());
            long nameEnd = positions.getEndPosition(unit, tree.getPackageName());
            if (nameStart < 0 || nameEnd <= nameStart) {
                return SourceSpan.NONE;
            }
            int start = Math.toIntExact(nameStart);
            while (start >= "package".length()) {
                int candidate = start - "package".length();
                if (new String(source, candidate, "package".length()).equals("package")) {
                    start = candidate;
                    break;
                }
                start--;
            }
            for (var annotation : tree.getPackageAnnotations()) {
                long annotationStart = positions.getStartPosition(unit, annotation);
                if (annotationStart >= 0) {
                    start = Math.min(start, Math.toIntExact(annotationStart));
                }
            }
            int end = Math.toIntExact(nameEnd);
            while (end < source.length && source[end] != ';') {
                end++;
            }
            return lineRange(start, end < source.length ? end + 1L : nameEnd);
        }

        private SourceSpan leadingDocumentationRange(SourceSpan declaration) {
            if (!declaration.hasOffsets()) {
                return SourceSpan.NONE;
            }
            int end = declaration.startOffset();
            while (end >= 2 && !(source[end - 2] == '*' && source[end - 1] == '/')) {
                end--;
            }
            if (end < 2) {
                return SourceSpan.NONE;
            }
            int start = end - 2;
            while (start >= 3 && !(source[start - 3] == '/' && source[start - 2] == '*' && source[start - 1] == '*')) {
                start--;
            }
            if (start < 3) {
                return SourceSpan.NONE;
            }
            return lineRange(start - 3L, end);
        }

        private SourceSpan implementationRange(Tree tree) {
            Tree implementation = switch (tree) {
                case MethodTree method -> method.getBody();
                case VariableTree variable -> variable.getInitializer();
                default -> null;
            };
            return implementation == null ? SourceSpan.NONE : sourceRange(implementation);
        }

        private SourceSpan docRange(TreePath path) {
            if (!documentation) {
                return SourceSpan.NONE;
            }
            var docTrees = (DocTrees) trees;
            var doc = docTrees.getDocCommentTree(path);
            if (doc == null) {
                return SourceSpan.NONE;
            }
            var positions = docTrees.getSourcePositions();
            long contentStart = positions.getStartPosition(unit, doc, doc);
            long contentEnd = positions.getEndPosition(unit, doc, doc);
            if (contentStart < 0 || contentEnd < contentStart) {
                return SourceSpan.NONE;
            }

            int start = Math.toIntExact(contentStart);
            while (start >= 3 && !(source[start - 3] == '/' && source[start - 2] == '*' && source[start - 1] == '*')) {
                start--;
            }
            if (start < 3) {
                return SourceSpan.NONE;
            }
            start -= 3;

            int end = Math.toIntExact(contentEnd);
            while (end + 1 < source.length && !(source[end] == '*' && source[end + 1] == '/')) {
                end++;
            }
            if (end + 1 >= source.length) {
                return SourceSpan.NONE;
            }
            return lineRange(start, end + 2L);
        }

        private SourceSpan sourceRange(Tree tree) {
            var positions = trees.getSourcePositions();
            return lineRange(positions.getStartPosition(unit, tree), positions.getEndPosition(unit, tree));
        }

        private SourceSpan lineRange(long start, long end) {
            if (start < 0 || end <= start || end > source.length) {
                return SourceSpan.NONE;
            }
            var lines = unit.getLineMap();
            return new SourceSpan(Math.toIntExact(start), Math.toIntExact(end), Math.toIntExact(lines.getLineNumber(start)),
                    Math.toIntExact(lines.getLineNumber(end - 1)));
        }

        private boolean visible(Set<Modifier> modifiers, boolean implicitlyPublic) {
            return switch (access) {
                case PUBLIC -> implicitlyPublic || modifiers.contains(javax.lang.model.element.Modifier.PUBLIC);
                case PROTECTED -> implicitlyPublic || modifiers.contains(javax.lang.model.element.Modifier.PUBLIC) || modifiers.contains(javax.lang.model.element.Modifier.PROTECTED);
                case PACKAGE -> !modifiers.contains(javax.lang.model.element.Modifier.PRIVATE);
                case PRIVATE -> true;
            };
        }

        private static boolean isTopLevelOrMember(TreePath path) {
            var parent = path.getParentPath();
            return parent != null && (parent.getLeaf() instanceof CompilationUnitTree || parent.getLeaf() instanceof ClassTree);
        }

        private static boolean isDirectClassMember(TreePath path) {
            var parent = path.getParentPath();
            return parent != null && parent.getLeaf() instanceof ClassTree;
        }
    }

    static String attributionKey(String className, String methodName, int ordinal) {
        return className + "#" + methodName + "@" + ordinal;
    }

    public record SourceSpan(int startOffset, int endOffset, int startLine,
            int endLine) {
        boolean present() {
            return startLine > 0;
        }

        boolean hasOffsets() {
            return startOffset >= 0;
        }

        static SourceSpan lines(int startLine, int endLine) {
            return startLine > 0 ? new SourceSpan(-1, -1, startLine, endLine) : NONE;
        }

        static SourceSpan covering(SourceSpan first, SourceSpan second) {
            if (!first.present()) {
                return second;
            }
            if (!second.present()) {
                return first;
            }
            int startOffset = first.hasOffsets() && second.hasOffsets()
                    ? Math.min(first.startOffset(), second.startOffset())
                    : -1;
            int endOffset = first.hasOffsets() && second.hasOffsets()
                    ? Math.max(first.endOffset(), second.endOffset())
                    : -1;
            return new SourceSpan(
                    startOffset,
                    endOffset,
                    Math.min(first.startLine(), second.startLine()),
                    Math.max(first.endLine(), second.endLine()));
        }

        static final SourceSpan NONE = new SourceSpan(-1, -1, 0, 0);
    }

    public record Attribution(SourceSpan documentation, SourceSpan signature, SourceSpan declaration,
            SourceSpan implementation, List<String> paramNames) {
        public int docStart() {
            return documentation.startLine();
        }

        public int docEnd() {
            return documentation.endLine();
        }

        public int srcStart() {
            return declaration.startLine();
        }

        public int srcEnd() {
            return declaration.endLine();
        }

        public boolean hasDoc() {
            return documentation.present();
        }

        public boolean hasSrc() {
            return declaration.present();
        }

        static Attribution docOnly(SourceSpan documentation) {
            return new Attribution(documentation, SourceSpan.NONE, SourceSpan.NONE, SourceSpan.NONE, List.of());
        }

        static Attribution docOnly(int docStart, int docEnd) {
            return docOnly(SourceSpan.lines(docStart, docEnd));
        }

        static Attribution of(SourceSpan documentation, SourceSpan declaration, SourceSpan implementation,
                              List<String> paramNames) {
            return new Attribution(documentation, declaration, declaration, implementation, paramNames);
        }

        static Attribution of(SourceSpan documentation, SourceSpan signature, SourceSpan declaration,
                              SourceSpan implementation, List<String> paramNames) {
            return new Attribution(documentation, signature, declaration, implementation, paramNames);
        }

        static Attribution of(int docStart, int docEnd, int srcStart,
                              int srcEnd, List<String> paramNames) {
            var source = SourceSpan.lines(srcStart, srcEnd);
            return new Attribution(SourceSpan.lines(docStart, docEnd), source, source, SourceSpan.NONE, paramNames);
        }

        static final Attribution EMPTY = new Attribution(SourceSpan.NONE, SourceSpan.NONE, SourceSpan.NONE,
                SourceSpan.NONE, List.of());
    }
}
