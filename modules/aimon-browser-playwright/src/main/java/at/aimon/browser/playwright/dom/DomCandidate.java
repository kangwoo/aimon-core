package at.aimon.browser.playwright.dom;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * LLM에 제공할 클릭 가능한 DOM 요소 후보.
 *
 * <p>
 * 브라우저 액션 수행 후 LLM이 다음 행동을 결정할 수 있도록
 * 상호작용 가능한 요소의 정보를 제공한다.
 *
 * <p>
 * Immutable value object with Builder pattern.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DomCandidate {

    private final String label;
    private final String selector;
    private final String role;
    private final String text;

    private DomCandidate(Builder builder) {
        this.label = builder.label;
        this.selector = builder.selector;
        this.role = builder.role;
        this.text = builder.text;
    }

    /**
     * Creates a new Builder.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getLabel() {
        return label;
    }

    public String getSelector() {
        return selector;
    }

    public String getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DomCandidate other)) {
            return false;
        }
        return Objects.equals(label, other.label) && Objects.equals(selector, other.selector)
                && Objects.equals(role, other.role) && Objects.equals(text, other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, selector, role, text);
    }

    @Override
    public String toString() {
        return "DomCandidate{label='" + label + "', selector='" + selector + "', role='" + role + "'}";
    }

    public static final class Builder {

        private String label;
        private String selector;
        private String role;
        private String text;

        private Builder() {
        }

        /** Sets the visible label text. */
        public Builder label(String label) {
            this.label = label;
            return this;
        }

        /** Sets the CSS selector. */
        public Builder selector(String selector) {
            this.selector = selector;
            return this;
        }

        /** Sets the ARIA role. */
        public Builder role(String role) {
            this.role = role;
            return this;
        }

        /** Sets the auxiliary text (placeholder or aria-label). */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /** Builds the DomCandidate. */
        public DomCandidate build() {
            return new DomCandidate(this);
        }
    }
}
