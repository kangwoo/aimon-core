package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WikiSearchResult")
class WikiSearchResultTest {

    @Test
    @DisplayName("constructor stores page and score")
    void constructorStoresFields() {
        WikiPage page = WikiPage.builder().path("/wiki/foo.md").title("Foo").content("# Foo").build();

        WikiSearchResult result = new WikiSearchResult(page, 4.2);

        assertThat(result.getPage()).isSameAs(page);
        assertThat(result.getScore()).isEqualTo(4.2);
    }

    @Test
    @DisplayName("null page throws NullPointerException")
    void nullPageThrows() {
        assertThatThrownBy(() -> new WikiSearchResult(null, 1.0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("zero score is allowed")
    void zeroScoreAllowed() {
        WikiPage page = WikiPage.builder().path("/wiki/foo.md").title("Foo").content("# Foo").build();

        WikiSearchResult result = new WikiSearchResult(page, 0.0);

        assertThat(result.getScore()).isZero();
    }

    @Test
    @DisplayName("toString includes path and score")
    void toStringIncludesFields() {
        WikiPage page = WikiPage.builder().path("/wiki/foo.md").title("Foo").content("# Foo").build();

        WikiSearchResult result = new WikiSearchResult(page, 1.5);

        assertThat(result.toString()).contains("/wiki/foo.md").contains("1.5");
    }

    @Test
    @DisplayName("builder produces an equivalent result")
    void builderEquivalentToConstructor() {
        WikiPage page = WikiPage.builder().path("/wiki/foo.md").title("Foo").content("# Foo").build();

        WikiSearchResult viaBuilder = WikiSearchResult.builder().page(page).score(3.14).build();
        WikiSearchResult viaCtor = new WikiSearchResult(page, 3.14);

        assertThat(viaBuilder.getPage()).isSameAs(viaCtor.getPage());
        assertThat(viaBuilder.getScore()).isEqualTo(viaCtor.getScore());
    }

    @Test
    @DisplayName("builder score defaults to 0.0 when not set")
    void builderScoreDefaultsToZero() {
        WikiPage page = WikiPage.builder().path("/wiki/foo.md").title("Foo").content("# Foo").build();

        WikiSearchResult result = WikiSearchResult.builder().page(page).build();

        assertThat(result.getScore()).isZero();
    }

    @Test
    @DisplayName("builder without page throws NullPointerException")
    void builderWithoutPageThrows() {
        assertThatThrownBy(() -> WikiSearchResult.builder().score(1.0).build())
                .isInstanceOf(NullPointerException.class);
    }
}
