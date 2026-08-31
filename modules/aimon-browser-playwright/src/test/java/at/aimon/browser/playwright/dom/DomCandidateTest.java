package at.aimon.browser.playwright.dom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomCandidateTest {

    @Test
    void shouldBuildWithAllFields() {
        DomCandidate candidate = DomCandidate.builder().label("Sign in").selector("button[data-testid=\"login\"]")
                .role("button").text("Click to sign in").build();

        assertThat(candidate.getLabel()).isEqualTo("Sign in");
        assertThat(candidate.getSelector()).isEqualTo("button[data-testid=\"login\"]");
        assertThat(candidate.getRole()).isEqualTo("button");
        assertThat(candidate.getText()).isEqualTo("Click to sign in");
    }

    @Test
    void shouldBuildWithNullFields() {
        DomCandidate candidate = DomCandidate.builder().label("Search").selector("input[name=\"q\"]").build();

        assertThat(candidate.getLabel()).isEqualTo("Search");
        assertThat(candidate.getSelector()).isEqualTo("input[name=\"q\"]");
        assertThat(candidate.getRole()).isNull();
        assertThat(candidate.getText()).isNull();
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        DomCandidate a = DomCandidate.builder().label("OK").selector("#ok").role("button").build();
        DomCandidate b = DomCandidate.builder().label("OK").selector("#ok").role("button").build();
        DomCandidate c = DomCandidate.builder().label("Cancel").selector("#cancel").role("button").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void shouldImplementToString() {
        DomCandidate candidate = DomCandidate.builder().label("OK").selector("#ok").role("button").build();

        assertThat(candidate.toString()).contains("OK");
        assertThat(candidate.toString()).contains("#ok");
        assertThat(candidate.toString()).contains("button");
    }
}
