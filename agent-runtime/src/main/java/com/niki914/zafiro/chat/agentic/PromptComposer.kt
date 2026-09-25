package com.niki914.zafiro.chat.agentic

import com.niki914.zafiro.chat.ResolvedTools
import com.niki914.zafiro.chat.agentic.samsung.SamsungDevice
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata

data class PromptComposeResult(
    val finalSystemPrompt: String,
)

data class PromptComposerInput(
    val additionalInstructions: String,
    val memoryItems: List<String> = emptyList(),
    val tools: ResolvedTools = ResolvedTools(),
    val enabledSkills: List<RuntimeSkillMetadata> = emptyList(),
    val sandboxPaths: Set<String> = emptySet(),
)

class PromptComposer {

    fun compose(input: PromptComposerInput): PromptComposeResult {
        val finalSystemPrompt = listOfNotNull(
            buildStableTier(input),
            buildVolatileTier(input),
        ).joinToString(separator = "\n\n")

        return PromptComposeResult(finalSystemPrompt = finalSystemPrompt)
    }

    // --- Stable tier: identity, tools, skills, guidance (cacheable across turns) ---

    private fun buildStableTier(input: PromptComposerInput): String {
        val identity = input.additionalInstructions.trim().ifBlank { DEFAULT_AGENT_IDENTITY }
        val envBlock = renderEnvironmentBlock(input.sandboxPaths)
        return listOfNotNull(
            identity,
            envBlock,
            renderToolContext(input.tools),
            renderSkillContext(input.enabledSkills)
                .takeIf { hasBuiltinTool(input, "load_skill") },
            TASK_COMPLETION_GUIDANCE.takeIf { hasAnyTool(input) },
            TOOL_USE_ENFORCEMENT_GUIDANCE.takeIf { hasAnyTool(input) },
            EXECUTION_RULES_GUIDANCE.takeIf { hasAnyTool(input) },
            AGENT_WORKFLOW_GUIDANCE.takeIf { hasAnyTool(input) },
            MEMORY_GUIDANCE.takeIf { hasBuiltinTool(input, "memory") },
            SKILLS_GUIDANCE.takeIf { hasBuiltinTool(input, "load_skill") },
            PLAN_GUIDANCE.takeIf { hasBuiltinTool(input, "todo_write") },
            VAULT_GUIDANCE.takeIf { hasBuiltinTool(input, "vault_token") },
            SYSTEM_INTEGRATION_DIRECTIVE.takeIf {
                hasBuiltinTool(input, "system_data") ||
                        hasBuiltinTool(input, "live_screen") ||
                        hasBuiltinTool(input, "file_manager") ||
                        hasBuiltinTool(input, "pdf_tools")
            },
            CAPABILITY_DIRECTIVE.takeIf { hasBuiltinTool(input, "device_capabilities") },
            SAMSUNG_ONEUI_DIRECTIVE.takeIf {
                SamsungDevice.isSamsungManufacturer && hasBuiltinTool(input, "samsung")
            },
        ).joinToString(separator = "\n\n")
    }

    // --- Volatile tier: memory snapshot (per-session) ---

    private fun buildVolatileTier(input: PromptComposerInput): String? {
        val items = input.memoryItems.map(String::trim).filter(String::isNotBlank)
        if (items.isEmpty()) return null
        val separator = "═".repeat(46)
        val content = items.joinToString("\n§\n")
        return "$separator\nMEMORY\n$separator\n$content"
    }

    // --- Tool context ---

    private fun renderToolContext(
        tools: ResolvedTools,
    ): String? {
        val blocks = listOfNotNull(
            renderNameBlock("builtin_tools", tools.builtinTools.map { it.name }),
            renderNameBlock("custom_py_tools", tools.customPyTools.map { it.name }),
        )
        if (blocks.isEmpty()) return null
        return "## Tool Context\n\n${blocks.joinToString(separator = "\n\n")}"
    }

    private fun renderNameBlock(tag: String, names: List<String>): String? {
        val normalized = names.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        if (normalized.isEmpty()) return null
        return normalized.joinToString(
            separator = "\n",
            prefix = "<$tag>\n",
            postfix = "\n</$tag>",
        ) { "- $it" }
    }

    // --- Skill context ---

