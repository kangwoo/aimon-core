package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlaywrightConnectionConfigTest {

    @Test
    void builderShouldDefaultToLocalHeadless() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.builder().build();

        assertThat(config.getMode()).isEqualTo(PlaywrightConnectionMode.LOCAL);
        assertThat(config.isHeadless()).isTrue();
        assertThat(config.getEndpoint()).isNull();
    }

    @Test
    void localFactoryShouldCreateLocalConfig() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.local(true);

        assertThat(config.getMode()).isEqualTo(PlaywrightConnectionMode.LOCAL);
        assertThat(config.isHeadless()).isTrue();
        assertThat(config.getEndpoint()).isNull();
    }

    @Test
    void localFactoryShouldSupportNonHeadless() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.local(false);

        assertThat(config.getMode()).isEqualTo(PlaywrightConnectionMode.LOCAL);
        assertThat(config.isHeadless()).isFalse();
    }

    @Test
    void shouldBuildRemoteWsConfig() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.builder()
                .mode(PlaywrightConnectionMode.REMOTE_WS).endpoint("ws://playwright-server:3000").build();

        assertThat(config.getMode()).isEqualTo(PlaywrightConnectionMode.REMOTE_WS);
        assertThat(config.getEndpoint()).isEqualTo("ws://playwright-server:3000");
    }

    @Test
    void shouldBuildRemoteCdpConfig() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.builder()
                .mode(PlaywrightConnectionMode.REMOTE_CDP).endpoint("http://chrome:9222").build();

        assertThat(config.getMode()).isEqualTo(PlaywrightConnectionMode.REMOTE_CDP);
        assertThat(config.getEndpoint()).isEqualTo("http://chrome:9222");
    }

    @Test
    void shouldRejectRemoteWsWithoutEndpoint() {
        assertThatThrownBy(() -> PlaywrightConnectionConfig.builder().mode(PlaywrightConnectionMode.REMOTE_WS).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Endpoint is required");
    }

    @Test
    void shouldRejectRemoteCdpWithoutEndpoint() {
        assertThatThrownBy(() -> PlaywrightConnectionConfig.builder().mode(PlaywrightConnectionMode.REMOTE_CDP).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Endpoint is required");
    }

    @Test
    void shouldRejectRemoteWsWithBlankEndpoint() {
        assertThatThrownBy(() -> PlaywrightConnectionConfig.builder().mode(PlaywrightConnectionMode.REMOTE_WS)
                .endpoint("   ").build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Endpoint is required");
    }

    @Test
    void shouldRejectNullMode() {
        assertThatThrownBy(() -> PlaywrightConnectionConfig.builder().mode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAllowLocalWithEndpointIgnored() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.builder().mode(PlaywrightConnectionMode.LOCAL)
                .endpoint("ws://ignored").build();

        assertThat(config.getMode()).isEqualTo(PlaywrightConnectionMode.LOCAL);
        assertThat(config.getEndpoint()).isEqualTo("ws://ignored");
    }

    @Test
    void remoteModesShouldForceHeadlessTrue() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.builder()
                .mode(PlaywrightConnectionMode.REMOTE_WS).endpoint("ws://server:3000").headless(false).build();

        assertThat(config.isHeadless()).isTrue();
    }

    @Test
    void toStringShouldContainModeAndEndpoint() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.builder()
                .mode(PlaywrightConnectionMode.REMOTE_WS).endpoint("ws://server:3000").build();

        String str = config.toString();
        assertThat(str).contains("REMOTE_WS");
        assertThat(str).contains("ws://server:3000");
    }

    @Test
    void toStringShouldMaskQueryParameters() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.builder()
                .mode(PlaywrightConnectionMode.REMOTE_WS).endpoint("ws://server:3000/?token=secret123").build();

        String str = config.toString();
        assertThat(str).contains("ws://server:3000/?***");
        assertThat(str).doesNotContain("secret123");
    }

    @Test
    void toStringLocalShouldShowNAForEndpoint() {
        PlaywrightConnectionConfig config = PlaywrightConnectionConfig.local(true);

        String str = config.toString();
        assertThat(str).contains("LOCAL");
        assertThat(str).contains("N/A");
    }
}
