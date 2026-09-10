package com.example.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.db.SteamGameEntity

/** Steam client / store palette (matches the desktop + mobile app). */
private val SteamBg = Color(0xFF171A21)
private val SteamPanel = Color(0xFF1B2838)
private val SteamPanelHi = Color(0xFF2A475E)
private val SteamText = Color(0xFFC7D5E0)
private val SteamTextDim = Color(0xFF8F98A0)
private val SteamAccent = Color(0xFF66C0F4)
private val SteamBuy = Color(0xFF5BA32B)

private fun formatPlaytime(minutes: Int): String =
    if (minutes <= 0) "Not played yet"
    else {
        val hours = minutes / 60f
        if (hours < 10f) "%.1f h played".format(hours)
        else "%.0f h played".format(hours)
    }

/**
 * The signed-in user's Steam library — complete, games-only (native CM
 * licenses + PICS), presented like the Steam client: dark theme, capsule
 * art grid, search, sort and per-game download action.
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
    val selectedGame by viewModel.selectedGame.collectAsStateWithLifecycle()
    val dlcUi by viewModel.dlcUi.collectAsStateWithLifecycle()

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
            SortOrder.NAME -> games.sortedBy { it.name.lowercase() }
            SortOrder.PLAYTIME -> games.sortedByDescending { it.playtimeForever }
            SortOrder.APP_ID -> games.sortedBy { it.appId }
        }
    }

    // Store-style tabs: ALL / PLAYED / NEVER PLAYED (owned games only).
    var libraryFilter by remember { mutableStateOf(0) }
    val filteredGames = remember(sortedGames, libraryFilter) {
        when (libraryFilter) {
            1 -> sortedGames.filter { it.playtimeForever > 0 }
            2 -> sortedGames.filter { it.playtimeForever <= 0 }
            else -> sortedGames
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SteamBg)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------------- Sticky-style header (spans full width) ----------------
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "LIBRARY",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                color = SteamText,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (session != null)
                                    "${games.size} games you own — ${
                                        (session?.accountName ?: "")
                                    }"
                                else "Sign in to see your games",
                                fontSize = 11.sp,
                                color = SteamTextDim
                            )
                        }
                        Row {
                            IconButton(onClick = { viewModel.fetchGames() }) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Sync library",
                                    tint = SteamAccent
                                )
                            }
                            IconButton(onClick = { viewModel.signOut() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = "Sign out",
                                    tint = SteamTextDim
                                )
                            }
                        }
                    }

                    // -------- Search (Steam-dark) --------
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        placeholder = {
                            Text("Search your games", color = SteamTextDim, fontSize = 13.sp)
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = SteamTextDim)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                    Icon(
                                        Icons.Filled.Clear,
                                        contentDescription = "Clear search",
                                        tint = SteamTextDim
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SteamPanel,
                            unfocusedContainerColor = SteamPanel,
                            focusedBorderColor = SteamAccent,
                            unfocusedBorderColor = SteamPanelHi,
                            focusedTextColor = SteamText,
                            unfocusedTextColor = SteamText
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // -------- Store tabs: ALL / PLAYED / NEVER PLAYED --------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("ALL GAMES" to 0, "PLAYED" to 1, "NEVER PLAYED" to 2)
                            .forEach { (label, code) ->
                                FilterChip(
                                    selected = libraryFilter == code,
                                    onClick = { libraryFilter = code },
                                    label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = SteamPanel,
                                        labelColor = SteamTextDim,
                                        selectedContainerColor = SteamAccent,
                                        selectedLabelColor = SteamBg
                                    ),
                                    border = null,
                                    modifier = Modifier.testTag("tab_$code")
                                )
                            }
                    }

                    // -------- Sort chips --------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SortOrder.entries.forEach { order ->
                            FilterChip(
                                selected = sortOrder == order,
                                onClick = { viewModel.onSortOrderChanged(order) },
                                label = {
                                    Text(
                                        when (order) {
                                            SortOrder.NAME -> "A–Z"
                                            SortOrder.PLAYTIME -> "Most played"
                                            SortOrder.APP_ID -> "App ID"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = SteamPanel,
                                    labelColor = SteamTextDim,
                                    selectedContainerColor = SteamPanelHi,
                                    selectedLabelColor = SteamAccent
                                ),
                                border = null,
                                modifier = Modifier.testTag("sort_${order.name}")
                            )
                        }
                    }

                    if (isLoading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = SteamAccent,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Scanning your licenses with Steam…",
                                fontSize = 11.sp,
                                color = SteamTextDim
                            )
                        }
                    }
                }
            }

            // ---------------- Featured hero (store-style, most played) ----------------
            if (!isLoading && searchQuery.isBlank() && filteredGames.isNotEmpty()) {
                val hero = filteredGames.maxByOrNull { it.playtimeForever } ?: filteredGames.first()
                item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                    StoreHeroCapsule(game = hero, onGet = {
                        onNavigateToDownloaderWithAppId(hero.appId, hero.name)
                    })
                }
            }

            // ---------------- Games ----------------
            if (!isLoading && filteredGames.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SportsEsports,
                            contentDescription = null,
                            tint = SteamPanelHi,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isBlank())
                                "Library is empty — tap the sync icon."
                            else "No games match \"$searchQuery\"",
                            color = SteamTextDim,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            items(filteredGames, key = { it.appId }) { game ->
                GameCapsule(
                    game = game,
                    onTap = { viewModel.openGameDetails(game) },
                    onGet = { onNavigateToDownloaderWithAppId(game.appId, game.name) }
                )
            }
        }

        // Tile tap → full-screen details page (base download + owned DLC list).
        selectedGame?.let { game ->
            GameDetailsDialog(
                game = game,
                dlcUi = dlcUi,
                onClose = { viewModel.closeGameDetails() },
                onDownloadBase = {
                    viewModel.closeGameDetails()
                    onNavigateToDownloaderWithAppId(game.appId, game.name)
                },
                onDownloadDlc = { dlc ->
                    viewModel.closeGameDetails()
                    onNavigateToDownloaderWithAppId(dlc.appId, dlc.name)
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/** Store-window style featured hero: wide art, gradient, big title + CTA. */
@Composable
private fun StoreHeroCapsule(
    game: SteamGameEntity,
    onGet: () -> Unit
) {
    Surface(
        color = SteamPanel,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(game.imgHeaderUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = game.name,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(600f / 380f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SteamPanelHi)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xB3171A21))
                    .padding(14.dp)
            ) {
                Text(
                    text = "FEATURED FROM YOUR LIBRARY",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = SteamAccent,
                    letterSpacing = 1.6.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = game.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = SteamText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatPlaytime(game.playtimeForever),
                        fontSize = 11.sp,
                        color = SteamTextDim
                    )
                    Button(
                        onClick = onGet,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SteamBuy,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("DOWNLOAD", fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

/**
 * Tile-tap details page (full-screen dialog): hero art, base-game download
 * button, and the owned-DLC list with one DOWNLOAD action per DLC — the
 * "grab each DLC whenever you want, one by one" flow. Ownership comes from
 * the account's CM licenses (not store pages), so only real entitlements
 * show up as downloadable.
 */
@Composable
private fun GameDetailsDialog(
    game: SteamGameEntity,
    dlcUi: LibraryViewModel.DlcUiState?,
    onClose: () -> Unit,
    onDownloadBase: () -> Unit,
    onDownloadDlc: (com.example.data.steam.SteamRuntime.DlcEntry) -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SteamBg,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data("https://cdn.cloudflare.steamstatic.com/steam/apps/${game.appId}/library_hero.jpg")
                                .crossfade(true)
                                .build(),
                            contentDescription = game.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(SteamPanelHi)
                        )
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .background(Color(0x99171A21), RoundedCornerShape(50))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Close",
                                tint = SteamText
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = game.name,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = SteamText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${formatPlaytime(game.playtimeForever)}  ·  App ${game.appId}",
                            fontSize = 12.sp,
                            color = SteamTextDim
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onDownloadBase,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SteamBuy,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("details_download_base")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("DOWNLOAD BASE GAME", fontSize = 13.sp, fontWeight = FontWeight.Black)
                        }

                        Spacer(modifier = Modifier.height(22.dp))
                        Text(
                            text = "DLC — DOWNLOAD ONE BY ONE WHENEVER YOU WANT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = SteamAccent,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        val state = dlcUi
                        when {
                            state == null || state.loading -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = SteamAccent,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Checking DLC on your account…", fontSize = 12.sp, color = SteamTextDim)
                            }

                            state.error != null -> Text(
                                text = "Couldn't load DLC right now (${state.error}) — tap outside, then try again.",
                                fontSize = 12.sp,
                                color = SteamTextDim
                            )

                            state.entries.isEmpty() -> Text(
                                text = "No DLC exists for this game.",
                                fontSize = 12.sp,
                                color = SteamTextDim
                            )

                            else -> state.entries.forEach { entry ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SteamPanel)
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (entry.owned) SteamText else SteamTextDim,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (entry.owned) "OWNED" else "Not on this account",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (entry.owned) SteamBuy else SteamTextDim,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                    if (entry.owned) {
                                        Button(
                                            onClick = { onDownloadDlc(entry) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = SteamPanelHi,
                                                contentColor = SteamText
                                            ),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp),
                                            modifier = Modifier
                                                .height(30.dp)
                                                .padding(start = 8.dp)
                                                .testTag("get_dlc_${entry.appId}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("GET", fontSize = 10.sp, fontWeight = FontWeight.Black)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A game capsule card like the Windows Steam client grid: poster + title + get. */
@Composable
private fun GameCapsule(
    game: SteamGameEntity,
    onTap: () -> Unit,
    onGet: () -> Unit
) {
    Surface(
        color = SteamPanel,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
    ) {
        Column {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(game.imgHeaderUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = game.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(600f / 900f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(SteamPanelHi)
            )
            Column(modifier = Modifier.padding(10.dp)) {
                // (tap anywhere on the card → details & DLC page)
                Text(
                    text = game.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = SteamText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatPlaytime(game.playtimeForever),
                    fontSize = 10.sp,
                    color = SteamTextDim,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onGet,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SteamBuy,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .testTag("get_${game.appId}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("DOWNLOAD", fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}