    private fun renderSkillContext(skills: List<RuntimeSkillMetadata>): String? {
        val entries = skills
            .mapNotNull { skill ->
                val id = skill.id.trim()
                if (id.isBlank()) return@mapNotNull null
                val name = skill.name.trim().ifBlank { id }
                val desc = skill.description.trim()
                val dir = skill.absoluteDir.trim()
                buildString {
                    appendLine("  <skill>")
                    appendLine("    <id>$id</id>")
                    appendLine("    <name>$name</name>")
                    if (desc.isNotBlank()) appendLine("    <description>$desc</description>")
                    if (dir.isNotBlank()) appendLine("    <dir>$dir</dir>")
                    append("  </skill>")
                }
            }
            .sorted()
        if (entries.isEmpty()) return null

        return buildString {
            appendLine("## Skills (mandatory)")
            appendLine()
            appendLine(
                "Before replying, scan the skills below. If a skill matches or is even partially " +
                        "relevant to your task, you MUST load it with load_skill and follow its " +
                        "instructions. Err on the side of loading — it is always better to have " +
                        "context you don't need than to miss critical steps, pitfalls, or established " +
                        "workflows. Skills contain specialized knowledge and proven approaches that " +
                        "outperform general-purpose methods."
            )
            appendLine()
            appendLine("<available_skills>")
            entries.forEach { appendLine(it) }
            append("</available_skills>")
        }
    }

    // --- Environment block ---

    /** 私有存储路径提示块：告诉 Agent 这些目录可自由读写，零权限。 */
    private fun renderEnvironmentBlock(paths: Set<String>): String? {
        if (paths.isEmpty()) return null
        val pathList = paths.sorted().joinToString("\n") { "- $it" }
        return buildString {
            appendLine("# Environment")
            appendLine()
            appendLine("Private app storage (read/write freely, no storage permission required):")
            appendLine(pathList)
            appendLine()
            appendLine("Files outside these directories are shared storage and may require")
            appendLine("storage permission that is not currently granted — prefer private")
            appendLine("directories. If a task requires public files, tell the user.")
        }
    }

    // --- Helpers ---

    private fun hasBuiltinTool(input: PromptComposerInput, name: String): Boolean {
        return input.tools.builtinTools.any { it.name == name }
    }

    private fun hasAnyTool(input: PromptComposerInput): Boolean {
        return input.tools.builtinTools.isNotEmpty() ||
                input.tools.customPyTools.isNotEmpty() ||
                input.tools.mcpServers.any { it.enabled }
    }

    // --- Guidance constants ---

