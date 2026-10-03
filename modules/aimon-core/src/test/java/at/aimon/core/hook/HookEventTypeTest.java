package at.aimon.core.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PermissionDeniedContext;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.skill.hook.SkillHookSet;

@DisplayName("HookEventType: which events fire inside an execution")
class HookEventTypeTest {

    private static final List<HookEventType<?>> INSIDE = List.of(HookEventType.PRE_TOOL, HookEventType.POST_TOOL,
            HookEventType.ON_START, HookEventType.ON_STOP, HookEventType.PRE_COMPACT, HookEventType.POST_COMPACT,
            HookEventType.PERMISSION_REQUEST, HookEventType.PERMISSION_DENIED, HookEventType.SUBAGENT_START,
            HookEventType.SUBAGENT_STOP);

    private static final List<HookEventType<?>> OUTSIDE = List.of(HookEventType.ON_SESSION_START,
            HookEventType.ON_SESSION_END, HookEventType.ON_CONFIG_RELOAD);

    @Test
    @DisplayName("ten events fire inside an execution; the session and config lifecycle events do not")
    void classificationIsPinned() {
        // Pinned on purpose: this flag decides whether a skill may put a shell action on the event at all. Moving
        // an event across the line is a decision, not a refactor — recount its firing sites first.
        assertThat(HookEventType.values()).hasSize(INSIDE.size() + OUTSIDE.size());
        assertThat(INSIDE).allMatch(HookEventType::firesInsideExecution);
        assertThat(OUTSIDE).noneMatch(HookEventType::firesInsideExecution);
    }

    @Test
    @DisplayName("every event a skill can declare fires inside an execution")
    void skillDeclarableEventsAllFireInsideAnExecution() {
        assertThat(SkillHookSet.supportedEvents()).allMatch(HookEventType::firesInsideExecution);
    }

    @Test
    @DisplayName("a context can be handed an execution environment exactly when its event fires inside an execution")
    void onlyInExecutionContextsCanBeBuiltWithAnEnvironment() {
        // The classification is enforced by shape: an out-of-execution context builder has no executionEnvironment
        // setter, so no firing site can fill one in by mistake.
        assertThat(List.of(PreToolContext.builder(), PostToolContext.builder(), OnStartContext.builder(),
                OnStopContext.builder(), PreCompactContext.builder(), PostCompactContext.builder(),
                PermissionRequestContext.builder(), PermissionDeniedContext.builder(), SubagentStartContext.builder(),
                SubagentStopContext.builder())).allMatch(HookEventTypeTest::acceptsAnExecutionEnvironment);
        assertThat(List.of(OnSessionStartContext.builder(), OnSessionEndContext.builder(),
                OnConfigReloadContext.builder())).noneMatch(HookEventTypeTest::acceptsAnExecutionEnvironment);
    }

    private static boolean acceptsAnExecutionEnvironment(Object builder) {
        return Arrays.stream(builder.getClass().getMethods()).map(Method::getParameterTypes)
                .anyMatch(types -> types.length == 1 && types[0] == ExecutionEnvironment.class);
    }
}
