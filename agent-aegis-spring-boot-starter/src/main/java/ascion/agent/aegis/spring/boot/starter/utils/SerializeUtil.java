package ascion.agent.aegis.spring.boot.starter.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class SerializeUtil {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();
    /**
     * 安全的 JSON 序列化，防止入参无法序列化导致切面直接崩溃
     */
    public static String safeSerialize(Object object) {
        try {
            return MAPPER.writeValueAsString(object);
        } catch (Exception e) {
            return "[Unserializable Input]";
        }
    }

    public static ObjectMapper getMapper() {
        return MAPPER;
    }
}
