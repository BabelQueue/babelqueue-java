package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.babelqueue.schema.MapProvider;
import com.babelqueue.schema.PayloadValidator;
import com.babelqueue.schema.RegisteredSchemaWarning;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared cross-SDK payload-schema cases (ADR-0024) from the vendored conformance
 * suite: this validator must agree with the Go, PHP, Python and Node ones on each case's
 * {@code valid} flag. The {@code payload_schema_keywords} section (union types, numeric
 * equality, unsupported-keyword warnings) also compares the registration warnings as a set of
 * {@code (pointer, keyword)} pairs.
 */
class PayloadSchemaConformanceTest {

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> payloadCasesMatchAcrossSdks() {
        Map<String, Object> manifest =
            (Map<String, Object>) Json.parse(readResource("/conformance/manifest.json"));
        Object sectionObj = manifest.get("payload_schema");
        assertNotNull(sectionObj, "manifest has a payload_schema section");

        Map<String, Object> section = (Map<String, Object>) sectionObj;
        Map<String, Object> schema = (Map<String, Object>) section.get("schema");
        List<Object> cases = (List<Object>) section.get("cases");
        assertFalse(cases.isEmpty());

        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> testCase = (Map<String, Object>) cases.get(i);
            Object name = testCase.get("name");
            String id = name instanceof String s ? s : "payload_schema#" + i;
            tests.add(dynamicTest(id, ConformanceResults.record("payload_schema", id, () -> {
                Map<String, Object> data = (Map<String, Object>) testCase.get("data");
                boolean expected = Boolean.TRUE.equals(testCase.get("valid"));
                boolean isValid = PayloadValidator.validate(schema, data) == null;
                assertEquals(expected, isValid, "case " + id);
            })));
        }
        return tests.stream();
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> keywordCasesMatchAcrossSdks() {
        Map<String, Object> manifest =
            (Map<String, Object>) Json.parse(readResource("/conformance/manifest.json"));
        Object sectionObj = manifest.get("payload_schema_keywords");
        assertNotNull(sectionObj, "manifest has a payload_schema_keywords section");

        List<Object> cases = (List<Object>) ((Map<String, Object>) sectionObj).get("cases");
        assertFalse(cases.isEmpty());

        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> testCase = (Map<String, Object>) cases.get(i);
            Object name = testCase.get("name");
            String id = name instanceof String s ? s : "payload_schema_keywords#" + i;
            tests.add(dynamicTest(id, ConformanceResults.record("payload_schema_keywords", id, () -> {
                Map<String, Object> schema = (Map<String, Object>) testCase.get("schema");
                Map<String, Object> data = (Map<String, Object>) testCase.get("data");
                boolean expected = Boolean.TRUE.equals(testCase.get("valid"));

                // Registration produces the warnings; the verdict is unaffected by them.
                List<RegisteredSchemaWarning> produced = new ArrayList<>();
                new MapProvider(Map.of("urn:test:case", schema), produced::add);
                boolean isValid = PayloadValidator.validate(schema, data) == null;
                assertEquals(expected, isValid, "case " + id);

                Set<String> expectedWarnings = new HashSet<>();
                Object listed = testCase.get("warnings");
                if (listed instanceof List<?> items) {
                    for (Object item : items) {
                        Map<String, Object> w = (Map<String, Object>) item;
                        expectedWarnings.add(w.get("pointer") + "\u0000" + w.get("keyword"));
                    }
                }
                Set<String> actualWarnings = new HashSet<>();
                for (RegisteredSchemaWarning w : produced) {
                    actualWarnings.add(w.warning().pointer() + "\u0000" + w.warning().keyword());
                }
                assertEquals(expectedWarnings, actualWarnings, "warnings of " + id);
                assertEquals(actualWarnings.size(), produced.size(), "one warning per occurrence in " + id);
            })));
        }
        return tests.stream();
    }

    private static String readResource(String path) {
        try (InputStream in = PayloadSchemaConformanceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
