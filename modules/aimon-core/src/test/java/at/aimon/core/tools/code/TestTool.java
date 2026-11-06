package at.aimon.core.tools.code;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.ToolDefinition;

/** A configurable tool that records its calls. */
final class TestTool implements Tool {

    final String name;
    final SideEffectLevel level;
    final DestructiveBehavior destructive;
    final DataReach reach;
    final boolean codeCallable;
    final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();

    TestTool(String name, SideEffectLevel level, DestructiveBehavior destructive, DataReach reach,
            boolean codeCallable) {
        this.name = name;
        this.level = level;
        this.destructive = destructive;
        this.reach = reach;
        this.codeCallable = codeCallable;
    }

    static TestTool reader(String name) {
        return new TestTool(name, SideEffectLevel.READ_ONLY, DestructiveBehavior.DESTRUCTIVE, DataReach.ENVIRONMENT,
                true);
    }

    static TestTool writer(String name) {
        return new TestTool(name, SideEffectLevel.MUTATING, DestructiveBehavior.NON_DESTRUCTIVE, DataReach.HOST, true);
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.of(name, name + " does things.", Map.of("type", "object", "properties",
                Map.of("path", Map.of("type", "string", "description", "a path")), "required", List.of("path")));
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        return level;
    }

    @Override
    public DestructiveBehavior getDestructiveBehavior() {
        return destructive;
    }

    @Override
    public DataReach dataReach() {
        return reach;
    }

    @Override
    public boolean isCodeCallable() {
        return codeCallable;
    }

    @Override
    public ConcurrencyBehavior getConcurrencyBehavior() {
        return ConcurrencyBehavior.CONCURRENT_SAFE;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        calls.add(input.toMap());
        return ToolResult.success(name + ":" + input.toMap().getOrDefault("path", ""));
    }
}
