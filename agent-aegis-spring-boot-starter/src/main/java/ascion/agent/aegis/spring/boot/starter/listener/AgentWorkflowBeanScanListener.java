package ascion.agent.aegis.spring.boot.starter.listener;

import ascion.agent.aegis.spring.boot.starter.annotation.AgentWorkflow;
import ascion.agent.aegis.spring.boot.starter.registry.AgentWorkflowRegistry;
import ascion.agent.aegis.spring.boot.starter.registry.WorkflowDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.ClassUtils;
import java.lang.reflect.Method;

public class AgentWorkflowBeanScanListener implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(AgentWorkflowBeanScanListener.class);

    private final AgentWorkflowRegistry  agentWorkflowRegistry;

    public AgentWorkflowBeanScanListener(AgentWorkflowRegistry agentWorkflowRegistry) {
        this.agentWorkflowRegistry = agentWorkflowRegistry;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 定义侧扫描：收集 @AgentWorkflow 入口，按定义键注册到 Registry

        // 获取所有spring bean
        ApplicationContext context = event.getApplicationContext();
        String[] beanNames = context.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            // 3. 获取 Bean 实例
            Object bean = context.getBean(beanName);
            Class<?> clazz = bean.getClass();

            // 处理 CGLIB 代理对象，拿到真实的原始类
            if (clazz.getName().contains("$$")) {
                clazz = ClassUtils.getUserClass(bean);
            }

            // 4. 遍历方法，寻找 @AgentWorkflow 注解
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(AgentWorkflow.class)) {
                    AgentWorkflow annotation = method.getAnnotation(AgentWorkflow.class);
                    String name;

                    if (annotation.name().isBlank()) {
                        logger.warn("An unnamed workflow has been detected, and the method name will be used as its name. " +
                                "Considering the potential impact on task recovery due to future changes in method names, please use a custom name whenever possible." +
                                "Use @AgentWorkflow(name = \"your_workflow_name\")[current: {}] to complete custom naming.", method.getName());
                        name = method.getName();
                    }else {
                        name = annotation.name();
                    }

                    // 按定义键注册（键将与实例侧 TaskContext.name 对齐）
                    agentWorkflowRegistry.register(name, new WorkflowDescription(bean, method));
                }
            }
        }
    }
}
