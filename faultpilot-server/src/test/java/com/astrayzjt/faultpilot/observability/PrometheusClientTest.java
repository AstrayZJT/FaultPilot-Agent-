package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PrometheusClientTest {

    @Test
    void scopesQueriesToCatalogLabelsAndParsesVectorSamples() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server.expect(once(), request -> assertThat(request.getURI().getPath()).isEqualTo("/api/v1/query"))
                .andExpect(request -> rawQuery.set(request.getURI().getRawQuery()))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"job":"faultpilot-lab-order"},"value":[1700000000,"0.95"]}
                        ]}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        ServiceCatalogProperties catalog = new ServiceCatalogProperties();
        catalog.setServices(Map.of("order-service", new ServiceCatalogProperties.ServiceDefinition(
                Map.of("job", "faultpilot-lab-order"), "http://localhost:8081", "lab", List.of(), List.of())));
        ObservabilityProperties properties = new ObservabilityProperties();
        PrometheusClient client = new PrometheusClient(builder.baseUrl("http://localhost:9090").build(), catalog);

        List<PrometheusClient.Sample> samples = client.queryMetric("process_cpu_usage", "order-service");

        assertThat(samples).singleElement().extracting(PrometheusClient.Sample::value).isEqualTo(0.95);
        assertThat(URLDecoder.decode(rawQuery.get(), StandardCharsets.UTF_8))
                .isEqualTo("query=process_cpu_usage{job=\"faultpilot-lab-order\"}");
        server.verify();
    }
}
