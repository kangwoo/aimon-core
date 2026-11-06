package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DataReach")
class DataReachTest {

    @Test
    @DisplayName("max() takes the wider reach in either order")
    void max() {
        assertThat(DataReach.ENVIRONMENT.max(DataReach.HOST)).isEqualTo(DataReach.HOST);
        assertThat(DataReach.HOST.max(DataReach.ENVIRONMENT)).isEqualTo(DataReach.HOST);
        assertThat(DataReach.HOST.max(DataReach.EXTERNAL)).isEqualTo(DataReach.EXTERNAL);
        assertThat(DataReach.EXTERNAL.max(DataReach.ENVIRONMENT)).isEqualTo(DataReach.EXTERNAL);
        assertThat(DataReach.ENVIRONMENT.max(DataReach.ENVIRONMENT)).isEqualTo(DataReach.ENVIRONMENT);
    }
}
