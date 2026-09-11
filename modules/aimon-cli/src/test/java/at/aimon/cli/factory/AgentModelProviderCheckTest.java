package at.aimon.cli.factory;

import static at.aimon.cli.factory.AgentModelProviderCheck.declaredModels;
import static at.aimon.cli.factory.AgentModelProviderCheck.endpointOf;
import static at.aimon.cli.factory.AgentModelProviderCheck.vendorOfModel;
import static at.aimon.cli.factory.AgentModelProviderCheck.vendorOfProvider;
import static at.aimon.cli.factory.AgentModelProviderCheck.warning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.factory.AgentModelProviderCheck.DeclaredModel;
import at.aimon.cli.factory.AgentModelProviderCheck.Endpoint;
import at.aimon.cli.factory.AgentModelProviderCheck.Origin;
import at.aimon.cli.factory.AgentModelProviderCheck.Vendor;
import at.aimon.core.agent.impl.AdaptiveAgentBundleLoader;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;

/**
 * The startup check behind #92: which names and endpoints it treats as a mismatch, what it says, and that the bundles
 * this CLI ships get the answer the configuration comment and the guide promise.
 *
 * <p>
 * Message tests assert fragments, never whole strings — the wording may be polished, but a path, a key or a remedy
 * appearing where it should not is the defect these exist to catch.
 */
@DisplayName("AgentModelProviderCheck")
class AgentModelProviderCheckTest {

    private static final String WORKING_DIRECTORY = "/work";

    private static LlmProviderConfig llm(String provider, String baseUrl) {
        final LlmProviderConfig config = new LlmProviderConfig();
        config.setProvider(provider);
        config.setBaseUrl(baseUrl);
        return config;
    }

    private static Optional<String> check(String provider, String baseUrl, String agentName, DeclaredModel... models) {
        return warning(llm(provider, baseUrl), agentName, WORKING_DIRECTORY, List.of(models));
    }

    private static String fired(String provider, String baseUrl, String agentName, DeclaredModel... models) {
        final Optional<String> message = check(provider, baseUrl, agentName, models);
        assertThat(message).as("the check should fire").isPresent();
        return message.orElseThrow();
    }

    private static DeclaredModel main(String model) {
        return DeclaredModel.mainAgent(model);
    }

    private static DeclaredModel bundled(String name, String model) {
        return DeclaredModel.subagent(name, model, Origin.BUNDLE);
    }

    private static DeclaredModel outside(String name, String model) {
        return DeclaredModel.subagent(name, model, Origin.OUTSIDE_BUNDLE);
    }

    @Nested
    @DisplayName("A. which vendor's family a model name belongs to")
    class Classification {

        /**
         * The row keys {@code InMemoryModelCapabilityRegistry.builderWithDefaults()} registers, copied by hand. That is
         * the limit of this grounding: a vendor row added to the table later is not seen here until it is added to one
         * of these lists, and a new family prefix would still have to be added to {@code FAMILY_PREFIXES}.
         */
        private static final List<String> BUILT_IN_OPENAI_ROW_KEYS = List.of("gpt-5-chat", "gpt-5", "gpt-5.6-terra",
                "o1", "o3", "o4", "o1-2024-12-17", "o3-2025-04-16", "o3-mini", "o3-mini-2025-01-31", "o4-mini",
                "o4-mini-2025-04-16");

        /** @see #BUILT_IN_OPENAI_ROW_KEYS */
        private static final List<String> BUILT_IN_ANTHROPIC_ROW_KEYS = List.of("claude-fable-5", "claude-opus-5",
                "claude-opus-4-7", "claude-opus-4-8", "claude-sonnet-5", "claude-mythos", "claude-opus-4-5",
                "claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-6", "claude-sonnet-4-6");

        @ParameterizedTest
        @ValueSource(strings = {"claude-sonnet-4-5", "CLAUDE-OPUS-5"})
        @DisplayName("claude-* names, in any case, are Anthropic's")
        void anthropicFamily(String modelName) {
            assertThat(vendorOfModel(modelName)).contains(Vendor.ANTHROPIC);
        }

        @ParameterizedTest
        @ValueSource(strings = {"gpt-5.6-terra", "gpt-5.1", "gpt-4o", "o1", "o3-mini", "o4-mini"})
        @DisplayName("gpt-* and o-series names are OpenAI's")
        void openAiFamily(String modelName) {
            assertThat(vendorOfModel(modelName)).contains(Vendor.OPENAI);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"haiku", "prod-assistant", "llama-3.1-70b"})
        @DisplayName("a name neither family claims belongs to nobody")
        void unclaimed(String modelName) {
            assertThat(vendorOfModel(modelName)).isEmpty();
        }

