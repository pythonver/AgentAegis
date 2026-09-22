package ascion.agent.aegis.spring.boot.starter.handler;

/**
 * 标准单步骤接口
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface AgentStepHandler<I, O> {

    O execute(I input) throws Throwable;


}
