package com.github.lechandonga.idempotentsettlementledgerjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 语义：成功转账与幂等重放、四类失败在响应 body 的 errorCode 上可区分，
 * 且并发争用(409 CONCURRENT_CONFLICT)与幂等冲突(409 IDEMPOTENCY_CONFLICT)虽同状态码，
 * 调用方仍能凭 errorCode 分辨"重试"还是"换键"。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiIT {

    private static final Logger log = LoggerFactory.getLogger(LedgerApiIT.class);

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void initData() throws Exception {
        mockMvc.perform(post("/ledger/accounts/HA").content("{\"initialBalance\":100.00}")
                .contentType("application/json"));
        mockMvc.perform(post("/ledger/accounts/HB").content("{\"initialBalance\":0}")
                .contentType("application/json"));
    }

    @Test
    @DisplayName("HTTP: 转账成功、同键重放稳定；参数/余额不足/幂等冲突错误码互不相同")
    void transferReplayAndDistinctErrors() throws Exception {
        // 首次成功
        MvcResult first = mockMvc.perform(post("/ledger/transfers")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"HK1\",\"fromAccountNo\":\"HA\",\"toAccountNo\":\"HB\",\"amount\":30.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String transferId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.transferId");
        log.info("[输入] HTTP 首次转账 HK1; [判定] transferId={}", transferId);

        // 同键同内容重放：200、replayed=true、transferId 不变
        mockMvc.perform(post("/ledger/transfers")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"HK1\",\"fromAccountNo\":\"HA\",\"toAccountNo\":\"HB\",\"amount\":30.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.transferId").value(transferId));
        log.info("[输入] HTTP 同键重放; [判定] 200 replayed=true transferId 稳定");

        // 同键不同内容 -> 409 IDEMPOTENCY_CONFLICT
        mockMvc.perform(post("/ledger/transfers")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"HK1\",\"fromAccountNo\":\"HA\",\"toAccountNo\":\"HB\",\"amount\":99.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
        log.info("[输入] HTTP 同键改金额; [判定] 409 errorCode=IDEMPOTENCY_CONFLICT");

        // 参数错误 -> 422 PARAM_INVALID
        mockMvc.perform(post("/ledger/transfers")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"HK2\",\"fromAccountNo\":\"HA\",\"toAccountNo\":\"HB\",\"amount\":-1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"));
        log.info("[输入] HTTP 金额为负; [判定] 422 errorCode=PARAM_INVALID");

        // 余额不足 -> 422 INSUFFICIENT_FUNDS
        mockMvc.perform(post("/ledger/transfers")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"HK3\",\"fromAccountNo\":\"HA\",\"toAccountNo\":\"HB\",\"amount\":9999}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));
        log.info("[输入] HTTP 超额转账; [判定] 422 errorCode=INSUFFICIENT_FUNDS");

        // 未知账户 -> 404
        mockMvc.perform(post("/ledger/transfers")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"HK4\",\"fromAccountNo\":\"HA\",\"toAccountNo\":\"NOPE\",\"amount\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
        log.info("[输入] HTTP 目标账户不存在; [判定] 404 errorCode=ACCOUNT_NOT_FOUND");

        // 核对接口
        mockMvc.perform(get("/ledger/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true));
        log.info("[输入] HTTP 核对; [判定] balanced=true");
    }
}
