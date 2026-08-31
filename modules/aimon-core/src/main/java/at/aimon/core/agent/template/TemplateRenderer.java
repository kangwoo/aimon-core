package at.aimon.core.agent.template;

import java.util.Map;

public interface TemplateRenderer {
    /** 템플릿을 변수로 렌더링한다. */
    String render(String template, Map<String, Object> variables);

    /** 템플릿을 기본 변수와 오버라이드 변수로 렌더링한다. */
    String render(String template, Map<String, Object> defaultVariables, Map<String, Object> overrideVariables);
}
