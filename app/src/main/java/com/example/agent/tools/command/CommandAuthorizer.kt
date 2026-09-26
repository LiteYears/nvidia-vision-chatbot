package com.example.agent.tools.command

/**
 * Interface for delegating command execution authorization to the UI layer.
 * Allows the app to prompt the user with "Allow Once", "Always Allow This Command",
 * "Always Allow All (Unrestricted)", or "Deny".
 */
interface CommandAuthorizer {
    /**
     * Suspends execution and requests permission from the user.
     */
    suspend fun requestPermission(
        command: String,
        workingDir: String,
        classification: CommandClassification
    ): CommandPermissionDecision
}
