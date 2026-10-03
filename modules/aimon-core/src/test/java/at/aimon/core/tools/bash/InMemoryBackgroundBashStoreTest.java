package at.aimon.core.tools.bash;

import org.junit.jupiter.api.DisplayName;

@DisplayName("InMemoryBackgroundBashStore — the BackgroundBashStore contract")
class InMemoryBackgroundBashStoreTest extends BackgroundBashStoreContractTest {

    @Override
    protected BackgroundBashStore createStore() {
        return new InMemoryBackgroundBashStore();
    }
}
