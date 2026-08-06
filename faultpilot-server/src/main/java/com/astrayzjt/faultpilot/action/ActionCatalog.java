package com.astrayzjt.faultpilot.action;

import com.astrayzjt.faultpilot.common.domain.ActionCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ActionCatalog {

    private final Map<ActionCode, RemediationAction<?>> actions;

    public ActionCatalog(List<RemediationAction<?>> actions) {
        EnumMap<ActionCode, RemediationAction<?>> index = new EnumMap<>(ActionCode.class);
        for (RemediationAction<?> action : actions) {
            if (index.put(action.code(), action) != null) {
                throw new IllegalStateException("Duplicate remediation action: " + action.code());
            }
        }
        this.actions = Map.copyOf(index);
    }

    public RemediationAction<?> require(ActionCode code) {
        RemediationAction<?> action = actions.get(code);
        if (action == null) {
            throw new IllegalArgumentException("Unsupported remediation action: " + code);
        }
        return action;
    }
}
