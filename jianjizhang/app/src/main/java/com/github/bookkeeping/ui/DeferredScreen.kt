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
import com.github.bookkeeping.automation.*
import com.github.bookkeeping.data.*
import com.github.bookkeeping.deferred.*
import kotlinx.coroutines.launch
import java.util.Calendar

internal data class EditedOrder(val product: String, val amount: Long, val category: String, val number: String, val time: Long,
    val mode: String, val platform: String, val account: String, val direction: String)

@Composable
internal fun DeferredScreen(repository: DeferredRepository, bills: List<BillEntity>, onBack: () -> Unit, onTools: () -> Unit) {
    val orders by repository.ordersFlow().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<DeferredOrder?>(null) }
    var adding by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<DeferredOrder?>(null) }
    var action by remember { mutableStateOf<Pair<String, DeferredOrder>?>(null) }
    var expense by remember { mutableStateOf<BillEntity?>(null) }
    var repayment by remember { mutableStateOf<DeferredOrder?>(null) }
    var draftLink by remember { mutableStateOf<DeferredOrder?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { message = if (it) "已允许通知" else "未允许通知，仍可在本页核对" }
    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } catch (e: Exception) { message = e.message ?: "操作失败" } finally { busy = false } }
    }
    val unlinked = bills.filter { it.direction == Direction.DEFERRED_PAYMENT && orders.none { o -> o.settlementBillId == it.id } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = onBack) { Text("返回") }
            Text("待确认 / 平台订单", style = MaterialTheme.typography.headlineSmall)
            Text("先核对交易已完成，再确认金额和分类。待确认不计收支；先用后付待扣款 ¥${amountText(orders.filter { it.status == "OPEN" && it.paymentMode == "DEFERRED" }.sumOf { it.amountCents })}。")
            Row {
                Button(enabled = !busy, onClick = { adding = true }) { Text("手动补记") }
                TextButton(onClick = onTools) { Text("截图 / 规则 / 诊断") }
            }
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else message = "已允许通知；如仍无提醒，请检查系统通知渠道或后台限制"
            }) { Text("开启待确认通知") }
        }
        if (orders.isEmpty()) item { Text("暂无记录。仅浏览商品或结算页不会记账；点击提交后仍需确认下单成功。") }
        items(orders.sortedBy { if (it.status == "REVIEW") 0 else 1 }, key = { "order-${it.id}" }) { order ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(order.product, style = MaterialTheme.typography.titleMedium)
                    Text((if (order.amountCents > 0) "¥${amountText(order.amountCents)}" else "金额待选择") + " · " + statusText(order))
                    Text("账户：${order.accountName.ifBlank { if (order.paymentMode == "DEFERRED") "拼多多先用后付" else Platforms.name(order.platform) }}")
                    Text("交易日期：${dateTimeText(order.placedAt)}")
                    if (order.evidence.isNotBlank()) Text(order.evidence, style = MaterialTheme.typography.bodySmall)
                    if (order.orderNumber.isNotBlank()) Text("订单号：${order.orderNumber}")
                    if (order.status in setOf("REVIEW", "OPEN") || order.direction == Direction.INCOME) {
                        Row {
                            TextButton(enabled = !busy, onClick = { editing = order }) { Text(if (order.status == "REVIEW") "核对并确认" else "修改") }
                            TextButton(enabled = !busy, onClick = { action = "cancel" to order }) { Text(if (order.status == "REVIEW") "忽略" else "取消整笔") }
                        }
                    }
                    if (order.status == "REVIEW" && order.direction == Direction.EXPENSE) {
                        TextButton(enabled = !busy, onClick = { repayment = order }) { Text("这是还款，不计支出") }
                        TextButton(enabled = !busy && order.amountCents > 0, onClick = { draftLink = order }) { Text("这是已记订单的付款 / 扣款") }
                    }
                    if (order.status == "OPEN") {
                        TextButton(enabled = !busy, onClick = { linking = order }) { Text("关联已入账的付款 / 扣款") }
                        TextButton(enabled = !busy, onClick = { action = "settle" to order }) { Text("已付款，没有其他付款账单") }
                    }
                    if (order.status == "SETTLED" && order.direction == Direction.EXPENSE) {
                        Text("付款核对时间：${dateTimeText(order.settledAt)}")
                        TextButton(enabled = !busy, onClick = { action = "undo" to order }) { Text("撤销付款关联 / 结清") }
                    }
                }
            }
        }
        item { Text("待核对扣款（不计消费）", style = MaterialTheme.typography.titleMedium) }
        items(unlinked, key = { "payment-${it.id}" }) { bill ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("¥${amountText(bill.amountCents)} · ${dateTimeText(bill.transactionTime)}\n${bill.merchant.orEmpty()}")
                Text("如已登记购物消费，请在原订单关联；如确实未记过消费，才作为支出。")
                TextButton(enabled = !busy, onClick = { expense = bill }) { Text("作为普通支出") }
            } }
        }
    }
    if (adding || editing != null) OrderEditor(editing, busy, onDismiss = { if (!busy) { adding = false; editing = null } }) { saved ->
        val current = editing
        runAction {
            repository.confirm(current?.id, saved.product, saved.amount, saved.category, saved.number, saved.time,
                saved.mode, saved.platform, saved.account, saved.direction, current?.status == "REVIEW")
            LearnedCategories(context).save(saved.platform, saved.product, saved.direction, saved.category)
            adding = false; editing = null
        }
    }
    linking?.let { order ->
        val candidates = bills.filter { bill -> DeferredParser.canSettle(order, bill) && orders.none { it.billId == bill.id || it.settlementBillId == bill.id } }
        ChoiceDialog("选择实际付款", "以下只是同金额候选，不代表已匹配。请核对同一笔订单，关联后付款记录不再计第二笔消费。", busy, { linking = null },
            candidates.map { it.id to "${dateTimeText(it.transactionTime)} · ${it.merchant.orEmpty()} ¥${amountText(it.amountCents)}" }) { id ->
            runAction { repository.settle(order.id, id); linking = null }
        }
    }
    draftLink?.let { draft ->
        val candidates = orders.filter { it.status == "OPEN" && it.direction == Direction.EXPENSE && it.id != draft.id && it.amountCents == draft.amountCents && draft.placedAt >= it.placedAt }
        ChoiceDialog("选择已记的原消费", "请确认是同一笔。合并后保留原消费日期，本次记录只作为付款，不再计支出。", busy, { draftLink = null },
            candidates.map { it.id to "${dateTimeText(it.placedAt)} · ${it.product} ¥${amountText(it.amountCents)}" }) { id ->
            runAction { repository.linkDraftPayment(draft.id, id); draftLink = null }
        }
    }
    action?.let { (kind, order) ->
        val explanation = when (kind) {
            "cancel" -> if (order.status == "REVIEW") "忽略后将删除这条记录，不计收支。仅保留最小哈希标识防止近期重复提醒。" else "取消整笔将删除原消费或收入。部分退款请不要使用此操作。"
            "undo" -> "撤销后恢复未核对付款状态；关联的扣款仍不重复计入消费。"
            else -> "确认已实际付款？仅标记付款已核对，不新增消费。以后出现付款账单时，先撤销结清再关联。"
        }
        AlertDialog(onDismissRequest = { if (!busy) action = null }, title = { Text("请确认") }, text = { Text(explanation) },
            confirmButton = { TextButton(enabled = !busy, onClick = { runAction {
                when (kind) {
                    "cancel" -> if (order.status == "REVIEW") repository.ignoreReview(order.id) else repository.cancel(order.id)
                    "undo" -> repository.undoSettlement(order.id)
                    else -> repository.settle(order.id, null)
                }; action = null
            } }) { Text("确认") } }, dismissButton = { TextButton(enabled = !busy, onClick = { action = null }) { Text("返回") } })
    }
    repayment?.let { order ->
        var amount by remember(order.id) { mutableStateOf(if (order.amountCents > 0) amountText(order.amountCents) else "") }
        AlertDialog(onDismissRequest = { if (!busy) repayment = null }, title = { Text("还款不计支出") }, text = {
            Column { Text("保留还款记录，但不计消费。请确认购物消费此前已记过，利息和手续费应另外记支出。")
                OutlinedTextField(amount, { amount = it }, label = { Text("核对还款金额（元）") }) }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { runAction {
            require(Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(amount.trim())) { "请输入最多两位小数的金额" }
            repository.repaymentDraft(order.id, parseAmountCents(amount)); repayment = null
        } }) { Text("确认不计支出") } }, dismissButton = { TextButton(enabled = !busy, onClick = { repayment = null }) { Text("取消") } })
    }
    expense?.let { bill -> AlertDialog(onDismissRequest = { expense = null }, title = { Text("确认计入支出？") }, text = { Text("¥${amountText(bill.amountCents)} 将计入这次扣款日期的支出，请确认没有登记过原消费。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { runAction { repository.treatAsExpense(bill.id); expense = null } }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { expense = null }) { Text("取消") } }) }
    message?.let { AlertDialog(onDismissRequest = { message = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }) }
}

private fun statusText(order: DeferredOrder) = when (order.status) {
    "REVIEW" -> "待确认（未计入收支）"; "OPEN" -> if (order.paymentMode == "DEFERRED") "已记消费 · 待扣款" else "已记消费"
    "SETTLED" -> if (order.direction == Direction.INCOME) "已记收入" else "付款已核对"; else -> "已取消"
}

@Composable
private fun ChoiceDialog(title: String, explanation: String, busy: Boolean, onDismiss: () -> Unit, choices: List<Pair<Long, String>>, onChoice: (Long) -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text(explanation); if (choices.isEmpty()) Text("暂无符合金额和时间条件的记录；分次付款、金额不同请手动核对。")
            choices.forEach { (id, text) -> TextButton(enabled = !busy, onClick = { onChoice(id) }) { Text(text) } }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } })
}

