package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.workflow.graaljs.exception.JsScriptCancelledException;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * Sandbox tests: deny-by-default host surface and the pure-JS runaway statement backstop.
 */
@DisplayName("JsSandbox — deny-by-default + statement backstop")
class JsSandboxTest extends AbstractGraalJsRunTest {

    @Test
    @DisplayName("Host class lookup is denied and Polyglot access is absent under HostAccess.NONE")
    void hostEscapeIsDenied() {
        // The Nashorn-compat `Java` global exists but is inert: Java.type must fail with no host class lookup.
        final String out = run("let r = [];\n" + "try { Java.type('java.lang.System'); r.push('type:ESCAPED'); }\n"
                + "catch (e) { r.push('type:denied'); }\n" + "r.push('polyglot:' + (typeof Polyglot));\n"
                + "return r.join('|');");
        assertThat(out).isEqualTo("type:denied|polyglot:undefined");
    }

    @Test
    @DisplayName("A pure-JS infinite loop is terminated by the statement limit, reported as such")
    void infiniteLoopHitsStatementLimit() {
        final JsSandboxConfig tightConfig = JsSandboxConfig.builder().maxStatements(500_000)
                .wallClockTimeout(Duration.ofSeconds(20)).build();
        // A statement-limit overrun is a script defect, not a cancellation — the error must say so.
        assertThatThrownBy(() -> run("while (true) { }", java.util.Map.of(), tightConfig))
                .isInstanceOf(JsScriptException.class).isNotInstanceOf(JsScriptCancelledException.class)
                .hasMessageContaining("statement limit");
    }

    @Test
    @DisplayName("wallClockTimeout must be positive — zero/negative would silently disable the mandatory backstop")
    void wallClockTimeoutMustBePositive() {
        assertThatThrownBy(() -> JsSandboxConfig.builder().wallClockTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsSandboxConfig.builder().wallClockTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
