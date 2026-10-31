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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.reflect.AccessFlag;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;

final class CallSearch implements Closeable {
    private final Options options;
    private final SearchEnvironment environment;
    private final JavaAnalysis analysis;
    private final Map<String, Set<String>> visiblePackages;
    private final boolean readsUnnamed;
    private final Set<String> selectedModules;
    private final Set<String> incoming = new HashSet<>();
    private final Set<String> outgoing = new HashSet<>();
    private final Set<String> emitted = new HashSet<>();
    private final Set<String> sourceLines = new HashSet<>();

    private CallSearch(Options options, SearchEnvironment environment, JavaAnalysis analysis) throws IOException, ToolException {
        this.options = options;
        this.environment = environment;
        this.analysis = analysis;
        var modules = environment.modules();
        var packages = new HashMap<>(modules.applicationPackages());
        packages.putAll(modules.systemPackages());
        this.visiblePackages = Map.copyOf(packages);
        this.readsUnnamed = SearchEnvironment.readsUnnamedModule(options);
        this.selectedModules = Set.copyOf(SearchEnvironment.selectedModules(options));
    }

    static CallSearch open(Options options, SearchEnvironment environment) throws IOException, ToolException {
        var analysis = JavaAnalysis.open(options, environment, List.of());
        try {
            var search = new CallSearch(options, environment, analysis);
            search.select(options.callers(), search.incoming);
            search.select(options.callees(), search.outgoing);
            return search;
        } catch (IOException | ToolException | RuntimeException | Error e) {
            analysis.close();
            throw e;
        }
    }

    private void select(List<String> targets, Set<String> selected) throws IOException, ToolException {
        for (var target : targets) {
            var selection = environment.resolveSymbolSelection(target);
            var type = analysis.resolveType(selection.path());
            var member = selection.member();
            boolean found = false;
            var members = member == null || member.equals("new")
                    ? type.getEnclosedElements()
                    : analysis.elements().getAllMembers(type);
            for (var element : members) {
                if (element instanceof ExecutableElement method && matches(member, method)) {
                    selected.add(analysis.key(method));
                    found = true;
                }
            }
            if (member != null && !found) {
                throw new ToolException(1, "Method not found: " + target);
            }
        }
    }

    private static boolean matches(String member, ExecutableElement method) {
        if (member == null) return true;
        if (member.equals("new")) return method.getKind() == ElementKind.CONSTRUCTOR;
        return method.getSimpleName().contentEquals(member);
    }

    void search(PrintWriter out) throws IOException, ToolException {
        try (var sources = environment.sources()) {
            CompiledInputs.scan(options, environment, InputStream::readAllBytes,
                    (bytes, location) -> compiled(bytes, location, sources, out));
            var candidates = new LinkedHashMap<URI, SourceCandidate>();
            for (var root : sources.explicitPaths()) {
                try (var files = Files.walk(root)) {
                    var iterator = files.filter(path -> path.toString().endsWith(".java"))
                            .filter(path -> !path.getFileName().toString().equals("module-info.java")).iterator();
                    while (iterator.hasNext()) {
                        var file = iterator.next();
                        if (!environment.includesSource(root, file)) continue;
                        var bytes = Files.readAllBytes(file);
                        if (mayContainSelectedCall(new String(bytes, StandardCharsets.UTF_8))) {
                            candidates.putIfAbsent(file.toUri(), new SourceCandidate(file, bytes));
                        }
                    }
                }
            }
            source(candidates, out);
        }
    }

    private boolean visible(JavaAnalysis context, ExecutableElement method, boolean source) {
        var module = context.elements().getModuleOf(method);
        if (module == null || module.isUnnamed()) {
            if (!readsUnnamed) return false;
        } else if (!selectedModules.contains(module.getQualifiedName().toString())) {
            var packages = visiblePackages.getOrDefault(module.getQualifiedName().toString(), Set.of());
            if (!packages.contains(context.elements().getPackageOf(method).getQualifiedName().toString())) return false;
        }
        var visibility = options.visibility() != null ? options.visibility() : source ? Visibility.ALL : Visibility.LIBRARY;
        return visibility.visibleModifiers(method.getModifiers(), false);
    }

