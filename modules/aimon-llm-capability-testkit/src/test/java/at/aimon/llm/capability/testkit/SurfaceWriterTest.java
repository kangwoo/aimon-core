package at.aimon.llm.capability.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ThinkingDialect;

@DisplayName("SurfaceWriter - writes a builder-typed value onto a surface, converting one way only")
class SurfaceWriterTest {

    /** The builder's own type for the ladder, read off the builder rather than restated. */
    private static final Type LADDER = DeclarableKeys.parameterTypeOf("acceptedReasoningEfforts");

    @Nested
    @DisplayName("a value the property already accepts")
    class AlreadyAccepted {

        @Test
        @DisplayName("is written as is")
        void isWrittenAsIs() {
            final TypedSurface surface = new TypedSurface();

            SurfaceWriter.write(surface, "dialect", ThinkingDialect.EITHER, ThinkingDialect.class);

            assertThat(surface.getDialect()).isSameAs(ThinkingDialect.EITHER);
        }

        @Test
        @DisplayName("is written as is into a Set property whose elements already match")
        void aSetIntoAMatchingSetIsWrittenAsIs() {
            final TypedSurface surface = new TypedSurface();
            final Set<ReasoningEffort> ladder = EnumSet.of(ReasoningEffort.NONE, ReasoningEffort.LOW);

            SurfaceWriter.write(surface, "ladderSet", ladder, LADDER);

            assertThat(surface.getLadderSet()).isSameAs(ladder);
        }
    }

    @Nested
    @DisplayName("a value the property declares differently")
    class Converted {

        @Test
        @DisplayName("a set into a List property is copied in the set's own order — the conversion both surfaces use")
        void aSetIntoAListIsCopiedInOrder() {
            final TypedSurface surface = new TypedSurface();

            SurfaceWriter.write(surface, "ladderList", EnumSet.of(ReasoningEffort.LOW, ReasoningEffort.NONE), LADDER);

            assertThat(surface.getLadderList()).isInstanceOf(ArrayList.class).containsExactly(ReasoningEffort.NONE,
                    ReasoningEffort.LOW);
        }

        @Test
        @DisplayName("a set into a Collection property is copied too, rather than being unreachable")
        void aSetIntoACollectionIsCopied() {
            final TypedSurface surface = new TypedSurface();

            SurfaceWriter.write(surface, "ladderCollection", EnumSet.of(ReasoningEffort.LOW), LADDER);

            assertThat(surface.getLadderCollection()).isInstanceOf(ArrayList.class)
                    .containsExactly(ReasoningEffort.LOW);
        }

        @Test
        @DisplayName("an enum into a String property becomes its name")
        void anEnumIntoAStringBecomesItsName() {
            final StringSurface surface = new StringSurface();

            SurfaceWriter.write(surface, "dialect", ThinkingDialect.EITHER, ThinkingDialect.class);

            assertThat(surface.getDialect()).isEqualTo("EITHER");
        }

        @Test
        @DisplayName("a set of an enum into a List<String> property becomes the names, in iteration order")
        void aSetOfAnEnumIntoAListOfStringsBecomesTheNames() {
            final StringSurface surface = new StringSurface();

            SurfaceWriter.write(surface, "ladderList", EnumSet.of(ReasoningEffort.LOW, ReasoningEffort.NONE), LADDER);

            assertThat(surface.getLadderList()).containsExactly("NONE", "LOW");
        }

        @Test
        @DisplayName("a set of an enum into a Set<String> property becomes the names — generic types decide, not raw ones")
        void aSetOfAnEnumIntoASetOfStringsBecomesTheNames() {
            final StringSurface surface = new StringSurface();

            SurfaceWriter.write(surface, "ladderSet", EnumSet.of(ReasoningEffort.NONE, ReasoningEffort.LOW), LADDER);

            // An EnumSet is raw-assignable to Set, so a raw comparison would have written the enum set as is.
            assertThat(surface.getLadderSet()).containsExactly("NONE", "LOW");
        }

