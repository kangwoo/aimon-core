package at.aimon.llm.capability.testkit;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Two distinct values for a declarable key, synthesised from its setter's parameter type.
 *
 * <p>
 * <strong>Two, not one.</strong> One value shows a key arrived. A forwarding that ignores the surface and writes a
 * constant arrives too, whenever the constant is the value the probe happened to guess — so one value cannot tell a
 * value that is carried from one that is hard-coded, and "carried" is the property under test.
 *
 * <p>
 * The table is small and explicit on purpose:
 * <ul>
 * <li>{@code Boolean} / {@code boolean} — {@code true}, then {@code false}
 * <li>an enum — its first two constants. For {@code ThinkingDialect} one of them is {@code UNKNOWN}, which is a
 * legitimate declared value: declaring it is not the same as omitting the key
 * <li>a {@code Set}, {@code Collection} or {@code List} of an enum — one constant, then two. Never empty, because the
 * declaration itself refuses an empty ladder
 * <li>{@code String} — two fixed strings
 * <li>{@code Integer} / {@code int}, {@code Long} / {@code long} — {@code 1}, then {@code 2}
 * </ul>
 *
 * <p>
 * A type that is not in the table fails, naming the key, the type and this class as the place to extend, and so does a
 * type that cannot yield two distinct values. Neither is ever skipped: a guard that passes over the key it cannot
 * handle is the defect it exists to catch, wearing a different hat.
 */
public final class ProbeValues {

    private ProbeValues() {
    }

    /**
     * @param key
     *            a declarable key
     * @return two distinct values of the type the key's builder setter takes
     * @throws AssertionError
     *             if that type is not in the table, or has no two distinct values
     */
    public static List<Object> distinctPairFor(String key) {
        return pairFor(key, DeclarableKeys.parameterTypeOf(key));
    }

    /**
     * The table itself, over any type, so the loud failures can be shown on types no key has today.
     */
    static List<Object> pairFor(String key, Type type) {
        if (type == Boolean.class || type == boolean.class) {
            return List.of(Boolean.TRUE, Boolean.FALSE);
        }
        if (type == String.class) {
            return List.of("aimon-probe-a", "aimon-probe-b");
        }
        if (type == Integer.class || type == int.class) {
            return List.of(1, 2);
        }
        if (type == Long.class || type == long.class) {
            return List.of(1L, 2L);
        }
        if (type instanceof Class<?> enumType && enumType.isEnum()) {
            final Enum<?>[] constants = firstTwoConstants(key, type, enumType);
            return List.of(constants[0], constants[1]);
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> elementType && elementType.isEnum()) {
            final Type container = parameterized.getRawType();
            if (container == Set.class || container == Collection.class) {
                final Enum<?>[] constants = firstTwoConstants(key, type, elementType);
                return enumSets(constants[0], constants[1]);
            }
            if (container == List.class) {
                final Enum<?>[] constants = firstTwoConstants(key, type, elementType);
                return List.of(List.of(constants[0]), List.of(constants[0], constants[1]));
            }
        }
        throw new AssertionError("`" + key + "` is declared on ModelCapabilityDeclaration.Builder as "
                + type.getTypeName() + ", and ProbeValues has no row for that type, so the contract cannot synthesise"
                + " a value to write for it. Add a row to ProbeValues.pairFor returning two distinct values of "
                + type.getTypeName() + ". The key is not skipped: a key the guard cannot probe is a key it does not"
                + " guard.");
    }

    private static Enum<?>[] firstTwoConstants(String key, Type declared, Class<?> enumType) {
        final Enum<?>[] constants = (Enum<?>[]) enumType.getEnumConstants();
        if (constants.length < 2) {
            throw new AssertionError("`" + key + "` is declared as " + declared.getTypeName() + ", and "
                    + enumType.getName() + " has " + constants.length + " constant(s), so there are no two distinct"
                    + " values to probe it with. Two are needed: with one, a forwarding that writes a hard-coded"
                    + " constant instead of reading the surface passes whenever it hard-codes the one the probe"
                    + " picked. How such a key is probed has to be decided here, in the contract — it is not skipped.");
        }
        return constants;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> enumSets(Enum<?> first, Enum<?> second) {
        return List.of(EnumSet.of((Enum) first), EnumSet.of((Enum) first, (Enum) second));
    }
}
