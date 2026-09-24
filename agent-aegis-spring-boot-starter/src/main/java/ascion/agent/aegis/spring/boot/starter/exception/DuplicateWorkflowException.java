package ascion.agent.aegis.spring.boot.starter.exception;

// 定义侧：Registry 中 workflow 定义键冲突（启动期，非实例冲突）
public class DuplicateWorkflowException extends RuntimeException {

    public DuplicateWorkflowException(String message) {
        super(message);
    }
}
