package com.leaf.vixi.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.vixi.App
import com.leaf.vixi.data.MsgKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Clock = DateTimeFormatter.ofPattern("HH:mm")
private val DayMonth = DateTimeFormatter.ofPattern("M月d日")
private val DayFull = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val FullTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenConversations: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var viewer by remember { mutableStateOf<String?>(null) }
    var galleryMsg by remember { mutableStateOf<String?>(null) }

    // 群聊里每条气泡要按 speakerId 选头像/名字；单聊统一用会话里唯一的那个人
    val nameById = remember(state.characters) { state.characters.associate { it.id to it.name } }
    val selfId = state.members.firstOrNull()?.id ?: "c1"
    val nameOf: (String?) -> String = remember(
        state.characters, state.members, state.conversationTitle,
    ) {
        { id ->
            (id?.let { nameById[it] })
                ?: state.members.firstOrNull()?.name
                ?: state.conversationTitle
        }
    }

    // 提示两秒后自动消失
    LaunchedEffect(galleryMsg) {
        if (galleryMsg != null) {
            kotlinx.coroutines.delay(2200)
            galleryMsg = null
        }
    }

    // 分段提示同样自动消失，否则它会一直挡在输入框上面，还看不出能不能点
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(2600)
            vm.dismissNotice()
        }
    }

    // 看图时按系统返回只关浮层，不退出聊天
    BackHandler(enabled = viewer != null) { viewer = null }

    // 一次可以选多张，选完全部挂进待发栏（不会立刻发出去）
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) vm.addAttachments(uris)
    }

    // 「进这个会话先落到最新一条」——每次换会话只做一次。
    // 上一版只判断"现在在不在底部"，于是**刚从别的会话进来时永远不算在底部**，
    // 停在历史中间不动（用户 2026-11-25 报的"自动跳最新失效"就是这个）。
    var jumpedConv by remember { mutableStateOf("") }
    LaunchedEffect(state.conversationId, state.bubbles.size) {
        val count = state.bubbles.size + if (state.typing && !state.isGroup) 1 else 0
        if (count <= 0) return@LaunchedEffect
        if (jumpedConv != state.conversationId) {
            jumpedConv = state.conversationId
            listState.scrollToItem(count - 1)
        }
    }

    LaunchedEffect(state.bubbles.size, state.typing, state.isGroup) {
        // 群聊没有 typing 气泡，别把滚动位置算到一条不存在的 item 上
        val count = state.bubbles.size + if (state.typing && !state.isGroup) 1 else 0
        if (count <= 0) return@LaunchedEffect
        val info = listState.layoutInfo
        if (info.totalItemsCount == 0) {
            // 首次装载：直接落底（animate 会从第 0 条一路滑下来，很难看）
            listState.scrollToItem(count - 1)
        } else {
            // 只有"本来就在底部"才跟着滚：往上翻历史时被新消息拽回底部是很烦的事
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= info.totalItemsCount - 3) listState.animateScrollToItem(count - 1)
        }
    }

    // 键盘弹起时把最新消息顶上来（否则键盘挡住下半屏，看到的还是旧位置）
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom > 0) {
        if (imeBottom > 0 && state.bubbles.isNotEmpty()) {
            listState.animateScrollToItem(state.bubbles.size - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(VixiPalette.ink)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
        ) {
            ChatHeader(
                title = state.conversationTitle,
                subtitle = when {
                    state.typing -> "正在输入…"
                    state.isGroup -> state.members.joinToString("、") { it.name }
                    else -> "\u00A0"
                },
                isGroup = state.isGroup,
                memberCount = state.members.size,
                onOpenConversations = onOpenConversations,
                onOpenSettings = onOpenSettings,
                onOpenDebug = onOpenDebug,
                onEndTopic = vm::endTopic,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(state.bubbles, key = { _, b -> b.id }) { index, bubble ->
                    val prev = state.bubbles.getOrNull(index - 1)
                    val showTime = prev == null || bubble.ts - prev.ts > 10 * 60_000
                    if (showTime) TimeDivider(bubble.ts)

                    val onCopy = { clipboard.setText(AnnotatedString(bubble.text)) }
                    val onQuote = { vm.startQuote(bubble) }
                    val onDelete = { vm.deleteMessage(bubble) }

                    when {
                        bubble.kind == MsgKind.ACTION -> ActionRow(bubble)
                        bubble.self -> MyRow(
                            bubble = bubble,
                            prev = prev,
                            onCopy = onCopy,
                            onQuote = onQuote,
                            onDelete = onDelete,
                            onOpenImage = { viewer = it },
                            onRetry = { vm.retryOne(bubble.id) },
                        )
                        else -> HerRow(
                            bubble = bubble,
                            prev = prev,
                            isGroup = state.isGroup,
                            selfId = selfId,
                            nameOf = nameOf,
                            onPat = vm::patHead,
                            onCopy = onCopy,
                            onQuote = onQuote,
                            onDelete = onDelete,
                            onOpenImage = { viewer = it },
                        )
                    }
                }
                // 群聊不显示"正在输入"气泡：不知道是谁在打字，摆一个空泡反而迷惑
                // （等待反馈靠标题下面那行「正在输入…」）
                if (state.typing && !state.isGroup) {
                    item(key = "typing") {
                        HerRow(null, null, state.isGroup, selfId, nameOf, null, {}, {}, {}, {})
                    }
                }
            }

            state.error?.let { ErrorBar(it, vm::dismissError) }
            if (state.sandbox) {
                Text(
                    text = "测试环境 · 不记忆 / 不检索 / 独立记录",
                    color = VixiPalette.danger,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                )
            }
            state.notice?.let { msg ->
                TextAction(
                    label = msg,
                    color = VixiPalette.accent,
                    fontSize = 11.sp,
                    horizontalPadding = 16,
                    verticalPadding = 4,
                    onClick = vm::dismissNotice,
                )
            }
            galleryMsg?.let {
                Text(
                    text = it,
                    color = VixiPalette.accent,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            InputBar(
                value = state.input,
                onValueChange = vm::onInputChange,
                onFocus = vm::onInputFocus,
                onSend = vm::send,
                onPickImage = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                searchNext = state.searchNext,
                onToggleSearch = vm::toggleSearchNext,
                quoting = state.quoting,
                onCancelQuote = vm::cancelQuote,
                pendingImages = state.pendingImages,
                onRemoveAttachment = vm::removeAttachment,
            )
        }

        // 大图浮层：点任意处关闭，底部可存相册
        viewer?.let { name ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VixiPalette.ink)
                    .clickableNoIndication { viewer = null },
                contentAlignment = Alignment.Center,
            ) {
                FullImage(name = name)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 40.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextAction(
                        label = "保存到相册",
                        color = VixiPalette.accent,
                        fontSize = 13.sp,
                        horizontalPadding = 14,
                        verticalPadding = 10,
                        onClick = {
                            // 文件拷贝 + MediaStore 插入放到 IO，别在主线程做（大图会卡顿甚至 ANR）
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    App.instance.images.saveToGallery(name)
                                }
                                galleryMsg = if (ok) "已保存到相册" else "保存失败，可能没有相册权限"
                                viewer = null
                            }
                        },
                    )
                }
                Text(
                    text = "点任意处关闭",
                    color = VixiPalette.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatHeader(
    title: String,
    subtitle: String,
    isGroup: Boolean,
    memberCount: Int,
    onOpenConversations: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
    onEndTopic: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 标题就是会话入口：点一下弹会话列表（单聊/群聊 + 新建）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickableNoIndication { onOpenConversations() }
                    .padding(vertical = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = VixiPalette.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isGroup) {
                        Text(
                            text = " · $memberCount 人 ›",
                            color = VixiPalette.muted,
                            fontSize = 12.sp,
                        )
                    } else {
                        Text(text = " ›", color = VixiPalette.muted, fontSize = 12.sp)
                    }
                }
                Text(
                    text = subtitle,
                    color = VixiPalette.accent,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 手动分段：规则（30 分钟 / 收尾话）总有猜不准的时候，给一个明确开关
            TextAction(
                label = "分段",
                color = VixiPalette.muted,
                fontSize = 12.sp,
                horizontalPadding = 8,
                onClick = onEndTopic,
            )
            TextAction(label = "调试", onClick = onOpenDebug)
            TextAction(label = "设置", onClick = onOpenSettings)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))
    }
}