    private boolean edge(JavaAnalysis context, ExecutableElement caller, ExecutableElement callee,
            String unit, String file, String origin, int line, String site, boolean source, PrintWriter out) {
        boolean callerMatch = incoming.contains(context.key(callee)) && visible(context, caller, source);
        boolean calleeMatch = outgoing.contains(context.key(caller)) && visible(context, callee, source);
        if (!callerMatch && !calleeMatch) return false;
        if (options.listOnly()) {
            if (callerMatch) symbol(context, caller, origin, out);
            if (calleeMatch) symbol(context, callee, origin, out);
            return true;
        }
        if (!emitted.add(origin + ":" + site + ":" + context.key(caller) + ":" + context.key(callee))) {
            return true;
        }
        if (out instanceof Output.OriginOutput output) {
            output.classOrigin(unit, origin);
            if (file != null) output.sourceOrigin(unit, file, origin);
        }
        var prefix = file != null && line > 0 ? unit + "(" + file + ":" + line + "): " : unit + ": ";
        out.println(prefix + context.signature(caller) + " -> " + context.signature(callee));
        return true;
    }

    private void symbol(JavaAnalysis context, ExecutableElement method, String origin, PrintWriter out) {
        if (emitted.add(context.key(method)) && out instanceof Output.SymbolOutput output) {
            output.symbol(SymbolKind.METHOD, context.signature(method), origin);
        }
    }

    private boolean mayContainSelectedCall(String source) {
        for (var key : incoming) {
            if (source.contains(sourceNeedle(key))) return true;
        }
        for (var key : outgoing) {
            if (source.contains(sourceNeedle(key))) return true;
        }
        return false;
    }

    private static String sourceNeedle(String key) {
        int memberStart = key.indexOf('#') + 1;
        int memberEnd = key.indexOf('(', memberStart);
        var member = key.substring(memberStart, memberEnd);
        if (!member.equals("<init>")) return member;
        int ownerEnd = memberStart - 1;
        return key.substring(key.lastIndexOf('.', ownerEnd - 1) + 1, ownerEnd);
    }

    private void source(Map<URI, SourceCandidate> candidates, PrintWriter out) throws IOException, ToolException {
        if (candidates.isEmpty()) return;
        try (var context = JavaAnalysis.open(options, environment,
                candidates.values().stream().map(SourceCandidate::file).toList())) {
            for (var unit : context.units()) {
                var candidate = candidates.get(unit.getSourceFile().toUri());
                if (candidate == null) continue;
                var file = candidate.file();
                var positions = context.trees().getSourcePositions();
                new TreePathScanner<Void, ExecutableElement>() {

                    @Override
                    public Void visitClass(ClassTree tree, ExecutableElement caller) {
                        var element = context.trees().getElement(getCurrentPath());
                        if (element instanceof TypeElement type
                                && (type.getNestingKind() == NestingKind.LOCAL || type.getNestingKind() == NestingKind.ANONYMOUS)) return null;
                        return super.visitClass(tree, null);
                    }

                    @Override
                    public Void visitMethod(MethodTree tree, ExecutableElement caller) {
                        var element = context.trees().getElement(getCurrentPath());
                        super.visitMethod(tree, element instanceof ExecutableElement method ? method : null);
                        return null;
                    }

                    private void invocation(Tree tree, ExecutableElement caller) {
                        if (caller == null) return;
                        var element = context.trees().getElement(getCurrentPath());
                        if (!(element instanceof ExecutableElement callee)) return;
                        long position = positions.getStartPosition(unit, tree);
                        long end = positions.getEndPosition(unit, tree);
                        if (position < 0 || end < 0) return;
                        int line = (int) unit.getLineMap().getLineNumber(position);
                        var owner = (TypeElement) caller.getEnclosingElement();
                        while (owner.getEnclosingElement() instanceof TypeElement parent) owner = parent;
                        var name = owner.getQualifiedName().toString();
                        var fileName = file.getFileName().toString();
                        var origin = file.toUri().toString();
                        if (edge(context, caller, callee, name, fileName, origin, line,
                                Long.toString(position), true, out)) {
                            enrich(name, fileName, new SourceUnit(origin,
                                    candidate.bytes(), true, file, null), line, out);
                        }
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree tree, ExecutableElement caller) {
                        invocation(tree, caller);
                        return super.visitMethodInvocation(tree, caller);
                    }

                    @Override
                    public Void visitNewClass(NewClassTree tree, ExecutableElement caller) {
                        invocation(tree, caller);
                        return super.visitNewClass(tree, caller);
                    }
                }.scan(unit, null);
            }
            context.checkDiagnostics();
        }
    }

