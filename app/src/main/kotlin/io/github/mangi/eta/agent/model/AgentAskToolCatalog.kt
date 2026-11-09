package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * `ask_user`：让模型主动向用户提问并拿到带选项的答案。
 *
 * 这是「审批卡」的另一半——系统拦截只覆盖危险动作，
 * 但「你更想要 A 还是 B」这类问题只有模型自己知道要问。
 * 调用后本轮会暂停，用户选完以消息形式回灌，模型不会自己编答案。
 */
internal object AgentAskToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "ask_user",
                description = "向用户提出一个带若干选项的问题，本轮会暂停直到用户选择。" +
                    "用于：需要用户拍板、偏好无法从上下文推断、或有多条合理路线需要他选。" +
                    "不要用它问已经知道答案的事，也不要用它代替确认危险动作（危险动作由系统自动拦截）。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "question",
                                JSONObject().put("type", "string")
                                    .put("description", "一句话问题，会作为卡片标题。"),
                            )
                            .put(
                                "body",
                                JSONObject().put("type", "string")
                                    .put("description", "补充说明：为什么问他、这个选择会影响什么。"),
                            )
                            .put(
                                "options",
                                JSONObject()
                                    .put("type", "array")
                                    .put("description", "选项数量不设上限，第一个作为推荐项；但别为了凑数塞无用项。")
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties",
                                                JSONObject()
                                                    .put(
                                                        "label",
                                                        JSONObject().put("type", "string")
                                                            .put("description", "选项文案，尽量短。"),
                                                    )
                                                    .put(
                                                        "description",
                                                        JSONObject().put("type", "string")
                                                            .put("description", "选了这个会发生什么。"),
                                                    ),
                                            )
                                            .put("required", JSONArray().put("label")),
                                    ),
                            )
                            .put(
                                "allow_multiple",
                                JSONObject().put("type", "boolean")
                                    .put("description", "是否允许多选，默认 false。"),
                            ),
                    )
                    .put("required", JSONArray().put("question").put("options")),
            ),
        )
    }
}
