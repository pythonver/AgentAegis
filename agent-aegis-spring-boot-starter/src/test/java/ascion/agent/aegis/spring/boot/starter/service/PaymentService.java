package ascion.agent.aegis.spring.boot.starter.service;

import ascion.agent.aegis.spring.boot.starter.annotation.AgentStep;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class PaymentService {

    @AgentStep(name = "deduct_fee", timeout = 1, timeoutUnit = TimeUnit.SECONDS)
    public String deductFee(String accountId, double amount) {
        // 模拟业务逻辑
        return "SUCCESS_" + accountId;
    }
}