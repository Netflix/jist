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

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

/** One on-demand javac analysis, without annotation processing or generated ClassFiles. */
final class JavaAnalysis implements Closeable {
    private final StandardJavaFileManager fileManager;
    private final DiagnosticCollector<JavaFileObject> diagnostics;
    private final SearchEnvironment environment;
    private final JavacTask task;
    private final List<CompilationUnitTree> units;

    private JavaAnalysis(StandardJavaFileManager fileManager, DiagnosticCollector<JavaFileObject> diagnostics,
            SearchEnvironment environment, JavacTask task, List<CompilationUnitTree> units) {
        this.fileManager = fileManager;
        this.diagnostics = diagnostics;
        this.environment = environment;
        this.task = task;
        this.units = units;
    }

    static JavaAnalysis open(Options options, SearchEnvironment environment, List<Path> sourceFiles)
            throws IOException, ToolException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        try {
            var arguments = new ArrayList<>(environment.compilerOptions(true, false));
            arguments.add("-Xprefer:source");
            var descriptors = environment.moduleSourceInputs().stream()
                    .map(input -> input.location().resolve("module-info.java"))
                    .filter(Files::isRegularFile)
                    .toList();
            if (!options.moduleSourcePaths().isEmpty()) {
                for (var option : List.of("--source-path", "--patch-module")) {
                    int index = arguments.indexOf(option);
                    if (index >= 0) {
                        arguments.subList(index, index + 2).clear();
                    }
                }
                for (var path : options.moduleSourcePaths()) {
                    arguments.addAll(List.of("--module-source-path", path));
                }
                // javac requires an output location with --module-source-path; analyze never writes ClassFiles.
                manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(Path.of(System.getProperty("java.io.tmpdir"))));
            }
            var files = new LinkedHashSet<>(descriptors);
            files.addAll(sourceFiles);
            Iterable<? extends JavaFileObject> inputs = files.isEmpty()
                    ? List.of(new SourceModel.StringJavaFileObject("__JistAnalysis.java", new char[0]))
                    : manager.getJavaFileObjectsFromPaths(files);
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, arguments, null, inputs);
            var units = new ArrayList<CompilationUnitTree>();
            task.parse().forEach(units::add);
            task.analyze();
            var analysis = new JavaAnalysis(manager, diagnostics, environment, task, List.copyOf(units));
            analysis.checkDiagnostics();
            return analysis;
        } catch (IOException | ToolException | RuntimeException | Error e) {
            manager.close();
            throw e;
        }
    }

    static JavaAnalysis openAttached(SearchEnvironment environment, SourceUnit source, String binaryName,
            String moduleName) throws IOException, ToolException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        try {
            var arguments = new ArrayList<>(environment.compilerOptions(true, false));
            for (var option : List.of("--source-path", "--patch-module")) {
                int index = arguments.indexOf(option);
                if (index >= 0) arguments.subList(index, index + 2).clear();
            }
            var input = new SourceModel.StringJavaFileObject(binaryName.replace('.', '/') + ".java",
                    new String(source.bytes(), StandardCharsets.UTF_8).toCharArray());
            var taskManager = moduleName == null ? manager : new PatchedSourceFileManager(manager, List.of(input), moduleName);
            var task = (JavacTask) compiler.getTask(null, taskManager, diagnostics, arguments, null, List.of(input));
            var units = new ArrayList<CompilationUnitTree>();
            task.parse().forEach(units::add);
            task.analyze();
            var analysis = new JavaAnalysis(manager, diagnostics, environment, task, List.copyOf(units));
            analysis.checkDiagnostics();
            return analysis;
        } catch (IOException | ToolException | RuntimeException | Error e) {
            manager.close();
            throw e;
        }
    }

    Elements elements() {
        return task.getElements();
    }

    Types types() {
        return task.getTypes();
    }

    Trees trees() {
        return Trees.instance(task);
    }

    List<CompilationUnitTree> units() {
        return units;
    }

    TypeElement resolveType(String name) throws IOException, ToolException {
        var selection = environment.resolveSymbolSelection(name);
        if (selection.member() != null || !environment.classTargetExists(name)) {
            throw new ToolException(1, "Type not found: " + name);
        }
        var source = environment.resolveSourceFile(name);
        if (source != null) {
            var fileName = source.getFileName().toString();
            var structure = SourceModel.parse(Files.readAllBytes(source)).structure(fileName, Visibility.ALL, false);
            readSourceUnit(DeclarationSearch.sourceUnitName(structure, fileName));
        }
        var type = elements().getTypeElement(name);
        checkDiagnostics();
        if (type == null) {
            throw new ToolException(1, "Cannot resolve type: " + name);
        }
        return type;
    }

    void readSourceUnit(String name) throws ToolException {
        elements().getTypeElement(name);
        checkDiagnostics();
    }

    ExecutableElement method(String owner, String name, String descriptor) throws IOException, ToolException {
        var type = elements().getTypeElement(owner);
        if (type == null) type = resolveType(owner);
        var members = name.equals("<init>") ? type.getEnclosedElements() : elements().getAllMembers(type);
        for (var member : members) {
            if (member instanceof ExecutableElement method
                    && method.getSimpleName().contentEquals(name)
                    && descriptor(method).equals(descriptor)) {
                return method;
            }
        }
        checkDiagnostics();
        throw new ToolException(2, "Cannot resolve method: " + owner + "." + name + descriptor);
    }

    String descriptor(ExecutableElement method) {
        return method.getParameters().stream()
                .map(parameter -> descriptor(parameter.asType()))
                .collect(Collectors.joining("", "(", ")")) + descriptor(method.getReturnType());
    }

    private String descriptor(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case CHAR -> "C";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) type).getComponentType());
            case DECLARED -> "L" + elements().getBinaryName((TypeElement) ((DeclaredType) type).asElement())
                    .toString().replace('.', '/') + ";";
            default -> descriptor(types().erasure(type));
        };
    }

    String signature(ExecutableElement method) {
        var owner = ((TypeElement) method.getEnclosingElement()).getQualifiedName().toString();
        var name = method.getKind() == ElementKind.CONSTRUCTOR ? "new" : method.getSimpleName().toString();
        return method.getParameters().stream()
                .map(parameter -> types().erasure(parameter.asType()).toString())
                .collect(Collectors.joining(",", owner + "." + name + "(", ")"));
    }

    String key(ExecutableElement method) {
        return ((TypeElement) method.getEnclosingElement()).getQualifiedName() + "#"
                + method.getSimpleName() + descriptor(method);
    }

    void checkDiagnostics() throws ToolException {
        for (var diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Kind.ERROR) {
                throw new ToolException(2, "Cannot resolve relationships: " + diagnostic.getMessage(Locale.ROOT));
            }
        }
    }

    @Override
    public void close() throws IOException {
        fileManager.close();
    }
}
