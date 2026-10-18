package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The case-id rule of the conformance README ("Sections, scopes and exceptions"). */
class ConformanceResultsTest {

    @Test
    void topLevelUnnamedCaseIsSectionHashIndex() {
        assertEquals(List.of("a", "sec#1"),
            ConformanceResults.caseIds("sec", List.of(Map.of("name", "a"), Map.of("x", 1))));
        assertEquals(List.of("sec#0", "named"),
            ConformanceResults.caseIds("sec", Map.of("cases", List.of(Map.of("x", 1), Map.of("name", "named")))));
    }

    @Test
    void subKeyUnnamedCaseIsSubKeySlashIndex() {
        Map<String, Object> body = Map.of(
            "description", "ignored",
            "schema_version_gate", Map.of("cases", List.of(Map.of("x", 1), Map.of("name", "n"), Map.of("y", 2))));

        assertEquals(List.of("schema_version_gate/0", "schema_version_gate/n", "schema_version_gate/2"),
            ConformanceResults.caseIds("sqs", body));
    }

    @Test
    void subKeyWithoutCasesIsASingleCase() {
        assertEquals(List.of("dedup_key"),
            ConformanceResults.caseIds("idempotency", Map.of("dedup_key", Map.of("key_field", "meta.id"))));
    }
}
