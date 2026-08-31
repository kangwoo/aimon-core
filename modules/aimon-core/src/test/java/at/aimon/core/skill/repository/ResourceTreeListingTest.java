package at.aimon.core.skill.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ResourceTreeListingTest {

    @Test
    void enumeratedCarriesTheFiles() {
        final ResourceTreeListing listing = ResourceTreeListing.enumerated(List.of("SKILL.md", "scripts/run.sh"));

        assertThat(listing.isEnumerated()).isTrue();
        assertThat(listing.getFiles()).containsExactly("SKILL.md", "scripts/run.sh");
        assertThat(listing.getUnsupportedProtocol()).isEmpty();
    }

    @Test
    void emptyIsEnumerated() {
        // The whole point of the type: "nothing there" is a fact about the directory, not about the layout.
        final ResourceTreeListing listing = ResourceTreeListing.empty();

        assertThat(listing.isEnumerated()).isTrue();
        assertThat(listing.getFiles()).isEmpty();
        assertThat(listing.getUnsupportedProtocol()).isEmpty();
    }

    @Test
    void enumeratedWithNoFilesEqualsEmpty() {
        assertThat(ResourceTreeListing.enumerated(List.of())).isSameAs(ResourceTreeListing.empty());
    }

    @Test
    void unsupportedIsNotEnumeratedAndNamesTheProtocol() {
        final ResourceTreeListing listing = ResourceTreeListing.unsupported("vfs");

        assertThat(listing.isEnumerated()).isFalse();
        assertThat(listing.getUnsupportedProtocol()).contains("vfs");
        assertThat(listing.getFiles()).isEmpty();
    }

    @Test
    void unsupportedAndEmptyAreDistinguishable() {
        // Both hold zero files. Before this type they were the same value, and callers could only report the second.
        assertThat(ResourceTreeListing.unsupported("jrt").getFiles()).isEqualTo(ResourceTreeListing.empty().getFiles());
        assertThat(ResourceTreeListing.unsupported("jrt").isEnumerated())
                .isNotEqualTo(ResourceTreeListing.empty().isEnumerated());
    }

    @Test
    void filesAreDefensivelyCopied() {
        final List<String> source = new ArrayList<>(List.of("SKILL.md"));
        final ResourceTreeListing listing = ResourceTreeListing.enumerated(source);

        source.add("added-later.md");

        assertThat(listing.getFiles()).containsExactly("SKILL.md");
    }

    @Test
    void filesAreUnmodifiable() {
        final ResourceTreeListing listing = ResourceTreeListing.enumerated(List.of("SKILL.md"));

        assertThatThrownBy(() -> listing.getFiles().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void enumeratedRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> ResourceTreeListing.enumerated(null));
    }

    @Test
    void unsupportedRejectsNullAndBlank() {
        assertThatNullPointerException().isThrownBy(() -> ResourceTreeListing.unsupported(null));
        assertThatIllegalArgumentException().isThrownBy(() -> ResourceTreeListing.unsupported("  "));
    }

    @Test
    void toStringSaysWhichOutcomeItIs() {
        assertThat(ResourceTreeListing.enumerated(List.of("a", "b")))
                .hasToString("ResourceTreeListing[enumerated, files=2]");
        assertThat(ResourceTreeListing.unsupported("vfs"))
                .hasToString("ResourceTreeListing[not enumerated, protocol=vfs]");
    }
}
