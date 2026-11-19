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

import com.netflix.tools.json.JsonString;

/** Output adapters for streaming search results. */
final class Output {
    private Output() {}

    record ResultOrigin(
            String kind,
            String location,
            String classLocation,
            String system,
            String module,
            String version,
            String resource,
            String source,
            Integer line) {}

    record SourceRange(String location, int lineCount, int startLine, int endLine) {}

    record SymbolLocation(String classLocation, String sourceLocation, String sourceFile) {
        static SymbolLocation of(String location, SourceRange source) {
            var sourceLocation = source != null ? source.location()
                    : location != null && location.endsWith(".java") ? location : null;
            return new SymbolLocation(Objects.equals(location, sourceLocation) ? null : location,
                    sourceLocation, sourceLocation == null ? null : fileName(sourceLocation));
        }
    }

    private interface ResultOutput {
        void resultOrigin(ResultOrigin origin);

        void resultSource(SourceRange source);

        void resultSymbol(SymbolKind kind, String name);

        void resultUnit(String unit);
    }

    static final class JsonPrintWriter extends PrintWriter implements ResultOutput {
        private final JsonWriter json;

        JsonPrintWriter(PrintWriter delegate) {
            this(new JsonWriter(delegate));
        }

        private JsonPrintWriter(JsonWriter json) {
            super(json, true);
            this.json = json;
        }

        @Override
        public void resultOrigin(ResultOrigin origin) {
            json.resultOrigin(origin);
        }

        @Override
        public void resultSource(SourceRange source) {
            json.resultSource(source);
        }

        @Override
        public void resultSymbol(SymbolKind kind, String name) {
            json.kind = kind.optionName();
            json.symbol = name;
        }

        @Override
        public void resultUnit(String unit) {
            json.unit = unit;
        }

        void failure(int exitCode, String kind, String message) {
            json.exitCode = exitCode;
            json.diagnosticKind = kind;
            json.diagnosticMessage = message;
        }

        void finish() {
            flush();
            json.finish();
        }
    }

    private static final class JsonWriter extends Writer {
        private final Writer delegate;
        private final StringBuilder line = new StringBuilder();
        private boolean first = true;
        private boolean finished;
        private int exitCode;
        private String diagnosticKind;
        private String diagnosticMessage;
        private ResultOrigin origin;
        private SourceRange source;
        private String kind;
        private String symbol;
        private String unit;

