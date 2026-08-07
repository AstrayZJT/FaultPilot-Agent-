package com.astrayzjt.faultpilot.lab.order.api;

import com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager;
import com.astrayzjt.faultpilot.lab.order.cache.OrderCacheService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final JdbcTemplate jdbcTemplate;
    private final FaultScenarioManager faultManager;
    private final OrderCacheService orderCacheService;

    public OrderController(JdbcTemplate jdbcTemplate, FaultScenarioManager faultManager, OrderCacheService orderCacheService) {
        this.jdbcTemplate = jdbcTemplate;
        this.faultManager = faultManager;
        this.orderCacheService = orderCacheService;
    }

    @GetMapping("/{orderNumber}")
    public Map<String, Object> getOrder(@PathVariable String orderNumber) {
        return orderCacheService.getOrLoad(orderNumber, () -> {
            if (faultManager.isSlowSqlEnabled()) {
                sleep(2000);
            }
            return jdbcTemplate.queryForMap("SELECT order_number, item_name, quantity, created_at " +
                    "FROM lab_orders WHERE order_number = ?", orderNumber);
        });
    }

    @GetMapping("/internal/diagnostics")
    public Map<String, Object> diagnostics() {
        return Map.ofEntries(
                Map.entry("service", "order-service"),
                Map.entry("cpuHotspot", faultManager.isActive(com.astrayzjt.faultpilot.lab.order.fault.ScenarioCode.CPU_HOTSPOT)),
                Map.entry("threadPoolExhausted", faultManager.isActive(com.astrayzjt.faultpilot.lab.order.fault.ScenarioCode.THREAD_POOL_EXHAUSTED)),
                Map.entry("slowSql", faultManager.isSlowSqlEnabled()),
                Map.entry("dbPoolExhausted", faultManager.isActive(com.astrayzjt.faultpilot.lab.order.fault.ScenarioCode.DB_POOL_EXHAUSTED)),
                Map.entry("redisLatency", faultManager.isActive(com.astrayzjt.faultpilot.lab.order.fault.ScenarioCode.REDIS_LATENCY)),
                Map.entry("redisClientPoolExhausted", faultManager.isActive(com.astrayzjt.faultpilot.lab.order.fault.ScenarioCode.REDIS_CLIENT_POOL_EXHAUSTED)),
                Map.entry("redisClientActive", faultManager.redisClientPoolActiveCount()),
                Map.entry("redisClientPending", faultManager.redisClientPoolPendingCount()),
                Map.entry("blockedActive", faultManager.blockedActiveCount()),
                Map.entry("blockedQueue", faultManager.blockedQueueSize()));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