@Composable
internal fun OrderEditor(order: DeferredOrder?, busy: Boolean, onDismiss: () -> Unit, onSave: (EditedOrder) -> Unit) {
    var product by remember(order?.id) { mutableStateOf(order?.product.orEmpty()) }
    var amount by remember(order?.id) { mutableStateOf(order?.takeIf { it.amountCents > 0 }?.let { amountText(it.amountCents) }.orEmpty()) }
    var number by remember(order?.id) { mutableStateOf(order?.orderNumber.orEmpty()) }
    var category by remember(order?.id) { mutableStateOf(order?.categoryKey ?: CategoryKey.SHOPPING) }
    var time by remember(order?.id) { mutableLongStateOf(order?.placedAt ?: System.currentTimeMillis()) }
    var mode by remember(order?.id) { mutableStateOf(order?.paymentMode ?: "IMMEDIATE") }
    var platform by remember(order?.id) { mutableStateOf(order?.platform ?: SourceKey.PINDUODUO) }
    var account by remember(order?.id) { mutableStateOf(order?.accountName?.ifBlank { if (mode == "DEFERRED") "拼多多先用后付" else Platforms.name(platform) } ?: Platforms.name(platform)) }
    var direction by remember(order?.id) { mutableStateOf(order?.direction ?: Direction.EXPENSE) }
    var menu by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("核对并确认入账") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("请确认交易或下单已成功。金额、日期、分类和账户均可修改；有疑问请取消，不直接猜测入账。")
            Row {
                FilterChip(direction == Direction.EXPENSE, { direction = Direction.EXPENSE; category = CategoryKey.SHOPPING }, label = { Text("支出") }, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                FilterChip(direction == Direction.INCOME, { direction = Direction.INCOME; category = CategoryKey.RECEIPT; mode = "IMMEDIATE" }, label = { Text("收入 / 退款") }, enabled = !busy)
            }
            Box {
                TextButton(enabled = !busy, onClick = { menu = "platform" }) { Text("来源：${Platforms.name(platform)}") }
                DropdownMenu(menu == "platform", { menu = "" }) {
                    (Platforms.apps.values + listOf(SourceKey.WECHAT, SourceKey.ALIPAY, SourceKey.BANK, Platforms.SCREENSHOT, Platforms.CUSTOM)).distinct().forEach { key ->
                        DropdownMenuItem(text = { Text(Platforms.name(key)) }, onClick = { platform = key; mode = "IMMEDIATE"; account = Platforms.name(key); menu = "" })
                    }
                }
            }
            if (platform == SourceKey.PINDUODUO && direction == Direction.EXPENSE) Row {
                FilterChip(mode == "DEFERRED", { mode = "DEFERRED"; account = "拼多多先用后付" }, label = { Text("先用后付") }, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                FilterChip(mode == "IMMEDIATE", { mode = "IMMEDIATE"; account = "多多支付" }, label = { Text("多多支付") }, enabled = !busy)
            }
            OutlinedTextField(product, { product = it.take(100) }, label = { Text("商品或商户") }, enabled = !busy)
            OutlinedTextField(amount, { amount = it }, label = { Text("最终实付 / 应付总额（元）") }, singleLine = true, enabled = !busy)
            order?.amountOptions?.split(',')?.mapNotNull { it.toLongOrNull() }?.distinct()?.take(12)?.let { choices ->
                if (choices.size > 1) { Text("页面候选金额，仅供核对：")
                    choices.forEach { value -> TextButton(enabled = !busy, onClick = { amount = amountText(value) }) { Text("选用 ¥${amountText(value)}") } }
                }
            }
            OutlinedTextField(account, { account = it.take(40) }, label = { Text("付款 / 收款账户") }, enabled = !busy)
            OutlinedTextField(number, { number = it.take(40) }, label = { Text("订单号（可选）") }, enabled = !busy)
            Box {
                TextButton(enabled = !busy, onClick = { menu = "category" }) { Text("分类：${stringResource(CategoryCatalog.labelRes(category, direction))}") }
                DropdownMenu(menu == "category", { menu = "" }) {
                    CategoryCatalog.forDirection(direction).forEach { item -> DropdownMenuItem(text = { Text(stringResource(item.labelRes)) }, onClick = { category = item.key; menu = "" }) }
                }
            }
            Text("日期：${dateTimeText(time)}")
            TextButton(enabled = !busy, onClick = {
                val c = Calendar.getInstance().apply { timeInMillis = time }
                DatePickerDialog(context, { _, y, m, d -> c.set(y, m, d); time = c.timeInMillis }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text("修改交易日期") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(enabled = !busy, onClick = {
        val cents = parseAmountCents(amount)
        if (product.isBlank() || cents == null || cents !in 1..999_999_999L || !Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(amount.trim())) error = "请填写商品和有效金额，最多两位小数"
        else onSave(EditedOrder(product, cents, category, number.trim(), time, if (platform == SourceKey.PINDUODUO) mode else "IMMEDIATE", platform, account, direction))
    }) { Text(if (busy) "保存中…" else "确认入账") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