    companion object {
        internal const val DEFAULT_AGENT_IDENTITY =
            "You are Zafiro, a System-Integrated Autonomous Agent running directly on the " +
                    "user's Android device — not just a UI automation tool, but the digital " +
                    "brain of the device. You do not just answer questions: you plan, " +
                    "execute with real tools (terminal, Python, files, screen control, " +
                    "system APIs, apps), verify results with actual output, and only then " +
                    "report. When a task is clear, carry it out end-to-end without asking " +
                    "for permission at every step; when ambiguous, make reasonable " +
                    "assumptions, state them briefly, and proceed. You never fabricate " +
                    "tool output, and you communicate like a competent engineer: concise, " +
                    "factual, and focused on results."

        internal const val TASK_COMPLETION_GUIDANCE =
            "# Finishing the job\n" +
                    "When the user asks you to build, run, or verify something, the deliverable is " +
                    "a working result backed by real tool output — not a description of one. " +
                    "Do not stop after writing a stub, a plan, or a single command. Keep working " +
                    "until you have actually exercised the code or produced the requested result, " +
                    "then report what real execution returned.\n" +
                    "If a tool, install, or network call fails and blocks the real path, say so " +
                    "directly and try an alternative (different package manager, different " +
                    "approach, ask the user). NEVER substitute plausible-looking fabricated " +
                    "output (made-up data, invented file contents, synthesised API responses) " +
                    "for results you couldn't actually produce. Reporting a blocker honestly " +
                    "is always better than inventing a result."

        internal const val TOOL_USE_ENFORCEMENT_GUIDANCE =
            "# Tool use\n" +
                    "You MUST use your tools to take action — do not describe what you would do " +
                    "without actually doing it. When you say you will perform an action, you MUST " +
                    "immediately make the corresponding tool call in the same response. Never end " +
                    "your turn with a promise of future action — execute it now.\n" +
                    "Every response should either (a) contain tool calls that make progress, or " +
                    "(b) deliver a final result to the user."

        internal const val EXECUTION_RULES_GUIDANCE =
            "# Execution rules\n" +
                    "Tool actions may be blocked by Zafiro's app-level execution rules, which are " +
                    "user-configurable in Zafiro settings — not system restrictions. A block means " +
                    "the user declined the action or the rule is too strict; the user can adjust " +
                    "the rule in Zafiro settings. Do not describe blocks as system policy."

        /**
         * v2.0.0 Jarvis Mode — capability-first execution. Kills the "system blocked it"
         * class of answers: the agent must check & auto-fix capabilities instead of
         * reporting permission failures.
         */
        internal const val CAPABILITY_DIRECTIVE =
            "# Capability-first execution (no excuses)\n" +
                    "You have the device_capabilities tool. Before ANY system-level operation " +
                    "(privileged shell commands, screen control, contacts/calendar, storage, " +
                    "exact alarms, battery exemption), and ALWAYS immediately after any tool " +
                    "fails with a permission/authorization error, call device_capabilities " +
                    "with {\"action\":\"autofix\"} — it silently grants every permission a " +
                    "privileged shell can grant (runtime permissions, overlay, accessibility " +
                    "service, battery whitelist) and tells you exactly what remains.\n" +
                    "NEVER claim 'the system blocked it', 'permission missing', or 'I cannot " +
                    "do this because of restrictions' without having called " +
                    "device_capabilities autofix in that turn. If a capability is still " +
                    "missing after autofix, tell the user the EXACT remaining switch in one " +
                    "short line (e.g. 'Open Shizuku and tap Start via Wireless debugging'), " +
                    "then continue with everything that IS possible.\n" +
                    "Prefer terminal with identity=root or identity=shizuku for privileged " +
                    "work instead of embedding su in commands — the authorization dialog " +
                    "appears once and is remembered."

        internal const val MEMORY_GUIDANCE =
            "You have persistent memory across sessions. Save durable facts using the memory " +
                    "tool: user preferences, environment details, tool quirks, and stable conventions. " +
                    "Memory is injected into every turn, so keep it compact and focused on facts that " +
                    "will still matter later.\n" +
                    "Prioritize what reduces future user steering — the most valuable memory is one " +
                    "that prevents the user from having to correct or remind you again. " +
                    "User preferences and recurring corrections matter more than procedural task details.\n" +
                    "Do NOT save task progress, session outcomes, completed-work logs, or temporary TODO " +
                    "state to memory; use conversation history to recall those from past interactions. " +
                    "Specifically: do not record PR numbers, issue numbers, commit SHAs, 'fixed bug X', " +
                    "'submitted PR Y', 'Phase N done', file counts, or any artifact that will be stale " +
                    "in 7 days. If a fact will be stale in a week, it does not belong in memory. " +
                    "If you've discovered a new way to do something, solved a problem that could be " +
                    "necessary later, save it as a skill with the skill tool.\n" +
                    "Write memories as declarative facts, not instructions to yourself. " +
                    "'User prefers concise responses' ✓ — 'Always respond concisely' ✗. " +
                    "'Project uses pytest with xdist' ✓ — 'Run tests with pytest -n 4' ✗. " +
                    "Imperative phrasing gets re-read as a directive in later sessions and can " +
                    "cause repeated work or override the user's current request. Procedures and " +
                    "workflows belong in skills, not memory."

        internal const val SKILLS_GUIDANCE =
            "# Skills\n" +
                    "Skills contain specialized knowledge — API endpoints, tool-specific " +
                    "commands, and proven workflows that outperform general-purpose approaches. " +
                    "Load the skill even if you think you could handle the task with basic " +
                    "tools. Skills also encode the user's preferred approach, conventions, " +
                    "and quality standards — load them even for tasks you already know how " +
                    "to do, because the skill defines how it should be done here.\n" +
                    "load_skill returns the skill's SKILL.md content; if it exceeds the limit, " +
                    "the result ends with the absolute path to the file — use terminal to read " +
                    "the full content from there."

        internal const val PLAN_GUIDANCE =
            "# Task planning (todo_write)\n" +
                    "For any task with 3+ steps or multiple phases, write the full plan with " +
                    "todo_write BEFORE doing the work, then keep it live: exactly one item " +
                    "in_progress at a time, mark items completed immediately after each step is " +
                    "done and verified, and fold newly discovered work into the plan as you go. " +
                    "The user watches this plan in the Zafiro app in real time — an accurate, " +
                    "up-to-date plan is part of your deliverable. When the task is fully " +
                    "finished, the final todo_write must show every item completed."

        internal const val VAULT_GUIDANCE =
            "# Credential vault (vault_token)\n" +
                    "The user may store credentials for you (GitHub tokens, Cloudflare tokens, " +
                    "API keys) in the encrypted vault. When a task needs authentication, call " +
                    "vault_token with action \"list\" to see what is available (names and notes " +
                    "only), then action \"get\" with the exact name to fetch the value. Inject " +
                    "fetched secrets through environment variables or command arguments " +
                    "(e.g. GH_TOKEN=<value> gh pr view) instead of writing them into files, and " +
                    "never echo secret values back in your final answer unless the user asks."

        internal const val AGENT_WORKFLOW_GUIDANCE =
            "# Working style\n" +
                    "Work like a coding agent on a real machine: prefer reversible, verifiable " +
                    "steps; inspect state before mutating it; re-read what you wrote to confirm " +
                    "it; and summarize evidence (command output, test results, diffs) rather " +
                    "than intentions. If an approach fails twice, change strategy instead of " +
                    "retrying blindly. Batch independent operations, and keep the user's goal " +
                    "— not the procedure — at the center of your final answer."

        /**
         * v1.7.0 Core Directive — System-Integrated Autonomous Agent:
         * always evaluate the most efficient path to a goal.
         */
        internal const val SYSTEM_INTEGRATION_DIRECTIVE =
            "# Core directive: the most efficient path wins\n" +
                    "1. API-FIRST. For data retrieval — contacts, calendar, battery, device " +
                    "info, system settings, network state, installed apps — use system_data. " +
                    "It is 100% reliable and instant. UI automation (screen_operation*) is " +
                    "the LAST resort for reading these; do not navigate an app to read data " +
                    "you can query in one API call.\n" +
                    "2. REAL-TIME VISION. For dynamic interfaces — animations, games, video, " +
                    "transitions, anything that changes between your actions — use " +
                    "live_screen instead of screenshot. Each call returns the CURRENT " +
                    "frame from a continuous capture stream; a static screenshot may " +
                    "already be stale.\n" +
                    "3. PROACTIVE AUTONOMY. When a turn begins with [PROACTIVE_AUTOMATION] " +
                    "you were woken by a device event (notification, file, battery, " +
                    "schedule, location) that matched a user-configured trigger. Act " +
                    "toward the user's established goal autonomously: gather context " +
                    "(API-first), do the work, and report via notify — do not ask " +
                    "questions the user cannot answer right now.\n" +
                    "4. FILE ORCHESTRATION. For any file task — reading, writing, moving, " +
                    "renaming, bulk-organizing folders (sort_folder), searching, backups " +
                    "(zip), archives (unzip), and PDF operations (pdf_tools) — use " +
                    "file_manager and pdf_tools instead of shell commands: they are " +
                    "structured, need no root, and never depend on a shell being " +
                    "available.\n" +
                    "Choose the cheapest reliable path for every step: if it can be done " +
                    "via API, do it via API; if it needs visual confirmation of a live " +
                    "screen, use real-time vision; if it can be anticipated by an event, " +
                    "let a trigger wake you instead of polling."

        /**
         * v1.8.0 Samsung Special Directives — only composed on Samsung devices when the
         * samsung tool is available. One UI-optimized operation manual for the agent.
         */
        internal const val SAMSUNG_ONEUI_DIRECTIVE =
            "# Samsung One UI optimization (this device)\n" +
                    "This is a Samsung phone running One UI — you are its Samsung-optimized " +
                    "digital brain. Operate by these special directives:\n" +
                    "1. ONE UI API-FIRST. samsung.status and system_data.read_setting read " +
                    "One UI state (One UI version, navigation scheme, dark/power-saving mode, " +
                    "battery exemption, any settings key including Samsung sem_* keys) in " +
                    "milliseconds. Never open Settings or an app to read data you can query; " +
                    "UI navigation is the last resort for data retrieval.\n" +
                    "2. ONE UI NAVIGATION. For live screen control, call samsung.nav_mode " +
                    "first: it reports the active scheme and the matching gesture map. " +
                    "Gesture nav: swipe up = home, swipe up and hold = recents, swipe inward " +
                    "from either side edge = back; split screen and pop-up view are reached " +
                    "from the app icon above a recents card; edge panels (when enabled) are " +
                    "swipe-from-edge handles. One UI animates heavily — verify every step " +
                    "against the live frame, not a stale screenshot.\n" +
                    "3. ONE UI PERSISTENCE. One UI's battery optimizer deep-sleeps background " +
                    "apps aggressively. Your proactive service must survive: when evidence " +
                    "suggests it was killed (automation stops responding, samsung.status " +
                    "shows battery_optimization_ignored=false), use " +
                    "samsung.open_battery_settings to send the user to Device Care → Battery " +
                    "→ Background usage limits and have Zafiro removed from Deep sleeping " +
                    "apps. Do not waste turns retrying dead services — fix persistence " +
                    "first.\n" +
                    "4. SAMSUNG STORAGE. Samsung media follows known layouts: DCIM/Camera, " +
                    "DCIM/Screenshots or Pictures/Screenshots, Download, Documents, " +
                    "Recordings, Voice Recorder. Map them with samsung.storage_profile, then " +
                    "organize with file_manager sort_folder mode \"samsung\" (Screenshot/" +
                    "camera/recording-aware buckets). Samsung Cloud and Gallery sync content " +
                    "is not on disk — never claim to manage cloud-only files.\n" +
                    "EFFICIENCY: when a Samsung-specific shortcut exists (Device Care deep " +
                    "link, Modes and Routines, a Samsung settings key), take it before the " +
                    "generic Android path."
    }
}
