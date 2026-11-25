package com.cmbchina.whitelist.infrastructure.config;

import com.cmbchina.whitelist.adapter.web.controller.WhitelistController;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WhitelistWebConfiguration implements WebMvcConfigurer {
    private final ObjectMapper mapper;

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // Preserve the string-only JSON array contract when binding directly to List<String>.
        SimpleModule strictStrings = new SimpleModule();
        strictStrings.addDeserializer(String.class, new JsonDeserializer<String>() {
            @Override
            public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                    return context.reportInputMismatch(String.class, "Whitelist IDs must be JSON strings");
                }
                return parser.getText();
            }
        });
        converters.add(0, new StringListRequestConverter(mapper.copy().registerModule(strictStrings)));
    }

    private static final class StringListRequestConverter extends MappingJackson2HttpMessageConverter {
        private static final Type STRING_LIST = new ParameterizedTypeReference<List<String>>() { }.getType();

        private StringListRequestConverter(ObjectMapper mapper) {
            super(mapper);
        }

        @Override
        public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
            return contextClass != null && WhitelistController.class.isAssignableFrom(contextClass)
                    && STRING_LIST.equals(type) && super.canRead(type, contextClass, mediaType);
        }

        @Override
        public boolean canWrite(Class<?> type, MediaType mediaType) {
            return false;
        }
    }
}
