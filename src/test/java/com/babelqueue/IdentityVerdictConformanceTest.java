package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the core-scope {@code identity_verdict} conformance section (conformance v1.7.0; ADR-0036 decision 4) on
 * {@link BodyVerdict}: each case's raw {@code body} must classify as the case's {@code expect} ({@code ok} or
 * {@code poison}). {@link EnvelopeCodec#decode(String)} is not the oracle: it is lenient about the type of a known member,
 * so it answers {@code ok} for {@code {"job":"urn:x","attempts":"3"}} where the suite expects {@code poison}.
 */
class IdentityVerdictConformanceTest {

    private static final int CASES = 97;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases() {
        Map<String, Object> section = (Map<String, Object>) ConformanceResults.manifest().get("identity_verdict");
        assertNotNull(section, "manifest is missing the 'identity_verdict' section");
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Object o : (List<Object>) section.get("cases")) {
            cases.add((Map<String, Object>) o);
        }
        return cases;
    }

    @TestFactory
    Stream<DynamicTest> identityVerdictCases() {
        List<Map<String, Object>> cases = cases();
        assertEquals(CASES, cases.size());
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String name = (String) c.get("name");
            tests.add(dynamicTest(name, ConformanceResults.record("identity_verdict", name, () -> run(c))));
        }
        return tests.stream();
    }

    private static void run(Map<String, Object> c) {
        String body = (String) c.get("body");
        String want = (String) c.get("expect");
        BodyVerdict.Result verdict = BodyVerdict.classify(body);
        String got = verdict.isOk() ? "ok" : "poison";
        assertEquals(want, got, "body " + body + (verdict.isPoison() ? " (" + verdict.cause() + ")" : ""));
    }

    @Test
    void okVerdictCarriesNoCauseAndPoisonCarriesOne() {
        BodyVerdict.Result ok = BodyVerdict.classify("{\"job\":\"urn:x\"}");
        assertTrue(ok.isOk());
        assertFalse(ok.isPoison());
        assertEquals(BodyVerdict.Kind.OK, ok.kind());
        assertNull(ok.cause());

        for (String body : new String[] {null, "", "not-json", "[]", "null", "{}", "{\"job\":5}"}) {
            BodyVerdict.Result poison = BodyVerdict.classify(body);
            assertTrue(poison.isPoison(), String.valueOf(body));
            assertNotNull(poison.cause(), String.valueOf(body));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"job\":\"urn:x\",\"attempts\":\"3\"}",
        "{\"job\":\"urn:x\",\"data\":[]}",
    })
    void decodeStaysLenientWhileTheVerdictIsPoison(String body) {
        // The additive verdict API must not change EnvelopeCodec.decode: it still yields the identity.
        Envelope envelope = EnvelopeCodec.decode(body, warning -> { });
        assertEquals("urn:x", EnvelopeCodec.urn(envelope));
        assertTrue(BodyVerdict.classify(body).isPoison());
    }

    /**
     * The conformance cases of the typed members are identity-less, so they are poison for that reason alone. These
     * bodies carry a valid identity, so only the member-type rule can make them poison.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"job\":\"urn:x\",\"trace_id\":7}                                      | trace_id",
        "{\"job\":\"urn:x\",\"attempts\":1.5}                                    | attempts",
        "{\"job\":\"urn:x\",\"attempts\":9223372036854775808}                    | attempts",
        "{\"job\":\"urn:x\",\"meta\":\"x\"}                                        | meta",
        "{\"job\":\"urn:x\",\"meta\":{\"id\":5}}                                   | meta.id",
        "{\"job\":\"urn:x\",\"meta\":{\"queue\":5}}                                | meta.queue",
        "{\"job\":\"urn:x\",\"meta\":{\"lang\":true}}                              | meta.lang",
        "{\"job\":\"urn:x\",\"meta\":{\"schema_version\":\"1\"}}                   | meta.schema_version",
        "{\"job\":\"urn:x\",\"meta\":{\"created_at\":1.5}}                         | meta.created_at",
        "{\"job\":\"urn:x\",\"meta\":{\"created_at\":9223372036854775808}}         | meta.created_at",
        "{\"job\":\"urn:x\",\"dead_letter\":[]}                                    | dead_letter",
        "{\"job\":\"urn:x\",\"dead_letter\":{\"reason\":5}}                         | dead_letter.reason",
        "{\"job\":\"urn:x\",\"dead_letter\":{\"lang\":{}}}                         | dead_letter.lang",
        "{\"job\":\"urn:x\",\"dead_letter\":{\"failed_at\":1.5}}                   | dead_letter.failed_at",
        "{\"job\":\"urn:x\",\"dead_letter\":{\"attempts\":\"3\"}}                  | dead_letter.attempts",
        "{\"job\":\"urn:x\",\"data\":{\"n\":1e400}}                                | data",
    })
    void wrongTypedKnownMemberOfAnIdentifiedBodyIsPoisonNamingTheMemberPath(String body, String member) {
        BodyVerdict.Result verdict = BodyVerdict.classify(body.strip());
        assertTrue(verdict.isPoison(), body);
        assertTrue(verdict.cause().contains("'" + member + "'"), verdict.cause());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"job\":\"urn:x\",\"meta\":{\"schema_version\":2}}",
        "{\"job\":\"urn:x\",\"meta\":{\"created_at\":9223372036854775807,\"id\":null}}",
        "{\"job\":\"urn:x\",\"dead_letter\":{\"failed_at\":1,\"error\":null}}",
        "{\"job\":\"urn:x\",\"data\":{\"n\":1e300,\"s\":\"\\ud800\"},\"\\ud800\":1}",
    })
    void wellTypedMembersOfAnIdentifiedBodyAreOk(String body) {
        assertTrue(BodyVerdict.classify(body).isOk(), body);
    }

    @Test
    void depthLimitIsDeterministicAndIndependentOfTheThreadStack() throws InterruptedException {
        assertEquals(256, BodyVerdict.MAX_DEPTH);
        // The top-level object is level 1: an array nested MAX_DEPTH - 1 levels deeper is the deepest accepted body.
        String atLimit = nested(BodyVerdict.MAX_DEPTH - 1);
        String overLimit = nested(BodyVerdict.MAX_DEPTH);
        for (long stackBytes : new long[] {0L, 128L * 1024, 4L * 1024 * 1024}) {
            BodyVerdict.Result[] out = new BodyVerdict.Result[3];
            Thread t = new Thread(null, () -> {
                out[0] = BodyVerdict.classify(atLimit);
                out[1] = BodyVerdict.classify(overLimit);
                out[2] = BodyVerdict.classify(nested(50_000));
            }, "verdict-depth", stackBytes);
            t.start();
            t.join();
            assertTrue(out[0].isOk(), "stack " + stackBytes);
            assertTrue(out[1].isPoison(), "stack " + stackBytes);
            assertTrue(out[2].isPoison(), "stack " + stackBytes);
            assertTrue(out[1].cause().contains("deeper than 256"), out[1].cause());
        }
        // An accepted body is one the core decoder can read, on a small stack too.
        Thread small = new Thread(null, () -> assertEquals("urn:x", EnvelopeCodec.urn(EnvelopeCodec.decode(atLimit, w -> { }))),
            "decode-small", 128L * 1024);
        small.start();
        small.join();
    }

    /**
     * JSON allows only ASCII {@code [0-9A-Fa-f]} in a {@code \\u} escape. {@code Character.digit} would also accept
     * fullwidth and Arabic-Indic digits, which would route the body on a different URN than every other SDK.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"job\":\"urn:\\u\uFF10\uFF10\uFF14\uFF11\"}",
        "{\"job\":\"urn:x\",\"trace_id\":\"\\u\u0660\u0660\u0664\u0661\"}",
        "{\"job\":\"urn:x\",\"\\u\uFF10\uFF10\uFF14\uFF11\":1}",
        "{\"job\":\"urn:x\",\"data\":{\"a\":\"\\u00\uFF14\uFF11\"}}",
        "{\"job\":\"urn:x\",\"x\":\"\\u004g\"}",
    })
    void nonAsciiOrNonHexUnicodeEscapeIsPoison(String body) {
        BodyVerdict.Result verdict = BodyVerdict.classify(body);
        assertTrue(verdict.isPoison(), body);
        assertTrue(verdict.cause().contains("unicode escape"), verdict.cause());
    }

    @Test
    void asciiHexUnicodeEscapesInAnyCaseAreOk() {
        assertTrue(BodyVerdict.classify("{\"job\":\"urn:\\u0041\\u00e9\\u00E9\"}").isOk());
    }

    @Test
    void duplicatedIdentityKeepsTheLastValue() {
        assertTrue(BodyVerdict.classify("{\"job\":5,\"job\":\"urn:x\"}").isOk());
        assertTrue(BodyVerdict.classify("{\"job\":\"urn:x\",\"job\":5}").isPoison());
        assertTrue(BodyVerdict.classify("{\"job\":\"  \",\"urn\":\"urn:x\",\"urn\":null}").isPoison());
    }

    private static String nested(int levels) {
        return "{\"job\":\"urn:x\",\"x\":" + "[".repeat(levels) + "]".repeat(levels) + "}";
    }
}
