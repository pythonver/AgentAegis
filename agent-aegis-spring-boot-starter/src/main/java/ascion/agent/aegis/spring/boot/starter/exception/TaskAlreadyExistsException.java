package ascion.agent.aegis.spring.boot.starter.exception;

// 实例侧：同一运行实例（taskId）已 SUCCESS 且 conflictStrategy=THROW_EXCEPTION 时拒绝重入
public class TaskAlreadyExistsException extends RuntimeException {
    public TaskAlreadyExistsException(String message) {
        super(message);
    }
}
