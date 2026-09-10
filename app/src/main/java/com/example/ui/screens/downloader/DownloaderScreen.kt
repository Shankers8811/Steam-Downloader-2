package com.example.ui.screens.downloader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoveUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.download.DepotProgress
import com.example.data.download.SessionPhase
import com.example.data.model.DlcMode
import com.example.data.model.LicenseReport
import com.example.data.model.LogLevel
import com.example.ui.components.EditorialGlassCard
import com.example.ui.components.WhiteCard
import com.example.ui.components.WhiteTextField
import com.example.ui.theme.ActiveGreen
import com.example.ui.theme.EditorialBackground
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.TextSecondaryLight
import com.example.ui.theme.WarningOrange
import com.example.ui.util.FormatUtils

/** Steam client / store palette (matches the LibraryScreen). */
private val SteamBg = Color(0xFF171A21)
private val SteamPanel = Color(0xFF1B2838)
private val SteamPanelHi = Color(0xFF2A475E)
private val SteamText = Color(0xFFC7D5E0)
private val SteamTextDim = Color(0xFF8F98A0)
private val SteamAccent = Color(0xFF66C0F4)

/**
 * Steam-style download manager: license check first, then live download
 * statistics (internet speed, disk write speed, ETA), chunk-boundary pausing
 * and a file-by-file view of what is staged vs installed. Store rule baked
 * in: downloads are limited to items the account actually owns.
 */
