package org.example;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.*;
import jakarta.enterprise.inject.spi.configurator.AnnotatedFieldConfigurator;
import jakarta.enterprise.inject.spi.configurator.AnnotatedTypeConfigurator;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link InjectionTarget} that computes and delegates to other {@link InjectionTarget}s, all representing the same
 * class, but each configured differently to that the {@link ConfigProperty} qualified field injection points have their
 * names prefixed dynamically.
 *
 * @param <T> the bean class type
 */
public class DynamicInjectionTarget<T> implements InjectionTarget<T> {
    private final BeanManager beanManager;
    private final AnnotatedType<T> annotatedType;
    private Bean<T> bean;
    private InjectionPoint injectionPointInjectionPoint; // the synthetic injection point of type InjectionPoint
    /**
     * Maps the value of {@link ConfigProperties#prefix()} at the injection point to the appropriate
     * {@link InjectionTarget} whose injection points are all configured to that particular prefix.
     */
    private final Map<String, InjectionTarget<T>> injectionTargets;

    public DynamicInjectionTarget(BeanManager beanManager, AnnotatedType<T> annotatedType) {
        this.beanManager = beanManager;
        this.annotatedType = annotatedType;

        this.injectionTargets = new ConcurrentHashMap<>();
    }

    void setBean(Bean<T> bean) {
        if (bean.getScope() != Dependent.class) {
            throw new IllegalArgumentException(
                    "DynamicInjectionTarget can only be created for beans that are not @Dependent scoped");
        }
        this.bean = bean;

        prepareInjectionTargetWithoutPrefix();

        this.injectionPointInjectionPoint = new InjectionPointInjectionPoint();
    }

    private void prepareInjectionTargetWithoutPrefix() {
        final ConfigProperties configProperties = getConfigPropertiesQualifier(bean.getQualifiers()).orElseThrow();
        final String classLevelPrefix = configProperties.prefix();
        final InjectionTarget<T> noPrefixInjectionTarget = createInjectionTarget(classLevelPrefix);

        this.injectionTargets.put(ConfigProperties.UNCONFIGURED_PREFIX, noPrefixInjectionTarget);
    }

    @Override
    public T produce(CreationalContext<T> ctx) {
        final String prefix = getPrefix(ctx);
        final InjectionTarget<T> injectionTarget = getInjectionTargetForPrefix(prefix);
        return injectionTarget.produce(ctx);
    }

    @Override
    public void inject(T instance, CreationalContext<T> ctx) {
        final String prefix = getPrefix(ctx);
        final InjectionTarget<T> injectionTarget = getInjectionTargetForPrefix(prefix);
        injectionTarget.inject(instance, ctx);
    }

    @Override
    public void postConstruct(T instance) {
        getInjectionTargetWithoutPrefix().postConstruct(instance);
    }

    @Override
    public void preDestroy(T instance) {
        getInjectionTargetWithoutPrefix().preDestroy(instance);
    }

    @Override
    public void dispose(T instance) {
        getInjectionTargetWithoutPrefix().dispose(instance);
    }

    /**
     * @return all {@link InjectionPoint}s of the underlying bean plus a synthetic InjectionPoint of type
     * InjectionPoint.
     */
    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        return getInjectionTargetWithoutPrefix().getInjectionPoints();
    }

    private InjectionTarget<T> getInjectionTargetWithoutPrefix() {
        return injectionTargets.get(ConfigProperties.UNCONFIGURED_PREFIX);
    }

    private InjectionTarget<T> getInjectionTargetForPrefix(String prefix) {
        return injectionTargets.computeIfAbsent(prefix, this::createInjectionTarget);
    }

    private InjectionTarget<T> createInjectionTarget(String prefix) {
        final InjectionTargetFactory<T> injectionTargetFactory = beanManager.getInjectionTargetFactory(annotatedType);
        final AnnotatedTypeConfigurator<T> typeConfigurator = injectionTargetFactory.configure();
        typeConfigurator.fields().forEach(f -> configureWithPrefix(f, prefix));

        return injectionTargetFactory.createInjectionTarget(bean);
    }

    private <Y> void configureWithPrefix(AnnotatedFieldConfigurator<Y> f, String prefix) {
        final ConfigProperty configProperty = f.getAnnotated().getAnnotation(ConfigProperty.class);
        if (!f.getAnnotated().isAnnotationPresent(Inject.class) || configProperty == null) {
            // nothing to do
            return;
        }

        final String oldName = configProperty.name();
        f.remove(q -> q.annotationType() == ConfigProperty.class)
                .add(new ConfigPropertyLiteral(prefix + "." + oldName, configProperty.defaultValue()));
    }

    private String getPrefix(CreationalContext<T> ctx) {
        final InjectionPoint injectionPoint = (InjectionPoint) beanManager.getInjectableReference(
                injectionPointInjectionPoint, ctx);
        return getConfigPropertiesQualifier(injectionPoint.getQualifiers()).map(ConfigProperties::prefix)
                .orElse(ConfigProperties.UNCONFIGURED_PREFIX);
    }

    private Optional<ConfigProperties> getConfigPropertiesQualifier(final Set<Annotation> qualifiers) {
        return qualifiers.stream()
                .filter(ConfigProperties.class::isInstance)
                .map(ConfigProperties.class::cast)
                .findAny();
    }

    /**
     * A synthetic injection point of type {@link InjectionPoint} backed by a dummy field.
     */
    private class InjectionPointInjectionPoint implements InjectionPoint {
        private final AnnotatedField<?> annotatedField;

        public InjectionPointInjectionPoint() {
            this.annotatedField = beanManager.createAnnotatedType(DynamicInjectionTarget.class)
                    .getFields()
                    .stream()
                    .filter(f -> f.getBaseType() == InjectionPoint.class)
                    .findAny()
                    .orElseThrow();
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
}
