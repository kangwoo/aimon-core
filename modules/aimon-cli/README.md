# Aimon CLI

Command Line Interface for the Aimon AI Agent Framework.

## Overview

Aimon CLI provides a user-friendly command-line interface for interacting with AI agents, managing conversations, and executing AI-powered tasks.

## Features

- 🤖 Interactive chat sessions with AI agents
- 📋 Agent management and discovery
- 💬 Multiple agent types support
- 🔧 Extensible command system
- Conversation compaction (autocompact + manual `/compact`)

## Installation

### Prerequisites

- Java 17 or higher
- Gradle 8.0 or higher

### Build from Source

```bash
./gradlew :aimon-cli:build
```

### Create Distribution

```bash
./gradlew :aimon-cli:installDist
```

The distribution will be created in `build/install/aimon-cli/`.

## Usage

### Running with Gradle

```bash
./gradlew :aimon-cli:run --args="[COMMAND] [OPTIONS]"
```

### Basic Commands

#### Show Help

```bash
./gradlew :aimon-cli:run --args="--help"
```

#### Show Version

```bash
./gradlew :aimon-cli:run --args="--version"
```

### Chat Commands

#### Start Interactive Chat

```bash
./gradlew :aimon-cli:run --args="chat --interactive"
```

#### Send Single Message

```bash
./gradlew :aimon-cli:run --args="chat 'Hello, how are you?'"
```

#### Specify Agent Type

```bash
./gradlew :aimon-cli:run --args="chat --agent orca 'Explain how recursion works'"
```

#### Specify Model

```bash
./gradlew :aimon-cli:run --args="chat --model gpt-4o 'Write a haiku'"
```

### Agent Commands

#### List Available Agents

```bash
./gradlew :aimon-cli:run --args="agent list"
```

#### Show Agent Information

```bash
./gradlew :aimon-cli:run --args="agent info orca"
```

### Conversation Compaction

Long-running conversations are summarised before they exceed the model's context window. Compaction runs in two
modes — both are wired automatically by `OrcaAgentRuntimeFactory`, so the CLI ships with them enabled and no
extra configuration is required.

#### Autocompact (AUTO)

The framework's `DefaultCompactionGuard` is consulted before every LLM call. When the estimated token count crosses the
auto-compact threshold for the active model and there are no in-flight `tool_use` calls, the conversation is summarised
and the original messages are replaced with a boundary marker plus a single summary message. Thresholds are derived
from the `ModelContextLimits` registered in `InMemoryModelContextWindowRegistry.withDefaults()` —
`effectiveWindow = contextWindow − reservedOutputTokens` (default 20k), then
`autoCompact = effectiveWindow − 13k`, `warning = autoCompact − 20k`, `blocking = effectiveWindow − 3k`:

| Model family       | Context window | Auto-compact at | Warning at | Blocking at |
|--------------------|----------------|-----------------|------------|-------------|
| `gpt-4o*`          | 128k           | ~95k            | ~75k       | ~105k       |
| `claude-*` (4.x)   | 200k           | ~167k           | ~147k      | ~177k       |

`PostCompactHook`s run after each successful compaction. The framework ships `RecentFilesRestoreHook`, which re-attaches
the most recently `Read`-accessed files so the agent does not lose track of in-flight file context — register it from
your wiring code if you want that behaviour.

#### Manual compaction (`/compact`)

Type `/compact` in interactive chat to trigger a MANUAL compaction immediately. Optional advisory text is forwarded to
the summary LLM call:

```text
> /compact
> /compact focus on the design decisions, drop tool output
```

`PreCompactHook` blocks are downgraded to advisory warnings for MANUAL trigger (so a user can always force a
compaction), and the guard's circuit-breaker counter is reset on success.

#### Disabling or replacing

Compaction is wired via `OrcaAgentRuntimeFactory.doCreate(...)`. If you instantiate the context yourself and
omit `compactionEngine` / `compactionGuard`, both AUTO and `/compact` are silently disabled and the agent runs with the
pre-compaction behaviour. Replace either collaborator (or pass a custom `MessageStripper` / `SummaryPromptTemplate`)
through `DefaultCompactionEngine.withCollaborators(...)` for tighter control.

## Available Options

### Global Options

- `-v, --verbose` - Enable verbose output
- `-q, --quiet` - Suppress all output except errors
- `-h, --help` - Show help message
- `-V, --version` - Print version information

### Chat Options

- `-a, --agent=<agentType>` - Agent type to use (default: orca)
- `-m, --model=<model>` - LLM model to use (default: gpt-4o)
- `-i, --interactive` - Start interactive mode

## Development

### Project Structure

```
aimon-cli/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── at/aimon/cli/
│   │   │       ├── AimonCli.java          # Main entry point
│   │   │       └── command/
│   │   │           ├── ChatCommand.java    # Chat command
│   │   │           └── AgentCommand.java   # Agent management
│   │   └── resources/
│   │       └── logback.xml                 # Logging configuration
│   └── test/
│       └── java/
│           └── at/aimon/cli/
│               └── AimonCliTest.java       # CLI tests
├── build.gradle.kts                        # Build configuration
└── README.md                               # This file
```

### Running Tests

```bash
./gradlew :aimon-cli:test
```

### Building Fat JAR

```bash
./gradlew :aimon-cli:fatJar
```

The fat JAR will be created in `build/libs/aimon-cli-0.0.1-SNAPSHOT-all.jar`.

### Running Fat JAR

```bash
java -jar build/libs/aimon-cli-0.0.1-SNAPSHOT-all.jar --help
```

## Dependencies

- **aimon-core** - Core agent framework
- **aimon-llm-openai** - OpenAI LLM integration
- **picocli** - Command-line interface framework
- **slf4j-api** - Logging facade
- **logback-classic** - Logging implementation

## Configuration

Logging configuration can be customized in `src/main/resources/logback.xml`.

Logs are written to:
- Console (stdout)
- File: `logs/aimon-cli.log` (with 7-day rotation)

## Troubleshooting

### Java Version Issues

Ensure you're using Java 17 or higher:

```bash
java -version
```

### Build Issues

Clean and rebuild:

```bash
./gradlew clean :aimon-cli:build
```

## Future Enhancements

- [ ] Configuration file support
- [ ] Conversation history persistence
- [ ] Tool and skill management commands
- [ ] Environment variable configuration
- [ ] Shell completion scripts
- [ ] Interactive REPL mode

## License

See the main project LICENSE file.

## Contributing

Contributions are welcome! Please see the main project CONTRIBUTING guidelines.
