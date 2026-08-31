package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class EnvironmentTest {

    @Test
    void testCreateDefault_CurrentSystem_PopulatesAllFields() {
        // Act
        Environment environment = Environment.createDefault();

        // Assert
        assertThat(environment.getWorkingDirectory()).isNotNull();
        assertThat(environment.getPlatform()).isNotNull();
        assertThat(environment.getOsVersion()).isNotNull();
        assertThat(environment.getTimeZone()).isNotNull();

        // Verify working directory matches system property
        String expectedWorkingDir = System.getProperty("user.dir");
        assertThat(environment.getWorkingDirectory()).isEqualTo(expectedWorkingDir);

        // Verify OS version matches system property
        String expectedOsVersion = System.getProperty("os.version");
        assertThat(environment.getOsVersion()).isEqualTo(expectedOsVersion);

        // Verify time zone matches system default
        ZoneId expectedTimeZone = ZoneId.systemDefault();
        assertThat(environment.getTimeZone()).isEqualTo(expectedTimeZone);

        // Verify platform is normalized (darwin, linux, or windows)
        String platform = environment.getPlatform();
        assertThat(platform).isIn("darwin", "linux", "windows").isNotEmpty();
    }

    @Test
    void testCreateWithWorkingDirectory_ExplicitPath_OverridesUserDirDefault() {
        // Arrange - a path that can never equal the JVM's launch directory
        String explicitWorkingDir = "/explicit/workspace/root";

        // Act
        Environment environment = Environment.createWithWorkingDirectory(explicitWorkingDir);

        // Assert - the explicit path wins over the derived default
        assertThat(environment.getWorkingDirectory()).isEqualTo(explicitWorkingDir);
        assertThat(environment.getWorkingDirectory()).isNotEqualTo(System.getProperty("user.dir"));

        // Assert - host-describing fields are still derived from the running system
        assertThat(environment.getOsVersion()).isEqualTo(System.getProperty("os.version"));
        assertThat(environment.getTimeZone()).isEqualTo(ZoneId.systemDefault());
        assertThat(environment.getPlatform()).isIn("darwin", "linux", "windows");
    }

    @Test
    void testCreateDefault_DelegatesToExplicitFactory_ProducesEqualEnvironment() {
        // Act
        Environment derived = Environment.createDefault();
        Environment explicit = Environment.createWithWorkingDirectory(System.getProperty("user.dir"));

        // Assert - the no-arg path still yields exactly the old user.dir-derived default
        assertThat(derived).isEqualTo(explicit);
        assertThat(derived.getWorkingDirectory()).isEqualTo(System.getProperty("user.dir"));
    }

    @Test
    void testBuilder_ValidInputs_CreatesEnvironment() {
        // Arrange
        String workingDir = "/test/directory";
        String platform = "darwin";
        String osVersion = "24.2.0";
        ZoneId timeZone = ZoneId.of("Asia/Seoul");

        // Act
        Environment environment = Environment.builder().workingDirectory(workingDir).platform(platform)
                .osVersion(osVersion).timeZone(timeZone).build();

        // Assert
        assertThat(environment.getWorkingDirectory()).isEqualTo(workingDir);
        assertThat(environment.getPlatform()).isEqualTo(platform);
        assertThat(environment.getOsVersion()).isEqualTo(osVersion);
        assertThat(environment.getTimeZone()).isEqualTo(timeZone);
    }

    @Test
    void testBuilder_WorkingDirectoryAsString_CreatesEnvironment() {
        // Arrange
        String workingDirString = "/test/directory";
        String platform = "linux";
        String osVersion = "5.15.0";
        ZoneId timeZone = ZoneId.of("UTC");

        // Act
        Environment environment = Environment.builder().workingDirectory(workingDirString).platform(platform)
                .osVersion(osVersion).timeZone(timeZone).build();

        // Assert
        assertThat(environment.getWorkingDirectory()).isEqualTo(workingDirString);
        assertThat(environment.getPlatform()).isEqualTo(platform);
        assertThat(environment.getOsVersion()).isEqualTo(osVersion);
        assertThat(environment.getTimeZone()).isEqualTo(timeZone);
    }

    @Test
    void testBuilder_NullWorkingDirectory_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> Environment.builder().workingDirectory((String) null).platform("darwin")
                .osVersion("24.2.0").timeZone(ZoneId.of("Asia/Seoul")).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("workingDirectory must not be null");
    }

    @Test
    void testBuilder_NullPlatform_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> Environment.builder().workingDirectory("/test").platform(null).osVersion("24.2.0")
                .timeZone(ZoneId.of("Asia/Seoul")).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("platform must not be null");
    }

    @Test
    void testBuilder_NullOsVersion_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> Environment.builder().workingDirectory("/test").platform("darwin").osVersion(null)
                .timeZone(ZoneId.of("Asia/Seoul")).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("osVersion must not be null");
    }

    @Test
    void testBuilder_NullTimeZone_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(null).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timeZone must not be null");
    }

    @Test
    void testEquals_SameValues_ReturnsTrue() {
        // Arrange
        ZoneId timeZone = ZoneId.of("Asia/Seoul");
        Environment env1 = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(timeZone).build();

        Environment env2 = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(timeZone).build();

        // Act & Assert
        assertThat(env1).isEqualTo(env2);
        assertThat(env1.hashCode()).isEqualTo(env2.hashCode());
    }

    @Test
    void testEquals_DifferentWorkingDirectory_ReturnsFalse() {
        // Arrange
        ZoneId timeZone = ZoneId.of("Asia/Seoul");
        Environment env1 = Environment.builder().workingDirectory("/test1").platform("darwin").osVersion("24.2.0")
                .timeZone(timeZone).build();

        Environment env2 = Environment.builder().workingDirectory("/test2").platform("darwin").osVersion("24.2.0")
                .timeZone(timeZone).build();

        // Act & Assert
        assertThat(env1).isNotEqualTo(env2);
    }

    @Test
    void testEquals_DifferentPlatform_ReturnsFalse() {
        // Arrange
        ZoneId timeZone = ZoneId.of("Asia/Seoul");
        Environment env1 = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(timeZone).build();

        Environment env2 = Environment.builder().workingDirectory("/test").platform("linux").osVersion("24.2.0")
                .timeZone(timeZone).build();

        // Act & Assert
        assertThat(env1).isNotEqualTo(env2);
    }

    @Test
    void testEquals_DifferentOsVersion_ReturnsFalse() {
        // Arrange
        ZoneId timeZone = ZoneId.of("Asia/Seoul");
        Environment env1 = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(timeZone).build();

        Environment env2 = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("25.0.0")
                .timeZone(timeZone).build();

        // Act & Assert
        assertThat(env1).isNotEqualTo(env2);
    }

    @Test
    void testEquals_DifferentTimeZone_ReturnsFalse() {
        // Arrange
        Environment env1 = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(ZoneId.of("Asia/Seoul")).build();

        Environment env2 = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(ZoneId.of("America/New_York")).build();

        // Act & Assert
        assertThat(env1).isNotEqualTo(env2);
    }

    @Test
    void testEquals_SameInstance_ReturnsTrue() {
        // Arrange
        Environment env = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(ZoneId.of("Asia/Seoul")).build();

        // Act & Assert
        assertThat(env).isEqualTo(env);
    }

    @Test
    void testEquals_Null_ReturnsFalse() {
        // Arrange
        Environment env = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(ZoneId.of("Asia/Seoul")).build();

        // Act & Assert
        assertThat(env).isNotEqualTo(null);
    }

    @Test
    void testEquals_DifferentClass_ReturnsFalse() {
        // Arrange
        Environment env = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(ZoneId.of("Asia/Seoul")).build();

        // Act & Assert
        assertThat(env).isNotEqualTo("not an environment");
    }

    @Test
    void testToString_ValidEnvironment_ContainsAllFields() {
        // Arrange
        Environment environment = Environment.builder().workingDirectory("/test/directory").platform("darwin")
                .osVersion("24.2.0").timeZone(ZoneId.of("Asia/Seoul")).build();

        // Act
        String result = environment.toString();

        // Assert
        assertThat(result).contains("Environment{").contains("workingDirectory=").contains("/test/directory")
                .contains("platform='darwin'").contains("osVersion='24.2.0'").contains("timeZone=Asia/Seoul");
    }

    @Test
    void testBuilder_AllPlatforms_AcceptsValidValues() {
        ZoneId timeZone = ZoneId.of("UTC");

        // Test darwin
        Environment darwin = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(timeZone).build();
        assertThat(darwin.getPlatform()).isEqualTo("darwin");

        // Test linux
        Environment linux = Environment.builder().workingDirectory("/test").platform("linux").osVersion("5.15.0")
                .timeZone(timeZone).build();
        assertThat(linux.getPlatform()).isEqualTo("linux");

        // Test windows
        Environment windows = Environment.builder().workingDirectory("C:\\test").platform("windows")
                .osVersion("10.0.19044").timeZone(timeZone).build();
        assertThat(windows.getPlatform()).isEqualTo("windows");
    }

    @Test
    void testBuilder_VariousTimeZones_AcceptsValidValues() {
        // Test Asia/Seoul
        Environment seoul = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(ZoneId.of("Asia/Seoul")).build();
        assertThat(seoul.getTimeZone()).isEqualTo(ZoneId.of("Asia/Seoul"));

        // Test America/New_York
        Environment newYork = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(ZoneId.of("America/New_York")).build();
        assertThat(newYork.getTimeZone()).isEqualTo(ZoneId.of("America/New_York"));

        // Test UTC
        Environment utc = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(ZoneId.of("UTC")).build();
        assertThat(utc.getTimeZone()).isEqualTo(ZoneId.of("UTC"));

        // Test Europe/London
        Environment london = Environment.builder().workingDirectory("/test").platform("darwin").osVersion("24.2.0")
                .timeZone(ZoneId.of("Europe/London")).build();
        assertThat(london.getTimeZone()).isEqualTo(ZoneId.of("Europe/London"));
    }
}
