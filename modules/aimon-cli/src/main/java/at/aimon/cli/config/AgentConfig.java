package at.aimon.cli.config;

import java.util.Objects;

public class AgentConfig {
    private String name = "default";

    /** AgentConfig를 생성한다. */
    public AgentConfig() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final AgentConfig that = (AgentConfig) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "AgentConfig{" + "name='" + name + '\'' + '}';
    }
}
