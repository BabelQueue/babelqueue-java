package com.babelqueue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.TestAbortedException;

/**
 * Records the outcome of every conformance case and, when {@code BQ_CONFORMANCE_RESULTS} names a
 * path, writes a {@code results.json} in the format of {@code schema/results.schema.json}
 * (ADR-0032 section 2) when the test JVM exits. With the variable unset nothing is written.
 *
 * <p>The sections come from the manifest {@code sections} map, not from a hard-coded list. A
 * section is in scope when its scope is {@code core} or a binding this package carries
 * ({@link #carriedBindings()}); a section of any other scope is absent from the results. Every
 * case id the manifest defines for an in-scope section MUST end up in the file: a case that was
 * never executed is written as {@code fail}, so a runner that forgets a case cannot go green.
 * A section newer than this runner's knowledge is still enumerated and therefore reported as
 * {@code fail} rather than crashing or being silently dropped.
 */
public final class ConformanceResults {

    /** Environment variable naming the file to write. */
    public static final String ENV_RESULTS = "BQ_CONFORMANCE_RESULTS";

    private static final String SDK = "babelqueue-java";
    private static final String CORE_SCOPE = "core";

    /** section -> case id -> {status, reason}; the maps are filled from many test classes. */
    private static final Map<String, Map<String, String[]>> RECORDED = new ConcurrentHashMap<>();

    private static final Object INSTALL_LOCK = new Object();
    private static boolean installed;

    private ConformanceResults() {
    }

    // ---- recording ---------------------------------------------------------------------------

    /**
     * Wraps {@code body} so its outcome is recorded under {@code section}/{@code id}: a normal
     * return is {@code pass}, a JUnit abort (failed assumption) is {@code skip} with the abort
     * message as reason, anything else is {@code fail} and is rethrown.
     */
    public static Executable record(String section, String id, Executable body) {
        return () -> {
            install();
            try {
                body.execute();
            } catch (TestAbortedException aborted) {
                put(section, id, "skip", aborted.getMessage());
                throw aborted;
            } catch (Throwable failure) {
                put(section, id, "fail", String.valueOf(failure.getMessage()));
                throw failure;
            }
            put(section, id, "pass", null);
        };
    }

    private static void put(String section, String id, String status, String reason) {
        RECORDED.computeIfAbsent(section, k -> new ConcurrentHashMap<>()).put(id, new String[] {status, reason});
    }

    private static void install() {
        if (System.getenv(ENV_RESULTS) == null || System.getenv(ENV_RESULTS).isBlank()) {
            return;
        }
        synchronized (INSTALL_LOCK) {
            if (installed) {
                return;
            }
            installed = true;
            Runtime.getRuntime().addShutdownHook(new Thread(ConformanceResults::writeFile, "bq-conformance-results"));
        }
    }

    // ---- shared manifest access (public so runners in other packages can use it) -----------------

