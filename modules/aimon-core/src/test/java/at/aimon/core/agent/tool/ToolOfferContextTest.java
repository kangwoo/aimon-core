package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayName("ToolOfferContext")
class ToolOfferContextTest {

    private static final OfferKey<String> ENGINE = OfferKey.of("engine", String.class);

    private static Tool tool(String name, SideEffectLevel level) {
        return new AbstractTool(name, "d", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }

            @Override
            public SideEffectLevel getSideEffectLevel() {
                return level;
            }
        };
    }

    private static ToolRegistry registryWith(Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    @Test
    @DisplayName("none() offers nothing, admits everything and has the unrestricted ceiling")
    void none() {
        final ToolOfferContext none = ToolOfferContext.none();

        assertThat(none.isOffering()).isFalse();
        assertThat(none.getRegistry()).isEmpty();
        assertThat(none.offer()).isEmpty();
        assertThat(none.getCeiling()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(none.admits(tool("X", SideEffectLevel.MUTATING))).isTrue();
        assertThat(none.get(ENGINE)).isEmpty();
    }

    @Test
    @DisplayName("offer() keeps the admitted tools within the ceiling, in registry order")
    void offer_appliesAdmissionAndCeiling() {
        final ToolOfferContext context = ToolOfferContext.builder()
                .registry(registryWith(tool("Read", SideEffectLevel.READ_ONLY), tool("Write", SideEffectLevel.MUTATING),
                        tool("Grep", SideEffectLevel.READ_ONLY)))
                .admission(t -> !t.getDefinition().getName().equals("Grep")).ceiling(SideEffectLevel.READ_ONLY).build();

        assertThat(context.isOffering()).isTrue();
        assertThat(context.offer()).extracting(ToolDescriptor::getName).containsExactly("Read");
    }

    @Test
    @DisplayName("offer() reads each tool's descriptor in this context, not its getters, and describes it once")
    void offer_readsTheDescriptor() {
        final AtomicInteger describes = new AtomicInteger();
        final Tool contextual = new AbstractTool("ExecuteCode", "d", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }

            @Override
            public ToolDescriptor describe(ToolOfferContext context) {
                describes.incrementAndGet();
                // Read-only only in a context that says so; the getters say MUTATING.
                final SideEffectLevel level = context.get(ENGINE).filter("javascript"::equals).isPresent()
                        ? SideEffectLevel.READ_ONLY
                        : SideEffectLevel.MUTATING;
                return ToolDescriptor.from(this).sideEffectLevel(level).build();
            }
        };
        final ToolRegistry registry = registryWith(contextual);

        final ToolOfferContext js = ToolOfferContext.builder().registry(registry).ceiling(SideEffectLevel.READ_ONLY)
                .put(ENGINE, "javascript").build();
        final ToolOfferContext shell = ToolOfferContext.builder().registry(registry).ceiling(SideEffectLevel.READ_ONLY)
                .put(ENGINE, "python").build();

        assertThat(js.offer()).extracting(ToolDescriptor::getName).containsExactly("ExecuteCode");
        assertThat(shell.offer()).isEmpty();
        assertThat(describes).hasValue(2);
    }

    @Test
    @DisplayName("of(ToolContext) returns the bound offer, or none() when nothing is bound")
    void of_readsTheBoundOffer() {
        final ToolOfferContext offer = ToolOfferContext.builder().build();
        final ToolContext bound = ToolContext.builder().put(ToolOfferContext.CONTEXT_KEY, offer).build();

        assertThat(ToolOfferContext.of(bound)).isSameAs(offer);
        assertThat(ToolOfferContext.of(ToolContext.empty())).isSameAs(ToolOfferContext.none());
    }

    @Test
    @DisplayName("the context key is write-once: an enricher cannot swap the offer")
    void contextKey_isWriteOnce() {
        final ToolContext.Builder builder = ToolContext.builder().put(ToolOfferContext.CONTEXT_KEY,
                ToolOfferContext.builder().build());

        assertThat(ToolOfferContext.CONTEXT_KEY.isWriteOnce()).isTrue();
        assertThatThrownBy(() -> builder.put(ToolOfferContext.CONTEXT_KEY, ToolOfferContext.none()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("write-once");
    }

    @Test
    @DisplayName("attributes are typed and keyed by identity")
    void attributes() {
        final OfferKey<String> sameName = OfferKey.of("engine", String.class);
        final ToolOfferContext context = ToolOfferContext.builder().put(ENGINE, "javascript").build();

        assertThat(context.get(ENGINE)).contains("javascript");
        assertThat(context.get(sameName)).isEmpty();
    }

    private static Tool nullDescribing(String name) {
        return new AbstractTool(name, "d", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }

            @Override
            public ToolDescriptor describe(ToolOfferContext context) {
                return null;
            }
        };
    }

    @Test
    @DisplayName("a tool whose describe returns null fails offer(), eligible() and the code-only check alike, by name")
    void nullDescriptorFailsEveryPath() {
        final ToolOfferContext context = ToolOfferContext.builder()
                .registry(registryWith(tool("Read", SideEffectLevel.READ_ONLY), nullDescribing("Broken")))
                .programmatic(
                        ProgrammaticToolSettings.builder().tools(List.of("Read")).codeOnly(List.of("Read")).build())
                .build();
        final ToolDescriptor read = ToolDescriptor.of(tool("Read", SideEffectLevel.READ_ONLY));

        assertThatThrownBy(context::offer).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Broken.describe returned null");
        assertThatThrownBy(() -> context.eligible(List.of("Broken"))).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Broken.describe returned null");
        assertThatThrownBy(() -> context.isCodeOnly(read)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Broken.describe returned null");
    }

    @Test
    @DisplayName("the warning for a mutating programmatic-only tool is logged once, until reset")
    void codeOnlyWarningOnce() {
        ToolOfferContext.resetCodeOnlyWarnings();
        final ToolOfferContext context = ToolOfferContext.builder()
                .registry(registryWith(tool("Write", SideEffectLevel.MUTATING))).agentName("agent-warn-once")
                .programmatic(
                        ProgrammaticToolSettings.builder().tools(List.of("Write")).codeOnly(List.of("Write")).build())
                .build();
        final ToolDescriptor write = ToolDescriptor.of(tool("Write", SideEffectLevel.MUTATING));
        final Logger logger = (Logger) LoggerFactory.getLogger(ToolOfferContext.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            context.isNamedCodeOnly(write);
            context.isNamedCodeOnly(write);
            assertThat(warnings(appender)).hasSize(1);
            ToolOfferContext.resetCodeOnlyWarnings();
            context.isNamedCodeOnly(write);
            assertThat(warnings(appender)).hasSize(2);
        } finally {
            logger.detachAppender(appender);
            ToolOfferContext.resetCodeOnlyWarnings();
        }
    }

    private static List<ILoggingEvent> warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }
}
