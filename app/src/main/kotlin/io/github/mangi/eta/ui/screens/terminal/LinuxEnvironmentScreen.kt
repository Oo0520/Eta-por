package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentInstaller
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentState
import io.github.mangi.eta.agent.terminal.AlpineInstallProgress
import io.github.mangi.eta.agent.terminal.AlpineInstallResult
import io.github.mangi.eta.agent.terminal.AlpineInstallStage
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallProgress
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallResult
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallStage
import io.github.mangi.eta.agent.terminal.DebianEnvironmentInstaller
import io.github.mangi.eta.agent.terminal.LinuxInstallRunner
import io.github.mangi.eta.agent.terminal.DebianEnvironmentState
import io.github.mangi.eta.agent.terminal.DebianInstallProgress
import io.github.mangi.eta.agent.terminal.DebianInstallResult
import io.github.mangi.eta.agent.terminal.DebianInstallStage
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxApkAnalysisInstaller
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxCapabilityItem
import io.github.mangi.eta.agent.terminal.LinuxCapabilityProbe
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.LinuxPackageProfile
import io.github.mangi.eta.agent.terminal.LinuxPackageProfileInstaller
import io.github.mangi.eta.agent.terminal.LinuxPackageProfiles
import io.github.mangi.eta.agent.terminal.PackageProfileInstallProgress
import io.github.mangi.eta.agent.terminal.PackageProfileInstallResult
import io.github.mangi.eta.agent.terminal.PackageProfileInstallStage
import io.github.mangi.eta.agent.terminal.PackageProfileUninstallResult
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.ui.app.KimiWebLaunchResult
import io.github.mangi.eta.ui.app.KimiWebLauncher
import io.github.mangi.eta.ui.app.launchForegroundExecution
import io.github.mangi.eta.ui.app.message
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.app.rememberExecutionNotificationRequest
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class InstallTarget {
    BASE,
    TOOLS,
    APK_ANALYSIS,
    PYTHON,
    NODE,
    SSH,
    KIMI,
}

private data class PackageProfileUi(
    val target: InstallTarget,
    val profile: LinuxPackageProfile,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    @param:StringRes val readyRes: Int,
    @param:StringRes val debianSummaryRes: Int = summaryRes,
    @param:StringRes val debianReadyRes: Int = readyRes,
)

private val packageProfileUis = listOf(
    PackageProfileUi(
        target = InstallTarget.PYTHON,
        profile = LinuxPackageProfiles.PYTHON,
        titleRes = R.string.linux_python_tools,
        summaryRes = R.string.linux_python_tools_summary,
        readyRes = R.string.linux_python_tools_ready,
        debianSummaryRes = R.string.linux_python_tools_summary_debian,
        debianReadyRes = R.string.linux_python_tools_ready_debian,
    ),
    PackageProfileUi(
        target = InstallTarget.NODE,
        profile = LinuxPackageProfiles.NODE,
        titleRes = R.string.linux_node_tools,
        summaryRes = R.string.linux_node_tools_summary,
        readyRes = R.string.linux_node_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.SSH,
        profile = LinuxPackageProfiles.SSH,
        titleRes = R.string.linux_ssh_tools,
        summaryRes = R.string.linux_ssh_tools_summary,
        readyRes = R.string.linux_ssh_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.KIMI,
        profile = LinuxPackageProfiles.KIMI,
        titleRes = R.string.linux_kimi_tools,
        summaryRes = R.string.linux_kimi_tools_summary,
        readyRes = R.string.linux_kimi_tools_ready,
    ),
)