        @Test
        @DisplayName("every built-in capability row key is a row, and classifies to the vendor its block describes")
        void familiesAreTheCapabilityTablesPrefixes() {
            final InMemoryModelCapabilityRegistry table = InMemoryModelCapabilityRegistry.withDefaults();

            assertThat(BUILT_IN_ANTHROPIC_ROW_KEYS).hasSize(11).allSatisfy(key -> {
                assertThat(table.capabilitiesOf(key)).as(key).isPresent();
                assertThat(vendorOfModel(key)).as(key).contains(Vendor.ANTHROPIC);
            });
            assertThat(BUILT_IN_OPENAI_ROW_KEYS).allSatisfy(key -> {
                assertThat(table.capabilitiesOf(key)).as(key).isPresent();
                assertThat(vendorOfModel(key)).as(key).contains(Vendor.OPENAI);
            });
        }

        @ParameterizedTest
        @CsvSource({"openai, OPENAI", "OpenAI, OPENAI", "anthropic, ANTHROPIC", "ANTHROPIC, ANTHROPIC"})
        @DisplayName("llm.provider folds case, as LlmClientFactory does")
        void providerFoldsCase(String provider, Vendor expected) {
            assertThat(vendorOfProvider(provider)).contains(expected);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"gemini", " anthropic"})
        @DisplayName("any other provider string names no vendor")
        void unknownProvider(String provider) {
            assertThat(vendorOfProvider(provider)).isEmpty();
        }
    }

    @Nested
    @DisplayName("B. whether the endpoint cannot serve the other vendor's names")
    class EndpointGate {

        static Stream<Arguments> endpoints() {
            return Stream.of(arguments(Vendor.OPENAI, null, Endpoint.VENDOR_API),
                    arguments(Vendor.OPENAI, "", Endpoint.VENDOR_API),
                    arguments(Vendor.OPENAI, "https://api.openai.com/v1", Endpoint.VENDOR_API),
                    arguments(Vendor.OPENAI, "HTTPS://API.OPENAI.COM", Endpoint.VENDOR_API),
                    arguments(Vendor.OPENAI, "https://gw.internal/v1", Endpoint.UNKNOWN),
                    arguments(Vendor.OPENAI, "https://x.openai.azure.com", Endpoint.UNKNOWN),
                    // Anthropic serves an OpenAI-SDK-compatible endpoint on its own host, so this pairing stays silent.
                    arguments(Vendor.OPENAI, "https://api.anthropic.com/v1", Endpoint.UNKNOWN),
                    arguments(Vendor.ANTHROPIC, null, Endpoint.VENDOR_API),
                    arguments(Vendor.ANTHROPIC, "https://api.anthropic.com", Endpoint.VENDOR_API),
                    // What editing only `provider:` in the shipped default-config.yaml produces.
                    arguments(Vendor.ANTHROPIC, "https://api.openai.com/v1", Endpoint.WRONG_HOST),
                    arguments(Vendor.ANTHROPIC, "https://gw.internal", Endpoint.UNKNOWN));
        }

        @ParameterizedTest(name = "{0} + {1} -> {2}")
        @MethodSource("endpoints")
        @DisplayName("host alone decides, ignoring case, scheme, port and path")
        void endpoint(Vendor provider, String baseUrl, Endpoint expected) {
            assertThat(endpointOf(provider, baseUrl)).isEqualTo(expected);
        }

        @ParameterizedTest
        @EnumSource(Vendor.class)
        @DisplayName("a baseUrl that does not parse is UNKNOWN, not an exception")
        void unparseable(Vendor provider) {
            assertThatCode(() -> endpointOf(provider, "not a url ::")).doesNotThrowAnyException();
            assertThat(endpointOf(provider, "not a url ::")).isEqualTo(Endpoint.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("C. the message and its remedies")
    class Message {

        @Test
        @DisplayName("C1 acceptance: anthropic with the shipped default names both bundle files and offers the switch")
        void acceptanceCase() {
            final String message = fired("anthropic", null, "default", main("gpt-5.6-terra"),
                    bundled("explore", "gpt-5.1"));

            assertThat(message).contains("classpath", "agents/default/agent.md", "model.name: gpt-5.6-terra",
                    "agents/default/agents/explore.md", "model: gpt-5.1", "`agent.name: default-anthropic`",
                    "llm.model", "Or point `llm.baseUrl`").doesNotContain(".aimon/agents");
        }

        @Test
        @DisplayName("C2 review 1: a user subagent under a correct bundle gets its own file and no bundle switch")
        void userSubagentUnderTheRightBundle() {
            final String message = fired("anthropic", null, "default-anthropic", main("claude-sonnet-4-5"),
                    outside("reviewer", "gpt-4o"));

            assertThat(message).contains("/work/.aimon/agents/reviewer.md", "model: gpt-4o", "Change the key shown")
                    .doesNotContain("agent.name:", "model.name", "claude-sonnet-4-5", "classpath",
                            "where that bundle is built");
        }

        @Test
        @DisplayName("C3 a user file shadowing a bundled subagent is named, and the bundled file is not")
        void shadowingUserFile() {
            final String message = fired("anthropic", null, "default-anthropic", outside("explore", "gpt-5.1"));

            assertThat(message).contains("/work/.aimon/agents/explore.md")
                    .doesNotContain("agents/default-anthropic/agents/explore.md", "agent.name:");
        }

        @Test
        @DisplayName("C4 a mismatched bundled subagent under a correct main agent: edit or override, no switch")
        void bundledSubagentOnly() {
            final String message = fired("anthropic", null, "my-claude", main("claude-sonnet-4-5"),
                    bundled("helper", "gpt-5.1"));

            assertThat(message).contains("classpath", "agents/my-claude/agents/helper.md", "where that bundle is built",
                    "without rebuilding", "/work/.aimon/agents").doesNotContain("agent.name:");
        }

        @Test
        @DisplayName("C5 the switch is offered, and says it does not reach .aimon/agents")
        void switchPlusUserFile() {
            final String message = fired("anthropic", null, "default", main("gpt-5.6-terra"),
                    outside("reviewer", "gpt-4o"));

            assertThat(message)
                    .contains("`agent.name: default-anthropic`", "does not change them", "/work/.aimon/agents")
                    .doesNotContain("without rebuilding", "Change the key shown");
        }

        @Test
        @DisplayName("C6 the named bundle already configured: no switch, edit where the bundle is built")
        void editedProviderBundle() {
            final String message = fired("anthropic", null, "default-anthropic", main("gpt-5.1"));

            assertThat(message)
                    .contains("agents/default-anthropic/agent.md", "model.name: gpt-5.1", "where that bundle is built")
                    .doesNotContain("agent.name:", "without rebuilding");
        }

        @Test
        @DisplayName("C7 OpenAI's host under anthropic: the host sentence replaces the gateway one")
        void wrongHost() {
            final String message = fired("anthropic", "https://api.openai.com/v1", "default", main("gpt-5.6-terra"));

            assertThat(message).contains("`agent.name: default-anthropic`", "is OpenAI's host")
                    .doesNotContain("Or point `llm.baseUrl`");
        }

        // Named by index: one argument holds a NUL, which would otherwise land in the test's display name and in every
        // console log and report that prints it.
        @ParameterizedTest(name = "working directory #{index}")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "/work\0dir"})
        @DisplayName("C8 a working directory that is null, blank or not a path prints the relative location")
        void relativeFallback(String workingDirectory) {
            assertThatCode(() -> warning(llm("anthropic", null), "default-anthropic", workingDirectory,
                    List.of(outside("reviewer", "gpt-4o")))).doesNotThrowAnyException();

            assertThat(warning(llm("anthropic", null), "default-anthropic", workingDirectory,
                    List.of(outside("reviewer", "gpt-4o")))).get().asString().contains("`.aimon/agents/reviewer.md`");
        }

        @Test
        @DisplayName("C9 openai with an Anthropic main agent names `default`, not `default-anthropic`")
        void switchToDefault() {
            final String message = fired("openai", "https://api.openai.com/v1", "default-anthropic",
                    main("claude-sonnet-4-5"));

            assertThat(message).contains("`agent.name: default`").doesNotContain("`agent.name: default-anthropic`");
        }

        @Test
        @DisplayName("the provider string ANTHROPIC behaves as anthropic")
        void providerCase() {
            assertThat(fired("ANTHROPIC", null, "default", main("gpt-5.6-terra")))
                    .contains("`agent.name: default-anthropic`");
        }

        @Test
        @DisplayName("silent: matching pairs, gateways, Anthropic's host under openai, and names nobody claims")
        void silentCases() {
            assertThat(check("anthropic", null, "default-anthropic", main("claude-sonnet-4-5"),
                    bundled("explore", "haiku"))).isEmpty();
            assertThat(check("openai", null, "default", main("gpt-5.6-terra"))).isEmpty();
            assertThat(check("openai", "https://gw.internal/v1", "default", main("claude-sonnet-4-5"))).isEmpty();
            assertThat(check("openai", "https://api.anthropic.com/v1", "default", main("claude-sonnet-4-5"))).isEmpty();
            assertThat(check("openai", null, "custom", main("prod-assistant"))).isEmpty();
            assertThat(check("anthropic", null, "custom", main("prod-assistant"))).isEmpty();
        }

        @Test
        @DisplayName("silent: an unknown provider, no llm block, no entries")
        void silentWithoutInputs() {
            assertThat(check("gemini", null, "default", main("gpt-5.6-terra"))).isEmpty();
            assertThat(warning(null, "default", WORKING_DIRECTORY, List.of(main("gpt-5.6-terra")))).isEmpty();
            assertThat(check("anthropic", null, "default")).isEmpty();
        }
    }

    @Nested
    @DisplayName("D. the bundles this CLI ships, through the real loader")
    class ShippedBundles {

        private final AdaptiveAgentBundleLoader loader = new AdaptiveAgentBundleLoader("agents");

        /**
         * The bundle's own registry stands in for the runtime's: the no-user-file case, so every subagent is BUNDLE.
         */
        private List<DeclaredModel> modelsOf(String bundleName) {
            final AgentBundle bundle = loader.load(bundleName);
            return declaredModels(bundle.getAgent(), bundle.getSubagentRegistry(),
                    bundle.getSubagentRegistry().orElse(null));
        }

        private Optional<String> checkBundle(String bundleName, String provider) {
            return warning(llm(provider, null), bundleName, WORKING_DIRECTORY, modelsOf(bundleName));
        }

        @ParameterizedTest(name = "{0} under {1}: fires={2}")
        @CsvSource({"default, anthropic, true, `agent.name: default-anthropic`", "default, openai, false,",
                "default-anthropic, anthropic, false,", "default-anthropic, openai, true, `agent.name: default`",
                "default-openai, anthropic, true, `agent.name: default-anthropic`", "default-openai, openai, false,",
                "terra, anthropic, true, `agent.name: default-anthropic`", "terra, openai, false,",
                "ops-agent, anthropic, true, `agent.name: default-anthropic`", "ops-agent, openai, false,"})
        @DisplayName("each shipped bundle fires under the other provider only, offering the provider's bundle")
        void shippedBundle(String bundleName, String provider, boolean fires, String remedy) {
            final Optional<String> message = checkBundle(bundleName, provider);

            if (fires) {
                assertThat(message).get().asString().contains(remedy);
            } else {
                assertThat(message).isEmpty();
            }
        }

        @Test
        @DisplayName("the shipped default under anthropic names the main agent and its explore subagent")
        void shippedDefaultNamesBothFiles() {
            assertThat(checkBundle("default", "anthropic")).get().asString().contains("agents/default/agent.md",
                    "model.name: gpt-5.6-terra", "agents/default/agents/explore.md", "model: gpt-5.1")
                    .doesNotContain(".aimon/agents");
        }

        @Test
        @DisplayName("default-anthropic's explore names haiku, which neither family claims — silent by rule (L-17)")
        void haikuIsSilentByRule() {
            assertThat(modelsOf("default-anthropic")).extracting(DeclaredModel::getModelName)
                    .contains("claude-sonnet-4-5", "haiku");
            assertThat(checkBundle("default-anthropic", "anthropic")).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(Vendor.class)
        @DisplayName("the bundle the switch names yields no entries of the other vendor's family")
        void theSwitchTargetIsClean(Vendor provider) {
            final String target = AgentModelProviderCheck.BUNDLE_FOR.get(provider);

            assertThat(modelsOf(target)).as(target)
                    .noneMatch(model -> vendorOfModel(model.getModelName()).orElse(null) == provider.other());
            assertThat(checkBundle(target, provider.providerKey())).isEmpty();
        }
    }
}
