package ascion.agent.aegis.spring.boot.starter;

import ascion.agent.aegis.core.model.Checkpoint;
import ascion.agent.aegis.core.model.StepStatus;
import ascion.agent.aegis.core.model.TaskContext;
import ascion.agent.aegis.core.repository.CheckpointRepository;
import ascion.agent.aegis.spring.boot.starter.annotation.AgentStep;
import ascion.agent.aegis.spring.boot.starter.aspect.AgentStepAspect;
import ascion.agent.aegis.spring.boot.starter.context.TaskContextHolder;
import ascion.agent.aegis.spring.boot.starter.repository.JdbcCheckpointRepositoryImpl;
import ascion.agent.aegis.spring.boot.starter.utils.SerializeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.UndeclaredThrowableException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
@SpringBootTest
//@ActiveProfiles("test")
@Rollback(false) // 允许数据真正 COMMIT 到 MySQL
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // 强制使用真实配置的数据库
//@TestPropertySource(properties = {
//        "agent-aegis.enabled=true",
//        "agent-aegis.repository-type=JDBC",
//        "agent-aegis.datasource.mode=INHERIT",
//        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
//        "spring.datasource.url=jdbc:mysql://localhost:3306/agent_aegis_test?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
//        "spring.datasource.username=ascion",
//        "spring.datasource.password=ko50314Vc++nt",
//        "spring.sql.init.mode=always",
//        "spring.sql.init.schema-locations=classpath:schema-mysql.sql"
//})
@TestPropertySource(properties = {
        "agent-aegis.enabled=true",
        "agent-aegis.repository-type=JDBC",
        "agent-aegis.datasource.mode=INHERIT",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-mysql.sql"
})
class AgentStepJdbcIntegrationTest {

    @SpringBootApplication(scanBasePackages = "ascion.agent.aegis")
    static class TestApplication {
        @Bean
        public TestBizService testBizService() {
            return new TestBizService();
        }
    }

    @Autowired
    private TestBizService proxy; // 由 Spring 容器托管并增强的真实代理对象

    @Autowired
    private CheckpointRepository checkpointRepository; // 此时注入的是真实的 JdbcCheckpointRepositoryImpl

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 辅助测试的目标业务类
    static class TestBizService {

        @AgentStep(name = "complex_payload_step", timeout = 0)
        public List<OrderItem> complexPayloadStep(OrderRequest request, String channel) {
            // 返回包含范型的 List<OrderItem>
            return request.getItems();
        }

        @AgentStep(name = "normal_step", timeout = 0)
        public String normalStep(String input) {
            return "RESULT_" + input;
        }

        @AgentStep(name = "void_step", timeout = 0)
        public void voidStep() {
            // do nothing
        }

        @AgentStep(name = "ignore_output_step", timeout = 0, ignoreOutput = true)
        public String ignoreOutputStep(String input) {
            return "SENSITIVE_DATA_" + input;
        }

        @AgentStep(name = "timeout_step", timeout = 100, timeoutUnit = TimeUnit.MILLISECONDS)
        public String timeoutStep() {
            try {
                Thread.sleep(500); // 故意超过 100ms
            } catch (InterruptedException e) {
                // 响应中断信号
            }
            return "TIMEOUT_RESULT";
        }

        @AgentStep(name = "exception_step", timeout = 0)
        public String exceptionStep() {
            throw new IllegalArgumentException("Business Exception");
        }

        @AgentStep(name = "timeout_exception_step", timeout = 500, timeoutUnit = TimeUnit.MILLISECONDS)
        public String timeoutExceptionStep() {
            throw new IllegalStateException("Async Business Exception");
        }
    }

