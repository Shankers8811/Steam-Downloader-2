package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Library : Screen(
        route = "library",
        title = "Steam Library",
        selectedIcon = Icons.Filled.SportsEsports,
        unselectedIcon = Icons.Outlined.SportsEsports
    )

    object Downloader : Screen(
        route = "downloader",
        title = "Downloader",
        selectedIcon = Icons.Filled.Download,
        unselectedIcon = Icons.Outlined.Download
    )
}
