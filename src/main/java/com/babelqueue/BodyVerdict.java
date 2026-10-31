package com.babelqueue;

import java.util.Map;

/**
 * Strict classification of a raw message body (ADR-0036 decision 4; ADR-0032 section 6; error-handling.md section 3):
 * the {@code identity_verdict} of the conformance suite.
 *
 * <p>{@link EnvelopeCodec#decode(String)} is deliberately lenient: it never throws and maps a wrongly typed known
 * member to its default, so it cannot tell a well-typed envelope from {@code {"job":"urn:x","attempts":"3"}}.
 * {@link #classify(String)} is the strict, additive counterpart, so a consumer can park a malformed body on the
 * poison route instead of handing a half-decoded envelope to a handler. {@code decode} itself is unchanged.
 *
 * <p>A body is <b>poison</b> when:
 * <ul>
 *   <li>it is not exactly one JSON value (no BOM, trailing tokens or malformed numbers) or not a JSON object (a bare
 *       {@code null} is empty, hence has no identity);</li>
 *   <li>a known member has the wrong type: {@code trace_id}, {@code meta.id|queue|lang} and
 *       {@code dead_letter.reason|error|exception|original_queue|lang} are string-or-null;
 *       {@code meta.schema_version|created_at}, {@code attempts} and {@code dead_letter.failed_at|attempts} are
 *       integer-or-null (an integer literal, no fraction or exponent, that fits int64); {@code data}, {@code meta}
 *       and {@code dead_letter} are object-or-null and numbers inside {@code data} are finite doubles. Member names are
 *       case-sensitive and every occurrence of a duplicated key is checked; any other member is only checked for
 *       syntax;</li>
 *   <li>it nests deeper than {@link #MAX_DEPTH} levels. The limit is a fixed, thread-independent constant: the
 *       recursive reader behind {@link EnvelopeCodec#decode(String)} carries that depth on any practical thread stack,
 *       so the verdict never depends on which thread classifies the body. The limit is specific to the Java core: Go and
 *       Node accept 10000 levels, PHP 511, {@code .NET} 64, and Python depends on the interpreter, so a body nested
 *       between 65 and 10000 levels can differ across SDKs (ADR-0036 decision 4, "SDK note", owner decision
 *       2026-10-31);</li>
 *   <li>it carries no valid identity: the LAST top-level {@code job} is used when it is a string that is not blank
 *       (only ASCII whitespace is blank); otherwise the LAST {@code urn} alias when it is a non-blank string. A
 *       {@code job} or {@code urn} of another JSON type is ignored.</li>
 * </ul>
 * A well-typed envelope that fails {@link EnvelopeCodec#accepts(Envelope)} (no {@code data}, an unsupported
 * {@code schema_version}) is <b>not</b> poison here.
 */
public final class BodyVerdict {

    /** The two outcomes of {@link #classify(String)}. */
    public enum Kind {
        /** The body decodes into an envelope that carries a valid identity ({@code job} / {@code urn}). */
        OK,
        /** The body cannot be decoded, or decodes without a valid identity. */
        POISON
    }

    /**
     * A classification result.
     *
     * @param kind  the verdict
     * @param cause why the body is poison, or {@code null} when it is {@link Kind#OK}
     */
    public record Result(Kind kind, String cause) {

        /** Whether the body decodes into an envelope with a valid identity. */
        public boolean isOk() {
            return kind == Kind.OK;
        }

        /** Whether the body is poison. */
        public boolean isPoison() {
            return kind == Kind.POISON;
        }
    }

    /**
     * The deepest JSON nesting {@link #classify(String)} accepts, counting the top-level object as level 1. Deeper
     * bodies are poison. The scan itself is iterative, so the verdict does not depend on the thread stack.
     */
    public static final int MAX_DEPTH = 256;

    private static final Result OK = new Result(Kind.OK, null);

    private BodyVerdict() {
    }

    /**
     * Classifies {@code body}, the raw message body (a JSON text). Never throws.
     *
     * @param body the raw body; {@code null} is poison
     * @return the verdict and, for a poison body, the cause
     */
    public static Result classify(String body) {
        if (body == null) {
            return poison("the body is null.");
        }
        Scanner scanner = new Scanner(body);
        String violation = scanner.violation();
        if (violation != null) {
            return poison(violation);
        }
        // ADR-0032 section 6: the last `job` / `urn` of the top-level object, a non-string one counting as absent.
        if (validIdentity(scanner.job) || validIdentity(scanner.urn)) {
            return OK;
        }
        return poison("no valid identity: neither 'job' nor 'urn' is a non-blank string.");
    }

    private static boolean validIdentity(String value) {
        return value != null && !EnvelopeCodec.isBlankAscii(value);
    }

    private static Result poison(String cause) {
        return new Result(Kind.POISON, cause);
    }

