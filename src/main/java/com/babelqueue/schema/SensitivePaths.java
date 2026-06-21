package com.babelqueue.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Extracts the {@code x-gdpr-sensitive} marks (ADR-0030) from a decoded JSON Schema. It walks the
 * same raw schema {@code Map} the {@link PayloadValidator} validates against — the Java schema model
 * is the decoded {@code Map}/{@code List}/scalar tree, so there is nothing extra to parse — and
 * returns every marked property as a {@link SensitivePath}, in sorted path order.
 *
 * <p>{@code x-gdpr-sensitive} is a <b>validation-neutral</b> extension keyword: it never makes a
 * value valid or invalid (the validator ignores unknown keywords), so annotating a schema with it is
 * never a breaking change (GR-1). It is recognised in two shapes, matching the Go reference and the
 * babelqueue-registry parser:
 * <ul>
 *   <li>the boolean {@code true} &rarr; sensitive, empty category;</li>
 *   <li>a non-empty string &rarr; sensitive, that string as the category (e.g. {@code "email"}).</li>
 * </ul>
 * Any other shape — {@code false}, {@code ""}, a number — leaves the property unmarked.
 *
 * <p>The walk descends nested objects (dotted paths, e.g. {@code profile.full_name}), array item
 * schemas (the {@code "field[]"} segment, e.g. {@code addresses[].line}), and reports a mark on the
 * root schema itself as the empty path {@code ""}. The Java mirror of the Go
 * {@code schema.Schema.SensitivePaths}.
 */
public final class SensitivePaths {

    /** The extension keyword that marks a property as carrying personal/sensitive data. */
    public static final String KEYWORD = "x-gdpr-sensitive";

    private SensitivePaths() {
    }

    /**
     * Every property marked {@code x-gdpr-sensitive} in {@code schema}, in sorted path order.
     *
     * @param schema a decoded JSON Schema ({@code Map}); {@code null} yields an empty list
     * @return the sensitive paths, sorted by path
     */
    public static List<SensitivePath> of(Map<String, Object> schema) {
        List<SensitivePath> out = new ArrayList<>();
        collect(schema, "", out);
        out.sort(Comparator.comparing(SensitivePath::path));
        return out;
    }

    private static void collect(Object node, String path, List<SensitivePath> out) {
        if (!(node instanceof Map<?, ?> schema)) {
            return;
        }
        String category = sensitiveCategory(schema.get(KEYWORD));
        if (category != null) {
            out.add(new SensitivePath(path, category));
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                collect(entry.getValue(), join(path, String.valueOf(entry.getKey())), out);
            }
        }
        if (schema.get("items") instanceof Map<?, ?> items) {
            collect(items, path + "[]", out);
        }
    }

    /**
     * The category for an {@code x-gdpr-sensitive} value, or {@code null} when the value does not
     * mark the property sensitive. {@code Boolean.TRUE} &rarr; {@code ""} (sensitive, no category);
     * a non-empty {@code String} &rarr; that string; anything else &rarr; {@code null}.
     */
    private static String sensitiveCategory(Object marker) {
        if (Boolean.TRUE.equals(marker)) {
            return "";
        }
        if (marker instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
