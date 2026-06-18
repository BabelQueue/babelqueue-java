package com.babelqueue.idempotency;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local, thread-safe {@link Store} backed by a concurrent set. For tests and
 * single-process consumers; it is not shared across workers and not persistent — use a
 * Redis- or database-backed store for production fleets.
 */
public final class InMemoryStore implements Store {

    private final Set<String> ids = ConcurrentHashMap.newKeySet();

    @Override
    public boolean seen(String messageId) {
        return ids.contains(messageId);
    }

    @Override
    public void remember(String messageId) {
        ids.add(messageId);
    }

    @Override
    public void forget(String messageId) {
        ids.remove(messageId);
    }
}
