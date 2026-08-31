package at.aimon.cli.exception;

import java.io.Serial;

public class ConfigurationException extends CliException {
    @Serial
    private static final long serialVersionUID = -6683932492571167950L;

    /** 메시지를 포함하는 ConfigurationException을 생성한다. */
    public ConfigurationException(String message) {
        super(message);
    }

    /** 메시지와 원인을 포함하는 ConfigurationException을 생성한다. */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 원인을 포함하는 ConfigurationException을 생성한다. */
    public ConfigurationException(Throwable cause) {
        super(cause);
    }
}
