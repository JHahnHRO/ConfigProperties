package org.example;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldJunit5Extension;
import org.jboss.weld.junit5.WeldSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(WeldJunit5Extension.class)
class ConfigPropertiesExtensionTest {

    Weld weld = WeldInitiator.createWeld()
            .addExtension(new ConfigPropertiesExtension())
            .addBeanClass(ConfigPropertyProducer.class) // to produce @ConfigProperty String
            .addBeanClasses(FooBean.class, BarBean.class, BazBean.class);

    @SuppressWarnings("unused")
    @WeldSetup
    WeldInitiator w = WeldInitiator.of(weld);

    @Test
    void testPrefixAtInjectionPoint(@ConfigProperties(prefix = "my.prefix") FooBean fooWithPrefix) {
        assertThat(fooWithPrefix.getBar()).isEqualTo("my.prefix.bar");
    }

    @Test
    void testNoPrefixAtInjectionPoint(@ConfigProperties FooBean fooWithoutPrefix) {
        assertThat(fooWithoutPrefix.getBar()).isEqualTo("foo.bar");
    }

    @Test
    void testNameAtField(@ConfigProperties FooBean fooWithoutPrefix) {
        assertThat(fooWithoutPrefix.getBaz()).isEqualTo("foo.bazzz");
    }

    @Test
    void testClassWithoutNoArgsConstructor(@ConfigProperties BarBean barWithoutPrefix) {
        assertThat(barWithoutPrefix.getBaz()).isEqualTo("bar.bazzz");
    }

    @Test
    void testClassWithProducerMethod(@Default PairOfStrings pairOfStrings) {
        assertThat(pairOfStrings.left).isEqualTo("baz.fizz");
        assertThat(pairOfStrings.right).isEqualTo("baz.buzz");
    }

    @Singleton
    private static class ConfigPropertyProducer {
        /**
         * @param injectionPoint an injection point
         * @return the value of {@link ConfigProperty#name()} at the given injection point
         */
        @Produces
        @ConfigProperty
        String configProperty(InjectionPoint injectionPoint) {
            final ConfigProperty qualifier = injectionPoint.getQualifiers()
                    .stream()
                    .filter(q -> q instanceof ConfigProperty)
                    .map(ConfigProperty.class::cast)
                    .findAny()
                    .orElseThrow();

            return qualifier.name();
        }
    }

    @Dependent
    @ConfigProperties(prefix = "foo")
    static class FooBean {

        private String bar;

        @ConfigProperty(name = "bazzz")
        private String baz;

        public String getBar() {
            return bar;
        }

        public String getBaz() {
            return baz;
        }
    }


    @Dependent
    @ConfigProperties(prefix = "bar")
    static class BarBean {

        @ConfigProperty(name = "bazzz")
        private String baz;

        @Inject
        public BarBean(BeanManager ignored) {
            // argument irrelevant, just needs to be present and a resolvable dependency
        }

        public String getBaz() {
            return baz;
        }
    }

    @Dependent
    @ConfigProperties(prefix = "baz")
    static class BazBean {

        private String fizz;
        private String buzz;


        @Produces
        @Dependent
        PairOfStrings fizzBuzz() {
            return new PairOfStrings(fizz, buzz);
        }
    }

    static class PairOfStrings {

        final String left;
        final String right;

        PairOfStrings(String left, String right) {
            this.left = left;
            this.right = right;
        }
    }
}