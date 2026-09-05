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

/** Output adapters for streaming search results. */
final class Output {
    private Output() {}

    private static final class CountingWriter extends Writer {
        private final Writer delegate;
        private long count;

        CountingWriter(Writer delegate) {
            this.delegate = delegate;
        }

        long count() {
            return count;
        }

        void match() {
            count++;
        }

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            delegate.write(chars, offset, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.flush();
        }
    }

    interface OriginOutput {
        void sourceOrigin(String unit, String sourceFile, String origin);

        void classOrigin(String unit, String origin);
    }

    interface SymbolOutput {
        boolean listOnly();

        void symbol(SymbolKind kind, String name, String location);
    }

    interface SourceWarningOutput {
        void sourceUnavailable(String unit);
    }

    static final class SourceWarnings {
        private final PrintWriter err;
        private final Set<String> unavailable = new HashSet<>();

        SourceWarnings(PrintWriter err) {
            this.err = err;
        }

        void unavailable(String unit) {
            if (unavailable.add(unit)) {
                err.println("Warning: Source unavailable for " + unit + "; using ClassFile information");
            }
        }
    }

    static final class SourceWarningPrintWriter extends PrintWriter implements SourceWarningOutput {
        private final SourceWarnings warnings;

        SourceWarningPrintWriter(PrintWriter delegate, SourceWarnings warnings) {
            super(delegate, true);
            this.warnings = warnings;
        }

        @Override
        public void sourceUnavailable(String unit) {
            warnings.unavailable(unit);
        }
    }

    static final class CountingPrintWriter extends PrintWriter implements OriginOutput, SymbolOutput, SourceWarningOutput {
        private final PrintWriter delegate;
        private final CountingWriter counter;

        CountingPrintWriter(PrintWriter delegate) {
            this(delegate, new CountingWriter(delegate));
        }

        private CountingPrintWriter(PrintWriter delegate, CountingWriter counter) {
            super(counter, true);
            this.delegate = delegate;
            this.counter = counter;
        }

        long count() {
            return counter.count();
        }

        @Override
        public void sourceOrigin(String unit, String sourceFile, String origin) {
            if (delegate instanceof OriginOutput output) {
                output.sourceOrigin(unit, sourceFile, origin);
            }
        }

        @Override
        public void classOrigin(String unit, String origin) {
            if (delegate instanceof OriginOutput output) {
                output.classOrigin(unit, origin);
            }
        }

        @Override
        public boolean listOnly() {
            return delegate instanceof SymbolOutput output && output.listOnly();
        }

        @Override
        public void symbol(SymbolKind kind, String name, String location) {
            counter.match();
            if (delegate instanceof SymbolOutput output) {
                output.symbol(kind, name, location);
            }
        }

        @Override
        public void sourceUnavailable(String unit) {
            if (delegate instanceof SourceWarningOutput output) {
                output.sourceUnavailable(unit);
            }
        }
    }

    static final class InteractivePrintWriter extends PrintWriter implements OriginOutput, SymbolOutput, SourceWarningOutput {
        private final InteractiveWriter interactive;
        private final SourceWarnings warnings;

        InteractivePrintWriter(
                PrintWriter delegate,
                boolean heading,
                boolean color,
                boolean listOnly,
                boolean qualifiedPath,
                boolean lineNumber,
                boolean breakMatches,
                boolean compactFile,
                SourceWarnings warnings) {
            this(
                    new InteractiveWriter(delegate, heading, color, listOnly, qualifiedPath, lineNumber,
                            breakMatches, compactFile),
                    warnings);
        }

        private InteractivePrintWriter(InteractiveWriter interactive, SourceWarnings warnings) {
            super(interactive, true);
            this.interactive = interactive;
            this.warnings = warnings;
        }

        @Override
        public void sourceOrigin(String unit, String sourceFile, String origin) {
            if (origin != null) {
                interactive.sourceOrigin(unit, sourceFile, origin);
            }
        }

        @Override
        public void classOrigin(String unit, String origin) {
            if (origin != null) {
                interactive.classOrigin(unit, origin);
            }
        }

        @Override
        public boolean listOnly() {
            return interactive.listOnly;
        }

