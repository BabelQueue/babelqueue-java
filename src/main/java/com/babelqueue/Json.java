package com.babelqueue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON reader/writer — just enough for the BabelQueue
 * wire envelope. It keeps the Java core free of Jackson/Gson so it never forces a
 * JSON library on consumers.
 *
 * <p>Parsing yields {@link LinkedHashMap} (objects, insertion order preserved),
 * {@link ArrayList} (arrays), {@link String}, {@link Boolean}, {@code null}, and
 * {@link Long}/{@link BigInteger}/{@link Double} (numbers). Writing emits compact
 * JSON with slashes and non-ASCII left unescaped, matching the other SDK cores.
 */
final class Json {

    private Json() {}

    // ---- Writer ---------------------------------------------------------------

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Map<?, ?> m) {
            writeObject(sb, m);
        } else if (v instanceof Iterable<?> it) {
            writeArray(sb, it);
        } else if (v instanceof Number n) {
            writeNumber(sb, n);
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> it) {
        sb.append('[');
        boolean first = true;
        for (Object o : it) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new BabelQueueException("Cannot encode non-finite number: " + n);
            }
            sb.append(Double.toString(d));
        } else {
            sb.append(n.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        // Slashes, non-ASCII (UTF-8), <, >, & are all left literal.
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- Parser ---------------------------------------------------------------

    static Object parse(String raw) {
        Parser p = new Parser(raw);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new BabelQueueException("Trailing content after JSON value at index " + p.index());
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        int index() {
            return i;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(i);
        }

        private char next() {
            if (i >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(i++);
        }

        private BabelQueueException error(String message) {
            return new BabelQueueException("JSON parse error at index " + i + ": " + message);
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            next(); // consume '{'
            skipWhitespace();
            if (peek() == '}') {
                next();
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected string key");
                }
                String key = parseString();
                skipWhitespace();
                if (next() != ':') {
                    throw error("expected ':'");
                }
                map.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            next(); // consume '['
            skipWhitespace();
            if (peek() == ']') {
                next();
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            next(); // consume opening '"'
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw error("invalid \\u escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw error("invalid escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Boolean parseBoolean() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw error("invalid literal");
        }

        private Object parseNull() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw error("invalid literal");
        }

        private Number parseNumber() {
            int start = i;
            if (peek() == '-') {
                next();
            }
            while (!atEnd() && Character.isDigit(peek())) {
                next();
            }
            boolean isDouble = false;
            if (!atEnd() && peek() == '.') {
                isDouble = true;
                next();
                while (!atEnd() && Character.isDigit(peek())) {
                    next();
                }
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                isDouble = true;
                next();
                if (!atEnd() && (peek() == '+' || peek() == '-')) {
                    next();
                }
                while (!atEnd() && Character.isDigit(peek())) {
                    next();
                }
            }
            String token = s.substring(start, i);
            if (isDouble) {
                return Double.valueOf(token);
            }
            try {
                return Long.valueOf(token);
            } catch (NumberFormatException ex) {
                return new BigInteger(token);
            }
        }
    }
}
