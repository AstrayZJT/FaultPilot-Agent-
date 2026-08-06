package com.astrayzjt.faultpilot.runbook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RunbookService {
    private final JdbcTemplate jdbcTemplate;

    public RunbookService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RunbookDocument> search(String query, String causeCode) {
        String needle = query == null ? "" : query.trim();
        return jdbcTemplate.query("SELECT id,title,content,cause_code,reviewed,updated_at FROM runbook_document " +
                        "WHERE reviewed=true AND (?='' OR title ILIKE '%'||?||'%' OR content ILIKE '%'||?||'%') " +
                        "AND (? IS NULL OR cause_code=?) ORDER BY updated_at DESC LIMIT 20", (rs, row) ->
                        new RunbookDocument(rs.getObject("id", java.util.UUID.class), rs.getString("title"),
                                rs.getString("content"), rs.getString("cause_code"), rs.getBoolean("reviewed"),
                                rs.getTimestamp("updated_at").toInstant()), needle, needle, needle, causeCode, causeCode);
    }
}
