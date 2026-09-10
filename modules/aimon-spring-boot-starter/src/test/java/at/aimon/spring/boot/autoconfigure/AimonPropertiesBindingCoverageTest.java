package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.capability.ModelCapabilityDeclaration;

/**
 * The starter half of #69's recurrence guard: every key {@link ModelCapabilityDeclaration} accepts has a property
 * here.
 *
 * <p>
 * #69 <em>is</em> the absence of this test and its CLI twin ({@code ModelCapabilityConfigBindingTest}).
 * {@code thinkingDialect} was a fully built declaration key with no property on either surface, so the refusal
 * message that advertises it was advice an operator could not act on — silently on this surface, because Boot
 * ignores an unknown property (backlog {@code L-1}).
 *
 * <p>
 * Written as its own class rather than folded into {@code AimonAutoConfigurationTest} so that a failure says which
 * key is unbound rather than which context failed to start.
 */
@DisplayName("AimonProperties.ModelCapabilityProperties - every declarable key has a property")
class AimonPropertiesBindingCoverageTest {

    @Test
    @DisplayName("every setter on the declaration builder has a property of the same name on this surface")
    void everyDeclarableKeyIsBound() throws Exception {
        final List<String> declarable = Arrays.stream(ModelCapabilityDeclaration.Builder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getReturnType() == ModelCapabilityDeclaration.Builder.class)
                .map(Method::getName).sorted().toList();

        final List<String> bound = Arrays
                .stream(Introspector.getBeanInfo(AimonProperties.ModelCapabilityProperties.class, Object.class)
                        .getPropertyDescriptors())
                .filter(property -> property.getReadMethod() != null && property.getWriteMethod() != null)
                .map(PropertyDescriptor::getName).sorted().toList();

        assertThat(declarable).as("the declaration's own key list, which is what the refusal message advertises")
                .isNotEmpty();
        assertThat(bound).as("aimon.llm.model-capabilities.<model>.* — every key an operator can write here")
                .containsAll(declarable);
    }
}
