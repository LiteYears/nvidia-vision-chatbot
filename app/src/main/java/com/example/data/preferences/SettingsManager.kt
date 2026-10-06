package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai_chatbot_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_PLAN_TYPE = "plan_type"
        private const val KEY_INCOGNITO = "is_incognito"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_P = "top_p"
        private const val KEY_MAX_TOKENS = "max_tokens"

        // Command Permission Keys
        private const val KEY_ALWAYS_ALLOW_ALL_COMMANDS = "always_allow_all_commands"
        private const val KEY_COMMAND_PERMISSION_POLICY = "command_permission_policy"
        private const val KEY_ALWAYS_ALLOWED_COMMANDS = "always_allowed_commands"
        private const val KEY_SHOW_TERMINAL = "show_terminal"

        const val DEFAULT_USER_NAME = ""
        const val DEFAULT_MODEL = "meta/llama-3.2-11b-vision-instruct"
        const val MODEL_QUANTUM = "Quantum 3"
        const val DEFAULT_SYSTEM_PROMPT = "You are an intelligent, helpful AI assistant built with precision."
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_P = 0.95f
        const val DEFAULT_MAX_TOKENS = 4096

        fun sanitizeKey(key: String): String {
            return key.trim().trim('.', ',', ';', ':', '"', '\'', '`', ' ')
        }
    }

    /**
     * Resolves API key from custom in-app preference, BuildConfig (from .env),
     * BuildConfig (from environment variable), or the default key.
     */
    fun getEffectiveApiKey(): String {
        val customKey = sanitizeKey(prefs.getString(KEY_CUSTOM_API_KEY, "") ?: "")
        if (customKey.isNotBlank()) return customKey

        val buildKey = try {
            sanitizeKey(BuildConfig.NVIDIA_API_KEY ?: "")
        } catch (_: Exception) {
            ""
        }
        if (buildKey.isNotBlank() && !buildKey.contains("placeholder", ignoreCase = true) && !buildKey.contains("DEFAULT_API_KEY", ignoreCase = true)) {
            return buildKey
        }

        val envKey = try {
            sanitizeKey(BuildConfig.NVIDIA_ENV_API_KEY ?: "")
        } catch (_: Exception) {
            ""
        }
        if (envKey.isNotBlank() && !envKey.contains("placeholder", ignoreCase = true)) {
            return envKey
        }

        return ""
    }

    fun getCustomApiKey(): String = sanitizeKey(prefs.getString(KEY_CUSTOM_API_KEY, "") ?: "")

    fun setCustomApiKey(key: String) {
        val sanitized = sanitizeKey(key)
        prefs.edit().putString(KEY_CUSTOM_API_KEY, sanitized).apply()
    }

    fun getSelectedModel(): String {
        val model = prefs.getString(KEY_SELECTED_MODEL, MODEL_QUANTUM) ?: MODEL_QUANTUM
        if (model.contains("llama-3.2-3b", ignoreCase = true) ||
            model.contains("llama-3.2-1b", ignoreCase = true) ||
            model.contains("nemotron-70b", ignoreCase = true)) {
            return DEFAULT_MODEL
        }
        return model
    }

    fun setSelectedModel(model: String) {
        prefs.edit().putString(KEY_SELECTED_MODEL, model).apply()
    }

    fun getUserName(): String =
        prefs.getString(KEY_USER_NAME, DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME

    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    fun getPlanType(): String =
        prefs.getString(KEY_PLAN_TYPE, "Free plan") ?: "Free plan"

    fun setPlanType(plan: String) {
        prefs.edit().putString(KEY_PLAN_TYPE, plan).apply()
    }

    fun isIncognito(): Boolean =
        prefs.getBoolean(KEY_INCOGNITO, false)

    fun setIncognito(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INCOGNITO, enabled).apply()
    }

    fun getSystemPrompt(): String =
        prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT

    fun setSystemPrompt(prompt: String) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt.trim()).apply()
    }

    fun getTemperature(): Float =
        prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)

    fun setTemperature(temperature: Float) {
        prefs.edit().putFloat(KEY_TEMPERATURE, temperature.coerceIn(0.0f, 1.5f)).apply()
    }

    fun getTopP(): Float =
        prefs.getFloat(KEY_TOP_P, DEFAULT_TOP_P)

    fun setTopP(topP: Float) {
        prefs.edit().putFloat(KEY_TOP_P, topP.coerceIn(0.05f, 1.0f)).apply()
    }

    fun getMaxTokens(): Int =
        prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)

    fun setMaxTokens(maxTokens: Int) {
        prefs.edit().putInt(KEY_MAX_TOKENS, maxTokens.coerceIn(256, 8192)).apply()
    }

    // --- Command Permission & Agent Control Preferences ---

    /**
     * True if unrestricted autonomous control is granted (all commands allowed to execute normally).
     */
    fun isAlwaysAllowAllCommands(): Boolean =
        prefs.getBoolean(KEY_ALWAYS_ALLOW_ALL_COMMANDS, true)

    fun setAlwaysAllowAllCommands(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALWAYS_ALLOW_ALL_COMMANDS, enabled).apply()
    }

    /**
     * Selected command permission policy (always_allow_all, ask_sensitive, always_ask).
     */
    fun getCommandPermissionPolicy(): String =
        prefs.getString(KEY_COMMAND_PERMISSION_POLICY, "always_allow_all") ?: "always_allow_all"

    fun setCommandPermissionPolicy(policy: String) {
        prefs.edit().putString(KEY_COMMAND_PERMISSION_POLICY, policy).apply()
    }

    /**
     * Set of specific commands/executables that have been whitelisted for "Always Allow".
     */
    fun getAlwaysAllowedCommands(): Set<String> =
        prefs.getStringSet(KEY_ALWAYS_ALLOWED_COMMANDS, emptySet()) ?: emptySet()

    fun isCommandAlwaysAllowed(executable: String): Boolean {
        return true
    }

    fun addAlwaysAllowedCommand(executable: String) {
        val clean = executable.trim().lowercase()
        if (clean.isEmpty()) return
        val current = getAlwaysAllowedCommands().toMutableSet()
        current.add(clean)
        prefs.edit().putStringSet(KEY_ALWAYS_ALLOWED_COMMANDS, current).apply()
    }

    fun removeAlwaysAllowedCommand(executable: String) {
        val clean = executable.trim().lowercase()
        val current = getAlwaysAllowedCommands().toMutableSet()
        current.remove(clean)
        prefs.edit().putStringSet(KEY_ALWAYS_ALLOWED_COMMANDS, current).apply()
    }

    fun clearAlwaysAllowedCommands() {
        prefs.edit().remove(KEY_ALWAYS_ALLOWED_COMMANDS).apply()
    }

    /**
     * Controls whether the developer/advanced Terminal is exposed in the UI.
     * OFF by default to keep the primary experience focused and smartphone-first.
     */
    fun isShowTerminal(): Boolean =
        prefs.getBoolean(KEY_SHOW_TERMINAL, false)

    fun setShowTerminal(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TERMINAL, show).apply()
    }
}
