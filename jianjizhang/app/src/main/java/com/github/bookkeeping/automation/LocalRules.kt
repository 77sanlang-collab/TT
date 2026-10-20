package com.github.bookkeeping.automation

import android.content.Context
import com.github.bookkeeping.AccessibilityPackages
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** No executable regex/scripts are imported. User rules consist only of bounded literal labels. */
data class LocalRule(val id: String = UUID.randomUUID().toString(), val packageName: String, val name: String,
    val trigger: String, val click: Boolean = true, val amountLabels: List<String> = AmountReader.labels,
    val enabled: Boolean = true)

class LocalRules(context: Context) {
    val prefs = context.getSharedPreferences("local_rules", Context.MODE_PRIVATE)
    fun all(): List<LocalRule> = runCatching {
        val a = JSONArray(prefs.getString("rules", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            val labels = o.getJSONArray("labels")
            LocalRule(o.getString("id"), o.getString("package"), o.getString("name"), o.getString("trigger"),
                o.getBoolean("click"), (0 until labels.length()).map { labels.getString(it) }, o.getBoolean("enabled"))
        } }
    }.getOrDefault(emptyList())
    fun save(rule: LocalRule) {
        require(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+").matches(rule.packageName)) { "请输入有效应用包名" }
        require(rule.packageName != AccessibilityPackages.SELF && !rule.packageName.startsWith("com.android.") && rule.packageName != "android") { "不能读取记账应用自身或系统应用" }
        require(rule.name.isNotBlank() && rule.name.length <= 40 && rule.trigger.isNotBlank() && rule.trigger.length <= 40) { "名称和触发文字需为 1 至 40 个字符" }
        require(rule.amountLabels.size in 1..24 && rule.amountLabels.all { it.isNotBlank() && it.length <= 30 }) { "金额标签需为 1 至 24 项，每项不超过 30 字" }
        val items = all().filterNot { it.id == rule.id } + rule
        require(items.size <= 30) { "最多保存 30 条规则" }
        write(items)
    }
    fun remove(id: String) = write(all().filterNot { it.id == id })
    private fun write(items: List<LocalRule>) {
        val a = JSONArray()
        items.forEach { r -> a.put(JSONObject().put("id", r.id).put("package", r.packageName).put("name", r.name)
            .put("trigger", r.trigger).put("click", r.click).put("labels", JSONArray(r.amountLabels)).put("enabled", r.enabled)) }
        check(prefs.edit().putString("rules", a.toString()).commit()) { "规则保存失败" }
    }
    fun matches(rule: LocalRule, pkg: String?, text: String, clicked: String?): Boolean = rule.enabled &&
        pkg == rule.packageName && if (rule.click) clicked?.contains(rule.trigger) == true else text.contains(rule.trigger)
}