    /** The parsed vendored {@code manifest.json}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> manifest() {
        return (Map<String, Object>) Json.parse(read("/conformance/manifest.json"));
    }

    /** A vendored conformance resource as UTF-8 text, e.g. {@code /conformance/fixtures/x.json}. */
    public static String read(String path) {
        try {
            return new String(resource(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- manifest enumeration ----------------------------------------------------------------

    /**
     * Bindings this package carries, derived from the code that is actually present in the
     * package (not hand-written). The Java core carries no broker transport (those are separate
     * Maven artifacts with their own conformance runs); it carries {@code otel} when the
     * {@code com.babelqueue.otel} W3C traceparent code is on the class path.
     */
    static List<String> carriedBindings() {
        List<String> bindings = new ArrayList<>();
        if (classPresent("com.babelqueue.otel.Traceparent")) {
            bindings.add("otel");
        }
        return bindings;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, ConformanceResults.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** section name -> ordered case ids, for every section whose scope this package carries. */
    @SuppressWarnings("unchecked")
    static Map<String, List<String>> expectedCases(Map<String, Object> manifest) {
        Map<String, Object> scopes = (Map<String, Object>) manifest.get("sections");
        Set<String> carried = new LinkedHashSet<>(carriedBindings());
        carried.add(CORE_SCOPE);

        Map<String, List<String>> expected = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : scopes.entrySet()) {
            String scope = (String) ((Map<String, Object>) e.getValue()).get("scope");
            if (carried.contains(scope)) {
                expected.put(e.getKey(), caseIds(e.getKey(), manifest.get(e.getKey())));
            }
        }
        return expected;
    }

    /** The case-id rule of the conformance README ("Sections, scopes and exceptions"). */
    @SuppressWarnings("unchecked")
    static List<String> caseIds(String section, Object body) {
        List<String> ids = new ArrayList<>();
        if (body instanceof List<?> list) {
            addNamed(ids, "", i -> section + "#" + i, list);
            return ids;
        }
        if (!(body instanceof Map<?, ?>)) {
            return ids;
        }
        Map<String, Object> map = (Map<String, Object>) body;
        if (map.get("cases") instanceof List<?> list) {
            addNamed(ids, "", i -> section + "#" + i, list);
        }
        for (Map.Entry<String, Object> sub : map.entrySet()) {
            String key = sub.getKey();
            if ("description".equals(key) || "schema".equals(key) || "cases".equals(key)
                || !(sub.getValue() instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> subMap = (Map<String, Object>) sub.getValue();
            if (subMap.get("cases") instanceof List<?> list) {
                addNamed(ids, key + "/", String::valueOf, list);
            } else {
                ids.add(key);
            }
        }
        return ids;
    }

    /** {@code fallback} maps the 0-based index of an unnamed case to its id. */
    private static void addNamed(List<String> ids, String prefix, IntFunction<String> fallback, List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object name = list.get(i) instanceof Map<?, ?> m ? m.get("name") : null;
            ids.add(prefix + (name instanceof String s ? s : fallback.apply(i)));
        }
    }

    // ---- file --------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void writeFile() {
        try {
            Map<String, Object> manifest = (Map<String, Object>) Json.parse(
                new String(resource("/conformance/manifest.json"), StandardCharsets.UTF_8));
            String conformanceVersion = new String(resource("/conformance/CONFORMANCE_VERSION"), StandardCharsets.UTF_8).strip();
            Map<String, List<String>> expected = expectedCases(manifest);

            Map<String, Object> sections = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> section : expected.entrySet()) {
                Map<String, String[]> have = RECORDED.getOrDefault(section.getKey(), Map.of());
                Map<String, Object> cases = new LinkedHashMap<>();
                for (String id : section.getValue()) {
                    String[] outcome = have.get(id);
                    if (outcome == null) {
                        outcome = new String[] {"fail", "case was not executed by the runner"};
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", outcome[0]);
                    if (outcome[1] != null && !"pass".equals(outcome[0])) {
                        result.put("reason", outcome[1].isBlank() ? "no detail" : outcome[1]);
                    }
                    cases.put(id, result);
                }
                sections.put(section.getKey(), cases);
            }

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("sdk", SDK);
            doc.put("version", packageVersion());
            doc.put("conformance_version", conformanceVersion);
            doc.put("generated_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            doc.put("bindings", new ArrayList<Object>(carriedBindings()));
            doc.put("sections", sections);

            Files.writeString(Path.of(System.getenv(ENV_RESULTS)), Json.write(doc) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The package version, read from the build's own {@code pom.xml} (the SDK's version source:
     * the core has no version constant). The first {@code <version>} after the
     * {@code babelqueue-core} artifactId is the project's.
     */
    static String packageVersion() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<artifactId>babelqueue-core</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        if (!m.find()) {
            throw new IllegalStateException("cannot read the package version from pom.xml");
        }
        return m.group(1).strip();
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = ConformanceResults.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return in.readAllBytes();
        }
    }
}
