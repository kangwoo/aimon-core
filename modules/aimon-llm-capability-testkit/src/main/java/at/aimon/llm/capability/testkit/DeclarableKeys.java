package at.aimon.llm.capability.testkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import at.aimon.core.llm.capability.ModelCapabilityDeclaration;

/**
 * The keys a configuration surface can declare, read off {@link ModelCapabilityDeclaration.Builder} rather than
 * listed.
 *
 * <p>
 * A key is a public single-argument method on the builder that returns the builder — the filter #69's two guards used,
 * so the list is the one {@link ModelCapabilityDeclaration.Builder#build()}'s refusal message advertises. Nothing in
 * this module keeps a list of keys by hand, which is what makes a ninth key fail a build without anybody editing a
 * test.
 *
 * <p>
 * Two ways the filter can go wrong are refused rather than tolerated. Two setters sharing a name would make the key
 * ambiguous, and picking one would leave the other untested with nothing said. A filter that matches nothing — the
 * builder's setters changed their return type, say — would hand every parameterized case zero arguments. JUnit 5.12
 * already fails a {@code @ParameterizedTest} that receives none ({@code allowZeroInvocations} defaults to
 * {@code false}), and that default is what kept #69's {@code isNotEmpty()} assertion alive when discovery moved into a
 * {@code @MethodSource}. {@link #names()} refuses it as well, so the protection does not rest on an annotation
 * attribute someone can set.
 *
 * <p>
 * Named so it cannot be read as the CLI's {@code declarationOf}.
 */
public final class DeclarableKeys {

    private DeclarableKeys() {
    }

    /**
     * Every declarable key, sorted by name — the {@code @MethodSource} of both checks in
     * {@link AbstractModelCapabilityBindingContractTest}.
     *
     * @return the key names, never empty
     * @throws IllegalStateException
     *             if discovery finds no key, or finds two setters sharing a name
     */
    public static List<String> names() {
        return namesOf(ModelCapabilityDeclaration.Builder.class);
    }

    /**
     * @param key
     *            a declarable key
     * @return its setter on {@link ModelCapabilityDeclaration.Builder}
     * @throws IllegalArgumentException
     *             if the builder declares no such key
     */
    public static Method setterFor(String key) {
        Objects.requireNonNull(key, "key");
        return settersOf(ModelCapabilityDeclaration.Builder.class).stream()
                .filter(method -> method.getName().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "ModelCapabilityDeclaration.Builder declares no key `" + key + "`; its keys are " + names()));
    }

    /**
     * @param key
     *            a declarable key
     * @return the generic parameter type of its setter — {@code Set<ReasoningEffort>}, not {@code Set}
     */
    public static Type parameterTypeOf(String key) {
        return setterFor(key).getGenericParameterTypes()[0];
    }

    /**
     * The declaration a surface has to produce when {@code key} is the only thing written on it: the builder with
     * exactly that one setter called, and nothing else.
     *
     * @param key
     *            a declarable key
     * @param value
     *            the value written, of the setter's own type — never a surface-side conversion of it
     * @return the expected declaration
     * @throws IllegalArgumentException
     *             if the declaration itself refuses {@code value}
     */
    public static ModelCapabilityDeclaration expectedDeclaration(String key, Object value) {
        final ModelCapabilityDeclaration.Builder builder = ModelCapabilityDeclaration.builder();
        try {
            setterFor(key).invoke(builder, value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not call ModelCapabilityDeclaration.Builder#" + key, e);
        }
        return builder.build();
    }

    /**
     * Discovery over any builder type, so the two refusals can be shown on a builder that has the defect.
     */
    static List<String> namesOf(Class<?> builderType) {
        final List<Method> setters = settersOf(builderType);
        if (setters.isEmpty()) {
            throw new IllegalStateException(builderType.getName() + " declares no public single-argument method"
                    + " returning itself, so discovery found zero keys. A contract over zero keys passes and checks"
                    + " nothing, so that is refused; if the builder changed shape, DeclarableKeys has to change with"
                    + " it.");
        }
        final List<String> overloaded = setters.stream()
                .collect(Collectors.groupingBy(Method::getName, Collectors.counting())).entrySet().stream()
                .filter(entry -> entry.getValue() > 1).map(Map.Entry::getKey).sorted().toList();
        if (!overloaded.isEmpty()) {
            throw new IllegalStateException(builderType.getName() + " declares more than one single-argument setter"
                    + " named " + overloaded + ". A key has to name exactly one setter: with two, the probe would"
                    + " exercise one and say nothing about the other.");
        }
        return setters.stream().map(Method::getName).sorted().toList();
    }

    private static List<Method> settersOf(Class<?> builderType) {
        return Arrays.stream(builderType.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getReturnType() == builderType).toList();
    }
}
