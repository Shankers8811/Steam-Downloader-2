package com.example.ui.screens.library

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.db.SteamGameEntity
import com.example.ui.components.WhiteCard
import com.example.ui.components.WhiteTextField
import com.example.ui.theme.EditorialBackground
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.TextSecondaryLight

/**
 * The signed-in user's Steam library, synced from their account after login.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onNavigateToDownloaderWithAppId: (Int, String) -> Unit
) {
    val games by viewModel.games.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    val sortedGames = remember(games, sortOrder) {
        when (sortOrder) {
            SortOrder.NAME -> games.sortedBy { it.name }
            SortOrder.PLAYTIME -> games.sortedByDescending { it.playtimeForever }
            SortOrder.APP_ID -> games.sortedBy { it.appId }
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
        ) {
            // ---------------- Header ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SIGNED IN AS ${session?.accountName?.uppercase() ?: "—"}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0x80FFFFFF),
                        letterSpacing = 2.sp,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "STEAM ",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = (-1).sp
                        )
                        Text(
                            text = "LIBRARY",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0x66FFFFFF),
                            letterSpacing = (-1).sp
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = { viewModel.fetchGames() },
                        enabled = !isLoading && session != null,
                        modifier = Modifier.testTag("refresh_library_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh library",
                            tint = if (session != null) Color.White else Color(0x44FFFFFF)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.signOut() },
                        modifier = Modifier.testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sign out",
                            tint = Color.White
                        )
                    }
                }
            }

            // ---------------- Account / sync status card ----------------
            WhiteCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACCOUNT LIBRARY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0x66000000),
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${games.size} games owned",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = TextPrimaryLight
                        )
                        Text(
                            text = when {
                                session == null -> "Not signed in"
                                games.isEmpty() && !isLoading -> "Tap refresh to sync your licenses"
                                else -> "Steam ID ${session?.steamId ?: "—"}"
                            },
                            fontSize = 12.sp,
                            color = TextSecondaryLight,
                            maxLines = 1
                        )
                    }

                    Surface(
                        color = if (session != null) Color(0xFF09090B) else Color(0xFFEF4444),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = if (session != null) "ONLINE" else "OFFLINE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---------------- Search & sort card ----------------
            WhiteCard(modifier = Modifier.fillMaxWidth()) {
                WhiteTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    label = "Filter your library",
                    placeholder = "Search game title or App ID…",
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = Color(0xFF71717A)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear",
                                    tint = Color(0xFF71717A)
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SORT BY:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondaryLight,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )

                    SortOrder.values().forEach { order ->
                        val selected = sortOrder == order
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.onSortOrderChanged(order) },
                            label = {
                                Text(
                                    text = when (order) {
                                        SortOrder.NAME -> "NAME"
                                        SortOrder.PLAYTIME -> "PLAYTIME"
                                        SortOrder.APP_ID -> "APP ID"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF09090B),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFFF4F4F5),
                                labelColor = TextPrimaryLight
                            ),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Syncing your Steam library…",
                                color = TextSecondaryDark,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                sortedGames.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        WhiteCard(modifier = Modifier.padding(8.dp)) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SportsEsports,
                                    contentDescription = null,
                                    tint = Color(0xFF09090B),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (searchQuery.isBlank()) "Nothing synced yet" else "No games matched",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryLight
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (searchQuery.isBlank())
                                        "Pull your licensed games from your Steam account — everything you own appears here and can be downloaded."
                                    else
                                        "No owned game matches '$searchQuery'.",
                                    fontSize = 13.sp,
                                    color = TextSecondaryLight,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                                if (searchQuery.isBlank()) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { viewModel.fetchGames() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF09090B)),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.testTag("sync_library_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("SYNC MY LIBRARY", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(sortedGames, key = { it.appId }) { game ->
                            GameCardItem(
                                game = game,
                                onDownloadClicked = {
                                    onNavigateToDownloaderWithAppId(game.appId, game.name)
                                }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun GameCardItem(
    game: SteamGameEntity,
    onDownloadClicked: () -> Unit
) {
    WhiteCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 110.dp, height = 65.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF18181B))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(game.imgHeaderUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = game.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextPrimaryLight,
                    maxLines = 1
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Surface(
                        color = Color(0xFF09090B),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "APP ${game.appId}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val playtimeHours = game.playtimeForever / 60.0
                    Text(
                        text = if (playtimeHours > 0) "%.1f hrs".format(playtimeHours) else "Unplayed",
                        fontSize = 11.sp,
                        color = TextSecondaryLight
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onDownloadClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF09090B),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("download_game_${game.appId}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Download ${game.name}",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
