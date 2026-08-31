package at.aimon.cli;

import java.time.Duration;
import java.util.concurrent.Callable;

import org.fusesource.jansi.AnsiConsole;

import at.aimon.cli.budget.BudgetOptionParser;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliConfigLoader;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.exception.CliException;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.cli.factory.AgentSetupFactory;
import at.aimon.cli.repl.ReplSession;
import at.aimon.core.agent.budget.ExecutionBudget;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

// @formatter:off
@Command(name = "aimon",
        description = "Aimon CLI - Interactive AI Agent using ReAct pattern",
        mixinStandardHelpOptions = true,
        version = "Aimon CLI 0.1.0-SNAPSHOT")
// @formatter:on
public class AimonCli implements Callable<Integer> {

    @Option(names = {"-c",
            "--config"}, description = "Configuration file path (default: uses embedded default-config.yaml)")
    private String configPath;

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose logging")
    private boolean verbose;

    @Option(names = "--max-iterations", description = "Maximum ReAct iterations per agent execution (session default).")
    private String maxIterations;

    @Option(names = "--max-tokens", description = "Maximum cumulative tokens per agent execution (session default).")
    private String maxTokens;

    @Option(names = "--timeout", description = "Wall-clock timeout per agent execution (session default). "
            + "Accepts 500ms, 30s, 5m, 2h, or ISO-8601 (e.g. PT30S).")
    private String timeout;

    // PSTREAM-11: picocli `negatable = true` turns a single field into --streaming / --no-streaming.
    // Left as Boolean (boxed) rather than boolean so we can tell "user did not pass the flag" (null → defer to
    // CliSettings default, which is ON) from an explicit opt-out. Explicit ON is also honoured for clarity in scripts.
    @Option(names = "--streaming", negatable = true, description = "Enable token-level streaming of assistant text "
            + "(default: enabled). Use --no-streaming to force the legacy single-shot output path.")
    private Boolean streaming;

    /** CLI 애플리케이션의 진입점이다. */
    public static void main(String[] args) {
        // Initialize ANSI console for colored output
        AnsiConsole.systemInstall();

        try {
            int exitCode = new CommandLine(new AimonCli()).execute(args);
            System.exit(exitCode);
        } finally {
            AnsiConsole.systemUninstall();
        }
    }

    @Override
    public Integer call() {
        try {
            // Load configuration
            CliConfigLoader configLoader = new CliConfigLoader();
            CliConfig config = loadConfiguration(configLoader);

            // PSTREAM-11: apply CLI streaming flag over the config-file default before the agent setup reads it.
            // When --streaming / --no-streaming is omitted (streaming == null), CliSettings.isStreaming() keeps
            // whatever the config file set (or the default ON). When supplied, the CLI value wins.
            //
            // This mutation MUST happen before AgentSetupFactory.create(config) below, because the factory wires
            // CliSettings into both the OutputFormatter (by reference) and the OrcaAgentExecutor (by value, via
            // withUseStreaming). Post-construction mutations of CliSettings would silently change OutputFormatter
            // behaviour at runtime while leaving the executor configuration stale. Treat CliSettings as effectively
            // frozen once create() returns.
            if (streaming != null) {
                config.getCliSettings().setStreaming(streaming);
            }

            // Build initial session budget from CLI options (null if none supplied → legacy behaviour)
            ExecutionBudget initialBudget = buildInitialBudget();

            // SECURITY: config.toString() must never include apiKey
            if (verbose) {
                System.out.println("Configuration loaded: " + config);
                if (initialBudget != null) {
                    System.out.println("Initial session budget: " + initialBudget);
                }
            }

            // Create Agent Setup (AutoCloseable — ensures scheduling engine and context are cleaned up)
            AgentSetupFactory agentFactory = new AgentSetupFactory();
            try (AgentSetupFactory.AgentSetup agentSetup = agentFactory.create(config)) {
                if (verbose) {
                    System.out.println("Agent created: " + agentSetup.getAgentExecutor());
                }

                // Start REPL session
                CliSettings cliSettings = config.getCliSettings();
                if (cliSettings.getPrompt() == null || cliSettings.getPrompt().trim().isEmpty()) {
                    cliSettings.setPrompt(agentSetup.getAgent().getName() + "> ");
                }
                ReplSession replSession = new ReplSession(agentSetup, cliSettings, initialBudget);
                replSession.start();
            }

            return 0;

        } catch (ConfigurationException e) {
            System.err.println("Configuration error: " + e.getMessage());
            if (verbose && e.getCause() != null) {
                e.getCause().printStackTrace();
            }
            return 1;

        } catch (CliException e) {
            System.err.println("CLI error: " + e.getMessage());
            if (verbose && e.getCause() != null) {
                e.getCause().printStackTrace();
            }
            return 1;

        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private CliConfig loadConfiguration(CliConfigLoader configLoader) {
        if (configPath == null || configPath.trim().isEmpty()) {
            if (verbose) {
                System.out.println("No config file specified, using default configuration");
            }
            return configLoader.loadDefault();
        } else {
            if (verbose) {
                System.out.println("Loading configuration from: " + configPath);
            }
            return configLoader.load(configPath);
        }
    }

    /**
     * PSTREAM-11: exposes the parsed --streaming / --no-streaming flag for unit-test coverage without invoking the
     * full {@link #call()} path. Returns {@code null} when the user did not supply the flag — callers must treat that
     * as "defer to {@link at.aimon.cli.config.CliSettings#isStreaming()}".
     *
     * <p>
     * Package-private by design.
     */
    Boolean streamingOverride() {
        return streaming;
    }

    /**
     * Builds the session default {@link ExecutionBudget} from CLI options.
     *
     * <p>
     * Returns {@code null} when no budget-related option was supplied — this preserves the pre-BUDGET-05 behaviour of
     * sending requests with no budget attached.
     *
     * <p>
     * Package-private for direct unit-test coverage without invoking picocli.
     */
    ExecutionBudget buildInitialBudget() {
        final boolean hasIterations = maxIterations != null && !maxIterations.isBlank();
        final boolean hasTokens = maxTokens != null && !maxTokens.isBlank();
        final boolean hasTimeout = timeout != null && !timeout.isBlank();
        if (!hasIterations && !hasTokens && !hasTimeout) {
            return null;
        }
        ExecutionBudget.Builder builder = ExecutionBudget.builder();
        if (hasIterations) {
            try {
                builder.maxIterations(BudgetOptionParser.parsePositiveInt(maxIterations, "--max-iterations"));
            } catch (IllegalArgumentException e) {
                throw new CliException(e.getMessage(), e);
            }
        }
        if (hasTokens) {
            try {
                builder.maxTokens(BudgetOptionParser.parsePositiveInt(maxTokens, "--max-tokens"));
            } catch (IllegalArgumentException e) {
                throw new CliException(e.getMessage(), e);
            }
        }
        if (hasTimeout) {
            try {
                Duration duration = BudgetOptionParser.parseDuration(timeout, "--timeout");
                builder.maxWallClockDuration(duration);
            } catch (IllegalArgumentException e) {
                throw new CliException(e.getMessage(), e);
            }
        }
        return builder.build();
    }
}
