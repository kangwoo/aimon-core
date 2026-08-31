package at.aimon.browser.playwright.dom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Page;

/**
 * 페이지에서 상호작용 가능한 DOM 요소를 수집한다.
 *
 * <p>
 * 수집 대상:
 * {@code a}, {@code button}, {@code [role=button]}, {@code input},
 * {@code select}, {@code textarea}, {@code [role=link]},
 * {@code [role=tab]}, {@code [role=menuitem]}
 *
 * <p>
 * 보이는(visible) 요소만 포함하며 최대 30개까지 반환한다.
 */
public final class CandidateExtractor {

    private static final Logger log = LoggerFactory.getLogger(CandidateExtractor.class);

    static final int MAX_CANDIDATES = 30;

    private static final String EXTRACT_SCRIPT = buildExtractScript();

    // @formatter:off
    private static final String EXTRACT_TEMPLATE = """
            (function() {
              var selectors = 'a, button, [role=button], input, select, '
                + 'textarea, [role=link], [role=tab], [role=menuitem]';
              var elements = Array.from(document.querySelectorAll(selectors));
              var results = [];
              for (var i = 0; i < elements.length && results.length < %d; i++) {
                var el = elements[i];
                var rect = el.getBoundingClientRect();
                if (rect.width === 0 && rect.height === 0) continue;
                var style = window.getComputedStyle(el);
                if (style.display === 'none' || style.visibility === 'hidden') continue;
                var label = (el.textContent || '').trim().substring(0, 80);
                var role = el.getAttribute('role') || el.tagName.toLowerCase();
                if (el.tagName === 'INPUT') role = el.type || 'text';
                if (el.tagName === 'A') role = 'link';
                if (el.tagName === 'BUTTON') role = 'button';
                if (el.tagName === 'SELECT') role = 'combobox';
                if (el.tagName === 'TEXTAREA') role = 'textbox';
                var text = el.getAttribute('placeholder')
                  || el.getAttribute('aria-label') || null;
                results.push({
                  label: label || null,
                  selector: generateSelector(el),
                  role: role,
                  text: text
                });
              }
              return results;
            })()
            """;
    // @formatter:on

    private static String buildExtractScript() {
        return SelectorGenerator.selectorGeneratorJs() + EXTRACT_TEMPLATE.formatted(MAX_CANDIDATES);
    }

    private CandidateExtractor() {
    }

    /**
     * 페이지에서 상호작용 가능한 요소를 수집하여 DomCandidate 목록으로 반환한다.
     *
     * @param page
     *            Playwright Page
     * @return DomCandidate 목록 (최대 {@value MAX_CANDIDATES}개)
     */
    @SuppressWarnings("unchecked")
    public static List<DomCandidate> extract(Page page) {
        try {
            Object result = page.evaluate(EXTRACT_SCRIPT);
            if (!(result instanceof List<?>)) {
                return Collections.emptyList();
            }

            List<DomCandidate> candidates = new ArrayList<>();
            for (Object item : (List<?>) result) {
                if (item instanceof Map<?, ?> map) {
                    DomCandidate candidate = DomCandidate.builder().label(castString(map.get("label")))
                            .selector(castString(map.get("selector"))).role(castString(map.get("role")))
                            .text(castString(map.get("text"))).build();
                    candidates.add(candidate);
                }
            }
            return candidates;
        } catch (Exception e) {
            log.warn("Failed to extract candidates: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String castString(Object obj) {
        return obj instanceof String s ? s : null;
    }
}
