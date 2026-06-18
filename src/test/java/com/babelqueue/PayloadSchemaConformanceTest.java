package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.babelqueue.schema.PayloadValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Runs the shared cross-SDK payload-schema cases (ADR-0024) from the vendored conformance
 * suite: this validator must agree with the Go, PHP, Python and Node ones on each case's
 * {@code valid} flag.
 */
class PayloadSchemaConformanceTest {

    @Test
    @SuppressWarnings("unchecked")
    void payloadCasesMatchAcrossSdks() {
        Map<String, Object> manifest =
            (Map<String, Object>) Json.parse(readResource("/conformance/manifest.json"));
        Object sectionObj = manifest.get("payload_schema");
        assertNotNull(sectionObj, "manifest has a payload_schema section");

        Map<String, Object> section = (Map<String, Object>) sectionObj;
        Map<String, Object> schema = (Map<String, Object>) section.get("schema");
        List<Object> cases = (List<Object>) section.get("cases");
        assertFalse(cases.isEmpty());

        for (Object caseObj : cases) {
            Map<String, Object> testCase = (Map<String, Object>) caseObj;
            Map<String, Object> data = (Map<String, Object>) testCase.get("data");
            boolean expected = Boolean.TRUE.equals(testCase.get("valid"));
            boolean isValid = PayloadValidator.validate(schema, data) == null;
            assertEquals(expected, isValid, "case " + testCase.get("name"));
        }
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
