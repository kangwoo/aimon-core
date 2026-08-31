package at.aimon.browser.playwright;

import java.util.Objects;

/**
 * Playwright 브라우저 연결 설정.
 *
 * <p>
 * 불변 객체이며 Builder 패턴으로 생성한다.
 * 로컬 실행과 원격(WebSocket, CDP) 연결을 모두 지원한다.
 *
 * <p>
 * 사용 예시:
 *
 * <pre>
 * {@code
 * // 로컬 headless 모드 (기본)
 * PlaywrightConnectionConfig local = PlaywrightConnectionConfig.local(true);
 *
 * // 원격 Playwright Server 연결
 * PlaywrightConnectionConfig remote = PlaywrightConnectionConfig.builder()
 *         .mode(PlaywrightConnectionMode.REMOTE_WS)
 *         .endpoint("ws://playwright-server:3000")
 *         .build();
 *
 * // 원격 CDP 연결
 * PlaywrightConnectionConfig cdp = PlaywrightConnectionConfig.builder()
 *         .mode(PlaywrightConnectionMode.REMOTE_CDP)
 *         .endpoint("http://chrome:9222")
 *         .build();
 * }
 * </pre>
 *
 * @see PlaywrightConnectionMode
 * @see PlaywrightLifecycleManager
 */
public final class PlaywrightConnectionConfig {

    private final PlaywrightConnectionMode mode;
    private final boolean headless;
    private final String endpoint;

    private PlaywrightConnectionConfig(Builder builder) {
        this.mode = Objects.requireNonNull(builder.mode, "Connection mode cannot be null");
        this.endpoint = builder.endpoint;
        // 원격 모드에서는 headless 옵션이 의미 없으므로 기본값(true)을 사용한다
        this.headless = mode.isRemote() ? true : builder.headless;

        validate();
    }

    private void validate() {
        if (mode.isRemote() && (endpoint == null || endpoint.isBlank())) {
            throw new IllegalArgumentException("Endpoint is required for remote connection mode: " + mode);
        }
    }

    /**
     * 로컬 연결 설정을 생성하는 편의 팩토리 메서드.
     *
     * @param headless
     *            Chromium headless 모드 여부
     * @return 로컬 모드의 {@link PlaywrightConnectionConfig}
     */
    public static PlaywrightConnectionConfig local(boolean headless) {
        return builder().mode(PlaywrightConnectionMode.LOCAL).headless(headless).build();
    }

    /**
     * 새 Builder를 생성한다.
     *
     * @return 새 {@link Builder} 인스턴스
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 연결 모드를 반환한다.
     *
     * @return 연결 모드 (never null)
     */
    public PlaywrightConnectionMode getMode() {
        return mode;
    }

    /**
     * Chromium headless 모드 여부를 반환한다.
     * {@link PlaywrightConnectionMode#LOCAL} 모드에서만 의미가 있다.
     *
     * @return headless 모드 여부
     */
    public boolean isHeadless() {
        return headless;
    }

    /**
     * 원격 연결 엔드포인트를 반환한다.
     * {@link PlaywrightConnectionMode#REMOTE_WS} 또는 {@link PlaywrightConnectionMode#REMOTE_CDP}
     * 모드에서 사용된다.
     *
     * @return 엔드포인트 URL (로컬 모드에서는 null)
     */
    public String getEndpoint() {
        return endpoint;
    }

    @Override
    public String toString() {
        return "PlaywrightConnectionConfig{mode=" + mode + ", headless=" + headless + ", endpoint="
                + maskEndpoint(endpoint) + "}";
    }

    /**
     * 엔드포인트 URL에서 쿼리 파라미터를 마스킹한다.
     * 인증 토큰 등 민감 정보 노출을 방지한다.
     */
    private static String maskEndpoint(String endpoint) {
        if (endpoint == null) {
            return "N/A";
        }
        int queryIndex = endpoint.indexOf('?');
        return queryIndex >= 0 ? endpoint.substring(0, queryIndex) + "?***" : endpoint;
    }

    /**
     * {@link PlaywrightConnectionConfig}의 Builder.
     */
    public static final class Builder {

        private PlaywrightConnectionMode mode = PlaywrightConnectionMode.LOCAL;
        private boolean headless = true;
        private String endpoint;

        private Builder() {
        }

        /**
         * 연결 모드를 설정한다.
         *
         * @param mode
         *            연결 모드
         * @return this builder
         * @throws NullPointerException
         *             mode가 null인 경우
         */
        public Builder mode(PlaywrightConnectionMode mode) {
            this.mode = Objects.requireNonNull(mode, "Connection mode cannot be null");
            return this;
        }

        /**
         * Chromium headless 모드를 설정한다.
         * {@link PlaywrightConnectionMode#LOCAL} 모드에서만 적용된다.
         *
         * @param headless
         *            headless 모드 여부 (기본값: true)
         * @return this builder
         */
        public Builder headless(boolean headless) {
            this.headless = headless;
            return this;
        }

        /**
         * 원격 연결 엔드포인트를 설정한다.
         * 원격 모드에서 필수이다.
         *
         * @param endpoint
         *            WebSocket URL 또는 CDP URL
         * @return this builder
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * {@link PlaywrightConnectionConfig}를 생성한다.
         *
         * @return 새 {@link PlaywrightConnectionConfig} 인스턴스
         * @throws NullPointerException
         *             mode가 null인 경우
         * @throws IllegalArgumentException
         *             원격 모드에서 endpoint가 없는 경우
         */
        public PlaywrightConnectionConfig build() {
            return new PlaywrightConnectionConfig(this);
        }
    }
}
