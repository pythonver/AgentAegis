package ascion.agent.aegis.spring.boot.starter.registry;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.lang.reflect.Method;

@AllArgsConstructor
@Getter
public class WorkflowDescription {

    Object bean;
    Method method;

    // 便捷调用 workflow 入口（经容器 Bean；失败属一次运行实例的调用失败）
    public Object invoke(Object... args) {
        try {
            method.setAccessible(true);
            return method.invoke(bean, args);
        } catch (Exception e) {
            throw new RuntimeException("执行 workflow 入口失败: " + method.getName(), e);
        }
    }
}
