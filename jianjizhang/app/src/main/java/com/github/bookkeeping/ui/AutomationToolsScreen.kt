package com.github.bookkeeping.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.bookkeeping.*
import com.github.bookkeeping.automation.*
import com.github.bookkeeping.deferred.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AutomationToolsScreen(onBack: () -> Unit, onOrders: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalRules(context) }
    var rules by remember { mutableStateOf(store.all()) }
    var editing by remember { mutableStateOf<LocalRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var screenshot by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val snapshot by Diagnostics.snapshots.collectAsState()
    var diagnosticsActive by remember { mutableStateOf(Diagnostics.until > System.currentTimeMillis()) }
    var overlay by remember { mutableStateOf(store.prefs.getBoolean("overlay", false)) }
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = onBack) { Text("返回") }
            Text("本地识别工具", style = MaterialTheme.typography.headlineSmall)
            Text("截图补记、自动规则、诊断和悬浮确认均在本机处理。截图导入一笔账单不会自动学会整个平台。")
            Button(onClick = { screenshot = true }) { Text("选择截图，识别补记") }
            Row {
                Checkbox(checked = overlay, onCheckedChange = { enabled ->
                    overlay = enabled; store.prefs.edit().putBoolean("overlay", enabled).apply()
                    if (enabled && !Settings.canDrawOverlays(context)) context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                })
                Text("显示待确认悬浮卡片")
            }
            Text("需要系统悬浮窗权限；支付安全页面、锁屏或系统限制下可能不显示，仍可点通知或进入待确认列表。", style = MaterialTheme.typography.bodySmall)
            Text("自定义识别规则", style = MaterialTheme.typography.titleLarge)
            Text("新增或修改规则后即时生效，无需重装。规则只能处理应用暴露给无障碍的文字；读不到页面时请用截图补记。")
            Button(onClick = { adding = true }) { Text("添加平台规则") }
        }
        items(rules, key = { it.id }) { rule ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(rule.name); Text(rule.packageName)
                    Text((if (rule.click) "点击：" else "页面包含：") + rule.trigger)
                    Switch(checked = rule.enabled, onCheckedChange = { enabled ->
                        runCatching { store.save(rule.copy(enabled = enabled)); rules = store.all() }.onFailure { message = it.message }
                    })
                    Row {
                        TextButton(onClick = { editing = rule }) { Text("编辑 / 测试") }
                        TextButton(onClick = { store.remove(rule.id); rules = store.all() }) { Text("删除规则") }
                    }
                }
            }
        }
        item {
            HorizontalDivider(); Text("识别诊断", style = MaterialTheme.typography.titleLarge)
            Text("主动开启后，最多诊断 10 分钟。只暂存在内存，可能含收货地址等隐私；手机号和长号码会尝试遮盖，但不是完整脱敏。不要把未检查的文字发给别人。")
            Row {
                Button(onClick = { Diagnostics.start(); diagnosticsActive = true }) { Text("开始 10 分钟诊断") }
                TextButton(onClick = { Diagnostics.clear(); diagnosticsActive = false }) { Text("停止并清空") }
            }
            Text(if (diagnosticsActive && Diagnostics.until > System.currentTimeMillis()) "请切换到支付平台重现页面，再回来看结果。" else "诊断未开启或已到期")
            if (snapshot.at != 0L && Diagnostics.until > System.currentTimeMillis()) {
                Text("应用：${snapshot.pkg}\n时间：${dateTimeText(snapshot.at)}\n事件：${snapshot.event}\n判断：${snapshot.reason}")
                SelectionContainer { Text(snapshot.text) }
            }
        }
    }
    if (adding || editing != null) RuleEditor(editing, snapshot, onDismiss = { adding = false; editing = null }) { rule ->
        runCatching { store.save(rule); rules = store.all(); adding = false; editing = null }.onFailure { message = it.message }
    }
    if (screenshot) ScreenshotImportDialog(onDismiss = { screenshot = false }, onSaved = { screenshot = false; onOrders() })
    message?.let { AlertDialog(onDismissRequest = { message = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }) }
}

