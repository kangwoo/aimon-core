package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Asserts the metadata an IDE reads to offer completion for {@code aimon.*}.
 *
 * <p>
 * This file is a build output, not source, and that is exactly why it is worth a test. Every other guarantee in
 * this module is enforced by the compiler or by a context that fails to start; this one is enforced by an
 * annotation processor whose failure mode is to produce slightly less metadata and succeed. A selector that
 * stops offering its values is invisible until a user types the property by hand and gets it wrong.
 *
 * <p>
 * <b>Value candidates come from two different places, on purpose.</b> The enum selectors carry their candidates in
 * the property's {@code type}: the processor does <em>not</em> emit hints for enums, it records the class and the
 * IDE reads the constants off it. {@code aimon.llm.provider} is a {@code String} so a third party
 * can contribute a value this starter has never heard of, and it pays for that with hand-written hints in
 * {@code additional-spring-configuration-metadata.json}. {@link #everySelectorOffersItsValues()} asks both the
 * same question — what would the IDE offer? — and does not care which mechanism answered.
 *
 * <p>
 * <b>What is deliberately not asserted:</b> {@code description} and {@code defaultValue}. Both are read from
 * javadoc and field initialisers, which the processor can only see when it is handed the source. An incremental
 * build that recompiles nothing drops them from the regenerated entries, so asserting them would produce a test
 * that passes on CI and fails on a developer's second run.
 */
class AimonConfigurationMetadataTest {

    private static final String METADATA_RESOURCE = "META-INF/spring-configuration-metadata.json";

    private static final JsonNode METADATA = loadAimonMetadata();

    @Test
    @DisplayName("every selector offers the values it documents")
    void everySelectorOffersItsValues() {
        assertThat(valueCandidates(AimonProperties.SESSION_STORE)).containsExactlyInAnyOrder("in-memory", "postgres",
                "mongodb", "redis");
        assertThat(valueCandidates(AimonProperties.SESSION_MODE)).containsExactlyInAnyOrder("single-node",
                "distributed");
        assertThat(valueCandidates(AimonProperties.SKILL_APPROVAL_MODE)).containsExactlyInAnyOrder("deny", "allow-list",
                "suspend", "channel");
        assertThat(valueCandidates(AimonProperties.SCHEDULING_BACKEND)).containsExactlyInAnyOrder("none", "in-memory",
                "quartz");
        assertThat(valueCandidates(AimonProperties.TRACING_PAYLOAD_CAPTURE)).containsExactlyInAnyOrder("none", "full");
        assertThat(valueCandidates(AimonProperties.KNOWLEDGE_BACKEND)).containsExactlyInAnyOrder("none", "keyword",
                "supplied");
        assertThat(valueCandidates(AimonProperties.AGENT_RUNTIME_EVICTION)).containsExactlyInAnyOrder("idle", "never");
        assertThat(valueCandidates(AimonProperties.MEMORY_BACKEND)).containsExactlyInAnyOrder("none", "in-memory",
                "supplied");
        assertThat(valueCandidates(AimonProperties.MEMORY_PEER_MODE)).containsExactlyInAnyOrder("fixed", "caller");
        assertThat(valueCandidates(AimonProperties.MEMORY_REDACTION)).containsExactlyInAnyOrder("default", "strict",
                "none", "supplied");
        // The only selector whose enum this module does not declare — MemoryInjectionMode is a core type, and the
        // processor records it by name like any other. Worth an entry here for exactly that reason: nothing in
        // this module would notice a constant being added or renamed there.
        assertThat(valueCandidates(AimonProperties.MEMORY_INJECTION_MODE)).containsExactlyInAnyOrder("summary-only",
                "full");
        assertThat(valueCandidates(AimonProperties.LLM_PROVIDER)).containsExactlyInAnyOrder("anthropic", "openai",
                "none");
    }

    @Test
    @DisplayName("the hand-written provider hints survived the merge into the generated file")
    void additionalMetadataIsMerged() {
        // The processor locates the hand-written file by a path derived from the compiler's output directory. Get
        // that derivation wrong and it silently merges a stale copy — a value added to the source file is then
        // simply absent here, with a green build. Reached exactly that state once; hence this assertion.
        final JsonNode hint = hint(AimonProperties.LLM_PROVIDER);
        assertThat(hint).as("hint block for %s", AimonProperties.LLM_PROVIDER).isNotNull();
        assertThat(hint.path("values").findValuesAsText("description"))
                .allSatisfy(description -> assertThat(description).isNotBlank());
    }

    @Test
    @DisplayName("the provider selector stays open to values this starter has never heard of")
    void providerAcceptsUnlistedValues() {
        // "any" is what stops an IDE from marking a third party's provider name as invalid. Without it the hints
        // above would read as a closed set, which is the one thing this property is not.
        assertThat(hint(AimonProperties.LLM_PROVIDER).path("providers").findValuesAsText("name")).contains("any");
        assertThat(property(AimonProperties.LLM_PROVIDER).path("type").asText()).isEqualTo("java.lang.String");
    }

    @Test
    @DisplayName("only the provider needs hand-written hints")
    void enumSelectorsCarryTheirValuesInTheType() {
        // Locks the reason every other selector is an enum. If a hint block ever appears for one of them, either
        // the processor's behaviour changed or someone hand-wrote metadata that will now drift from the enum.
        final List<String> hinted = new ArrayList<>();
        METADATA.path("hints").forEach(hint -> hinted.add(hint.path("name").asText()));
        assertThat(hinted).containsExactly(AimonProperties.LLM_PROVIDER);
    }

    @Test
    @DisplayName("the tree is rooted where the binder is told to look")
    void everyPropertyIsUnderThePrefix() {
        final List<String> names = new ArrayList<>();
        METADATA.path("properties").forEach(property -> names.add(property.path("name").asText()));
        assertThat(names).isNotEmpty().allSatisfy(name -> assertThat(name).startsWith(AimonProperties.PREFIX + "."));
    }

    /**
     * Answers what an IDE would offer for a property, whichever mechanism supplies it.
     *
     * @param property
     *            the fully qualified property name
     * @return the value candidates, in the spelling a properties file uses
     */
    private static Set<String> valueCandidates(String property) {
        final JsonNode hint = hint(property);
        if (hint != null) {
            return new LinkedHashSet<>(hint.path("values").findValuesAsText("value"));
        }
        final String type = property(property).path("type").asText();
        final Set<String> values = new LinkedHashSet<>();
        for (Object constant : enumConstantsOf(type)) {
            values.add(AimonProperties.asPropertyValue((Enum<?>) constant));
        }
        return values;
    }

    private static Object[] enumConstantsOf(String type) {
        try {
            final Class<?> declared = Class.forName(type);
            assertThat(declared.isEnum()).as("%s is an enum", type).isTrue();
            return declared.getEnumConstants();
        } catch (ClassNotFoundException e) {
            throw new AssertionError("metadata names a type that is not on the test classpath: " + type, e);
        }
    }

    private static JsonNode property(String name) {
        for (JsonNode property : METADATA.path("properties")) {
            if (name.equals(property.path("name").asText())) {
                return property;
            }
        }
        throw new AssertionError("no metadata entry for property " + name);
    }

    private static JsonNode hint(String name) {
        for (JsonNode hint : METADATA.path("hints")) {
            if (name.equals(hint.path("name").asText())) {
                return hint;
            }
        }
        return null;
    }

    /**
     * Finds this module's generated metadata among all the copies on the classpath.
     *
     * <p>
     * Every Boot module ships a file at the same path, so the first match is almost certainly somebody else's.
     * The one that describes {@code aimon.*} is identified by its content rather than by a build path, which
     * keeps the test working whether it runs from a class directory or from the packaged jar.
     */
    private static JsonNode loadAimonMetadata() {
        try {
            final List<URL> candidates = Collections
                    .list(AimonConfigurationMetadataTest.class.getClassLoader().getResources(METADATA_RESOURCE));
            final ObjectMapper mapper = new ObjectMapper();
            for (URL url : candidates) {
                try (InputStream in = url.openStream()) {
                    final JsonNode metadata = mapper.readTree(in);
                    if (describesAimon(metadata)) {
                        return metadata;
                    }
                }
            }
            throw new AssertionError("no " + METADATA_RESOURCE + " describing " + AimonProperties.PREFIX
                    + ".* on the classpath (found " + candidates.size() + " unrelated copies). The configuration"
                    + " processor did not run — check that spring-boot-configuration-processor is still an"
                    + " annotationProcessor dependency of this module.");
        } catch (IOException e) {
            throw new AssertionError("could not read " + METADATA_RESOURCE, e);
        }
    }

    private static boolean describesAimon(JsonNode metadata) {
        for (JsonNode property : metadata.path("properties")) {
            if (property.path("name").asText().startsWith(AimonProperties.PREFIX + ".")) {
                return true;
            }
        }
        return false;
    }
}
