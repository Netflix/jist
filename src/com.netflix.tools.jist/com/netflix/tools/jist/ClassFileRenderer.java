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

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AnnotationValue.OfAnnotation;
import java.lang.classfile.AnnotationValue.OfArray;
import java.lang.classfile.AnnotationValue.OfBoolean;
import java.lang.classfile.AnnotationValue.OfChar;
import java.lang.classfile.AnnotationValue.OfClass;
import java.lang.classfile.AnnotationValue.OfConstant;
import java.lang.classfile.AnnotationValue.OfEnum;
import java.lang.classfile.AnnotationValue.OfString;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Signature;
import java.lang.classfile.Signature.ArrayTypeSig;
import java.lang.classfile.Signature.BaseTypeSig;
import java.lang.classfile.Signature.ClassTypeSig;
import java.lang.classfile.Signature.ThrowableSig;
import java.lang.classfile.Signature.TypeArg;
import java.lang.classfile.Signature.TypeArg.Bounded;
import java.lang.classfile.Signature.TypeArg.Unbounded;
import java.lang.classfile.Signature.TypeParam;
import java.lang.classfile.Signature.TypeVarSig;
import java.lang.module.ModuleDescriptor;

/** Renders ClassFile metadata as concise Java declarations. */
final class ClassFileRenderer {
    static String compilationUnitName(ClassModel model, String className) {
        var sourceFile = model.findAttribute(Attributes.sourceFile()).orElse(null);
        if (sourceFile == null) {
            int nested = className.indexOf('$');
            return nested >= 0 ? className.substring(0, nested) : className;
        }
        var fileName = sourceFile.sourceFile().stringValue();
        int extension = fileName.lastIndexOf('.');
        var simpleName = extension > 0 ? fileName.substring(0, extension) : fileName;
        var internalName = model.thisClass().asInternalName();
        int packageEnd = internalName.lastIndexOf('/');
        return packageEnd < 0 ? simpleName : internalName.substring(0, packageEnd).replace('/', '.') + "." + simpleName;
    }