    /** The typed-shape scanner: a recursive-descent pass over the JSON text that checks the known members. */
    private static final class Scanner {

        private enum Type { STRING, INTEGER, OBJECT, DATA }

        private record Member(Type kind, Map<String, Type> members) {}

        private static final Map<String, Type> META = Map.of(
            "id", Type.STRING, "queue", Type.STRING, "lang", Type.STRING,
            "schema_version", Type.INTEGER, "created_at", Type.INTEGER);

        private static final Map<String, Type> DEAD_LETTER = Map.of(
            "reason", Type.STRING, "error", Type.STRING, "exception", Type.STRING,
            "failed_at", Type.INTEGER, "original_queue", Type.STRING, "attempts", Type.INTEGER, "lang", Type.STRING);

        private static final Map<String, Member> TOP = Map.of(
            "trace_id", new Member(Type.STRING, null),
            "attempts", new Member(Type.INTEGER, null),
            "data", new Member(Type.DATA, null),
            "meta", new Member(Type.OBJECT, META),
            "dead_letter", new Member(Type.OBJECT, DEAD_LETTER));

        private final String s;
        private int i;
        /** Containers currently open around the cursor (the typed levels; {@link #value} adds its own stack). */
        private int depth;
        /** The last top-level {@code job} / {@code urn}, or {@code null} when absent or not a string. */
        String job;
        String urn;

        private Scanner(String s) {
            this.s = s;
        }

        /** The reason the body is undecodable, or {@code null} when the typed shape is acceptable. */
        String violation() {
            try {
                parse();
                return null;
            } catch (Reject reject) {
                return reject.getMessage();
            }
        }

        private static final class Reject extends RuntimeException {
            Reject(String message) {
                super(message, null, false, false);
            }
        }

        private void parse() {
            skipWhitespace();
            if (i >= s.length()) {
                throw new Reject("empty body.");
            }
            char c = s.charAt(i);
            if (c == 'n') {
                literal("null");
            } else if (c == '{') {
                object(TOP, null, "");
            } else {
                value(false);
                throw new Reject("expected a JSON object.");
            }
            skipWhitespace();
            if (i < s.length()) {
                throw new Reject("trailing content after the JSON value at index " + i + ".");
            }
        }

        // -- typed object ------------------------------------------------------------------------

