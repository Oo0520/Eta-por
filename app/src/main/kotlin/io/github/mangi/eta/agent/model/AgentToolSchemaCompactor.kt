package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 压缩 MCP 工具 schema 里的自然语言描述，保留 type／enum／required 等结构信息。
 * 只改发给模型的内容，不影响工具校验与执行。
 */
internal object AgentToolSchemaCompactor {
    private fun compactNode(node: JSONObject?, limit: Int) {
        if (node == null) return
        node.optJSONObject("properties")?.let { properties ->
            val keys = properties.keys()
            while (keys.hasNext()) {
                val child = properties.optJSONObject(keys.next()) ?: continue
                shorten(child, limit)
                compactNode(child, limit)
            }
        }
        node.optJSONObject("items")?.let { items ->
            shorten(items, limit)
            compactNode(items, limit)
        }
        for (key in COMBINATORS) {
            val list = node.optJSONArray(key) ?: continue
            for (index in 0 until list.length()) {
                val child = list.optJSONObject(index) ?: continue
                shorten(child, limit)
                compactNode(child, limit)
            }
        }
    }

    private fun shorten(node: JSONObject, limit: Int) {
        if (!node.has("description")) return
        val text = node.optString("description")
        if (text.length > limit) node.put("description", text.take(limit).trimEnd() + "…")
    }

    /**
     * MCP 工具单独压：描述来自第三方服务器，长度完全不受控（实测两个 server 合计约 190KB），
     * 而「正常档不压缩」这条只针对我们自己写的工具。结构与 enum／required 一律保留，
     * 只截断自然语言描述，因此不影响参数校验与执行。
     */
    fun compactMcp(tools: JSONArray) {
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            shorten(function, McpFunctionDescriptionLimit)
            compactNode(function.optJSONObject("parameters"), McpPropertyDescriptionLimit)
        }
    }

    /** 工具整体说明压到 200 字：保留「这个工具干什么」，去掉厂商的长篇用法举例。 */
    private const val McpFunctionDescriptionLimit = 200

    /** 单个参数说明压到 90 字：参数名、类型、必填、enum 都还在。 */
    private const val McpPropertyDescriptionLimit = 90

    private val COMBINATORS = listOf("anyOf", "oneOf", "allOf")
}
