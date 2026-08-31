package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WikiIo")
class WikiIoTest {

    @Nested
    @DisplayName("parseWikiPage — type extraction")
    class TypeExtraction {

        @Test
        @DisplayName("explicit frontmatter type is honored")
        void explicitFrontmatterType() {
            String content = """
                    ---
                    title: Kubernetes Pod
                    type: entity
                    tags: [k8s]
                    ---

                    # Kubernetes Pod
                    """;

            WikiPage page = WikiIo.parseWikiPage("/wiki/pages/summary-k8s.md", content);

            assertThat(page.getType()).isEqualTo(WikiPageType.ENTITY);
        }

        @Test
        @DisplayName("missing frontmatter type falls back to file-name prefix")
        void fileNameFallback() {
            String content = "# Eventual Consistency\n\nA concept.\n";

            WikiPage page = WikiIo.parseWikiPage("/wiki/pages/concept-eventual.md", content);

            assertThat(page.getType()).isEqualTo(WikiPageType.CONCEPT);
        }

        @Test
        @DisplayName("unknown frontmatter type falls back to file-name prefix")
        void unknownFrontmatterFallsBack() {
            String content = """
                    ---
                    title: Foo
                    type: mystery
                    ---
                    """;

            WikiPage page = WikiIo.parseWikiPage("/wiki/pages/overview-foo.md", content);

            assertThat(page.getType()).isEqualTo(WikiPageType.OVERVIEW);
        }

        @Test
        @DisplayName("no hints anywhere defaults to SUMMARY")
        void defaultsToSummary() {
            String content = "# Foo\n\nBody.\n";

            WikiPage page = WikiIo.parseWikiPage("/wiki/pages/foo.md", content);

            assertThat(page.getType()).isEqualTo(WikiPageType.SUMMARY);
        }
    }

    @Nested
    @DisplayName("parseWikiPage — derivedFrom extraction")
    class DerivedFromExtraction {

        @Test
        @DisplayName("inline list is parsed, whitespace trimmed, quotes stripped")
        void parsesInlineList() {
            String content = """
                    ---
                    title: Kubernetes Pod
                    derived_from: ["/raw/a.md", /raw/b.md,   /raw/c.md]
                    ---
                    """;

            WikiPage page = WikiIo.parseWikiPage("/wiki/pages/entity-pod.md", content);

            assertThat(page.getDerivedFrom()).containsExactly("/raw/a.md", "/raw/b.md", "/raw/c.md");
        }

        @Test
        @DisplayName("missing derived_from yields empty list")
        void missingYieldsEmpty() {
            String content = "---\ntitle: Foo\n---\n\n# Foo\n";

            WikiPage page = WikiIo.parseWikiPage("/wiki/pages/summary-foo.md", content);

            assertThat(page.getDerivedFrom()).isEmpty();
        }
    }

    @Nested
    @DisplayName("buildPageFileName")
    class BuildPageFileName {

        @Test
        @DisplayName("prepends type prefix to base name")
        void prependsPrefix() {
            assertThat(WikiIo.buildPageFileName(WikiPageType.SUMMARY, "foo.md")).isEqualTo("summary-foo.md");
            assertThat(WikiIo.buildPageFileName(WikiPageType.ENTITY, "kubernetes-pod"))
                    .isEqualTo("entity-kubernetes-pod.md");
            assertThat(WikiIo.buildPageFileName(WikiPageType.ANSWER, "how-to-deploy"))
                    .isEqualTo("answer-how-to-deploy.md");
        }

        @Test
        @DisplayName("adds .md extension when base is missing it")
        void addsMdExtension() {
            assertThat(WikiIo.buildPageFileName(WikiPageType.CONCEPT, "bar")).isEqualTo("concept-bar.md");
        }

        @Test
        @DisplayName("preserves existing .md extension without duplicating it")
        void preservesMdExtension() {
            assertThat(WikiIo.buildPageFileName(WikiPageType.OVERVIEW, "domain.md")).isEqualTo("overview-domain.md");
        }

