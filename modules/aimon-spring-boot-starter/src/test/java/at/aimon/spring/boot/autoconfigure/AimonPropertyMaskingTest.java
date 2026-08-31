package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint.ConfigurationPropertiesBeanDescriptor;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;
import org.springframework.boot.actuate.env.EnvironmentEndpoint.PropertyValueDescriptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What {@code /actuator/env} and {@code /actuator/configprops} print for {@code aimon.llm.api-key}.
 *
 * <p>
 * <b>Why the endpoints are built by hand here.</b> Boot's two auto-configurations are
 * {@code @ConditionalOnAvailableEndpoint}, which needs an exposure technology, and this runner is not a web
 * context — so asking for the endpoint beans would test the exposure machinery instead of the masking. The two
 * constructor calls below are copied from {@code EnvironmentEndpointAutoConfiguration} and
 * {@code ConfigurationPropertiesReportEndpointAutoConfiguration}: both collect every {@link SanitizingFunction}
 * bean through {@code ObjectProvider.orderedStream()} and hand the list to the endpoint. Whatever this context
 * publishes is therefore exactly what a real deployment's endpoints would apply.
 *
 * <p>
 * <b>Two opt-ins are needed before any of this is visible.</b> {@code show-values} defaults to
 * {@link Show#NEVER}, and at that setting the sanitizer replaces every value before consulting any function
 * (Boot's {@code Sanitizer#sanitize} returns the mask when {@code showUnsanitized} is false). The leak this class
 * is about needs an operator to expose the endpoint <em>and</em> to move {@code show-values} off its default —
 * at which point Boot 3.5 applies the published functions and nothing else, because it registers no default
 * sanitizing function of its own.
 */
class AimonPropertyMaskingTest {

    private static final String SECRET = "sk-test-must-not-be-printed";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
                    AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class,
                    AimonKnowledgeAutoConfiguration.class, AimonMemoryAutoConfiguration.class,
                    AimonObservabilityAutoConfiguration.class, AimonAutoConfiguration.class));

    private ApplicationContextRunner minimal(Path workspace) {
        return runner.withPropertyValues("aimon.workspace.root=" + workspace,
                AimonProperties.LLM_API_KEY + "=" + SECRET, "aimon.agent-defaults.default-agent=test-agent");
    }

    @Test
    @DisplayName("/env masks the API key even when the operator turned values on")
    void envMasksTheApiKey(@TempDir Path workspace) {
        minimal(workspace).run(ctx -> {
            final List<Object> shown = envValues(ctx, Show.ALWAYS, AimonProperties.LLM_API_KEY);

            // Present — this is not "the key is absent", which would hide a rename behind a passing test.
            assertThat(shown).isNotEmpty().doesNotContain(SECRET)
                    .allSatisfy(value -> assertThat(value).isEqualTo(SanitizableData.SANITIZED_VALUE));
        });
    }

    @Test
    @DisplayName("/configprops masks the API key too — a different key spelling reaches the same function")
    void configpropsMasksTheApiKey(@TempDir Path workspace) {
        // The two endpoints do not agree on how the key is spelled. /env reports the property name as its source
        // wrote it (aimon.llm.api-key), while /configprops walks the serialized bean and qualifies each field
        // with the prefix (aimon.llm.apiKey). A function written against one spelling passes half of this class.
        minimal(workspace).run(ctx -> {
            final Map<String, Object> llm = configpropsGroup(ctx, Show.ALWAYS, "llm");

            assertThat(llm).containsEntry("apiKey", SanitizableData.SANITIZED_VALUE);
        });
    }

    @Test
    @DisplayName("at the default show-values the value is hidden before any function is consulted")
    void theDefaultHidesEverything(@TempDir Path workspace) {
        // Why this item is a latent exposure rather than a live one: an untouched deployment prints nothing,
        // with or without the function below. Recorded so the severity claim in the backlog stays checkable.
        minimal(workspace).run(ctx -> assertThat(envValues(ctx, Show.NEVER, AimonProperties.LLM_API_KEY)).isNotEmpty()
                .allSatisfy(value -> assertThat(value).isEqualTo(SanitizableData.SANITIZED_VALUE)));
    }

    @Test
    @DisplayName("an application's own secrets are left exactly as Boot would have shown them")
    void applicationPropertiesAreNotTouched(@TempDir Path workspace) {
        // Deliberate scope. An operator who set show-values=ALWAYS asked to see values, and a starter that
        // silently masked the application's own properties would be answering a question it was not asked. The
        // starter owns the `aimon.` prefix and stops there.
        minimal(workspace).withPropertyValues("myapp.datasource.password=hunter2").run(ctx -> {
            assertThat(envValues(ctx, Show.ALWAYS, "myapp.datasource.password")).containsExactly("hunter2");
        });
    }

    @Test
    @DisplayName("an aimon limit that merely reads like a credential stays readable")
    void limitsAreNotMistakenForSecrets(@TempDir Path workspace) {
        // aimon.budget.max-tokens is why the rule matches suffixes rather than substrings — as does
        // aimon.memory.max-tokens, which is left out only because reaching it means satisfying the memory
        // backend's own validation chain. A masked ceiling helps nobody, and the operator who turned show-values
        // on did so to read numbers like this one.
        minimal(workspace).withPropertyValues("aimon.budget.max-tokens=4096")
                .run(ctx -> assertThat(envValues(ctx, Show.ALWAYS, "aimon.budget.max-tokens")).containsExactly("4096"));
    }

    @Test
    @DisplayName("a credential leaf nobody could have listed by name is masked anyway")
    void arbitraryCredentialLeavesAreMasked(@TempDir Path workspace) {
        // Why aimon.credentials.* is spelled in the plural. A name-based rule cannot enumerate the leaves of a
        // tree whose leaves are whatever a deployment calls them — username, pat, client-id, none of which end
        // in one of the four suffixes. What covers them is the other arm: Boot's word list has one substring,
        // "credentials", and the rule tests the whole key rather than its last segment, so the prefix carries
        // the masking down to every leaf beneath it. Spelled aimon.credential.* this test fails.
        minimal(workspace).withPropertyValues("aimon.credentials.jira.username=admin",
                "aimon.credentials.jira.password=hunter2", "aimon.credentials.github.pat=ghp_abc").run(ctx -> {
                    assertThat(envValues(ctx, Show.ALWAYS, "aimon.credentials.jira.username"))
                            .containsExactly(SanitizableData.SANITIZED_VALUE);
                    assertThat(envValues(ctx, Show.ALWAYS, "aimon.credentials.jira.password"))
                            .containsExactly(SanitizableData.SANITIZED_VALUE);
                    assertThat(envValues(ctx, Show.ALWAYS, "aimon.credentials.github.pat"))
                            .containsExactly(SanitizableData.SANITIZED_VALUE);
                });
    }

    @Test
    @DisplayName("/configprops masks the credential tree under its own spelling too")
    void configpropsMasksTheCredentialTree(@TempDir Path workspace) {
        // The endpoint walks the serialized bean, so the tree arrives as nested maps rather than as the flat
        // keys /env reports. Same question as the api-key case: does the qualified key the sanitizer is handed
        // still carry the prefix that does the work?
        minimal(workspace)
                .withPropertyValues("aimon.credentials.jira.username=admin", "aimon.credentials.jira.password=hunter2")
                .run(ctx -> {
                    final Map<String, Object> jira = credentialProfile(ctx, "jira");

                    assertThat(jira).containsEntry("username", SanitizableData.SANITIZED_VALUE)
                            .containsEntry("password", SanitizableData.SANITIZED_VALUE);
                });
    }

    @Test
    @DisplayName("without Actuator the branch backs off and the context still starts")
    void backsOffWithoutActuator(@TempDir Path workspace) {
        minimal(workspace).withClassLoader(new FilteredClassLoader(SanitizingFunction.class))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("an application's own function is added to ours, not substituted for it")
    void anApplicationsOwnFunctionComposes(@TempDir Path workspace) {
        // The reason the bean above backs off by name and not by type. Actuator runs every function it is given,
        // so an application registering one for its own properties is not saying anything about ours — and a
        // type-level @ConditionalOnMissingBean would have read it as if it were.
        minimal(workspace).withPropertyValues("myapp.datasource.password=hunter2")
                .withBean("myappSanitizingFunction", SanitizingFunction.class,
                        () -> data -> data.getLowerCaseKey().startsWith("myapp.") ? data.withSanitizedValue() : data)
                .run(ctx -> {
                    assertThat(ctx).getBeans(SanitizingFunction.class).hasSize(2);
                    assertThat(envValues(ctx, Show.ALWAYS, "myapp.datasource.password"))
                            .containsExactly(SanitizableData.SANITIZED_VALUE);
                    assertThat(envValues(ctx, Show.ALWAYS, AimonProperties.LLM_API_KEY))
                            .containsExactly(SanitizableData.SANITIZED_VALUE);
                });
    }

    @Test
    @DisplayName("an application can replace the function by name without losing its own")
    void anApplicationCanReplaceIt(@TempDir Path workspace) {
        minimal(workspace).withPropertyValues("myapp.datasource.password=hunter2")
                .withBean("aimonSanitizingFunction", SanitizingFunction.class,
                        () -> data -> data.getLowerCaseKey().startsWith("myapp.") ? data.withSanitizedValue() : data)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SanitizingFunction.class);
                    assertThat(envValues(ctx, Show.ALWAYS, "myapp.datasource.password"))
                            .containsExactly(SanitizableData.SANITIZED_VALUE);
                    assertThat(envValues(ctx, Show.ALWAYS, AimonProperties.LLM_API_KEY)).containsExactly(SECRET);
                });
    }

    private static List<SanitizingFunction> functions(AssertableApplicationContext ctx) {
        return ctx.getBeanProvider(SanitizingFunction.class).orderedStream().toList();
    }

    private static List<Object> envValues(AssertableApplicationContext ctx, Show show, String key) {
        final EnvironmentEndpoint endpoint = new EnvironmentEndpoint(ctx.getEnvironment(), functions(ctx), show);
        return endpoint.environment(null).getPropertySources().stream().map(source -> source.getProperties().get(key))
                .filter(Objects::nonNull).map(PropertyValueDescriptor::getValue).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> credentialProfile(AssertableApplicationContext ctx, String profile) {
        final Map<String, Object> credentials = configpropsGroup(ctx, Show.ALWAYS, "credentials");
        return (Map<String, Object>) credentials.get(profile);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configpropsGroup(AssertableApplicationContext ctx, Show show, String group) {
        final ConfigurationPropertiesReportEndpoint endpoint = new ConfigurationPropertiesReportEndpoint(functions(ctx),
                show);
        endpoint.setApplicationContext(ctx);

        final List<ConfigurationPropertiesBeanDescriptor> beans = new ArrayList<>();
        endpoint.configurationProperties().getContexts().values()
                .forEach(context -> beans.addAll(context.getBeans().values()));
        return beans.stream().filter(bean -> AimonProperties.PREFIX.equals(bean.getPrefix()))
                .map(bean -> (Map<String, Object>) bean.getProperties().get(group)).filter(Objects::nonNull).findFirst()
                .orElseThrow(() -> new AssertionError("no " + AimonProperties.PREFIX + "." + group + " group"));
    }
}
