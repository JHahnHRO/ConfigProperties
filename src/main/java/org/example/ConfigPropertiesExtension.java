package org.example;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.literal.InjectLiteral;
import jakarta.enterprise.inject.spi.*;
import jakarta.enterprise.inject.spi.configurator.AnnotatedFieldConfigurator;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Proof-of-concept implementation to show that @ConfigProperties can work on beans that do not have a no-args
 * constructor and do have constructor and/or initializer injection points.
 */
public class ConfigPropertiesExtension implements Extension {

    public <X> void onAnnotatedType(@Observes @WithAnnotations(ConfigProperties.class) ProcessAnnotatedType<X> pat) {
        final ConfigProperties configProperties = pat.getAnnotatedType().getAnnotation(ConfigProperties.class);
        if (configProperties == null) {
            // annotation on some method, not on the type => not relevant
            return;
        }

        pat.configureAnnotatedType().fields().forEach(this::configureField);
    }

    private <Y> void configureField(AnnotatedFieldConfigurator<Y> f) {
        if (!f.getAnnotated().isAnnotationPresent(Inject.class)) {
            f.add(InjectLiteral.INSTANCE);
        }
        if (!f.getAnnotated().isAnnotationPresent(ConfigProperty.class)) {
            f.add(new ConfigPropertyLiteral(f.getAnnotated().getJavaMember().getName()));
        }
    }

    Map<AnnotatedType<?>, DynamicInjectionTarget<?>> injectionTargetMap = new HashMap<>();

    public <T> void onProcessInjectionTarget(@Observes ProcessInjectionTarget<T> pit, BeanManager beanManager) {
        final AnnotatedType<T> annotatedType = pit.getAnnotatedType();
        if (!annotatedType.isAnnotationPresent(ConfigProperties.class)) {
            // bean not relevant
            return;
        }

        final DynamicInjectionTarget<T> injectionTarget = new DynamicInjectionTarget<>(beanManager, annotatedType);
        injectionTargetMap.put(annotatedType, injectionTarget);
        pit.setInjectionTarget(injectionTarget);
    }

    public <T> void onProcessBean(@Observes ProcessBean<T> pb) {
        final DynamicInjectionTarget<T> dynamicInjectionTarget = (DynamicInjectionTarget<T>) injectionTargetMap.get(
                pb.getAnnotated());
        if (dynamicInjectionTarget != null) {
            dynamicInjectionTarget.setBean(pb.getBean());
        }
    }
}
