package at.aimon.cli.factory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.bootstrap.assemble.StackPaths;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.core.agent.Agent;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * Says, at startup, when a loaded agent definition names a model the configured provider's API cannot serve (#92).
 *
 * <p>
 * The model name each agent request carries comes from the agent definition, not from the {@code llm:} block: both
 * clients send {@code modelConfig.getName().orElse(config.getModel())}. So a user who switches {@code llm.provider}
 * and leaves {@code agent.name} alone keeps sending the other vendor's model names, and nothing said so. This class
 * computes that message; it never refuses — a custom or proxied model name has to start.
 *
 * <p>
 * <strong>Two gates, both required.</strong> Either one alone would false-alarm.
 * <ul>
 * <li><strong>The endpoint cannot serve the other vendor's names</strong> ({@link #endpointOf}): {@code llm.baseUrl}
 * is unset, is the provider's own public host, or — the one pairing with no legitimate reading — is OpenAI's host under
 * {@code provider: anthropic}. Any other host is a gateway, a proxy or Azure, and stays silent: the tree documents a
 * {@code claude-*} model served through {@code provider: openai} behind an OpenAI-compatible gateway as a legitimate
 * path.
 * <li><strong>The name belongs to the other vendor's family</strong> ({@link #vendorOfModel}). The families are the
 * vendor prefixes under which the built-in capability table
 * ({@link at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry}) names its rows. A name neither family claims —
 * {@code haiku}, {@code prod-assistant}, {@code llama-3.1-70b} — is silent, because "what a vendor serves" is not a
 * family and either vendor may ship a new one tomorrow, while "a vendor's API never serves the other vendor's family"
 * is certain.
 * </ul>
 *
 * <p>
 * <strong>Which file each entry names.</strong> The main agent always comes from the loaded bundle. A subagent is
 * attributed by instance identity alone: the runtime layers the bundle's own registry object, then a separate registry
 * that parses {@code .aimon/agents} into instances of its own, so a winner that <em>is</em> the bundle's instance is a
 * classpath file and anything else is a user file — including a user copy that keeps the bundled model. That rule
 * breaks the day a registry starts returning copies or the wiring wraps the bundle's registry;
 * {@code AgentSetupFactoryAgentModelCheckTest} builds the real stack to fail first when it does.
 *
 * <p>
 * Design, the alternatives rejected and where the implementation departed from it:
 * {@code docs/design/llm/provider-switch-agent-model-check.md}.
 */
final class AgentModelProviderCheck {

    /** The two vendors a CLI provider can name, with the key {@code llm.provider} carries. */
    enum Vendor {
        OPENAI("openai", "OpenAI"), ANTHROPIC("anthropic", "Anthropic");

        private final String providerKey;
        private final String displayName;

        Vendor(String providerKey, String displayName) {
            this.providerKey = providerKey;
            this.displayName = displayName;
        }

        String providerKey() {
            return providerKey;
        }

        String displayName() {
            return displayName;
        }

        Vendor other() {
            return this == OPENAI ? ANTHROPIC : OPENAI;
        }
    }

    /**
     * What {@code llm.baseUrl} says about who answers: the provider's own API (unset, or its public host), OpenAI's
     * host under {@code provider: anthropic}, or anything else — which silences the check.
     */
    enum Endpoint {
        VENDOR_API, WRONG_HOST, UNKNOWN
    }

    /** Where the runtime read a subagent definition from. Decided by instance identity alone. */
    enum Origin {
        BUNDLE, OUTSIDE_BUNDLE
    }

    /**
     * The prefixes the built-in capability rows are named under, per vendor: the eleven {@code claude-*} prefixes, and
     * {@code gpt-5-chat} / {@code gpt-5} / {@code gpt-5.6-terra} plus the {@code o1} / {@code o3} / {@code o4} rows and
     * their exact o-series names. Prefixes rather than the rows themselves, because a row look-up misses
     * {@code gpt-4o}, {@code gpt-4.1} and {@code claude-sonnet-4-20250514} — the last being {@code AnthropicConfig}'s
     * own default — and the registry carries no vendor to ask.
     */
    static final Map<Vendor, List<String>> FAMILY_PREFIXES = Map.of(Vendor.ANTHROPIC, List.of("claude-"), Vendor.OPENAI,
            List.of("gpt-", "o1", "o3", "o4"));

