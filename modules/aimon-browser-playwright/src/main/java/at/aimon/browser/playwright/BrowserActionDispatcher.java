package at.aimon.browser.playwright;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.browser.playwright.action.BrowserActionHandler;
import at.aimon.browser.playwright.action.BrowserActionResult;

/**
 * action 문자열을 해당 {@link BrowserActionHandler}로 라우팅한다.
 *
 * <p>
 * BashTool의 foreground/background 분기 패턴과 유사하나,
 * 14개 액션 타입을 Map 기반으로 디스패치한다.
 *
 * <p>
 * SSRF 검증, 보안 정책 등 액션별 횡단 관심사는 각 핸들러의 책임이다.
 * 예를 들어 OpenActionHandler는 생성자로 SsrfGuard를 주입받아
 * URL 검증을 수행한다 (Open/Closed Principle).
 */
public class BrowserActionDispatcher {

    private final Map<String, BrowserActionHandler> handlers;

    /**
     * BrowserActionDispatcher를 생성한다.
     *
     * @param handlerList
     *            등록할 핸들러 목록
     * @throws NullPointerException
     *             handlerList가 null인 경우
     * @throws IllegalArgumentException
     *             동일한 actionType을 가진 핸들러가 중복 등록된 경우
     */
    public BrowserActionDispatcher(List<BrowserActionHandler> handlerList) {
        Objects.requireNonNull(handlerList, "handlerList cannot be null");
        Map<String, BrowserActionHandler> mutableHandlers = new HashMap<>();
        for (BrowserActionHandler handler : handlerList) {
            String actionType = handler.getActionType();
            if (mutableHandlers.containsKey(actionType)) {
                throw new IllegalArgumentException("Duplicate handler for action: " + actionType);
            }
            mutableHandlers.put(actionType, handler);
        }
        this.handlers = Map.copyOf(mutableHandlers);
    }

    /**
     * 액션을 해당 핸들러에 디스패치한다.
     *
     * @param action
     *            액션 타입 문자열
     * @param input
     *            바인딩된 Tool 입력 파라미터
     * @param session
     *            브라우저 세션
     * @param timeoutMs
     *            타임아웃 (밀리초)
     * @return 액션 실행 결과
     */
    public BrowserActionResult dispatch(String action, BrowserInput input, BrowserSession session, int timeoutMs) {
        final BrowserActionHandler handler = handlers.get(action);
        if (handler == null) {
            return BrowserActionResult.error(session.getId(), action, "UNKNOWN_ACTION", "Unknown action: " + action);
        }

        return handler.handle(input, session, timeoutMs);
    }
}
