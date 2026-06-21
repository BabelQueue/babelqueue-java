package com.babelqueue.gdpr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import com.babelqueue.JsonValues;
import com.babelqueue.schema.PayloadValidator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GdprTest {

    // A deterministic 32-byte key → AES-256-GCM.
    private static final byte[] KEY_256 = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static AesGcmCipher cipher() {
        return new AesGcmCipher(KEY_256);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String raw) {
        return (Map<String, Object>) JsonValues.decode(raw);
    }

    // ---- AES-GCM cipher --------------------------------------------------------

    @Test
    void aesGcmRoundTripsAllKeySizes() throws Exception {
        for (int size : new int[] {16, 24, 32}) {
            byte[] key = new byte[size];
            for (int i = 0; i < size; i++) {
                key[i] = (byte) i;
            }
            Cipher c = new AesGcmCipher(key);
            byte[] plaintext = "the quick brown fox \"jümps\"".getBytes(StandardCharsets.UTF_8);
            String ct = c.encrypt(plaintext);
            assertArrayEqualsLocal(plaintext, c.decrypt(ct));
        }
    }

    @Test
    void aesGcmUsesFreshIvSoCiphertextDiffersButDecryptsSame() throws Exception {
        Cipher c = cipher();
        byte[] plaintext = "same".getBytes(StandardCharsets.UTF_8);
        String a = c.encrypt(plaintext);
        String b = c.encrypt(plaintext);
        assertNotEquals(a, b, "a random IV should make each encryption distinct");
        assertArrayEqualsLocal(plaintext, c.decrypt(a));
        assertArrayEqualsLocal(plaintext, c.decrypt(b));
    }

    @Test
    void aesGcmRejectsBadKeySize() {
        assertThrows(InvalidKeySizeException.class, () -> new AesGcmCipher(new byte[20]));
        assertThrows(InvalidKeySizeException.class, () -> new AesGcmCipher(null));
    }

    @Test
    void aesGcmRejectsTamperedAndMalformedCiphertext() throws Exception {
        Cipher c = cipher();
        String ct = c.encrypt("secret".getBytes(StandardCharsets.UTF_8));

        // Wrong key → GCM auth fails.
        Cipher wrongKey = new AesGcmCipher("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> wrongKey.decrypt(ct));

        // Tampered last char (still valid Base64 length but a different byte) → auth fails.
        char[] chars = ct.toCharArray();
        chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';
        assertThrows(Exception.class, () -> c.decrypt(new String(chars)));

        // Not Base64 at all.
        assertThrows(MalformedCiphertextException.class, () -> c.decrypt("not base64 @@@@"));

        // Valid Base64 but too short to hold a 12-byte IV.
        java.util.Base64.Encoder enc = java.util.Base64.getEncoder();
        String tooShort = enc.encodeToString(new byte[] {1, 2, 3});
        assertThrows(MalformedCiphertextException.class, () -> c.decrypt(tooShort));
    }

    // ---- sensitive-path extraction --------------------------------------------

    @Test
    void sensitivePathsCoversRootNestedAndArrayAndCategory() {
        // Root mark (path "") + nested object + array item + a category string + a false/non-mark.
        Map<String, Object> schema = json("""
            {"type":"object","x-gdpr-sensitive":true,
             "properties":{
               "email":{"type":"string","x-gdpr-sensitive":"email"},
               "age":{"type":"integer","x-gdpr-sensitive":false},
               "profile":{"type":"object","properties":{
                  "full_name":{"type":"string","x-gdpr-sensitive":true}}},
               "addresses":{"type":"array","items":{"type":"object","properties":{
                  "line":{"type":"string","x-gdpr-sensitive":true}}}}}}""");

        List<String> paths = new ArrayList<>();
        com.babelqueue.schema.SensitivePaths.of(schema)
            .forEach(sp -> paths.add(sp.path() + "|" + sp.category()));

        // Sorted; "" (root) sorts first. age is NOT marked (false).
        assertEquals(List.of("|", "addresses[].line|", "email|email", "profile.full_name|"), paths);
    }

    @Test
    void sensitivePathsOfNullSchemaIsEmpty() {
        assertTrue(com.babelqueue.schema.SensitivePaths.of(null).isEmpty());
    }

    // ---- protect / unprotect round-trip ---------------------------------------

    @Test
    void roundTripRestoresNestedArrayAndScalarExactly() {
        Map<String, Object> schema = json("""
            {"type":"object","properties":{
               "email":{"type":"string","x-gdpr-sensitive":true},
               "age":{"type":"integer","x-gdpr-sensitive":true},
               "profile":{"type":"object","properties":{
                  "full_name":{"type":"string","x-gdpr-sensitive":true},
                  "nickname":{"type":"string"}}},
               "addresses":{"type":"array","items":{"type":"object","properties":{
                  "line":{"type":"string","x-gdpr-sensitive":true},
                  "zip":{"type":"string"}}}}}}""");

        Map<String, Object> data = mutableData("""
            {"email":"alice@example.com","age":42,
             "profile":{"full_name":"Alice Smith","nickname":"al"},
             "addresses":[{"line":"1 Main St","zip":"00001"},{"line":"2 Oak Ave","zip":"00002"}]}""");
        Map<String, Object> original = mutableData(JsonValues.encode(data));

        Cipher c = cipher();
        Gdpr.protect(data, schema, c);

        // Every marked leaf is now a (ciphertext) String; non-sensitive siblings are unchanged.
        assertInstanceOf(String.class, data.get("email"));
        assertInstanceOf(String.class, data.get("age"));
        assertInstanceOf(String.class, nested(data, "profile").get("full_name"));
        assertEquals("al", nested(data, "profile").get("nickname"));
        List<?> addrs = (List<?>) data.get("addresses");
        assertInstanceOf(String.class, ((Map<?, ?>) addrs.get(0)).get("line"));
        assertEquals("00001", ((Map<?, ?>) addrs.get(0)).get("zip"));
        // age (42) became a ciphertext, not the literal number.
        assertNotEquals(original.get("age"), data.get("age"));

        Gdpr.unprotect(data, schema, c);

        // Byte-for-byte: re-encoding the restored data equals re-encoding the original.
        assertEquals(JsonValues.encode(original), JsonValues.encode(data));
        // And the number type is restored (Long), not left as a String.
        assertInstanceOf(Long.class, data.get("age"));
    }

    @Test
    void protectedEnvelopeStillDecodesAndKeepsSchemaVersionAndTraceId() {
        Map<String, Object> schema = json("""
            {"type":"object","properties":{
               "email":{"type":"string","x-gdpr-sensitive":true}}}""");

        Map<String, Object> data = mutableData("{\"email\":\"bob@example.com\",\"order_id\":7}");
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", data, "orders", "trace-123");

        Gdpr.protect(env.data(), schema, cipher());
        String body = EnvelopeCodec.encode(env);

        // The plaintext email no longer appears on the wire.
        assertFalse(body.contains("bob@example.com"));

        Envelope decoded = EnvelopeCodec.decode(body);
        assertTrue(EnvelopeCodec.accepts(decoded));
        assertEquals(1, decoded.meta().schemaVersion());
        assertEquals("trace-123", decoded.traceId());
        assertEquals("urn:babel:orders:created", decoded.job());
        // The protected field is a JSON string (pure JSON, GR-3); the non-sensitive field is intact.
        assertInstanceOf(String.class, decoded.data().get("email"));
        assertEquals(7L, decoded.data().get("order_id"));

        // Validating the cleartext schema AFTER unprotect passes (the ciphertext string would have
        // failed an integer/format constraint had we validated while protected).
        Gdpr.unprotect(decoded.data(), schema, cipher());
        assertEquals("bob@example.com", decoded.data().get("email"));
    }

    @Test
    void wrongKeyOnUnprotectThrowsDecryptException() {
        Map<String, Object> schema = json("""
            {"type":"object","properties":{"ssn":{"type":"string","x-gdpr-sensitive":true}}}""");
        Map<String, Object> data = mutableData("{\"ssn\":\"123-45-6789\"}");

        Gdpr.protect(data, schema, cipher());

        Cipher wrong = new AesGcmCipher("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8));
        assertThrows(DecryptException.class, () -> Gdpr.unprotect(data, schema, wrong));
    }

    @Test
    void unprotectIsIdempotentForNonStringLeavesAndSkipsAbsentPaths() {
        Map<String, Object> schema = json("""
            {"type":"object","properties":{
               "email":{"type":"string","x-gdpr-sensitive":true},
               "missing":{"type":"string","x-gdpr-sensitive":true},
               "age":{"type":"integer","x-gdpr-sensitive":true}}}""");

        // age is already a (cleartext) number, email absent → never protected; unprotect must not
        // touch the non-string leaf and must skip the absent path.
        Map<String, Object> data = mutableData("{\"age\":30}");
        Gdpr.unprotect(data, schema, cipher());
        assertEquals(30L, data.get("age"));
        assertFalse(data.containsKey("email"));
        assertFalse(data.containsKey("missing"));
    }

    @Test
    void unprotectThrowsWhenLeafIsAStringThatIsNotCiphertext() {
        Map<String, Object> schema = json("""
            {"type":"object","properties":{"email":{"type":"string","x-gdpr-sensitive":true}}}""");
        // A plain string at a sensitive path that the cipher cannot open.
        Map<String, Object> data = mutableData("{\"email\":\"not-a-ciphertext\"}");
        assertThrows(DecryptException.class, () -> Gdpr.unprotect(data, schema, cipher()));
    }

    @Test
    void containerMarkEncryptsWholeSubValueAsOneString() {
        // A whole object marked sensitive → encoded and encrypted as one ciphertext string.
        Map<String, Object> schema = json("""
            {"type":"object","properties":{
               "profile":{"type":"object","x-gdpr-sensitive":true,"properties":{
                  "full_name":{"type":"string"}}}}}""");
        Map<String, Object> data = mutableData("{\"profile\":{\"full_name\":\"Alice\",\"vip\":true}}");
        Map<String, Object> original = mutableData(JsonValues.encode(data));

        Cipher c = cipher();
        Gdpr.protect(data, schema, c);
        assertInstanceOf(String.class, data.get("profile"));

        Gdpr.unprotect(data, schema, c);
        assertEquals(JsonValues.encode(original), JsonValues.encode(data));
    }

    @Test
    void typeMismatchesBetweenSchemaAndDataAreSkipped() {
        Cipher c = cipher();

        // Schema declares an array item path, but the message has a scalar there → skipped.
        Map<String, Object> arraySchema = json("""
            {"type":"object","properties":{
               "addresses":{"type":"array","items":{"type":"object","properties":{
                  "line":{"type":"string","x-gdpr-sensitive":true}}}}}}""");
        Map<String, Object> scalarWhereArrayExpected = mutableData("{\"addresses\":\"oops\"}");
        Gdpr.protect(scalarWhereArrayExpected, arraySchema, c);
        assertEquals("oops", scalarWhereArrayExpected.get("addresses"));

        // Schema declares a nested object path, but the message has a scalar there → skipped.
        Map<String, Object> nestedSchema = json("""
            {"type":"object","properties":{
               "profile":{"type":"object","properties":{
                  "full_name":{"type":"string","x-gdpr-sensitive":true}}}}}""");
        Map<String, Object> scalarWhereObjectExpected = mutableData("{\"profile\":\"oops\"}");
        Gdpr.protect(scalarWhereObjectExpected, nestedSchema, c);
        assertEquals("oops", scalarWhereObjectExpected.get("profile"));
    }

    @Test
    void directlySensitiveArrayItemsAreProtectedAndRestored() {
        // The array ITEM itself is sensitive ("tags[]") — exercises the array-leaf branch.
        Map<String, Object> schema = json("""
            {"type":"object","properties":{
               "tags":{"type":"array","items":{"type":"string","x-gdpr-sensitive":true}}}}""");
        Map<String, Object> data = mutableData("{\"tags\":[\"vip\",\"eu\"]}");
        Map<String, Object> original = mutableData(JsonValues.encode(data));

        Cipher c = cipher();
        Gdpr.protect(data, schema, c);
        List<?> tags = (List<?>) data.get("tags");
        assertInstanceOf(String.class, tags.get(0));
        assertNotEquals("vip", tags.get(0));

        Gdpr.unprotect(data, schema, c);
        assertEquals(JsonValues.encode(original), JsonValues.encode(data));
    }

    @Test
    void unprotectThrowsWhenDecryptedBytesAreNotJson() {
        // A cipher whose "decrypt" yields bytes that are not a single JSON value → the
        // decoded-plaintext-is-not-JSON branch throws DecryptException.
        Cipher notJson = new Cipher() {
            @Override
            public String encrypt(byte[] plaintext) {
                return "x";
            }

            @Override
            public byte[] decrypt(String ciphertext) {
                return "}{".getBytes(StandardCharsets.UTF_8);
            }
        };
        Map<String, Object> schema = json("""
            {"type":"object","properties":{"email":{"type":"string","x-gdpr-sensitive":true}}}""");
        Map<String, Object> data = mutableData("{\"email\":\"anything\"}");
        assertThrows(DecryptException.class, () -> Gdpr.unprotect(data, schema, notJson));
    }

    @Test
    void protectWrapsACheckedCipherFailureAsDecryptException() {
        // A cipher whose encrypt throws a non-DecryptException checked exception → runLeaf wraps it.
        Cipher boom = new Cipher() {
            @Override
            public String encrypt(byte[] plaintext) throws Exception {
                throw new java.security.GeneralSecurityException("boom");
            }

            @Override
            public byte[] decrypt(String ciphertext) {
                return new byte[0];
            }
        };
        Map<String, Object> schema = json("""
            {"type":"object","properties":{"email":{"type":"string","x-gdpr-sensitive":true}}}""");
        Map<String, Object> data = mutableData("{\"email\":\"a@b.co\"}");
        assertThrows(DecryptException.class, () -> Gdpr.protect(data, schema, boom));
    }

    @Test
    void nullArgumentsAreNoOps() {
        Cipher c = cipher();
        Map<String, Object> schema = json("{\"type\":\"object\",\"x-gdpr-sensitive\":true}");
        Map<String, Object> data = mutableData("{\"x\":1}");

        // Each null short-circuits walk(); nothing changes / no NPE.
        Gdpr.protect(null, schema, c);
        Gdpr.protect(data, null, c);
        Gdpr.protect(data, schema, null);
        assertEquals(1L, data.get("x"));
    }

    @Test
    void rootMarkAloneTouchesNothingInData() {
        // Only the root schema is marked (path "") — there is no addressable data leaf, so protect
        // leaves data unchanged.
        Map<String, Object> schema = json("{\"type\":\"object\",\"x-gdpr-sensitive\":true}");
        Map<String, Object> data = mutableData("{\"a\":1,\"b\":\"two\"}");
        Map<String, Object> before = mutableData(JsonValues.encode(data));
        Gdpr.protect(data, schema, cipher());
        assertEquals(JsonValues.encode(before), JsonValues.encode(data));
    }

    @Test
    void validatorAcceptsAnnotatedSchemaUnchanged() {
        // x-gdpr-sensitive is validation-neutral: the keyword never affects a verdict.
        Map<String, Object> annotated = json("""
            {"type":"object","required":["email"],"properties":{
               "email":{"type":"string","minLength":1,"x-gdpr-sensitive":"email"}}}""");
        assertNull(PayloadValidator.validate(annotated, json("{\"email\":\"a@b.co\"}")));
    }

    // ---- helpers ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableData(String raw) {
        // JsonValues.decode yields LinkedHashMap/ArrayList — already mutable in place.
        return (Map<String, Object>) JsonValues.decode(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> data, String key) {
        return (Map<String, Object>) data.get(key);
    }

    private static void assertArrayEqualsLocal(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8));
    }

    @Test
    void jsonValuesRoundTripsAValue() {
        // Exercise the public seam directly (encode/decode parity for the gdpr leaf codec).
        Object value = JsonValues.decode("{\"n\":1,\"s\":\"x\",\"arr\":[true,null]}");
        String encoded = JsonValues.encode(value);
        assertEquals(value, JsonValues.decode(encoded));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "v");
        assertEquals("{\"k\":\"v\"}", JsonValues.encode(m));
        assertSame(String.class, JsonValues.decode("\"plain\"").getClass());
    }
}
