package com.astrayzjt.faultpilot.lab.inventory.api;

import com.astrayzjt.faultpilot.lab.inventory.fault.FaultScenarioManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final JdbcTemplate jdbcTemplate;
    private final FaultScenarioManager faultManager;

    public InventoryController(JdbcTemplate jdbcTemplate, FaultScenarioManager faultManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.faultManager = faultManager;
    }

    @GetMapping("/{sku}")
    public Map<String, Object> getInventory(@PathVariable String sku) {
        if (faultManager.isDependencyDelayEnabled()) {
            sleep(1500);
        }
        return jdbcTemplate.queryForMap("SELECT sku, available_quantity, updated_at " +
                "FROM lab_inventory WHERE sku = ?", sku);
    }

    @GetMapping("/internal/diagnostics")
    public Map<String, Object> diagnostics() {
        return Map.of("service", "inventory-service", "dependencyDelay", faultManager.isDependencyDelayEnabled());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

