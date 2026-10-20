package com.github.bookkeeping.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.bookkeeping.*
import com.github.bookkeeping.data.*
import com.github.bookkeeping.deferred.*
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
internal fun DeferredScreen(repository: DeferredRepository, bills: List<BillEntity>, onBack: () -> Unit) {
    val orders by repository.ordersFlow().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DeferredOrder?>(null) }
    var adding by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<DeferredOrder?>(null) }
    var action by remember { mutableStateOf<Pair<String, DeferredOrder>?>(null) }
    var expenseCandidate by remember { mutableStateOf<BillEntity?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        message = if (it) "提醒已开启；识别结果仍需在订单列表核对" else "未开启提醒，仍可在本页查看待确认订单"
    }
    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: Exception) { message = e.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    val unlinkedPayments = bills.filter { bill ->
        bill.direction == Direction.DEFERRED_PAYMENT && orders.none { it.settlementBillId == bill.id }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("返回") }
            Text("拼多多订单 / 先用后付", style = MaterialTheme.typography.headlineSmall)
            Text("先确认订单记消费，实际扣款只结清。未确认订单不计入消费；已确认待扣款 ¥${amountText(orders.filter { it.status == "OPEN" && it.paymentMode == "DEFERRED" }.sumOf { it.amountCents })}。")
            Text("提交按钮点击只生成待确认记录，请确认实际下单成功并核对金额、日期。部分退款、拆单和分次扣款请手动核对，暂不自动关联。", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, onClick = { adding = true }) { Text("补记先用后付订单") }
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else message = "系统中已允许通知；若仍无提醒，请检查该应用通知渠道设置"
            }) { Text("开启订单待确认提醒") }
        }
        if (orders.isEmpty()) item { Text("暂无订单。开启拼多多来源和无障碍识别后，正常下单；未识别时可用上方按钮补记。") }
        items(orders, key = { "order-${it.id}" }) { order ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(order.product, style = MaterialTheme.typography.titleMedium)
                    Text("¥${amountText(order.amountCents)} · ${statusText(order.status)}")
                    Text(if (order.paymentMode == "DEFERRED") "支付方式：先用后付" else "支付方式：多多支付")
                    if (order.evidence.isNotBlank()) Text(order.evidence)
                    Text("下单时间：${dateTimeText(order.placedAt)}")
                    Text(stringResource(CategoryCatalog.labelRes(order.categoryKey)))
                    if (order.orderNumber.isNotBlank()) Text("订单号：${order.orderNumber}")
                    if (order.status == "REVIEW") Text("请核对页面金额、商品和下单日期；尚未计入支出。")
                    if (order.status in setOf("REVIEW", "OPEN")) {
                        Row {
                            TextButton(enabled = !busy, onClick = { editing = order }) { Text(if (order.status == "REVIEW") "核对并确认" else "修改订单") }
                            TextButton(enabled = !busy, onClick = { action = "cancel" to order }) { Text(if (order.status == "REVIEW") "忽略" else "取消整单") }
                        }
                    }
                    if (order.status == "OPEN") {
                        TextButton(enabled = !busy, onClick = { linking = order }) { Text("关联已扣款账单") }
                        TextButton(enabled = !busy, onClick = { action = "settle" to order }) { Text("已扣款但没有扣款账单") }
                    }
                    if (order.status == "SETTLED") {
                        Text("结清时间：${dateTimeText(order.settledAt)}")
                        TextButton(enabled = !busy, onClick = { action = "undo" to order }) { Text("撤销结清") }
                    }
                }
            }
        }
        item { Text("待核对扣款（不计入消费）", style = MaterialTheme.typography.titleMedium) }
        if (unlinkedPayments.isEmpty()) item { Text("暂无待核对扣款。普通微信扣款若未能识别为先用后付，可在订单的关联按钮中选择。") }
        items(unlinkedPayments, key = { "payment-${it.id}" }) { bill ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("¥${amountText(bill.amountCents)} · ${dateTimeText(bill.transactionTime)}")
                    Text(bill.merchant.orEmpty())
                    Text("如果已有订单，请在该订单中关联；如果没有登记过消费，可作为普通支出。")
                    TextButton(enabled = !busy, onClick = { expenseCandidate = bill }) { Text("作为普通支出") }
                }
            }
        }
    }
    if (adding || editing != null) {
        DeferredEditDialog(editing, busy, onDismiss = { if (!busy) { adding = false; editing = null } }) { product, cents, category, number, time, mode ->
            val id = editing?.id
            runAction {
                repository.confirm(id, product, cents, category, number, time, mode)
                adding = false; editing = null
            }
        }
    }
    linking?.let { order ->
        val candidates = bills.filter { bill ->
            DeferredParser.canSettle(order, bill) && orders.none { it.billId == bill.id || it.settlementBillId == bill.id }
        }
        AlertDialog(onDismissRequest = { if (!busy) linking = null }, title = { Text("选择 ¥${amountText(order.amountCents)} 的实际扣款") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text("请确认这是同一订单的扣款。关联后，该扣款不再重复计入支出。这里只列出下单后同金额的未关联记录，不代表已经匹配。")
                    if (candidates.isEmpty()) Text("没有符合条件的账单；部分扣款或金额不同暂不支持关联。")
                    candidates.forEach { bill ->
                        TextButton(enabled = !busy, onClick = {
                            runAction { repository.settle(order.id, bill.id); linking = null }
                        }) { Text("${dateTimeText(bill.transactionTime)} · ${bill.merchant.orEmpty()} · ${sourceName(bill.sourceChannel)} ¥${amountText(bill.amountCents)}") }
                    }
                }
            }, confirmButton = { TextButton(enabled = !busy, onClick = { linking = null }) { Text("关闭") } })
    }
    action?.let { (kind, order) ->
        val text = when (kind) {
            "cancel" -> "确认整单取消或忽略？已入账的这笔消费将删除，订单保留取消状态。部分退款请勿使用此操作。"
            "undo" -> "撤销结清后订单恢复待扣款，关联的扣款恢复待核对，不计入消费。"
            else -> "确认已实际扣款？仅将订单标记为结清。以后如出现扣款账单，请先撤销结清，再关联该账单，避免重复。"
        }
        AlertDialog(onDismissRequest = { if (!busy) action = null }, title = { Text("请确认") }, text = { Text(text) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                runAction {
                    when (kind) { "cancel" -> repository.cancel(order.id); "undo" -> repository.undoSettlement(order.id); else -> repository.settle(order.id, null) }
                    action = null
                }
            }) { Text("确认") } }, dismissButton = { TextButton(enabled = !busy, onClick = { action = null }) { Text("返回") } })
    }
    expenseCandidate?.let { bill ->
        AlertDialog(onDismissRequest = { expenseCandidate = null }, title = { Text("作为普通支出？") },
            text = { Text("¥${amountText(bill.amountCents)} 将计入扣款当天的支出。请确认此前没有为此订单记过消费。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { runAction { repository.treatAsExpense(bill.id); expenseCandidate = null } }) { Text("确认计入支出") } },
            dismissButton = { TextButton(onClick = { expenseCandidate = null }) { Text("取消") } })
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }) }
}

