package ascion.agent.aegis.spring.boot.starter.config;

import ascion.agent.aegis.spring.boot.starter.config.enums.RecoveryOrder;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "agent-aegis.task.resume")
public class AgentAegisTaskResumeProperties {

    // 恢复顺序（预留；当前 Ready 扫描按仓库默认顺序派发）
    private RecoveryOrder mode = RecoveryOrder.EARLIEST_CREATED;

}
