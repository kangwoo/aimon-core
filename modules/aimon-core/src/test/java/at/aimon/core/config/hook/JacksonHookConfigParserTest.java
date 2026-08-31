package at.aimon.core.config.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JacksonHookConfigParser")
class JacksonHookConfigParserTest {

    private final JacksonHookConfigParser parser = new JacksonHookConfigParser();

    @Test
    @DisplayName("parses Claude Code hooks.json shape")
    void parsesClaudeCodeShape() {
        final String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "Bash",
                        "hooks": [
                          { "type": "command", "command": "echo hi", "timeout": 60 }
                        ]
                      }
                    ],
                    "PostToolUse": [
                      {
                        "matcher": "*",
                        "hooks": [
                          { "type": "http", "url": "https://example.test/hook",
                            "method": "POST", "body": "{\\"x\\":\\"${tool_input.x}\\"}",
                            "allowedEnvVars": ["TOKEN"], "timeout": 5000 }
                        ]
                      }
                    ]
                  }
                }
                """;

        final HookConfigDocument doc = parser.parse(json);

        assertThat(doc.getHooks()).containsKeys("PreToolUse", "PostToolUse");
        final List<HookEntry> pre = doc.getHooks().get("PreToolUse");
        assertThat(pre).hasSize(1);
        final HookHandlerSpec spec = pre.get(0).getHandlers().get(0);
        assertThat(spec.getType()).isEqualTo(HookHandlerSpec.Type.COMMAND);
        assertThat(spec.getCommand()).isEqualTo("echo hi");
        // "timeout" is seconds (Claude Code parity), surfaced to the runtime as milliseconds.
        assertThat(spec.getTimeoutMs()).isEqualTo(60_000L);

        final HookHandlerSpec httpSpec = doc.getHooks().get("PostToolUse").get(0).getHandlers().get(0);
        assertThat(httpSpec.getType()).isEqualTo(HookHandlerSpec.Type.HTTP);
        assertThat(httpSpec.getUrl()).isEqualTo("https://example.test/hook");
        assertThat(httpSpec.getMethod()).isEqualTo("POST");
        assertThat(httpSpec.getAllowedEnvVars()).containsExactly("TOKEN");
        assertThat(httpSpec.getTimeoutMs()).isEqualTo(5_000_000L);
    }

    @Test
    @DisplayName("blank or null json returns empty document")
    void blankIsEmpty() {
        assertThat(parser.parse("").getHooks()).isEmpty();
        assertThat(parser.parse("   ").getHooks()).isEmpty();
        assertThat(parser.parse("null").getHooks()).isEmpty();
    }

    @Test
    @DisplayName("invalid type raises HookConfigParseException")
    void invalidTypeFails() {
        final String json = "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[{\"type\":\"unknown\"}]}]}}";
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(HookConfigParseException.class);
    }

    @Test
    @DisplayName("unknown handler fields are silently ignored (forwards-compat)")
    void unknownFieldsAreIgnored() {
        final String json = "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"x\""
                + ",\"someFutureField\":true}]}]}}";

        final HookConfigDocument doc = parser.parse(json);

        final HookHandlerSpec spec = doc.getHooks().get("PreToolUse").get(0).getHandlers().get(0);
        assertThat(spec.getCommand()).isEqualTo("x");
        assertThat(spec.getType()).isEqualTo(HookHandlerSpec.Type.COMMAND);
    }

    @Test
    @DisplayName("asyncRewake block binds to a RewakeSpecConfig (Phase 4A WI-4A.8)")
    void asyncRewakeBindsToConfig() {
        final String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "hooks": [
                          {
                            "type": "command",
                            "command": "x",
                            "asyncRewake": {
                              "trigger": { "delay": "5m" },
                              "timeout": "1h",
                              "maxAttempts": 4,
                              "payload": { "ticket": "T-1" },
                              "reason": "awaiting human approval"
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        final HookConfigDocument doc = parser.parse(json);
        final HookHandlerSpec spec = doc.getHooks().get("PreToolUse").get(0).getHandlers().get(0);

        assertThat(spec.getAsyncRewake()).isNotNull();
        assertThat(spec.getAsyncRewake().getTrigger().getDelay()).isEqualTo("5m");
        assertThat(spec.getAsyncRewake().getTimeout()).isEqualTo("1h");
        assertThat(spec.getAsyncRewake().getMaxAttempts()).isEqualTo(4);
        assertThat(spec.getAsyncRewake().getPayload()).containsExactlyEntriesOf(java.util.Map.of("ticket", "T-1"));
        assertThat(spec.getAsyncRewake().getReason()).isEqualTo("awaiting human approval");
    }

    @Test
    @DisplayName("asyncRewake event trigger binds the EventConfig sub-object")
    void asyncRewakeEventTriggerBinds() {
        final String json = """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "hooks": [
                          {
                            "type": "command",
                            "command": "x",
                            "asyncRewake": {
                              "trigger": { "event": { "type": "webhook", "key": "ticket-${tool_input.id}" } },
                              "reason": "wait for callback"
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        final HookConfigDocument doc = parser.parse(json);
        final HookHandlerSpec spec = doc.getHooks().get("PreToolUse").get(0).getHandlers().get(0);

        assertThat(spec.getAsyncRewake().getTrigger().getEvent()).isNotNull();
        assertThat(spec.getAsyncRewake().getTrigger().getEvent().getType()).isEqualTo("webhook");
        assertThat(spec.getAsyncRewake().getTrigger().getEvent().getKey()).isEqualTo("ticket-${tool_input.id}");
    }
}
