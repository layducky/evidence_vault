package com.evidencevault.retention;

import com.evidencevault.retention.RetentionStrategy.Kind;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** All retention strategies by kind; {@link #active()} is the one selected by configuration. */
@Component
public class RetentionStrategies {

    private final Map<Kind, RetentionStrategy> byKind = new EnumMap<>(Kind.class);
    private final RetentionProperties properties;

    public RetentionStrategies(List<RetentionStrategy> strategies, RetentionProperties properties) {
        strategies.forEach(s -> byKind.put(s.kind(), s));
        this.properties = properties;
        for (Kind kind : Kind.values()) {
            if (!byKind.containsKey(kind)) {
                throw new IllegalStateException("No retention strategy bean for " + kind);
            }
        }
    }

    public RetentionStrategy active() {
        return get(properties.strategy());
    }

    public RetentionStrategy get(Kind kind) {
        return byKind.get(kind);
    }
}
