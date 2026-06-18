package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.babelqueue.idempotency.Handler;
import com.babelqueue.schema.InvalidPayloadException;
import com.babelqueue.schema.MapProvider;
import com.babelqueue.schema.PayloadValidator;
import com.babelqueue.schema.SchemaProvider;
import com.babelqueue.schema.SchemaValidation;
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
}