@Composable
private fun TimeDivider(ts: Long) {
    val text = remember(ts) {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(ts).atZone(zone)
        val today = java.time.LocalDate.now(zone)
        when {
            at.toLocalDate() == today -> Clock.format(at)
            at.toLocalDate() == today.minusDays(1) -> "昨天 " + Clock.format(at)
            at.year == today.year -> DayMonth.format(at) + " " + Clock.format(at)
            else -> DayFull.format(at) + " " + Clock.format(at)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, color = VixiPalette.muted.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

@Composable
private fun HerRow(
    bubble: Bubble?,
    prev: Bubble?,
    isGroup: Boolean,
    selfId: String,
    nameOf: (String?) -> String,
    onPat: (() -> Unit)?,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    // 群聊里"连着说"的判断要带上说话人：两个人紧挨着说话，头像和名字都不能省
    val sameAsPrev = bubble != null && prev != null && !prev.self &&
        bubble.speakerId == prev.speakerId &&
        bubble.ts - prev.ts < 10 * 60_000

    // 没有 speakerId 的旧记录归默认角色（迁移过来的单聊记录都属于她）
    val speakerId = bubble?.speakerId ?: selfId
    val speakerName = nameOf(speakerId)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 群聊：换人说话时标一行名字，否则三条气泡分不出谁是谁
        if (isGroup && bubble != null && !sameAsPrev) {
            Text(
                text = speakerName,
                color = VixiPalette.muted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(start = 44.dp, bottom = 2.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (sameAsPrev) {
                Spacer(Modifier.width(36.dp))
            } else {
                Avatar(
                    which = speakerId,
                    size = 36.dp,
                    fallback = speakerName.take(1),
                    onDoubleTap = onPat,
                )
            }
            Spacer(Modifier.width(8.dp))

            if (bubble == null) {
                TypingBubble()
            } else {
                PressableBubble(bubble, onCopy, onQuote, onDelete) { requestMenu ->
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (sameAsPrev) 18.dp else 6.dp,
                            bottomEnd = 18.dp,
                        ),
                        color = if (bubble.kind == MsgKind.IMAGE && bubble.text.isBlank()) {
                            Color.Transparent
                        } else {
                            VixiPalette.her
                        },
                        modifier = Modifier.widthIn(max = 262.dp),
                    ) {
                        BubbleContent(bubble, Alignment.Start, onOpenImage, requestMenu)
                    }
                }
            }
        }
    }
}

@Composable
private fun MyRow(
    bubble: Bubble,
    prev: Bubble?,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val sameAsPrev = prev != null && prev.self && bubble.ts - prev.ts < 10 * 60_000

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            PressableBubble(bubble, onCopy, onQuote, onDelete) { requestMenu ->
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = if (sameAsPrev) 18.dp else 6.dp,
                    ),
                    color = if (bubble.kind == MsgKind.IMAGE && bubble.text.isBlank()) {
                        Color.Transparent
                    } else {
                        VixiPalette.mine
                    },
                    modifier = Modifier.widthIn(max = 262.dp),
                ) {
                    BubbleContent(bubble, Alignment.End, onOpenImage, requestMenu)
                }
            }
        }

        // 只显示失败态：文字消息不该挂"发送中"（会和"对方正在输入"打架，看着像没发出去）
        if (bubble.status == SendStatus.FAILED) {
            TextAction(
                label = "发送失败 · 点这里重发",
                color = VixiPalette.danger,
                fontSize = 10.sp,
                horizontalPadding = 4,
                verticalPadding = 2,
                onClick = onRetry,
            )
        }
        // 她这轮选择不回：**常驻**标在气泡下面（notice 2.6 秒就没了，
        // 事后分不清"她没回"和"回复丢了"）。点一下可以让她重说一次。
        if (bubble.status == SendStatus.NO_REPLY) {
            TextAction(
                label = "她这轮没回 · 点这里再说一次",
                color = VixiPalette.muted,
                fontSize = 10.sp,
                horizontalPadding = 4,
                verticalPadding = 2,
                onClick = onRetry,
            )
        }
    }
}