        @Override
        public void symbol(SymbolKind kind, String name, String location) {
            try {
                interactive.writeSymbol(kind, name, location);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void sourceUnavailable(String unit) {
            warnings.unavailable(unit);
        }

        void sourceHighlights(String unit, String sourceFile, int line,
                              List<SourceHighlight> highlights) {
            interactive.sourceHighlights(unit, sourceFile, line, highlights);
        }

        boolean highlightsEnabled() {
            return interactive.color;
        }
    }

    private static final class InteractiveWriter extends Writer {
        private static final String RESET = "\033[0m";
        private static final String HEADING = "\033[1;35m";
        private static final String HEADING_KIND = "\033[36m";
        private static final String UNIT = "\033[35m";
        private static final String LINE = "\033[32m";
        private static final String SEPARATOR = "\033[36m";
        private static final String MATCH = "\033[1;31m";

        private final Writer delegate;
        private final boolean heading;
        private final boolean color;
        private final boolean listOnly;
        private final boolean qualifiedPath;
        private final boolean lineNumber;
        private final boolean breakMatches;
        private final boolean compactFile;
        private final StringBuilder line = new StringBuilder();
        private String currentGroup;
        private String currentUnit;
        private String sourceUnit;
        private String sourceFile;
        private String sourceOrigin;
        private final Map<String, String> classOrigins = new HashMap<>();
        private String previousSourceGroup;
        private int previousSourceLine;
        private String highlightUnit;
        private String highlightFile;
        private int highlightLine;
        private List<SourceHighlight> highlights = List.of();

        InteractiveWriter(
                Writer delegate,
                boolean heading,
                boolean color,
                boolean listOnly,
                boolean qualifiedPath,
                boolean lineNumber,
                boolean breakMatches,
                boolean compactFile) {
            this.delegate = delegate;
            this.heading = heading;
            this.color = color;
            this.listOnly = listOnly;
            this.qualifiedPath = qualifiedPath;
            this.lineNumber = lineNumber;
            this.breakMatches = breakMatches;
            this.compactFile = compactFile;
        }

        void sourceOrigin(String unit, String sourceFile, String origin) {
            this.sourceUnit = unit;
            this.sourceFile = sourceFile;
            this.sourceOrigin = origin;
        }

        void classOrigin(String unit, String origin) {
            classOrigins.put(unit, origin);
        }

        void sourceHighlights(String unit, String sourceFile, int line,
                              List<SourceHighlight> highlights) {
            this.highlightUnit = unit;
            this.highlightFile = sourceFile;
            this.highlightLine = line;
            this.highlights = highlights;
        }

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            int end = offset + length;
            for (int i = offset; i < end; i++) {
                if (chars[i] == '\n') {
                    writeLine(line.toString());
                    line.setLength(0);
                } else if (chars[i] != '\r') {
                    line.append(chars[i]);
                }
            }
        }

        private void writeLine(String text) throws IOException {
            int close = text.lastIndexOf("): ");
            int open = close >= 0 ? text.lastIndexOf('(', close) : -1;
            int lineSeparator = close >= 0 ? text.lastIndexOf(':', close) : -1;
            if (open > 0 && lineSeparator > open && decimal(text, lineSeparator + 1, close)) {
                var unit = text.substring(0, open);
                var sourceFile = text.substring(open + 1, lineSeparator);
                var lineNumber = text.substring(lineSeparator + 1, close);
                var sourceText = text.substring(close + 3);
                writeSourceRow(unit, sourceFile, lineNumber, sourceText);
                return;
            }

            int separator = text.indexOf(": ");
            if (separator > 0) {
                writeDeclarationRow(text.substring(0, separator), text.substring(separator + 2));
                return;
            }
            if (heading && currentUnit != null && text.equals(currentUnit + ":")) {
                if (!listOnly) {
                    delegate.write('\n');
                }
                return;
            }
            delegate.write(text);
            delegate.write('\n');
        }

        private void writeSourceRow(String unit, String sourceFile, String lineNumber,
                String sourceText)
                throws IOException {
            var source = unit.equals(sourceUnit) && sourceFile.equals(this.sourceFile)
                    ? sourceOrigin
                    : null;
            var origin = source != null ? source : classOrigins.get(unit);
            var path = qualifiedPath && origin != null ? origin : sourceFile;
            var group = unit
                    + "("
                    + (origin != null ? origin : sourceFile)
                    + ")";
            var displayedGroup = unit + "(" + path + ")";
            int numericLine = Integer.parseInt(lineNumber);
            var rowHighlights = unit.equals(highlightUnit) && sourceFile.equals(highlightFile) && numericLine == highlightLine
                    ? highlights
                    : List.<SourceHighlight>of();
            highlights = List.of();
            if (!listOnly
                    && breakMatches
                    && previousSourceGroup != null
                    && (!group.equals(previousSourceGroup) || numericLine != previousSourceLine + 1)
                    && (!heading || group.equals(currentGroup))) {
                delegate.write('\n');
            }
            previousSourceGroup = group;
            previousSourceLine = numericLine;
            if (compactFile) {
                if (this.lineNumber) {
                    writeColored(LINE, lineNumber);
                    writeColored(SEPARATOR, ":");
                }
                writeHighlighted(sourceText, rowHighlights);
                delegate.write('\n');
                return;
            }
            if (heading) {
                writeHeading(group, displayedGroup, unit, sourceHeadingKind(sourceFile));
                if (listOnly) {
                    return;
                }
                if (this.lineNumber) {
                    writeColored(LINE, lineNumber);
                    writeColored(SEPARATOR, ":");
                }
                writeHighlighted(sourceText, rowHighlights);
                delegate.write('\n');
                return;
            }
            writeColored(UNIT, unit);
            delegate.write('(');
            delegate.write(path);
            if (this.lineNumber) {
                delegate.write(':');
                writeColored(LINE, lineNumber);
            }
            delegate.write(')');
            writeColored(SEPARATOR, ":");
            delegate.write(' ');
            writeHighlighted(sourceText, rowHighlights);
            delegate.write('\n');
        }

        private void writeDeclarationRow(String unit, String declaration) throws IOException {
            var origin = classOrigins.get(unit);
            var group = origin != null ? unit + "(" + origin + ")" : unit;
            var displayedGroup = qualifiedPath && origin != null
                    ? unit + "(" + origin + ")"
                    : unit;
            if (heading) {
                writeHeading(group, displayedGroup, unit, declarationHeadingKind(unit, declaration));
                if (listOnly) {
                    return;
                }
                delegate.write(declaration);
                delegate.write('\n');
                return;
            }
            writeColored(UNIT, unit);
            if (qualifiedPath && origin != null) {
                delegate.write('(');
                delegate.write(origin);
                delegate.write(')');
            }
            writeColored(SEPARATOR, ":");
            delegate.write(' ');
            delegate.write(declaration);
            delegate.write('\n');
        }

        private void writeHeading(String group, String displayedGroup, String unit,
                String kind)
                throws IOException {
            if (!group.equals(currentGroup)) {
                if (currentGroup != null && !listOnly) {
                    delegate.write('\n');
                }
                writeColored(HEADING_KIND, kind);
                delegate.write(' ');
                writeColored(HEADING, displayedGroup);
                delegate.write('\n');
                currentGroup = group;
                currentUnit = unit;
            }
        }

        private void writeSymbol(SymbolKind kind, String name, String location) throws IOException {
            writeColored(HEADING_KIND, kind.optionName());
            delegate.write(' ');
            var displayedName = qualifiedPath && location != null
                    ? name + "(" + location + ")"
                    : name;
            writeColored(HEADING, displayedName);
            delegate.write('\n');
        }

        private static String sourceHeadingKind(String sourceFile) {
            if (sourceFile.equals("module-info.java")) {
                return "module";
            }
            if (sourceFile.equals("package-info.java")) {
                return "package";
            }
            return "class";
        }

        private static String declarationHeadingKind(String unit, String declaration) {
            if (declaration.contains("package " + unit + ";")) {
                return "package";
            }
            if (declaration.contains("module " + unit)) {
                return "module";
            }
            return "class";
        }

        private void writeColored(String ansi, String text) throws IOException {
            if (color) {
                delegate.write(ansi);
            }
            delegate.write(text);
            if (color) {
                delegate.write(RESET);
            }
        }

        private void writeHighlighted(String text, List<SourceHighlight> highlights) throws IOException {
            if (!color || highlights.isEmpty()) {
                delegate.write(text);
                return;
            }
            var ordered = highlights.stream()
                    .sorted(Comparator.comparingInt(SourceHighlight::startColumn))
                    .toList();
            int written = 0;
            for (var highlight : ordered) {
                int start = Math.max(written,
                        Math.min(text.length(), highlight.startColumn()));
                int end = Math.max(start,
                        Math.min(text.length(), highlight.endColumn()));
                if (start == end) {
                    continue;
                }
                delegate.write(text, written, start - written);
                delegate.write(MATCH);
                delegate.write(text, start, end - start);
                delegate.write(RESET);
                written = end;
            }
            delegate.write(text, written, text.length() - written);
        }

        private static boolean decimal(String text, int start, int end) {
            if (start >= end) {
                return false;
            }
            for (int i = start; i < end; i++) {
                if (!Character.isDigit(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void flush() throws IOException {
            if (!line.isEmpty()) {
                writeLine(line.toString());
                line.setLength(0);
            }
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    record SourceHighlight(int line, int startColumn, int endColumn) {}
}
