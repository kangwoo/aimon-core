package at.aimon.core.tools.code.shell;

/**
 * Where UTF-8 bytes cut at a byte cap can be decoded without a broken character: a cut at the end may split the last
 * character, and a cut from the front may leave continuation bytes ({@code 0b10xxxxxx}) at the start. Either would
 * decode as U+FFFD ({@code docs/design/tool/programmatic-tool-calling-shell.md} §7).
 */
final class Utf8Cut {

    private static final int MAX_CONTINUATION = 3;

    private Utf8Cut() {
    }

    /**
     * Returns {@code length} less an incomplete UTF-8 sequence at its end, looking at most three bytes back. Bytes
     * that are not valid UTF-8 for another reason are left alone.
     *
     * @param bytes
     *            the bytes
     * @param length
     *            how many of them count, from the start
     * @return the length that ends on a whole character
     */
    static int completeLength(byte[] bytes, int length) {
        int start = length - 1;
        int back = 0;
        while (start >= 0 && back < MAX_CONTINUATION && isContinuation(bytes[start])) {
            start--;
            back++;
        }
        if (start < 0) {
            return length;
        }
        final int lead = bytes[start] & 0xFF;
        final int needed;
        if ((lead & 0xE0) == 0xC0) {
            needed = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            needed = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            needed = 4;
        } else {
            needed = 1;
        }
        return length - start < needed ? start : length;
    }

    /**
     * Returns how many continuation bytes, at most three, start {@code bytes}: what a cut from the front left of a
     * character.
     *
     * @param bytes
     *            the bytes
     * @param length
     *            how many of them count, from the start
     * @return the number of leading bytes to skip
     */
    static int leadingContinuations(byte[] bytes, int length) {
        int skip = 0;
        while (skip < MAX_CONTINUATION && skip < length && isContinuation(bytes[skip])) {
            skip++;
        }
        return skip;
    }

    private static boolean isContinuation(byte b) {
        return (b & 0xC0) == 0x80;
    }
}
