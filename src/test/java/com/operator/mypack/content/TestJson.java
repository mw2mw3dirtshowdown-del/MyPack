package com.operator.mypack.content;

import com.google.gson.JsonObject;
import com.operator.mypack.pack.Issues;
import com.operator.mypack.utils.JsonUtils;

import java.nio.charset.StandardCharsets;

/** Small helpers shared by the content tests. */
final class TestJson {

    private TestJson() {
    }

    static JsonObject obj(String json) {
        return JsonUtils.parseObject(json.getBytes(StandardCharsets.UTF_8));
    }

    static ParseContext ctx(Issues issues) {
        return new ParseContext("aether", "test.json", issues);
    }
}
