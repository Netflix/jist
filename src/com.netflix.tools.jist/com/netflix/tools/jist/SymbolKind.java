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
import java.lang.classfile.ClassModel;

import com.sun.source.tree.ClassTree;

enum SymbolKind {
    MODULE("module"),
    PACKAGE("package"),
    TYPE("type"),
    CLASS("class"),
    INTERFACE("interface"),
    ENUM("enum"),
    RECORD("record"),
    ANNOTATION("annotation"),
    METHOD("method"),
    FIELD("field");

    private static final String ACCEPTED = Arrays.stream(values())
            .map(SymbolKind::optionName)
            .collect(Collectors.joining(", "));

    private final String optionName;

    SymbolKind(String optionName) {
        this.optionName = optionName;
    }

    String optionName() {
        return optionName;
    }

    static SymbolKind parse(String value) throws ToolException {
        for (var kind : values()) {
            if (kind.optionName.equals(value)) {
                return kind;
            }
        }
        throw new ToolException(1, "Unknown symbol kind '" + value + "'; expected one of: " + ACCEPTED);
    }

    boolean matches(ClassModel model) {
        var actual = classKind(model);
        return this == TYPE || this == actual;
    }

    boolean matches(ClassTree tree) {
        var actual = classKind(tree);
        return actual != null && (this == TYPE || this == actual);
    }

    static SymbolKind classKind(ClassTree tree) {
        return switch (tree.getKind()) {
            case ANNOTATION_TYPE -> ANNOTATION;
            case ENUM -> ENUM;
            case RECORD -> RECORD;
            case INTERFACE -> INTERFACE;
            case CLASS -> CLASS;
            default -> null;
        };
    }

    static SymbolKind classKind(ClassModel model) {
        if (model.flags().has(AccessFlag.ANNOTATION)) {
            return ANNOTATION;
        }
        if (model.flags().has(AccessFlag.ENUM)) {
            return ENUM;
        }
        if (model.findAttribute(Attributes.record()).isPresent()) {
            return RECORD;
        }
        if (model.flags().has(AccessFlag.INTERFACE)) {
            return INTERFACE;
        }
        return CLASS;
    }
}
