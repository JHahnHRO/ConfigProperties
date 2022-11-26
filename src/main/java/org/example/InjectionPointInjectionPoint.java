package org.example;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * A synthetic injection point of type {@link InjectionPoint} backed by a dummy field.
 */
public class InjectionPointInjectionPoint implements InjectionPoint {
    private final Bean<?> bean;
    private final AnnotatedField<?> annotatedField;

    public InjectionPointInjectionPoint(Bean<?> bean, AnnotatedField<?> annotatedField) {
        this.bean = bean;
        this.annotatedField = annotatedField;
    }

    @Override
    public Type getType() {
        return InjectionPoint.class;
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return Set.of(Default.Literal.INSTANCE);
    }

    @Override
    public Bean<?> getBean() {
        return bean;
    }

    @Override
    public Member getMember() {
        return annotatedField.getJavaMember();
    }

    @Override
    public Annotated getAnnotated() {
        return annotatedField;
    }

    @Override
    public boolean isDelegate() {
        return false;
    }

    @Override
    public boolean isTransient() {
        return false;
    }
}
