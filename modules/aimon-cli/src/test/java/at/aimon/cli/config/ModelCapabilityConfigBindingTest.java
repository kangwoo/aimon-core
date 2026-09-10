package at.aimon.cli.config;

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
 * The CLI half of #69's recurrence guard: every key {@link ModelCapabilityDeclaration} accepts has a yaml key here.
 *
 * <p>
 * #69 <em>is</em> the absence of this test. {@code thinkingDialect} had a field on the declaration, an
 * {@code Optional} getter, a builder setter, a place in {@code declaresAnything()} and a test of its own — and no
 * config surface bound a key for it, so the refusal message that advertises it sent a CLI operator into a boot
 * failure and a starter operator into silence. Nothing went red, because nothing was looking at the two lists
 * together.
 *
 * <p>
 * It cannot live in {@code aimon-core}: that module cannot see this one or the starter, so a test there can only
 * check the refusal message against the declaration type — which was already true when #69 was filed. This module and
 * {@code aimon-spring-boot-starter} both depend on core, so each can see the list; the starter's half is
 * {@code AimonPropertiesBindingCoverageTest}, and the pair is what makes "a key exists on the declaration and no
 * surface binds it" fail a build rather than become an issue.
 */
@DisplayName("ModelCapabilityConfig - every declarable key has a yaml key")
class ModelCapabilityConfigBindingTest {

    @Test
    @DisplayName("every setter on the declaration builder has a property of the same name on this surface")
    void everyDeclarableKeyIsBound() throws Exception {
        final List<String> declarable = Arrays.stream(ModelCapabilityDeclaration.Builder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getReturnType() == ModelCapabilityDeclaration.Builder.class)
                .map(Method::getName).sorted().toList();

        final List<String> bound = Arrays
                .stream(Introspector.getBeanInfo(ModelCapabilityConfig.class, Object.class).getPropertyDescriptors())
                .filter(property -> property.getReadMethod() != null && property.getWriteMethod() != null)
                .map(PropertyDescriptor::getName).sorted().toList();

        assertThat(declarable).as("the declaration's own key list, which is what the refusal message advertises")
                .isNotEmpty();
        assertThat(bound).as("llm.modelCapabilities.<model>.* — every key an operator can write here")
                .containsAll(declarable);
    }
}
