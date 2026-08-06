package com.astrayzjt.faultpilot.incident.application;

import com.astrayzjt.faultpilot.common.domain.Incident;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.IncidentStatus;
import com.astrayzjt.faultpilot.common.domain.TimeRange;
import com.astrayzjt.faultpilot.incident.api.IncidentRequest;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.incident.persistence.IncidentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class IncidentService {

    private final IncidentRepository repository;
    private final ServiceCatalogProperties catalog;
    private final ObjectMapper objectMapper;

    public IncidentService(IncidentRepository repository, ServiceCatalogProperties catalog, ObjectMapper objectMapper) {
        this.repository = repository;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    public Incident create(IncidentRequest request) {
        return create(request, "MANUAL", null);
    }

    public Incident create(IncidentRequest request, String source, String externalRef) {
        catalog.require(request.serviceName());
        UUID incidentId = UUID.randomUUID();
        TimeRange timeRange = normalizeTimeRange(request.startTime(), request.endTime());
        Instant now = Instant.now();
        IncidentSnapshot snapshot = new IncidentSnapshot(incidentId, request.serviceName(), request.symptom(),
                request.alertId(), timeRange, request.endpointName(), request.instanceName(), request.requestId(),
                Boolean.TRUE.equals(request.allowRemediation()), now);
        Incident incident = new Incident(incidentId, IncidentStatus.ACCEPTED, snapshot, now, now);
        try {
            repository.insert(incident, objectMapper.writeValueAsString(request), source, externalRef);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize incident request", exception);
        }
        return incident;
    }

    public Optional<Incident> find(UUID incidentId) {
        return repository.findById(incidentId);
    }

    public void updateStatus(UUID incidentId, IncidentStatus status) {
        repository.updateStatus(incidentId, status);
    }

    private TimeRange normalizeTimeRange(Instant start, Instant end) {
        Instant now = Instant.now();
        if (start == null && end == null) {
            return new TimeRange(now.minus(Duration.ofMinutes(10)), now);
        }
        if (start == null) {
            start = end.minus(Duration.ofMinutes(10));
        }
        if (end == null) {
            end = now;
        }
        TimeRange range = new TimeRange(start, end);
        if (range.duration().compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Investigation window cannot exceed one hour");
        }
        return range;
    }
}
