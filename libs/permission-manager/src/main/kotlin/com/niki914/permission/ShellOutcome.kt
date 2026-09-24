package com.niki914.permission

/**
 * 一次性命令执行结果。
 * [exitCode] 为 null 表示无法取得退出码（超时/进程创建失败）。
 * Phase 2 起为 public：ChannelHandler.runSilent（public 接口方法）暴露此类型。
 */
data class ShellOutcome(
    val exitCode: Int?,
    val stdout: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = exitCode == 0
}
