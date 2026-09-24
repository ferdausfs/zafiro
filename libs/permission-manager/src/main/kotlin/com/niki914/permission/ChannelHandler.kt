package com.niki914.permission

interface ChannelHandler {
    val channel: Channel

    /** 版本门槛。实现类标 @RequiresApi，lint NewApi(error) 编译期兜底 */
    val minSdk: MinSdk

    /** 永远静默：不弹窗、不跳页、不写 */
    fun status(permission: Permission): PermissionState

    /**
     * 契约：必须在状态确定后返回，不允许"发射后不管"。
     * - SYSTEM_DIALOG：弹窗回调返回时确定
     * - JUMP_SETTINGS：launch → 等 resume → 复查一次 status()
     * - shell：exit code 校验后确定
     * Activity 销毁致挂起取消 = 本次无结果，下次 status() 兜底。
     */
    suspend fun request(permission: Permission): PermissionState

    /**
     * Phase 2 看门狗：静默 shell 执行（不拉授权、不弹任何 UI）。
     * 仅 shell 通道（ROOT_SHELL/SHIZUKU）在「授权已就绪」时覆写返回真实结果；
     * 默认 null = 本通道不支持或未就绪（调用方应继续降级，绝不触发授权流程 ——
     * 看门狗运行在后台，不能也无权弹 UI）。
     */
    suspend fun runSilent(command: String): ShellOutcome? = null
}
