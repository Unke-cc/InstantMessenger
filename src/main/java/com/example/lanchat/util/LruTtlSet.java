package com.example.lanchat.util;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class LruTtlSet {

    private final int maxSize;
    private final long ttlMs;
    private final LinkedHashMap<String, Long> map;

    public LruTtlSet(int maxSize, long ttlMs) {
        this.maxSize = maxSize;
        this.ttlMs = ttlMs;
        this.map = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized boolean addIfAbsent(String key, long now) {
        prune(now);
        Long existing = map.get(key);
        if (existing != null) {
            return false;
        }
        map.put(key, now);
        if (map.size() > maxSize) {
            Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        return true;
    }

    private void prune(long now) {
        if (map.isEmpty()) return;
        long threshold = now - ttlMs;
        Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() < threshold) {
                it.remove();
            }
        }
    }
}
