package com.example.ui.screens.downloader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.DlcMode
import com.example.ui.components.EditorialGlassCard
import com.example.ui.components.WhiteCard
import com.example.ui.components.WhiteTextField
import com.example.ui.theme.ActiveGreen
import com.example.ui.theme.EditorialBackground
import com.example.ui.theme.EditorialGlassBorder
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryLight
import com.example.ui.theme.WarningOrange

@Composable
fun DownloaderScreen(
    viewModel: DownloaderViewModel
) {
    val context = LocalContext.current

    val usernameInput by viewModel.usernameInput.collectAsStateWithLifecycle()
    val passwordInput by viewModel.passwordInput.collectAsStateWithLifecycle()
    val twoFactorInput by viewModel.twoFactorInput.collectAsStateWithLifecycle()

    val appIdInput by viewModel.appIdInput.collectAsStateWithLifecycle()
    val appNameInput by viewModel.appNameInput.collectAsStateWithLifecycle()
    val depotIdsInput by viewModel.depotIdsInput.collectAsStateWithLifecycle()
    val manifestIdInput by viewModel.manifestIdInput.collectAsStateWithLifecycle()
    val branchInput by viewModel.branchInput.collectAsStateWithLifecycle()

    val includeDlc by viewModel.includeDlc.collectAsStateWithLifecycle()
    val dlcDepotId by viewModel.dlcDepotId.collectAsStateWithLifecycle()
    val dlcMode by viewModel.dlcMode.collectAsStateWithLifecycle()

    val targetDisplayPath by viewModel.targetDisplayPath.collectAsStateWithLifecycle()
    val bridgeState by viewModel.bridgeState.collectAsStateWithLifecycle()
    val statusNotification by viewModel.statusNotification.collectAsStateWithLifecycle()

    var isConsoleExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusNotification) {
        statusNotification?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearNotification()
        }
    }

    // SAF Storage Directory Launcher
    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)

                val folderName = uri.lastPathSegment?.substringAfterLast(":") ?: "Selected Directory"
                val display = if (uri.toString().contains("primary")) "Phone Storage / $folderName" else "USB / OTG Drive / $folderName"

                viewModel.setTargetDirectory(uri, display)
            } catch (e: Exception) {
                viewModel.setTargetDirectory(uri, uri.path ?: "Selected Storage")
            }
        }
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
            // Editorial Header Banner
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SYSTEM V2.4",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0x80FFFFFF),
                        letterSpacing = 2.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DEPOT",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = (-1).sp
                        )
                        Text(
                            text = "DR",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0x66FFFFFF),
                            letterSpacing = (-1).sp
                        )
                    }
                }

                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = if (bridgeState.isBinaryAvailable) "CLI READY" else "BRIDGE MODE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // EDITORIAL MAIN DOWNLOAD HERO CARD (Pure White 28dp card as spec)
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
                            text = if (appNameInput.isNotBlank()) appNameInput else "App ID ${if (appIdInput.isNotBlank()) appIdInput else "---"}",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight,
                            maxLines = 1
                        )
                        Text(
                            text = "App ID: ${if (appIdInput.isNotBlank()) appIdInput else "---"} • ${if (branchInput.isNotBlank()) branchInput else "public"} Branch",
                            fontSize = 12.sp,
                            color = TextSecondaryLight
                        )
                    }

                    Surface(
                        color = Color.Black,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = if (bridgeState.isRunning) "LIVE" else "RESUMABLE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = bridgeState.totalSizeFormatted,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = TextPrimaryLight,
                            letterSpacing = (-1).sp
                        )
                        Text(
                            text = "Speed: ${bridgeState.downloadSpeed}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondaryLight
                        )
                    }

                    Text(
                        text = "${bridgeState.progressPercent.toInt()}%",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryLight
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = { bridgeState.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = Color.Black,
                    trackColor = Color(0xFFE4E4E7)
                )
            }

            // ACTION BUTTONS ROW (Editorial Cancel / Pause / Resume / Start)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (bridgeState.isRunning) {
                    Button(
                        onClick = { viewModel.pauseDownload() },
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF), contentColor = Color.White),
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
                } else if (bridgeState.isPaused) {
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF), contentColor = Color.White),
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
                } else {
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
                        Text("START GAME DOWNLOAD", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                }
            }

            // SAF STORAGE DESTINATION CARD
            EditorialGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "STORAGE DESTINATION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0x80FFFFFF),
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
                            Icon(imageVector = Icons.Filled.SdCard, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = targetDisplayPath,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1
                            )
                            Text(
                                text = "SAF External Directory",
                                fontSize = 10.sp,
                                color = Color(0x80FFFFFF)
                            )
                        }
                    }

                    Button(
                        onClick = { dirPickerLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("select_saf_directory_button")
                    ) {
                        Text("CHANGE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // GAME & DEPOT CONFIGURATION WHITE CARD
            WhiteCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "TARGET & DEPOT CONFIGURATION",
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
                        label = "Game Title",
                        placeholder = "Half-Life 2",
                        modifier = Modifier.weight(1.2f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                WhiteTextField(
                    value = depotIdsInput,
                    onValueChange = { viewModel.onDepotIdsChanged(it) },
                    label = "Depot IDs (Optional)",
                    placeholder = "e.g. 220, 221 (Leave blank for all)"
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhiteTextField(
                        value = manifestIdInput,
                        onValueChange = { viewModel.onManifestIdChanged(it) },
                        label = "Manifest ID",
                        placeholder = "8374920194831",
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

            // DLC INCLUSION WHITE CARD
            WhiteCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "DLC CONFIGURATION", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondaryLight, letterSpacing = 1.5.sp)
                        Text(text = "Include DLC depots in download", fontSize = 12.sp, color = TextPrimaryLight, fontWeight = FontWeight.Bold)
                    }

                    Switch(
                        checked = includeDlc,
                        onCheckedChange = { viewModel.onIncludeDlcChanged(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color.Black
                        )
                    )
                }

                AnimatedVisibility(visible = includeDlc) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        DlcModeOption(
                            selected = dlcMode == DlcMode.BASE_ONLY,
                            title = "Base Game Only",
                            onSelect = { viewModel.onDlcModeChanged(DlcMode.BASE_ONLY) }
                        )

                        DlcModeOption(
                            selected = dlcMode == DlcMode.BASE_AND_DLC,
                            title = "Base Game + Selected DLC",
                            onSelect = { viewModel.onDlcModeChanged(DlcMode.BASE_AND_DLC) }
                        )

                        DlcModeOption(
                            selected = dlcMode == DlcMode.DLC_ONLY,
                            title = "DLC-Only Mode",
                            onSelect = { viewModel.onDlcModeChanged(DlcMode.DLC_ONLY) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        WhiteTextField(
                            value = dlcDepotId,
                            onValueChange = { viewModel.onDlcDepotIdChanged(it) },
                            label = "DLC Depot ID",
                            placeholder = "e.g. 228981"
                        )
                    }
                }
            }

            // CREDENTIALS CARD
            WhiteCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Filled.Lock, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = "STEAM ACCOUNT CREDENTIALS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = TextSecondaryLight, letterSpacing = 1.2.sp)
                        Text(text = "In-memory credentials required for protected depot keys", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                WhiteTextField(
                    value = usernameInput,
                    onValueChange = { viewModel.onUsernameChanged(it) },
                    label = "Steam Username",
                    placeholder = "gabe_newell"
                )

                Spacer(modifier = Modifier.height(8.dp))

                WhiteTextField(
                    value = passwordInput,
                    onValueChange = { viewModel.onPasswordChanged(it) },
                    label = "Steam Password",
                    placeholder = "••••••••••••",
                    visualTransformation = PasswordVisualTransformation()
                )

                Spacer(modifier = Modifier.height(8.dp))

                WhiteTextField(
                    value = twoFactorInput,
                    onValueChange = { viewModel.onTwoFactorChanged(it) },
                    label = "Steam Guard / 2FA Code",
                    placeholder = "ABC12"
                )
            }

            // LOG CONSOLE
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
                            text = "TERMINAL LOG (${bridgeState.logOutput.size} LINES)",
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
                            .height(180.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .verticalScroll(scrollState)
                        ) {
                            if (bridgeState.logOutput.isEmpty()) {
                                Text(
                                    text = "Terminal logs will display here upon download initialization...",
                                    color = Color(0x66FFFFFF),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                bridgeState.logOutput.forEach { logLine ->
                                    Text(
                                        text = logLine,
                                        color = if (logLine.contains("SUCCESS")) ActiveGreen else if (logLine.contains("ERROR") || logLine.contains("EXCEPTION")) ErrorRed else Color.White,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
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
