package org.example;

import jakarta.enterprise.inject.spi.*;

/**
 * Creates {@link DynamicInjectionTarget}s.
 *
 * @param <T> the bean class type.
 */
public class DynamicInjectionTargetFactory<T> implements InjectionTargetFactory<T> {
    private final BeanManager beanManager;
    final AnnotatedType<T> annotatedType;

    public DynamicInjectionTargetFactory(BeanManager beanManager, AnnotatedType<T> annotatedType) {
        this.beanManager = beanManager;
        this.annotatedType = annotatedType;
    }

    @Override
    public InjectionTarget<T> createInjectionTarget(Bean<T> bean) {
        return new DynamicInjectionTarget<>(beanManager, bean, annotatedType);
    }
}