    static String renderTypeDeclaration(ClassModel model) {
        var out = new StringBuilder();
        appendDeclarationAnnotations(out, model);
        appendDeclarationModifiers(out, model.flags().flags(),
                true);
        var kind = SymbolKind.classKind(model);
        out.append(switch (kind) {
            case INTERFACE -> "interface ";
            case ENUM -> "enum ";
            case RECORD -> "record ";
            case ANNOTATION -> "@interface ";
            default -> "class ";
        });
        var name = model.thisClass().asInternalName();
        out.append(name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('$')) + 1));
        var signatureAttribute = model.findAttribute(Attributes.signature()).orElse(null);
        var signature = signatureAttribute != null ? signatureAttribute.asClassSignature() : null;
        if (signature != null && !signature.typeParameters().isEmpty()) {
            appendTypeParameters(out, signature.typeParameters());
        }
        if (kind == SymbolKind.RECORD) {
            var components = model.findAttribute(Attributes.record()).orElse(null);
            out.append('(');
            if (components != null) {
                for (int i = 0; i < components.components().size(); i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    var component = components.components().get(i);
                    var componentSignature = component.findAttribute(Attributes.signature()).orElse(null);
                    out.append(componentSignature != null ? renderSignature(componentSignature.asTypeSignature()) : javaType(component.descriptor()
                               .stringValue()))
                       .append(' ')
                       .append(component.name()
                                        .stringValue());
                }
            }
            out.append(')');
        }
        if (kind == SymbolKind.CLASS) {
            if (signature != null) {
                if (!signature.superclassSignature()
                              .classDesc()
                              .equals(ConstantDescs.CD_Object)) {
                    out.append(" extends ").append(renderClassTypeSignature(signature.superclassSignature()));
                }
            } else {
                model.superclass().ifPresent(superclass -> {
                    if (!superclass.asInternalName().equals("java/lang/Object")) {
                        out.append(" extends ").append(javaType(superclass.asSymbol()));
                    }
                });
            }
        }
        var genericInterfaces = signature != null ? signature.superinterfaceSignatures() : List.<ClassTypeSig>of();
        if (!genericInterfaces.isEmpty() || !model.interfaces().isEmpty()) {
            out.append(kind == SymbolKind.INTERFACE ? " extends " : " implements ");
            int interfaceCount = !genericInterfaces.isEmpty() ? genericInterfaces.size() : model.interfaces().size();
            for (int i = 0; i < interfaceCount; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(!genericInterfaces.isEmpty() ? renderClassTypeSignature(genericInterfaces.get(i)) : javaType(model.interfaces()
                        .get(i)
                        .asSymbol()));
            }
        }
        out.append(" {");
        return out.toString();
    }

    static SymbolKind metadataKind(ClassModel model, String className) {
        if (model != null) {
            if (model.isModuleInfo()) {
                return SymbolKind.MODULE;
            }
            var internalName = model.thisClass().asInternalName();
            if (internalName.equals("package-info") || internalName.endsWith("/package-info")) {
                return SymbolKind.PACKAGE;
            }
        }
        if (className.equals("module-info")) {
            return SymbolKind.MODULE;
        }
        if (className.equals("package-info") || className.endsWith(".package-info")) {
            return SymbolKind.PACKAGE;
        }
        return null;
    }

    static String compiledModuleName(ClassModel model) {
        return model.findAttribute(Attributes.module())
                    .map(attribute -> attribute.moduleName()
                            .name()
                            .stringValue())
                    .orElse(null);
    }

    static String renderModuleDeclaration(ClassModel model) {
        var attribute = model.findAttribute(Attributes.module()).orElseThrow();
        var out = new StringBuilder();
        appendDeclarationAnnotations(out, model);
        if (attribute.has(AccessFlag.OPEN)) {
            out.append("open ");
        }
        out.append("module ")
           .append(attribute.moduleName()
                            .name()
                            .stringValue())
           .append(" {");
        for (var requires : attribute.requires()) {
            if (requires.has(AccessFlag.MANDATED) || requires.has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            out.append(" requires ");
            if (requires.has(AccessFlag.STATIC_PHASE)) {
                out.append("static ");
            }
            if (requires.has(AccessFlag.TRANSITIVE)) {
                out.append("transitive ");
            }
            out.append(requires.requires()
                               .name()
                               .stringValue())
               .append(';');
        }
        for (var exports : attribute.exports()) {
            if (exports.has(AccessFlag.MANDATED) || exports.has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            out.append(" exports ").append(exports.exportedPackage()
                    .name()
                    .stringValue()
                    .replace('/', '.'));
            if (!exports.exportsTo().isEmpty()) {
                out.append(" to ").append(exports.exportsTo().stream()
                        .map(module -> module.name().stringValue())
                        .collect(Collectors.joining(", ")));
            }
            out.append(';');
        }
        for (var opens : attribute.opens()) {
            if (opens.has(AccessFlag.MANDATED) || opens.has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            out.append(" opens ").append(opens.openedPackage()
                    .name()
                    .stringValue()
                    .replace('/', '.'));
            if (!opens.opensTo().isEmpty()) {
                out.append(" to ").append(opens.opensTo().stream()
                        .map(module -> module.name().stringValue())
                        .collect(Collectors.joining(", ")));
            }
            out.append(';');
        }
        for (var service : attribute.uses()) {
            out.append(" uses ")
               .append(internalToModuleInfoName(service.asInternalName()))
               .append(';');
        }
        for (var provides : attribute.provides()) {
            out.append(" provides ")
               .append(internalToModuleInfoName(provides.provides()
                       .asInternalName()))
               .append(" with ")
               .append(provides.providesWith().stream()
                       .map(implementation -> internalToModuleInfoName(implementation.asInternalName()))
                       .collect(Collectors.joining(", ")))
               .append(';');
        }
        return out.append(" }").toString();
    }

    static String renderAutomaticModuleDeclaration(ModuleDescriptor descriptor) {
        var out = new StringBuilder("module ").append(descriptor.name()).append(" {");
        descriptor.packages().stream()
                .sorted()
                .forEach(packageName -> out.append(" exports ")
                        .append(packageName)
                        .append(';'));
        return out.append(" }").toString();
    }

    static String renderFieldDeclaration(FieldModel field) {
        var out = new StringBuilder();
        appendDeclarationAnnotations(out, field);
        appendDeclarationModifiers(out, field.flags().flags(),
                false);
        var signature = field.findAttribute(Attributes.signature()).orElse(null);
        out.append(signature != null ? renderSignature(signature.asTypeSignature()) : javaType(field.fieldType()
                   .stringValue()))
           .append(' ')
           .append(field.fieldName()
                        .stringValue());
        field.findAttribute(Attributes.constantValue()).ifPresent(
                constant -> out.append(" = ").append(
                        renderFieldConstant(field.fieldType().stringValue(),
                                constant.constant().constantValue())));
        return out.append(';').toString();
    }

    static String renderMethodDeclaration(ClassModel owner, MethodModel method) {
        var out = new StringBuilder();
        appendDeclarationAnnotations(out, method);
        appendDeclarationModifiers(out, method.flags().flags(),
                false);
        if (owner.flags().has(AccessFlag.INTERFACE)
                && !method.flags().has(AccessFlag.ABSTRACT)
                && !method.flags().has(AccessFlag.STATIC)
                && !method.flags().has(AccessFlag.PRIVATE)) {
            out.append("default ");
        }
        var descriptor = MethodTypeDesc.ofDescriptor(method.methodType()
                .stringValue());
        var signatureAttribute = method.findAttribute(Attributes.signature()).orElse(null);
        var signature = signatureAttribute != null ? signatureAttribute.asMethodSignature() : null;
        var name = method.methodName().stringValue();
        if (signature != null && !signature.typeParameters().isEmpty()) {
            appendTypeParameters(out, signature.typeParameters());
            out.append(' ');
        }
        if (ConstantDescs.INIT_NAME.equals(name)) {
            var internalName = owner.thisClass().asInternalName();
            out.append(internalName.substring(Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$')) + 1));
        } else {
            out.append(signature != null ? renderSignature(signature.result()) : javaType(descriptor.returnType()))
               .append(' ')
               .append(name);
        }
        out.append('(');
        var parameters = method.findAttribute(Attributes.methodParameters()).orElse(null);
        var debugParameterNames = parameterNames(method, descriptor);
        for (int i = 0; i < descriptor.parameterCount(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            var parameterType = signature != null && signature.arguments().size() == descriptor.parameterCount()
                    ? renderSignature(signature.arguments()
                            .get(i))
                    : javaType(descriptor.parameterType(i));
            if (method.flags().has(AccessFlag.VARARGS) && i == descriptor.parameterCount() - 1 && parameterType.endsWith("[]")) {
                parameterType = parameterType.substring(0, parameterType.length() - 2) + "...";
            }
            out.append(parameterType).append(' ');
            String parameterName = null;
            if (parameters != null && i < parameters.parameters().size()) {
                parameterName = parameters.parameters()
                        .get(i)
                        .name()
                        .map(Utf8Entry::stringValue)
                        .orElse(null);
            }
            if (parameterName == null && i < debugParameterNames.size()) {
                parameterName = debugParameterNames.get(i);
            }
            out.append(parameterName != null ? parameterName : "arg" + i);
        }
        out.append(')');
        if (signature != null && !signature.throwableSignatures().isEmpty()) {
            out.append(" throws ");
            for (int i = 0; i < signature.throwableSignatures().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(renderThrowableSignature(signature.throwableSignatures()
                        .get(i)));
            }
        } else {
            method.findAttribute(Attributes.exceptions()).ifPresent(exceptions -> {
                if (!exceptions.exceptions().isEmpty()) {
                    out.append(" throws ");
                }
                for (int i = 0; i < exceptions.exceptions().size(); i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(javaType(exceptions.exceptions()
                            .get(i)
                            .asSymbol()));
                }
            });
        }
        return out.append(method.flags().has(AccessFlag.ABSTRACT) || method.flags().has(AccessFlag.NATIVE)
                ? ';'
                : " { /* body omitted */ }")
                  .toString();
    }

    private static List<String> parameterNames(MethodModel method, MethodTypeDesc descriptor) {
        var code = method.findAttribute(Attributes.code()).orElse(null);
        if (code == null) {
            return List.of();
        }
        var table = code.findAttribute(Attributes.localVariableTable()).orElse(null);
        if (table == null) {
            return List.of();
        }
        var bySlot = new HashMap<Integer, String>();
        for (var variable : table.localVariables()) {
            bySlot.putIfAbsent(variable.slot(),
                    variable.name().stringValue());
        }
        var names = new ArrayList<String>(descriptor.parameterCount());
        int slot = method.flags().has(AccessFlag.STATIC) ? 0 : 1;
        for (int i = 0; i < descriptor.parameterCount(); i++) {
            names.add(bySlot.get(slot));
            var parameter = descriptor.parameterType(i).descriptorString();
            slot += parameter.equals("J") || parameter.equals("D")
                    ? 2
                    : 1;
        }
        return names;
    }

    private static String renderSignature(Signature signature) {
        return switch (signature) {
            case BaseTypeSig base -> ClassDesc.ofDescriptor(Character.toString(base.baseType())).displayName();
            case ClassTypeSig type -> renderClassTypeSignature(type);
            case TypeVarSig variable -> variable.identifier();
            case ArrayTypeSig array -> renderSignature(array.componentSignature()) + "[]";
        };
    }

    private static String renderClassTypeSignature(ClassTypeSig signature) {
        var out = new StringBuilder();
        signature.outerType().ifPresentOrElse(
                outer -> out.append(renderClassTypeSignature(outer))
                            .append('.')
                            .append(signature.classDesc()
                                             .displayName()
                                             .replace('$', '.')),
                () -> out.append(javaType(signature.classDesc())));
        if (!signature.typeArgs().isEmpty()) {
            out.append('<');
            for (int i = 0; i < signature.typeArgs().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(renderTypeArgument(signature.typeArgs()
                        .get(i)));
            }
            out.append('>');
        }
        return out.toString();
    }

    private static String renderTypeArgument(TypeArg argument) {
        return switch (argument) {
            case Unbounded _ -> "?";
            case Bounded bounded -> switch (bounded.wildcardIndicator()) {
                        case NONE -> renderSignature(bounded.boundType());
                        case EXTENDS -> "? extends " + renderSignature(bounded.boundType());
                        case SUPER -> "? super " + renderSignature(bounded.boundType());
                    };
        };
    }

    private static String renderThrowableSignature(ThrowableSig signature) {
        return switch (signature) {
            case ClassTypeSig type -> renderClassTypeSignature(type);
            case TypeVarSig variable -> variable.identifier();
        };
    }

    private static void appendTypeParameters(StringBuilder out, List<TypeParam> parameters) {
        out.append('<');
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            var parameter = parameters.get(i);
            out.append(parameter.identifier());
            var bounds = new ArrayList<String>();
            parameter.classBound().ifPresent(bound -> {
                if (!(bound instanceof ClassTypeSig type && type.classDesc().equals(ConstantDescs.CD_Object))) {
                    bounds.add(renderSignature(bound));
                }
            });
            parameter.interfaceBounds().forEach(bound -> bounds.add(renderSignature(bound)));
            if (!bounds.isEmpty()) {
                out.append(" extends ").append(String.join(" & ", bounds));
            }
        }
        out.append('>');
    }

    static void appendDeclarationAnnotations(StringBuilder out, AttributedElement element) {
        element.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(attribute -> attribute.annotations().stream()
                .filter(annotation -> !isCompilerMetadata(annotation))
                .forEach(annotation -> out.append(renderAnnotation(annotation)).append(' ')));
        element.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(attribute -> attribute.annotations().stream()
                .filter(annotation -> !isCompilerMetadata(annotation))
                .forEach(annotation -> out.append(renderAnnotation(annotation)).append(' ')));
    }

    private static boolean isCompilerMetadata(Annotation annotation) {
        return switch (annotation.classSymbol().descriptorString()) {
            case "Lkotlin/Metadata;", "Lkotlin/jvm/internal/SourceDebugExtension;" -> true;
            default -> false;
        };
    }

    private static String renderAnnotation(Annotation annotation) {
        var out = new StringBuilder("@").append(javaType(annotation.classSymbol()));
        if (annotation.elements().isEmpty()) {
            return out.toString();
        }
        boolean singleValue = annotation.elements().size() == 1 && annotation.elements()
                .getFirst()
                .name()
                .equalsString("value");
        out.append('(');
        for (int i = 0; i < annotation.elements().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            var element = annotation.elements().get(i);
            if (!singleValue) {
                out.append(element.name().stringValue()).append(" = ");
            }
            out.append(renderAnnotationValue(element.value()));
        }
        return out.append(')').toString();
    }

    private static String renderAnnotationValue(AnnotationValue value) {
        return switch (value) {
            case OfString string -> javaStringLiteral(string.stringValue());
            case OfBoolean bool -> Boolean.toString(bool.booleanValue());
            case OfChar character -> javaCharLiteral(character.charValue());
            case OfConstant constant -> renderJavaConstant(constant.constant()
                    .constantValue());
            case OfEnum enumeration -> javaType(enumeration.classSymbol()) + "." + enumeration.constantName().stringValue();
            case OfClass type -> javaType(type.classSymbol()) + ".class";
            case OfAnnotation nested -> renderAnnotation(nested.annotation());
            case OfArray array ->
                    array.values().stream()
                            .map(ClassFileRenderer::renderAnnotationValue)
                            .collect(Collectors.joining(", ", "{", "}"));
        };
    }

    private static String renderFieldConstant(String descriptor, Object value) {
        return switch (descriptor) {
            case "Z" -> ((Number) value).intValue() == 0 ? "false" : "true";
            case "C" -> javaCharLiteral((char) ((Number) value).intValue());
            default -> renderJavaConstant(value);
        };
    }

    private static String renderJavaConstant(Object value) {
        return switch (value) {
            case String string -> javaStringLiteral(string);
            case Character character -> javaCharLiteral(character);
            case Long number -> number + "L";
            case Float number when number.isNaN() -> "Float.NaN";
            case Float number when number == Float.POSITIVE_INFINITY -> "Float.POSITIVE_INFINITY";
            case Float number when number == Float.NEGATIVE_INFINITY -> "Float.NEGATIVE_INFINITY";
            case Float number -> number + "F";
            case Double number when number.isNaN() -> "Double.NaN";
            case Double number when number == Double.POSITIVE_INFINITY -> "Double.POSITIVE_INFINITY";
            case Double number when number == Double.NEGATIVE_INFINITY -> "Double.NEGATIVE_INFINITY";
            default -> value.toString();
        };
    }

    private static String javaStringLiteral(String value) {
        return javaLiteral(value, '"');
    }

    private static String javaCharLiteral(char value) {
        return javaLiteral(String.valueOf(value), '\'');
    }

    private static String javaLiteral(String value, char delimiter) {
        var out = new StringBuilder(value.length() + 2).append(delimiter);
        for (int i = 0; i < value.length(); i++) {
            var character = value.charAt(i);
            switch (character) {
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                case '\\' -> out.append("\\\\");
                case '"' -> out.append(delimiter == '"' ? "\\\"" : "\"");
                case '\'' -> out.append(delimiter == '\'' ? "\\'" : "'");
                default -> {
                    if (Character.isISOControl(character)) {
                        if (character <= 0xff) {
                            out.append('\\')
                               .append((char) ('0' + (character >>> 6)))
                               .append((char) ('0' + (character >>> 3 & 7)))
                               .append((char) ('0' + (character & 7)));
                        } else {
                            out.append("\\u");
                            for (int shift = 12; shift >= 0; shift -= 4) {
                                out.append(Character.forDigit(character >>> shift & 0xf, 16));
                            }
                        }
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append(delimiter).toString();
    }

    private static void appendDeclarationModifiers(StringBuilder out, Set<AccessFlag> flags, boolean type) {
        for (var flag : List.of(
                AccessFlag.PUBLIC,
                AccessFlag.PROTECTED,
                AccessFlag.PRIVATE,
                AccessFlag.ABSTRACT,
                AccessFlag.STATIC,
                AccessFlag.FINAL,
                AccessFlag.NATIVE,
                AccessFlag.SYNCHRONIZED,
                AccessFlag.STRICT)) {
            if (!flags.contains(flag)) {
                continue;
            }
            if (type && flag == AccessFlag.STATIC) {
                continue;
            }
            out.append(flag.name().toLowerCase(Locale.ROOT)).append(' ');
        }
    }

    static String javaType(String descriptor) {
        return javaType(ClassDesc.ofDescriptor(descriptor));
    }

    private static String javaType(ClassDesc descriptor) {
        return descriptor.displayName().replace('$', '.');
    }

    static Set<AccessFlag> listedClassFlags(ClassModel model) {
        var internalName = model.thisClass().asInternalName();
        var innerClasses = model.findAttribute(Attributes.innerClasses());
        if (innerClasses.isPresent()) {
            for (var inner : innerClasses.get().classes()) {
                if (inner.innerClass()
                         .asInternalName()
                         .equals(internalName)) {
                    return inner.flags();
                }
            }
        }
        return model.flags().flags();
    }

    /**
     * Convert internal class name (e.g. {@code java/lang/System$LoggerFinder})
     * to module-info source form ({@code java.lang.System.LoggerFinder}).
     */
    private static String internalToModuleInfoName(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }
}