        JsonWriter(Writer delegate) {
            this.delegate = delegate;
            try {
                delegate.write("{\"format\":\"jist\",\"version\":1,\"results\":[");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            if (finished) {
                throw new IOException("JSON output is already finished");
            }
            int end = offset + length;
            for (int i = offset; i < end; i++) {
                if (chars[i] == '\n') {
                    emitLine();
                } else if (chars[i] != '\r') {
                    line.append(chars[i]);
                }
            }
        }

        private void emitLine() throws IOException {
            if (line.isEmpty()) {
                origin = null;
                source = null;
                kind = null;
                symbol = null;
                unit = null;
                return;
            }
            if (!first) {
                delegate.write(',');
            }
            delegate.write("{\"text\":");
            delegate.write(JsonString.of(line.toString()).toString());
            writeField("kind", kind, true);
            writeField("symbol", symbol, true);
            writeField("unit", unit, true);
            if (origin != null) {
                writeOrigin(origin);
            }
            if (source != null) {
                writeSource(source);
            }
            delegate.write('}');
            line.setLength(0);
            origin = null;
            source = null;
            kind = null;
            symbol = null;
            unit = null;
            first = false;
        }

        private void writeOrigin(ResultOrigin value) throws IOException {
            delegate.write(",\"origin\":{");
            writeField("kind", value.kind(), false);
            writeField("location", value.location(), true);
            writeField("classLocation", value.classLocation(), true);
            writeField("system", value.system(), true);
            writeField("module", value.module(), true);
            writeField("version", value.version(), true);
            writeField("resource", value.resource(), true);
            writeField("source", value.source(), true);
            if (value.line() != null) {
                delegate.write(",\"line\":");
                delegate.write(Integer.toString(value.line()));
            }
            delegate.write('}');
        }

        private void writeSource(SourceRange value) throws IOException {
            delegate.write(",\"source\":{");
            writeField("location", value.location(), false);
            delegate.write(",\"lineCount\":");
            delegate.write(Integer.toString(value.lineCount()));
            delegate.write(",\"range\":{\"startLine\":");
            delegate.write(Integer.toString(value.startLine()));
            delegate.write(",\"endLine\":");
            delegate.write(Integer.toString(value.endLine()));
            delegate.write("}}");
        }

        private void writeField(String name, String value, boolean comma) throws IOException {
            if (value == null) {
                return;
            }
            if (comma) {
                delegate.write(',');
            }
            delegate.write(JsonString.of(name).toString());
            delegate.write(':');
            delegate.write(JsonString.of(value).toString());
        }

        void resultOrigin(ResultOrigin value) {
            origin = value;
        }

        void resultSource(SourceRange value) {
            source = value;
        }

        void finish() {
            if (finished) {
                return;
            }
            try {
                emitLine();
                delegate.write("],\"exitCode\":");
                delegate.write(Integer.toString(exitCode));
                delegate.write(",\"diagnostics\":[");
                if (diagnosticKind != null) {
                    delegate.write('{');
                    writeField("kind", diagnosticKind, false);
                    writeField("message", diagnosticMessage, true);
                    delegate.write('}');
                }
                delegate.write("]}\n");
                delegate.flush();
                finished = true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() {
            finish();
        }
    }

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

        default void symbol(SymbolKind kind, String name, String location) {
            symbol(kind, name, SymbolLocation.of(location, null), null);
        }

        default void symbol(SymbolKind kind, String name, String location, SourceRange source) {
            symbol(kind, name, SymbolLocation.of(location, source), source);
        }

        void symbol(SymbolKind kind, String name, SymbolLocation location, SourceRange source);
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
        public void symbol(SymbolKind kind, String name, SymbolLocation location, SourceRange source) {
            counter.match();
            if (delegate instanceof SymbolOutput output) {
                output.symbol(kind, name, location, source);
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
                boolean showOrigin,
                boolean lineNumber,
                boolean breakMatches,
                boolean compactFile,
                OriginResolver origins,
                SourceWarnings warnings) {
            this(
                    new InteractiveWriter(delegate, heading, color, listOnly, showOrigin, lineNumber,
                            breakMatches, compactFile, origins),
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
        public void symbol(SymbolKind kind, String name, SymbolLocation location, SourceRange source) {
            try {
                interactive.writeSymbol(kind, name, location, source);
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
        private final boolean showOrigin;
        private final boolean lineNumber;
        private final boolean breakMatches;
        private final boolean compactFile;
        private final OriginResolver origins;
        private final StringBuilder line = new StringBuilder();
        private String currentGroup;
        private String currentUnit;
        private String sourceUnit;
        private String sourceFile;
        private String sourceOrigin;
        private String currentArchive;
        private String currentArchiveName;
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
                boolean showOrigin,
                boolean lineNumber,
                boolean breakMatches,
                boolean compactFile,
                OriginResolver origins) {
            this.delegate = delegate;
            this.heading = heading;
            this.color = color;
            this.listOnly = listOnly;
            this.showOrigin = showOrigin;
            this.lineNumber = lineNumber;
            this.breakMatches = breakMatches;
            this.compactFile = compactFile;
            this.origins = origins;
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
            var classLocation = classOrigins.getOrDefault(unit, origin);
            int numericLine = Integer.parseInt(lineNumber);
            var resolved = resultOrigin(origin, sourceFile, numericLine, classLocation);
            if (delegate instanceof ResultOutput output) output.resultUnit(unit);
            var displayedUnit = qualify(unit, resolved);
            var path = showOrigin && origin != null
                    ? origin
                    : sourceFile;
            if (!showOrigin && resolved != null && resolved.module() == null) {
                var archive = archiveName(classLocation);
                if (archive != null) path = archive + "!/" + sourceFile;
            }
            var group = unit
                    + "("
                    + (origin != null ? origin : sourceFile)
                    + ")";
            var displayedGroup = displayedUnit + "(" + path + ")";
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
            writeColored(UNIT, displayedUnit);
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
            var resolved = resultOrigin(origin, null, null);
            if (delegate instanceof ResultOutput output) output.resultUnit(unit);
            var group = origin != null ? unit + "(" + origin + ")" : unit;
            var displayedGroup = reference(unit, resolved);
            if (heading) {
                writeHeading(group, displayedGroup, unit, declarationHeadingKind(unit, declaration));
                if (listOnly) {
                    return;
                }
                delegate.write(declaration);
                delegate.write('\n');
                return;
            }
            writeColored(UNIT, displayedGroup);
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

        private void writeSymbol(SymbolKind kind, String name, SymbolLocation location, SourceRange source) throws IOException {
            var resolved = origins.resolve(location.classLocation(), location.sourceLocation(), location.sourceFile(), null);
            if (delegate instanceof ResultOutput output) {
                output.resultOrigin(resolved);
                output.resultSymbol(kind, name);
                if (source != null) output.resultSource(source);
            }
            writeColored(HEADING_KIND, kind.optionName());
            delegate.write(' ');
            writeColored(HEADING, reference(name, resolved));
            delegate.write('\n');
        }

        private ResultOrigin resultOrigin(String location, String source, Integer line) {
            return resultOrigin(location, source, line, location);
        }

        private ResultOrigin resultOrigin(String location, String source, Integer line, String classLocation) {
            var resolved = origins.resolve(classLocation, location, source, line);
            if (delegate instanceof ResultOutput output) output.resultOrigin(resolved);
            return resolved;
        }

        private static String qualify(String name, ResultOrigin origin) {
            if (origin == null || origin.module() == null) return name;
            var module = origin.version() == null ? origin.module() : origin.module() + "@" + origin.version();
            return name.equals(origin.module()) ? module : module + "/" + name;
        }

        private String reference(String name, ResultOrigin origin) {
            var qualified = qualify(name, origin);
            if (origin == null) return qualified;
            var location = showOrigin ? origin.location() : archiveName(origin.classLocation());
            return location == null ? qualified : qualified + "(" + location + ")";
        }

        private String archiveName(String location) {
            if (location == null || !location.startsWith("jar:")) return null;
            int end = location.indexOf("!/");
            if (end < 0) return null;
            var archive = location.substring(4, end);
            if (!archive.equals(currentArchive)) {
                currentArchiveName = Path.of(URI.create(archive)).getFileName().toString();
                currentArchive = archive;
            }
            return currentArchiveName;
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

    private static String fileName(String location) {
        return location.substring(Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\')) + 1);
    }

    record SourceHighlight(int line, int startColumn, int endColumn) {}
}
