package at.aimon.core.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserLocale} carries only the time zone; the working directory, platform and OS version live on the
 * execution's {@code EnvironmentDescriptor} (execution-environment design §10).
 */
@DisplayName("UserLocale Tests")
class UserLocaleTest {

    @Test
    @DisplayName("createDefault() uses the system default time zone")
    void createDefaultUsesSystemTimeZone() {
        assertThat(UserLocale.createDefault().getTimeZone()).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    @DisplayName("the builder sets the time zone and defaults it to the system's")
    void builderSetsTimeZone() {
        assertThat(UserLocale.builder().timeZone(ZoneId.of("Asia/Seoul")).build().getTimeZone())
                .isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(UserLocale.builder().build().getTimeZone()).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    @DisplayName("a null time zone is rejected")
    void nullTimeZoneRejected() {
        assertThatThrownBy(() -> UserLocale.builder().timeZone(null).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timeZone");
    }

    @Test
    @DisplayName("equality and hash code follow the time zone")
    void equalityFollowsTimeZone() {
        final UserLocale utc = UserLocale.builder().timeZone(ZoneId.of("UTC")).build();
        final UserLocale utc2 = UserLocale.builder().timeZone(ZoneId.of("UTC")).build();
        final UserLocale seoul = UserLocale.builder().timeZone(ZoneId.of("Asia/Seoul")).build();

        assertThat(utc).isEqualTo(utc2).hasSameHashCodeAs(utc2).isNotEqualTo(seoul);
    }

    @Test
    @DisplayName("toString() names the type and the time zone")
    void toStringNamesTheTimeZone() {
        assertThat(UserLocale.builder().timeZone(ZoneId.of("UTC")).build()).hasToString("UserLocale{timeZone=UTC}");
    }
}
