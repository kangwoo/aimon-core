package at.aimon.browser.playwright;

import java.util.List;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.CustomToolPermissionRule;

/**
 * Browser Tool 권한 검사 규칙.
 *
 * <p>
 * 이 규칙은 {@link at.aimon.core.agent.tool.permission.DefaultToolPermissionValidator}에서 호출된다.
 * {@code "Browser"} 하나만 등록된 경우는 검증기가 사전에 허용으로 처리하므로 여기까지 오지 않는다. 다만
 * {@code "Browser"} 와 {@code "Browser(open:*)"} 가 함께 등록되면 그 무패턴 항목도 목록에 섞여 들어온다 —
 * 이때 무패턴 항목은 아무것도 허용하지 않아야 하므로, 아래 매칭은 {@link AllowedTool#hasPattern()} 으로
 * 걸러낸 뒤에 수행한다.
 *
 * <p>
 * 이 도구가 {@link at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware} 로 옮겨가지 않고 규칙을
 * 유지하는 이유는, {@code action:url} 이 이 도구만의 문법이기 때문이다. 프레임워크의
 * {@link at.aimon.core.agent.tool.permission.PermissionSubject.Kind} 는 명령(command)과 경로(path) 둘뿐이고,
 * 여기의 {@code :} 는 와일드카드 표시가 아니라 두 값을 잇는 구분자다. 이것을 세 번째 kind 로 승격시키면 한 도구의
 * 표기법이 프레임워크 어휘가 된다.
 *
 * <p>
 * AllowedTool 패턴 예시:
 * <ul>
 * <li>{@code "Browser"} - 모든 액션 허용 (DefaultToolPermissionValidator에서 처리)
 * <li>{@code "Browser(open:*)"} - open 액션만 허용 (모든 URL)
 * <li>{@code "Browser(open:https://example.com:*)"} - 그 URL 접두사로 시작하는 open 만 허용
 * <li>{@code "Browser(extract:*)"} - extract 액션만 허용
 * <li>{@code "Browser(screenshot:*)"} - screenshot 액션만 허용
 * </ul>
 *
 * <p>
 * 매칭은 {@link at.aimon.core.agent.tool.permission.ToolPattern} 에 위임하므로 규칙도 그대로다 — {@code :*} 로
 * 끝나면 접두사 매칭, 아니면 완전 일치. 즉 <b>호스트 글롭은 지원되지 않는다</b>: {@code "Browser(open:*.example.com)"}
 * 는 접두사 패턴이 아니므로 url 이 문자 그대로 {@code *.example.com} 인 경우에만 매치되고, 실질적으로 아무것도
 * 허용하지 않는다. 도메인으로 좁히려면 URL 접두사 뒤에 {@code :*} 를 붙인다.
 */
public class BrowserToolPermissionRule implements CustomToolPermissionRule {

    @Override
    public boolean isAllowed(ToolInput input, ToolContext context, List<AllowedTool> allowedTools) {
        final String action = stringOrNull(input, "action");
        if (action == null) {
            return false;
        }

        // URL이 있는 경우 "action:url" 형태로 매칭
        final String url = stringOrNull(input, "url");
        final String matchTarget = (url != null) ? action + ":" + url : action;

        return allowedTools.stream().filter(AllowedTool::hasPattern)
                .anyMatch(at -> at.getPattern().orElseThrow().matches(matchTarget));
    }

    @Override
    public String buildErrorDetail(ToolInput input, ToolContext context) {
        final String action = stringOrNull(input, "action");
        return " (Browser action: " + (action != null ? action : "unknown") + ")";
    }

    /**
     * 값을 문자열로 읽되, 문자열이 아니면 예외 대신 null 을 돌려준다.
     *
     * <p>
     * {@link ToolInput#getStringOrNull(String)} 은 타입이 다르면 {@link IllegalArgumentException} 을 던진다. 권한
     * 검사는 도구 실행보다 먼저 돌고 이 예외를 받아 줄 곳이 없으므로, 여기서는 "판단할 수 없음"(= 거부)으로
     * 처리한다.
     */
    private static String stringOrNull(ToolInput input, String key) {
        return input.get(key) instanceof String value ? value : null;
    }
}
