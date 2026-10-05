package at.aimon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContextKey;

/**
 * EE-32: the write-once names of the framework's own keys must be known to {@link ToolContextKey} without
 * {@link ToolContextKeys} having been initialised, or a string write that happens first is never checked.
 */
@DisplayName("ToolContextKeys write-once names (EE-32)")
class ToolContextKeysWriteOnceTest {

    @Test
    @DisplayName("every write-once key in ToolContextKeys is write-once before ToolContextKeys is initialised")
    void writeOnceWithoutInitialisingToolContextKeys() throws Exception {
        final List<String> names = writeOnceNames();
        assertThat(names).contains("executionEnvironment", "executionEnvironmentProvider", "hookRegistry");

        try (IsolatedLoader isolated = new IsolatedLoader(testClasspath())) {
            final Class<?> keyClass = Class.forName(ToolContextKey.class.getName(), true, isolated);
            final Method isWriteOnceName = keyClass.getMethod("isWriteOnceName", String.class);
            for (String name : names) {
                assertThat((boolean) isWriteOnceName.invoke(null, name)).as(name).isTrue();
            }

            // and a builder in that fresh world rejects the second string write
            final Class<?> contextClass = Class.forName("at.aimon.core.agent.tool.ToolContext", true, isolated);
            final Object builder = contextClass.getMethod("builder").invoke(null);
            final Method put = builder.getClass().getMethod("put", String.class, Object.class);
            put.invoke(builder, "hookRegistry", "first");
            final Throwable second = secondWrite(put, builder);
            assertThat(second).isInstanceOf(IllegalStateException.class).hasMessageContaining("write-once");

            // the check above must not have pulled ToolContextKeys in
            assertThat(isolated.hasLoaded(ToolContextKey.class.getName())).as("the probe can see a load").isTrue();
            assertThat(isolated.hasLoaded(ToolContextKeys.class.getName())).isFalse();
        }
    }

    private static Throwable secondWrite(Method put, Object builder) throws IllegalAccessException {
        try {
            put.invoke(builder, "hookRegistry", "second");
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    /** A loader with nothing of this test's world in it, which can say whether it has loaded a class. */
    private static final class IsolatedLoader extends URLClassLoader {

        IsolatedLoader(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        boolean hasLoaded(String className) {
            return findLoadedClass(className) != null;
        }
    }

    private static List<String> writeOnceNames() throws IllegalAccessException {
        final List<String> names = new ArrayList<>();
        for (Field field : ToolContextKeys.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ToolContextKey.class) {
                final ToolContextKey<?> key = (ToolContextKey<?>) field.get(null);
                if (key.isWriteOnce()) {
                    names.add(key.name());
                }
            }
        }
        return names;
    }

    private static URL[] testClasspath() throws MalformedURLException {
        final String[] entries = System.getProperty("java.class.path").split(File.pathSeparator);
        final URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = new File(entries[i]).toURI().toURL();
        }
        return urls;
    }
}
