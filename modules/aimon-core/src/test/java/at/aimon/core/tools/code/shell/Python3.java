package at.aimon.core.tools.code.shell;

import java.util.concurrent.TimeUnit;

/** Whether this machine runs {@code python3} 3.8 or later — the shell engine's tests assume it. */
final class Python3 {

    private static final boolean AVAILABLE = check();

    private Python3() {
    }

    static boolean available() {
        return AVAILABLE;
    }

    private static boolean check() {
        try {
            final Process process = new ProcessBuilder("python3", "-c",
                    "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)").redirectErrorStream(true).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
