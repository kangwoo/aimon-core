package at.aimon.browser.playwright.dom;

/**
 * 안정적 CSS selector를 생성하는 유틸리티.
 *
 * <p>
 * Selector 생성 우선순위:
 * <ol>
 * <li>data-testid / data-test
 * <li>고유 id
 * <li>role + aria-label 조합
 * <li>aria-label
 * <li>CSS path (최대한 짧게, nth-of-type 사용)
 * </ol>
 */
public final class SelectorGenerator {

    private SelectorGenerator() {
    }

    /**
     * JavaScript 코드에서 DOM 요소에 대한 안정적 selector를 생성한다.
     * 이 메서드는 CandidateExtractor의 JS 스크립트 내에서 사용되므로
     * JavaScript 함수 형태로 제공한다.
     *
     * @return selector 생성 JavaScript 함수 문자열
     */
    static String selectorGeneratorJs() {
        return """
                function _escAttr(v) {
                  return v.replace(/"/g, '\\\\"');
                }
                function generateSelector(el, depth) {
                  if (depth === undefined) depth = 0;
                  if (!el || typeof el.getAttribute !== 'function')
                    return (el && el.tagName) ? el.tagName.toLowerCase() : '*';
                  if (depth > 10) return el.tagName.toLowerCase();
                  if (el.dataset && el.dataset.testid)
                    return '[data-testid="' + _escAttr(el.dataset.testid) + '"]';
                  if (el.dataset && el.dataset.test)
                    return '[data-test="' + _escAttr(el.dataset.test) + '"]';
                  if (el.id) return '#' + CSS.escape(el.id);
                  var ariaLabel = el.getAttribute('aria-label');
                  var role = el.getAttribute('role');
                  if (role && ariaLabel)
                    return '[role="' + _escAttr(role) + '"][aria-label="' + _escAttr(ariaLabel) + '"]';
                  if (ariaLabel) return '[aria-label="' + _escAttr(ariaLabel) + '"]';
                  var tag = el.tagName.toLowerCase();
                  var parent = el.parentElement;
                  if (!parent) return tag;
                  var siblings = Array.from(parent.children).filter(c => c.tagName === el.tagName);
                  if (siblings.length === 1)
                    return generateSelector(parent, depth + 1) + ' > ' + tag;
                  var index = siblings.indexOf(el) + 1;
                  return generateSelector(parent, depth + 1) + ' > ' + tag + ':nth-of-type(' + index + ')';
                }
                """;
    }
}
