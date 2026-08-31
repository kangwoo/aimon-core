package at.aimon.core.hook.execution;

import java.util.List;

public interface HookExecutor {

    /** 주어진 정책에 따라 훅 목록을 실행한다. */
    <C extends HookContext> List<HookResult> execute(List<? extends ExecutionHook<C>> hooks, C context,
            HookExecutionPolicy policy);

}
