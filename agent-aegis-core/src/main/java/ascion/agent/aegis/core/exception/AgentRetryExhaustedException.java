package ascion.agent.aegis.core.exception;

public class AgentRetryExhaustedException extends RuntimeException {
    public AgentRetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}