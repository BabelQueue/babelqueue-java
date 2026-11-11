package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelqueue.idempotency.Handler;
import com.babelqueue.schema.InvalidPayloadException;
import com.babelqueue.schema.MapProvider;
import com.babelqueue.schema.PayloadValidator;
import com.babelqueue.schema.RegisteredSchemaWarning;
import com.babelqueue.schema.SchemaProvider;
import com.babelqueue.schema.SchemaValidation;
import com.babelqueue.schema.SchemaWarning;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SchemaValidationTest {

    private static final String ORDERS =
        "{\"type\":\"object\",\"required\":[\"order_id\"],"
        + "\"properties\":{\"order_id\":{\"type\":\"integer\"}},\"additionalProperties\":false}";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String raw) {
        return (Map<String, Object>) Json.parse(raw);
    }

    private static SchemaProvider provider() {
        return new MapProvider(Map.of("urn:babel:orders:created", json(ORDERS)));
    }

    @Test
    void validatorEnforcesObjectRequiredTypesAndAdditional() {
        Map<String, Object> s = json(ORDERS);
        assertNull(PayloadValidator.validate(s, json("{\"order_id\":7}")));
        assertNotNull(PayloadValidator.validate(s, json("{}")));
        assertNotNull(PayloadValidator.validate(s, json("{\"order_id\":\"x\"}")));
        assertNotNull(PayloadValidator.validate(s, json("{\"order_id\":7,\"extra\":1}")));
    }

    @Test
    void validatorScalarParity() {
        assertNull(PayloadValidator.validate(json("{\"type\":\"boolean\"}"), Boolean.TRUE));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"boolean\"}"), "x"));
        assertNull(PayloadValidator.validate(json("{\"type\":\"null\"}"), null));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"null\"}"), 1L));
        assertNull(PayloadValidator.validate(json("{\"type\":\"number\",\"minimum\":0.5}"), 0.6));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"number\",\"minimum\":0.5}"), 0.4));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"number\"}"), "x"));
        assertNull(PayloadValidator.validate(json("{\"type\":\"integer\"}"), 1L));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"integer\"}"), 1.5));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"integer\"}"), Boolean.TRUE));
    }

    @Test
    void validatorConstStringEnumAndArray() {
        assertNull(PayloadValidator.validate(json("{\"const\":\"v1\"}"), "v1"));
        assertNotNull(PayloadValidator.validate(json("{\"const\":\"v1\"}"), "v2"));
        assertNull(PayloadValidator.validate(json("{\"type\":\"string\",\"minLength\":1}"), "a"));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"string\",\"minLength\":1}"), ""));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"string\"}"), 5L));
        assertNull(PayloadValidator.validate(json("{\"enum\":[\"a\",\"b\"]}"), "b"));
        assertNotNull(PayloadValidator.validate(json("{\"enum\":[\"a\",\"b\"]}"), "c"));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"object\"}"), "x"));
        Map<String, Object> arr = json("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        assertNull(PayloadValidator.validate(arr, List.of("a", "b")));
        assertNotNull(PayloadValidator.validate(arr, List.of("a", 1L)));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"array\"}"), "x"));
    }

    @Test
    void checkValidInvalidAndUnregistered() {
        SchemaProvider p = provider();
        assertNull(SchemaValidation.check(p, "urn:babel:orders:created", json("{\"order_id\":1}")));
        assertNull(SchemaValidation.check(p, "urn:babel:unknown", json("{\"x\":1}")));
        assertNotNull(SchemaValidation.check(p, "urn:babel:orders:created", json("{}")));
    }

    @Test
    void validateThrowsWithDetailsOnInvalid() {
        InvalidPayloadException ex = assertThrows(InvalidPayloadException.class, () ->
            SchemaValidation.validate(provider(), "urn:babel:orders:created", json("{\"order_id\":\"x\"}")));
        assertEquals("urn:babel:orders:created", ex.urn());
        assertNotNull(ex.violation());
    }

    @Test
    void wrapRunsOnValidThrowsOnInvalidRunsForUnregistered() throws Exception {
        SchemaProvider p = provider();
        AtomicInteger calls = new AtomicInteger();
        Handler handler = SchemaValidation.wrap(p, env -> calls.incrementAndGet());

        handler.handle(EnvelopeCodec.make("urn:babel:orders:created", json("{\"order_id\":1}")));
        assertEquals(1, calls.get());

        assertThrows(InvalidPayloadException.class, () ->
            handler.handle(EnvelopeCodec.make("urn:babel:orders:created", json("{}"))));
        assertEquals(1, calls.get());

        handler.handle(EnvelopeCodec.make("urn:babel:unknown", json("{\"anything\":true}")));
        assertEquals(2, calls.get());
    }

    @Test
    void unionTypeAcceptsAnyListedTypeAndRejectsTheRest() {
        Map<String, Object> s = json("{\"type\":[\"string\",\"null\"]}");
        assertNull(PayloadValidator.validate(s, "x"));
        assertNull(PayloadValidator.validate(s, null));
        assertNotNull(PayloadValidator.validate(s, 5L));
        assertNotNull(PayloadValidator.validate(s, Boolean.FALSE));
        assertEquals("<root>: not_in_type_union", PayloadValidator.validate(s, 5L));
    }

    @Test
    void unionIntegerAcceptsWholeNumbersInAnyNumberType() {
        Map<String, Object> s = json("{\"type\":[\"integer\",\"null\"]}");
        assertNull(PayloadValidator.validate(s, 1.0));
        assertNull(PayloadValidator.validate(s, 100.0));
        assertNull(PayloadValidator.validate(s, 1.0f));
        assertNull(PayloadValidator.validate(s, new BigDecimal("1.00")));
        assertNull(PayloadValidator.validate(s, new BigDecimal("1E+2")));
        assertNull(PayloadValidator.validate(s, BigInteger.TEN.pow(30)));
        assertNull(PayloadValidator.validate(s, Integer.valueOf(3)));
        assertNotNull(PayloadValidator.validate(s, 1.5));
        assertNotNull(PayloadValidator.validate(s, new BigDecimal("1.5")));
        assertNotNull(PayloadValidator.validate(s, "1"));
    }

    @Test
    void unionKeepsTheKeywordsOfTheMatchingTypeEnforced() {
        Map<String, Object> s = json("{\"type\":[\"integer\",\"null\"],\"minimum\":0}");
        assertNull(PayloadValidator.validate(s, 3L));
        assertNull(PayloadValidator.validate(s, null));
        assertEquals("<root>: below_minimum", PayloadValidator.validate(s, -1L));

        Map<String, Object> obj = json(
            "{\"type\":[\"object\",\"null\"],\"required\":[\"id\"],"
            + "\"properties\":{\"id\":{\"type\":\"integer\"}}}");
        assertNull(PayloadValidator.validate(obj, json("{\"id\":1}")));
        assertNull(PayloadValidator.validate(obj, null));
        assertEquals("id: missing_required", PayloadValidator.validate(obj, json("{}")));
        assertEquals("id: not_an_integer", PayloadValidator.validate(obj, json("{\"id\":\"x\"}")));

        Map<String, Object> str = json("{\"type\":[\"string\",\"null\"],\"minLength\":2,\"maxLength\":3}");
        assertEquals("<root>: below_min_length", PayloadValidator.validate(str, "a"));
        assertEquals("<root>: above_max_length", PayloadValidator.validate(str, "abcd"));
        assertNull(PayloadValidator.validate(str, "abc"));
    }

    @Test
    void maxLengthCountsCodePoints() {
        Map<String, Object> s = json("{\"type\":\"string\",\"maxLength\":3}");
        assertNull(PayloadValidator.validate(s, "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"));
        assertNotNull(PayloadValidator.validate(s, "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"));
        assertNotNull(PayloadValidator.validate(json("{\"type\":\"string\",\"maxLength\":1}"), "e\u0301"));
    }

    @Test
    void constAndEnumCompareNumbersByValueAcrossNumberTypes() {
        Map<String, Object> one = json("{\"const\":1}");
        assertNull(PayloadValidator.validate(one, 1L));
        assertNull(PayloadValidator.validate(one, 1.0));
        assertNull(PayloadValidator.validate(one, 1.0f));
        assertNull(PayloadValidator.validate(one, Integer.valueOf(1)));
        assertNull(PayloadValidator.validate(one, new BigDecimal("1.000")));
        assertNull(PayloadValidator.validate(one, BigInteger.ONE));
        assertNotNull(PayloadValidator.validate(one, 1.5));
        assertNotNull(PayloadValidator.validate(one, Boolean.TRUE));
        assertNotNull(PayloadValidator.validate(one, "1"));
        assertNotNull(PayloadValidator.validate(one, null));

        // The schema side may carry any number type too.
        Map<String, Object> schemaWithDecimal = Map.of("const", new BigDecimal("2.50"));
        assertNull(PayloadValidator.validate(schemaWithDecimal, 2.5));
        assertNull(PayloadValidator.validate(Map.of("const", 1L), 1.0));
        assertNull(PayloadValidator.validate(Map.of("enum", List.of(1L, 2.5)), new BigDecimal("2.50")));

        assertNotNull(PayloadValidator.validate(json("{\"const\":true}"), 1L));
        assertNotNull(PayloadValidator.validate(json("{\"const\":false}"), 0L));
        assertNull(PayloadValidator.validate(json("{\"const\":null}"), null));
        assertNotNull(PayloadValidator.validate(json("{\"const\":null}"), 0L));
        assertNull(PayloadValidator.validate(json("{\"enum\":[true,null,0]}"), 0.0));
        assertNotNull(PayloadValidator.validate(json("{\"enum\":[true,null,0]}"), 1L));
    }

    @Test
    void constAndEnumCompareNestedStructuresByValue() {
        Map<String, Object> c = json("{\"const\":{\"a\":1,\"b\":[2,{\"c\":3}]}}");
        assertNull(PayloadValidator.validate(c, json("{\"b\":[2.0,{\"c\":3e0}],\"a\":1.0}")));
        assertNotNull(PayloadValidator.validate(c, json("{\"a\":1,\"b\":[2,{\"c\":true}]}")));
        assertNotNull(PayloadValidator.validate(c, json("{\"a\":1,\"b\":[{\"c\":3},2]}")));
        assertNotNull(PayloadValidator.validate(c, json("{\"a\":1,\"b\":[2,{\"c\":3}],\"d\":1}")));
        Map<String, Object> e = json("{\"enum\":[{\"n\":1},{\"n\":2}]}");
        assertNull(PayloadValidator.validate(e, json("{\"n\":1.0}")));
        assertNotNull(PayloadValidator.validate(e, json("{\"n\":\"1\"}")));
    }

    @Test
    void warningsListEachUnsupportedKeywordWithItsPointer() {
        Map<String, Object> schema = json(
            "{\"type\":\"object\",\"minProperties\":2,"
            + "\"properties\":{\"a/b\":{\"type\":\"string\",\"pattern\":\"x\"},"
            + "\"m~n\":{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"multipleOf\":2}}},"
            + "\"additionalProperties\":{\"type\":\"string\",\"format\":\"x\"},"
            + "\"allOf\":[{\"type\":\"integer\",\"pattern\":\"x\"}]}");

        List<SchemaWarning> warnings = PayloadValidator.warnings(schema);

        assertEquals(5, warnings.size());
        assertTrue(warnings.contains(new SchemaWarning("", "minProperties")));
        assertTrue(warnings.contains(new SchemaWarning("", "allOf")));
        assertTrue(warnings.contains(new SchemaWarning("/properties/a~1b", "pattern")));
        assertTrue(warnings.contains(new SchemaWarning("/properties/m~0n/items", "multipleOf")));
        assertTrue(warnings.contains(new SchemaWarning("/additionalProperties", "format")));
        // The unsupported keyword never changes the verdict.
        assertNull(PayloadValidator.validate(json("{\"type\":\"object\",\"minProperties\":2}"), json("{\"a\":1}")));
    }

    @Test
    void annotationKeywordsAndPropertyNamesNeverWarn() {
        Map<String, Object> schema = json(
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"$id\":\"urn:x\",\"title\":\"T\","
            + "\"description\":\"d\",\"$comment\":\"c\",\"examples\":[{}],\"default\":{},\"x-owner\":\"billing\","
            + "\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"x-gdpr-sensitive\":true},"
            + "\"pattern\":{\"type\":\"string\"},\"x-gdpr-sensitive\":{\"type\":\"string\"}}}");
        assertTrue(PayloadValidator.warnings(schema).isEmpty());
    }

    @Test
    void mapProviderWarnsOnRegistrationThroughTheHookAndKeepsTheList() {
        Map<String, Object> schema = json("{\"type\":\"string\",\"pattern\":\"^x\"}");
        List<RegisteredSchemaWarning> seen = new ArrayList<>();

        MapProvider p = new MapProvider(Map.of("urn:babel:a", schema), seen::add);

        assertEquals(List.of(new RegisteredSchemaWarning("urn:babel:a", new SchemaWarning("", "pattern"))), seen);
        assertEquals(seen, p.warnings());
        assertTrue(seen.get(0).message().contains("urn:babel:a"));
        // Registration warns; validation ignores the keyword.
        assertEquals("<root>: not_a_string", SchemaValidation.check(p, "urn:babel:a", Map.of()));
    }

    @Test
    void sameKeywordInTwoSchemasIsAttributedToEachUrn() {
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        schemas.put("urn:babel:a", json("{\"type\":\"object\",\"properties\":{\"email\":{\"pattern\":\"x\"}}}"));
        schemas.put("urn:babel:b", json("{\"type\":\"object\",\"properties\":{\"email\":{\"pattern\":\"x\"}}}"));

        MapProvider p = new MapProvider(schemas, w -> { });

        assertEquals(List.of(
            new RegisteredSchemaWarning("urn:babel:a", new SchemaWarning("/properties/email", "pattern")),
            new RegisteredSchemaWarning("urn:babel:b", new SchemaWarning("/properties/email", "pattern"))),
            p.warnings());
    }

    @Test
    void mapProviderSkipsNullSchemaValues() {
        Map<String, Map<String, Object>> schemas = new java.util.HashMap<>();
        schemas.put("urn:babel:none", null);

        MapProvider p = new MapProvider(schemas, w -> { });

        assertTrue(p.warnings().isEmpty());
        assertNull(p.schemaFor("urn:babel:none"));
    }
}
