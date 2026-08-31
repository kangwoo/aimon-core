package at.aimon.browser.playwright.dom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Page;

class CandidateExtractorTest {

    @Test
    void shouldExtractCandidatesFromValidResult() {
        Page page = mock(Page.class);
        List<Map<String, Object>> evaluateResult = List.of(
                Map.of("label", "Sign in", "selector", "#login", "role", "button", "text", "Login button"),
                Map.of("label", "Search", "selector", "input[name=q]", "role", "text"));
        when(page.evaluate(anyString())).thenReturn(evaluateResult);

        List<DomCandidate> candidates = CandidateExtractor.extract(page);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).getLabel()).isEqualTo("Sign in");
        assertThat(candidates.get(0).getSelector()).isEqualTo("#login");
        assertThat(candidates.get(0).getRole()).isEqualTo("button");
        assertThat(candidates.get(0).getText()).isEqualTo("Login button");
        assertThat(candidates.get(1).getLabel()).isEqualTo("Search");
        assertThat(candidates.get(1).getText()).isNull();
    }

    @Test
    void shouldReturnEmptyListForEmptyResult() {
        Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenReturn(List.of());

        List<DomCandidate> candidates = CandidateExtractor.extract(page);

        assertThat(candidates).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenResultIsNotList() {
        Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenReturn("not a list");

        List<DomCandidate> candidates = CandidateExtractor.extract(page);

        assertThat(candidates).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenResultIsNull() {
        Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenReturn(null);

        List<DomCandidate> candidates = CandidateExtractor.extract(page);

        assertThat(candidates).isEmpty();
    }

    @Test
    void shouldReturnEmptyListOnException() {
        Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenThrow(new RuntimeException("Timeout"));

        List<DomCandidate> candidates = CandidateExtractor.extract(page);

        assertThat(candidates).isEmpty();
    }

    @Test
    void shouldSkipNonMapItemsInResult() {
        Page page = mock(Page.class);
        List<Object> evaluateResult = List.of(Map.of("label", "OK", "selector", "#ok", "role", "button"), "not a map",
                42);
        when(page.evaluate(anyString())).thenReturn(evaluateResult);

        List<DomCandidate> candidates = CandidateExtractor.extract(page);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getLabel()).isEqualTo("OK");
    }

    @Test
    void shouldHandleNullValuesInMap() {
        Page page = mock(Page.class);
        Map<String, Object> map = new HashMap<>();
        map.put("label", null);
        map.put("selector", "#btn");
        map.put("role", "button");
        map.put("text", null);
        when(page.evaluate(anyString())).thenReturn(List.of(map));

        List<DomCandidate> candidates = CandidateExtractor.extract(page);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getLabel()).isNull();
        assertThat(candidates.get(0).getSelector()).isEqualTo("#btn");
        assertThat(candidates.get(0).getText()).isNull();
    }

    @Test
    void shouldReturnNullForNonStringValuesInMap() {
        Page page = mock(Page.class);
        List<Map<String, Object>> evaluateResult = List.of(Map.of("label", 123, "selector", true, "role", "button"));
        when(page.evaluate(anyString())).thenReturn(evaluateResult);

        List<DomCandidate> candidates = CandidateExtractor.extract(page);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getLabel()).isNull();
        assertThat(candidates.get(0).getSelector()).isNull();
    }
}