@Composable
private fun RuleEditor(initial: LocalRule?, snapshot: Diagnostics.Snapshot, onDismiss: () -> Unit, onSave: (LocalRule) -> Unit) {
    val context = LocalContext.current
    var pkg by remember { mutableStateOf(initial?.packageName ?: snapshot.pkg) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var trigger by remember { mutableStateOf(initial?.trigger ?: "支付成功") }
    var click by remember { mutableStateOf(initial?.click ?: false) }
    var labels by remember { mutableStateOf((initial?.amountLabels ?: AmountReader.labels).joinToString("，")) }
    var sample by remember { mutableStateOf(snapshot.text) }
    var result by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    fun rule() = LocalRule(initial?.id ?: java.util.UUID.randomUUID().toString(), pkg.trim(), name.trim(), trigger.trim(), click,
        labels.split(Regex("[,，\\n]")).map { it.trim() }.filter { it.isNotBlank() }, initial?.enabled ?: true)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("平台识别规则") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("平台 / 付款账户名称") })
            TextButton(onClick = { choosing = true }) { Text("从手机已安装应用选择") }
            OutlinedTextField(pkg, { pkg = it.take(150) }, label = { Text("应用包名，可手动填写") })
            OutlinedTextField(trigger, { trigger = it.take(40) }, label = { Text("触发文字，例如立即支付或支付成功") })
            Row { Checkbox(click, { click = it }); Text("仅实际点击这个按钮时触发") }
            Text("未勾选时，页面出现文字即可生成待确认；不要填商品页常见文字，建议使用明确成功文案。")
            OutlinedTextField(labels, { labels = it.take(800) }, label = { Text("金额标签，按优先级用逗号分隔") })
            Text("例如：立即支付，总计，实付款。存在多个不同的同优先级金额时，需人工选择。")
            OutlinedTextField(sample, { sample = it.take(12000) }, label = { Text("测试页面文字（可从诊断复制）") }, minLines = 3)
            TextButton(onClick = {
                val r = rule()
                val parsed = ShoppingParser.parse(r.packageName, sample, if (r.click) r.trigger else null, System.currentTimeMillis(), listOf(r))
                result = if (parsed == null) "未触发。请检查触发文字和应用包名。" else "模拟测试，不会付款或入账：" + parsed.reason +
                    if (parsed.cents > 0) "；¥${amountText(parsed.cents)}" else "；需要选择或输入金额"
            }) { Text("模拟规则测试（不入账）") }
            Text(result)
        }
    }, confirmButton = { Button(onClick = { onSave(rule()) }) { Text("保存规则") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if (choosing) {
        @Suppress("DEPRECATION")
        val apps = remember {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != context.packageName }.distinctBy { it.activityInfo.packageName }
                .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }.sortedBy { it.second }
        }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择应用") }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) { items(apps) { (id, label) -> TextButton(onClick = { pkg = id; if (name.isBlank()) name = label; choosing = false }) { Text("$label\n$id") } } }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("关闭") } })
    }
}

@Composable
private fun ScreenshotImportDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var imageFingerprint by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf(Platforms.SCREENSHOT) }
    var edit by remember { mutableStateOf<com.github.bookkeeping.data.DeferredOrder?>(null) }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true; error = ""
            scope.launch {
                try { val result = ScreenshotOcr.read(context, uri); text = result.text; imageFingerprint = result.fingerprint; if (text.isBlank()) error = "未识别到文字，可裁剪后重试或手动补记。" }
                catch (e: Exception) { error = e.message ?: "图片识别失败" }
                finally { busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("离线截图补记") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("仅处理你主动选择的图片，不保存图片、不上传。截图可能是未付款页面，确认下单或支付已成功后再入账。")
            Button(enabled = !busy, onClick = { picker.launch("image/*") }) { Text(if (busy) "本机识别中…" else "选择一张截图") }
            listOf(Platforms.SCREENSHOT, Platforms.XHS, Platforms.DOUYIN, Platforms.XIANYU, SourceKey.PINDUODUO, SourceKey.WECHAT, SourceKey.ALIPAY).forEach { key ->
                FilterChip(selected = platform == key, onClick = { platform = key }, label = { Text(Platforms.name(key)) })
            }
            OutlinedTextField(text, { text = it.take(24000) }, label = { Text("识别文字（可纠正错字）") }, minLines = 3)
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(enabled = !busy && imageFingerprint.isNotBlank(), onClick = {
        val amount = AmountReader.read(text)
        edit = com.github.bookkeeping.data.DeferredOrder().apply {
            this.platform = platform; accountName = Platforms.name(platform); product = Platforms.name(platform) + "截图账单"
            amountCents = amount.cents ?: 0; amountOptions = amount.choices.joinToString(","); evidence = amount.reason + "；截图补记需确认交易已完成"
            paymentMode = "IMMEDIATE"; placedAt = System.currentTimeMillis(); this.fingerprint = imageFingerprint
        }
    }) { Text("核对金额与分类") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
    edit?.let { draft ->
        OrderEditor(draft, busy, onDismiss = { edit = null }) { saved ->
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db = BookkeepingApplication.instance.database
                        check(db.deferredOrderDao().byFingerprint(draft.fingerprint) == null) { "这张截图已导入或正在待确认列表，请勿重复" }
                        check(DeferredRepository(db).captureParsed(DeferredParser.Order("", saved.product, saved.amount, draft.fingerprint, saved.mode, false,
                            saved.platform, saved.account, emptyList(), "截图补记，需手动确认日期", saved.category, saved.direction), saved.time)) { "相同截图近期已忽略或重复，请检查订单列表" }
                    }
                    // Screenshot is staged before final confirmation. If confirmation fails, it remains recoverable.
                    val db = BookkeepingApplication.instance.database
                    val id = withContext(Dispatchers.IO) { db.deferredOrderDao().byFingerprint(draft.fingerprint)!!.id }
                    DeferredRepository(db).confirm(id, saved.product, saved.amount, saved.category, saved.number, saved.time, saved.mode, saved.platform, saved.account, saved.direction)
                    onSaved()
                } catch (e: Exception) { error = e.message ?: "保存失败"; edit = null }
                finally { busy = false }
            }
        }
    }
}
