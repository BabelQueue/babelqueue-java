package com.babelqueue.schema;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates a message's {@code data} block against a per-URN JSON Schema (ADR-0024). A
 * hand-rolled subset of Draft-07 (zero dependencies, GR-7) whose verdicts match the Go, PHP,
 * Python and Node validators and babelqueue-registry's {@code compat} linter. Supported
 * keywords: {@code type}, {@code required}, {@code properties}, {@code additionalProperties},
 * {@code items}, {@code enum}, {@code const}, {@code minLength}, {@code minimum}; unknown
 * keywords are ignored. It works on the decoded {@code Map}/{@code List}/scalar structures
 * the codec produces (numbers are {@link Long}/{@link BigInteger}/{@link Double}).
 */
public final class PayloadValidator {

    private PayloadValidator() {
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
        if (schema.containsKey("const") && !Objects.equals(value, schema.get("const"))) {
            return violation(path, "wrong_const");
        }
        if (schema.get("enum") instanceof List<?> enumValues && !contains(enumValues, value)) {
            return violation(path, "not_in_enum");
        }

        String type = (schema.get("type") instanceof String s) ? s : "";
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
        if (schema.get("minLength") instanceof Number min && str.length() < min.intValue()) {
            return violation(path, "below_min_length");
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
    // Double still counts as an integer, matching the other SDKs. Booleans are not Numbers.
    private static boolean isInteger(Object value) {
        if (value instanceof Long || value instanceof Integer || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof Double d) {
            return !d.isInfinite() && Double.compare(d, Math.floor(d)) == 0;
        }
        if (value instanceof Float f) {
            return !f.isInfinite() && Float.compare(f, (float) Math.floor(f)) == 0;
        }
        return false;
    }

    private static boolean contains(List<?> values, Object value) {
        for (Object item : values) {
            if (Objects.equals(value, item)) {
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