@Composable
internal fun LinuxEnvironmentScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val capabilities = rememberDeviceCapabilities()
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val coroutineScope = rememberCoroutineScope()
    val selectionFlow = remember(appContext) {
        LinuxEnvironmentSettingsRepository.selectedFlow(appContext)
    }
    val selectedDistribution by selectionFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.current(appContext),
    )
    val backendFlow = remember(appContext, selectedDistribution, capabilities.root.isGranted) {
        LinuxEnvironmentSettingsRepository.backendFlow(appContext, selectedDistribution)
    }
    val backend by backendFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.backend(appContext, selectedDistribution),
    )
    val requiresRoot = backend == LinuxExecutionBackend.CHROOT && !capabilities.root.isGranted
    val installer = remember(appContext, backend) { AlpineEnvironmentInstaller(appContext) }
    val debianInstaller = remember(appContext, backend) { DebianEnvironmentInstaller(appContext) }
    val apkAnalysisInstaller = remember(appContext, selectedDistribution, backend) {
        LinuxApkAnalysisInstaller(appContext, selectedDistribution)
    }
    val profileInstallers = remember(appContext, selectedDistribution, backend) {
        packageProfileUis.associate { profileUi ->
            profileUi.target to LinuxPackageProfileInstaller(
                context = appContext,
                distribution = selectedDistribution,
                profile = profileUi.profile,
            )
        }
    }
    var status by remember(installer) { mutableStateOf(installer.status()) }
    var debianStatus by remember(debianInstaller) { mutableStateOf(debianInstaller.status()) }
    var busyTarget by remember { mutableStateOf<InstallTarget?>(null) }
    var progress by remember { mutableStateOf<AlpineInstallProgress?>(null) }
    var debianProgress by remember { mutableStateOf<DebianInstallProgress?>(null) }
    var profileProgressSummary by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var profileReady by remember(selectedDistribution, backend) {
        mutableStateOf(packageProfileUis.associate { it.target to profileInstallers.getValue(it.target).isReady() })
    }
    var apkAnalysisReady by remember(selectedDistribution, backend) {
        mutableStateOf(apkAnalysisInstaller.isReady())
    }
    var capabilityItems by remember(appContext, selectedDistribution, backend) {
        mutableStateOf<List<LinuxCapabilityItem>>(emptyList())
    }
    var capabilityChecking by remember(appContext, selectedDistribution, backend) { mutableStateOf(true) }
    var apkAnalysisProgress by remember { mutableStateOf<ApkAnalysisInstallProgress?>(null) }
    var kimiWebLaunching by remember { mutableStateOf(false) }
    var kimiWebRunning by remember(selectedDistribution, backend) { mutableStateOf(false) }
    val kimiWebLauncher = remember(appContext) {
        KimiWebLauncher(
            context = appContext,
            daemonSupervisor = DetachedTaskSupervisor(
                logger = AndroidAgentLogger,
                recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
                linuxRootfsPathProvider = { environment ->
                    environment.linuxDistribution?.let { distribution ->
                        LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
                    }
                },
                linuxSharedMountsProvider = { SharedFolderMounts.current() },
            ),
        )
    }
    var probeToken by remember { mutableStateOf(0) }
    var uninstallTarget by remember { mutableStateOf<InstallTarget?>(null) }
    // profileReady / apkAnalysisReady 进 key：装完或卸完立刻重测，别让能力检测停在旧结果。
    LaunchedEffect(
        appContext,
        selectedDistribution,
        backend,
        status.version,
        debianStatus.version,
        apkAnalysisReady,
        profileReady,
        probeToken,
    ) {
        capabilityChecking = true
        capabilityItems = LinuxCapabilityProbe.probe(appContext, selectedDistribution)
        capabilityChecking = false
    }
    LaunchedEffect(selectedDistribution, backend, kimiWebLaunching) {
        if (!kimiWebLaunching) {
            kimiWebRunning = kimiWebLauncher.status(selectedDistribution.terminalEnvironment).running
        }
    }
    val selectedBaseReady = when (selectedDistribution) {
        LinuxDistribution.ALPINE -> status.state != AlpineEnvironmentState.NOT_INSTALLED
        LinuxDistribution.DEBIAN -> debianStatus.state != DebianEnvironmentState.NOT_INSTALLED
    }
    val selectedToolsReady = when (selectedDistribution) {
        LinuxDistribution.ALPINE -> status.state == AlpineEnvironmentState.READY
        LinuxDistribution.DEBIAN -> debianStatus.state == DebianEnvironmentState.READY
    }

    fun launchInstallation(block: suspend () -> Unit) {
        val operation: suspend () -> Unit = {
            try {
                block()
            } finally {
                progress = null
                debianProgress = null
                profileProgressSummary = null
                apkAnalysisProgress = null
                busyTarget = null
            }
        }
        // 安装改到进程级作用域：退出设置页、切后台都不再中断下载（之前挂在页面 scope 上，
        // 一退页面协程就被取消，进度条停在原地）。前台租约拿不到时退回普通执行，不影响功能。
        if (!LinuxInstallRunner.tryAdopt()) {
            resultMessage = "已有安装任务在后台进行，完成后再试"
            return
        }
        requestExecutionNotifications()
        val hosted: suspend () -> Unit = {
            try {
                operation()
            } finally {
                LinuxInstallRunner.release()
            }
        }
        val job = LinuxInstallRunner.scope.launchForegroundExecution(
            context = appContext,
            onUnavailable = { /* 前台不可用不阻塞：下面退回普通执行 */ },
            block = hosted,
        )
        if (job == null) {
            LinuxInstallRunner.scope.launch { hosted() }
        }
    }

    fun installBase() {
        if (busyTarget != null || requiresRoot) return
        busyTarget = InstallTarget.BASE
        resultMessage = null
        launchInstallation {
            resultMessage = when (selectedDistribution) {
                LinuxDistribution.ALPINE -> installer.installBase { update ->
                    withContext(Dispatchers.Main.immediate) { progress = update }
                }.toMessage(context)
                LinuxDistribution.DEBIAN -> debianInstaller.installBase { update ->
                    withContext(Dispatchers.Main.immediate) { debianProgress = update }
                }.toMessage(context)
            }
            status = installer.status()
            debianStatus = debianInstaller.status()
            progress = null
            debianProgress = null
            busyTarget = null
        }
    }

    fun installTools() {
        if (busyTarget != null || requiresRoot) return
        busyTarget = InstallTarget.TOOLS
        resultMessage = null
        launchInstallation {
            resultMessage = when (selectedDistribution) {
                LinuxDistribution.ALPINE -> installer.installTools { update ->
                    withContext(Dispatchers.Main.immediate) { progress = update }
                }.toMessage(context)
                LinuxDistribution.DEBIAN -> debianInstaller.installTools { update ->
                    withContext(Dispatchers.Main.immediate) { debianProgress = update }
                }.toMessage(context)
            }
            status = installer.status()
            debianStatus = debianInstaller.status()
            profileReady = packageProfileUis.associate {
                it.target to profileInstallers.getValue(it.target).isReady()
            }
            apkAnalysisReady = apkAnalysisInstaller.isReady()
            progress = null
            debianProgress = null
            busyTarget = null
        }
    }

    /** Kimi 就绪后按钮变为启动 Web UI：守护任务常驻 kimi web，解析地址后拉起浏览器。 */
    fun launchKimiWeb() {
        if (kimiWebLaunching || requiresRoot) return
        requestExecutionNotifications()
        kimiWebLaunching = true
        resultMessage = null
        coroutineScope.launch {
            val result = kimiWebLauncher.launch(selectedDistribution.terminalEnvironment)
            kimiWebLaunching = false
            if (result is KimiWebLaunchResult.Failed) {
                resultMessage = result.message(context)
            }
        }
    }

    /** 卸载一个可选工具：先在 Linux 环境里删掉 Eta 自己的资产，再撤销安装记录。 */
    fun performUninstall(target: InstallTarget) {
        if (busyTarget != null || requiresRoot) return
        busyTarget = target
        resultMessage = null
        profileProgressSummary = context.getString(R.string.linux_uninstalling)
        launchInstallation {
            when (target) {
                InstallTarget.APK_ANALYSIS -> {
                    val uninstalled = apkAnalysisInstaller.uninstall()
                    apkAnalysisReady = apkAnalysisInstaller.isReady()
                    busyTarget = null
                    resultMessage = if (uninstalled) {
                        context.getString(R.string.linux_apk_uninstalled)
                    } else {
                        context.getString(R.string.linux_uninstall_failed)
                    }
                }
                else -> {
                    if (target == InstallTarget.KIMI && kimiWebRunning) {
                        kimiWebLauncher.stop(selectedDistribution.terminalEnvironment)
                        kimiWebRunning = false
                    }
                    val profileInstaller = profileInstallers.getValue(target)
                    val result = profileInstaller.uninstall()
                    profileReady = profileReady + (target to profileInstaller.isReady())
                    profileProgressSummary = null
                    busyTarget = null
                    val profileTitle = packageProfileUis
                        .firstOrNull { it.target == target }
                        ?.let { context.getString(it.titleRes) }
                        .orEmpty()
                    resultMessage = result.toMessage(context, profileTitle)
                }
            }
            probeToken++
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_linux_tool_environment_314d22),
        onBack = onBack,
    ) {
        item(key = "status-card") {
            val version = when (selectedDistribution) {
                LinuxDistribution.ALPINE -> status.version
                LinuxDistribution.DEBIAN -> debianStatus.version
            }
            val activeProgress = when (busyTarget) {
                InstallTarget.BASE, InstallTarget.TOOLS -> when (selectedDistribution) {
                    LinuxDistribution.ALPINE -> progress?.summary(context)
                    LinuxDistribution.DEBIAN -> debianProgress?.summary(context)
                }
                InstallTarget.APK_ANALYSIS -> apkAnalysisProgress?.summary(context)
                else -> profileProgressSummary
            }
            LinuxEnvironmentStatusCard(
                title = listOfNotNull(selectedDistribution.displayName(), version?.takeIf { it.isNotBlank() })
                    .joinToString(" "),
                mode = backend.displayName(),
                summary = when {
                    requiresRoot -> stringResource(R.string.capability_linux_root_lost)
                    kimiWebLaunching -> stringResource(R.string.linux_kimi_web_starting)
                    busyTarget != null -> activeProgress ?: stringResource(R.string.linux_installing)
                    selectedToolsReady -> stringResource(R.string.linux_environment_tools_ready)
                    selectedBaseReady -> stringResource(R.string.linux_environment_base_ready)
                    else -> stringResource(R.string.linux_not_installed) + "\n" + stringResource(
                        when {
                            backend == LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot_requirements
                            selectedDistribution == LinuxDistribution.ALPINE -> R.string.linux_requirements
                            else -> R.string.linux_debian_requirements
                        },
                    )
                },
                busy = busyTarget != null || kimiWebLaunching,
                message = resultMessage,
                actionText = when {
                    requiresRoot -> stringResource(R.string.capability_enhancements)
                    selectedToolsReady -> null
                    busyTarget != null -> stringResource(R.string.linux_installing)
                    selectedBaseReady -> stringResource(R.string.linux_install_base_tools)
                    else -> stringResource(R.string.linux_install_base)
                },
                actionEnabled = busyTarget == null && !kimiWebLaunching,
                onAction = {
                    if (requiresRoot) onNavigate(AppRoute.SystemEnhance)
                    else if (selectedBaseReady) installTools() else installBase()
                },
            )
        }
        item(key = "configuration-title") { EtaPreferenceGroupTitle(stringResource(R.string.linux_environment_configuration)) }
        item(key = "configuration-card") {
            LinuxEnvironmentConfiguration(
                distribution = selectedDistribution,
                backend = backend,
                rootGranted = capabilities.root.isGranted,
                enabled = busyTarget == null && !kimiWebLaunching,
                onDistributionSelected = { distribution ->
                    if (busyTarget == null && !kimiWebLaunching && distribution != selectedDistribution) {
                        resultMessage = null
                        coroutineScope.launch { LinuxEnvironmentSettingsRepository.select(distribution) }
                    }
                },
                onBackendSelected = { selectedBackend ->
                    if (busyTarget == null && !kimiWebLaunching && selectedBackend != backend &&
                        (selectedBackend == LinuxExecutionBackend.PROOT || capabilities.root.isGranted)
                    ) {
                        resultMessage = null
                        coroutineScope.launch {
                            LinuxEnvironmentSettingsRepository.selectBackend(selectedDistribution, selectedBackend)
                        }
                    }
                },
            )
        }
        item(key = "capability-section") {
            LinuxCapabilitySection(items = capabilityItems, checking = capabilityChecking)
        }
        item(key = "files-title") { EtaPreferenceGroupTitle(stringResource(R.string.linux_environment_files)) }
        item(key = "files-card") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(R.string.capability_workspace),
                    summary = stringResource(R.string.capability_workspace_summary),
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Folder, tint = EtaPreferenceColors.Orange) },
                    onClick = { onNavigate(AppRoute.Workspace) },
                )
                if (selectedBaseReady) {
                    EtaPreferenceDivider(hasLeading = true)
                    EtaArrowPreference(
                        title = stringResource(R.string.shared_folders_entry_title),
                        summary = stringResource(R.string.linux_environment_shared_folders_summary),
                        startAction = { EtaPreferenceIcon(Icons.Rounded.FolderOpen, tint = EtaPreferenceColors.Orange) },
                        onClick = { onNavigate(AppRoute.SharedFolders) },
                    )
                    EtaPreferenceDivider(hasLeading = true)
                    EtaArrowPreference(
                        title = stringResource(R.string.linux_files_entry_title),
                        summary = stringResource(R.string.linux_files_entry_summary),
                        startAction = { EtaPreferenceIcon(Icons.Rounded.Description, tint = EtaPreferenceColors.Blue) },
                        onClick = { onNavigate(AppRoute.LinuxFiles(selectedDistribution.wireName)) },
                    )
                }
            }
        }

        if (selectedToolsReady) {
            item(key = "optional-tools-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.ui_optional_tools_3097d6), modifier = Modifier.animateItem())
            }
            item(key = "optional-tools-card") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                        .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
                ) {
                    packageProfileUis.forEachIndexed { index, profileUi ->
                        val ready = profileReady[profileUi.target] == true
                        val isKimi = profileUi.target == InstallTarget.KIMI
                        val summaryRes = if (selectedDistribution == LinuxDistribution.DEBIAN) {
                            profileUi.debianSummaryRes
                        } else {
                            profileUi.summaryRes
                        }
                        val readyRes = if (selectedDistribution == LinuxDistribution.DEBIAN) {
                            profileUi.debianReadyRes
                        } else {
                            profileUi.readyRes
                        }
                        // 动作区只有行宽的 45%，放不下第三个按钮：Kimi Web 运行中时把「停止」放到行下方。
                        val kimiRunningAction: (@Composable () -> Unit)? = if (isKimi && ready && kimiWebRunning) {
                            {
                                Text(
                                    text = stringResource(R.string.linux_kimi_web_running_stop),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.clickable(
                                        enabled = !kimiWebLaunching && !requiresRoot && busyTarget == null,
                                    ) {
                                        coroutineScope.launch {
                                            val stopped = kimiWebLauncher.stop(
                                                selectedDistribution.terminalEnvironment,
                                            )
                                            kimiWebRunning = !stopped
                                        }
                                    },
                                )
                            }
                        } else {
                            null
                        }
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = stringResource(profileUi.titleRes),
                            summary = if (busyTarget == profileUi.target) {
                                profileProgressSummary ?: stringResource(summaryRes)
                            } else if (ready) {
                                stringResource(readyRes)
                            } else {
                                stringResource(summaryRes)
                            },
                            bottomAction = kimiRunningAction,
                            endActions = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (ready && isKimi) {
                                        CompactTextButton(
                                            text = when {
                                                kimiWebLaunching -> stringResource(R.string.linux_kimi_web_starting)
                                                kimiWebRunning -> stringResource(R.string.action_open)
                                                else -> stringResource(R.string.linux_kimi_web_launch_short)
                                            },
                                            enabled = !requiresRoot && !kimiWebLaunching && busyTarget == null,
                                            onClick = { launchKimiWeb() },
                                        )
                                    }
                                    if (ready) {
                                        UninstallIconButton(
                                            enabled = busyTarget == null && !requiresRoot && !kimiWebLaunching,
                                            onClick = { uninstallTarget = profileUi.target },
                                        )
                                    } else {
                                        CompactTextButton(
                                            text = if (busyTarget == profileUi.target) {
                                                stringResource(R.string.linux_installing)
                                            } else {
                                                stringResource(R.string.linux_install)
                                            },
                                            enabled = busyTarget == null && !requiresRoot,
                                            onClick = {
                                                if (busyTarget != null) return@CompactTextButton
                                                busyTarget = profileUi.target
                                                resultMessage = null
                                                val profileTitle = context.getString(profileUi.titleRes)
                                                launchInstallation {
                                                    val profileInstaller = profileInstallers.getValue(profileUi.target)
                                                    val result = profileInstaller.install { update ->
                                                        withContext(Dispatchers.Main.immediate) {
                                                            profileProgressSummary = update.summary(context, profileTitle)
                                                        }
                                                    }
                                                    profileReady = profileReady +
                                                        (profileUi.target to profileInstaller.isReady())
                                                    profileProgressSummary = null
                                                    busyTarget = null
                                                    resultMessage = result.toMessage(context, profileTitle)
                                                    probeToken++
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(
                        title = stringResource(R.string.ui_apk_analysis_95ad17),
                        summary = apkAnalysisProgress?.summary(context) ?: if (apkAnalysisReady) {
                            context.getString(R.string.linux_apk_tools_ready)
                        } else {
                            context.getString(R.string.linux_apk_tools_summary)
                        },
                        endActions = {
                            if (apkAnalysisReady) {
                                UninstallIconButton(
                                    enabled = busyTarget == null && !requiresRoot,
                                    onClick = { uninstallTarget = InstallTarget.APK_ANALYSIS },
                                )
                            } else {
                                CompactTextButton(
                                    text = if (busyTarget == InstallTarget.APK_ANALYSIS) {
                                        context.getString(R.string.linux_installing)
                                    } else {
                                        context.getString(R.string.linux_install)
                                    },
                                    enabled = busyTarget == null && !requiresRoot,
                                    onClick = {
                                        if (busyTarget != null) return@CompactTextButton
                                        busyTarget = InstallTarget.APK_ANALYSIS
                                        resultMessage = null
                                        launchInstallation {
                                            val result = apkAnalysisInstaller.install { update ->
                                                withContext(Dispatchers.Main.immediate) {
                                                    apkAnalysisProgress = update
                                                }
                                            }
                                            apkAnalysisReady = apkAnalysisInstaller.isReady()
                                            apkAnalysisProgress = null
                                            busyTarget = null
                                            resultMessage = result.toMessage(context)
                                            probeToken++
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    // 卸载是可逆但破坏性的：先弹一次确认再动手。
    uninstallTarget?.let { target ->
        val pendingTitle = when (target) {
            InstallTarget.APK_ANALYSIS -> context.getString(R.string.ui_apk_analysis_95ad17)
            else -> context.getString(packageProfileUis.first { it.target == target }.titleRes)
        }
        EtaWindowDialog(
            show = true,
            title = context.getString(R.string.linux_uninstall_title, pendingTitle),
            summary = context.getString(R.string.linux_uninstall_summary),
            onDismissRequest = { uninstallTarget = null },
        ) {
            Column {
                MiuixDialogActions(
                    confirmText = context.getString(R.string.linux_uninstall_confirm),
                    destructive = true,
                    onCancel = { uninstallTarget = null },
                    onConfirm = {
                        uninstallTarget = null
                        performUninstall(target)
                    },
                )
            }
        }
    }
}

private fun Long.toReadableSize(context: Context): String = Formatter.formatShortFileSize(context, this)

private fun AlpineInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != AlpineInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun DebianInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != DebianInstallStage.DOWNLOADING || totalBytes <= 0L) return stageName
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun PackageProfileInstallProgress.summary(context: Context, profileTitle: String): String =
    when (stage) {
        PackageProfileInstallStage.CHECKING -> context.getString(R.string.linux_profile_stage_checking)
        PackageProfileInstallStage.DOWNLOADING -> if (totalBytes > 0L) {
            context.getString(
                R.string.linux_profile_stage_downloading_percent,
                profileTitle,
                (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L),
            )
        } else {
            context.getString(R.string.linux_profile_stage_downloading, profileTitle)
        }
        PackageProfileInstallStage.INSTALLING ->
            context.getString(R.string.linux_profile_stage_installing, profileTitle)
        PackageProfileInstallStage.COMPLETE -> context.getString(R.string.linux_profile_stage_complete)
    }

private fun ApkAnalysisInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != ApkAnalysisInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    val name = when (artifactName) {
        "jadx" -> "JADX"
        "apktool" -> "Apktool"
        "smali" -> "smali"
        "baksmali" -> "baksmali"
        else -> context.getString(R.string.linux_tool)
    }
    return context.getString(R.string.linux_tool_progress_percent, stageName, name, percent)
}

private fun AlpineInstallResult.toMessage(context: Context): String = when (this) {
    AlpineInstallResult.AlreadyReady -> context.getString(R.string.linux_already_ready)
    is AlpineInstallResult.BaseInstalled -> context.getString(R.string.linux_base_install_complete, version)
    is AlpineInstallResult.ToolsInstalled -> context.getString(R.string.linux_install_complete, version)
    AlpineInstallResult.BaseNotInstalled -> context.getString(R.string.linux_base_required)
    is AlpineInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    AlpineInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    AlpineInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    AlpineInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is AlpineInstallResult.Failed -> message ?: context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun DebianInstallResult.toMessage(context: Context): String = when (this) {
    DebianInstallResult.AlreadyReady -> context.getString(R.string.linux_debian_already_ready)
    is DebianInstallResult.BaseInstalled -> context.getString(R.string.linux_debian_base_install_complete, version)
    is DebianInstallResult.ToolsInstalled -> context.getString(R.string.linux_debian_install_complete, version)
    DebianInstallResult.BaseNotInstalled -> context.getString(R.string.linux_base_required)
    is DebianInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    DebianInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    DebianInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    DebianInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is DebianInstallResult.Failed -> message ?: context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun PackageProfileInstallResult.toMessage(
    context: Context,
    profileTitle: String,
): String = when (this) {
    PackageProfileInstallResult.AlreadyReady ->
        context.getString(R.string.linux_profile_already_ready, profileTitle)
    PackageProfileInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is PackageProfileInstallResult.DependencyMissing -> {
        val dependencyTitle = packageProfileUis
            .firstOrNull { it.profile.id == profileId }
            ?.let { context.getString(it.titleRes) }
            ?: profileId
        context.getString(R.string.linux_profile_dependency_missing, dependencyTitle)
    }
    PackageProfileInstallResult.Installed ->
        context.getString(R.string.linux_profile_installed, profileTitle)
    is PackageProfileInstallResult.Failed -> context.getString(
        R.string.linux_profile_stage_failed,
        PackageProfileInstallProgress(stage).summary(context, profileTitle),
    )
}

private fun ApkAnalysisInstallResult.toMessage(context: Context): String = when (this) {
    ApkAnalysisInstallResult.AlreadyReady -> context.getString(R.string.linux_apk_analysis_ready)
    ApkAnalysisInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is ApkAnalysisInstallResult.InsufficientSpace ->
        context.getString(
            R.string.linux_insufficient_space,
            requiredBytes.toReadableSize(context),
            availableBytes.toReadableSize(context),
        )
    ApkAnalysisInstallResult.Installed -> context.getString(R.string.linux_apk_analysis_installed)
    is ApkAnalysisInstallResult.Failed -> context.getString(R.string.linux_apk_stage_failed, stage.displayName(context))
}

private fun PackageProfileUninstallResult.toMessage(
    context: Context,
    profileTitle: String,
): String = when (this) {
    PackageProfileUninstallResult.NotInstalled ->
        context.getString(R.string.linux_uninstalled, profileTitle)
    PackageProfileUninstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is PackageProfileUninstallResult.BlockedByDependent -> {
        val dependencyTitle = packageProfileUis
            .firstOrNull { it.profile.id == profileId }
            ?.let { context.getString(it.titleRes) }
            ?: profileId
        context.getString(R.string.linux_uninstall_blocked, dependencyTitle)
    }
    PackageProfileUninstallResult.Uninstalled ->
        context.getString(R.string.linux_uninstalled, profileTitle)
    is PackageProfileUninstallResult.Failed -> context.getString(R.string.linux_uninstall_failed)
}

private fun AlpineInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        AlpineInstallStage.CHECKING -> R.string.linux_stage_checking
        AlpineInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        AlpineInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        AlpineInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        AlpineInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun DebianInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        DebianInstallStage.CHECKING -> R.string.linux_stage_checking
        DebianInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        DebianInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        DebianInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        DebianInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun ApkAnalysisInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        ApkAnalysisInstallStage.CHECKING -> R.string.linux_apk_stage_checking
        ApkAnalysisInstallStage.DOWNLOADING -> R.string.linux_apk_stage_downloading
        ApkAnalysisInstallStage.PREPARING -> R.string.linux_apk_stage_preparing
        ApkAnalysisInstallStage.INSTALLING_JAVA -> R.string.linux_apk_stage_installing_java
        ApkAnalysisInstallStage.ACTIVATING -> R.string.linux_apk_stage_activating
        ApkAnalysisInstallStage.VERIFYING -> R.string.linux_apk_stage_verifying
        ApkAnalysisInstallStage.COMPLETE -> R.string.linux_apk_stage_complete
    },
)

/** 卸载入口用图标而不是开关/文字按钮：动作区只有行宽 45%，图标最省地方也不抢主色。 */
@Composable
private fun UninstallIconButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        minWidth = 32.dp,
        minHeight = 32.dp,
    ) {
        Icon(
            imageVector = Icons.Rounded.Delete,
            contentDescription = stringResource(R.string.linux_uninstall_confirm),
            modifier = Modifier.size(18.dp),
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurfaceVariantActions
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
    }
}

/** 行内小按钮：收窄内边距，动作区里塞两个控件时不至于溢出。 */
@Composable
private fun CompactTextButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) = EtaTextButton(
    text = text,
    enabled = enabled,
    onClick = onClick,
    minWidth = 0.dp,
    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
)
