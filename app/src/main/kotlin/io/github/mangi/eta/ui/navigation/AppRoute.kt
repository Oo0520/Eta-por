package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Browser : AppRoute

    @Serializable
    data object Terminal : AppRoute

    @Serializable
    data object Tools : AppRoute

    @Serializable
    data object WebToolsSettings : AppRoute

    @Serializable
    data object Skills : AppRoute

    @Serializable
    data object Characters : AppRoute

    @Serializable
    data class CharacterDetail(val characterId: String) : AppRoute

    @Serializable
    data class CharacterEditor(val characterId: String? = null) : AppRoute

    @Serializable
    data object CharacterPersona : AppRoute

    @Serializable
    data class CharacterMemory(val characterId: String) : AppRoute

    @Serializable
    data object Permissions : AppRoute

    @Serializable
    data object SystemEnhance : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object AppearanceSettings : AppRoute

    @Serializable
    data object SpeechSettings : AppRoute

    @Serializable
    data object SpeechRecognition : AppRoute

    @Serializable
    data object SpeechSynthesis : AppRoute

    @Serializable
    data object SpeechOss : AppRoute

    @Serializable
    data object DataBackup : AppRoute

    @Serializable
    data object Memory : AppRoute
    @Serializable
    data object Tasks : AppRoute

    @Serializable
    data object TokenUsage : AppRoute

    @Serializable
    data object DataUsage : AppRoute

    @Serializable
    data object SelfCheck : AppRoute

    @Serializable
    data object DiagnosticsPatrol : AppRoute

    @Serializable
    data object MemoryDocument : AppRoute

    @Serializable
    data object PromptStudio : AppRoute

    @Serializable
    data object PromptEditor : AppRoute

    @Serializable
    data object PromptVersions : AppRoute

    @Serializable
    data object PromptTools : AppRoute

    @Serializable
    data object PromptPresets : AppRoute

    @Serializable
    data object PromptSnapshots : AppRoute

    @Serializable
    data class PromptSnapshotDetail(val fileName: String) : AppRoute

    @Serializable
    data class PromptPresetEditor(val name: String? = null) : AppRoute

    @Serializable
    data object PromptIntegrity : AppRoute

    @Serializable
    data object PromptEffective : AppRoute

    /** segment = -1 表示工具清单，>=0 表示第 N 段 system 消息。 */
    @Serializable
    data class PromptEffectiveItem(val segment: Int) : AppRoute

    @Serializable
    data object PromptToolGroups : AppRoute

    @Serializable
    data object ConversationCategories : AppRoute
    @Serializable
    data object VirtualScreen : AppRoute

    @Serializable
    data object LinuxEnvironment : AppRoute

    @Serializable
    data object SharedFolders : AppRoute

    @Serializable
    data object Workspace : AppRoute

    @Serializable
    data class LinuxFiles(val distribution: String) : AppRoute

    @Serializable
    data object ModelProviders : AppRoute

    @Serializable
    data object CommunityCatalog : AppRoute

    @Serializable
    data class CommunityCatalogProvider(val catalogId: String) : AppRoute

    @Serializable
    data object McpServers : AppRoute

    @Serializable
    data object GitHubAccount : AppRoute

    @Serializable
    data class McpServerDetail(val serverId: String) : AppRoute

    @Serializable
    data class ModelProviderDetail(val providerId: String) : AppRoute

    @Serializable
    data class ModelProviderNew(val providerType: NewProviderType) : AppRoute
}

@Serializable
enum class NewProviderType { OpenAiCompatible, Anthropic }
