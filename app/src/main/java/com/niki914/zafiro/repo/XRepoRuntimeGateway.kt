package com.niki914.zafiro.repo

import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata
import com.niki914.zafiro.settings.model.RuntimeTodoItem
import com.niki914.zafiro.settings.model.RuntimeVaultTokenSummary
import com.niki914.zafiro.settings.model.RuntimeToolValidation

class XRepoRuntimeGateway(
    private val repo: XRepo = XRepo,
) : RuntimeSettingsGateway {
    override suspend fun readLlmConfig(agentId: String): RuntimeLlmConfig {
        val doc = repo.llmConfigs.document()
        val active = doc.activeConfig()
        val memories = repo.agents.memoriesFor(agentId)
        return RuntimeLlmConfig(
            provider = active?.provider.orEmpty(),
            endpoint = resolveEndpoint(active?.provider.orEmpty(), active?.endpoint.orEmpty()),
            // vault 引用优先：key 明文只在运行时内存中出现，不落盘
            apiKey = active?.resolveApiKey().orEmpty(),
            model = active?.model.orEmpty(),
            protocol = active?.protocol.orEmpty(),
            supportsImages = active?.supportsImages ?: false,
            proxy = active?.proxy.orEmpty(),
            thinkingLevel = active?.thinkingLevel.orEmpty(),
            prompt = doc.prompt,
            memories = memories,
            idleTimeoutSeconds = repo.llmIdleTimeoutSeconds().takeIf { it > 0L },
            retryMaxAttempts = repo.llmRetryMaxAttempts(),
            extraHeaders = gatewayHeaders(),
        )
    }

    override suspend fun fallbackConfigs(): List<RuntimeLlmConfig> {
        val doc = repo.llmConfigs.document()
        return repo.fallback.resolveConfigs(activeConfigId = doc.activeId).map { saved ->
            RuntimeLlmConfig(
                provider = saved.provider,
                endpoint = resolveEndpoint(saved.provider, saved.endpoint),
                apiKey = saved.resolveApiKey().orEmpty(),
                model = saved.model,
                protocol = saved.protocol,
                supportsImages = saved.supportsImages,
                // prompt 是全局一份的行为层配置；最终提示词沿用 active 组装结果
                prompt = doc.prompt,
                proxy = saved.proxy,
                thinkingLevel = saved.thinkingLevel,
                idleTimeoutSeconds = repo.llmIdleTimeoutSeconds().takeIf { it > 0L },
                retryMaxAttempts = repo.llmRetryMaxAttempts(),
                extraHeaders = gatewayHeaders(),
            )
        }.filter { it.apiKey.isNotBlank() || it.endpoint.isNotBlank() }
    }

    override suspend fun listMcpServers(): List<RuntimeMcpServer> = repo.mcp.list()

    override suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> {
        return repo.skills.listEnabled()
    }

    override suspend fun loadSkill(id: String): RuntimeLoadedSkill? {
        return repo.skills.getDetail(id)
    }

    override suspend fun addMemory(value: String) {
        repo.memory.add(value)
    }

    override suspend fun removeMemory(oldText: String): MemoryMutationResult {
        return repo.memory.removeByText(oldText)
    }

    override suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult {
        return repo.memory.replaceByText(oldText, content)
    }

    override suspend fun listCustomPyTools(): List<RuntimeCustomPyTool> = repo.customPyTools.list()

    override suspend fun saveCustomPyTool(
        tool: RuntimeCustomPyTool,
        overwrite: Boolean,
    ): RuntimeToolValidation? {
        return repo.customPyTools.save(tool, overwrite)
    }

    override suspend fun deleteCustomPyTool(name: String) {
        repo.customPyTools.delete(name)
    }

    override suspend fun setCustomPyToolEnabled(name: String, enabled: Boolean) {
        repo.customPyTools.setEnabled(name, enabled)
    }

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
        return repo.builtinTools.list()
    }

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setEnabled(name, enabled)
    }

    override suspend fun setBuiltinToolGroupEnabled(
        groupId: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setGroupEnabled(groupId, enabled)
    }

    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> {
        return repo.executionRules.list()
    }

    override suspend fun autonomousExecution(): Boolean {
        return repo.executionRules.autonomousExecution()
    }

    override suspend fun listVaultTokens(): List<RuntimeVaultTokenSummary> {
        return TokenVault.list()
    }

    override suspend fun vaultTokenValue(name: String): String? {
        return TokenVault.value(name)
    }

    override suspend fun readTodoItems(): List<RuntimeTodoItem> {
        return repo.todo.list()
    }

    override suspend fun writeTodoItems(items: List<RuntimeTodoItem>) {
        repo.todo.replaceAll(items)
    }

    /**
     * 端点解析：先做 {account_id} 占位符替换（Workers AI 直连），
     * 再做 AI Gateway 改写（网关设置完备且 provider 可映射时），否则原样。
     */
    private suspend fun resolveEndpoint(providerId: String, endpoint: String): String {
        val cloud = repo.cloud.settings()
        val substituted = CloudflareGateway.substituteAccountId(endpoint, cloud.accountId)
        if (!cloud.gatewayReady()) return substituted
        return CloudflareGateway.gatewayEndpointFor(
            providerId = providerId,
            customSlug = cloud.customProviderSlug,
            originalEndpoint = substituted,
            accountId = cloud.accountId,
            gatewayName = cloud.gatewayName,
        ) ?: substituted
    }

    /**
     * Gateway 鉴权头：Gateway Authentication 开启（cf-aig-authorization）时由
     * 宿主注入；token 未填 = 空表（网关鉴权保持关闭也可用）。
     */
    private suspend fun gatewayHeaders(): Map<String, String> {
        val cloud = repo.cloud.settings()
        if (!cloud.gatewayReady()) return emptyMap()
        val token = cloud.cfApiToken.trim()
        if (token.isEmpty()) return emptyMap()
        return mapOf(CloudflareGateway.AIG_AUTH_HEADER to "Bearer $token")
    }
}
