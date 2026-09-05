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

import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.netflix.tools.jist.Output.InteractivePrintWriter;
import com.netflix.tools.jist.Output.OriginOutput;
import com.netflix.tools.jist.Output.SourceHighlight;
import com.netflix.tools.jist.SourceModel.Attribution;
import com.netflix.tools.jist.SourceModel.SourceSpan;
import com.netflix.tools.jist.SourceModel.StringJavaFileObject;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberReferenceTree.ReferenceMode;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import static com.netflix.tools.jist.ClassFileRenderer.compilationUnitName;
import static com.netflix.tools.jist.ClassFileRenderer.listedClassFlags;
import static com.netflix.tools.jist.DeclarationSearch.enclosingTopLevelTypeAttribution;
import static com.netflix.tools.jist.DeclarationSearch.warnSourceUnavailable;
import static com.netflix.tools.jist.SearchEnvironment.parsePathList;
import static com.netflix.tools.jist.SearchEnvironment.readsUnnamedModule;
import static com.netflix.tools.jist.SearchEnvironment.resolveClass;
import static com.netflix.tools.jist.SearchEnvironment.selectedModules;
import static com.netflix.tools.jist.SearchEnvironment.unitLocation;

/** Finds semantic usages in ClassFiles and source. */
final class UsageSearch {
    private final SearchEnvironment environment;

    UsageSearch(SearchEnvironment environment) {
        this.environment = environment;
    }

    private static final String CLASS_EXT = ".class";
    private static final String JAVA_EXT = ".java";

