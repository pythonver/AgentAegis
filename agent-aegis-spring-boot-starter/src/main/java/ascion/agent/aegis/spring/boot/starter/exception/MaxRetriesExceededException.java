package ascion.agent.aegis.spring.boot.starter.exception;

public class MaxRetriesExceededException extends RuntimeException {
    public MaxRetriesExceededException(String message) {
        super(message);
    }
}