    private record SourceCandidate(Path file, byte[] bytes) {}

    private void compiled(byte[] bytes, String location, SourceLookup sources, PrintWriter out) throws IOException, ToolException {
        var constants = new String(bytes, StandardCharsets.ISO_8859_1);
        boolean mentionsCallee = incoming.stream().anyMatch(key -> {
            var name = key.substring(key.indexOf('#') + 1, key.indexOf('('));
            return constants.contains(name.equals("<init>") ? key.substring(0, key.indexOf('#')).replace('.', '/') : name);
        });
        boolean mentionsCaller = outgoing.stream().anyMatch(key -> constants.contains(key.substring(0, key.indexOf('#')).replace('.', '/')));
        if (!mentionsCallee && !mentionsCaller) return;
        var model = ClassFile.of().parse(bytes);
        var internal = model.thisClass().asInternalName();
        var name = SearchEnvironment.listedClassName(internal + ".class");
        if (name == null || internal.equals("module-info") || sources.hasOrdinarySource(model)) return;
        var lambdas = new HashMap<String, MethodModel>();
        for (var method : model.methods()) {
            if (method.code().isEmpty()) continue;
            for (var element : method.code().orElseThrow()) {
                if (element instanceof InvokeDynamicInstruction dynamic) {
                    for (var argument : dynamic.bootstrapArgs()) {
                        if (argument instanceof DirectMethodHandleDesc handle && handle.methodName().startsWith("lambda$")
                                && handle.owner().descriptorString().equals("L" + internal + ";")) {
                            lambdas.put(handle.methodName() + handle.lookupDescriptor(), method);
                        }
                    }
                }
            }
        }
        for (var method : model.methods()) {
            var lexical = method;
            var visited = new HashSet<MethodModel>();
            while (lexical.flags().has(AccessFlag.SYNTHETIC) && visited.add(lexical)) {
                var parent = lambdas.get(lexical.methodName().stringValue() + lexical.methodType().stringValue());
                if (parent == null) break;
                lexical = parent;
            }
            if (lexical.flags().has(AccessFlag.SYNTHETIC) || lexical.methodName().stringValue().equals("<clinit>") || method.code().isEmpty()) continue;
            var code = method.code().orElseThrow();
            ExecutableElement caller = null;
            int bci = 0;
            for (var element : code) {
                if (!(element instanceof Instruction instruction)) continue;
                if (instruction instanceof InvokeInstruction invoke) {
                    var owner = invoke.owner().asInternalName().replace('/', '.').replace('$', '.');
                    var methodName = invoke.name().stringValue();
                    var callerPrefix = name + "#" + lexical.methodName().stringValue();
                    if (incoming.stream().anyMatch(key -> methodName.equals("<init>")
                            ? key.startsWith(owner + "#<init>(") : key.contains("#" + methodName + "("))
                            || outgoing.stream().anyMatch(key -> key.startsWith(callerPrefix + "("))) {
                        var callee = analysis.method(owner, methodName, invoke.type().stringValue());
                        if (!incoming.contains(analysis.key(callee)) && outgoing.stream().noneMatch(key -> key.startsWith(callerPrefix + "("))) {
                            bci += instruction.sizeInBytes();
                            continue;
                        }
                        if (caller == null) caller = analysis.method(name, lexical.methodName().stringValue(), lexical.methodType().stringValue());
                        var sourceFile = model.findAttribute(Attributes.sourceFile()).map(value -> value.sourceFile().stringValue()).orElse(null);
                        int packageEnd = internal.lastIndexOf('/');
                        var unit = packageEnd < 0 ? "" : internal.substring(0, packageEnd + 1).replace('/', '.');
                        unit += sourceFile == null ? internal.substring(packageEnd + 1).split("\\$")[0] : sourceFile.replaceFirst("\\.java$", "");
                        int line = line(code, bci);
                        if (edge(analysis, caller, callee, unit, sourceFile, location, line,
                                method.methodName().stringValue() + method.methodType().stringValue() + ":" + bci, false, out)
                                && enrichedSource()) {
                            int slash = internal.lastIndexOf('/');
                            var relative = (slash < 0 ? "" : internal.substring(0, slash + 1)) + sourceFile;
                            enrich(unit, sourceFile, sources.find(relative, location, internal + ".class"), line, out);
                        }
                    }
                }
                bci += instruction.sizeInBytes();
            }
        }
    }

