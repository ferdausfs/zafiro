package com.niki914.zafiro.chat.agentic.buildin

import com.niki914.zafiro.chat.agentic.buildin.impl.ExecutePythonBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.FileManagerBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.FindInstalledAppsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LaunchAppBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LiveScreenBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LoadSkillBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.MemoryBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.NotifyBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.OpenUriBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.PyMetaToolsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.PdfToolsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenOperationAccessibilityBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenOperationShellBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.SamsungBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenshotBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.SystemDataBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.TodoWriteBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.TokenVaultBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ViewImageBuiltin

class BuiltinToolRegistry(
    private val tools: List<BuiltinTool>,
) {
    fun all(): List<BuiltinTool> = tools

    fun find(name: String): BuiltinTool? {
        return tools.firstOrNull { it.name == name }
    }

    companion object {
        fun default(): BuiltinToolRegistry = BuiltinToolRegistry(
            listOf(
                ExecutePythonBuiltin(),
                LaunchAppBuiltin(),
                PyMetaToolsBuiltin(),
                MemoryBuiltin(),
                NotifyBuiltin(),
                OpenUriBuiltin(),
                LoadSkillBuiltin(),
                TerminalBuiltin(),
                FindInstalledAppsBuiltin(),
                ScreenOperationAccessibilityBuiltin(),
                ScreenOperationShellBuiltin(),
                ScreenshotBuiltin(),
                ViewImageBuiltin(),
                TokenVaultBuiltin(),
                TodoWriteBuiltin(),
                // v1.7.0 System-Integrated Autonomous Agent
                SystemDataBuiltin(),
                LiveScreenBuiltin(),
                FileManagerBuiltin(),
                PdfToolsBuiltin(),
                // v1.8.0 Samsung-Optimized Agent
                SamsungBuiltin(),
            )
        )
    }
}
