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

import javax.lang.model.element.Modifier;

public record Visibility(Set<Level> levels) {
    public enum Level {
        PUBLIC, PROTECTED, PACKAGE, PRIVATE
    }

    public static final Visibility LIBRARY = new Visibility(EnumSet.of(Level.PUBLIC, Level.PROTECTED));
    public static final Visibility ALL = new Visibility(EnumSet.allOf(Level.class));

    public Visibility {
        levels = Set.copyOf(levels);
    }

    static Visibility parse(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        var levels = EnumSet.noneOf(Level.class);
        for (var value : values) {
            if (value.equals("all")) {
                levels.addAll(ALL.levels());
            } else {
                levels.add(Level.valueOf(value.toUpperCase(Locale.ROOT)));
            }
        }
        return new Visibility(levels);
    }

    boolean includesAll() {
        return levels.size() == Level.values().length;
    }

    boolean visible(AccessFlags flags) {
        return visible(flags.flags());
    }

    boolean visible(Set<AccessFlag> flags) {
        var level = flags.contains(AccessFlag.PUBLIC) ? Level.PUBLIC
                : flags.contains(AccessFlag.PROTECTED) ? Level.PROTECTED
                : flags.contains(AccessFlag.PRIVATE) ? Level.PRIVATE
                : Level.PACKAGE;
        return levels.contains(level);
    }

    boolean visibleModifiers(Set<Modifier> modifiers) {
        return visibleModifiers(modifiers, false);
    }

    boolean visibleModifiers(Set<Modifier> modifiers, boolean implicitlyPublic) {
        var level = modifiers.contains(Modifier.PUBLIC) ? Level.PUBLIC
                : modifiers.contains(Modifier.PROTECTED) ? Level.PROTECTED
                : modifiers.contains(Modifier.PRIVATE) ? Level.PRIVATE
                : implicitlyPublic ? Level.PUBLIC : Level.PACKAGE;
        return levels.contains(level);
    }
}
