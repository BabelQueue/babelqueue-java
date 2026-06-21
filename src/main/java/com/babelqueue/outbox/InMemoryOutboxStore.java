package com.babelqueue.outbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-local reference {@link OutboxStore} backed by a map — for tests and single-process demos.
 * It has <b>no real transaction</b>: {@link #save(byte[], String)} just appends, so it cannot deliver
 * the atomic-with-the-business-write guarantee a production store gives. Use a database-backed
 * adapter (a JDBC one, binding {@code save} to the caller's open transaction) in production.
 *
 * <p>It still faithfully models the relay contract: rows are pending until
 * {@link #markPublished(List)}, {@link #fetchUnpublished(int)} returns them <b>oldest-first</b> (a
 * {@link LinkedHashMap} preserves insertion order), and {@link #markFailed(String, String)} bumps the
 * attempt count and stores the last error while leaving the row pending for retry. The body is held
 * (and returned) as a <b>verbatim copy</b> of the bytes it was stored with — never re-encoded.
 *
 * <p>Not thread-safe; intended for single-threaded tests/demos. It does <b>not</b> implement the
 * claim/lock ({@code FOR UPDATE SKIP LOCKED}) that a concurrent production relay needs — that is the
 * adapter's job (ADR-0029 §Scope).
 */
public final class InMemoryOutboxStore implements OutboxStore {

    private static final class Row {
        final byte[] body;
        final String queue;
        int attempts;
        boolean published;
        String error = "";

        Row(byte[] body, String queue) {
            this.body = body;
            this.queue = queue;
        }
    }

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private int sequence;

    @Override
    public String save(byte[] encoded, String queue) {
        // A non-numeric id keeps ids unambiguous and matches the other SDK references.
        String id = "ob-" + (++sequence);
        rows.put(id, new Row(encoded.clone(), queue));
        return id;
    }

    @Override
    public List<OutboxRecord> fetchUnpublished(int limit) {
        List<OutboxRecord> records = new ArrayList<>();
        for (Map.Entry<String, Row> entry : rows.entrySet()) {
            Row row = entry.getValue();
            if (row.published) {
                continue;
            }
            records.add(new OutboxRecord(entry.getKey(), row.body.clone(), row.queue, row.attempts));
            if (records.size() >= limit) {
                break;
            }
        }
        return records;
    }

    @Override
    public void markPublished(List<String> ids) {
        for (String id : ids) {
            Row row = rows.get(id);
            if (row != null) {
                row.published = true;
            }
        }
    }

    @Override
    public void markFailed(String id, String error) {
        Row row = rows.get(id);
        if (row != null) {
            row.attempts++;
            row.error = error;
        }
    }

    /** Test/inspection helper: the number of rows still pending publish. */
    public int pendingCount() {
        int pending = 0;
        for (Row row : rows.values()) {
            if (!row.published) {
                pending++;
            }
        }
        return pending;
    }

    /** Test/inspection helper: the recorded attempt count for one row (0 if unknown). */
    public int attemptsOf(String id) {
        Row row = rows.get(id);
        return row == null ? 0 : row.attempts;
    }

    /** Test/inspection helper: the last recorded error for one row ("" if none). */
    public String lastErrorOf(String id) {
        Row row = rows.get(id);
        return row == null ? "" : row.error;
    }
}
