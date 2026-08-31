package at.aimon.browser.playwright.action;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;

/**
 * 개별 브라우저 액션을 처리하는 핸들러 인터페이스.
 *
 * <p>
 * 각 구현체는 하나의 액션 타입만 담당한다 (SRP).
 * 핸들러가 추가 의존성(SsrfGuard, ContentExtractor 등)을 필요로 하는 경우
 * 핸들러의 생성자에서 주입받는다. 인터페이스 시그니처는 깔끔하게 유지한다.
 *
 * <p>
 * 핸들러가 받는 것은 {@link BrowserInput} 이다 — 파라미터는 이미 바인딩·검증된 뒤이므로 핸들러 안에는 타입 검사도
 * 파라미터 이름 문자열도 남지 않는다. 예외는 <b>액션별 필수 파라미터</b> 하나다: JSON Schema 의 {@code required}
 * 는 "action=open 일 때만 필수" 를 표현할 수 없으므로, 그 판정만은 액션을 아는 핸들러가
 * {@link #require(String, String)} 으로 직접 한다.
 */
public interface BrowserActionHandler {

    /**
     * 이 핸들러가 처리하는 액션 타입을 반환한다.
     *
     * @return 액션 타입 문자열 (e.g., "open", "click", "type")
     */
    String getActionType();

    /**
     * 액션을 실행하고 결과를 반환한다.
     *
     * @param input
     *            바인딩된 Tool 입력 파라미터
     * @param session
     *            현재 브라우저 세션
     * @param timeoutMs
     *            타임아웃 (밀리초)
     * @return 액션 실행 결과 (never null)
     */
    BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs);

    /**
     * 이 액션에만 필수인 파라미터가 실제로 왔는지 확인한다.
     *
     * <p>
     * 던지는 예외와 메시지는 이 자리에 있던 {@code ToolInput.getRequiredString} 과 같다. 호출자
     * ({@code BrowserTool.doExecute}) 가 그것을 잡아 에러 결과로 바꾸므로 누락된 파라미터는 이전과 똑같이
     * <b>에러</b> 결과가 된다. 여기서 {@link BrowserActionResult#error} 를 대신 돌려주면 성공 결과에 실린 JSON 이
     * 되어 에러 플래그가 뒤집힌다.
     *
     * @param value
     *            바인딩된 값 (없으면 null)
     * @param name
     *            모델에게 보이는 파라미터 이름
     * @return null 이 아닌 값
     * @throws IllegalArgumentException
     *             값이 없는 경우
     */
    static String require(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + name);
        }
        return value;
    }
}
