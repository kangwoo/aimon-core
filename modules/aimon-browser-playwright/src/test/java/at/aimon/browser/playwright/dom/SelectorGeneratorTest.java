package at.aimon.browser.playwright.dom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SelectorGeneratorTest {

    @Test
    void shouldContainEscapeHelperFunction() {
        String js = SelectorGenerator.selectorGeneratorJs();

        assertThat(js).contains("function _escAttr(v)");
    }

    @Test
    void shouldContainGenerateSelectorFunction() {
        String js = SelectorGenerator.selectorGeneratorJs();

        assertThat(js).contains("function generateSelector(el, depth)");
    }

    @Test
    void shouldEscapeAttributeValuesWithHelper() {
        String js = SelectorGenerator.selectorGeneratorJs();

        assertThat(js).contains("_escAttr(el.dataset.testid)");
        assertThat(js).contains("_escAttr(el.dataset.test)");
        assertThat(js).contains("_escAttr(ariaLabel)");
        assertThat(js).contains("_escAttr(role)");
    }

    @Test
    void shouldUseNthOfTypeInsteadOfNthChild() {
        String js = SelectorGenerator.selectorGeneratorJs();

        assertThat(js).contains("nth-of-type");
        assertThat(js).doesNotContain("nth-child");
    }

    @Test
    void shouldGuardAgainstNonElementNodes() {
        String js = SelectorGenerator.selectorGeneratorJs();

        assertThat(js).contains("typeof el.getAttribute !== 'function'");
    }

    @Test
    void shouldCheckIdBeforeAriaLabel() {
        String js = SelectorGenerator.selectorGeneratorJs();

        int idIndex = js.indexOf("if (el.id)");
        int ariaLabelIndex = js.indexOf("var ariaLabel");
        assertThat(idIndex).isPositive().isLessThan(ariaLabelIndex);
    }

    @Test
    void shouldCheckRoleWithAriaLabelBeforeAriaLabelAlone() {
        String js = SelectorGenerator.selectorGeneratorJs();

        int roleComboIndex = js.indexOf("role && ariaLabel");
        int ariaAloneIndex = js.indexOf("if (ariaLabel) return");
        assertThat(roleComboIndex).isPositive().isLessThan(ariaAloneIndex);
    }
}
