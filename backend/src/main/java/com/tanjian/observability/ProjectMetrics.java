package com.tanjian.observability;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Fixed metric names; no user identifiers, tokens or prompts in labels. */
@Component
@Endpoint(id = "tanjian")
public class ProjectMetrics {
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    public void increment(String name) { counters.computeIfAbsent(name, k -> new LongAdder()).increment(); }
    public void gauge(String name, long value) { gauges.computeIfAbsent(name, k -> new AtomicLong()).set(value); }
    public void duration(String name, long millis) {
        increment(name + ".count");
        counters.computeIfAbsent(name + ".total_ms", k -> new LongAdder()).add(millis);
        gauges.computeIfAbsent(name + ".max_ms", k -> new AtomicLong()).accumulateAndGet(millis, Math::max);
    }
    @ReadOperation
    public Map<String, Long> snapshot() {
        Map<String, Long> result = new TreeMap<>();
        counters.forEach((k,v) -> result.put(k, v.sum()));
        gauges.forEach((k,v) -> result.put(k, v.get()));
        return result;
    }
}
