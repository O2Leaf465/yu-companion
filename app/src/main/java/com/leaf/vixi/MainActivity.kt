package com.leaf.vixi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leaf.vixi.ui.AvatarScreen
import com.leaf.vixi.ui.ChatScreen
import com.leaf.vixi.ui.ChatViewModel
import com.leaf.vixi.ui.ConversationsScreen
import com.leaf.vixi.ui.DebugScreen
import com.leaf.vixi.ui.NewCharacterScreen
import com.leaf.vixi.ui.NewGroupScreen
import com.leaf.vixi.ui.PersonaScreen
import com.leaf.vixi.ui.PromptLogScreen
import com.leaf.vixi.ui.RecordScreen
import com.leaf.vixi.ui.RulesScreen
import com.leaf.vixi.ui.SettingsScreen
import com.leaf.vixi.ui.StickerScreen
import com.leaf.vixi.ui.SystemPromptScreen
import com.leaf.vixi.ui.VixiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 在 Android 15 上强制全面屏，必须显式接管 insets，
        // 否则输入框会画到导航栏底下（就是"被导航键挡住"那个 bug）
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        askNotificationPermission()

        setContent {
            VixiTheme {
                // 真的返回栈：人设 → 设置 → 聊天，而不是"每次都跳回聊天页"
                var stack by remember { mutableStateOf(listOf(Screen.Chat)) }
                val screen = stack.last()
                val vm: ChatViewModel = viewModel()

                fun go(to: Screen) { stack = stack + to }
                fun back() {
                    if (stack.size > 1) stack = stack.dropLast(1)
                }

                BackHandler(enabled = stack.size > 1) { back() }

                // 回到前台时对一次盘：她**主动开口**时是后台 Worker 往同一份
                // chat_<会话>.jsonl 追加的，而聊天页只在 init/切会话时读盘 ——
                // 不重读的话，那条消息在界面上根本不存在（但导出/归档里有）。
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            vm.reloadIfChanged()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }

                when (screen) {
                    Screen.Chat -> ChatScreen(
                        vm = vm,
                        onOpenSettings = { go(Screen.Settings) },
                        onOpenDebug = { go(Screen.Debug) },
                        onOpenConversations = {
                            vm.refreshLists()
                            go(Screen.Conversations)
                        },
                    )

                    Screen.Conversations -> ConversationsScreen(
                        vm = vm,
                        onBack = { back() },
                        onNewCharacter = { go(Screen.NewCharacter) },
                        onNewGroup = { go(Screen.NewGroup) },
                    )
                    // 建完直接回聊天页：createCharacter / createGroup 已经把会话切过去了
                    Screen.NewCharacter -> NewCharacterScreen(
                        vm = vm,
                        onBack = { back() },
                        onDone = { stack = listOf(Screen.Chat) },
                    )
                    Screen.NewGroup -> NewGroupScreen(
                        vm = vm,
                        onBack = { back() },
                        onDone = { stack = listOf(Screen.Chat) },
                    )

                    Screen.Settings -> SettingsScreen(
                        vm = vm,
                        onBack = { back() },
                        onOpenPersona = { go(Screen.Persona) },
                        onOpenSystemPrompt = { go(Screen.SystemPrompt) },
                        onOpenAvatar = { go(Screen.Avatar) },
                        onOpenRules = { go(Screen.Rules) },
                        onOpenRecords = { go(Screen.Records) },
                        onOpenStickers = { go(Screen.Stickers) },
                    )
                    Screen.Records -> RecordScreen(vm = vm, onBack = { back() })
                    Screen.Stickers -> StickerScreen(onBack = { back() })
                    Screen.Debug -> DebugScreen(
                        vm = vm,
                        onBack = { back() },
                        onOpenPrompts = { go(Screen.Prompts) },
                    )
                    Screen.Prompts -> PromptLogScreen(vm = vm, onBack = { back() })
                    Screen.Persona -> PersonaScreen(
                        vm = vm,
                        onBack = { back() },
                        onOpenSystemPrompt = { go(Screen.SystemPrompt) },
                    )
                    Screen.SystemPrompt -> SystemPromptScreen(
                        vm = vm,
                        onBack = { back() },
                        onEditPersona = { go(Screen.Persona) },
                        onEditRules = { go(Screen.Rules) },
                    )
                    Screen.Rules -> RulesScreen(onBack = { back() })
                    Screen.Avatar -> AvatarScreen(vm = vm, onBack = { back() })
                }
            }
        }
    }

    private enum class Screen {
        Chat, Settings, Debug, Prompts, Persona, SystemPrompt, Rules, Avatar, Records,
        Conversations, NewCharacter, NewGroup, Stickers,
    }

    override fun onResume() {
        super.onResume()
        App.instance.isForeground = true
        // 回到聊天页就把通知收掉
        Notifier.clear(this)
    }

    override fun onPause() {
        App.instance.isForeground = false
        super.onPause()
    }

    /** Android 13+ 发通知要运行时授权；不给也不影响聊天，只是没提醒 */
    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
    }
}
