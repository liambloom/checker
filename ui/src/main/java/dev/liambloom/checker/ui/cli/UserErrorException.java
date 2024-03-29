package dev.liambloom.checker.ui.cli;

public class UserErrorException extends RuntimeException {
    public UserErrorException() {
        super();
    }

    public UserErrorException(String message) {
        super(message);
    }

    public UserErrorException(String message, Throwable cause) {
        super(message, cause);
    }

    public UserErrorException(Throwable cause) {
        super(cause);
    }
}
