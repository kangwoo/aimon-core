package at.aimon.cli.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AgentConfig {
    private String name = "default";

    /**
     * Directories a symbolic link in the agent bundle's {@code skills/} directory may resolve into, besides that
     * directory itself (EE-35). Absolute paths; empty by default, so a link must stay inside {@code skills/}.
     */
    private List<String> allowedSkillLinkRoots = new ArrayList<>();

    /** AgentConfig를 생성한다. */
    public AgentConfig() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getAllowedSkillLinkRoots() {
        return allowedSkillLinkRoots;
    }

    public void setAllowedSkillLinkRoots(List<String> allowedSkillLinkRoots) {
        this.allowedSkillLinkRoots = (allowedSkillLinkRoots == null) ? new ArrayList<>() : allowedSkillLinkRoots;
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
        return Objects.equals(name, that.name) && Objects.equals(allowedSkillLinkRoots, that.allowedSkillLinkRoots);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, allowedSkillLinkRoots);
    }

    @Override
    public String toString() {
        return "AgentConfig{" + "name='" + name + '\'' + ", allowedSkillLinkRoots=" + allowedSkillLinkRoots + '}';
    }
}
