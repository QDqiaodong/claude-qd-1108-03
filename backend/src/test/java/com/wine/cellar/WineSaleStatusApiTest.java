package com.wine.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 停售/复售 HTTP 接口端到端：统一返回包装、阻断提示、资格字段随列表下发、
 * 停售→退窖→复售全链路与前台销售入口联动。
 * 真实端口起服务（改动会真实提交），类级 DirtiesContext 结束后重建上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class WineSaleStatusApiTest {

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper om;

    @Test
    void delistRelistLifecycleOverHttp() throws Exception {
        // 1) 酒款列表随返同一套可售资格字段
        JsonNode wines = get("/api/wines");
        assertTrue(wines.get("ok").asBoolean());
        JsonNode w1 = byId(wines.get("data"), 1);
        assertTrue(w1.get("saleEligible").asBoolean());
        assertEquals(80, w1.get("sellableQty").asInt());
        assertTrue(w1.get("stockConsistent").asBoolean());
        JsonNode w3 = byId(wines.get("data"), 3);
        assertFalse(w3.get("saleEligible").asBoolean(), "种子停售酒不可售");
        assertTrue(w3.get("saleBlockReason").asText().contains("停售"));

        // 2) 造在途出窖单 → 停售被拦截，提示原因与处理入口
        JsonNode sale = post("/api/sale-outs", "{\"items\":[{\"wineId\":1,\"qty\":2}]}");
        assertTrue(sale.get("ok").asBoolean());
        long saleId = sale.get("data").get("id").asLong();

        JsonNode blocked = post("/api/wines/1/delist", null);
        assertFalse(blocked.get("ok").asBoolean());
        assertTrue(blocked.get("message").asText().contains("在途业务"), blocked.get("message").asText());
        assertTrue(blocked.get("message").asText().contains("销售出窖"), blocked.get("message").asText());
        assertEquals("在售", byId(get("/api/wines").get("data"), 1).get("status").asText(), "被拦截时保持原状态");

        // 3) 退窖结案 → 停售成功，资格同步关闭
        assertTrue(post("/api/sale-outs/" + saleId + "/refund", null).get("ok").asBoolean());
        JsonNode delisted = post("/api/wines/1/delist", null);
        assertTrue(delisted.get("ok").asBoolean(), delisted.get("message").asText());
        assertEquals("停售", delisted.get("data").get("status").asText());

        JsonNode afterDelist = byId(get("/api/wines").get("data"), 1);
        assertFalse(afterDelist.get("saleEligible").asBoolean(), "停售后前台销售入口同步关闭");
        JsonNode blockedSale = post("/api/sale-outs", "{\"items\":[{\"wineId\":1,\"qty\":1}]}");
        assertFalse(blockedSale.get("ok").asBoolean());
        assertTrue(blockedSale.get("message").asText().contains("停售"));

        // 4) 重复停售 / 非法跳跃被拒
        assertFalse(post("/api/wines/1/delist", null).get("ok").asBoolean());

        // 5) 复售成功：资格恢复，可售量与批次合计一致
        JsonNode relisted = post("/api/wines/1/relist", null);
        assertTrue(relisted.get("ok").asBoolean(), relisted.get("message").asText());
        assertEquals("在售", relisted.get("data").get("status").asText());
        JsonNode elig = get("/api/wines/1/sale-eligibility").get("data");
        assertTrue(elig.get("saleEligible").asBoolean());
        assertEquals(80, elig.get("sellableQty").asInt());

        // 6) PUT 手工改状态被拒（保护通道不可绕过）
        JsonNode handEdit = put("/api/wines/1", "{\"status\":\"停售\"}");
        assertFalse(handEdit.get("ok").asBoolean());
        assertTrue(handEdit.get("message").asText().contains("停售/复售"), handEdit.get("message").asText());
        assertEquals("在售", byId(get("/api/wines").get("data"), 1).get("status").asText());

        // 7) 种子停售酒复售（批次/库存一致 → 通过），重复复售被拒
        JsonNode relist3 = post("/api/wines/3/relist", null);
        assertTrue(relist3.get("ok").asBoolean(), relist3.get("message").asText());
        assertEquals("在售", relist3.get("data").get("status").asText());
        assertEquals(10, get("/api/wines/3/sale-eligibility").get("data").get("sellableQty").asInt());
        assertFalse(post("/api/wines/3/relist", null).get("ok").asBoolean());
    }

    // ---------------- 工具 ----------------
    private JsonNode byId(JsonNode wines, long id) {
        for (JsonNode w : wines) {
            if (w.get("id").asLong() == id) {
                return w;
            }
        }
        throw new AssertionError("酒款不存在: " + id);
    }

    private JsonNode get(String path) throws Exception {
        return om.readTree(rest.getForObject(path, String.class));
    }

    private JsonNode post(String path, String json) throws Exception {
        return om.readTree(rest.exchange(path, HttpMethod.POST, entity(json), String.class).getBody());
    }

    private JsonNode put(String path, String json) throws Exception {
        return om.readTree(rest.exchange(path, HttpMethod.PUT, entity(json), String.class).getBody());
    }

    private HttpEntity<String> entity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json == null ? "" : json, headers);
    }
}
