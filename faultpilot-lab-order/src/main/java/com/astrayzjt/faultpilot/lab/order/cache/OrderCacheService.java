package com.astrayzjt.faultpilot.lab.order.cache;

import com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Service
public class OrderCacheService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FaultScenarioManager faultScenarioManager;
    private final Timer commandLatency;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter cacheErrors;
    private final AtomicBoolean available = new AtomicBoolean(true);

    public OrderCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                             FaultScenarioManager faultScenarioManager, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.faultScenarioManager = faultScenarioManager;
        this.commandLatency = Timer.builder("faultpilot.redis.command.latency")
                .description("Observed cache command latency in the order laboratory")
                .register(meterRegistry);
        this.cacheHits = Counter.builder("faultpilot.cache.lookups").tag("result", "hit").register(meterRegistry);
        this.cacheMisses = Counter.builder("faultpilot.cache.lookups").tag("result", "miss").register(meterRegistry);
        this.cacheErrors = Counter.builder("faultpilot.cache.lookups").tag("result", "error").register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("faultpilot.redis.availability", available, state -> state.get() ? 1 : 0)
                .register(meterRegistry);
    }

    public Map<String, Object> getOrLoad(String orderNumber, Supplier<Map<String, Object>> databaseLoader) {
        Optional<Map<String, Object>> cached = get(orderNumber);
        if (cached.isPresent()) {
            cacheHits.increment();
            return cached.get();
        }
        cacheMisses.increment();
        Map<String, Object> order = databaseLoader.get();
        put(orderNumber, order);
        return order;
    }

    private Optional<Map<String, Object>> get(String orderNumber) {
        Timer.Sample sample = Timer.start();
        try {
            String payload = faultScenarioManager.executeRedisOperation(() -> redisTemplate.opsForValue().get(key(orderNumber)));
            available.set(true);
            return payload == null ? Optional.empty() : Optional.of(objectMapper.readValue(payload, MAP_TYPE));
        } catch (Exception exception) {
            available.set(false);
            cacheErrors.increment();
            return Optional.empty();
        } finally {
            sample.stop(commandLatency);
        }
    }

    private void put(String orderNumber, Map<String, Object> order) {
        Timer.Sample sample = Timer.start();
        try {
            String payload = objectMapper.writeValueAsString(order);
            faultScenarioManager.executeRedisOperation(() -> {
                redisTemplate.opsForValue().set(key(orderNumber), payload, CACHE_TTL);
                return null;
            });
            available.set(true);
        } catch (Exception exception) {
            available.set(false);
            cacheErrors.increment();
        } finally {
            sample.stop(commandLatency);
        }
    }

    private String key(String orderNumber) {
        return "faultpilot:lab:order:" + orderNumber;
    }
}
