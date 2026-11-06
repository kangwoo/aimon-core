package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.llm.ToolDefinition;

@DisplayName("ToolDescriptor and Tool#describe")
class ToolDescriptorTest {

    private static Tool undeclared(String name) {
        return new AbstractTool(name, "d", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }
        };
    }

    @Test
    @DisplayName("the default describe is the tool's getters and static definition, whatever the context")
    void defaultDescribe_isTheGetters() {
        final Tool tool = new AbstractTool("Reader", "reads", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }

            @Override
            public SideEffectLevel getSideEffectLevel() {
                return SideEffectLevel.READ_ONLY;
            }

            @Override
            public ConcurrencyBehavior getConcurrencyBehavior() {
                return ConcurrencyBehavior.CONCURRENT_SAFE;
            }

            @Override
            public InterruptBehavior getInterruptBehavior() {
                return InterruptBehavior.COOPERATIVE;
            }

            @Override
            public DataReach dataReach() {
                return DataReach.ENVIRONMENT;
            }
        };

        final ToolDescriptor offered = tool.describe(ToolOfferContext.builder().build());
        final ToolDescriptor none = tool.describe(ToolOfferContext.none());

        assertThat(offered).isEqualTo(none).isEqualTo(ToolDescriptor.of(tool));
        assertThat(offered.getDefinition()).isEqualTo(tool.getDefinition());
        assertThat(offered.getName()).isEqualTo("Reader");
        assertThat(offered.getSideEffectLevel()).isEqualTo(SideEffectLevel.READ_ONLY);
        assertThat(offered.getConcurrencyBehavior()).isEqualTo(ConcurrencyBehavior.CONCURRENT_SAFE);
        assertThat(offered.getInterruptBehavior()).isEqualTo(InterruptBehavior.COOPERATIVE);
        assertThat(offered.getDataReach()).isEqualTo(DataReach.ENVIRONMENT);
        assertThat(offered.isCodeCallable()).isTrue();
        assertThat(offered.isApprovalDelegated()).isFalse();
        assertThat(offered.getApprovalKey()).isEqualTo("Reader");
    }

    @Test
    @DisplayName("an undeclared tool describes the conservative end of every axis")
    void undeclared_isConservative() {
        final ToolDescriptor descriptor = ToolDescriptor.of(undeclared("Thing"));

        assertThat(descriptor.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(descriptor.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
        assertThat(descriptor.getIdempotencyBehavior()).isEqualTo(IdempotencyBehavior.NON_IDEMPOTENT);
        assertThat(descriptor.getDataReach()).isEqualTo(DataReach.EXTERNAL);
        assertThat(descriptor.isCodeCallable()).isTrue();
    }

    @Test
    @DisplayName("a DEDICATED_THREAD tool is not code-callable by default")
    void dedicatedThread_isNotCodeCallableByDefault() {
        final Tool dedicated = new AbstractTool("Fork", "d", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }

            @Override
            public ConcurrencyBehavior getConcurrencyBehavior() {
                return ConcurrencyBehavior.DEDICATED_THREAD;
            }
        };

        assertThat(dedicated.isCodeCallable()).isFalse();
        assertThat(ToolDescriptor.of(dedicated).isCodeCallable()).isFalse();
    }

    @Test
    @DisplayName("the effective destructiveness is NON_DESTRUCTIVE below MUTATING")
    void effectiveDestructiveness() {
        final ToolDescriptor mutating = ToolDescriptor.of(undeclared("W"));
        final ToolDescriptor readOnly = ToolDescriptor.from(undeclared("R")).sideEffectLevel(SideEffectLevel.READ_ONLY)
                .build();

        assertThat(mutating.getEffectiveDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
        assertThat(readOnly.getDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
        assertThat(readOnly.getEffectiveDestructiveBehavior()).isEqualTo(DestructiveBehavior.NON_DESTRUCTIVE);
    }

    @Test
    @DisplayName("from() lets an overriding tool change some axes and keep the rest")
    void from_overridesSomeAxes() {
        final ToolDescriptor descriptor = ToolDescriptor.from(undeclared("ExecuteCode")).approvalDelegated(true)
                .approvalKey("Bash").dataReach(DataReach.ENVIRONMENT).build();

        assertThat(descriptor.isApprovalDelegated()).isTrue();
        assertThat(descriptor.getApprovalKey()).isEqualTo("Bash");
        assertThat(descriptor.getDataReach()).isEqualTo(DataReach.ENVIRONMENT);
        assertThat(descriptor.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(descriptor).isNotEqualTo(ToolDescriptor.of(undeclared("ExecuteCode")));
    }

    @Test
    @DisplayName("builder() requires every axis without a default")
    void builder_requiresAxes() {
        final ToolDefinition definition = ToolDefinition.of("T", "d", Map.of("type", "object"));
        assertThatNullPointerException().isThrownBy(() -> ToolDescriptor.builder().definition(definition).build());

        final ToolDescriptor built = ToolDescriptor.builder().definition(definition)
                .sideEffectLevel(SideEffectLevel.READ_ONLY).destructiveBehavior(DestructiveBehavior.NON_DESTRUCTIVE)
                .idempotencyBehavior(IdempotencyBehavior.IDEMPOTENT)
                .concurrencyBehavior(ConcurrencyBehavior.CONCURRENT_SAFE)
                .interruptBehavior(InterruptBehavior.NON_INTERRUPTIBLE).dataReach(DataReach.HOST).build();
        assertThat(built.isCodeCallable()).isFalse();
        assertThat(built.getApprovalKey()).isEqualTo("T");
    }
}