        /** Parse an object; {@code top} (top level) or {@code flat} (meta / dead_letter) says which members are typed. */
        private void object(Map<String, Member> top, Map<String, Type> flat, String path) {
            enter(0);
            depth++;
            i++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                i++;
                depth--;
                return;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new Reject("expected an object key at index " + i + ".");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (top != null) {
                    Member member = lookup(top, key);
                    if (member != null) {
                        typed(path + key, member.kind, member.members);
                    } else if (key.equals("job") || key.equals("urn")) {
                        // Identity members are not typed (ADR-0032 section 6): the last one wins, a non-string
                        // one counts as absent.
                        String identity = peek() == '"' ? string() : null;
                        if (identity == null) {
                            value(false);
                        }
                        if (key.equals("job")) {
                            job = identity;
                        } else {
                            urn = identity;
                        }
                    } else {
                        value(false);
                    }
                } else {
                    Type kind = lookup(flat, key);
                    if (kind == null) {
                        value(false);
                    } else {
                        typed(path + key, kind, null);
                    }
                }
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    depth--;
                    return;
                } else {
                    throw new Reject("expected ',' or '}' at index " + i + ".");
                }
            }
        }

        private void typed(String key, Type kind, Map<String, Type> members) {
            boolean isNull = peek() == 'n';
            switch (kind) {
                case STRING -> {
                    if (isNull) {
                        literal("null");
                    } else if (peek() == '"') {
                        string();
                    } else {
                        throw wrongType(key, "a string");
                    }
                }
                case INTEGER -> {
                    if (isNull) {
                        literal("null");
                    } else if (peek() == '-' || isDigit(peek())) {
                        integer(key);
                    } else {
                        throw wrongType(key, "an integer");
                    }
                }
                case OBJECT, DATA -> {
                    if (isNull) {
                        literal("null");
                    } else if (peek() == '{') {
                        if (kind == Type.DATA) {
                            value(true);
                        } else {
                            object(null, members, key + ".");
                        }
                    } else {
                        throw wrongType(key, "a JSON object");
                    }
                }
            }
        }

        private static Reject wrongType(String key, String expected) {
            return new Reject("'" + key + "' must be " + expected + ".");
        }

        /** Member names are matched exactly as spelled (case-sensitive), like the core decoder and the contract schema. */
        private static <T> T lookup(Map<String, T> members, String key) {
            return members.get(key);
        }

        // -- generic JSON ------------------------------------------------------------------------

        /**
         * Parses any well-formed value; with {@code finiteNumbers} every number must fit a finite double. Iterative on
         * purpose: the nesting depth is bounded by {@link BodyVerdict#MAX_DEPTH}, never by the thread stack.
         */
        private void value(boolean finiteNumbers) {
            StringBuilder open = new StringBuilder();
            boolean expectValue = true;
            while (true) {
                skipWhitespace();
                char c = peek();
                if (expectValue) {
                    if (c == '{' || c == '[') {
                        enter(open.length());
                        open.append(c);
                        i++;
                        skipWhitespace();
                        if (peek() == (c == '{' ? '}' : ']')) {
                            i++;
                            open.setLength(open.length() - 1);
                            expectValue = false;
                        } else if (c == '{') {
                            key();
                        }
                        continue;
                    }
                    scalar(finiteNumbers);
                    expectValue = false;
                }
                if (open.length() == 0) {
                    return;
                }
                char top = open.charAt(open.length() - 1);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    i++;
                    if (top == '{') {
                        key();
                    }
                    expectValue = true;
                } else if (next == (top == '{' ? '}' : ']')) {
                    i++;
                    open.setLength(open.length() - 1);
                    if (open.length() == 0) {
                        return;
                    }
                } else {
                    throw new Reject("expected ',' or the end of a container at index " + i + ".");
                }
            }
        }

        /** Reads {@code "key" :} inside a skipped object. */
        private void key() {
            skipWhitespace();
            if (peek() != '"') {
                throw new Reject("expected an object key at index " + i + ".");
            }
            string();
            skipWhitespace();
            expect(':');
        }

        private void scalar(boolean finiteNumbers) {
            char c = peek();
            switch (c) {
                case '"' -> string();
                case 't' -> literal("true");
                case 'f' -> literal("false");
                case 'n' -> literal("null");
                default -> {
                    if (c == '-' || isDigit(c)) {
                        String token = number();
                        if (finiteNumbers && !Double.isFinite(Double.parseDouble(token))) {
                            throw new Reject("'data' holds a number that is not a finite double: " + token + ".");
                        }
                    } else {
                        throw new Reject("unexpected character at index " + i + ".");
                    }
                }
            }
        }

        /** Counts one more open container (the typed levels plus {@code skipped} inside a skipped value). */
        private void enter(int skipped) {
            if (depth + skipped + 1 > MAX_DEPTH) {
                throw new Reject("nesting is deeper than " + MAX_DEPTH + " levels.");
            }
        }

        private void integer(String key) {
            String token = number();
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                throw wrongType(key, "an integer");
            }
            try {
                Long.parseLong(token);
            } catch (NumberFormatException overflow) {
                throw new Reject("'" + key + "' is out of the 64-bit integer range.");
            }
        }

        /** One strict JSON number token (RFC 8259 grammar). */
        private String number() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            if (peek() == '0') {
                i++;
            } else if (isDigit(peek())) {
                while (isDigit(peek())) {
                    i++;
                }
            } else {
                throw new Reject("invalid number at index " + start + ".");
            }
            if (peek() == '.') {
                i++;
                if (!isDigit(peek())) {
                    throw new Reject("invalid number at index " + start + ".");
                }
                while (isDigit(peek())) {
                    i++;
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                i++;
                if (peek() == '+' || peek() == '-') {
                    i++;
                }
                if (!isDigit(peek())) {
                    throw new Reject("invalid number at index " + start + ".");
                }
                while (isDigit(peek())) {
                    i++;
                }
            }
            return s.substring(start, i);
        }

        /** One JSON string; returns its decoded content (used for member-name matching). */
        private String string() {
            i++; // opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new Reject("unterminated string.");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw new Reject("control character in a string at index " + (i - 1) + ".");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw new Reject("unterminated string.");
                }
                char escape = s.charAt(i++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new Reject("invalid unicode escape.");
                        }
                        int code = 0;
                        for (int k = 0; k < 4; k++) {
                            int digit = hexDigit(s.charAt(i + k));
                            if (digit < 0) {
                                throw new Reject("invalid unicode escape.");
                            }
                            code = code * 16 + digit;
                        }
                        i += 4;
                        out.append((char) code);
                    }
                    default -> throw new Reject("invalid escape at index " + (i - 1) + ".");
                }
            }
        }

        // -- lexing helpers ----------------------------------------------------------------------

        private void literal(String word) {
            if (!s.startsWith(word, i)) {
                throw new Reject("invalid literal at index " + i + ".");
            }
            i += word.length();
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new Reject("expected '" + c + "' at index " + i + ".");
            }
            i++;
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        /** The value of an ASCII hex digit {@code [0-9A-Fa-f]}, or -1; never a Unicode digit (RFC 8259). */
        private static int hexDigit(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    return;
                }
            }
        }
    }
}
