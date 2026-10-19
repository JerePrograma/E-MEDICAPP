package ar.emedicapp.catalog;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ChainInputJsonTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void omittedOrNullRemoveLogoPreservesExistingImage() {
        for (String optional : new String[] {"", ",\"removeLogo\":null", ",\"removeLogo\":false"}) {
            var input =
                    mapper.readValue(
                            "{\"name\":\"Cadena\",\"active\":true,\"version\":1" + optional + "}",
                            CatalogDtos.ChainInput.class);
            assertThat(input.removeLogo()).isFalse();
            assertThat(input.logoDataUrl()).isNull();
        }
        assertThat(new CatalogDtos.ChainInput("Cadena", true, 1).removeLogo()).isFalse();
    }

    @Test
    void explicitRemoveLogoIsKept() {
        var input =
                mapper.readValue(
                        "{\"name\":\"Cadena\",\"active\":true,\"version\":1,\"removeLogo\":true}",
                        CatalogDtos.ChainInput.class);
        assertThat(input.removeLogo()).isTrue();
    }
}
