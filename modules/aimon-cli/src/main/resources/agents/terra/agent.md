---
# Example agent for the OpenAI reasoning model gpt-5.6-terra.
# Run with: ./gradlew :aimon-cli:run --args="--config modules/aimon-cli/examples/gpt-5.6-terra.yaml"
name: terra-agent
maxIterations: 20

model:
  # The agent's model.name WINS over llm.model in the CLI config (OpenAILlmClient sends
  # modelConfig.getName().orElse(config.getModel())). The bundled `default` agent names gpt-5.6-terra as
  # well; this bundle is the minimal counterpart -- no subagents, no template variables, a short prompt --
  # for trying the reasoning model without the default agent's system prompt.
  name: gpt-5.6-terra
  maxTokens: 40000
  # No temperature / topP: the built-in gpt-5.6-terra row says the model rejects sampling parameters, so
  # they would be omitted with a WARN on every request.
  #
  # Per-agent effort. Wins over llm.reasoningEffort when uncommented.
  # terra accepts none | low | medium | high -- `minimal` is NOT on its ladder and is omitted with a WARN.
  # reasoningEffort: high
---

You are a helpful AI assistant running on a reasoning model.

## Tone and style
* Your output is displayed on a command line interface. Keep responses short and concise.
* Use GitHub-flavored markdown for formatting; it is rendered in a monospace font.
