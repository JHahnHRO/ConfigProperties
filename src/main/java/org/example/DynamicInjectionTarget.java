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
import java.util.*;
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
    private final InjectionTarget<T> originalInjectionTarget;
    private final InjectionPoint syntheticInjectionPoint; // the synthetic injection point of type InjectionPoint
    private final Set<InjectionPoint> allInjectionPoints; // the original bean's injection points + the synthetic one
    private Bean<T> bean;
    /**
     * Maps the value of {@link ConfigProperties#prefix()} at the injection point to the appropriate
     * {@link InjectionTarget} whose injection points are all configured to that particular prefix.
     */
    private final Map<String, InjectionTarget<T>> injectionTargets;

    public DynamicInjectionTarget(BeanManager beanManager, AnnotatedType<T> annotatedType, InjectionTarget<T> originalInjectionTarget) {
        this.beanManager = beanManager;
        this.annotatedType = annotatedType;
        this.originalInjectionTarget = originalInjectionTarget;

        this.injectionTargets = new ConcurrentHashMap<>();
        this.syntheticInjectionPoint = new InjectionPointInjectionPoint();
        this.allInjectionPoints = getAllInjectionPoints();
    }

    private Set<InjectionPoint> getAllInjectionPoints() {
        Set<InjectionPoint> allInjectionPoints = new HashSet<>(originalInjectionTarget.getInjectionPoints());
        allInjectionPoints.add(syntheticInjectionPoint);
        return Collections.unmodifiableSet(allInjectionPoints);
    }

    void setBean(Bean<T> bean) {
        if (bean.getScope() != Dependent.class) {
            throw new IllegalArgumentException(
                    "DynamicInjectionTarget can only be created for beans that are not @Dependent scoped");
        }
        this.bean = bean;

        prepareInjectionTargetWithoutPrefix();

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
        originalInjectionTarget.postConstruct(instance);
    }

    @Override
    public void preDestroy(T instance) {
        originalInjectionTarget.preDestroy(instance);
    }

    @Override
    public void dispose(T instance) {
        originalInjectionTarget.dispose(instance);
    }

    /**
     * @return all {@link InjectionPoint}s of the underlying bean plus a synthetic InjectionPoint of type
     * InjectionPoint.
     */
    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        return allInjectionPoints;
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
                syntheticInjectionPoint, ctx);
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

        public InjectionPointInjectionPoint() {
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
            return null;
        }

        @Override
        public Annotated getAnnotated() {
            return new Annotated() {
                @Override
                public Type getBaseType() {
                    return InjectionPoint.class;
                }

                @Override
                public Set<Type> getTypeClosure() {
                    return Set.of(InjectionPoint.class, Object.class);
                }

                @Override
                public <T extends Annotation> T getAnnotation(Class<T> aClass) {
                    return null;
                }

                @Override
                public <T extends Annotation> Set<T> getAnnotations(Class<T> aClass) {
                    return Collections.emptySet();
                }

                @Override
                public Set<Annotation> getAnnotations() {
                    return Collections.emptySet();
                }

                @Override
                public boolean isAnnotationPresent(Class<? extends Annotation> aClass) {
                    return false;
                }
            };
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
