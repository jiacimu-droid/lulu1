package com.jiacimu.lulu

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI
import java.net.URLEncoder

/**
 * Public, per-build community destinations. Never store recipient credentials
 * in the APK. Add only the owner's public contact and HTTPS payment URL before
 * release; do not use an end user's own settings as the community recipient.
 */
internal object LuluCommunitySupportConfig {
    const val WISHES_URL = "https://github.com/jiacimu-droid/lulu1/issues"
    const val FEEDBACK_EMAIL = "1187725424@qq.com" // Public feedback mailbox designated by the maintainer.
    const val DONATION_URL = LuluAlipayDonationQrData.expectedPayload // Owner-provided personal Alipay receiving QR.

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun wishesListUrl(): String =
        "$WISHES_URL?q=${encode("is:issue in:title 许愿")}"

    fun wishTitle(title: String): String = "[许愿] " + title.trim().take(72)

    fun wishBody(kind: String, detail: String): String =
        "### 类别\n" + kind.trim().take(24) +
            "\n\n### 愿望\n" + detail.trim().take(1_800) +
            "\n\n---\n此内容由提交者主动填写。请勿包含 API Key、密码、聊天记录或个人联系方式。"

    fun issueComposerUrl(title: String, kind: String, detail: String): String =
        "$WISHES_URL/new?title=${encode(wishTitle(title))}&body=${encode(wishBody(kind, detail))}"

    fun safeDonationUrl(): String? = DONATION_URL.trim().takeIf { url ->
        try {
            val parsed = URI(url)
            parsed.scheme.equals("https", ignoreCase = true) &&
                !parsed.host.isNullOrBlank() && parsed.userInfo == null
        } catch (_: Exception) { false }
    }

    fun emailAddress(): String? = FEEDBACK_EMAIL.trim()
        .takeIf { it.matches(Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")) }
}

private val wishKinds = listOf("功能许愿", "体验优化", "界面美化", "其他想法")

/** Local draft only: sending requires the user to press GitHub's submit button. */
private class WishDraft(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lulu_community_wish_draft", Context.MODE_PRIVATE)
    val initialTitle: String get() = prefs.getString("title", "").orEmpty()
    val initialDetail: String get() = prefs.getString("detail", "").orEmpty()
    val initialKind: String get() = prefs.getString("kind", wishKinds[0]).orEmpty()
    fun save(title: String, detail: String, kind: String) {
        prefs.edit().putString("title", title).putString("detail", detail).putString("kind", kind).apply()
    }
    fun clear() = prefs.edit().clear().apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp),
            content = content,
        )
    }
}

@Composable
private fun CommunityEyebrow(label: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
            letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
        Text(title, fontSize = 29.sp, lineHeight = 37.sp,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, fontSize = 13.sp, lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommunityNote(message: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f),
    ) {
        Text(
            message, modifier = Modifier.fillMaxWidth().padding(14.dp),
            fontSize = 12.sp, lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LuluWishCommunityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val draft = remember(context) { WishDraft(context) }
    var title by rememberSaveable { mutableStateOf(draft.initialTitle) }
    var detail by rememberSaveable { mutableStateOf(draft.initialDetail) }
    var kind by rememberSaveable { mutableStateOf(draft.initialKind.takeIf(wishKinds::contains) ?: wishKinds[0]) }
    var openError by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val valid = title.isNotBlank() && detail.isNotBlank()

    fun saveDraft() { draft.save(title, detail, kind) }
    fun issueUrl() = LuluCommunitySupportConfig.issueComposerUrl(title, kind, detail)

    CommunityScaffold("许愿", onBack) {
        CommunityEyebrow(
            "LULU · WISHES",
            "一起把露露机变得更好",
            "你希望增加什么功能？或者哪里还可以更顺手？",
        )
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(72); saveDraft(); openError = false },
            label = { Text("愿望标题") },
            placeholder = { Text("例如：希望增加聊天搜索") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("想法类型", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                wishKinds.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option; saveDraft() },
                        label = { Text(option, fontSize = 12.sp) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = detail,
            onValueChange = { detail = it.take(1_800); saveDraft(); openError = false },
            label = { Text("详细说说") },
            placeholder = { Text("希望它怎么工作？你会在什么场景使用？") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 155.dp),
            minLines = 5, maxLines = 10,
            shape = RoundedCornerShape(15.dp),
            supportingText = { Text("${detail.length} / 1800") },
        )
        CommunityNote("只会提交你主动填写的内容。愿望会发布到公开的 GitHub Issues，请勿填写密钥、联系方式或私人聊天内容。")
        Button(
            onClick = {
                saveDraft()
                openError = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl())))
                }.isFailure
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            shape = RoundedCornerShape(15.dp),
        ) {
            Text("前往提交愿望")
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp))
        }
        Text("会打开 GitHub 页面，登录并点击「Submit new issue」后，作者才会收到愿望。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, lineHeight = 19.sp)
        if (openError) Text("无法打开浏览器，可复制愿望文本后手动提交。",
            color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(LuluCommunitySupportConfig.wishTitle(title) +
                        "\n\n" + LuluCommunitySupportConfig.wishBody(kind, detail)))
                    copied = true
                },
                enabled = valid,
            ) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "已复制" else "复制愿望")
            }
            TextButton(onClick = { title = ""; detail = ""; kind = wishKinds[0]; draft.clear() }) {
                Text("清空草稿")
            }
        }
        val email = LuluCommunitySupportConfig.emailAddress()
        if (email != null) {
            TextButton(
                enabled = valid,
                onClick = {
                    saveDraft()
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, LuluCommunitySupportConfig.wishTitle(title))
                        putExtra(Intent.EXTRA_TEXT, LuluCommunitySupportConfig.wishBody(kind, detail))
                    }
                    openError = runCatching { context.startActivity(intent) }.isFailure
                },
            ) { Text("改用邮件发送") }
        }
        OutlinedButton(
            onClick = {
                openError = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse(LuluCommunitySupportConfig.wishesListUrl())))
                }.isFailure
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.OpenInNew, null, Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("查看社区愿望")
        }
    }
}

@Composable
fun LuluSupportCreatorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var openError by remember { mutableStateOf(false) }
    val paymentUrl = LuluCommunitySupportConfig.safeDonationUrl()
    CommunityScaffold("打赏", onBack) {
        CommunityEyebrow(
            "LULU · SUPPORT",
            "为喜欢的作品\n送上一点心意",
            "露露机可以免费使用。喜欢的话，也可以自愿支持作者继续完善。",
        )
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.FavoriteBorder, null,
                    tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(38.dp))
                Text("感谢你的喜欢", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("赞赏完全自愿，不影响角色、模型与任何功能的使用。",
                    fontSize = 12.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (paymentUrl != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = androidx.compose.ui.graphics.Color.White,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Text("支付宝", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = androidx.compose.ui.graphics.Color(0xFF1677FF))
                    LuluAlipayDonationQr(modifier = Modifier.fillMaxWidth())
                    Text("打开支付宝扫一扫 · 自愿赞赏",
                        fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFF5F6167))
                }
            }
            Button(
                onClick = {
                    openError = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl)))
                    }.isFailure
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Outlined.CardGiftcard, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("打开支付宝付款页")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
            CommunityNote("请先在支付宝核对收款方与金额，再自行决定是否付款。此收款码来自作者本人，露露机不会读取支付信息或自动扣款。")
        } else {
            CommunityNote("作者尚未设置收款方式，赞赏入口暂不开放。")
        }
        if (openError) Text("暂时无法打开赞赏链接，请稍后重试。",
            color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}
