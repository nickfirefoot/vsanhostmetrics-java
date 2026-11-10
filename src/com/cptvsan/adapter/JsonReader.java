package com.cptvsan.adapter;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small pull parser for JSON (RFC 8259), so large responses are read as a
 * stream of tokens instead of a tree: the caller walks the array of perf
 * entities one at a time and keeps only what it needs. {@link #readValue()}
 * builds a tree ({@code Map}, {@code List}, {@code String}, {@code Double},
 * {@code Boolean}, {@code null}) for a small subtree.
 *
 * <p>Malformed input throws {@link Unreadable}. Nesting is capped at
 * {@link #MAX_DEPTH}. Not thread safe.
 */
final class JsonReader {

    enum Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER,
        BOOLEAN, NULL, END }

    static final int MAX_DEPTH = 64;

    private static final int CTX_ARRAY_FIRST = 1;
    private static final int CTX_ARRAY = 2;
    private static final int CTX_OBJECT_FIRST = 3;
    private static final int CTX_OBJECT_NAME = 4;
    private static final int CTX_OBJECT_VALUE = 5;
    private static final int CTX_OBJECT = 6;
    private static final int CTX_TOP = 7;
    private static final int CTX_DONE = 8;

    private final Reader in;
    private final char[] buf = new char[8192];
    private int pos;
    private int lim;
    private final int[] stack = new int[MAX_DEPTH + 1];
    private int depth;
    private Token peeked;
    private final StringBuilder sb = new StringBuilder();

    JsonReader(Reader in) {
        this.in = in;
        stack[0] = CTX_TOP;
    }

    static JsonReader of(String s) {
        return new JsonReader(new StringReader(s));
    }

    /** Parse a whole small document into a tree. */
    static Object parse(String s) throws IOException {
        JsonReader r = of(s);
        Object v = r.readValue();
        if (r.peek() != Token.END) {
            throw new Unreadable("JSON: trailing content after the value");
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Token level
    // ------------------------------------------------------------------

    Token peek() throws IOException {
        if (peeked != null) {
            return peeked;
        }
        int ctx = stack[depth];
        int c;
        switch (ctx) {
            case CTX_ARRAY_FIRST:
                c = next();
                if (c == ']') {
                    return peeked = Token.END_ARRAY;
                }
                if (c != -1) {
                    back();
                }
                stack[depth] = CTX_ARRAY;
                break;
            case CTX_ARRAY:
                c = next();
                if (c == ']') {
                    return peeked = Token.END_ARRAY;
                }
                if (c != ',') {
                    throw syntax("',' or ']' expected");
                }
                break;
            case CTX_OBJECT_FIRST:
            case CTX_OBJECT:
                c = next();
                if (c == '}') {
                    return peeked = Token.END_OBJECT;
                }
                if (ctx == CTX_OBJECT) {
                    if (c != ',') {
                        throw syntax("',' or '}' expected");
                    }
                    c = next();
                }
                if (c != '"') {
                    throw syntax("a member name expected");
                }
                stack[depth] = CTX_OBJECT_NAME;
                return peeked = Token.NAME;
            case CTX_OBJECT_VALUE:
                if (next() != ':') {
                    throw syntax("':' expected");
                }
                stack[depth] = CTX_OBJECT;
                break;
            case CTX_TOP:
                stack[depth] = CTX_DONE;
                break;
            case CTX_DONE:
                if (next() != -1) {
                    throw syntax("trailing content");
                }
                return peeked = Token.END;
            default:
                throw syntax("a member name is pending");
        }
        c = next();
        switch (c) {
            case '{':
                return peeked = Token.BEGIN_OBJECT;
            case '[':
                return peeked = Token.BEGIN_ARRAY;
            case '"':
                return peeked = Token.STRING;
            case 't':
            case 'f':
                back();
                return peeked = Token.BOOLEAN;
            case 'n':
                back();
                return peeked = Token.NULL;
            case -1:
                throw syntax("unexpected end of input");
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    back();
                    return peeked = Token.NUMBER;
                }
                throw syntax("unexpected character '" + (char) c + "'");
        }
    }

    void beginObject() throws IOException {
        expect(Token.BEGIN_OBJECT);
        push(CTX_OBJECT_FIRST);
    }

    void endObject() throws IOException {
        expect(Token.END_OBJECT);
        depth--;
    }

    void beginArray() throws IOException {
        expect(Token.BEGIN_ARRAY);
        push(CTX_ARRAY_FIRST);
    }

    void endArray() throws IOException {
        expect(Token.END_ARRAY);
        depth--;
    }

    /** More elements or members in the current array or object. */
    boolean hasNext() throws IOException {
        Token t = peek();
        return t != Token.END_OBJECT && t != Token.END_ARRAY && t != Token.END;
    }

    String nextName() throws IOException {
        expect(Token.NAME);
        stack[depth] = CTX_OBJECT_VALUE;
        return readString();
    }

    String nextString() throws IOException {
        expect(Token.STRING);
        return readString();
    }

    /** The number's text as written (callers parse what they need). */
    String nextNumber() throws IOException {
        expect(Token.NUMBER);
        sb.setLength(0);
        for (;;) {
            int c = next0();
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e'
                    || c == 'E') {
                sb.append((char) c);
            } else {
                if (c != -1) {
                    pos--;
                }
                break;
            }
        }
        String s = sb.toString();
        try {
            Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw syntax("malformed number");
        }
        return s;
    }

    boolean nextBoolean() throws IOException {
        expect(Token.BOOLEAN);
        if (literal("true")) {
            return true;
        }
        if (literal("false")) {
            return false;
        }
        throw syntax("malformed literal");
    }

    void nextNull() throws IOException {
        expect(Token.NULL);
        if (!literal("null")) {
            throw syntax("malformed literal");
        }
    }

    /** A string, number or boolean as text; {@code null} for null; a container is skipped. */
    String nextScalarOrSkip() throws IOException {
        switch (peek()) {
            case STRING:
                return nextString();
            case NUMBER:
                return nextNumber();
            case BOOLEAN:
                return String.valueOf(nextBoolean());
            case NULL:
                nextNull();
                return null;
            default:
                skipValue();
                return null;
        }
    }

    void skipValue() throws IOException {
        int level = 0;
        do {
            switch (peek()) {
                case BEGIN_OBJECT:
                    beginObject();
                    level++;
                    break;
                case BEGIN_ARRAY:
                    beginArray();
                    level++;
                    break;
                case END_OBJECT:
                    endObject();
                    level--;
                    break;
                case END_ARRAY:
                    endArray();
                    level--;
                    break;
                case NAME:
                    nextName();
                    break;
                case STRING:
                    nextString();
                    break;
                case NUMBER:
                    nextNumber();
                    break;
                case BOOLEAN:
                    nextBoolean();
                    break;
                case NULL:
                    nextNull();
                    break;
                default:
                    throw syntax("unexpected end of input");
            }
        } while (level > 0);
    }

    /** The next value as a tree. */
    Object readValue() throws IOException {
        switch (peek()) {
            case BEGIN_OBJECT: {
                Map<String, Object> m = new LinkedHashMap<>();
                beginObject();
                while (hasNext()) {
                    String k = nextName();
                    m.put(k, readValue());
                }
                endObject();
                return m;
            }
            case BEGIN_ARRAY: {
                List<Object> l = new ArrayList<>();
                beginArray();
                while (hasNext()) {
                    l.add(readValue());
                }
                endArray();
                return l;
            }
            case STRING:
                return nextString();
            case NUMBER:
                return Double.valueOf(nextNumber());
            case BOOLEAN:
                return nextBoolean();
            case NULL:
                nextNull();
                return null;
            default:
                throw syntax("a value expected");
        }
    }

    // ------------------------------------------------------------------
    // Tree helpers
    // ------------------------------------------------------------------

    /** {@code tree.a.b...}, or {@code null} when any step is missing or not an object. */
    static Object at(Object tree, String... path) {
        Object o = tree;
        for (String p : path) {
            if (!(o instanceof Map)) {
                return null;
            }
            o = ((Map<?, ?>) o).get(p);
        }
        return o;
    }

    /** {@link #at} as text: a string as is, a number or boolean as written; else {@code null}. */
    static String str(Object tree, String... path) {
        Object o = at(tree, path);
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof Double) {
            double d = (Double) o;
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d)
                    : String.valueOf(d);
        }
        if (o instanceof Boolean) {
            return o.toString();
        }
        return null;
    }

    /** {@link #at} as a list; an absent or non-list value is empty. */
    static List<?> list(Object tree, String... path) {
        Object o = at(tree, path);
        return o instanceof List ? (List<?>) o : java.util.Collections.emptyList();
    }

    /**
     * A vSphere JSON boxed value, {@code {"_typeName":"string","_value":x}},
     * as {@code x}; anything else unchanged.
     */
    static Object unbox(Object v) {
        if (v instanceof Map && ((Map<?, ?>) v).containsKey("_value")) {
            return ((Map<?, ?>) v).get("_value");
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Characters
    // ------------------------------------------------------------------

    private void expect(Token t) throws IOException {
        if (peek() != t) {
            throw syntax(t + " expected, found " + peeked);
        }
        peeked = null;
    }

    private void push(int ctx) throws IOException {
        if (depth + 1 > MAX_DEPTH) {
            throw syntax("nested deeper than " + MAX_DEPTH);
        }
        stack[++depth] = ctx;
    }

    private String readString() throws IOException {
        sb.setLength(0);
        for (;;) {
            int c = next0();
            if (c == '"') {
                return sb.toString();
            }
            if (c == -1) {
                throw syntax("unterminated string");
            }
            if (c == '\\') {
                c = next0();
                switch (c) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        int v = 0;
                        for (int i = 0; i < 4; i++) {
                            int h = Character.digit(next0(), 16);
                            if (h < 0) {
                                throw syntax("malformed \\u escape");
                            }
                            v = v * 16 + h;
                        }
                        sb.append((char) v);
                        break;
                    }
                    default:
                        throw syntax("malformed escape");
                }
            } else if (c < 0x20) {
                throw syntax("control character in string");
            } else {
                sb.append((char) c);
            }
        }
    }

    private boolean literal(String word) throws IOException {
        for (int i = 0; i < word.length(); i++) {
            int c = next0();
            if (c != word.charAt(i)) {
                if (i == 0 && c != -1) {
                    pos--;
                }
                return false;
            }
        }
        return true;
    }

    /** The next non-whitespace character, or -1. */
    private int next() throws IOException {
        for (;;) {
            int c = next0();
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return c;
            }
        }
    }

    private int next0() throws IOException {
        if (pos == lim) {
            lim = in.read(buf, 0, buf.length);
            pos = 0;
            if (lim <= 0) {
                lim = 0;
                return -1;
            }
        }
        return buf[pos++];
    }

    private void back() {
        pos--;
    }

    private Unreadable syntax(String what) {
        return new Unreadable("JSON: " + what);
    }
}
