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
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodTypeEntry;
import java.lang.classfile.constantpool.NameAndTypeEntry;

final class ClassReferences {
    private final Set<String> names = new LinkedHashSet<>();

    static Set<String> collect(List<ClassModel> models) {
        var references = new ClassReferences();
        for (var model : models) {
            references.read(model);
        }
        models.forEach(model -> references.names.remove(model.thisClass().asInternalName().replace('/', '.').replace('$', '.')));
        return references.names;
    }

    private void read(ClassModel model) {
        for (var entry : model.constantPool()) {
            switch (entry) {
                case ClassEntry type -> descriptor(type.asSymbol().descriptorString());
                case NameAndTypeEntry member -> descriptor(member.type().stringValue());
                case MethodTypeEntry method -> descriptor(method.descriptor().stringValue());
                default -> {}
            }
        }
        metadata(model);
        model.findAttribute(Attributes.signature()).ifPresent(attribute -> classSignature(attribute.asClassSignature()));
        for (var field : model.fields()) {
            descriptor(field.fieldType().stringValue());
            metadata(field);
            field.findAttribute(Attributes.signature()).ifPresent(attribute -> signature(attribute.asTypeSignature()));
        }
        for (var method : model.methods()) {
            descriptor(method.methodType().stringValue());
            metadata(method);
            method.findAttribute(Attributes.signature()).ifPresent(attribute -> methodSignature(attribute.asMethodSignature()));
            method.findAttribute(Attributes.runtimeVisibleParameterAnnotations()).ifPresent(attribute -> attribute.parameterAnnotations()
                    .forEach(annotations -> annotations.forEach(this::annotation)));
            method.findAttribute(Attributes.runtimeInvisibleParameterAnnotations()).ifPresent(attribute -> attribute.parameterAnnotations()
                    .forEach(annotations -> annotations.forEach(this::annotation)));
            method.findAttribute(Attributes.annotationDefault()).ifPresent(attribute -> annotationValue(attribute.defaultValue()));
            method.code().ifPresent(code -> {
                metadata(code);
                code.findAttribute(Attributes.localVariableTable()).ifPresent(attribute -> attribute.localVariables()
                        .forEach(variable -> descriptor(variable.type().stringValue())));
                code.findAttribute(Attributes.localVariableTypeTable()).ifPresent(attribute -> attribute.localVariableTypes()
                        .forEach(variable -> signature(Signature.parseFrom(variable.signature().stringValue()))));
            });
        }
        model.findAttribute(Attributes.record()).ifPresent(attribute -> attribute.components().forEach(component -> {
            descriptor(component.descriptor().stringValue());
            metadata(component);
            component.findAttribute(Attributes.signature()).ifPresent(value -> signature(value.asTypeSignature()));
        }));
    }

    private void descriptor(String descriptor) {
        if (descriptor.startsWith("(")) {
            methodSignature(MethodSignature.parseFrom(descriptor));
        } else {
            signature(Signature.parseFrom(descriptor));
        }
    }

    private void classSignature(ClassSignature signature) {
        signature.typeParameters().forEach(this::parameter);
        signature(signature.superclassSignature());
        signature.superinterfaceSignatures().forEach(this::signature);
    }

    private void methodSignature(MethodSignature signature) {
        signature.typeParameters().forEach(this::parameter);
        signature.arguments().forEach(this::signature);
        signature(signature.result());
        signature.throwableSignatures().forEach(type -> signature((Signature) type));
    }

    private void parameter(Signature.TypeParam parameter) {
        parameter.classBound().ifPresent(this::signature);
        parameter.interfaceBounds().forEach(this::signature);
    }

    private void signature(Signature signature) {
        switch (signature) {
            case Signature.ClassTypeSig type -> {
                var descriptor = type.classDesc().descriptorString();
                names.add(descriptor.substring(1, descriptor.length() - 1).replace('/', '.').replace('$', '.'));
                type.outerType().ifPresent(this::signature);
                type.typeArgs().forEach(argument -> {
                    if (argument instanceof Signature.TypeArg.Bounded bounded) signature(bounded.boundType());
                });
            }
            case Signature.ArrayTypeSig array -> signature(array.componentSignature());
            default -> {}
        }
    }

    private void metadata(AttributedElement element) {
        element.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(attribute -> attribute.annotations().forEach(this::annotation));
        element.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(attribute -> attribute.annotations().forEach(this::annotation));
        element.findAttribute(Attributes.runtimeVisibleTypeAnnotations()).ifPresent(attribute -> attribute.annotations().forEach(type -> annotation(type.annotation())));
        element.findAttribute(Attributes.runtimeInvisibleTypeAnnotations()).ifPresent(attribute -> attribute.annotations().forEach(type -> annotation(type.annotation())));
    }

    private void annotation(Annotation annotation) {
        descriptor(annotation.classSymbol().descriptorString());
        annotation.elements().forEach(element -> annotationValue(element.value()));
    }

    private void annotationValue(AnnotationValue value) {
        switch (value) {
            case AnnotationValue.OfClass type -> descriptor(type.classSymbol().descriptorString());
            case AnnotationValue.OfEnum enumeration -> descriptor(enumeration.classSymbol().descriptorString());
            case AnnotationValue.OfAnnotation nested -> annotation(nested.annotation());
            case AnnotationValue.OfArray array -> array.values().forEach(this::annotationValue);
            default -> {}
        }
    }
}
