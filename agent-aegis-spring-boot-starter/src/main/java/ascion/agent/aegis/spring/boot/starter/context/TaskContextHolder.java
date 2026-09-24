package ascion.agent.aegis.spring.boot.starter.context;

import ascion.agent.aegis.core.model.TaskContext;
import com.alibaba.ttl.TransmittableThreadLocal;
import org.springframework.util.StringUtils;
import java.util.UUID;


/**
 * 运行实例上下文持有者（Task = 实例侧；当前线程绑定的 workflow 一次运行）
 */
public class TaskContextHolder {

    // 1. 私有化 ThreadLocal，避免外部直接篡改
    // 推荐使用 TransmittableThreadLocal（TTL），若未引入依赖可先使用 InheritableThreadLocal 或 ThreadLocal
    private static final ThreadLocal<TaskContext> CONTEXT_HOLDER = new TransmittableThreadLocal<>();

    private TaskContextHolder() {
        // 工具类禁止实例化
    }

    /**
     * 设置当前线程的 Task 上下文
     */
    public static void setContext(TaskContext context) {
        if (context != null) {
            CONTEXT_HOLDER.set(context);
        }
    }

    /**
     * 获取当前线程的 Task 上下文，如果为空则初始化一个带全新 taskId 的上下文
     */
    public static TaskContext getOrCreateContext() {
        TaskContext context = CONTEXT_HOLDER.get();
        if (context == null) {
            context = TaskContext.builder()
                    .taskId(generateTaskId())
                    .build();
            CONTEXT_HOLDER.set(context);
        }
        return context;
    }

    /**
     * 获取当前线程的 Task 上下文
     */
    public static TaskContext getContext() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 便捷获取当前的 taskId
     */
    public static String getTaskId() {
        TaskContext context = CONTEXT_HOLDER.get();
        return (context != null && StringUtils.hasText(context.getTaskId())) ? context.getTaskId() : null;
    }

    /**
     * 必须在 try-finally 块中强行调用，彻底清除上下文，防止 ThreadLocal 内存泄漏与线程污染
     */
    public static void clear() {
        CONTEXT_HOLDER.remove();
    }

    /**
     * 生成唯一的 TaskId (可以根据业务需要替换为雪花算法或其他 ID 生成规则)
     */
    public static String generateTaskId() {
        return "task_" + UUID.randomUUID().toString().replace("-", "");
    }


}