    private boolean enrichedSource() {
        return !options.listOnly() && options.source() != SourceScope.NONE && options.source() != SourceScope.SIGNATURE;
    }

    private void enrich(String unit, String file, SourceUnit source, int line, PrintWriter out) {
        if (!enrichedSource()) return;
        if (source == null) {
            if (out instanceof Output.SourceWarningOutput warnings) warnings.sourceUnavailable(unit);
            return;
        }
        var model = SourceModel.parse(source.bytes());
        var span = SourceModel.SourceSpan.lines(line, line);
        if (options.source() == SourceScope.UNIT) {
            span = SourceModel.SourceSpan.lines(1, model.contentLineCount());
        } else if (options.source() != SourceScope.BODY && source.javaSource()) {
            var attributions = model.structure(file, Visibility.ALL, true).attribution();
            var candidates = attributions.entrySet().stream()
                    .filter(entry -> options.source() != SourceScope.TYPE || !entry.getKey().contains("#"))
                    .map(Map.Entry::getValue)
                    .filter(value -> value.declaration().present() && value.declaration().startLine() <= line && line <= value.declaration().endLine());
            var comparator = Comparator.comparingInt((SourceModel.Attribution value) -> value.declaration().endLine() - value.declaration().startLine());
            var selected = (options.source() == SourceScope.TYPE ? candidates.max(comparator) : candidates.min(comparator)).orElse(null);
            if (selected != null) {
                span = options.source() == SourceScope.DOC ? selected.signature() : selected.declaration();
                if (selected.documentation().present()) {
                    span = SourceModel.SourceSpan.lines(selected.documentation().startLine(), span.endLine());
                }
            }
        }
        if (!span.present()) return;
        for (int n = span.startLine(); n <= span.endLine() && n <= model.contentLineCount(); n++) {
            if (sourceLines.add(source.origin() + ":" + n)) {
                DeclarationSearch.printSourceLine(out, unit, file, source.origin(), n, model.line(n));
            }
        }
    }

    private static int line(CodeModel code, int bci) {
        int line = -1;
        int start = -1;
        var table = code.findAttribute(Attributes.lineNumberTable()).orElse(null);
        if (table != null) {
            for (var entry : table.lineNumbers()) {
                if (entry.startPc() <= bci && entry.startPc() >= start) {
                    start = entry.startPc();
                    line = entry.lineNumber();
                }
            }
        }
        return line;
    }

    @Override
    public void close() throws IOException {
        analysis.close();
    }
}
