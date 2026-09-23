package ascion.agent.aegis.spring.boot.starter;

import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentRetryAspect;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentStepAspect;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentWorkflowAspect;
import ascion.agent.aegis.spring.boot.starter.config.AgentAegisAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收 AEGIC-16：
 * 1) AutoConfiguration.imports 注册存在，外部项目可自动装配
 * 2) 默认装配三个切面与 CheckpointRepository
 * 3) agent-aegis.enabled=false 时全部优雅失效
 */
class AgentAegisAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAegisAutoConfiguration.class));

    @Test
    @DisplayName("imports 文件必须存在于 classpath，且内容为 AgentAegisAutoConfiguration FQCN")
    void autoConfigurationImportsFilePresent() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertTrue(resource.exists(), "缺少 AutoConfiguration.imports，Starter 将无法自动装配");

        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(
                    content.contains(AgentAegisAutoConfiguration.class.getName()),
                    "imports 内容应包含 " + AgentAegisAutoConfiguration.class.getName()
                            + "，实际为:\n" + content
            );
        }
    }

    @Test
    @DisplayName("默认 enabled：三个切面与 CheckpointRepository 均已注册")
    void aspectsAndRepositoryRegisteredByDefault() {
        runner.run(context -> {
            assertTrue(context.getBeanNamesForType(AgentRetryAspect.class).length > 0,
                    "AgentRetryAspect 未注册");
            assertTrue(context.getBeanNamesForType(AgentStepAspect.class).length > 0,
                    "AgentStepAspect 未注册");
            assertTrue(context.getBeanNamesForType(AgentWorkflowAspect.class).length > 0,
                    "AgentWorkflowAspect 未注册");
            assertTrue(context.getBeanNamesForType(CheckpointRepository.class).length > 0,
                    "CheckpointRepository 未注册");
        });
    }

    @Test
    @DisplayName("enabled=false：所有切面与 Repository 均不装配")
    void allBeansAbsentWhenDisabled() {
        runner.withPropertyValues("agent-aegis.enabled=false")
                .run(context -> {
                    assertEquals(0, context.getBeanNamesForType(AgentRetryAspect.class).length,
                            "enabled=false 时不应注册 AgentRetryAspect");
                    assertEquals(0, context.getBeanNamesForType(AgentStepAspect.class).length,
                            "enabled=false 时不应注册 AgentStepAspect");
                    assertEquals(0, context.getBeanNamesForType(AgentWorkflowAspect.class).length,
                            "enabled=false 时不应注册 AgentWorkflowAspect");
                    assertEquals(0, context.getBeanNamesForType(CheckpointRepository.class).length,
                            "enabled=false 时不应注册 CheckpointRepository");
                });
    }

    @Test
    @DisplayName("enabled=true 显式配置：行为与默认一致（切面可获取）")
    void aspectsRegisteredWhenExplicitlyEnabled() {
        runner.withPropertyValues("agent-aegis.enabled=true")
                .run(context -> assertNotNull(context.getBean(CheckpointRepository.class)));
    }
}
