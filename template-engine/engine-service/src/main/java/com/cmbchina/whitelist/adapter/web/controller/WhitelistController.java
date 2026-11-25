package com.cmbchina.whitelist.adapter.web.controller;

import com.cmb.bee.web.annotations.BeeResponseBody;
import com.cmb.bee.web.entity.BeeResponseEntity;
import com.cmbchina.common.exception.ServiceException;
import com.cmbchina.whitelist.application.WhitelistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@BeeResponseBody
@RequiredArgsConstructor
@RequestMapping("/v1/whitelist")
public class WhitelistController {
    private static final int MAX_BATCH_SIZE = 500;

    private final WhitelistService whitelistService;

    @PostMapping
    public BeeResponseEntity<Void> add(@RequestBody List<String> ids) {
        whitelistService.add(ids);
        return BeeResponseEntity.success();
    }

    @GetMapping(value = "/check")
    public BeeResponseEntity<Boolean> check(@RequestHeader HttpHeaders headers) {
        List<String> auth = headers.get("auth");
        if (auth == null || auth.size() != 1) {
            throw new ServiceException("BAD_REQUEST", "exactly one auth header is required", 400);
        }
        return BeeResponseEntity.success(whitelistService.contains(auth.get(0)));
    }
}