    void listUsages(Options opts, PrintWriter out, PrintWriter err) throws IOException, ToolException {
        var query = environment.resolveSymbolSelection(opts.target());
        if (query.path().endsWith(JAVA_EXT) || query.path().endsWith(CLASS_EXT) || Files.isRegularFile(Path.of(query.path()))) {
            throw new ToolException(1, "--usages requires a class or member target");
        }
        var target = resolveUsageTarget(query, opts);
        var usageOutput = new UsageOutput(out, err, opts.source());
        scanCompiledUsages(opts, target, usageOutput);
        var visited = new HashSet<Path>();
        for (var sourceRoot : environment.sourceRoots()) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (var files = Files.walk(sourceRoot)) {
                var iterator = files.filter(Files::isRegularFile)
                                    .filter(path -> path.toString().endsWith(JAVA_EXT))
                                    .iterator();
                while (iterator.hasNext()) {
                    var sourceFile = iterator.next().toRealPath();
                    if (!visited.add(sourceFile)) {
                        continue;
                    }
                    var source = Files.readString(sourceFile, StandardCharsets.UTF_8);
                    if (!target.mayOccurIn(source)) {
                        continue;
                    }
                    scanUsages(sourceFile, opts, target, usageOutput);
                }
            }
        }
        out.flush();
    }

    private void scanCompiledUsages(Options opts, UsageTarget target, UsageOutput out) throws IOException, ToolException {
        try (var sources = SourceLookup.open(opts.sourcePath(), environment.moduleSourceRoots(), opts.system())) {
            var classReader = new UsageClassReader(target);
            out.sourceAttributor(location -> attributeAttachedSourceUnit(location, opts, target, out));
            try {
                if (readsUnnamedModule(opts)) {
                    for (var entry : parsePathList(opts.classPath())) {
                        scanCompiledUsageEntry(entry, target, sources, classReader, out);
                    }
                }
                for (var module : environment.moduleInputs()) {
                    scanCompiledUsageEntry(module.location(), target, sources, classReader, out);
                }
                var system = opts.system();
                if ("none".equals(system)) {
                    return;
                }
                var systemModules = environment.modules()
                        .systemPackages()
                        .keySet();
                if (systemModules.isEmpty()) {
                    return;
                }
                if (system != null && !Files.isRegularFile(Path.of(system)
                        .resolve("lib/modules"))) {
                    return;
                }

                FileSystem fileSystem;
                boolean close;
                if (system == null) {
                    try {
                        fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"));
                        close = false;
                    } catch (FileSystemNotFoundException _) {
                        fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of());
                        close = true;
                    }
                } else {
                    fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", system));
                    close = true;
                }
                try {
                    var modules = fileSystem.getPath("/modules");
                    if (!Files.isDirectory(modules)) {
                        return;
                    }
                    for (var moduleName : systemModules) {
                        var module = modules.resolve(moduleName);
                        if (!Files.isDirectory(module)) {
                            continue;
                        }
                        try (var files = Files.walk(module)) {
                            var iterator = files.filter(Files::isRegularFile)
                                                .filter(path -> path.toString().endsWith(CLASS_EXT))
                                                .iterator();
                            while (iterator.hasNext()) {
                                var classFile = iterator.next();
                                var bytes = classReader.read(classFile);
                                if (bytes != null) {
                                    scanCompiledUsageClass(bytes, unitLocation(classFile), target, sources, out);
                                }
                            }
                        }
                    }
                } finally {
                    if (close) {
                        fileSystem.close();
                    }
                }
            } finally {
                out.sourceAttributor(null);
            }
        }
    }

    private void scanCompiledUsageEntry(Path entry, UsageTarget target, SourceLookup sources,
            UsageClassReader classReader, UsageOutput out)
            throws IOException {
        if (Files.isDirectory(entry)) {
            try (var files = Files.walk(entry)) {
                var iterator = files.filter(Files::isRegularFile)
                                    .filter(path -> path.toString().endsWith(CLASS_EXT))
                                    .iterator();
                while (iterator.hasNext()) {
                    var classFile = iterator.next();
                    var bytes = classReader.read(classFile);
                    if (bytes != null) {
                        scanCompiledUsageClass(bytes, unitLocation(classFile), target, sources, out);
                    }
                }
            }
        } else if (Files.isRegularFile(entry)) {
            try (var jar = new JarFile(entry.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                var iterator = jar.versionedStream()
                                  .filter(candidate -> !candidate.isDirectory())
                                  .filter(candidate -> candidate.getName().endsWith(CLASS_EXT))
                                  .iterator();
                while (iterator.hasNext()) {
                    var jarEntry = iterator.next();
                    try (var input = jar.getInputStream(jarEntry)) {
                        var bytes = classReader.read(input);
                        if (bytes != null) {
                            scanCompiledUsageClass(bytes, "jar:" + entry.toAbsolutePath()
                                    .normalize()
                                    .toUri()
                                    + "!/" + jarEntry.getRealName(),
                                    target, sources, out);
                        }
                    }
                }
            }
        }
    }

    private void scanCompiledUsageClass(byte[] bytes, String location, UsageTarget target,
            SourceLookup sources, UsageOutput out)
            throws IOException {
        var model = ClassFile.of().parse(bytes);
        if (model.isModuleInfo() || hasOrdinarySource(model, sources.explicitPaths())) {
            return;
        }
        var enclosingClass = displayClassName(model);
        var lexicalEnclosing = compiledLexicalEnclosingTarget(model);
        UsageLocation usageLocation = null;
        boolean classMetadataMatches = model.superclass().isPresent() && target.matchesBytecodeType(model.superclass()
                .get()
                .asInternalName());
        if (!classMetadataMatches) {
            for (var parent : model.interfaces()) {
                if (target.matchesBytecodeType(parent.asInternalName())) {
                    classMetadataMatches = true;
                    break;
                }
            }
        }
        classMetadataMatches |= signatureMatches(target, model);
        if (classMetadataMatches) {
            usageLocation = compiledUsageLocation(model, location, sources);
            out.print(enclosingClass, usageLocation, -1, DeclarationHint.CLASS);
        }
        for (var field : model.fields()) {
            if (!target.matchesBytecodeDescriptor(field.fieldType()
                    .stringValue())
                    && !signatureMatches(target, field)) {
                continue;
            }
            if (usageLocation == null) {
                usageLocation = compiledUsageLocation(model, location, sources);
            }
            out.print(enclosingClass + "." + field.fieldName().stringValue(),
                    usageLocation, -1, DeclarationHint.FIELD);
        }
        for (var method : model.methods()) {
            var methodName = method.methodName().stringValue();
            if (method.flags().has(AccessFlag.BRIDGE) || method.flags().has(AccessFlag.SYNTHETIC) && !methodName.startsWith("lambda$")) {
                continue;
            }
            var code = method.findAttribute(Attributes.code()).orElse(null);
            if (target.matchesBytecodeOverride(model, method)) {
                if (usageLocation == null) {
                    usageLocation = compiledUsageLocation(model, location, sources);
                }
                var line = code != null ? lineNumber(code, 0) : -1;
                out.print(compiledEnclosingTarget(enclosingClass, method.methodName().stringValue()), usageLocation,
                        line, DeclarationHint.METHOD);
            }
            boolean generatedEnumMetadata = model.flags().has(AccessFlag.ENUM) && (methodName.equals("valueOf") || ConstantDescs.INIT_NAME.equals(methodName));
            boolean methodMetadataMatches = !generatedEnumMetadata
                    && (target.matchesBytecodeDescriptor(method.methodType()
                            .stringValue())
                            || signatureMatches(target, method) || exceptionsMatch(target, method));
            if (methodMetadataMatches) {
                if (usageLocation == null) {
                    usageLocation = compiledUsageLocation(model, location, sources);
                }
                var enclosingTarget = lexicalEnclosing != null ? lexicalEnclosing : compiledEnclosingTarget(enclosingClass, method.methodName()
                        .stringValue());
                var line = code != null ? lineNumber(code, 0) : -1;
                out.print(enclosingTarget, usageLocation, line, DeclarationHint.METHOD);
            }
            if (code == null) {
                continue;
            }
            var enclosingTarget = lexicalEnclosing != null ? lexicalEnclosing : compiledEnclosingTarget(enclosingClass, method.methodName()
                    .stringValue());
            int bci = 0;
            for (var element : code) {
                if (!(element instanceof Instruction instruction)) {
                    continue;
                }
                if (matchesCompiledInstruction(target, model, instruction)) {
                    if (usageLocation == null) {
                        usageLocation = compiledUsageLocation(model, location, sources);
                    }
                    var line = lineNumber(code, bci);
                    out.print(enclosingTarget, usageLocation, line, DeclarationHint.METHOD);
                }
                bci += instruction.sizeInBytes();
            }
        }
    }

    private static boolean signatureMatches(UsageTarget target, AttributedElement element) {
        var signature = element.findAttribute(Attributes.signature()).orElse(null);
        return signature != null && target.matchesBytecodeSignature(signature.signature()
                .stringValue());
    }

    private static boolean exceptionsMatch(UsageTarget target, MethodModel method) {
        var exceptions = method.findAttribute(Attributes.exceptions()).orElse(null);
        if (exceptions == null) {
            return false;
        }
        for (var exception : exceptions.exceptions()) {
            if (target.matchesBytecodeType(exception.asInternalName())) {
                return true;
            }
        }
        return false;
    }

    private UsageLocation compiledUsageLocation(ClassModel model, String classLocation, SourceLookup sources) throws IOException {
        var className = displayClassName(model);
        var unitName = compilationUnitName(model, className);
        var sourceFileName = model.findAttribute(Attributes.sourceFile())
                .map(attribute -> attribute.sourceFile().stringValue())
                .orElse(unitName.substring(unitName.lastIndexOf('.') + 1) + JAVA_EXT);
        var sourceFile = model.findAttribute(Attributes.sourceFile());
        if (sourceFile.isPresent()) {
            var internalName = model.thisClass().asInternalName();
            var slash = internalName.lastIndexOf('/');
            var relativePath = (slash < 0 ? "" : internalName.substring(0, slash + 1))
                    + sourceFile.get()
                                .sourceFile()
                                .stringValue();
            var source = sources.find(relativePath, classLocation, internalName + CLASS_EXT);
            if (source != null) {
                return new UsageLocation(unitName, sourceFileName, classLocation, source);
            }
        }
        return new UsageLocation(unitName, sourceFileName, classLocation, null);
    }

    private static final class UsageClassReader {
        private final UsageTarget target;
        private byte[] buffer = new byte[16 * 1024];

        UsageClassReader(UsageTarget target) {
            this.target = target;
        }

        byte[] read(Path path) throws IOException {
            try (var input = Files.newInputStream(path)) {
                return read(input);
            }
        }

        byte[] read(InputStream input) throws IOException {
            int length = 0;
            while (true) {
                int count = input.read(buffer, length, buffer.length - length);
                if (count < 0) {
                    break;
                }
                length += count;
                if (length < buffer.length) {
                    continue;
                }
                int next = input.read();
                if (next < 0) {
                    break;
                }
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
                buffer[length++] = (byte) next;
            }
            return target.mayOccurIn(buffer, length) ? Arrays.copyOf(buffer, length) : null;
        }
    }

    private boolean hasOrdinarySource(ClassModel model, List<Path> sourcePaths) {
        var sourceFile = model.findAttribute(Attributes.sourceFile());
        if (sourceFile.isEmpty() || !sourceFile.get()
                .sourceFile()
                .stringValue()
                .endsWith(JAVA_EXT)) {
            return false;
        }
        var internalName = model.thisClass().asInternalName();
        var slash = internalName.lastIndexOf('/');
        var relativePath = (slash < 0 ? "" : internalName.substring(0, slash + 1))
                + sourceFile.get()
                            .sourceFile()
                            .stringValue();
        for (var root : sourcePaths) {
            if (Files.isDirectory(root) && Files.isRegularFile(root.resolve(relativePath))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCompiledInstruction(UsageTarget target, ClassModel model, Instruction instruction) {
        if (instruction instanceof InvokeInstruction invoke) {
            var name = invoke.name().stringValue();
            return target.matchesBytecodeMethod(invoke.owner().asInternalName(), name,
                            invoke.type().stringValue())
                    || target.matchesDirectSubtypeMethod(
                            model,
                            invoke.owner().asInternalName(),
                            name,
                            invoke.type().stringValue())
                    || !ConstantDescs.INIT_NAME.equals(name) && target.matchesBytecodeType(invoke.owner()
                            .asInternalName());
        }
        if (instruction instanceof FieldInstruction field) {
            return target.matchesBytecodeField(
                    field.owner().asInternalName(),
                    field.name().stringValue(),
                    field.type().stringValue())
                    || target.matchesBytecodeType(field.owner()
                    .asInternalName());
        }
        if (instruction instanceof InvokeDynamicInstruction dynamic) {
            for (var argument : dynamic.bootstrapArgs()) {
                if (argument instanceof DirectMethodHandleDesc handle && target.matchesMethodHandle(handle)) {
                    return true;
                }
            }
            return false;
        }
        if (instruction instanceof NewObjectInstruction object) {
            return target.matchesBytecodeType(object.className()
                    .asInternalName());
        }
        if (instruction instanceof TypeCheckInstruction check) {
            return target.matchesBytecodeType(check.type()
                    .asInternalName());
        }
        if (instruction instanceof NewReferenceArrayInstruction array) {
            return target.matchesBytecodeType(array.componentType()
                    .asInternalName());
        }
        if (instruction instanceof NewMultiArrayInstruction array) {
            return target.matchesBytecodeDescriptor(array.arrayType()
                    .asInternalName());
        }
        if (instruction instanceof LoadConstantInstruction constant && constant.constantEntry() instanceof ClassEntry type) {
            return target.matchesBytecodeType(type.asInternalName());
        }
        return false;
    }

    private static int lineNumber(CodeAttribute code, int bci) {
        var table = code.findAttribute(Attributes.lineNumberTable()).orElse(null);
        if (table == null) {
            return -1;
        }
        int line = -1;
        int start = -1;
        for (var entry : table.lineNumbers()) {
            if (entry.startPc() <= bci && entry.startPc() >= start) {
                start = entry.startPc();
                line = entry.lineNumber();
            }
        }
        return line;
    }

    private static String compiledEnclosingTarget(String owner, String methodName) {
        if (ConstantDescs.CLASS_INIT_NAME.equals(methodName)) {
            return owner;
        }
        if (ConstantDescs.INIT_NAME.equals(methodName)) {
            return owner + ".new";
        }
        if (methodName.startsWith("lambda$")) {
            int end = methodName.lastIndexOf('$');
            if (end > "lambda$".length()) {
                return owner + "." + methodName.substring("lambda$".length(), end);
            }
        }
        return owner + "." + methodName;
    }

    private static String compiledLexicalEnclosingTarget(ClassModel model) {
        var enclosing = model.findAttribute(Attributes.enclosingMethod()).orElse(null);
        if (enclosing == null) {
            return null;
        }
        var owner = enclosing.enclosingClass()
                             .asInternalName()
                             .replace('/', '.')
                             .replace('$', '.');
        var method = enclosing.enclosingMethodName().orElse(null);
        return method == null ? owner : compiledEnclosingTarget(owner, method.stringValue());
    }

    private UsageTarget resolveUsageTarget(SymbolSelection query, Options opts) throws IOException, ToolException {
        var binaryName = query.path();
        var resolved = readsUnnamedModule(opts) ? resolveClass(binaryName, parsePathList(opts.classPath())) : null;
        if (resolved == null) {
            resolved = environment.resolveModuleClass(binaryName);
        }
        if (resolved == null) {
            resolved = environment.resolveSystemClass(binaryName);
        }
        var sourceFile = resolved == null ? environment.resolveSourceFile(binaryName) : null;
        if (resolved == null && sourceFile == null) {
            throw new ToolException(1, "Class not found: " + query.path());
        }

        var access = opts.access() != null
                ? opts.access()
                : query.member() != null ? Access.PRIVATE : Access.PROTECTED;
        var keys = resolved != null ? usageKeys(resolved.classes(), binaryName, query.member(), access) : usageKeys(sourceFile, binaryName, query.member(), access);
        if (keys.isEmpty()) {
            throw new ToolException(1, "Member not found: " + binaryName + "." + query.member());
        }
        var bytecodeTypes = resolved != null ? usageTypeInternalNames(resolved.classes(), binaryName, query.member()) : Set.<String>of();
        var bytecodeOwners = resolved != null ? usageOwnerInternalNames(resolved.classes(), binaryName) : Set.<String>of();
        var bytecodeMethods = resolved != null ? usageBytecodeMethods(resolved.classes(), binaryName, query.member(), access) : Set.<BytecodeMethodKey>of();
        var bytecodeFields = resolved != null ? usageBytecodeFields(resolved.classes(), binaryName, query.member(), access) : Set.<BytecodeFieldKey>of();
        var typeNeedles = bytecodeTypes.stream()
                .map(name -> name.getBytes(StandardCharsets.UTF_8))
                .toList();
        var ownerNeedles = bytecodeOwners.stream()
                .map(name -> name.getBytes(StandardCharsets.UTF_8))
                .toList();
        var descriptorNeedles = bytecodeTypes.stream()
                .map(name -> "L" + name + ";")
                .toList();
        var genericSignatureNeedles = bytecodeTypes.stream()
                .map(name -> "L" + name + "<")
                .toList();
        var memberNeedle = query.member() != null && !query.member().equals("new")
                ? query.member().getBytes(StandardCharsets.UTF_8)
                : null;
        return new UsageTarget(
                Set.copyOf(keys),
                bytecodeTypes,
                bytecodeMethods,
                bytecodeFields,
                typeNeedles,
                ownerNeedles,
                descriptorNeedles,
                genericSignatureNeedles,
                memberNeedle,
                query.member() != null && query.member().equals("new"));
    }

    private static Set<UsageKey> usageKeys(List<ClassModel> classes, String binaryName, String member,
            Access access) {
        var canonicalName = binaryName.replace('$', '.');
        var selected = classes.stream()
                .filter(model -> displayClassName(model).equals(canonicalName))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return Set.of();
        }
        if (member == null) {
            return Set.of(new UsageKey(UsageKind.TYPE, canonicalName, null));
        }

        var keys = new HashSet<UsageKey>();
        for (var field : selected.fields()) {
            if (!field.flags().has(AccessFlag.SYNTHETIC) && access.visible(field.flags()) && field.fieldName().equalsString(member)) {
                keys.add(new UsageKey(UsageKind.FIELD, canonicalName, member));
            }
        }
        for (var method : selected.methods()) {
            if (method.flags().has(AccessFlag.SYNTHETIC) || method.flags().has(AccessFlag.BRIDGE) || !access.visible(method.flags())) {
                continue;
            }
            var methodName = method.methodName().stringValue();
            if (ConstantDescs.CLASS_INIT_NAME.equals(methodName)) {
                continue;
            }
            if (selected.flags().has(AccessFlag.ENUM) && (methodName.equals("values") || methodName.equals("valueOf"))) {
                continue;
            }
            if (ConstantDescs.INIT_NAME.equals(methodName) && member.equals("new")) {
                keys.add(new UsageKey(UsageKind.CONSTRUCTOR, canonicalName, "new"));
            } else if (methodName.equals(member)) {
                keys.add(new UsageKey(UsageKind.METHOD, canonicalName, member));
            }
        }
        var selectedName = selected.thisClass().asInternalName();
        for (var model : classes) {
            var name = model.thisClass().asInternalName();
            if (name.startsWith(selectedName + "$")
                    && name.indexOf('$', selectedName.length() + 1) < 0
                    && name.substring(selectedName.length() + 1).equals(member)
                    && access.visible(listedClassFlags(model))) {
                keys.add(new UsageKey(UsageKind.TYPE, displayClassName(model), null));
            }
        }
        return Set.copyOf(keys);
    }

    static String displayClassName(ClassModel model) {
        return model.thisClass()
                    .asInternalName()
                    .replace('/', '.')
                    .replace('$', '.');
    }

    private static Set<String> usageTypeInternalNames(List<ClassModel> classes, String binaryName, String member) {
        var canonicalName = binaryName.replace('$', '.');
        var selected = classes.stream()
                .filter(model -> displayClassName(model).equals(canonicalName))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return Set.of();
        }
        if (member == null) {
            return Set.of(selected.thisClass()
                                  .asInternalName());
        }
        var selectedName = selected.thisClass().asInternalName();
        return classes.stream()
                .map(model -> model.thisClass().asInternalName())
                .filter(name ->
                        name.startsWith(selectedName + "$") && name.indexOf('$', selectedName.length() + 1) < 0 && name.substring(selectedName.length() + 1).equals(member))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> usageOwnerInternalNames(List<ClassModel> classes, String binaryName) {
        var canonicalName = binaryName.replace('$', '.');
        return classes.stream()
                .filter(model -> displayClassName(model).equals(canonicalName))
                .map(model -> model.thisClass().asInternalName())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<BytecodeMethodKey> usageBytecodeMethods(List<ClassModel> classes, String binaryName, String member,
            Access access) {
        if (member == null) {
            return Set.of();
        }
        var canonicalName = binaryName.replace('$', '.');
        var selected = classes.stream()
                .filter(model -> displayClassName(model).equals(canonicalName))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return Set.of();
        }
        var result = new HashSet<BytecodeMethodKey>();
        for (var method : selected.methods()) {
            if (method.flags().has(AccessFlag.SYNTHETIC) || method.flags().has(AccessFlag.BRIDGE) || !access.visible(method.flags())) {
                continue;
            }
            var name = method.methodName().stringValue();
            if (ConstantDescs.INIT_NAME.equals(name) ? !member.equals("new") : !name.equals(member)) {
                continue;
            }
            result.add(
                    new BytecodeMethodKey(
                            selected.thisClass().asInternalName(),
                            name,
                            method.methodType().stringValue(),
                            !ConstantDescs.INIT_NAME.equals(name)
                                    && !method.flags().has(AccessFlag.PRIVATE)
                                    && !method.flags().has(AccessFlag.STATIC)
                                    && !method.flags().has(AccessFlag.FINAL)));
        }
        return Set.copyOf(result);
    }

    private static Set<BytecodeFieldKey> usageBytecodeFields(List<ClassModel> classes, String binaryName, String member,
            Access access) {
        if (member == null) {
            return Set.of();
        }
        var canonicalName = binaryName.replace('$', '.');
        var selected = classes.stream()
                .filter(model -> displayClassName(model).equals(canonicalName))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return Set.of();
        }
        return selected.fields().stream()
                .filter(field -> !field.flags().has(AccessFlag.SYNTHETIC))
                .filter(field -> access.visible(field.flags()))
                .filter(field -> field.fieldName().equalsString(member))
                .map(
                        field -> new BytecodeFieldKey(selected.thisClass().asInternalName(), member,
                                field.fieldType().stringValue()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<UsageKey> usageKeys(Path sourceFile, String binaryName, String member,
            Access access)
            throws IOException {
        var model = SourceModel.parse(Files.readAllBytes(sourceFile));
        var structure = model.structure(sourceFile.getFileName().toString(), access,
                false);
        var owner = binaryName.replace('$', '.');
        var keys = new HashSet<UsageKey>();
        for (var key : structure.attribution().keySet()) {
            int hash = key.indexOf('#');
            if (hash < 0) {
                if (member == null && key.equals(owner)) {
                    keys.add(new UsageKey(UsageKind.TYPE, owner, null));
                }
                continue;
            }
            if (member == null || !key.regionMatches(0, owner, 0, owner.length()) || hash != owner.length()) {
                continue;
            }
            var sourceMember = key.substring(hash + 1);
            int ordinal = sourceMember.lastIndexOf('@');
            if (ordinal >= 0) {
                sourceMember = sourceMember.substring(0, ordinal);
                var publicName = sourceMember.equals("<init>") ? "new" : sourceMember;
                if (publicName.equals(member)) {
                    keys.add(new UsageKey(sourceMember.equals("<init>") ? UsageKind.CONSTRUCTOR : UsageKind.METHOD, owner, publicName));
                }
            } else if (sourceMember.equals(member)) {
                keys.add(new UsageKey(UsageKind.FIELD, owner, member));
            }
        }
        return Set.copyOf(keys);
    }

    private void scanUsages(Path sourceFile, Options opts, UsageTarget target,
                            UsageOutput out)
            throws IOException, ToolException {
        if (!scanUsages(sourceFile, opts, target, out, false)) {
            scanUsages(sourceFile, opts, target, out, true);
        }
    }

    private void attributeAttachedSourceUnit(UsageLocation location, Options opts, UsageTarget target,
            UsageOutput out) {
        var source = location.source();
        if (source == null || source.path() == null || source.root() == null) {
            return;
        }
        var relative = source.root().relativize(source.path());
        if (relative.getNameCount() == 0) {
            return;
        }
        String moduleName = null;
        var possibleModule = relative.getName(0).toString();
        if (relative.getNameCount() > 1 && Files.isRegularFile(source.root()
                .resolve(possibleModule)
                .resolve("module-info.java"))) {
            moduleName = possibleModule;
        }
        var context = new AttachedSourceContext(source.root(), moduleName);
        try {
            if (!attributeAttachedSourceUnit(context, location, opts, target, out, false)) {
                attributeAttachedSourceUnit(context, location, opts, target, out, true);
            }
        } catch (IOException | ToolException | IllegalStateException _) {
            // The ClassFile-confirmed usage remains valid without optional source attribution.
        }
    }

    private boolean attributeAttachedSourceUnit(AttachedSourceContext context, UsageLocation location, Options opts,
            UsageTarget target, UsageOutput out, boolean enablePreview)
            throws IOException, ToolException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var source = location.source();
            var relative = context.root().relativize(source.path());
            if (context.moduleName() != null) {
                relative = relative.subpath(1, relative.getNameCount());
            }
            var binaryName = relative.toString()
                    .replace(File.separatorChar, '.')
                    .replace('/', '.');
            if (binaryName.endsWith(JAVA_EXT)) {
                binaryName = binaryName.substring(0, binaryName.length() - JAVA_EXT.length());
            }
            var sourceFile = new StringJavaFileObject(binaryName.replace('.', '/') + JAVA_EXT, new String(source.bytes(), StandardCharsets.UTF_8).toCharArray());
            var taskFileManager = context.moduleName() != null ? new PatchedSourceFileManager(fileManager, List.of(sourceFile), context.moduleName()) : fileManager;
            var task = (JavacTask) compiler.getTask(null, taskFileManager, diagnostics, usageCompilerOptions(opts, enablePreview, false), null,
                    List.of(sourceFile));
            var units = task.parse().iterator();
            if (!units.hasNext()) {
                return true;
            }
            var unit = units.next();
            task.analyze();
            if (!enablePreview && requiresPreview(diagnostics)) {
                return false;
            }
            failOnUsageDiagnostics(source.path(), diagnostics);
            var scanner = new UsageScanner(unit, Trees.instance(task), task.getElements(), target,
                    location, out);
            scanner.scan(unit, null);
            scanner.flush();
            return true;
        }
    }

    private record AttachedSourceContext(Path root, String moduleName) {}

    /**
     * Associates selected in-memory source units with an existing module
     * without exposing the rest of its source archive, so javac resolves
     * dependencies from the compiled module.
     */
    private static final class PatchedSourceFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Set<URI> sources;
        private final Location patchLocation;

        PatchedSourceFileManager(StandardJavaFileManager fileManager, Collection<JavaFileObject> sources, String moduleName) {
            super(fileManager);
            this.sources = sources.stream()
                    .map(FileObject::toUri)
                    .collect(Collectors.toSet());
            this.patchLocation = new PatchLocation(moduleName);
        }

        @Override
        public boolean hasLocation(Location location) {
            return location == StandardLocation.PATCH_MODULE_PATH || super.hasLocation(location);
        }

        @Override
        public Location getLocationForModule(Location location, JavaFileObject file) throws IOException {
            if (location == StandardLocation.PATCH_MODULE_PATH && sources.contains(file.toUri())) {
                return patchLocation;
            }
            return super.getLocationForModule(location, file);
        }

        @Override
        public Location getLocationForModule(Location location, String moduleName) throws IOException {
            if (location == StandardLocation.PATCH_MODULE_PATH && moduleName.equals(inferModuleName(patchLocation))) {
                return patchLocation;
            }
            return super.getLocationForModule(location, moduleName);
        }

        @Override
        public String inferModuleName(Location location) throws IOException {
            return location == patchLocation ? ((PatchLocation) patchLocation).moduleName() : super.inferModuleName(location);
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                boolean recurse)
                throws IOException {
            return location == patchLocation ? List.of() : super.list(location, packageName, kinds, recurse);
        }

        private record PatchLocation(String moduleName) implements Location {
            @Override
            public String getName() {
                return "PATCH_MODULE_PATH[" + moduleName + "]";
            }

            @Override
            public boolean isOutputLocation() {
                return false;
            }

            @Override
            public boolean isModuleOrientedLocation() {
                return false;
            }
        }
    }

    private boolean scanUsages(Path sourceFile, Options opts, UsageTarget target,
            UsageOutput out, boolean enablePreview)
            throws IOException, ToolException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, usageCompilerOptions(opts, enablePreview), null,
                    fileManager.getJavaFileObjects(sourceFile));
            var units = task.parse().iterator();
            if (!units.hasNext()) {
                return true;
            }
            var unit = units.next();
            task.analyze();
            if (!enablePreview && requiresPreview(diagnostics)) {
                return false;
            }
            failOnUsageDiagnostics(sourceFile, diagnostics);
            var scanner = new UsageScanner(unit, Trees.instance(task), task.getElements(), target,
                    sourceFile, out);
            scanner.scan(unit, null);
            scanner.flush();
            return true;
        }
    }

    private List<String> usageCompilerOptions(Options opts, boolean enablePreview) throws IOException, ToolException {
        return usageCompilerOptions(opts, enablePreview, true);
    }

    private List<String> usageCompilerOptions(Options opts, boolean enablePreview, boolean preserveNoSystem) throws IOException, ToolException {
        var result = new ArrayList<String>();
        result.add("-proc:none");
        result.add("-implicit:none");
        result.add("-XDshould-stop.at=ATTR");
        if (enablePreview) {
            result.addAll(List.of("--enable-preview", "--source",
                    Integer.toString(Runtime.version().feature())));
        }
        if (opts.modulePath() != null && !opts.modulePath().isBlank()) {
            result.addAll(List.of("--module-path", opts.modulePath()));
        }
        if (opts.classPath() != null && !opts.classPath().isBlank()) {
            result.addAll(List.of("--class-path", opts.classPath()));
        }
        var selectedModules = selectedModules(opts);
        if (!selectedModules.isEmpty()) {
            result.addAll(List.of("--add-modules", String.join(",", selectedModules)));
        }
        if (opts.addModules() != null) {
            result.addAll(List.of("--add-modules", opts.addModules()));
        }
        for (var value : opts.addExports()) {
            result.addAll(List.of("--add-exports", value));
        }
        for (var value : opts.addReads()) {
            result.addAll(List.of("--add-reads", value));
        }
        if (opts.limitModules() != null) {
            result.addAll(List.of("--limit-modules", opts.limitModules()));
        }
        var sourceDirectories = environment.sourceRoots().stream()
                .filter(Files::isDirectory)
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
        if (!sourceDirectories.isEmpty()) {
            if (selectedModules.size() == 1) {
                result.addAll(List.of("--patch-module", selectedModules.getFirst() + "=" + sourceDirectories));
            } else {
                result.addAll(List.of("--source-path", sourceDirectories));
            }
        }
        if (opts.system() != null && (preserveNoSystem || !opts.system().equals("none"))) {
            result.addAll(List.of("--system", opts.system()));
        }
        return List.copyOf(result);
    }

    private static boolean requiresPreview(DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .anyMatch(message -> message.contains("uses preview features"));
    }

    private static void failOnUsageDiagnostics(Path sourceFile, DiagnosticCollector<JavaFileObject> diagnostics) throws ToolException {
        var error = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                .findFirst()
                .orElse(null);
        if (error != null) {
            throw new ToolException(2, "Cannot attribute usages in " + sourceFile + ": " + error.getMessage(Locale.ROOT));
        }
    }

    private enum UsageKind {
        TYPE,
        METHOD,
        CONSTRUCTOR,
        FIELD
    }

    private record UsageKey(UsageKind kind, String owner, String member) {}

    private record BytecodeMethodKey(String owner, String name, String descriptor,
            boolean overridable) {}

    private record BytecodeFieldKey(String owner, String name, String descriptor) {}

    private record UsageTarget(
            Set<UsageKey> keys,
            Set<String> bytecodeTypeInternalNames,
            Set<BytecodeMethodKey> bytecodeMethods,
            Set<BytecodeFieldKey> bytecodeFields,
            List<byte[]> bytecodeTypeNeedles,
            List<byte[]> bytecodeOwnerNeedles,
            List<String> descriptorNeedles,
            List<String> genericSignatureNeedles,
            byte[] memberNeedle,
            boolean constructorTarget) {
        boolean matchesDirectly(Element element) {
            var key = usageKey(element);
            return key != null && keys.contains(key);
        }

        boolean overridesTarget(Element element, Elements elements) {
            if (!(element instanceof ExecutableElement method) || method.getKind() != ElementKind.METHOD || !(method.getEnclosingElement() instanceof TypeElement owner)) {
                return false;
            }
            for (var key : keys) {
                if (key.kind() != UsageKind.METHOD || !key.member().contentEquals(method.getSimpleName())) {
                    continue;
                }
                var targetOwner = elements.getTypeElement(key.owner());
                if (targetOwner == null) {
                    continue;
                }
                for (var candidate : targetOwner.getEnclosedElements()) {
                    if (candidate instanceof ExecutableElement targetMethod
                            && targetMethod.getKind() == ElementKind.METHOD
                            && targetMethod.getSimpleName().contentEquals(key.member())
                            && elements.overrides(method, targetMethod, owner)) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean mayOccurIn(String source) {
            for (var key : keys) {
                var text = key.kind() == UsageKind.CONSTRUCTOR
                        ? terminalName(key.owner())
                        : key.member() != null ? key.member() : terminalName(key.owner());
                if (source.contains(text)) {
                    return true;
                }
            }
            return false;
        }

        boolean mayOccurIn(byte[] classFile, int length) {
            for (var needle : bytecodeTypeNeedles) {
                if (contains(classFile, length, needle)) {
                    return true;
                }
            }
            if (!constructorTarget && (memberNeedle == null || !contains(classFile, length, memberNeedle))) {
                return false;
            }
            for (var needle : bytecodeOwnerNeedles) {
                if (contains(classFile, length, needle)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean contains(byte[] bytes, int length, byte[] expected) {
            outer:
            for (int i = 0; i <= length - expected.length; i++) {
                for (int j = 0; j < expected.length; j++) {
                    if (bytes[i + j] != expected[j]) {
                        continue outer;
                    }
                }
                return true;
            }
            return false;
        }

        boolean matchesBytecodeMethod(String owner, String name, String descriptor) {
            for (var method : bytecodeMethods) {
                if (method.owner().equals(owner) && method.name().equals(name) && method.descriptor().equals(descriptor)) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesBytecodeField(String owner, String name, String descriptor) {
            for (var field : bytecodeFields) {
                if (field.owner().equals(owner) && field.name().equals(name) && field.descriptor().equals(descriptor)) {
                    return true;
                }
            }
            return false;
        }

        boolean isDirectSubtype(ClassModel model, String targetOwner) {
            if (model.superclass().isPresent() && model.superclass()
                    .get()
                    .asInternalName()
                    .equals(targetOwner)) {
                return true;
            }
            for (var parent : model.interfaces()) {
                if (parent.asInternalName().equals(targetOwner)) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesBytecodeOverride(ClassModel model, MethodModel method) {
            var owner = model.thisClass().asInternalName();
            for (var target : bytecodeMethods) {
                if (target.overridable()
                        && !owner.equals(target.owner())
                        && method.methodName().equalsString(target.name())
                        && method.methodType().equalsString(target.descriptor())
                        && isDirectSubtype(model, target.owner())) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesDirectSubtypeMethod(ClassModel model, String owner, String name,
                String descriptor) {
            if (!owner.equals(model.thisClass()
                                   .asInternalName())) {
                return false;
            }
            for (var target : bytecodeMethods) {
                if (target.overridable()
                        && target.name().equals(name)
                        && target.descriptor().equals(descriptor)
                        && isDirectSubtype(model, target.owner())) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesBytecodeType(String internalName) {
            if (internalName.startsWith("[")) {
                return matchesBytecodeDescriptor(internalName);
            }
            return bytecodeTypeInternalNames.contains(internalName);
        }

        boolean matchesBytecodeDescriptor(String descriptor) {
            for (var needle : descriptorNeedles) {
                if (descriptor.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesBytecodeSignature(String signature) {
            for (int i = 0; i < descriptorNeedles.size(); i++) {
                if (signature.contains(descriptorNeedles.get(i)) || signature.contains(genericSignatureNeedles.get(i))) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesMethodHandle(DirectMethodHandleDesc handle) {
            var ownerDescriptor = handle.owner().descriptorString();
            if (matchesBytecodeDescriptor(ownerDescriptor)) {
                return true;
            }
            var owner = ownerDescriptor.substring(1, ownerDescriptor.length() - 1);
            return switch (handle.kind()) {
                case CONSTRUCTOR -> matchesBytecodeMethod(owner, ConstantDescs.INIT_NAME, handle.lookupDescriptor());
                case GETTER, SETTER, STATIC_GETTER, STATIC_SETTER ->
                        matchesBytecodeField(owner, handle.methodName(), handle.lookupDescriptor());
                default -> matchesBytecodeMethod(owner, handle.methodName(), handle.lookupDescriptor());
            };
        }
    }

    private static UsageKey usageKey(Element element) {
        if (element instanceof TypeElement type) {
            return new UsageKey(UsageKind.TYPE, type.getQualifiedName().toString(),
                    null);
        }
        var enclosing = element.getEnclosingElement();
        if (!(enclosing instanceof TypeElement owner)) {
            return null;
        }
        var ownerName = owner.getQualifiedName().toString();
        return switch (element.getKind()) {
            case METHOD -> new UsageKey(UsageKind.METHOD, ownerName,
                    element.getSimpleName().toString());
            case CONSTRUCTOR -> new UsageKey(UsageKind.CONSTRUCTOR, ownerName, "new");
            case FIELD, ENUM_CONSTANT -> new UsageKey(UsageKind.FIELD, ownerName,
                    element.getSimpleName().toString());
            default -> null;
        };
    }

    static String terminalName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    private record UsageLocation(String unitName, String sourceFileName, String classLocation,
            SourceUnit source) {}

    private enum DeclarationHint {
        UNKNOWN,
        CLASS,
        FIELD,
        METHOD
    }

    @FunctionalInterface
    private interface SourceUnitAttributor {
        void attribute(UsageLocation location);
    }

    private static final class UsageOutput {
        private final PrintWriter out;
        private final PrintWriter err;
        private final SourceScope sourceScope;
        private final Map<String, SourceUnitView> sourceViews = new HashMap<>();
        private final Set<UsageOutputKey> emitted = new HashSet<>();
        private final Map<SourceUnitLine, List<SourceHighlight>> highlights = new HashMap<>();
        private final Set<String> attributedSources = new HashSet<>();
        private SourceUnitAttributor sourceAttributor;

        UsageOutput(PrintWriter out, PrintWriter err, SourceScope sourceScope) {
            this.out = out;
            this.err = err;
            this.sourceScope = sourceScope;
        }

        void register(UsageLocation location, Collection<SourceHighlight> matches) {
            if (!(out instanceof InteractivePrintWriter interactive) || !interactive.highlightsEnabled()) {
                return;
            }
            for (var match : matches) {
                highlights.computeIfAbsent(new SourceUnitLine(usageIdentity(location), match.line()),
                        _ -> new ArrayList<>())
                          .add(match);
            }
        }

        void sourceAttributor(SourceUnitAttributor sourceAttributor) {
            this.sourceAttributor = out instanceof InteractivePrintWriter interactive && interactive.highlightsEnabled()
                    ? sourceAttributor
                    : null;
        }

        void print(String enclosingTarget, UsageLocation location, int line) {
            print(enclosingTarget, location, line, DeclarationHint.UNKNOWN);
        }

        void print(String enclosingTarget, UsageLocation location, int line,
                   DeclarationHint declarationHint) {
            if (out instanceof OriginOutput output) {
                output.classOrigin(location.unitName(), location.classLocation());
            }
            if (sourceAttributor != null
                    && location.source() != null
                    && location.source().javaSource()
                    && location.source().path() != null
                    && attributedSources.add(location.source()
                            .origin())) {
                sourceAttributor.attribute(location);
            }
            var view = sourceView(location);
            var effectiveLine = line > 0 ? line : declarationLine(view, enclosingTarget, declarationHint);
            if (sourceScope == null || view == null) {
                if (view == null) {
                    warnSourceUnavailable(out, location.unitName());
                }
                printUsageLine(enclosingTarget, location, view, effectiveLine);
                return;
            }
            switch (sourceScope) {
                case NONE, SIGNATURE, DOC, SYMBOL ->
                        throw new AssertionError("invalid usage source scope was not rejected");
                case BODY -> printUsageLine(enclosingTarget, location, view, effectiveLine);
                case DEFINITION -> {
                    var attribution = definitionAttribution(view.attribution(), enclosingTarget, effectiveLine);
                    printSpan(location, view, attribution.declaration());
                    if (!attribution.declaration().present()) {
                        warnSourceUnavailable(out, location.unitName());
                        printUsageLine(enclosingTarget, location, view, effectiveLine);
                    }
                }
                case TYPE -> {
                    var attribution = enclosingTopLevelTypeAttribution(view.attribution(), enclosingTarget);
                    printSpan(location, view, attribution.documentation());
                    printSpan(location, view, attribution.declaration());
                    if (!attribution.declaration().present()) {
                        warnSourceUnavailable(out, location.unitName());
                        printUsageLine(enclosingTarget, location, view, effectiveLine);
                    }
                }
                case UNIT -> printSpan(location, view,
                        SourceSpan.lines(1, view.model().contentLineCount()));
            }
        }

        private void printUsageLine(String enclosingTarget, UsageLocation location, SourceUnitView view,
                int line) {
            var key = new UsageOutputKey(usageIdentity(location), line,
                    line > 0 ? null : enclosingTarget);
            if (!emitted.add(key)) {
                return;
            }
            if (line > 0) {
                var sourceLine = view != null && line <= view.model().contentLineCount();
                if (!sourceLine) {
                    warnSourceUnavailable(out, location.unitName());
                }
                var text = sourceLine ? view.model().line(line) : enclosingTarget;
                printSourceLine(location, line, text);
            } else {
                warnSourceUnavailable(out, location.unitName());
                out.println(location.unitName() + ": " + enclosingTarget);
            }
        }

        private void printSpan(UsageLocation location, SourceUnitView view, SourceSpan span) {
            if (!span.present()) {
                return;
            }
            for (int line = span.startLine();
                 line <= span.endLine() && line <= view.model().contentLineCount();
                 line++) {
                var key = new UsageOutputKey(usageIdentity(location), line, null);
                if (emitted.add(key)) {
                    printSourceLine(location, line,
                            view.model().line(line));
                }
            }
        }

        private void printSourceLine(UsageLocation location, int line, String text) {
            if (out instanceof InteractivePrintWriter interactive) {
                var sourceLine = new SourceUnitLine(usageIdentity(location), line);
                interactive.sourceHighlights(location.unitName(), location.sourceFileName(), line,
                        highlights.getOrDefault(sourceLine, List.of()));
                highlights.remove(sourceLine);
            }
            DeclarationSearch.printSourceLine(
                    out,
                    location.unitName(),
                    location.sourceFileName(),
                    location.source() != null ? location.source().origin() : null,
                    line,
                    text);
        }

        private static String usageIdentity(UsageLocation location) {
            return location.source() != null
                    ? location.source().origin()
                    : location.classLocation() != null ? location.classLocation() : location.unitName();
        }

        private SourceUnitView sourceView(UsageLocation location) {
            if (location.source() == null) {
                return null;
            }
            return sourceViews.computeIfAbsent(
                    location.source().origin(),
                    ignored -> {
                        var model = SourceModel.parse(location.source().bytes());
                        if (!location.source().javaSource()) {
                            return new SourceUnitView(model, Map.of());
                        }
                        if (sourceScope == null || sourceScope == SourceScope.UNIT) {
                            return new SourceUnitView(model, Map.of());
                        }
                        try {
                            var structure = model.structure(location.sourceFileName(), Access.PRIVATE, sourceScope == SourceScope.TYPE);
                            return new SourceUnitView(model, structure.attribution());
                        } catch (UncheckedIOException e) {
                            err.println("Error: Cannot parse usage source " + location.source().origin() + ": " + e.getMessage());
                            return new SourceUnitView(model, Map.of());
                        }
                    });
        }

        private static Attribution definitionAttribution(Map<String, Attribution> attribution, String enclosingTarget, int line) {
            if (line > 0) {
                var containing = attribution.values().stream()
                        .filter(candidate -> candidate.declaration().startLine() <= line && line <= candidate.declaration().endLine())
                        .min(Comparator.comparingInt(candidate -> candidate.declaration().endLine() - candidate.declaration().startLine()));
                if (containing.isPresent()) {
                    return containing.get();
                }
            }
            Attribution first = null;
            for (var entry : attribution.entrySet()) {
                if (!attributionSymbol(entry.getKey()).equals(enclosingTarget)) {
                    continue;
                }
                if (first == null) {
                    first = entry.getValue();
                }
            }
            return first != null ? first : Attribution.EMPTY;
        }

        private static String attributionSymbol(String key) {
            int hash = key.indexOf('#');
            if (hash < 0) {
                return key;
            }
            var member = key.substring(hash + 1);
            int ordinal = member.lastIndexOf('@');
            if (ordinal >= 0) {
                member = member.substring(0, ordinal);
            }
            return key.substring(0, hash)
                    + "."
                    + (member.equals("<init>") ? "new" : member);
        }

        private int declarationLine(SourceUnitView view, String enclosingTarget, DeclarationHint declarationHint) {
            if (view == null) {
                return -1;
            }
            var model = view.model();
            int separator = declarationHint == DeclarationHint.CLASS ? -1 : enclosingTarget.lastIndexOf('.');
            var owner = separator >= 0 ? enclosingTarget.substring(0, separator) : enclosingTarget;
            var member = separator >= 0 ? enclosingTarget.substring(separator + 1) : null;
            var ownerDeclaration = ownerDeclaration(model, owner.substring(owner.lastIndexOf('.') + 1));
            if (member == null || member.equals("new") || declarationHint == DeclarationHint.CLASS) {
                return ownerDeclaration.line();
            }

            var field = Pattern.compile("(?:\\b(?:val|var)\\s+"
                    + Pattern.quote(member)
                    + "\\b|\\b"
                    + Pattern.quote(member)
                    + "\\b\\s*(?:[:=;]))");
            var method = Pattern.compile("(?:\\bfun\\s+"
                    + Pattern.quote(member)
                    + "\\b|\\b"
                    + Pattern.quote(member)
                    + "\\b\\s*\\()");
            int depth = 0;
            for (int i = 0; i < model.contentLineCount(); i++) {
                var line = model.line(i + 1);
                if (i > ownerDeclaration.line() - 1 && depth == ownerDeclaration.bodyDepth() && !nonCodeLine(line)) {
                    if ((declarationHint == DeclarationHint.FIELD || declarationHint == DeclarationHint.UNKNOWN) && field.matcher(line).find()) {
                        return i + 1;
                    }
                    if ((declarationHint == DeclarationHint.METHOD || declarationHint == DeclarationHint.UNKNOWN) && method.matcher(line).find()) {
                        return i + 1;
                    }
                }
                if (!nonCodeLine(line)) {
                    depth += braceDelta(line);
                }
            }
            return -1;
        }

        private static OwnerDeclaration ownerDeclaration(SourceModel model, String simpleName) {
            var declaration = Pattern.compile("\\b(?:class|interface|enum|record|object)\\s+" + Pattern.quote(simpleName) + "\\b");
            int depth = 0;
            int declarationLine = -1;
            int bodyDepth = -1;
            for (int i = 0; i < model.contentLineCount(); i++) {
                var line = model.line(i + 1);
                if (!nonCodeLine(line)) {
                    if (declarationLine < 0 && declaration.matcher(line).find()) {
                        declarationLine = i + 1;
                    }
                    int delta = braceDelta(line);
                    if (declarationLine > 0 && bodyDepth < 0 && delta > 0) {
                        bodyDepth = depth + 1;
                    }
                    depth += delta;
                }
            }
            return new OwnerDeclaration(declarationLine, bodyDepth);
        }

        private static int braceDelta(String line) {
            int result = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '{') {
                    result++;
                } else if (line.charAt(i) == '}') {
                    result--;
                }
            }
            return result;
        }

        private record OwnerDeclaration(int line, int bodyDepth) {}

        private static boolean nonCodeLine(String line) {
            var stripped = line.stripLeading();
            return stripped.startsWith("*")
                    || stripped.startsWith("/*")
                    || stripped.startsWith("//")
                    || stripped.startsWith("import ")
                    || stripped.startsWith("package ");
        }

        private record SourceUnitView(SourceModel model, Map<String, Attribution> attribution) {}

        private record UsageOutputKey(String origin, int line, String enclosingTarget) {}

        private record SourceUnitLine(String origin, int line) {}
    }

    private static final class UsageScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final Elements elements;
        private final UsageTarget target;
        private final UsageLocation location;
        private final UsageOutput out;
        private final char[] source;
        private final List<PendingUsage> pending = new ArrayList<>();

        UsageScanner(CompilationUnitTree unit, Trees trees, Elements elements,
                     UsageTarget target, Path sourceFile, UsageOutput out)
                throws IOException {
            this(unit, trees, elements, target, sourceUsageLocation(unit, sourceFile),
                    out);
        }

        UsageScanner(CompilationUnitTree unit, Trees trees, Elements elements,
                     UsageTarget target, UsageLocation location, UsageOutput out) {
            this.unit = unit;
            this.trees = trees;
            this.elements = elements;
            this.target = target;
            this.location = location;
            this.source = new String(location.source()
                    .bytes(),
                    StandardCharsets.UTF_8)
                    .toCharArray();
            this.out = out;
        }

        private static UsageLocation sourceUsageLocation(CompilationUnitTree unit, Path sourceFile) throws IOException {
            var fileName = sourceFile.getFileName().toString();
            var simpleName = fileName.endsWith(JAVA_EXT) ? fileName.substring(0, fileName.length() - JAVA_EXT.length()) : fileName;
            var packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            var unitName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            var bytes = Files.readAllBytes(sourceFile);
            return new UsageLocation(
                    unitName,
                    fileName,
                    null,
                    new SourceUnit(unitLocation(sourceFile), bytes, true, sourceFile,
                            sourceFile.getParent()));
        }

        @Override
        public Void visitImport(ImportTree tree, Void unused) {
            return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            report(tree);
            return super.visitIdentifier(tree, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            report(tree);
            return super.visitMemberSelect(tree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            report(tree);
            return super.visitNewClass(tree, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
            report(tree);
            return super.visitMemberReference(tree, unused);
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            var element = trees.getElement(getCurrentPath());
            if (target.overridesTarget(element, elements)
                    && element instanceof ExecutableElement method
                    && method.getEnclosingElement() instanceof TypeElement owner
                    && isReusable(owner)) {
                report(tree, owner.getQualifiedName() + "." + method.getSimpleName());
            }
            return super.visitMethod(tree, unused);
        }

        private void report(Tree tree) {
            var path = getCurrentPath();
            var element = trees.getElement(path);
            if (!target.matchesDirectly(element) && !target.overridesTarget(element, elements)) {
                return;
            }
            var enclosing = enclosingTarget(path);
            if (enclosing == null) {
                return;
            }
            report(tree, enclosing);
        }

        private void report(Tree tree, String enclosing) {
            var position = trees.getSourcePositions().getStartPosition(unit, tree);
            if (position < 0) {
                return;
            }
            var highlight = sourceHighlight(tree);
            var line = highlight != null ? highlight.line() : Math.toIntExact(unit.getLineMap()
                    .getLineNumber(position));
            pending.add(new PendingUsage(enclosing, line, highlight));
        }

        void flush() {
            out.register(location,
                    pending.stream()
                            .map(PendingUsage::highlight)
                            .filter(Objects::nonNull)
                            .toList());
            pending.forEach(usage -> out.print(usage.enclosing(), location, usage.line()));
        }

        private SourceHighlight sourceHighlight(Tree tree) {
            var positions = trees.getSourcePositions();
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            if (start < 0 || end <= start) {
                return null;
            }
            final long tokenStart;
            final long tokenEnd;
            switch (tree) {
                case MemberSelectTree select -> {
                    tokenEnd = end;
                    tokenStart = tokenEnd - select.getIdentifier().length();
                }
                case MemberReferenceTree reference -> {
                    var name = reference.getMode() == ReferenceMode.NEW ? "new" : reference.getName().toString();
                    tokenEnd = end;
                    tokenStart = tokenEnd - name.length();
                }
                case NewClassTree newClass -> {
                    long identifierStart = positions.getStartPosition(unit, newClass.getIdentifier());
                    tokenStart = findLastKeyword("new", start, identifierStart >= start ? identifierStart : end);
                    tokenEnd = tokenStart >= 0 ? tokenStart + "new".length() : -1;
                }
                case MethodTree method -> {
                    var name = method.getName().toString();
                    long returnEnd = method.getReturnType() != null ? positions.getEndPosition(unit, method.getReturnType()) : start;
                    tokenStart = findMethodName(name, returnEnd >= start ? returnEnd : start, end);
                    tokenEnd = tokenStart >= 0 ? tokenStart + name.length() : -1;
                }
                case IdentifierTree _ -> {
                    tokenStart = start;
                    tokenEnd = end;
                }
                default -> {
                    return null;
                }
            }
            if (tokenStart < 0 || tokenEnd <= tokenStart) {
                return null;
            }
            long line = unit.getLineMap().getLineNumber(tokenStart);
            if (unit.getLineMap().getLineNumber(tokenEnd - 1) != line) {
                return null;
            }
            long lineStart = unit.getLineMap().getStartPosition(line);
            return new SourceHighlight(Math.toIntExact(line), Math.toIntExact(tokenStart - lineStart), Math.toIntExact(tokenEnd - lineStart));
        }

        private long findLastKeyword(String keyword, long start, long end) {
            int found = -1;
            int limit = Math.min(source.length, Math.toIntExact(end));
            for (int i = Math.toIntExact(start);
                 i + keyword.length() <= limit;
                 i++) {
                if (matchesIdentifier(keyword, i)) {
                    found = i;
                }
            }
            return found;
        }

        private long findMethodName(String name, long start, long end) {
            int limit = Math.min(source.length, Math.toIntExact(end));
            for (int i = Math.toIntExact(start);
                 i + name.length() <= limit;
                 i++) {
                if (!matchesIdentifier(name, i)) {
                    continue;
                }
                int next = i + name.length();
                while (next < limit && Character.isWhitespace(source[next])) {
                    next++;
                }
                if (next < limit && source[next] == '(') {
                    return i;
                }
            }
            return -1;
        }

        private boolean matchesIdentifier(String value, int offset) {
            if (offset < 0 || offset + value.length() > source.length) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                if (source[offset + i] != value.charAt(i)) {
                    return false;
                }
            }
            return (offset == 0 || !Character.isJavaIdentifierPart(source[offset - 1]))
                    && (offset + value.length() == source.length || !Character.isJavaIdentifierPart(source[offset + value.length()]));
        }

        private String enclosingTarget(TreePath occurrence) {
            for (var path = occurrence.getParentPath();
                 path != null;
                 path = path.getParentPath()) {
                if (path.getLeaf() instanceof MethodTree) {
                    var element = trees.getElement(path);
                    if (element instanceof ExecutableElement executable && reusableOwner(executable.getEnclosingElement()) instanceof TypeElement owner) {
                        var name = executable.getKind() == ElementKind.CONSTRUCTOR ? "new" : executable.getSimpleName().toString();
                        return owner.getQualifiedName() + "." + name;
                    }
                }
                if (path.getLeaf() instanceof VariableTree variable && path.getParentPath() != null && path.getParentPath().getLeaf() instanceof ClassTree) {
                    var element = trees.getElement(path);
                    if (element instanceof VariableElement field && reusableOwner(field.getEnclosingElement()) instanceof TypeElement owner) {
                        return owner.getQualifiedName() + "." + variable.getName();
                    }
                }
                if (path.getLeaf() instanceof ClassTree) {
                    var element = trees.getElement(path);
                    if (element instanceof TypeElement type && isReusable(type)) {
                        return type.getQualifiedName().toString();
                    }
                }
            }
            return null;
        }

        private static Element reusableOwner(Element element) {
            return element instanceof TypeElement type && isReusable(type)
                    ? type
                    : null;
        }

        private static boolean isReusable(TypeElement type) {
            return type.getNestingKind() == NestingKind.TOP_LEVEL || type.getNestingKind() == NestingKind.MEMBER;
        }

        private record PendingUsage(String enclosing, int line, SourceHighlight highlight) {}
    }
}
