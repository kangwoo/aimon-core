package at.aimon.cli.config;

import java.util.Objects;

import at.aimon.core.agent.session.transcript.SessionLogFormat;

public class CliSettings {
    private String prompt;
    private boolean colorOutput = true;
    private boolean showIterations = true;
    private boolean showToolCalls = true;
    // PSTREAM-11: streaming defaults to ON so new installations get the progressive-text UX out of the box.
    // Users can opt out via the CLI `--no-streaming` flag, which maps to CliSettings.setStreaming(false).
    private boolean streaming = true;
    // TRACE-01: LangSmith-style execution tracing. Off by default; enable via `cli.tracing: true` to record a
    // per-turn span tree (inspect with the `/trace` REPL command).
    private boolean tracing = false;
    // TRACE-02: when tracing is on, also capture tool result + LLM response content in spans (truncated). Off by
    // default; secret keys are masked regardless via the default redactor.
    private boolean tracingCaptureContent = false;
    // TRACE-02: truncation cap (characters) for captured content. Only consulted when tracingCaptureContent is true.
    private int tracingMaxPayloadChars = 8192;
    // Opt-in for the experimental multi-perspective `Workflow` tool. Off by default because it adds a tool to the
    // agent's system prompt and each call fans out to several sub-agent LLM calls. Enable via `cli.enableWorkflow:
    // true`.
    private boolean enableWorkflow = false;
    // Opt-in for the GraalJS-scripted `WorkflowJs` tool (module aimon-workflow-graaljs). Off by default: it
    // adds a tool to the agent's system prompt and each call runs an author-supplied JS script that fans out to
    // several sub-agent LLM calls. Enable via `cli.enableWorkflowJs: true`. Independent of enableWorkflow, but either
    // flag enables the per-context WorkflowRunner used by both tools' background mode.
    private boolean enableWorkflowJs = false;
    // The session log format the CLI writes (session-log §7.3): v1 (default) or v2. v2 keeps the log append-only and
    // is what an agent with `context-engine: rolling` needs; set `cli.sessionLogWriteFormat: v2` to run one. The CLI
    // keeps sessions in memory, so there is no older node that could fail to read v2 — the switch exists only so the
    // default stays the format every other assembly defaults to.
    private SessionLogFormat sessionLogWriteFormat = SessionLogFormat.V1;

    /** CliSettings를 생성한다. */
    public CliSettings() {
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public boolean isColorOutput() {
        return colorOutput;
    }

    public void setColorOutput(boolean colorOutput) {
        this.colorOutput = colorOutput;
    }

    public boolean isShowIterations() {
        return showIterations;
    }

    public void setShowIterations(boolean showIterations) {
        this.showIterations = showIterations;
    }

    public boolean isShowToolCalls() {
        return showToolCalls;
    }

    public void setShowToolCalls(boolean showToolCalls) {
        this.showToolCalls = showToolCalls;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public boolean isTracing() {
        return tracing;
    }

    public void setTracing(boolean tracing) {
        this.tracing = tracing;
    }

    public boolean isTracingCaptureContent() {
        return tracingCaptureContent;
    }

    public void setTracingCaptureContent(boolean tracingCaptureContent) {
        this.tracingCaptureContent = tracingCaptureContent;
    }

    public int getTracingMaxPayloadChars() {
        return tracingMaxPayloadChars;
    }

    public void setTracingMaxPayloadChars(int tracingMaxPayloadChars) {
        this.tracingMaxPayloadChars = tracingMaxPayloadChars;
    }

    public boolean isEnableWorkflow() {
        return enableWorkflow;
    }

    public void setEnableWorkflow(boolean enableWorkflow) {
        this.enableWorkflow = enableWorkflow;
    }

    public boolean isEnableWorkflowJs() {
        return enableWorkflowJs;
    }

    public void setEnableWorkflowJs(boolean enableWorkflowJs) {
        this.enableWorkflowJs = enableWorkflowJs;
    }

    public SessionLogFormat getSessionLogWriteFormat() {
        return sessionLogWriteFormat;
    }

    /**
     * @param sessionLogWriteFormat
     *            the format; null restores the default {@link SessionLogFormat#V1}
     */
    public void setSessionLogWriteFormat(SessionLogFormat sessionLogWriteFormat) {
        this.sessionLogWriteFormat = sessionLogWriteFormat == null ? SessionLogFormat.V1 : sessionLogWriteFormat;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CliSettings that = (CliSettings) o;
        return colorOutput == that.colorOutput && showIterations == that.showIterations
                && showToolCalls == that.showToolCalls && streaming == that.streaming && tracing == that.tracing
                && tracingCaptureContent == that.tracingCaptureContent
                && tracingMaxPayloadChars == that.tracingMaxPayloadChars && enableWorkflow == that.enableWorkflow
                && enableWorkflowJs == that.enableWorkflowJs && sessionLogWriteFormat == that.sessionLogWriteFormat
                && Objects.equals(prompt, that.prompt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prompt, colorOutput, showIterations, showToolCalls, streaming, tracing,
                tracingCaptureContent, tracingMaxPayloadChars, enableWorkflow, enableWorkflowJs, sessionLogWriteFormat);
    }

    @Override
    public String toString() {
        return "CliSettings{" + "prompt='" + prompt + '\'' + ", colorOutput=" + colorOutput + ", showIterations="
                + showIterations + ", showToolCalls=" + showToolCalls + ", streaming=" + streaming + ", tracing="
                + tracing + ", tracingCaptureContent=" + tracingCaptureContent + ", tracingMaxPayloadChars="
                + tracingMaxPayloadChars + ", enableWorkflow=" + enableWorkflow + ", enableWorkflowJs="
                + enableWorkflowJs + ", sessionLogWriteFormat=" + sessionLogWriteFormat + '}';
    }
}