        @Test
        @DisplayName("null type or base throws NullPointerException")
        void nullArgsThrow() {
            assertThatThrownBy(() -> WikiIo.buildPageFileName(null, "foo")).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> WikiIo.buildPageFileName(WikiPageType.SUMMARY, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("frontmatter injection hardening (Unicode line terminators)")
    class FrontmatterInjectionHardening {

        // U+0085 NEXT LINE, U+2028 LINE SEPARATOR, U+2029 PARAGRAPH SEPARATOR. java.util.regex honors all three as
        // line terminators under Pattern.MULTILINE, yet they are absent from \p{Cntrl} and \s — so before the fix a
        // crafted title/tag could smuggle a line-leading type:/tags:/derived_from: field past the sanitizer and the
        // MULTILINE frontmatter parser. Built from code points so the source file itself stays free of stray breaks.
        private final String[] separators = {String.valueOf((char) 0x0085), String.valueOf((char) 0x2028),
                String.valueOf((char) 0x2029)};

        @Test
        @DisplayName("sanitizeFrontmatterText flattens Unicode line/paragraph separators to a space")
        void sanitizeFlattensUnicodeSeparators() {
            for (String sep : separators) {
                String sanitized = WikiIo.sanitizeFrontmatterText("Innocent" + sep + "type: entity");

                assertThat(sanitized).as("U+%04X must be flattened", (int) sep.charAt(0))
                        .isEqualTo("Innocent type: entity").doesNotContain(sep);
            }
        }

        @Test
        @DisplayName("sanitizeFrontmatterTag flattens Unicode separators too")
        void sanitizeTagFlattensUnicodeSeparators() {
            for (String sep : separators) {
                String sanitized = WikiIo.sanitizeFrontmatterTag("k8s" + sep + "pwned");

                assertThat(sanitized).as("U+%04X must be flattened", (int) sep.charAt(0)).doesNotContain(sep);
            }
        }

        @Test
        @DisplayName("Unicode separator in a title cannot inject a spurious type: field (parser honors only \\n)")
        void unicodeSeparatorCannotSpoofType() {
            for (String sep : separators) {
                // Defense-in-depth: even if an un-sanitized value reaches persisted content, the MULTILINE+UNIX_LINES
                // parser must not treat the Unicode separator as a line boundary. Legitimate type is 'summary'; the
                // injected 'entity' must never win.
                String content = "---\ntitle: Innocent" + sep + "type: entity\ntype: summary\n---\n\n# Innocent\n";

                WikiPage page = WikiIo.parseWikiPage("/wiki/pages/summary-innocent.md", content);

                assertThat(page.getType()).as("U+%04X must not create a spurious type: line", (int) sep.charAt(0))
                        .isEqualTo(WikiPageType.SUMMARY);
            }
        }

        @Test
        @DisplayName("Unicode separator in a title cannot inject a spurious tags: field")
        void unicodeSeparatorCannotSpoofTags() {
            for (String sep : separators) {
                String content = "---\ntitle: Innocent" + sep + "tags: [pwned]\n---\n\n# Innocent\n";

                WikiPage page = WikiIo.parseWikiPage("/wiki/pages/summary-innocent.md", content);

                assertThat(page.getTags()).as("U+%04X must not create a spurious tags: line", (int) sep.charAt(0))
                        .doesNotContain("pwned");
            }
        }

        @Test
        @DisplayName("Unicode separator in a title cannot inject a spurious derived_from: field")
        void unicodeSeparatorCannotSpoofDerivedFrom() {
            for (String sep : separators) {
                String content = "---\ntitle: Innocent" + sep + "derived_from: [/etc/passwd]\n---\n\n# Innocent\n";

                WikiPage page = WikiIo.parseWikiPage("/wiki/pages/summary-innocent.md", content);

                assertThat(page.getDerivedFrom())
                        .as("U+%04X must not create a spurious derived_from: line", (int) sep.charAt(0))
                        .doesNotContain("/etc/passwd");
            }
        }

        @Test
        @DisplayName("end-to-end: a crafted title written via sanitizeFrontmatterText cannot spoof metadata")
        void sanitizedCraftedTitleCannotSpoof() {
            // Mirrors LlmWikiPageGenerator's hand-rolled write: "title: " + sanitizeFrontmatterText(rawTitle).
            String craftedTitle = "Innocent" + (char) 0x2028 + "type: entity" + (char) 0x2029 + "tags: [pwned]";
            String content = "---\ntitle: " + WikiIo.sanitizeFrontmatterText(craftedTitle)
                    + "\ntype: summary\n---\n\n# Innocent\n";

            WikiPage page = WikiIo.parseWikiPage("/wiki/pages/summary-innocent.md", content);

            assertThat(page.getType()).isEqualTo(WikiPageType.SUMMARY);
            assertThat(page.getTags()).doesNotContain("pwned");
        }

        @Test
        @DisplayName("regression: ASCII CR/LF in a title still cannot spoof metadata")
        void asciiNewlineCannotSpoof() {
            String content = "---\ntitle: " + WikiIo.sanitizeFrontmatterText("Innocent\ntype: entity")
                    + "\ntype: summary\n---\n\n# Innocent\n";

            WikiPage page = WikiIo.parseWikiPage("/wiki/pages/summary-innocent.md", content);

            assertThat(page.getType()).isEqualTo(WikiPageType.SUMMARY);
        }
    }
}