    /**
     * Each vendor's public API host. Neither vendor config exposes a default base-URL constant, so these are the SDK
     * defaults; OpenAI's is also the value the shipped {@code default-config.yaml} writes.
     */
    static final Map<Vendor, String> OWN_API_HOST = Map.of(Vendor.OPENAI, "api.openai.com", Vendor.ANTHROPIC,
            "api.anthropic.com");

    /** The bundled agent whose main model is the vendor's own — what the {@code agent.name} remedy names. */
    static final Map<Vendor, String> BUNDLE_FOR = Map.of(Vendor.OPENAI, "default", Vendor.ANTHROPIC,
            "default-anthropic");

    /** Where a bundle keeps its subagent definitions, under {@code agents/<name>/}. */
    private static final String BUNDLE_SUBAGENTS_DIRECTORY = "agents";

    private AgentModelProviderCheck() {
    }

    /**
     * The vendor whose family a model name belongs to, matched ignoring case as the capability registry matches.
     *
     * @param modelName
     *            the model name as written in a definition (may be null)
     * @return the vendor, or empty for a name neither family claims
     */
    static Optional<Vendor> vendorOfModel(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return Optional.empty();
        }
        final String lower = modelName.toLowerCase(Locale.ROOT);
        for (Map.Entry<Vendor, List<String>> family : FAMILY_PREFIXES.entrySet()) {
            if (family.getValue().stream().anyMatch(lower::startsWith)) {
                return Optional.of(family.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * The vendor {@code llm.provider} names, folding case as {@code LlmClientFactory} does.
     *
     * @param provider
     *            the configured provider (may be null)
     * @return the vendor, or empty for anything else
     */
    static Optional<Vendor> vendorOfProvider(String provider) {
        if (provider == null) {
            return Optional.empty();
        }
        final String key = provider.toLowerCase(Locale.ROOT);
        for (Vendor vendor : Vendor.values()) {
            if (vendor.providerKey().equals(key)) {
                return Optional.of(vendor);
            }
        }
        return Optional.empty();
    }

    /**
     * Gate A: whether the request goes to an API that cannot serve the other vendor's names.
     *
     * <p>
     * The comparison is on the host alone, ignoring case, scheme, port and path. {@code provider: openai} pointed at
     * Anthropic's host stays {@link Endpoint#UNKNOWN} on purpose: Anthropic serves an OpenAI-SDK-compatible endpoint
     * there, so that pairing can be a working configuration, and a missed warning is the cheaper mistake.
     *
     * @param provider
     *            the configured vendor (must not be null)
     * @param baseUrl
     *            {@code llm.baseUrl}; null or empty means the vendor's public host, as both clients read it
     * @return where the request goes; never throws on a value that does not parse
     */
    static Endpoint endpointOf(Vendor provider, String baseUrl) {
        Objects.requireNonNull(provider, "provider cannot be null");
        if (baseUrl == null || baseUrl.isEmpty()) {
            return Endpoint.VENDOR_API;
        }
        final String host = hostOf(baseUrl);
        if (host == null) {
            return Endpoint.UNKNOWN;
        }
        if (host.equals(OWN_API_HOST.get(provider))) {
            return Endpoint.VENDOR_API;
        }
        if (provider == Vendor.ANTHROPIC && host.equals(OWN_API_HOST.get(Vendor.OPENAI))) {
            return Endpoint.WRONG_HOST;
        }
        return Endpoint.UNKNOWN;
    }

    /**
     * One entry per model the runtime will send on its own: the main agent's {@code model.name}, and every subagent the
     * runtime's registry resolves that names a model of its own.
     *
     * <p>
     * A subagent without a model inherits the main agent's name, which is already an entry; a definition without
     * {@code model.name} sends {@code llm.model}, which is not this check's defect.
     *
     * @param agent
     *            the loaded bundle's main agent (must not be null)
     * @param bundleSubagents
     *            the loaded bundle's own subagent registry (must not be null; may be empty)
     * @param runtimeSubagents
     *            the runtime's composite registry — the winning definition per name; null means the main agent only
     * @return the entries, main agent first
     */
    static List<DeclaredModel> declaredModels(Agent agent, Optional<SubagentRegistry> bundleSubagents,
            SubagentRegistry runtimeSubagents) {
        Objects.requireNonNull(agent, "agent cannot be null");
        Objects.requireNonNull(bundleSubagents, "bundleSubagents cannot be null");
        final List<DeclaredModel> models = new ArrayList<>();
        agent.getMetadata().getModel().getName().filter(name -> !name.isBlank()).map(DeclaredModel::mainAgent)
                .ifPresent(models::add);
        if (runtimeSubagents != null) {
            for (Subagent subagent : runtimeSubagents.getAllSubagents()) {
                final String model = subagent.getMetadata().getModel();
                if (model != null && !model.isBlank()) {
                    models.add(DeclaredModel.subagent(subagent.getName(), model, originOf(subagent, bundleSubagents)));
                }
            }
        }
        return List.copyOf(models);
    }

    /**
     * The startup message for these entries, or empty when both gates do not pass for at least one of them.
     *
     * @param llm
     *            the {@code llm:} block
     * @param bundleBasePath
     *            the classpath root the caller's bundle loader reads bundles from, so a bundle file is printed where
     *            that loader found it
     * @param agentName
     *            the configured {@code agent.name} — not the definition's metadata name, which three bundles share
     * @param workingDirectory
     *            the runtime's working directory, which {@code .aimon/agents} resolves under; null, blank or not a path
     *            prints the relative location instead
     * @param models
     *            the entries from {@link #declaredModels}
     * @return the message, one header line, one line per entry and a line of remedies
     */
    static Optional<String> warning(LlmProviderConfig llm, String bundleBasePath, String agentName,
            String workingDirectory, List<DeclaredModel> models) {
        if (llm == null || models == null) {
            return Optional.empty();
        }
        final Optional<Vendor> configured = vendorOfProvider(llm.getProvider());
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        final Vendor provider = configured.get();
        final Endpoint endpoint = endpointOf(provider, llm.getBaseUrl());
        if (endpoint == Endpoint.UNKNOWN) {
            return Optional.empty();
        }
        final Vendor other = provider.other();
        final List<DeclaredModel> mismatched = models.stream()
                .filter(model -> vendorOfModel(model.getModelName()).orElse(null) == other).toList();
        if (mismatched.isEmpty()) {
            return Optional.empty();
        }
        final StringBuilder message = new StringBuilder("Agent model: `llm.provider` is `")
                .append(provider.providerKey()).append("`, but these definitions name ").append(other.displayName())
                .append(" models, which ").append(provider.displayName()).append("'s API does not serve:");
        for (DeclaredModel model : mismatched) {
            message.append("\n  - ").append(entryLine(model, bundleBasePath, agentName, workingDirectory));
        }
        message.append("\nEach request carries these names; `llm.model` does not replace them.");
        for (String remedy : remedies(provider, endpoint, agentName, workingDirectory, mismatched)) {
            message.append(' ').append(remedy);
        }
        return Optional.of(message.append(" Startup continues.").toString());
    }

    /**
     * Only the remedies that change something for these entries. Offering {@code agent.name} needs the main agent to
     * be an entry — when only a bundled subagent mismatches, the bundle is the user's choice and its main agent is
     * right — and needs {@code agent.name} not to be that bundle already. What the switch cannot reach,
     * {@code .aimon/agents}, is said separately. The host sentence replaces the gateway one because fixing the agent
     * alone would silence the warning while every request still failed on the host.
     */
    private static List<String> remedies(Vendor provider, Endpoint endpoint, String agentName, String workingDirectory,
            List<DeclaredModel> mismatched) {
        final List<String> sentences = new ArrayList<>();
        final String providerBundle = BUNDLE_FOR.get(provider);
        final boolean mainAgentMismatches = mismatched.stream().anyMatch(DeclaredModel::isMainAgent);
        if (mainAgentMismatches && !providerBundle.equals(agentName)) {
            sentences.add("Set `agent.name: " + providerBundle
                    + "`: it replaces the main agent and the subagents in its bundle.");
            if (mismatched.stream().anyMatch(model -> model.getOrigin() == Origin.OUTSIDE_BUNDLE)) {
                sentences.add("Files under `" + userSubagentDirectory(workingDirectory)
                        + "` load with every agent, so `agent.name` does not change them: edit those files.");
            }
        } else {
            sentences.add("Change the key shown on each line.");
            // The main agent's origin is always BUNDLE, so this covers it as well as a bundled subagent.
            if (mismatched.stream().anyMatch(model -> model.getOrigin() == Origin.BUNDLE)) {
                sentences.add("A classpath file is part of the agent bundle: edit it where that bundle is built.");
            }
            if (mismatched.stream().anyMatch(model -> !model.isMainAgent() && model.getOrigin() == Origin.BUNDLE)) {
                sentences.add("Or override a bundled subagent without rebuilding: a file of the same name in `"
                        + userSubagentDirectory(workingDirectory) + "` replaces it.");
            }
        }
        sentences.add(endpoint == Endpoint.WRONG_HOST
                ? "`llm.baseUrl` is OpenAI's host, which does not serve Anthropic's API: remove it, or point it at a"
                        + " gateway that serves these names."
                : "Or point `llm.baseUrl` at a gateway that serves these names.");
        return sentences;
    }

    private static String entryLine(DeclaredModel model, String bundleBasePath, String agentName,
            String workingDirectory) {
        final String bundleRoot = bundleBasePath + "/" + agentName;
        if (model.isMainAgent()) {
            return "main agent, classpath `" + bundleRoot + "/agent.md`: `model.name: " + model.getModelName() + "`";
        }
        final String location = model.getOrigin() == Origin.BUNDLE
                ? "classpath `" + bundleRoot + "/" + BUNDLE_SUBAGENTS_DIRECTORY + "/" + model.getSubagentName() + ".md`"
                : "`" + userSubagentFile(workingDirectory, model.getSubagentName()) + "`";
        return "subagent `" + model.getSubagentName() + "`, " + location + ": `model: " + model.getModelName() + "`";
    }

    private static Origin originOf(Subagent winner, Optional<SubagentRegistry> bundleSubagents) {
        // Identity, not equality: a user file that copies the bundled definition is equal to it and is still the file
        // the runtime read.
        return bundleSubagents.flatMap(registry -> registry.getSubagent(winner.getName()))
                .filter(bundled -> bundled == winner).isPresent() ? Origin.BUNDLE : Origin.OUTSIDE_BUNDLE;
    }

    /**
     * {@code <working directory>/.aimon/agents}, the directory the runtime's user layer resolved — or the relative
     * directory when the working directory is null, blank or not a path, so a bad path string never costs the warning.
     */
    private static Path userSubagentDirectory(String workingDirectory) {
        final Path relative = Path.of(StackPaths.AGENTS_DIRECTORY);
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return relative;
        }
        try {
            return Path.of(workingDirectory).resolve(relative);
        } catch (InvalidPathException e) {
            return relative;
        }
    }

    private static String userSubagentFile(String workingDirectory, String subagentName) {
        final Path directory = userSubagentDirectory(workingDirectory);
        try {
            return directory.resolve(subagentName + ".md").toString();
        } catch (InvalidPathException e) {
            return directory + "/" + subagentName + ".md";
        }
    }

    private static String hostOf(String baseUrl) {
        try {
            final String host = new URI(baseUrl).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * One model a definition names, and where it was read from. A carrier for one call chain rather than a domain
     * type, hence the package-private shape.
     */
    static final class DeclaredModel {

        private final boolean mainAgent;
        private final String subagentName;
        private final String modelName;
        private final Origin origin;

        DeclaredModel(boolean mainAgent, String subagentName, String modelName, Origin origin) {
            this.mainAgent = mainAgent;
            this.subagentName = subagentName;
            this.modelName = Objects.requireNonNull(modelName, "modelName cannot be null");
            this.origin = Objects.requireNonNull(origin, "origin cannot be null");
        }

        /** The main agent's {@code model.name}. It always comes from the loaded bundle. */
        static DeclaredModel mainAgent(String modelName) {
            return new DeclaredModel(true, null, modelName, Origin.BUNDLE);
        }

        /** A subagent's {@code model}, read from {@code origin}. */
        static DeclaredModel subagent(String subagentName, String modelName, Origin origin) {
            return new DeclaredModel(false, Objects.requireNonNull(subagentName, "subagentName cannot be null"),
                    modelName, origin);
        }

        boolean isMainAgent() {
            return mainAgent;
        }

        /** Null for the main agent. */
        String getSubagentName() {
            return subagentName;
        }

        String getModelName() {
            return modelName;
        }

        Origin getOrigin() {
            return origin;
        }
    }
}
