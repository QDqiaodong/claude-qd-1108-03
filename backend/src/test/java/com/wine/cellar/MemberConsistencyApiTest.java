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
 * 会员档案 HTTP 端到端：等级只读（接口体里出现 level 直接 400，不只是前端隐藏下拉）、
 * 只改联系方式不改积分、积分调整同结果复核等级、版本号过期提交被拒且最新积分保留、
 * 列表等级筛选与积分一致（刷新后仍成立）。真实端口起服务，改动真实提交。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class MemberConsistencyApiTest {

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper om;

    @Test
    void memberProfileRulesOverHttp() throws Exception {
        // 1) 新增：按积分核定等级；手填 level 即使与积分吻合也拒绝（没有可写通道）
        JsonNode blackAttempt = post("/api/members",
                "{\"phone\":\"13600000001\",\"name\":\"越级甲\",\"points\":0,\"level\":\"黑卡\"}");
        assertFalse(blackAttempt.get("ok").asBoolean());
        assertTrue(blackAttempt.get("message").asText().contains("积分")
                || blackAttempt.get("message").asText().contains("等级"));

        JsonNode created = post("/api/members",
                "{\"phone\":\"13600000001\",\"name\":\"越级甲\",\"points\":1900}");
        assertTrue(created.get("ok").asBoolean(), created.path("message").asText());
        long id = created.get("data").get("id").asLong();
        assertEquals("普通", created.get("data").get("level").asText());
        long v0 = created.get("data").get("version").asLong();

        // 2) 只改昵称：不带 points/level，积分 1900 与等级保持不变
        JsonNode renamed = put("/api/members/" + id,
                "{\"name\":\"越级甲改名\",\"version\":" + v0 + "}");
        assertTrue(renamed.get("ok").asBoolean(), renamed.path("message").asText());
        assertEquals(1900, renamed.get("data").get("points").asInt());
        assertEquals("普通", renamed.get("data").get("level").asText());
        long v1 = renamed.get("data").get("version").asLong();
        assertEquals(v0 + 1, v1);

        // 3) 调整积分到 5000：同一响应里等级升为黑卡
        JsonNode upgraded = put("/api/members/" + id,
                "{\"points\":5000,\"version\":" + v1 + "}");
        assertTrue(upgraded.get("ok").asBoolean(), upgraded.path("message").asText());
        assertEquals(5000, upgraded.get("data").get("points").asInt());
        assertEquals("黑卡", upgraded.get("data").get("level").asText());
        long v2 = upgraded.get("data").get("version").asLong();

        // 4) 用过期版本号提交联系方式修改 → 拒绝，最新积分 5000 保留
        JsonNode stale = put("/api/members/" + id,
                "{\"phone\":\"13600000099\",\"version\":" + v0 + "}");
        assertFalse(stale.get("ok").asBoolean());
        assertTrue(stale.get("message").asText().contains("刷新"), stale.get("message").asText());
        JsonNode latestNode = byId(get("/api/members").get("data"), id);
        assertEquals(5000, latestNode.get("points").asInt());
        assertEquals("黑卡", latestNode.get("level").asText());
        assertEquals("13600000001", latestNode.get("phone").asText(), "被拒提交不得改动手机号");

        // 5) 单独加积分（delta 入口）跨越黑卡阈值，PUT 旧版本随之进一步过期
        JsonNode plus = post("/api/members/" + id + "/points?delta=100", null);
        assertTrue(plus.get("ok").asBoolean(), plus.path("message").asText());
        assertEquals(5100, plus.get("data").get("points").asInt());
        assertEquals("黑卡", plus.get("data").get("level").asText());
        JsonNode stale2 = put("/api/members/" + id,
                "{\"name\":\"再改一次\",\"version\":" + v2 + "}");
        assertFalse(stale2.get("ok").asBoolean(), "积分变动后旧表单必须被拒，防止覆盖最新积分");
        assertEquals(5100, byId(get("/api/members").get("data"), id).get("points").asInt());

        // 6) 积分调回 2000 → 降级银卡（等级不是只升不降）
        long v3 = byId(get("/api/members").get("data"), id).get("version").asLong();
        JsonNode downgraded = put("/api/members/" + id,
                "{\"points\":2000,\"version\":" + v3 + "}");
        assertTrue(downgraded.get("ok").asBoolean());
        assertEquals("银卡", downgraded.get("data").get("level").asText());

        // 7) 负积分拒绝
        long v4 = downgraded.get("data").get("version").asLong();
        JsonNode neg = put("/api/members/" + id, "{\"points\":-1,\"version\":" + v4 + "}");
        assertFalse(neg.get("ok").asBoolean());
        JsonNode negDelta = post("/api/members/" + id + "/points?delta=-99999", null);
        assertFalse(negDelta.get("ok").asBoolean());

        // 8) 等级筛选与积分一致；再次刷新仍然成立（中文查询参数走 URI 变量展开，避免手写百分号被双重编码）
        JsonNode silverList = om.readTree(rest.getForObject("/api/members?level={level}", String.class, "银卡"));
        assertTrue(silverList.get("ok").asBoolean());
        boolean inSilver = false;
        for (JsonNode m : silverList.get("data")) {
            int points = m.get("points").asInt();
            assertTrue(points >= 2000 && points < 5000,
                    "银卡筛选结果必须与积分阈值一致，实际积分 " + points);
            if (m.get("id").asLong() == id) inSilver = true;
        }
        assertTrue(inSilver, "降级后应出现在银卡筛选中");
        JsonNode blackList = om.readTree(rest.getForObject("/api/members?level={level}", String.class, "黑卡"));
        for (JsonNode m : blackList.get("data")) {
            assertTrue(m.get("points").asInt() >= 5000, "黑卡筛选结果积分必须 ≥5000");
        }
        assertFalse(hasId(blackList.get("data"), id), "降级后不应再出现在黑卡筛选中");
        assertEquals("银卡", byId(get("/api/members").get("data"), id).get("level").asText(), "刷新后等级仍正确");
    }

    private boolean hasId(JsonNode arr, long id) {
        for (JsonNode n : arr) {
            if (n.get("id").asLong() == id) return true;
        }
        return false;
    }

    private JsonNode byId(JsonNode members, long id) {
        for (JsonNode m : members) {
            if (m.get("id").asLong() == id) {
                return m;
            }
        }
        throw new AssertionError("会员不存在: " + id);
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
