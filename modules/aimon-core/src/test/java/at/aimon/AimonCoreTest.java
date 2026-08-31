package at.aimon;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutor;

@DisplayName("Aimon Core Module Tests")
class AimonCoreTest {

    @Test
    @DisplayName("Module should be testable")
    void moduleShouldBeTestable() {
        // This is a basic test to verify the test infrastructure works
        assertTrue(true, "Module test infrastructure is working");
    }

    @Test
    @DisplayName("Package structure should be correct")
    void packageStructureShouldBeCorrect() {
        String expectedPackage = "at.aimon.core.agent";
        String actualPackage = AgentExecutor.class.getPackage().getName();

        assertEquals(expectedPackage, actualPackage, "Package structure should match convention");
    }
}
