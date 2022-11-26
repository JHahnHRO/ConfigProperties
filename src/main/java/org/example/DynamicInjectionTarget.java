package org.example;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.*;
import jakarta.enterprise.inject.spi.configurator.AnnotatedFieldConfigurator;
import jakarta.enterprise.inject.spi.configurator.AnnotatedTypeConfigurator;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.annotation.Annotation;
import java.util.HashSet;
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
    private final Bean<T> bean;
    private final InjectionPoint injectionPointInjectionPoint; // the synthetic injection point of type InjectionPoint
    /**
     * Maps the value of {@link ConfigProperties#prefix()} at the injection point to the appropriate
     * {@link InjectionTarget} whose injection points are all configured to that particular prefix.
     */
    private final Map<String, InjectionTarget<T>> injectionTargets;
    private final Set<InjectionPoint> allInjectionPoints;

    public DynamicInjectionTarget(BeanManager beanManager, Bean<T> bean, AnnotatedType<T> annotatedType) {
        this.beanManager = beanManager;
        this.annotatedType = annotatedType;
        this.bean = bean;
        if (bean.getScope() != Dependent.class) {
            throw new IllegalArgumentException(
                    "DynamicInjectionTarget can only be created for beans that are not @Dependent scoped");
        }

        this.injectionTargets = new ConcurrentHashMap<>();
        prepareInjectionTargetWithoutPrefix();


        this.injectionPointInjectionPoint = createSpecialInjectionPoint(bean);
        // cannot use bean.getInjectionPoints, because the bean will get its injection points from this.
        // Therefore, we delegate to the no-prefix InjectionTarget instead.
        Set<InjectionPoint> originalInjectionPoints = getInjectionTargetWithoutPrefix().getInjectionPoints();
        Set<InjectionPoint> allInjectionPoints = new HashSet<>(originalInjectionPoints);
        allInjectionPoints.add(injectionPointInjectionPoint);
        this.allInjectionPoints = Set.copyOf(allInjectionPoints);
    }

    private InjectionPointInjectionPoint createSpecialInjectionPoint(Bean<T> bean) {
        final AnnotatedField<? super DynamicInjectionTarget<T>> annotatedField = beanManager.createAnnotatedType(
                        DynamicInjectionTarget.class)
                .getFields()
                .stream()
                .filter(f -> f.getBaseType() == InjectionPoint.class)
                .findAny()
                .orElseThrow();
        return new InjectionPointInjectionPoint(bean, annotatedField);
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
        return allInjectionPoints;
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
        final ConfigProperties configProperties = getConfigPropertiesQualifier(
                injectionPoint.getQualifiers()).orElseThrow();

        return configProperties.prefix();
    }

    private Optional<ConfigProperties> getConfigPropertiesQualifier(final Set<Annotation> qualifiers) {
        return qualifiers.stream()
                .filter(ConfigProperties.class::isInstance)
                .map(ConfigProperties.class::cast)
                .findAny();
    }

}
