package at.aimon.cli.config;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CliConfig {
    @JsonProperty("llm")
    private LlmProviderConfig llmConfig;

    @JsonProperty("agent")
    private AgentConfig agentConfig;

    @JsonProperty("cli")
    private CliSettings cliSettings;

    @JsonProperty("mcp")
    private McpConfig mcpConfig;

    @JsonProperty("memory")
    private MemoryConfig memoryConfig;

    /** CliConfig를 생성한다. */
    public CliConfig() {
    }

    public LlmProviderConfig getLlmConfig() {
        return llmConfig;
    }

    public void setLlmConfig(LlmProviderConfig llmConfig) {
        this.llmConfig = llmConfig;
    }

    public AgentConfig getAgentConfig() {
        return agentConfig;
    }

    public void setAgentConfig(AgentConfig agentConfig) {
        this.agentConfig = agentConfig;
    }

    public CliSettings getCliSettings() {
        return cliSettings;
    }

    public void setCliSettings(CliSettings cliSettings) {
        this.cliSettings = cliSettings;
    }

    public McpConfig getMcpConfig() {
        return mcpConfig;
    }

    public void setMcpConfig(McpConfig mcpConfig) {
        this.mcpConfig = mcpConfig;
    }

    public MemoryConfig getMemoryConfig() {
        return memoryConfig;
    }

    public void setMemoryConfig(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CliConfig cliConfig = (CliConfig) o;
        return Objects.equals(llmConfig, cliConfig.llmConfig) && Objects.equals(agentConfig, cliConfig.agentConfig)
                && Objects.equals(cliSettings, cliConfig.cliSettings) && Objects.equals(mcpConfig, cliConfig.mcpConfig)
                && Objects.equals(memoryConfig, cliConfig.memoryConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(llmConfig, agentConfig, cliSettings, mcpConfig, memoryConfig);
    }

    @Override
    public String toString() {
        return "CliConfig{" + "llmConfig=" + llmConfig + ", agentConfig=" + agentConfig + ", cliSettings=" + cliSettings
                + ", mcpConfig=" + mcpConfig + ", memoryConfig=" + memoryConfig + '}';
    }
}
