package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PoisonRecordTest {

    private static final List<String> BLOCK_ORDER =
        List.of("reason", "error", "exception", "failed_at", "original_queue", "attempts", "lang");

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String json) {
        return (Map<String, Object>) assertInstanceOf(Map.class, Json.parse(json));
    }

    /** Rebuilds the block by hand with the failed_at taken from the produced record, then compares bytes. */
    private static void assertBytesEqualToManualBlock(byte[] body, String queue, int attempts, String error,
        String exception) {
        long before = System.currentTimeMillis();
        String actual = PoisonRecord.build(body, queue, attempts, error, exception);
        long after = System.currentTimeMillis();

        Map<String, Object> dl = deadLetterOf(actual);
        Object failedAt = dl.get("failed_at");
        long ts = assertInstanceOf(Number.class, failedAt).longValue();
        assertTrue(ts >= before && ts <= after, "failed_at is epoch ms taken during the call");

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("reason", "poison");
        block.put("error", error);
        block.put("exception", exception);
        block.put("failed_at", ts);
        block.put("original_queue", queue);
        block.put("attempts", attempts);
        block.put("lang", EnvelopeCodec.SOURCE_LANG);
        assertEquals(PoisonRecord.build(body, JsonValues.encode(block)), actual);
    }

    private static Map<String, Object> deadLetterOf(String record) {
        Object dl = parseObject(record).get("dead_letter");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) assertInstanceOf(Map.class, dl);
        return map;
    }

    @Test
    void overloadEqualsManuallyBuiltBlock() {
        assertBytesEqualToManualBlock(bytes("{\"a\":1}"), "orders", 3, "boom", "java.lang.IllegalStateException");
    }

    @Test
    void blockHasCanonicalOrderAndTypes() {
        String record = PoisonRecord.build(bytes("{\"a\":1}"), "orders", 2, "boom", "X");
        Map<String, Object> dl = deadLetterOf(record);
        assertEquals(BLOCK_ORDER, new ArrayList<>(dl.keySet()));
        assertEquals("poison", dl.get("reason"));
        assertEquals("boom", dl.get("error"));
        assertEquals("X", dl.get("exception"));
        assertEquals("orders", dl.get("original_queue"));
        assertEquals(2L, ((Number) dl.get("attempts")).longValue());
        assertEquals("java", dl.get("lang"));
        assertTrue(dl.get("failed_at") instanceof Number);
        assertTrue(((Number) dl.get("failed_at")).longValue() > 1_600_000_000_000L);
    }

    @Test
    void nullErrorAndExceptionAreWrittenAsNull() {
        String record = PoisonRecord.build(bytes("{\"a\":1}"), "q", 0, null, null);
        Map<String, Object> dl = deadLetterOf(record);
        assertEquals(BLOCK_ORDER, new ArrayList<>(dl.keySet()));
        assertTrue(dl.containsKey("error"));
        assertTrue(dl.containsKey("exception"));
        assertNull(dl.get("error"));
        assertNull(dl.get("exception"));
        assertTrue(record.contains("\"error\":null,\"exception\":null"));
        assertBytesEqualToManualBlock(bytes("{\"a\":1}"), "q", 0, null, null);
    }

    @Test
    void zeroAttempts() {
        String record = PoisonRecord.build(bytes("{}"), "q", 0, "e", "E");
        assertEquals(0L, ((Number) deadLetterOf(record).get("attempts")).longValue());
        assertBytesEqualToManualBlock(bytes("{}"), "q", 0, "e", "E");
    }

    @Test
    void invalidUtf8TakesBase64Branch() {
        byte[] body = {(byte) 0xFF, (byte) 0xFE, 0x41};
        String record = PoisonRecord.build(body, "q", 1, "bad", "E");
        Map<String, Object> parsed = parseObject(record);
        assertEquals(Base64.getEncoder().encodeToString(body), parsed.get("raw"));
        assertEquals("base64", parsed.get("raw_encoding"));
        assertEquals(BLOCK_ORDER, new ArrayList<>(deadLetterOf(record).keySet()));
        assertBytesEqualToManualBlock(body, "q", 1, "bad", "E");
    }

    @Test
    void spliceableObjectIsSplicedAtByteLevel() {
        String body = "{\"n\":1e400,\"big\":18446744073709551616,\"n\":2}";
        String record = PoisonRecord.build(bytes(body), "q", 4, "e", "E");
        assertTrue(record.startsWith(body.substring(0, body.length() - 1) + ",\"dead_letter\":{"), record);
        assertTrue(record.endsWith("}}"));
        assertBytesEqualToManualBlock(bytes(body), "q", 4, "e", "E");
    }

    @Test
    void nonObjectBodyIsWrappedAsRawText() {
        String record = PoisonRecord.build(bytes("not json"), "q", 1, "e", "E");
        Map<String, Object> parsed = parseObject(record);
        assertEquals("not json", parsed.get("raw"));
        assertFalse(parsed.containsKey("raw_encoding"));
        assertBytesEqualToManualBlock(bytes("not json"), "q", 1, "e", "E");
    }
}
