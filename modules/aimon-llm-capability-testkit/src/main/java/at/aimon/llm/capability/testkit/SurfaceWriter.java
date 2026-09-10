package at.aimon.llm.capability.testkit;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Writes a probe value onto a configuration surface through its bean setter, converting it when the surface declares
 * the property differently from the builder.
 *
 * <p>
 * The conversions, first match wins:
 * <ul>
 * <li>the property already accepts the value — written as is
 * <li>a collection into a {@code List} or {@code Collection} property — copied into an {@code ArrayList}, in the
 * value's own order. This is the only one that fires today: both surfaces bind the ladder as a {@code List}, and the
 * builder takes a {@code Set}
 * <li>a collection into a {@code Set} property — as is
 * <li>an enum into a {@code String} property — its {@code name()}
 * <li>a collection of an enum into a collection of {@code String} — the names, in iteration order
 * <li>a {@code Boolean} or a number into a {@code String} property — {@code String.valueOf}
 * </ul>
 * Anything else fails, naming both types.
 *
 * <p>
 * The string rows exist because the reason once given for keeping two guards — one surface binds enums, the other
 * strings — would, if it ever became true, land exactly there. Measured when this module was written, both surfaces
 * declare the same type for every key.
 *
 * <p>
 * <strong>One way only.</strong> The expected declaration is always built from the unconverted value, so the
 * assertion stays in the builder's terms: if a surface held strings and its forwarding parsed them, the comparison
 * would still read {@code thinkingDialect=EITHER}, not {@code "EITHER"}. A conversion that also shaped the expectation
 * could launder a wrong answer into a pass.
 *
 * <p>
 * Assignability is judged on <em>generic</em> types. Raw classes would let an {@code EnumSet<E>} into a
 * {@code Set<String>} property as is — raw-assignable, and a {@code ClassCastException} later, inside the forwarding,
 * instead of the enum-to-name row above.
 */
public final class SurfaceWriter {

    private SurfaceWriter() {
    }

    /**
     * @param surfaceType
     *            the configuration surface's class
     * @param name
     *            the property name, which for a declarable key is the key itself
     * @return the bean property of that name, if the surface has one — whether or not it has a getter and a setter
     */
    public static Optional<PropertyDescriptor> property(Class<?> surfaceType, String name) {
        Objects.requireNonNull(surfaceType, "surfaceType");
        Objects.requireNonNull(name, "name");
        try {
            return Arrays.stream(Introspector.getBeanInfo(surfaceType, Object.class).getPropertyDescriptors())
                    .filter(descriptor -> descriptor.getName().equals(name)).findFirst();
        } catch (IntrospectionException e) {
            throw new IllegalStateException("Could not introspect " + surfaceType.getName(), e);
        }
    }

    /**
     * Writes {@code value} to the property {@code name} of {@code surface}.
     *
     * @param surface
     *            the configuration surface
     * @param name
     *            the property name
     * @param value
     *            the value, of the builder's type
     * @param valueType
     *            the builder's generic type for it, which the conversions are judged against
     * @throws AssertionError
     *             if the surface has no setter for the property, no conversion applies, or the setter refuses the
     *             value
     */
    public static void write(Object surface, String name, Object value, Type valueType) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(valueType, "valueType");
        final Method setter = property(surface.getClass(), name).map(PropertyDescriptor::getWriteMethod)
                .orElseThrow(() -> new AssertionError(surface.getClass().getName() + " has no setter for `" + name
                        + "`, so there is nowhere to write the probe value."));
        final Object converted = convert(name, value, valueType, setter.getGenericParameterTypes()[0]);
        setter.trySetAccessible();
        try {
            setter.invoke(surface, converted);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Could not call " + setter, e);
        } catch (InvocationTargetException e) {
            throw new AssertionError(surface.getClass().getName() + "#" + setter.getName() + " refused " + converted
                    + " — the surface rejected the probe value before any forwarding ran. If that is legitimate, the"
                    + " contract subclass can supply a pair it accepts by overriding valuesFor(\"" + name + "\").",
                    e.getCause());
        }
    }

    static Object convert(String name, Object value, Type valueType, Type propertyType) {
        final Class<?> propertyClass = rawClassOf(propertyType);
        if (propertyClass != null && Collection.class.isAssignableFrom(propertyClass)
                && value instanceof Collection<?> collection) {
            return convertCollection(name, collection, valueType, propertyType, propertyClass);
        }
        if (propertyType instanceof Class<?> target) {
            final Class<?> boxed = boxed(target);
            if (boxed.isInstance(value)) {
                return value;
            }
            if (boxed == String.class && value instanceof Enum<?> constant) {
                return constant.name();
            }
            if (boxed == String.class && (value instanceof Boolean || value instanceof Number)) {
                return String.valueOf(value);
            }
        }
        throw unwritable(name, valueType, propertyType);
    }

    static Class<?> boxed(Class<?> type) {
        return type.isPrimitive() ? MethodType.methodType(type).wrap().returnType() : type;
    }

    private static Object convertCollection(String name, Collection<?> value, Type valueType, Type propertyType,
            Class<?> propertyClass) {
        final Type from = elementTypeOf(valueType);
        final Type to = elementTypeOf(propertyType);
        final List<Object> elements = new ArrayList<>();
        final boolean renamed;
        if (isAssignable(to, from)) {
            elements.addAll(value);
            renamed = false;
        } else if (to == String.class && from instanceof Class<?> fromClass && fromClass.isEnum()) {
            value.forEach(element -> elements.add(((Enum<?>) element).name()));
            renamed = true;
        } else {
            throw unwritable(name, valueType, propertyType);
        }
        final Object converted;
        if (Set.class.isAssignableFrom(propertyClass)) {
            converted = !renamed && propertyClass.isInstance(value) ? value : new LinkedHashSet<>(elements);
        } else {
            converted = new ArrayList<>(elements);
        }
        if (!propertyClass.isInstance(converted)) {
            throw unwritable(name, valueType, propertyType);
        }
        return converted;
    }

    private static Type elementTypeOf(Type collectionType) {
        if (collectionType instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1) {
            final Type argument = parameterized.getActualTypeArguments()[0];
            return argument instanceof WildcardType wildcard ? wildcard.getUpperBounds()[0] : argument;
        }
        return Object.class;
    }

    private static boolean isAssignable(Type to, Type from) {
        if (to == Object.class) {
            return true;
        }
        if (to instanceof Class<?> target && from instanceof Class<?> source) {
            return boxed(target).isAssignableFrom(boxed(source));
        }
        return to.equals(from);
    }

    private static Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    private static AssertionError unwritable(String name, Type valueType, Type propertyType) {
        return new AssertionError("`" + name + "` is declared on the builder as " + valueType.getTypeName()
                + " and on the surface as " + propertyType.getTypeName() + ", and SurfaceWriter has no conversion"
                + " between the two, so the probe cannot write a value that means the same thing on both sides. Add"
                + " one to SurfaceWriter.convert — one way only: the expected declaration is always built from the"
                + " builder's value.");
    }
}
