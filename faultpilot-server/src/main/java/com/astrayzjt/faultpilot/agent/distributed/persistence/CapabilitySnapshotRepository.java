package com.astrayzjt.faultpilot.agent.distributed.persistence;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshot;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshotStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CapabilitySnapshotRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CapabilitySnapshotRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(CapabilitySnapshot snapshot) {
        jdbcTemplate.update("INSERT INTO capability_snapshot (id,status,agents_json,discovered_at) " +
                        "VALUES (?,?,?::jsonb,?)", snapshot.snapshotId(), snapshot.status().name(),
                json(snapshot.agents()), Timestamp.from(snapshot.discoveredAt()));
    }

    @Transactional
    public void replaceCurrent(CapabilitySnapshot snapshot) {
        insert(snapshot);
        jdbcTemplate.update("UPDATE capability_snapshot SET status='SUPERSEDED' " +
                "WHERE status IN ('ACTIVE','DEGRADED') AND id<>?", snapshot.snapshotId());
    }

    public Optional<CapabilitySnapshot> find(UUID snapshotId) {
        return query("SELECT id,status,agents_json,discovered_at FROM capability_snapshot WHERE id=?", snapshotId)
                .stream().findFirst();
    }

    public Optional<CapabilitySnapshot> findLatestCurrent() {
        return query("SELECT id,status,agents_json,discovered_at FROM capability_snapshot " +
                "WHERE status IN ('ACTIVE','DEGRADED') ORDER BY discovered_at DESC LIMIT 1")
                .stream().findFirst();
    }

    private List<CapabilitySnapshot> query(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, (rs, row) -> {
            try {
                List<AgentCapability> agents = objectMapper.readValue(rs.getString("agents_json"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, AgentCapability.class));
                return new CapabilitySnapshot(rs.getObject("id", UUID.class),
                        CapabilitySnapshotStatus.valueOf(rs.getString("status")), agents,
                        rs.getTimestamp("discovered_at").toInstant());
            } catch (JsonProcessingException exception) {
                throw new SQLException("Cannot parse CapabilitySnapshot agents", exception);
            }
        }, arguments);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize CapabilitySnapshot", exception);
        }
    }
}
