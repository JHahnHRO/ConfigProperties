package org.example;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.literal.InjectLiteral;
import jakarta.enterprise.inject.spi.*;
import jakarta.enterprise.inject.spi.configurator.AnnotatedFieldConfigurator;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

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

    private List<BeanData<?>> beansToCreate = new ArrayList<>();

    static final class BeanData<T> {
        private final BeanAttributes<T> beanAttributes;
        private final Class<T> beanClass;
        private final InjectionTargetFactory<T> injectionTargetFactory;

        BeanData(BeanAttributes<T> beanAttributes, Class<T> beanClass,
                 InjectionTargetFactory<T> injectionTargetFactory) {
            this.beanAttributes = beanAttributes;
            this.beanClass = beanClass;
            this.injectionTargetFactory = injectionTargetFactory;
        }
    }

    public <T> void processBeanAttributes(@Observes ProcessBeanAttributes<T> pba, BeanManager beanManager) {
        final Annotated annotated = pba.getAnnotated();
        if (annotated instanceof AnnotatedType && annotated.isAnnotationPresent(ConfigProperties.class)) {
            pba.veto();

            AnnotatedType<T> annotatedType = (AnnotatedType<T>) annotated;

            beansToCreate.add(new BeanData<>(pba.getBeanAttributes(), annotatedType.getJavaClass(),
                                             new DynamicInjectionTargetFactory<>(beanManager, annotatedType)));
        }
    }

    public void addSyntheticBeans(@Observes AfterBeanDiscovery abd, BeanManager beanManager) {
        beansToCreate.forEach(beanData -> abd.addBean(createSyntheticBean(beanManager, beanData)));
        beansToCreate = null;
    }

    private <T> Bean<T> createSyntheticBean(BeanManager beanManager, BeanData<T> beanData) {
        return beanManager.createBean(beanData.beanAttributes, beanData.beanClass, beanData.injectionTargetFactory);
    }
}
