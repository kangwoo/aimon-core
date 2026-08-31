package at.aimon.core.command.execution;

import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.execution.ToolExecutionContext;
import at.aimon.core.agent.tool.execution.ToolExecutionRequest;
import at.aimon.core.agent.tool.execution.ToolExecutionResult;
import at.aimon.core.agent.tool.execution.ToolExecutor;

/** Mock implementation of {@link ToolExecutor} for testing. */
public class MockToolExecutor implements ToolExecutor {
    private String defaultResult = "Tool executed successfully";
    private boolean shouldFail = false;
    private String errorMessage = "Tool execution failed";

    @Override
    public ToolExecutionResult execute(ToolExecutionContext executionContext, ToolExecutionRequest request) {
        if (shouldFail) {
            return ToolExecutionResult.of(request.getId(), ToolResult.error(errorMessage));
        }
        return ToolExecutionResult.of(request.getId(), ToolResult.success(defaultResult));
    }

    public void setDefaultResult(String result) {
        this.defaultResult = result;
    }

    public void setShouldFail(boolean shouldFail) {
        this.shouldFail = shouldFail;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
