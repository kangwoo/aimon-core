package at.aimon.core.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VfsPaths")
class VfsPathsTest {

    @Test
    @DisplayName("a local base: relative, ./ and absolute-under-base forms collapse to one root-relative path")
    void localBase() {
        assertThat(VfsPaths.rootRelative("/proj", "a.txt")).isEqualTo("a.txt");
        assertThat(VfsPaths.rootRelative("/proj", "./a.txt")).isEqualTo("a.txt");
        assertThat(VfsPaths.rootRelative("/proj", "/proj/a.txt")).isEqualTo("a.txt");
        assertThat(VfsPaths.rootRelative("/proj/", "/proj/sub/../a.txt")).isEqualTo("a.txt");
        assertThat(VfsPaths.rootRelative("/proj", "/proj")).isEmpty();
    }

    @Test
    @DisplayName("a local base: an absolute path outside the base is returned normalised, not relativised")
    void outsideBase() {
        assertThat(VfsPaths.rootRelative("/proj", "/etc/./passwd")).isEqualTo("/etc/passwd");
        assertThat(VfsPaths.rootRelative("/proj", "/proj2/x")).isEqualTo("/proj2/x");
    }

    @Test
    @DisplayName("a URI base: leading slashes are root-anchored")
    void uriBase() {
        assertThat(VfsPaths.rootRelative("gridfs://db/bucket", "/a.txt")).isEqualTo("a.txt");
        assertThat(VfsPaths.rootRelative("s3://bucket", "a/./b.txt")).isEqualTo("a/b.txt");
    }

    @Test
    @DisplayName("a relative base: ./ is stripped, a /-leading path is left absolute")
    void relativeBase() {
        assertThat(VfsPaths.rootRelative(".", "./a/b.txt")).isEqualTo("a/b.txt");
        assertThat(VfsPaths.rootRelative(".", "/proj/a.txt")).isEqualTo("/proj/a.txt");
        assertThat(VfsPaths.rootRelative(null, "a")).isEqualTo("a");
    }

    @Test
    @DisplayName("a relative path escaping above the root yields null")
    void escape() {
        assertThat(VfsPaths.rootRelative("/proj", "../x")).isNull();
        assertThat(VfsPaths.normalizeRelative("a/../../x")).isNull();
        assertThat(VfsPaths.normalizeRelative("a/../.aimon/x")).isEqualTo(".aimon/x");
    }

    @Test
    @DisplayName("isUnder matches whole segments only")
    void isUnder() {
        assertThat(VfsPaths.isUnder(".aimon-staged/x", ".aimon-staged")).isTrue();
        assertThat(VfsPaths.isUnder(".aimon-staged", ".aimon-staged")).isTrue();
        assertThat(VfsPaths.isUnder(".aimon-staged2/x", ".aimon-staged")).isFalse();
        assertThat(VfsPaths.isUnder("x", "")).isFalse();
        assertThat(VfsPaths.isUnder(null, "a")).isFalse();
    }

    @Test
    @DisplayName("join uses single slashes and keeps a leading slash or scheme")
    void join() {
        assertThat(VfsPaths.join("/proj/", "/.aimon-staged/", "demo", "k")).isEqualTo("/proj/.aimon-staged/demo/k");
        assertThat(VfsPaths.join("artifacts", "key", "f.txt")).isEqualTo("artifacts/key/f.txt");
        assertThat(VfsPaths.join("/", "a")).isEqualTo("/a");
        assertThat(VfsPaths.join("s3://b", "x")).isEqualTo("s3://b/x");
        assertThat(VfsPaths.join("a", ".", "")).isEqualTo("a");
    }

    @Test
    @DisplayName("resolveUnder: a path that leaves the base and comes back lands in the base; one that stays out is null")
    void resolveUnder() {
        assertThat(VfsPaths.resolveUnder("/w/proj", "a/b")).isEqualTo("a/b");
        assertThat(VfsPaths.resolveUnder("/w/proj", "../proj/.aimon/x")).isEqualTo(".aimon/x");
        assertThat(VfsPaths.resolveUnder("/w/proj", "/w/proj/../proj/.aimon/x")).isEqualTo(".aimon/x");
        assertThat(VfsPaths.resolveUnder("/w/proj/", "/w/proj")).isEmpty();
        assertThat(VfsPaths.resolveUnder("/w/proj", "../proj2/x")).isNull();
        assertThat(VfsPaths.resolveUnder("/w/proj", "/etc/hosts")).isNull();
        assertThat(VfsPaths.resolveUnder("/w/proj", "../../../../x")).isNull();
        assertThat(VfsPaths.resolveUnder("/", "/a/../b")).isEqualTo("b");
        assertThat(VfsPaths.resolveUnder("C:/w/proj", "../proj/.aimon")).isEqualTo(".aimon");
        assertThat(VfsPaths.resolveUnder("C:\\w\\proj", "C:\\w\\proj\\..\\proj\\.aimon")).isEqualTo(".aimon");
        assertThat(VfsPaths.resolveUnder("s3://bucket", "/a/./b")).isEqualTo("a/b");
        assertThat(VfsPaths.resolveUnder(".", "../x")).isNull();
    }

    @Test
    @DisplayName("isUnderIgnoreCase matches whole segments regardless of case")
    void isUnderIgnoreCase() {
        assertThat(VfsPaths.isUnderIgnoreCase(".AIMON/x", ".aimon")).isTrue();
        assertThat(VfsPaths.isUnderIgnoreCase(".Aimon-Staged", ".aimon-staged")).isTrue();
        assertThat(VfsPaths.isUnderIgnoreCase(".AIMON2/x", ".aimon")).isFalse();
        assertThat(VfsPaths.isUnderIgnoreCase(null, ".aimon")).isFalse();
    }
}
