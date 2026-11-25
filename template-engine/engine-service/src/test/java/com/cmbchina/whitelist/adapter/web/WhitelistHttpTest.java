package com.cmbchina.whitelist.adapter.web;

import com.cmbchina.whitelist.adapter.web.controller.WhitelistController;
import com.cmbchina.whitelist.application.WhitelistService;
import com.cmbchina.whitelist.domain.WhitelistRepository;
import com.cmbchina.whitelist.infrastructure.config.WhitelistWebConfiguration;
import com.cmbchina.whitelist.infrastructure.persistence.MybatisWhitelistRepository;
import com.cmbchina.whitelist.infrastructure.persistence.mapper.WhitelistMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WhitelistHttpTest {
    private static final String ENDPOINT = "/v1/whitelist";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsStringIdsAndChecksOnlyTheAuthHeader() throws Exception {
        WhitelistRepository store = mock(WhitelistRepository.class);
        when(store.contains("00123456")).thenReturn(true);
        when(store.contains("MemberA")).thenReturn(true);
        MockMvc mvc = mvc(store);

        try (InputStream input = getClass().getResourceAsStream("/fixtures/whitelist.json")) {
            assertNotNull(input);
            JsonNode fixture = JSON.readTree(input);
            mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                            .content(fixture.at("/add/request").toString()))
                    .andExpect(status().is(fixture.at("/add/status").asInt()))
                    .andExpect(content().json(fixture.at("/add/response").toString(), true));
            verify(store).addAll(new HashSet<>(Arrays.asList("00123456", "MemberA")));

            for (JsonNode check : fixture.path("checks")) {
                String auth = check.path("auth").asText();
                mvc.perform(get(ENDPOINT + "/check").header("auth", auth))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().json(check.path("response").toString(), true));
                verify(store).contains(auth);
            }
        }
    }

    @Test
    void rejectsInvalidBatchesAndMissingOrRepeatedHeadersBeforeAccessingStorage() throws Exception {
        WhitelistRepository store = mock(WhitelistRepository.class);
        MockMvc mvc = mvc(store);
        for (String body : Arrays.asList("", "null", "{}", "[]", "[\"ok\",123]", "[true]", "[1.5]",
                "[\"ok\",null]", "[[\"id\"]]", "[\" \"]", "[\"ok \"]")) {
            expectFailure(mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body)),
                    400, "BAD_REQUEST");
        }
        expectFailure(mvc.perform(get(ENDPOINT + "/check").header("Authorization", "00123456")),
                400, "BAD_REQUEST");
        expectFailure(mvc.perform(get(ENDPOINT + "/check").header("auth", "00123456", "other")),
                400, "BAD_REQUEST");
        verifyNoInteractions(store);
    }

    @Test
    void wrapsErrorsRaisedBeforeControllerSelection() throws Exception {
        WhitelistRepository store = mock(WhitelistRepository.class);
        MockMvc mvc = mvc(store);
        expectFailure(mvc.perform(delete(ENDPOINT)).andExpect(header().string("Allow", "POST")),
                405, "BAD_REQUEST");
        expectFailure(mvc.perform(get(ENDPOINT + "/check").accept(MediaType.APPLICATION_XML)),
                406, "BAD_REQUEST");
        expectFailure(mvc.perform(post(ENDPOINT).contentType(MediaType.TEXT_PLAIN).content("[]")),
                415, "BAD_REQUEST");
        verifyNoInteractions(store);
    }

    @Test
    void reportsUnavailableWhenTheMapperCannotConnect() throws Exception {
        WhitelistMapper mapper = mock(WhitelistMapper.class);
        CannotGetJdbcConnectionException unavailable = new CannotGetJdbcConnectionException("database unavailable");
        when(mapper.selectByMemberId(anyString())).thenThrow(unavailable);
        when(mapper.insertAll(anyList())).thenThrow(unavailable);
        MockMvc mvc = mvc(new MybatisWhitelistRepository(mapper));
        expectFailure(mvc.perform(get(ENDPOINT + "/check").header("auth", "00123456")),
                503, "WHITELIST_UNAVAILABLE");
        expectFailure(mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("[\"00123456\"]")),
                503, "WHITELIST_UNAVAILABLE");
    }

    private static void expectFailure(ResultActions result, int httpStatus, String code) throws Exception {
        result.andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.returnCode").value(code))
                .andExpect(jsonPath("$.errorMsg").value(not(isEmptyString())))
                .andExpect(jsonPath("$.body").value(nullValue()));
    }

    private static MockMvc mvc(WhitelistRepository store) {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        converters.add(new MappingJackson2HttpMessageConverter(JSON));
        new WhitelistWebConfiguration(JSON).extendMessageConverters(converters);
        return MockMvcBuilders.standaloneSetup(new WhitelistController(new WhitelistService(store)))
                .setMessageConverters(converters.toArray(new HttpMessageConverter<?>[0]))
                .setHandlerExceptionResolvers(new WhitelistExceptionResolver(JSON))
                .build();
    }
}
