package com.example.ui.screens.auth

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.CrashLog
import com.example.data.auth.AuthState
import com.example.data.auth.SteamGuardType
import com.example.ui.components.WhiteCard
import com.example.ui.components.WhiteTextField
import com.example.ui.theme.EditorialBackground
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.TextSecondaryLight

/**
 * The opening screen: Steam-style sign-in with account name + password,
 * followed by the Steam Guard / 2FA challenge (code from the Steam Mobile App
 * or approve-this-sign-in confirmation), exactly like the desktop client.
 */
@Composable
fun LoginScreen(viewModel: AuthViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val guardError by viewModel.guardError.collectAsStateWithLifecycle()

    var username by rememberSaveable { mutableStateOf(viewModel.rememberedAccountName) }
    var password by rememberSaveable { mutableStateOf("") }
    var rememberMe by rememberSaveable { mutableStateOf(true) }
    var guardCode by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(authState) {
        if (authState is AuthState.AwaitingGuard) guardCode = ""
    }

    val isBusy = authState is AuthState.Busy

    // Diagnostics (crash report) dialog state — survives sign-in crashes.
    var showDiagnostics by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copiedToClipboard by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorialBackground)
    ) {
        // 🐞 Diagnostics entry — always reachable, even before any sign-in.
        // If the app ever misbehaves, this is how the exact reason is shared.
        TextButton(
            onClick = {
                copiedToClipboard = false
                showDiagnostics = true
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 8.dp)
        ) {
            Text(
                text = "🐞 Report",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondaryLight
            )
        }

        if (showDiagnostics) {
            AlertDialog(
                onDismissRequest = { showDiagnostics = false },
                title = { Text("Diagnostics", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        text = CrashLog.fullText(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(CrashLog.fullText()))
                            copiedToClipboard = true
                        }
                    ) {
                        Text(if (copiedToClipboard) "COPIED ✓" else "COPY ALL")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiagnostics = false }) { Text("CLOSE") }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ---------------- Brand header ----------------
            Surface(
                color = androidx.compose.ui.graphics.Color.White,
                shape = CircleShape
            ) {
                Text(
                    text = "S",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = androidx.compose.ui.graphics.Color.Black,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "SIGN IN WITH STEAM",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondaryDark,
                letterSpacing = 2.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "DEPOT",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = androidx.compose.ui.graphics.Color.White,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "DOWNLOADER",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = androidx.compose.ui.graphics.Color(0x66FFFFFF),
                    letterSpacing = (-1).sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Access your game library and download your owned content.",
                fontSize = 12.sp,
                color = TextSecondaryDark,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            when (val state = authState) {
                is AuthState.AwaitingGuard -> {
                    SteamGuardCard(
                        state = state,
                        guardError = guardError,
                        code = guardCode,
                        onCodeChange = { guardCode = it.uppercase().take(6) },
                        onSubmit = { viewModel.submitGuardCode(guardCode) },
                        onSwitchType = { viewModel.useGuardType(it) },
                        onCancel = { viewModel.cancelSignIn() }
                    )
                }
                else -> {
                    // ---------------- Credentials card ----------------
                    WhiteCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "ACCOUNT SIGN-IN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondaryLight,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        WhiteTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = "Steam account name",
                            placeholder = "gabe_newell",
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFF71717A)
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        WhiteTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "Password",
                            placeholder = "••••••••••••",
                            visualTransformation = PasswordVisualTransformation(),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFF71717A)
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Remember-me, like the desktop client's "Remember me".
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = rememberMe,
                                onCheckedChange = { rememberMe = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = androidx.compose.ui.graphics.Color(0xFF09090B),
                                    checkmarkColor = androidx.compose.ui.graphics.Color.White
                                ),
                                modifier = Modifier.testTag("remember_me_checkbox")
                            )
                            Column {
                                Text(
                                    text = "Remember me on this device",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryLight
                                )
                                Text(
                                    text = "Stay signed in — your session is stored encrypted.",
                                    fontSize = 11.sp,
                                    color = TextSecondaryLight
                                )
                            }
                        }

                        if (state is AuthState.Error) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                color = ErrorRed.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = state.message,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorRed,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { viewModel.signIn(username, password, rememberMe) },
                            enabled = !isBusy && username.isNotBlank() && password.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = androidx.compose.ui.graphics.Color(0xFF09090B),
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("sign_in_button")
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    color = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = (authState as? AuthState.Busy)?.message ?: "Working…",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = "SIGN IN",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Your password is RSA-encrypted and sent only to api.steampowered.com — it is never stored. " +
                    "If your account is protected by Steam Guard you will be asked for the code from your Steam Mobile App next, just like signing in to the Steam app on Windows.",
                fontSize = 11.sp,
                color = androidx.compose.ui.graphics.Color(0x66FFFFFF),
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Not affiliated with Valve Corporation. Downloads only cover content your account is licensed for.",
                fontSize = 10.sp,
                color = androidx.compose.ui.graphics.Color(0x44FFFFFF),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The Steam Guard step — appears after the password is accepted, and offers
 * whatever second-factor methods the account supports (mobile app code,
 * email code, or approve-in-app confirmation).
 */
@Composable
private fun SteamGuardCard(
    state: AuthState.AwaitingGuard,
    guardError: String?,
    code: String,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchType: (Int) -> Unit,
    onCancel: () -> Unit
) {
    WhiteCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (state.guardType == SteamGuardType.DEVICE_CONFIRMATION)
                    Icons.Filled.Smartphone else Icons.Filled.Shield,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color(0xFF09090B),
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "STEAM GUARD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryLight,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Extra verification required",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryLight
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = state.promptMessage,
            fontSize = 13.sp,
            color = TextSecondaryLight,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (state.codeEntrySupported) {
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                placeholder = {
                    Text(
                        text = "XXXXX",
                        color = androidx.compose.ui.graphics.Color(0xA071717A),
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 8.sp
                    )
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center
                ),
                isError = guardError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color(0xFFF4F4F5),
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color(0xFFF4F4F5),
                    focusedBorderColor = androidx.compose.ui.graphics.Color(0xFF09090B),
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color(0xFFE4E4E7),
                    focusedTextColor = TextPrimaryLight,
                    unfocusedTextColor = TextPrimaryLight
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("guard_code_input")
            )

            if (guardError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = guardError,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ErrorRed
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSubmit,
                enabled = code.length >= 4,
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF09090B),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("verify_guard_button")
            ) {
                Text("VERIFY & SIGN IN", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
        } else {
            // Approve-in-app confirmation: we simply show the waiting state.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = androidx.compose.ui.graphics.Color(0xFF09090B),
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Waiting for approval in the Steam Mobile App…",
                    fontSize = 13.sp,
                    color = TextPrimaryLight,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Alternative 2FA methods the account also allowed.
        val alternatives = state.availableTypes.filter {
            it != state.guardType && SteamGuardType.supportsCodeEntry(it)
        }
        if (!state.codeEntrySupported || alternatives.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            alternatives.forEach { type ->
                TextButton(
                    onClick = { onSwitchType(type) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (type == SteamGuardType.EMAIL_CODE)
                            "Use a code sent to my email instead"
                        else
                            "Enter a code from the Steam Mobile App instead",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondaryLight
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("CANCEL", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondaryLight)
        }
    }
}
