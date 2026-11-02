package com.vobiz.masking.util;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer with no external dependencies, so the whole web layer
 * stays unit-testable without Spring/Jackson on the classpath.
 */
public final class Json {

    /** Nesting cap — a deep body used to overflow the parser's stack (a StackOverflowError is an
     *  Error, so the dispatcher's catch(Exception) never saw it) and take the request thread down. */
    static final int MAX_DEPTH = 32;

    private Json() {
    }

    /** Implemented by DTOs/models so {@link #write(Object)} can serialise them. */
    public interface Jsonable {
        Map<String, Object> toMap();
    }

    // ---------------- writing ----------------

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Jsonable j) {
            writeValue(sb, j.toMap());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            sb.append('[');
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, Array.get(v, i));
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------- parsing ----------------

    public static Object parse(String text) {
        if (text == null || text.isBlank()) throw new ApiException(400, "Empty JSON body");
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue(0);
        p.skipWs();
        if (!p.atEnd()) throw new ApiException(400, "Trailing characters in JSON at position " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new ApiException(400, "Expected a JSON object");
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object readValue(int depth) {
            skipWs();
            if (atEnd()) throw new ApiException(400, "Unexpected end of JSON");
            if (depth > MAX_DEPTH) throw new ApiException(400, "JSON nesting too deep (max " + MAX_DEPTH + ")");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> {
                    expect("true");
                    yield Boolean.TRUE;
                }
                case 'f' -> {
                    expect("false");
                    yield Boolean.FALSE;
                }
                case 'n' -> {
                    expect("null");
                    yield null;
                }
                default -> readNumber();
            };
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new ApiException(400, "Invalid JSON at position " + pos);
            pos += lit.length();
        }

        private Map<String, Object> readObject(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // consume '{'
            skipWs();
            if (!atEnd() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (atEnd() || s.charAt(pos) != '"')
                    throw new ApiException(400, "Expected string key at position " + pos);
                String key = readString();
                skipWs();
                if (atEnd() || s.charAt(pos) != ':')
                    throw new ApiException(400, "Expected ':' at position " + pos);
                pos++;
                m.put(key, readValue(depth + 1));
                skipWs();
                if (atEnd()) throw new ApiException(400, "Unterminated JSON object");
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return m;
                }
                throw new ApiException(400, "Expected ',' or '}' at position " + pos);
            }
        }

        private List<Object> readArray(int depth) {
            List<Object> list = new ArrayList<>();
            pos++; // consume '['
            skipWs();
            if (!atEnd() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue(depth + 1));
                skipWs();
                if (atEnd()) throw new ApiException(400, "Unterminated JSON array");
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new ApiException(400, "Expected ',' or ']' at position " + pos);
            }
        }

        private String readString() {
            pos++; // consume '"'
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new ApiException(400, "Unterminated JSON string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw new ApiException(400, "Unterminated escape");
                    char e = s.charAt(pos++);
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
                            if (pos + 4 > s.length()) throw new ApiException(400, "Bad \\u escape");
                            String hex = s.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                // was an uncaught NumberFormatException -> HTTP 500 instead of 400
                                throw new ApiException(400, "Bad \\u escape '" + hex + "'");
                            }
                            pos += 4;
                        }
                        default -> throw new ApiException(400, "Bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Number readNumber() {
            int start = pos;
            if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean floating = false;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    floating = true;
                    pos++;
                } else if (c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) throw new ApiException(400, "Invalid JSON value at position " + pos);
            String num = s.substring(start, pos);
            // Do NOT fold these into one ternary: `cond ? double : long` is subject to binary
            // numeric promotion, so the whole expression has type double and every integer in a
            // body came back as a Double ("1" -> 1.0), making (Long) casts on parsed bodies throw.
            try {
                if (floating) return Double.parseDouble(num);
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new ApiException(400, "Invalid number '" + num + "'");
            }
        }
    }
}
