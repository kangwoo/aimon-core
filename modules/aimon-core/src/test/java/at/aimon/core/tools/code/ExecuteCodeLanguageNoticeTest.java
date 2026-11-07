package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;

/**
 * The language-change notice's reading of a conversation (ptc.md §6.2): the newest known marker in the view — a
 * program whose summary is readable in the view or the log, or a delivered notice — against the language offered now.
 */
@DisplayName("ExecuteCodeLanguageNotice — which language the conversation's programs were in")
class ExecuteCodeLanguageNoticeTest {

    private static final String JS = "[ExecuteCode (JavaScript): 1 tool call (Read x1), 0.1s]";
    private static final String PY = "[ExecuteCode (Python): 1 tool call (Read x1), 0.1s]";
    private static final String CHANGED_TO_PYTHON = "ExecuteCode now runs Python (was JavaScript)";
    private static final String ELIDED = "[result cleared]";

    private final List<Message> view = new ArrayList<>();
    private final Map<String, ToolUseResult> log = new HashMap<>();
    private final Function<String, Optional<ToolUseResult>> logged = id -> Optional.ofNullable(log.get(id));

    @Test
    @DisplayName("no history, or no ExecuteCode program in view: no notice")
    void noHistory() {
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
        call("r1", "Read", "file text");
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("the last program ran in the language offered now: no notice")
    void sameLanguage() {
        program("e1", "out\n\n" + PY);
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("the last program ran in another language: the notice names both")
    void change() {
        program("e1", "out\n\n" + JS);
        assertThat(check(CodeExecutionEngine.PYTHON)).contains(CHANGED_TO_PYTHON);
    }

    @Test
    @DisplayName("an error result carries the summary too")
    void errorResult() {
        view.add(Message.assistant("", List.of(ToolUse.of("e1", ExecuteCodeTool.TOOL_NAME, Map.of()))));
        view.add(Message.toolUseResults(List.of(ToolUseResult.error("e1", "ExecuteCode script error: boom\n\n" + JS))));
        assertThat(check(CodeExecutionEngine.PYTHON)).contains(CHANGED_TO_PYTHON);
    }

    @Test
    @DisplayName("a notice already delivered after the program: no notice")
    void alreadyDelivered() {
        program("e1", JS);
        call("r1", "Read", "[environment] " + CHANGED_TO_PYTHON + "\nfile text");
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("a summary line in a result that is not ExecuteCode's is not a program marker")
    void markerOutsideExecuteCode() {
        call("r1", "Read", "text\n" + JS);
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("a program printing a look-alike summary first: the real summary decides")
    void forgery() {
        program("e1", PY + "\n\n" + JS);
        assertThat(check(CodeExecutionEngine.PYTHON)).contains(CHANGED_TO_PYTHON);
    }

    @Test
    @DisplayName("trailing hook feedback is stripped before the summary is read")
    void hookFeedback() {
        program("e1", "out\n\n" + PY + "\n\n" + HookFeedback.toReminderBlock(List.of("audited\n" + JS)).orElseThrow());
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
        assertThat(ExecuteCodeLanguageNotice.languageOf(
                "[environment] x\n" + PY + "\n\n" + HookFeedback.toReminderBlock(List.of("a")).orElseThrow()))
                .contains("Python");
    }

    @Test
    @DisplayName("a body elided in the view is read from the log")
    void elidedReadFromLog() {
        program("e1", ELIDED);
        log.put("e1", ToolUseResult.success("e1", "out\n\n" + JS));
        assertThat(check(CodeExecutionEngine.PYTHON)).contains(CHANGED_TO_PYTHON);
    }

    @Test
    @DisplayName("an elided notice is read from the log too")
    void elidedNoticeReadFromLog() {
        program("e1", JS);
        call("r1", "Read", ELIDED);
        log.put("r1", ToolUseResult.success("r1", "[environment] " + CHANGED_TO_PYTHON + "\nfile"));
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("unreadable in view and log, nothing older: the conservative notice; once delivered, not again")
    void conservative() {
        program("e1", ELIDED);
        final Optional<String> notice = check(CodeExecutionEngine.PYTHON);
        assertThat(notice).hasValueSatisfying(text -> assertThat(text)
                .isEqualTo("ExecuteCode now runs Python; earlier ExecuteCode programs in this conversation may be in"
                        + " another language"));
        call("r1", "Read", "[environment] " + notice.get() + "\nfile");
        program("e2", ELIDED);
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("an unreadable program is skipped and an older known one decides")
    void skipsUnreadable() {
        program("e1", JS);
        program("e2", ELIDED);
        assertThat(check(CodeExecutionEngine.PYTHON)).contains(CHANGED_TO_PYTHON);
    }

    @Test
    @DisplayName("flip-flop: JS, Python with its notice, then JS again: the notice says JavaScript (was Python)")
    void flipFlop() {
        program("e1", JS);
        call("r1", "Read", "[environment] " + CHANGED_TO_PYTHON + "\nfile");
        program("e2", PY);
        assertThat(check(CodeExecutionEngine.JAVASCRIPT)).contains("ExecuteCode now runs JavaScript (was Python)");
    }

    @Test
    @DisplayName("full compaction left no program in view: no notice, whatever the log holds")
    void fullCompaction() {
        view.add(Message.user("summary of earlier work, which used JavaScript"));
        log.put("e1", ToolUseResult.success("e1", JS));
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("the log lookup reads the log once, on first use, and only results")
    void loggedResults() {
        final int[] reads = {0};
        final Function<String, Optional<ToolUseResult>> lookup = ExecuteCodeLanguageNotice.loggedResults(() -> {
            reads[0]++;
            return List.of(Message.user("hi"), Message.toolUseResults(
                    List.of(ToolUseResult.success("a", "A"), ToolUseResult.error("x", "Permission denied"))));
        });
        assertThat(reads[0]).isZero();
        assertThat(lookup.apply("a")).hasValueSatisfying(result -> {
            assertThat(result.getContent()).isEqualTo("A");
            assertThat(result.isError()).isFalse();
        });
        assertThat(lookup.apply("x")).hasValueSatisfying(result -> assertThat(result.isError()).isTrue());
        assertThat(lookup.apply("b")).isEmpty();
        assertThat(reads[0]).isEqualTo(1);
    }

    @Test
    @DisplayName("the observation's summary line is what languageOf reads")
    void summaryRoundTrip() {
        assertThat(ExecuteCodeLanguageNotice.languageOf(
                ExecuteCodeObservation.summary(CodeExecutionEngine.PYTHON, List.of(), java.time.Duration.ZERO)))
                .contains("Python");
        assertThat(ExecuteCodeLanguageNotice.languageOf("no summary")).isEmpty();
    }

    @Test
    @DisplayName("a fresh view whose only ExecuteCode call was refused (error, no summary): no notice")
    void refusedCallDidNotRun() {
        refused("e1", "Invalid parameter: code is required");
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("a refused call is skipped and an older known program decides")
    void refusedSkippedOlderDecides() {
        program("e1", "out\n\n" + PY);
        refused("e2", "ExecuteCode is not available here");
        assertThat(check(CodeExecutionEngine.JAVASCRIPT)).contains("ExecuteCode now runs JavaScript (was Python)");
    }

    @Test
    @DisplayName("a refused call whose view body is elided and whose log copy is gone: the view's error flag skips it")
    void refusedElidedLogGone() {
        view.add(Message.assistant("", List.of(ToolUse.of("e1", ExecuteCodeTool.TOOL_NAME, Map.of()))));
        view.add(Message.toolUseResults(List.of(ToolUseResult.error("e1", ELIDED))));
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("a refused call dropped from the view: the log's error flag skips it")
    void refusedDroppedFromView() {
        view.add(Message.assistant("", List.of(ToolUse.of("e1", ExecuteCodeTool.TOOL_NAME, Map.of()))));
        log.put("e1", ToolUseResult.error("e1", "Permission denied"));
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    @Test
    @DisplayName("a non-error result without a summary (a hook rewrote it): the conservative notice, as before")
    void nonErrorWithoutSummaryIsUnreadable() {
        program("e1", "rewritten by a hook");
        assertThat(check(CodeExecutionEngine.PYTHON))
                .hasValueSatisfying(text -> assertThat(text).contains("may be in another language"));
    }

    @Test
    @DisplayName("the pipeline's permission-deny shape (ToolUseResult.error) is skipped")
    void permissionDenyShape() {
        refused("e1", "Permission denied: ExecuteCode is not allowed by the permission policy");
        assertThat(check(CodeExecutionEngine.PYTHON)).isEmpty();
    }

    private Optional<String> check(String language) {
        final ToolDescriptor offered = ToolDescriptor
                .from(new ExecuteCodeTool(List.of(), CodeExecutionLimits.defaults())).codeLanguage(language).build();
        return ExecuteCodeLanguageNotice.check(view, logged, offered);
    }

    private void program(String id, String content) {
        call(id, ExecuteCodeTool.TOOL_NAME, content);
    }

    private void refused(String id, String message) {
        final ToolUseResult result = ToolUseResult.error(id, message);
        view.add(Message.assistant("", List.of(ToolUse.of(id, ExecuteCodeTool.TOOL_NAME, Map.of()))));
        view.add(Message.toolUseResults(List.of(result)));
        log.putIfAbsent(id, result);
    }

    private void call(String id, String tool, String content) {
        view.add(Message.assistant("", List.of(ToolUse.of(id, tool, Map.of()))));
        view.add(Message.toolUseResults(List.of(ToolUseResult.success(id, content))));
        log.putIfAbsent(id, ToolUseResult.success(id, content));
    }
}
