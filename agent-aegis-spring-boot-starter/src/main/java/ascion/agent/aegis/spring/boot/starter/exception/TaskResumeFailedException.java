package ascion.agent.aegis.spring.boot.starter.exception;

public class TaskResumeFailedException extends RuntimeException {
    public TaskResumeFailedException(String message) {
        super(message);
    }
}
