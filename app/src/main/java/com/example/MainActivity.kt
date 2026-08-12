package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.navigation.Screen
import com.example.ui.screens.downloader.DownloaderScreen
import com.example.ui.screens.downloader.DownloaderViewModel
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.library.LibraryViewModel
import com.example.ui.theme.DepotDownloaderTheme
import com.example.ui.theme.EditorialBackground
import com.example.ui.theme.EditorialGlassBorder

class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()
    private val downloaderViewModel: DownloaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DepotDownloaderTheme {
                DepotDownloaderAppMain(
                    libraryViewModel = libraryViewModel,
                    downloaderViewModel = downloaderViewModel
                )
            }
        }
    }
}

@Composable
fun DepotDownloaderAppMain(
    libraryViewModel: LibraryViewModel,
    downloaderViewModel: DownloaderViewModel
) {
    val navController = rememberNavController()
    val items = listOf(Screen.Library, Screen.Downloader)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Library.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = EditorialBackground,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF000000),
                contentColor = Color.White,
                modifier = Modifier
                    .border(width = 1.dp, color = EditorialGlassBorder)
                    .navigationBarsPadding()
            ) {
                items.forEach { screen ->
                    val isSelected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = {
                            Text(
                                text = screen.title.uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = Color.White,
                            indicatorColor = Color.White,
                            unselectedIconColor = Color(0x66FFFFFF),
                            unselectedTextColor = Color(0x66FFFFFF)
                        ),
                        modifier = Modifier.testTag("nav_tab_${screen.route}")
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Library.route) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onNavigateToDownloaderWithAppId = { appId, name ->
                        downloaderViewModel.prefillFromLibrary(appId, name)
                        navController.navigate(Screen.Downloader.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(Screen.Downloader.route) {
                DownloaderScreen(
                    viewModel = downloaderViewModel
                )
            }
        }
    }
}
