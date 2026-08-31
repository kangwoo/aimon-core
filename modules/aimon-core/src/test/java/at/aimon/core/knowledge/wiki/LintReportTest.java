package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LintReport")
class LintReportTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with valid values stores all fields")
        void builderWithValidValues() {
            Instant now = Instant.now();
            LintReport.Issue issue = new LintReport.Issue(LintReport.Severity.WARNING, "/wiki/page.md", "Broken link");

            LintReport report = LintReport.builder().issues(List.of(issue)).checkedPageCount(5).checkedAt(now).build();

            assertThat(report.getIssues()).hasSize(1);
            assertThat(report.getIssues().get(0)).isSameAs(issue);
            assertThat(report.getCheckedPageCount()).isEqualTo(5);
            assertThat(report.getCheckedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("issues default to empty list when not set")
        void issuesDefaultToEmpty() {
            LintReport report = LintReport.builder().checkedAt(Instant.now()).build();

            assertThat(report.getIssues()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("negative checkedPageCount throws IllegalArgumentException")
        void negativeCheckedPageCountThrowsIae() {
            assertThatThrownBy(() -> LintReport.builder().checkedPageCount(-1).checkedAt(Instant.now()).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null checkedAt throws NullPointerException")
        void nullCheckedAtThrowsNpe() {
            assertThatThrownBy(() -> LintReport.builder().checkedAt(null).build())
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("isHealthy")
    class IsHealthy {

        @Test
        @DisplayName("returns true when no issues")
        void trueWhenNoIssues() {
            LintReport report = LintReport.builder().checkedAt(Instant.now()).build();

            assertThat(report.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("returns false when there are issues")
        void falseWhenIssuesExist() {
            LintReport report = LintReport.builder()
                    .issues(List.of(new LintReport.Issue(LintReport.Severity.ERROR, null, "Wiki-wide issue")))
                    .checkedAt(Instant.now()).build();

            assertThat(report.isHealthy()).isFalse();
        }
    }

    @Nested
    @DisplayName("countBySeverity")
    class CountBySeverity {

        @Test
        @DisplayName("counts issues matching given severity")
        void countsMatchingSeverity() {
            LintReport report = LintReport.builder()
                    .issues(List.of(new LintReport.Issue(LintReport.Severity.ERROR, "/wiki/a.md", "Error 1"),
                            new LintReport.Issue(LintReport.Severity.WARNING, "/wiki/b.md", "Warning 1"),
                            new LintReport.Issue(LintReport.Severity.ERROR, "/wiki/c.md", "Error 2"),
                            new LintReport.Issue(LintReport.Severity.INFO, null, "Info 1")))
                    .checkedAt(Instant.now()).build();

            assertThat(report.countBySeverity(LintReport.Severity.ERROR)).isEqualTo(2);
            assertThat(report.countBySeverity(LintReport.Severity.WARNING)).isEqualTo(1);
            assertThat(report.countBySeverity(LintReport.Severity.INFO)).isEqualTo(1);
        }

        @Test
        @DisplayName("returns zero when no issues match severity")
        void returnsZeroWhenNoMatch() {
            LintReport report = LintReport.builder().checkedAt(Instant.now()).build();

            assertThat(report.countBySeverity(LintReport.Severity.ERROR)).isZero();
        }
    }

    @Nested
    @DisplayName("Issue construction")
    class IssueConstruction {

        @Test
        @DisplayName("valid Issue stores all fields")
        void validIssue() {
            LintReport.Issue issue = new LintReport.Issue(LintReport.Severity.WARNING, "/wiki/page.md",
                    "Broken link detected");

            assertThat(issue.getSeverity()).isEqualTo(LintReport.Severity.WARNING);
            assertThat(issue.getPagePath()).isEqualTo("/wiki/page.md");
            assertThat(issue.getMessage()).isEqualTo("Broken link detected");
        }

        @Test
        @DisplayName("pagePath can be null for wiki-wide issues")
        void pagePathCanBeNull() {
            LintReport.Issue issue = new LintReport.Issue(LintReport.Severity.INFO, null, "Wiki-wide info");

            assertThat(issue.getPagePath()).isNull();
        }

        @Test
        @DisplayName("null severity throws NullPointerException")
        void nullSeverityThrowsNpe() {
            assertThatThrownBy(() -> new LintReport.Issue(null, "/wiki/page.md", "message"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null message throws NullPointerException")
        void nullMessageThrowsNpe() {
            assertThatThrownBy(() -> new LintReport.Issue(LintReport.Severity.INFO, "/wiki/page.md", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("issues list is unmodifiable")
        void issuesAreUnmodifiable() {
            LintReport.Issue issue = new LintReport.Issue(LintReport.Severity.INFO, null, "Info message");

            LintReport report = LintReport.builder().issues(new ArrayList<>(List.of(issue))).checkedAt(Instant.now())
                    .build();

            assertThatThrownBy(() -> report.getIssues().add(issue)).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