    @BeforeEach
    void setUp() {
        // 自动建立 MySQL 数据库表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS agent_guardian_task (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                task_id VARCHAR(64) NOT NULL,
                status VARCHAR(32) NOT NULL,
                metadata_json LONGTEXT,
                created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                CONSTRAINT uk_task_id UNIQUE (task_id)
            );
        """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS agent_guardian_checkpoint (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                task_id VARCHAR(64) NOT NULL,
                step_name VARCHAR(128) NOT NULL,
                status VARCHAR(32) NOT NULL,
                input_payload LONGTEXT,
                output_payload LONGTEXT,
                exception_stack TEXT,
                execution_time_ms BIGINT DEFAULT 0,
                created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                CONSTRAINT uk_task_step UNIQUE (task_id, step_name)
            );
        """);
    }

    @AfterEach
    void tearDown() {
        TaskContextHolder.clear();
    }

    @Test
    @DisplayName("验证当前使用的 CheckpointRepository 确实是 JDBC 实现")
    void testRepositoryType() {
        assertThat(checkpointRepository).isInstanceOf(JdbcCheckpointRepositoryImpl.class);
    }

    // ==================== 1. 真实 MySQL 执行与持久化测试 ====================

    @Test
    @DisplayName("用例1：首次无超时执行，应成功写入 MySQL 并持久化 SUCCESS 状态")
    void testNormalExecutionSuccess() {
        String taskId = "task-jdbc-001";
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());

        String result = proxy.normalStep("hello_mysql");
        assertEquals("RESULT_hello_mysql", result);

        // 直接从 MySQL 查询校验
        Optional<Checkpoint> cp = checkpointRepository.findCheckpoint(taskId, "normal_step");
        assertTrue(cp.isPresent());
        assertEquals(StepStatus.SUCCESS, cp.get().getStatus());
        assertEquals("\"RESULT_hello_mysql\"", cp.get().getOutputPayload());

        // 原生 SQL 校验条数
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_guardian_checkpoint WHERE task_id = ?", Integer.class, taskId);
        assertEquals(1, count);
    }

    @Test
    @DisplayName("用例2：void 返回类型的方法正常执行并持久化")
    void testVoidReturnType() {
        String taskId = "task-jdbc-002";
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());

        assertDoesNotThrow(() -> proxy.voidStep());

        Optional<Checkpoint> cp = checkpointRepository.findCheckpoint(taskId, "void_step");
        assertTrue(cp.isPresent());
        assertEquals(StepStatus.SUCCESS, cp.get().getStatus());
    }

    @Test
    @DisplayName("用例3：数据库已有 SUCCESS 时，重放直接读取缓存，不再重复插入新记录")
    void testReplayFromDatabaseCache() {
        String taskId = "task-jdbc-003";
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());

        // 首次执行，写入 DB
        proxy.normalStep("cached_val");

        // 二次执行，应触发重放
        String result = proxy.normalStep("cached_val");
        assertEquals("RESULT_cached_val", result);

        // 验证 MySQL 记录依然仅有 1 条
        List<Checkpoint> list = checkpointRepository.listCheckpoints(taskId);
        assertEquals(1, list.size());
    }

    @Test
    @DisplayName("用例4：配置 ignoreOutput=true 时，MySQL 中的 output_payload 应被屏蔽为 '{}'")
    void testIgnoreOutputFlag() {
        String taskId = "task-jdbc-004";
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());

        proxy.ignoreOutputStep("secret");

        Optional<Checkpoint> cp = checkpointRepository.findCheckpoint(taskId, "ignore_output_step");
        assertTrue(cp.isPresent());
        assertEquals(null, cp.get().getOutputPayload());
    }

    @Test
    @DisplayName("用例5：同步执行抛异常时，MySQL 正确记录 FAILURE 状态与异常栈")
    void testExecutionBusinessException() {
        String taskId = "task-jdbc-005";
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());

        assertThrows(IllegalArgumentException.class, () -> proxy.exceptionStep());

        Optional<Checkpoint> cp = checkpointRepository.findCheckpoint(taskId, "exception_step");
        assertTrue(cp.isPresent());
        assertEquals(StepStatus.FAILURE, cp.get().getStatus());
        assertTrue(cp.get().getExceptionStack().contains("Business Exception"));
    }

    @Test
    @DisplayName("用例6：超时控制场景，MySQL 中应落库 FAILURE 状态")
    void testTimeoutScenario() {
        String taskId = "task-jdbc-006";
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());

        Throwable thrown = assertThrows(Throwable.class, () -> proxy.timeoutStep());
        Throwable realCause = (thrown instanceof UndeclaredThrowableException) ?
                ((UndeclaredThrowableException) thrown).getUndeclaredThrowable() : thrown;

        assertInstanceOf(TimeoutException.class, realCause);

        Optional<Checkpoint> cp = checkpointRepository.findCheckpoint(taskId, "timeout_step");
        assertTrue(cp.isPresent());
        assertEquals(StepStatus.FAILURE, cp.get().getStatus());
        assertTrue(cp.get().getExceptionStack().contains("执行超时"));
    }


    static class OrderItem {
        private String itemId;
        private BigDecimal price;

        public OrderItem() {} // 无参构造函数（Jackson 反序列化必须）
        public OrderItem(String itemId, BigDecimal price) {
            this.itemId = itemId;
            this.price = price;
        }

        // getter/setter ...
        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
    }

    static class OrderRequest {
        private String orderId;
        private List<OrderItem> items;
        private Map<String, Object> extraMeta;

        public OrderRequest() {}
        public OrderRequest(String orderId, List<OrderItem> items, Map<String, Object> extraMeta) {
            this.orderId = orderId;
            this.items = items;
            this.extraMeta = extraMeta;
        }

        // getter/setter ...
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public List<OrderItem> getItems() { return items; }
        public void setItems(List<OrderItem> items) { this.items = items; }
        public Map<String, Object> getExtraMeta() { return extraMeta; }
        public void setExtraMeta(Map<String, Object> extraMeta) { this.extraMeta = extraMeta; }
    }

    @Test
    @DisplayName("用例8：复杂嵌套对象入参和泛型集合返回值的正确序列化与落库校验")
    void testComplexTypeSerialization() {
        String taskId = "task-jdbc-complex-001";
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());

        // 构造复杂的入参：嵌套对象 + List + Map
        List<OrderItem> items = List.of(
                new OrderItem("ITEM_A", new BigDecimal("99.9")),
                new OrderItem("ITEM_B", new BigDecimal("199.5"))
                        );
        Map<String, Object> meta = Map.of("vip", true, "retry", 1);
        OrderRequest request = new OrderRequest("ORD_8888", items, meta);

        // 执行方法
        List<OrderItem> responseItems = proxy.complexPayloadStep(request, "ONLINE");

        // 1. 校验内存中拿到的返回值
        assertNotNull(responseItems);
        assertEquals(2, responseItems.size());
        assertEquals("ITEM_A", responseItems.getFirst().getItemId());

        // 2. 从 MySQL 中取出数据，校验落库的 JSON 结构
        Optional<Checkpoint> cpOpt = checkpointRepository.findCheckpoint(taskId, "complex_payload_step");
        assertTrue(cpOpt.isPresent());
        Checkpoint cp = cpOpt.get();

        // 校验入参序列化字符串包含 key
        assertTrue(cp.getInputPayload().contains("ORD_8888"));
        assertTrue(cp.getInputPayload().contains("ITEM_A"));

        // 校验出参序列化字符串包含返回值
        assertTrue(cp.getOutputPayload().contains("ITEM_A"));
        assertTrue(cp.getOutputPayload().contains("199.5"));
    }

    @Test
    @DisplayName("用例9：复杂泛型返回值(List<OrderItem>)命中数据库缓存时的反序列化重放")
    void testComplexTypeReplayFromDatabase() {
        String taskId = "task-jdbc-complex-002";
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());

        OrderRequest request = new OrderRequest("ORD_9999", List.of(new OrderItem("ITEM_X", new BigDecimal("50.0"))), Map.of());

        // 第一次调用：执行真实业务并写库
        List<OrderItem> firstCallResult = proxy.complexPayloadStep(request, "OFFLINE");
        assertEquals(1, firstCallResult.size());

        // 第二次调用：命中 MySQL 缓存重放，反序列化出参
        List<OrderItem> replayedResult = proxy.complexPayloadStep(request, "OFFLINE");

        // 验证反序列化后的泛型对象字段完整无损
        assertNotNull(replayedResult);
        assertEquals(1, replayedResult.size());
        assertEquals("ITEM_X", replayedResult.get(0).getItemId());
        assertEquals(new BigDecimal("50.0"), replayedResult.get(0).getPrice());

        // 确认数据库依然只有 1 条记录，未产生重复插入
        List<Checkpoint> list = checkpointRepository.listCheckpoints(taskId);
        assertEquals(1, list.size());
    }

    @Test
    @DisplayName("用例10：验证特殊/边界入参（null、空集合、特殊字符）的序列化兼容性")
    void testBoundaryValueSerialization() {
        String taskId = "task-jdbc-boundary-001";
        TaskContextHolder.setContext(TaskContext.builder().taskId(taskId).build());

        // 传入包含 null 和包含特殊 JSON 字符 (双引号, 换行符, Emoji) 的对象
        OrderRequest request = new OrderRequest("ORD_\"SPECIAL\"_\n_😀", null, Map.of("keyWithNull", ""));

        assertDoesNotThrow(() -> proxy.complexPayloadStep(request, null));

        // 数据库持久化校验
        Optional<Checkpoint> cp = checkpointRepository.findCheckpoint(taskId, "complex_payload_step");
        assertTrue(cp.isPresent());
        assertEquals(StepStatus.SUCCESS, cp.get().getStatus());
        assertTrue(cp.get().getInputPayload().contains("ORD_\\\"SPECIAL\\\""));
    }
}