private fun statusText(status: String) = when (status) { "REVIEW" -> "待确认"; "OPEN" -> "已记消费 · 待核对付款"; "SETTLED" -> "已结清"; else -> "已取消 / 已忽略" }

@Composable
private fun DeferredEditDialog(order: DeferredOrder?, busy: Boolean, onDismiss: () -> Unit, onSave: (String, Long, String, String, Long, String) -> Unit) {
    var product by remember(order?.id) { mutableStateOf(order?.product.orEmpty()) }
    var amount by remember(order?.id) { mutableStateOf(order?.let { amountText(it.amountCents) }.orEmpty()) }
    var number by remember(order?.id) { mutableStateOf(order?.orderNumber.orEmpty()) }
    var category by remember(order?.id) { mutableStateOf(order?.categoryKey ?: CategoryKey.SHOPPING) }
    var time by remember(order?.id) { mutableLongStateOf(order?.placedAt ?: System.currentTimeMillis()) }
    var mode by remember(order?.id) { mutableStateOf(order?.paymentMode ?: "DEFERRED") }
    var categoryMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("确认下单消费") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("请确认已下单成功；仅点击提交不代表成功。确认后按下单时间计入支出，后续付款关联原消费。")
            Row {
                FilterChip(selected = mode == "DEFERRED", onClick = { mode = "DEFERRED" }, label = { Text("先用后付") }, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = mode == "IMMEDIATE", onClick = { mode = "IMMEDIATE" }, label = { Text("多多支付") }, enabled = !busy)
            }
            OutlinedTextField(product, { product = it.take(100) }, label = { Text("商品或商户") }, enabled = !busy)
            OutlinedTextField(amount, { amount = it }, label = { Text("订单应付总额（元）") }, singleLine = true, enabled = !busy)
            OutlinedTextField(number, { number = it.take(40) }, label = { Text("订单号（可选，用于准确关联扣款）") }, singleLine = true, enabled = !busy)
            Box {
                TextButton(enabled = !busy, onClick = { categoryMenu = true }) { Text("分类：${stringResource(CategoryCatalog.labelRes(category))}") }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    CategoryCatalog.expense.forEach { item -> DropdownMenuItem(text = { Text(stringResource(item.labelRes)) }, onClick = { category = item.key; categoryMenu = false }) }
                }
            }
            Text("下单时间：${dateTimeText(time)}")
            TextButton(enabled = !busy, onClick = {
                val c = Calendar.getInstance().apply { timeInMillis = time }
                DatePickerDialog(context, { _, y, m, d -> c.set(y, m, d); time = c.timeInMillis }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text("修改下单日期") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(enabled = !busy, onClick = {
        val cents = parseAmountCents(amount)
        if (product.isBlank() || cents == null || cents !in 1..999_999_999L) error = "请填写商品和有效金额（最多两位小数）"
        else if (!Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(amount.trim())) error = "金额最多两位小数"
        else onSave(product, cents, category, number.trim(), time, mode)
    }) { Text(if (busy) "保存中…" else "确认入账") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
