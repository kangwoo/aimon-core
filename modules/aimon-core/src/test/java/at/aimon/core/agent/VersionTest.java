package at.aimon.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void testVersionConstructor() {
        final Version version = new Version(1, 2, 3);
        assertEquals(1, version.getMajor());
        assertEquals(2, version.getMinor());
        assertEquals(3, version.getPatch());
        assertNull(version.getQualifier());
    }

    @Test
    void testVersionConstructorWithQualifier() {
        final Version version = new Version(1, 2, 3, "SNAPSHOT");
        assertEquals(1, version.getMajor());
        assertEquals(2, version.getMinor());
        assertEquals(3, version.getPatch());
        assertEquals("SNAPSHOT", version.getQualifier());
    }

    @Test
    void testVersionConstructorNegativeNumbers() {
        assertThrows(IllegalArgumentException.class, () -> new Version(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Version(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Version(0, 0, -1));
    }

    @Test
    void testParseValidVersions() {
        Version version = Version.parse("1.2.3");
        assertEquals(1, version.getMajor());
        assertEquals(2, version.getMinor());
        assertEquals(3, version.getPatch());
        assertNull(version.getQualifier());

        version = Version.parse("0.1.0-SNAPSHOT");
        assertEquals(0, version.getMajor());
        assertEquals(1, version.getMinor());
        assertEquals(0, version.getPatch());
        assertEquals("SNAPSHOT", version.getQualifier());

        version = Version.parse("2.0.0-beta.1");
        assertEquals(2, version.getMajor());
        assertEquals(0, version.getMinor());
        assertEquals(0, version.getPatch());
        assertEquals("beta.1", version.getQualifier());
    }

    @Test
    void testParseInvalidVersions() {
        assertThrows(IllegalArgumentException.class, () -> Version.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Version.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.2"));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.2.3.4"));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("a.b.c"));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.2.3-"));
    }

    @Test
    void testIsSnapshot() {
        assertTrue(new Version(1, 0, 0, "SNAPSHOT").isSnapshot());
        assertTrue(new Version(1, 0, 0, "snapshot").isSnapshot());
        assertTrue(new Version(1, 0, 0, "1.0-SNAPSHOT").isSnapshot());
        assertFalse(new Version(1, 0, 0).isSnapshot());
        assertFalse(new Version(1, 0, 0, "beta").isSnapshot());
    }

    @Test
    void testIsPreRelease() {
        assertTrue(new Version(1, 0, 0, "SNAPSHOT").isPreRelease());
        assertTrue(new Version(1, 0, 0, "beta").isPreRelease());
        assertTrue(new Version(1, 0, 0, "alpha.1").isPreRelease());
        assertFalse(new Version(1, 0, 0).isPreRelease());
        assertFalse(new Version(1, 0, 0, "").isPreRelease());
    }

    @Test
    void testCompareTo() {
        // Test major version comparison
        assertTrue(new Version(2, 0, 0).compareTo(new Version(1, 0, 0)) > 0);
        assertTrue(new Version(1, 0, 0).compareTo(new Version(2, 0, 0)) < 0);

        // Test minor version comparison
        assertTrue(new Version(1, 2, 0).compareTo(new Version(1, 1, 0)) > 0);
        assertTrue(new Version(1, 1, 0).compareTo(new Version(1, 2, 0)) < 0);

        // Test patch version comparison
        assertTrue(new Version(1, 0, 2).compareTo(new Version(1, 0, 1)) > 0);
        assertTrue(new Version(1, 0, 1).compareTo(new Version(1, 0, 2)) < 0);

        // Test qualifier comparison - release > pre-release
        assertTrue(new Version(1, 0, 0).compareTo(new Version(1, 0, 0, "SNAPSHOT")) > 0);
        assertTrue(new Version(1, 0, 0, "SNAPSHOT").compareTo(new Version(1, 0, 0)) < 0);

        // Test qualifier comparison - alphabetical
        assertTrue(new Version(1, 0, 0, "beta").compareTo(new Version(1, 0, 0, "alpha")) > 0);
        assertTrue(new Version(1, 0, 0, "alpha").compareTo(new Version(1, 0, 0, "beta")) < 0);

        // Test equality
        assertEquals(0, new Version(1, 0, 0).compareTo(new Version(1, 0, 0)));
        assertEquals(0, new Version(1, 0, 0, "SNAPSHOT").compareTo(new Version(1, 0, 0, "SNAPSHOT")));
    }

    @Test
    void testCompareToNull() {
        assertThrows(NullPointerException.class, () -> new Version(1, 0, 0).compareTo(null));
    }

    @Test
    void testEquals() {
        final Version v1 = new Version(1, 2, 3);
        final Version v2 = new Version(1, 2, 3);
        final Version v3 = new Version(1, 2, 4);
        final Version v4 = new Version(1, 2, 3, "SNAPSHOT");
        final Version v5 = new Version(1, 2, 3, "SNAPSHOT");

        assertEquals(v1, v1); // same object
        assertEquals(v1, v2); // same values
        assertNotEquals(v1, v3); // different patch
        assertNotEquals(v1, v4); // different qualifier
        assertEquals(v4, v5); // same with qualifier
        assertNotEquals(v1, null);
        assertNotEquals(v1, "1.2.3");
    }

    @Test
    void testHashCode() {
        final Version v1 = new Version(1, 2, 3);
        final Version v2 = new Version(1, 2, 3);
        final Version v3 = new Version(1, 2, 3, "SNAPSHOT");
        final Version v4 = new Version(1, 2, 3, "SNAPSHOT");

        assertEquals(v1.hashCode(), v2.hashCode());
        assertEquals(v3.hashCode(), v4.hashCode());
        assertNotEquals(v1.hashCode(), v3.hashCode());
    }

    @Test
    void testToString() {
        assertEquals("1.2.3", new Version(1, 2, 3).toString());
        assertEquals("0.1.0-SNAPSHOT", new Version(0, 1, 0, "SNAPSHOT").toString());
        assertEquals("2.0.0-beta.1", new Version(2, 0, 0, "beta.1").toString());
    }

    @Test
    void testParseAndToStringRoundTrip() {
        final String[] versions = {"1.2.3", "0.1.0-SNAPSHOT", "2.0.0-beta.1", "10.20.30-alpha.0"};

        for (final String versionString : versions) {
            final Version version = Version.parse(versionString);
            assertEquals(versionString, version.toString());
        }
    }
}
