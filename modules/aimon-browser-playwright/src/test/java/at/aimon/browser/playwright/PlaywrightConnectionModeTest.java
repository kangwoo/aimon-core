package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaywrightConnectionModeTest {

    @Test
    void localShouldNotBeRemote() {
        assertThat(PlaywrightConnectionMode.LOCAL.isRemote()).isFalse();
    }

    @Test
    void remoteWsShouldBeRemote() {
        assertThat(PlaywrightConnectionMode.REMOTE_WS.isRemote()).isTrue();
    }

    @Test
    void remoteCdpShouldBeRemote() {
        assertThat(PlaywrightConnectionMode.REMOTE_CDP.isRemote()).isTrue();
    }
}
