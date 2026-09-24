package ascion.agent.aegis.spring.boot.starter.resume;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Setter
@Getter
public class ResumeTicket {


    private String taskId;

    private String workflowName;
}
