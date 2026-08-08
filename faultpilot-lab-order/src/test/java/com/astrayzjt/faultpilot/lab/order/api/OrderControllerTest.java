package com.astrayzjt.faultpilot.lab.order.api;

import com.astrayzjt.faultpilot.lab.order.cache.OrderCacheService;
import com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private FaultScenarioManager faultManager;
    @Mock
    private OrderCacheService orderCacheService;

    @Test
    void executesDatabaseDelayInsidePostgresForTheSlowSqlScenario() {
        OrderController controller = controllerWithCacheMiss();
        when(faultManager.isSlowSqlEnabled()).thenReturn(true);

        controller.getOrder("order-demo-001");

        assertThat(OrderController.SLOW_ORDER_QUERY).contains("pg_sleep(2)");
        verify(jdbcTemplate).queryForMap(OrderController.SLOW_ORDER_QUERY, "order-demo-001");
    }

    @Test
    void usesTheNormalQueryOutsideTheSlowSqlScenario() {
        OrderController controller = controllerWithCacheMiss();

        controller.getOrder("order-demo-001");

        assertThat(OrderController.ORDER_QUERY).doesNotContain("pg_sleep");
        verify(jdbcTemplate).queryForMap(OrderController.ORDER_QUERY, "order-demo-001");
    }

    @SuppressWarnings("unchecked")
    private OrderController controllerWithCacheMiss() {
        when(jdbcTemplate.queryForMap(any(String.class), eq("order-demo-001")))
                .thenReturn(Map.of("order_number", "order-demo-001"));
        when(orderCacheService.getOrLoad(eq("order-demo-001"), any()))
                .thenAnswer(invocation -> ((Supplier<Map<String, Object>>) invocation.getArgument(1)).get());
        return new OrderController(jdbcTemplate, faultManager, orderCacheService);
    }
}
