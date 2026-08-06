package com.astrayzjt.faultpilot.lab.order.api;

import com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager;
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

    public OrderController(JdbcTemplate jdbcTemplate, FaultScenarioManager faultManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.faultManager = faultManager;
    }

    @GetMapping("/{orderNumber}")
    public Map<String, Object> getOrder(@PathVariable String orderNumber) {
        if (faultManager.isSlowSqlEnabled()) {
            sleep(2000);
        }
        return jdbcTemplate.queryForMap("SELECT order_number, item_name, quantity, created_at " +
                "FROM lab_orders WHERE order_number = ?", orderNumber);
    }

    @GetMapping("/internal/diagnostics")
    public Map<String, Object> diagnostics() {
        return Map.of(
                "service", "order-service",
                "cpuHotspot", faultManager.isActive(com.astrayzjt.faultpilot.lab.order.fault.ScenarioCode.CPU_HOTSPOT),
                "threadPoolExhausted", faultManager.isActive(com.astrayzjt.faultpilot.lab.order.fault.ScenarioCode.THREAD_POOL_EXHAUSTED),
                "slowSql", faultManager.isSlowSqlEnabled(),
                "dbPoolExhausted", faultManager.isActive(com.astrayzjt.faultpilot.lab.order.fault.ScenarioCode.DB_POOL_EXHAUSTED),
                "blockedActive", faultManager.blockedActiveCount(),
                "blockedQueue", faultManager.blockedQueueSize());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

