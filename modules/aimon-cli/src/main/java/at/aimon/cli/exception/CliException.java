package at.aimon.cli.exception;

import java.io.Serial;

public class CliException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 4030792807335282742L;

    /** 메시지를 포함하는 CliException을 생성한다. */
    public CliException(String message) {
        super(message);
    }

    /** 메시지와 원인을 포함하는 CliException을 생성한다. */
    public CliException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 원인을 포함하는 CliException을 생성한다. */
    public CliException(Throwable cause) {
        super(cause);
    }
}