/** 长按出菜单：复制 / 引用 / 删除；顶部显示精确时间 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PressableBubble(
    bubble: Bubble,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable (onLongPress: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Box {
        Box(
            modifier = Modifier.combinedClickable(
                indication = null,
                interactionSource = interaction,
                onClick = {},
                onLongClick = { menu = true },
            )
        ) {
            content { menu = true }
        }

        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            modifier = Modifier.background(VixiPalette.panel),
        ) {
            Text(
                text = FullTime.format(
                    Instant.ofEpochMilli(bubble.ts).atZone(ZoneId.systemDefault())
                ),
                color = VixiPalette.muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            if (bubble.text.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("复制", fontSize = 14.sp, color = VixiPalette.text) },
                    onClick = { menu = false; onCopy() },
                )
                DropdownMenuItem(
                    text = { Text("引用", fontSize = 14.sp, color = VixiPalette.text) },
                    onClick = { menu = false; onQuote() },
                )
            }
            DropdownMenuItem(
                text = { Text("删除", fontSize = 14.sp, color = VixiPalette.danger) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun BubbleContent(
    bubble: Bubble,
    align: Alignment.Horizontal,
    onOpenImage: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(3.dp),
        horizontalAlignment = align,
    ) {
        bubble.quote?.let { q ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 3.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "「$q」",
                    color = VixiPalette.muted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        bubble.images.forEach { name ->
            ImageThumb(
                name = name,
                width = if (bubble.images.size > 1) 140.dp else 180.dp,
                onTap = { onOpenImage(name) },
                onLongPress = onLongPress,
            )
        }
        if (bubble.text.isNotBlank()) {
            Text(
                text = bubble.text,
                color = VixiPalette.text,
                fontSize = 15.5.sp,
                lineHeight = 23.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** 图片缩略图：解码在工作线程；点开看大图，长按出菜单 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumb(
    name: String,
    width: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val store = App.instance.images
    val file = remember(name) { store.imageFile(name) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = name,
    ) {
        value = withContext(Dispatchers.IO) { store.decode(file, 1024)?.asImageBitmap() }
    }
    val interaction = remember { MutableInteractionSource() }

    val bmp = bitmap
    if (bmp == null) {
        Box(
            modifier = Modifier
                .width(width)
                .height(width * 0.72f)
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VixiPalette.hairline),
            contentAlignment = Alignment.Center,
        ) {
            Text("图片", color = VixiPalette.muted, fontSize = 11.sp)
        }
    } else {
        val ratio = bmp.width.toFloat() / bmp.height.toFloat()
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(width)
                .padding(vertical = 2.dp)
                .aspectRatio(ratio.coerceIn(0.6f, 1.8f))
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    indication = null,
                    interactionSource = interaction,
                    onClick = onTap,
                    onLongClick = onLongPress,
                ),
        )
    }
}

/** 全屏看图 */
@Composable
private fun FullImage(name: String) {
    val store = App.instance.images
    val file = remember(name) { store.imageFile(name) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = "full-$name",
    ) {
        value = withContext(Dispatchers.IO) { store.decode(file, 1600)?.asImageBitmap() }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    } else {
        Text("加载中…", color = VixiPalette.muted, fontSize = 12.sp)
    }
}

