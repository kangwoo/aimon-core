package at.aimon.browser.playwright;

/**
 * Playwright 브라우저 연결 모드.
 *
 * <p>
 * 로컬 Chromium 실행 또는 원격 브라우저 서버에 연결할 수 있다.
 *
 * <ul>
 * <li>{@link #LOCAL} — {@code pw.chromium().launch()} (기본값)
 * <li>{@link #REMOTE_WS} — {@code pw.chromium().connect(wsEndpoint)} (Playwright Server)
 * <li>{@link #REMOTE_CDP} — {@code pw.chromium().connectOverCDP(cdpUrl)} (Chrome DevTools Protocol)
 * </ul>
 */
public enum PlaywrightConnectionMode {

    /**
     * 로컬 Chromium 프로세스를 직접 실행한다.
     * Worker당 별도의 Chromium 프로세스가 생성되므로 약 200-500MB 메모리를 사용한다.
     */
    LOCAL,

    /**
     * Playwright Server에 WebSocket으로 연결한다.
     * {@code npx playwright run-server}로 시작한 원격 서버에 연결할 때 사용한다.
     */
    REMOTE_WS,

    /**
     * Chrome DevTools Protocol(CDP)을 통해 원격 Chrome에 연결한다.
     * {@code --remote-debugging-port}로 시작한 Chrome 인스턴스에 연결할 때 사용한다.
     */
    REMOTE_CDP;

    /**
     * 원격 연결 모드인지 여부를 반환한다.
     *
     * @return {@code true}이면 원격 모드 ({@link #REMOTE_WS} 또는 {@link #REMOTE_CDP})
     */
    public boolean isRemote() {
        return this != LOCAL;
    }
}
