package com.cmbchina.backend.standalone;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmb.bee.commons.exceptions.SysException;
import com.cmb.bee.web.entity.BeeResponseEntity;
import com.cmbchina.backend.common.exception.WebGlobalExceptionHandler;
import com.cmbchina.backend.common.exception.error.code.BusinessErrorCode;
import com.cmbchina.backend.common.exception.error.code.SystemErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResponseContractTest {
    @Test void successShapeAndExceptionParametersMatchFrontendContract() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        JsonNode success=mapper.readTree(mapper.writeValueAsBytes(BeeResponseEntity.success("value")));
        assertEquals(3,success.size());
        assertEquals("SUC0000",success.path("returnCode").asText());
        assertEquals("value",success.path("body").asText());
        Object[] params={"bad request"};
        BizException error=new BizException(BusinessErrorCode.PARAMETER_INVALID,params);
        params[0]="mutated";
        assertEquals("bad request",error.getMessage());
        assertEquals("bad request",error.getParams()[0]);
        error.getParams()[0]="mutated";
        assertEquals("bad request",error.getParams()[0]);
        BeeResponseEntity<String> response=new WebGlobalExceptionHandler().handleBizException(error);
        assertEquals("XCA0001",response.getReturnCode());
        assertEquals("bad request",response.getErrorMsg());
        assertNull(response.getBody());
        assertEquals("XCA9001",new WebGlobalExceptionHandler().handleSysException(new SysException(SystemErrorCode.UNKNOWN_EXCEPTION)).getReturnCode());
    }
    @Test void realAdviceHandlesMvcExceptions() throws Exception {
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new WebGlobalExceptionHandler()).build();
        mvc.perform(get("/probe")).andExpect(status().isOk()).andExpect(jsonPath("$.returnCode").value("XCA0001"))
            .andExpect(jsonPath("$.errorMsg").value("invalid"));
    }
    @RestController static class Probe {
        @GetMapping("/probe") public Object probe(){ throw new BizException(BusinessErrorCode.PARAMETER_INVALID,"invalid"); }
    }
}