@Composable
private fun ActionRow(bubble: Bubble) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = com.leaf.vixi.ActionText.forUi(bubble.text),
            color = VixiPalette.muted.copy(alpha = 0.85f),
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun TypingBubble() {
    val transition = rememberInfiniteTransition(label = "typing")
    Surface(
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp),
        color = VixiPalette.her,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val a by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 520, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = androidx.compose.animation.core.StartOffset(index * 170),
                    ),
                    label = "dot$index",
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .alpha(a)
                        .clip(CircleShape)
                        .background(VixiPalette.muted)
                )
            }
        }
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(VixiPalette.her)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = VixiPalette.danger,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextAction(label = "知道了", onClick = onDismiss)
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    /** 输入框获得焦点（键盘被叫起来）：也算"还在操作"，用来推后合并窗口 */
    onFocus: () -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
    /** 下一轮让她联网（单轮开关，发完自动关） */
    searchNext: Boolean = false,
    onToggleSearch: () -> Unit = {},
    quoting: Bubble?,
    onCancelQuote: () -> Unit,
    pendingImages: List<String>,
    onRemoveAttachment: (String) -> Unit,
) {
    val canSend = value.isNotBlank() || pendingImages.isNotEmpty()
    var wasFocused by remember { mutableStateOf(false) }

    Column {
        quoting?.let { QuoteStrip(it.text, onCancelQuote) }
        if (pendingImages.isNotEmpty()) {
            PendingAttachments(pendingImages, onRemoveAttachment)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (searchNext) VixiPalette.accent else VixiPalette.her)
                    .clickableNoIndication { onToggleSearch() },
                contentAlignment = Alignment.Center,
            ) {
                // 联网：亮起来 = 下一轮她会真的去查
                Text(
                    text = "🔍",
                    fontSize = 16.sp,
                    color = if (searchNext) VixiPalette.ink else VixiPalette.muted,
                )
            }
            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(VixiPalette.her)
                    .clickableNoIndication { onPickImage() },
                contentAlignment = Alignment.Center,
            ) {
                Text("＋", color = VixiPalette.muted, fontSize = 19.sp)
            }
            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(VixiPalette.her)
                    // ⚠️ padding **不能放在这层 Box 上**：那 11dp 上下留白就不属于输入框了，
                    // 手指点偏一点（胶囊 42dp 只有中间一行能点）就没反应 —— 全 App 最高频的控件。
                    // 留白挪进占位符和 BasicTextField 自己身上。
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = if (pendingImages.isEmpty()) "发消息…" else "就着这些图说点什么（可不填）",
                        color = VixiPalette.muted,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(color = VixiPalette.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(VixiPalette.accent),
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                        // 只在"从没焦点变成有焦点"时通知一次：点开输入框=还要继续说
                        .onFocusChanged { st ->
                            if (st.isFocused && !wasFocused) onFocus()
                            wasFocused = st.isFocused
                        },
                )
            }
            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (canSend) VixiPalette.accent else VixiPalette.her)
                    .clickableNoIndication(canSend) { onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "↑",
                    color = if (canSend) VixiPalette.ink else VixiPalette.muted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 待发送的图片：先挂着，可以继续加、也可以就着它们说话，最后一次发出去（一次调用） */
@Composable
private fun PendingAttachments(names: List<String>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEach { name ->
            Box {
                ImageThumb(name = name, width = 56.dp, onTap = {}, onLongPress = {})
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(VixiPalette.ink.copy(alpha = 0.8f))
                        .clickableNoIndication { onRemove(name) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", color = VixiPalette.text, fontSize = 11.sp)
                }
            }
        }
        Text(
            text = if (names.size > 1) "${names.size} 张 · 一起发" else "待发送",
            color = VixiPalette.muted,
            fontSize = 10.sp,
        )
    }
}

/** 引用条：告诉你正在引用哪句，右边 × 取消 */
@Composable
private fun QuoteStrip(text: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(VixiPalette.her)
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(VixiPalette.accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = VixiPalette.muted,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextAction(label = "×", horizontalPadding = 8, onClick = onCancel)
    }
}
