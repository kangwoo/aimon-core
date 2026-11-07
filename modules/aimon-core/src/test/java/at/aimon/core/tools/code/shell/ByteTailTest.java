package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("ByteTail — the last capacity bytes, kept in linear time")
class ByteTailTest {

    @Test
    @DisplayName("mixed appends keep exactly the tail an unbounded buffer would end with")
    void exactTailAfterMixedAppends() {
        final Random random = new Random(7);
        for (int capacity : new int[]{1, 5, 100, ByteTail.INITIAL_BYTES, ByteTail.INITIAL_BYTES * 3 + 1}) {
            final ByteTail tail = new ByteTail(capacity);
            final ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (int i = 0; i < 400; i++) {
                final byte[] chunk = new byte[random.nextInt(capacity * 2 + 2)];
                random.nextBytes(chunk);
                tail.append(chunk);
                all.write(chunk, 0, chunk.length);
                final byte[] everything = all.toByteArray();
                final byte[] expected = Arrays.copyOfRange(everything, Math.max(0, everything.length - capacity),
                        everything.length);
                assertThat(tail.toArray()).as("capacity %d, append %d", capacity, i).isEqualTo(expected);
                assertThat(tail.size()).isEqualTo(expected.length);
            }
        }
    }

    @Test
    @DisplayName("wrap-around: the oldest bytes go first and the order survives the seam")
    void wrapAround() {
        final ByteTail tail = new ByteTail(4);
        tail.append(new byte[]{1, 2, 3});
        tail.append(new byte[]{4, 5});
        assertThat(tail.toArray()).containsExactly(2, 3, 4, 5);
        tail.append(new byte[]{6});
        assertThat(tail.toArray()).containsExactly(3, 4, 5, 6);
        tail.append(new byte[]{7, 8, 9});
        assertThat(tail.toArray()).containsExactly(6, 7, 8, 9);
    }

    @Test
    @DisplayName("an append larger than the capacity keeps only its own last bytes")
    void appendLargerThanCapacity() {
        final ByteTail tail = new ByteTail(3);
        tail.append(new byte[]{1, 2});
        tail.append(new byte[]{3, 4, 5, 6, 7});
        assertThat(tail.toArray()).containsExactly(5, 6, 7);
    }

    @Test
    @DisplayName("a zero capacity keeps nothing; an empty append changes nothing")
    void zeroCapacityAndEmptyAppend() {
        final ByteTail none = new ByteTail(0);
        none.append(new byte[]{1, 2});
        assertThat(none.toArray()).isEmpty();
        final ByteTail tail = new ByteTail(4);
        tail.append(new byte[0]);
        assertThat(tail.toArray()).isEmpty();
        assertThat(tail.allocations()).isZero();
    }

    @Test
    @DisplayName("growth starts small and doubles up to the capacity: a huge cap does not allocate up front")
    void growthSteps() {
        final int capacity = 100 * ByteTail.INITIAL_BYTES;
        final ByteTail tail = new ByteTail(capacity);
        tail.append(new byte[10]);
        assertThat(tail.allocations()).isEqualTo(1);
        tail.append(new byte[ByteTail.INITIAL_BYTES]);
        assertThat(tail.allocations()).isEqualTo(2);
    }

    @Test
    @Timeout(30)
    @DisplayName("200 000 small appends at a 4 MiB cap allocate O(log cap) arrays, not one per append")
    void linear() {
        final int capacity = 4 * 1024 * 1024;
        final ByteTail tail = new ByteTail(capacity);
        final byte[] chunk = new byte[64];
        for (int i = 0; i < 200_000; i++) {
            chunk[0] = (byte) i;
            tail.append(chunk);
        }
        final int bound = (int) Math.ceil(Math.log((double) capacity / ByteTail.INITIAL_BYTES) / Math.log(2)) + 1;
        assertThat(tail.allocations()).isLessThanOrEqualTo(bound);
        assertThat(tail.size()).isEqualTo(capacity);
        final byte[] kept = tail.toArray();
        assertThat(kept[kept.length - 64]).isEqualTo((byte) (200_000 - 1));
    }
}
