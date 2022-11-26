package org.example;

import jakarta.enterprise.util.AnnotationLiteral;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * This class is sadly still missing from MicroProfile Config API.
 */
public class ConfigPropertyLiteral extends AnnotationLiteral<ConfigProperty> implements ConfigProperty {
    private final String name;
    private final String defaultValue;

    public ConfigPropertyLiteral(String name) {
        this(name, ConfigProperty.UNCONFIGURED_VALUE);
    }

    public ConfigPropertyLiteral(String name, final String defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String defaultValue() {
        return defaultValue;
    }
}
