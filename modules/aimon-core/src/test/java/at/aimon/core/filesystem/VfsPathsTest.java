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

    @Test
    @DisplayName("isUnderIgnoreCase folds Unicode letters a case-insensitive store treats as the same name")
    void isUnderIgnoreCaseUnicode() {
        // U+017F LATIN SMALL LETTER LONG S: APFS folds it to 's'
        assertThat(VfsPaths.isUnderIgnoreCase(".aimon-\u017Ftaged/k/run.sh", ".aimon-staged")).isTrue();
        assertThat(VfsPaths.isUnderIgnoreCase(".\u017Fecrets/key", ".secrets")).isTrue();
        // U+FB05 LATIN SMALL LIGATURE LONG S T
        assertThat(VfsPaths.isUnderIgnoreCase(".aimon-\uFB05aged/x", ".aimon-staged")).isTrue();
        // U+212A KELVIN SIGN folds to 'k'
        assertThat(VfsPaths.isUnderIgnoreCase("\u212Aeys/x", "keys")).isTrue();
        // NFD spelling of a precomposed prefix
        assertThat(VfsPaths.isUnderIgnoreCase("cafe\u0301/x", "caf\u00E9")).isTrue();
        // U+1E9E LATIN CAPITAL LETTER SHARP S: its full fold is "ss" (APFS opens .ssh through .ẞh)
        assertThat(VfsPaths.isUnderIgnoreCase(".\u1E9Eh/id", ".ssh")).isTrue();
        assertThat(VfsPaths.isUnderIgnoreCase(".\u00DFh/id", ".ssh")).isTrue();
        assertThat(VfsPaths.isUnderIgnoreCase(".ssh/id", ".\u1E9Eh")).isTrue();
        assertThat(VfsPaths.isUnderIgnoreCase("a\u1E9Eets/x", "assets")).isTrue();
        // Whole segments still
        assertThat(VfsPaths.isUnderIgnoreCase(".\u017Fecrets2/key", ".secrets")).isFalse();
    }
}