        @Test
        @DisplayName("a Boolean into a String property becomes its text")
        void aBooleanIntoAStringBecomesItsText() {
            final StringSurface surface = new StringSurface();

            SurfaceWriter.write(surface, "flag", Boolean.TRUE, Boolean.class);

            assertThat(surface.getFlag()).isEqualTo("true");
        }
    }

    @Nested
    @DisplayName("a write the probe cannot make")
    class Refused {

        @Test
        @DisplayName("a pairing with no conversion fails, naming both types")
        void anUnconvertiblePairingFails() {
            assertThatThrownBy(() -> SurfaceWriter.write(new StringSurface(), "count", Boolean.TRUE, Boolean.class))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("java.lang.Boolean")
                    .hasMessageContaining("java.lang.Integer");
        }

        @Test
        @DisplayName("a property the surface does not have fails, naming it and the surface")
        void aMissingPropertyFails() {
            assertThatThrownBy(() -> SurfaceWriter.write(new TypedSurface(), "thinkingDialect", ThinkingDialect.EITHER,
                    ThinkingDialect.class)).isInstanceOf(AssertionError.class).hasMessageContaining("`thinkingDialect`")
                    .hasMessageContaining(TypedSurface.class.getName());
        }

        @Test
        @DisplayName("a setter that refuses the value fails before any forwarding runs, with the refusal as the cause")
        void aRefusingSetterFails() {
            assertThatThrownBy(() -> SurfaceWriter.write(new RefusingSurface(), "flag", Boolean.TRUE, Boolean.class))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("refused")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Declares the builder's own types, and the ladder in each container shape. */
    static final class TypedSurface {
        private ThinkingDialect dialect;
        private List<ReasoningEffort> ladderList;
        private Set<ReasoningEffort> ladderSet;
        private Collection<ReasoningEffort> ladderCollection;

        public ThinkingDialect getDialect() {
            return dialect;
        }

        public void setDialect(ThinkingDialect dialect) {
            this.dialect = dialect;
        }

        public List<ReasoningEffort> getLadderList() {
            return ladderList;
        }

        public void setLadderList(List<ReasoningEffort> ladderList) {
            this.ladderList = ladderList;
        }

        public Set<ReasoningEffort> getLadderSet() {
            return ladderSet;
        }

        public void setLadderSet(Set<ReasoningEffort> ladderSet) {
            this.ladderSet = ladderSet;
        }

        public Collection<ReasoningEffort> getLadderCollection() {
            return ladderCollection;
        }

        public void setLadderCollection(Collection<ReasoningEffort> ladderCollection) {
            this.ladderCollection = ladderCollection;
        }
    }

    /** The surface the "one binds enums, the other strings" premise would produce. */
    static final class StringSurface {
        private String dialect;
        private String flag;
        private List<String> ladderList;
        private Set<String> ladderSet;
        private Integer count;

        public String getDialect() {
            return dialect;
        }

        public void setDialect(String dialect) {
            this.dialect = dialect;
        }

        public String getFlag() {
            return flag;
        }

        public void setFlag(String flag) {
            this.flag = flag;
        }

        public List<String> getLadderList() {
            return ladderList;
        }

        public void setLadderList(List<String> ladderList) {
            this.ladderList = ladderList;
        }

        public Set<String> getLadderSet() {
            return ladderSet;
        }

        public void setLadderSet(Set<String> ladderSet) {
            this.ladderSet = ladderSet;
        }

        public Integer getCount() {
            return count;
        }

        public void setCount(Integer count) {
            this.count = count;
        }
    }

    /** A surface that validates its input. */
    static final class RefusingSurface {
        public Boolean getFlag() {
            return null;
        }

        public void setFlag(Boolean flag) {
            throw new IllegalArgumentException("this surface validates its input");
        }
    }
}
