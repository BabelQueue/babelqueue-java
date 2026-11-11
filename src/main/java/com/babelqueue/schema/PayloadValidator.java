package com.babelqueue.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a message's {@code data} block against a per-URN JSON Schema (ADR-0024). A
 * hand-rolled subset of Draft-07 (zero dependencies, GR-7) whose verdicts match the Go, PHP,
 * Python and Node validators and babelqueue-registry's {@code compat} linter. Supported
 * keywords: {@code type} (a name or an array of names, i.e. a union), {@code required},
 * {@code properties}, {@code additionalProperties}, {@code items}, {@code enum}, {@code const},
 * {@code minLength}, {@code maxLength}, {@code minimum}. A keyword outside the subset never
 * changes the verdict (it is evaluated as absent), but {@link #warnings(Map)} reports each
 * occurrence so registration can warn about it. {@code const}/{@code enum} compare numbers by
 * mathematical value ({@code 1 == 1.0 == 1e0}), recursively, and never equate a boolean or a
 * string with a number. It works on the decoded {@code Map}/{@code List}/scalar structures
 * the codec produces (numbers are {@link Long}/{@link BigInteger}/{@link Double}) and
 * tolerates any {@link Number} subtype ({@link Integer}, {@link BigDecimal}, ...).
 */
public final class PayloadValidator {

    // Annotation keywords never warn; so does every keyword starting with "x-" (e.g. the
    // ADR-0030 x-gdpr-sensitive).
    private static final Set<String> ANNOTATIONS =
        Set.of("$schema", "$id", "title", "description", "examples", "default", "$comment");

    private static final Set<String> SUPPORTED = Set.of(
        "type", "required", "properties", "additionalProperties", "items", "enum", "const",
        "minLength", "maxLength", "minimum");

    private PayloadValidator() {
    }

    /**
     * Every keyword in {@code schema} that is outside the supported subset, one
     * {@link SchemaWarning} per occurrence. The walk descends only into the values of
     * {@code properties}, {@code items} and {@code additionalProperties} when each is an
     * object, and never into the value of an unsupported keyword. Annotation keywords
     * ({@code $schema}, {@code $id}, {@code title}, {@code description}, {@code examples},
     * {@code default}, {@code $comment}, {@code x-*}) are not unsupported. The verdict of
     * {@link #validate} is unaffected.
     *
     * @param schema the decoded JSON Schema for the data block
     * @return the warnings in document order, empty when every keyword is supported
     */
    public static List<SchemaWarning> warnings(Map<String, Object> schema) {
        List<SchemaWarning> out = new ArrayList<>();
        collectWarnings(schema, "", out);
        return out;
    }

    private static void collectWarnings(Map<?, ?> schema, String pointer, List<SchemaWarning> out) {
        for (Object key : schema.keySet()) {
            String keyword = String.valueOf(key);
            if (!SUPPORTED.contains(keyword) && !ANNOTATIONS.contains(keyword) && !keyword.startsWith("x-")) {
                out.add(new SchemaWarning(pointer, keyword));
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> child) {
                    collectWarnings(child, pointer + "/properties/" + escapePointer(String.valueOf(entry.getKey())), out);
                }
            }
        }
        if (schema.get("items") instanceof Map<?, ?> items) {
            collectWarnings(items, pointer + "/items", out);
        }
        if (schema.get("additionalProperties") instanceof Map<?, ?> additional) {
            collectWarnings(additional, pointer + "/additionalProperties", out);
        }
    }

    // RFC 6901: "~" -> "~0" first, then "/" -> "~1".
    private static String escapePointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    /**
     * The first violation of {@code value} against {@code schema} as
     * {@code "<json-pointer>: <reason>"}, or {@code null} when it conforms.
     *
     * @param schema the decoded JSON Schema for the data block
     * @param value  the decoded value to validate
     * @return the first violation, or null when valid
     */
    public static String validate(Map<String, Object> schema, Object value) {
        return validateNode(schema, value, "");
    }

    private static String validateNode(Map<?, ?> schema, Object value, String path) {
        if (schema.containsKey("const") && !jsonEquals(value, schema.get("const"))) {
            return violation(path, "wrong_const");
        }
        if (schema.get("enum") instanceof List<?> enumValues && !contains(enumValues, value)) {
            return violation(path, "not_in_enum");
        }

        Object type = schema.get("type");
        if (type instanceof List<?> union) {
            return checkUnion(schema, union, value, path);
        }
        return checkType(schema, (type instanceof String s) ? s : "", value, path);
    }

    // A union is valid when the value matches ANY listed type; the keywords of the matching
    // type(s) then apply to it (an integer/number pair runs the same minimum check twice at
    // worst). An empty union and an unknown type name constrain nothing, like an unknown
    // single `type`.
    private static String checkUnion(Map<?, ?> schema, List<?> union, Object value, String path) {
        boolean sawType = false;
        String shapeViolation = null;
        for (Object member : union) {
            if (!(member instanceof String name)) {
                continue;
            }
            sawType = true;
            String found = checkType(schema, name, value, path);
            if (found == null) {
                return null;
            }
            // A type that matches the value's shape but fails one of its keywords (minLength,
            // minimum, nested properties...) explains the failure better than a bare mismatch.
            if (shapeViolation == null && matchesType(name, value)) {
                shapeViolation = found;
            }
        }
        if (!sawType) {
            return null;
        }
        return shapeViolation != null ? shapeViolation : violation(path, "not_in_type_union");
    }

    private static boolean matchesType(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "integer" -> isInteger(value);
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static String checkType(Map<?, ?> schema, String type, Object value, String path) {
        return switch (type) {
            case "object" -> checkObject(schema, value, path);
            case "array" -> checkArray(schema, value, path);
            case "string" -> checkString(schema, value, path);
            case "integer" -> isInteger(value)
                ? checkMinimum(schema, value, path)
                : violation(path, "not_an_integer");
            case "number" -> (value instanceof Number)
                ? checkMinimum(schema, value, path)
                : violation(path, "not_a_number");
            case "boolean" -> (value instanceof Boolean) ? null : violation(path, "not_a_boolean");
            case "null" -> (value == null) ? null : violation(path, "not_null");
            default -> null;
        };
    }

    private static String checkObject(Map<?, ?> schema, Object value, String path) {
        if (!(value instanceof Map<?, ?> obj)) {
            return violation(path, "not_an_object");
        }
        if (schema.get("required") instanceof List<?> required) {
            for (Object key : required) {
                if (key instanceof String k && !obj.containsKey(k)) {
                    return violation(join(path, k), "missing_required");
                }
            }
        }
        Map<?, ?> properties = (schema.get("properties") instanceof Map<?, ?> p) ? p : Map.of();
        boolean additionalAllowed = !Boolean.FALSE.equals(schema.get("additionalProperties"));

        for (Map.Entry<?, ?> entry : obj.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (properties.get(name) instanceof Map<?, ?> propSchema) {
                String found = validateNode(propSchema, entry.getValue(), join(path, name));
                if (found != null) {
                    return found;
                }
            } else if (!additionalAllowed) {
                return violation(join(path, name), "additional_not_allowed");
            }
        }
        return null;
    }

    private static String checkArray(Map<?, ?> schema, Object value, String path) {
        if (!(value instanceof List<?> list)) {
            return violation(path, "not_an_array");
        }
        if (!(schema.get("items") instanceof Map<?, ?> items)) {
            return null;
        }
        for (int i = 0; i < list.size(); i++) {
            String found = validateNode(items, list.get(i), path + "[" + i + "]");
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String checkString(Map<?, ?> schema, Object value, String path) {
        if (!(value instanceof String str)) {
            return violation(path, "not_a_string");
        }
        // JSON Schema counts string length in Unicode code points, not UTF-16 units.
        if (schema.get("minLength") instanceof Number min
            && str.codePointCount(0, str.length()) < min.intValue()) {
            return violation(path, "below_min_length");
        }
        if (schema.get("maxLength") instanceof Number max
            && str.codePointCount(0, str.length()) > max.doubleValue()) {
            return violation(path, "above_max_length");
        }
        return null;
    }

    private static String checkMinimum(Map<?, ?> schema, Object value, String path) {
        if (schema.get("minimum") instanceof Number min
            && value instanceof Number num
            && num.doubleValue() < min.doubleValue()) {
            return violation(path, "below_minimum");
        }
        return null;
    }

    // JSON numbers decode to Long/BigInteger (integers) or Double (fractions); a whole-valued
    // number of any representation (1.0, 1e2, BigDecimal("1.00")) still counts as an integer,
    // matching the other SDKs. Booleans are not Numbers.
    private static boolean isInteger(Object value) {
        if (value instanceof Long || value instanceof Integer || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof Number n) {
            BigDecimal d = toBigDecimal(n);
            return d != null && (d.signum() == 0 || d.stripTrailingZeros().scale() <= 0);
        }
        return false;
    }

    // The exact decimal value of a Number, or null for NaN/Infinity. A Double/Float goes
    // through its shortest decimal string, i.e. the value the JSON literal denoted.
    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal bd) {
            return bd;
        }
        if (n instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return BigDecimal.valueOf(n.longValue());
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return new BigDecimal(n instanceof Float ? Float.toString(n.floatValue()) : Double.toString(d));
        }
        try {
            return new BigDecimal(n.toString());
        } catch (NumberFormatException e) {
            double d = n.doubleValue();
            return (Double.isNaN(d) || Double.isInfinite(d)) ? null : new BigDecimal(Double.toString(d));
        }
    }

    // JSON-Schema equality for const/enum: numbers by mathematical value (recursively inside
    // arrays and objects), objects by key set (order-free), arrays element by element, and
    // nothing crosses kinds (true != 1, "1" != 1, null equals only null).
    private static boolean jsonEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Number x && b instanceof Number y) {
            BigDecimal bx = toBigDecimal(x);
            BigDecimal by = toBigDecimal(y);
            if (bx == null || by == null) {
                return bx == null && by == null && Double.compare(x.doubleValue(), y.doubleValue()) == 0;
            }
            return bx.compareTo(by) == 0;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : ma.entrySet()) {
                if (!mb.containsKey(entry.getKey()) || !jsonEquals(entry.getValue(), mb.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!jsonEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Boolean || a instanceof String) {
            return a.equals(b);
        }
        return false;
    }

    private static boolean contains(List<?> values, Object value) {
        for (Object item : values) {
            if (jsonEquals(value, item)) {
                return true;
            }
        }
        return false;
    }

    private static String violation(String path, String reason) {
        return (path.isEmpty() ? "<root>" : path) + ": " + reason;
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