@Composable
fun DownloaderScreen(
    viewModel: DownloaderViewModel
) {


    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val accountName by viewModel.accountName.collectAsStateWithLifecycle()
    val installRootDisplay by viewModel.installRootDisplay.collectAsStateWithLifecycle()
    val statusNotification by viewModel.statusNotification.collectAsStateWithLifecycle()

    val appIdInput by viewModel.appIdInput.collectAsStateWithLifecycle()
    val appNameInput by viewModel.appNameInput.collectAsStateWithLifecycle()
    val depotIdsInput by viewModel.depotIdsInput.collectAsStateWithLifecycle()
    val branchInput by viewModel.branchInput.collectAsStateWithLifecycle()
    val includeDlc by viewModel.includeDlc.collectAsStateWithLifecycle()
    val dlcMode by viewModel.dlcMode.collectAsStateWithLifecycle()
    val ownedGames by viewModel.ownedGames.collectAsStateWithLifecycle()

    var pickerSearch by remember { mutableStateOf("") }
    var isFilesExpanded by remember { mutableStateOf(false) }
    var isConsoleExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusNotification) {
        statusNotification?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearNotification()
        }
    }

    val engineBusy = state.isEngineActive
    val phaseColor = when (state.phase) {
        SessionPhase.DOWNLOADING, SessionPhase.COMPLETED -> ActiveGreen
        SessionPhase.PAUSED -> WarningOrange
        SessionPhase.FAILED -> ErrorRed
        SessionPhase.CANCELLED -> Color(0xFF9CA3AF)
        else -> Color(0xFF38BDF8)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorialBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------------- Header ----------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ACCOUNT ${accountName.uppercase()}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0x80FFFFFF),
                        letterSpacing = 2.sp,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DOWN",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = (-1).sp
                        )
                        Text(
                            text = "LOADS",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0x66FFFFFF),
                            letterSpacing = (-1).sp
                        )
                    }
                }

                Surface(color = phaseColor, shape = RoundedCornerShape(20.dp)) {
                    Text(
                        text = when (state.phase) {
                            SessionPhase.IDLE -> "READY"
                            SessionPhase.VALIDATING_LICENSE -> "CHECKING LICENSE"
                            SessionPhase.ALLOCATING -> "ALLOCATING"
                            SessionPhase.DOWNLOADING -> "DOWNLOADING"
                            SessionPhase.PAUSED -> "PAUSED"
                            SessionPhase.VERIFYING -> "VERIFYING"
                            SessionPhase.COMPLETED -> "COMPLETE"
                            SessionPhase.FAILED -> "FAILED"
                            SessionPhase.CANCELLED -> "CANCELLED"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // ---------------- HERO CARD: progress + speeds + ETA ----------------
            WhiteCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DOWNLOADING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0x66000000),
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = state.appName.ifBlank {
                                appNameInput.ifBlank { "Pick a game from your library" }
                            },
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight,
                            maxLines = 1
                        )
                        Text(
                            text = buildString {
                                append("App ${if (state.appId != 0) state.appId else appIdInput.ifBlank { "---" }}")
                                append(" • ${state.branch} branch")
                                if (state.outputDisplay.isNotBlank()) append("\n→ ${state.outputDisplay}")
                            },
                            fontSize = 12.sp,
                            color = TextSecondaryLight,
                            lineHeight = 16.sp
                        )
                    }

                    Surface(
                        color = if (state.safeToMove) ActiveGreen else Color.Black,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = if (state.safeToMove) "SAFE TO MOVE" else "STAGED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = if (state.totalBytes > 0)
                            "${FormatUtils.formatBytes(state.downloadedBytes)} / ${FormatUtils.formatBytes(state.totalBytes)}"
                        else "-- / --",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimaryLight,
                        letterSpacing = (-1).sp
                    )
                    Text(
                        text = "${state.progressPercent.toInt()}%",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryLight
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = Color.Black,
                    trackColor = Color(0xFFE4E4E7)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Steam-style tri-stat: down rate / disk write / time remaining
                Row(modifier = Modifier.fillMaxWidth()) {
                    DownloadStat(
                        label = "DOWN RATE",
                        value = if (engineBusy) FormatUtils.formatSpeed(state.networkBytesPerSec) else "—",
                        caption = if (state.networkPeakBytesPerSec > 0)
                            "peak ${FormatUtils.formatSpeed(state.networkPeakBytesPerSec)}" else "internet speed",
                        modifier = Modifier.weight(1f)
                    )
                    DownloadStat(
                        label = "DISK WRITE",
                        value = if (engineBusy) FormatUtils.formatSpeed(state.diskBytesPerSec) else "—",
                        caption = "storage write speed",
                        modifier = Modifier.weight(1f)
                    )
                    DownloadStat(
                        label = "TIME REMAINING",
                        value = if (engineBusy && state.etaSeconds >= 0) FormatUtils.formatEta(state.etaSeconds)
                        else if (state.phase == SessionPhase.COMPLETED) "Done" else "—",
                        caption = if (engineBusy && state.etaSeconds > 0)
                            "≈ ${FormatUtils.formatDurationWords(state.etaSeconds)} left" else "estimated",
                        modifier = Modifier.weight(1f)
                    )
                }

                if (state.currentFile.isNotBlank() && state.phase == SessionPhase.DOWNLOADING) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color(0xFFF4F4F5),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = "INSTALLED  ${state.currentFile}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryLight,
                                maxLines = 1
                            )
                            Text(
                                text = "${state.completeFileCount} files whole & verified — " +
                                    "chunks are checksum-verified before they reach disk",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondaryLight,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (state.phase == SessionPhase.PAUSED) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = ActiveGreen.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoveUp,
                                contentDescription = null,
                                tint = ActiveGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Paused at a chunk boundary — no half-written files. Completed files are in your storage, ready to move to your PC; resume any time to continue from this exact point.",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryLight,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                if (state.errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = ErrorRed.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = state.errorMessage.orEmpty(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // ---------------- Control buttons ----------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    engineBusy -> {
                        Button(
                            onClick = { viewModel.pauseDownload() },
                            enabled = state.phase != SessionPhase.VERIFYING,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .testTag("pause_download_button")
                        ) {
                            Icon(imageVector = Icons.Filled.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("PAUSE", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        }
                        Button(
                            onClick = { viewModel.cancelDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.90f), contentColor = Color.White),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .testTag("cancel_download_button")
                        ) {
                            Icon(imageVector = Icons.Filled.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("CANCEL", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        }
                    }

                    state.phase == SessionPhase.PAUSED -> {
                        Button(
                            onClick = { viewModel.resumeDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .testTag("resume_download_button")
                        ) {
                            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("RESUME", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        }
                        Button(
                            onClick = { viewModel.cancelDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.90f), contentColor = Color.White),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Icon(imageVector = Icons.Filled.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("CANCEL", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        }
                    }

                    else -> {
                        Button(
                            onClick = { viewModel.startDownloadTask() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("start_download_button")
                        ) {
                            Icon(imageVector = Icons.Filled.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "VALIDATE LICENSE & DOWNLOAD",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                        }
                    }
                }
            }

            // Clear partials (shown when staged data survives a pause/cancel)
            if (state.phase == SessionPhase.PAUSED || state.phase == SessionPhase.CANCELLED ||
                (state.phase == SessionPhase.FAILED && state.hasResumableSession)
            ) {
                TextButton(
                    onClick = { viewModel.clearPartialData() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clear_partials_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        tint = TextSecondaryDark,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Clear staged partial data (${FormatUtils.formatBytes(state.downloadedBytes)})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondaryDark
                    )
                }
            }

            // ---------------- Storage destination ----------------
            EditorialGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "STORAGE DESTINATION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryDark,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x33FFFFFF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SdCard,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = installRootDisplay,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White,
                                maxLines = 2
                            )
                            Text(
                                text = "Real filesystem path — your PC sees this folder over USB (Android/data). Chunks resume; files are complete when listed below.",
                                fontSize = 10.sp,
                                color = TextSecondaryDark,
                                maxLines = 3
                            )
                        }
                    }
                }
            }

            // ---------------- License report ----------------
            state.licenseReport?.let { report ->
                LicenseReportCard(report = report)
            }

            // ---------------- Your games — pick what to download ----------------
            // Like the Steam store: this tab downloads ONLY items on the
            // account. Typing an App ID you don't own is refused by the
            // ownership guard in the ViewModel.
            Surface(
                color = SteamPanel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "OWNED BY YOU — TAP TO SELECT",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SteamAccent,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${ownedGames.size} games on this account · owned DLC appears on each game's details page",
                        fontSize = 10.sp,
                        color = SteamTextDim
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = pickerSearch,
                        onValueChange = { pickerSearch = it },
                        placeholder = { Text("Filter your games…", color = SteamTextDim, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = SteamTextDim)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SteamBg,
                            unfocusedContainerColor = SteamBg,
                            focusedBorderColor = SteamAccent,
                            unfocusedBorderColor = SteamPanelHi,
                            focusedTextColor = SteamText,
                            unfocusedTextColor = SteamText
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val filteredOwned = remember(ownedGames, pickerSearch) {
                        val owned = ownedGames.filter { it.appId > 0 }
                        if (pickerSearch.isBlank()) owned
                        else owned.filter { it.name.contains(pickerSearch, ignoreCase = true) }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SteamBg)
                    ) {
                        items(filteredOwned, key = { it.appId }) { g ->
                            val isSelected = appIdInput == g.appId.toString()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isSelected) SteamPanelHi else Color.Transparent)
                                    .clickable {
                                        viewModel.prefillFromLibrary(g.appId, g.name)
                                        pickerSearch = ""
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = g.name,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                                    color = SteamText,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                Text(
                                    text = "App ${g.appId}",
                                    fontSize = 10.sp,
                                    color = SteamTextDim
                                )
                            }
                        }
                        if (filteredOwned.isEmpty()) {
                            item {
                                Text(
                                    text = if (ownedGames.isEmpty())
                                        "Library not synced — open the Steam Library tab once so your games load."
                                    else "No owned items match \"$pickerSearch\".",
                                    fontSize = 12.sp,
                                    color = SteamTextDim,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Like the Steam store: anything you don't own is refused.",
                        fontSize = 10.sp,
                        color = SteamTextDim
                    )
                }
            }

            // ---------------- Target configuration ----------------
            WhiteCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "TARGET & BRANCH",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryLight,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhiteTextField(
                        value = appIdInput,
                        onValueChange = { viewModel.onAppIdChanged(it) },
                        label = "Steam App ID *",
                        placeholder = "e.g. 220",
                        modifier = Modifier.weight(1f)
                    )
                    WhiteTextField(
                        value = appNameInput,
                        onValueChange = { viewModel.onAppNameChanged(it) },
                        label = "Game title",
                        placeholder = "Half-Life 2",
                        modifier = Modifier.weight(1.2f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhiteTextField(
                        value = depotIdsInput,
                        onValueChange = { viewModel.onDepotIdsChanged(it) },
                        label = "Depot IDs (optional)",
                        placeholder = "Leave blank for all",
                        modifier = Modifier.weight(1f)
                    )
                    WhiteTextField(
                        value = branchInput,
                        onValueChange = { viewModel.onBranchChanged(it) },
                        label = "Branch",
                        placeholder = "public",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---------------- DLC configuration ----------------
            WhiteCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "DLC CONTENT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondaryLight,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Only DLC your account actually owns will be downloaded",
                            fontSize = 12.sp,
                            color = TextPrimaryLight,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = includeDlc,
                        onCheckedChange = { viewModel.onIncludeDlcChanged(it) }
                    )
                }

                AnimatedVisibility(visible = includeDlc) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        DlcModeOption(
                            selected = dlcMode == DlcMode.BASE_ONLY,
                            title = "Base game only",
                            onSelect = { viewModel.onDlcModeChanged(DlcMode.BASE_ONLY) }
                        )
                        DlcModeOption(
                            selected = dlcMode == DlcMode.BASE_AND_DLC,
                            title = "Base game + every licensed DLC (ownership checked first)",
                            onSelect = { viewModel.onDlcModeChanged(DlcMode.BASE_AND_DLC) }
                        )
                        DlcModeOption(
                            selected = dlcMode == DlcMode.DLC_ONLY,
                            title = "Licensed DLC only",
                            onSelect = { viewModel.onDlcModeChanged(DlcMode.DLC_ONLY) }
                        )
                    }
                }
            }

            // ---------------- Depots & installed files ----------------
            if (state.depots.isNotEmpty()) {
                EditorialGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isFilesExpanded = !isFilesExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "DEPOTS  ${state.depots.count { it.completed }}/${state.depots.size} • ${state.completeFileCount} FILES INSTALLED",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color.White,
                                letterSpacing = 1.2.sp
                            )
                        }
                        Text(
                            text = if (isFilesExpanded) "HIDE" else "SHOW",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    AnimatedVisibility(visible = isFilesExpanded) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            state.depots.forEach { depot ->
                                DepotProgressRow(depot = depot)
                            }
                            if (state.recentFiles.isNotEmpty()) {
                                Text(
                                    text = "RECENTLY INSTALLED (whole & verified — these can be moved)",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondaryDark,
                                    letterSpacing = 1.1.sp,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                                state.recentFiles.forEach { fileName ->
                                    RecentFileRow(fileName = fileName)
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- Engine log ----------------
            EditorialGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isConsoleExpanded = !isConsoleExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Filled.Terminal, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ENGINE LOG (${state.logLines.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color.White,
                            letterSpacing = 1.2.sp
                        )
                    }
                    Text(
                        text = if (isConsoleExpanded) "HIDE" else "SHOW",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                AnimatedVisibility(visible = isConsoleExpanded) {
                    Surface(
                        color = Color(0xFF000000),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(200.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        LaunchedEffect(state.logLines.size) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .verticalScroll(scrollState)
                        ) {
                            if (state.logLines.isEmpty()) {
                                Text(
                                    text = "Engine log appears here once a download starts…",
                                    color = Color(0x66FFFFFF),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                state.logLines.forEach { line ->
                                    Text(
                                        text = line.text,
                                        color = when (line.level) {
                                            LogLevel.OK -> ActiveGreen
                                            LogLevel.WARN -> WarningOrange
                                            LogLevel.ERROR -> ErrorRed
                                            LogLevel.INFO -> Color.White
                                        },
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ------------------------------------------------------------------
// Pieces
// ------------------------------------------------------------------

@Composable
private fun DownloadStat(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondaryLight,
            letterSpacing = 1.2.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            color = TextPrimaryLight,
            maxLines = 1
        )
        Text(
            text = caption,
            fontSize = 10.sp,
            color = TextSecondaryLight,
            maxLines = 1
        )
    }
}

@Composable
private fun LicenseReportCard(report: LicenseReport) {
    WhiteCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "LICENSE CHECK",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryLight,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = when {
                        report.baseLicensed -> "All downloads are licensed to your account"
                        report.baseUnverified -> "Web check unavailable — Steam servers enforce ownership"
                        else -> "Base app is not licensed"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryLight
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LicenseRow(
            ok = report.baseLicensed || report.baseUnverified,
            title = report.appName,
            tag = when {
                report.baseLicensed -> if (report.isFreeToPlay) "FREE LICENSE" else "OWNED"
                report.baseUnverified -> "CM-ENFORCED"
                else -> "PURCHASE REQUIRED"
            }
        )

        report.licensedDlc.forEach { dlc ->
            LicenseRow(ok = true, title = dlc.name, tag = "DLC LICENSED")
        }
        report.blockedDlc.forEach { dlc ->
            LicenseRow(ok = false, title = dlc.name, tag = "DLC — BUY SEPARATELY")
        }

        if (report.blockedDlc.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${report.blockedDlc.size} DLC item(s) skipped: they are sold separately and are not on this account, so they were excluded before any bytes were downloaded.",
                fontSize = 11.sp,
                color = TextSecondaryLight,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun LicenseRow(ok: Boolean, title: String, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Block,
            contentDescription = null,
            tint = if (ok) ActiveGreen else WarningOrange,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimaryLight,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Surface(
            color = if (ok) ActiveGreen.copy(alpha = 0.15f) else WarningOrange.copy(alpha = 0.18f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = tag,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (ok) ActiveGreen else WarningOrange,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun DepotProgressRow(depot: DepotProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = (if (depot.dlcAppId != null) "DLC ${depot.dlcAppId} • " else "") +
                    "${depot.name} (${depot.depotId})",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    depot.completed -> "DONE"
                    depot.fraction > 0f -> "DOWNLOADING"
                    else -> "QUEUED"
                },
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = when {
                    depot.completed -> ActiveGreen
                    depot.fraction > 0f -> Color(0xFF38BDF8)
                    else -> Color(0x66FFFFFF)
                }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { depot.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = if (depot.completed) ActiveGreen else Color.White,
            trackColor = Color(0x22FFFFFF)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = FormatUtils.formatBytes(depot.downloadedCompressedBytes) + " / " +
                    FormatUtils.formatBytes(depot.totalCompressedBytes),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0x80FFFFFF)
            )
            val pct = (depot.fraction * 100).toInt()
            Text(
                text = "$pct%",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0x80FFFFFF)
            )
        }
    }
}

@Composable
private fun RecentFileRow(fileName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = ActiveGreen,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = fileName,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xB3FFFFFF),
            maxLines = 1
        )
    }
}

@Composable
fun DlcModeOption(
    selected: Boolean,
    title: String,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = Color.Black)
        )
        Text(
            text = title,
            fontSize = 13.sp,
            color = TextPrimaryLight,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
