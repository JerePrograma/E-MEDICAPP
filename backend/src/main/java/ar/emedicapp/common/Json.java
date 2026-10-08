package ar.emedicapp.common;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class Json {
    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Object value) {
        return mapper.writeValueAsString(value);
    }

    public Map<String, Object> readMap(String value) {
        return value == null
                ? Map.of()
                : mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    }

    public List<Object> readList(String value) {
        return value == null
                ? List.of()
                : mapper.readValue(value, new TypeReference<List<Object>>() {});
    }

    public Map<String, Object> decodeMap(String value) {
        return readMap(value);
    }

    public List<Object> decodeList(String value) {
        return readList(value);
    }
}
