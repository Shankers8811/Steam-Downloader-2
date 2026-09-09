package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.auth.AuthState
import com.example.ui.navigation.Screen
import com.example.ui.screens.auth.AuthViewModel
import com.example.ui.screens.auth.LoginScreen
import com.example.ui.screens.downloader.DownloaderScreen
import com.example.ui.screens.downloader.DownloaderViewModel
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.library.LibraryViewModel
import com.example.ui.theme.DepotDownloaderTheme
import com.example.ui.theme.EditorialBackground
import com.example.ui.theme.EditorialGlassBorder
import com.example.ui.theme.TextSecondaryDark

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    private val downloaderViewModel: DownloaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DepotDownloaderTheme {
                val authState by authViewModel.authState.collectAsStateWithLifecycle()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = EditorialBackground
                ) {
                    when (authState) {
                        // Restoring a remembered session ("keep me signed in")
                        AuthState.Restoring -> Bootsplash()

                        // Not signed in → Steam-style login (password + Steam Guard)
                        is AuthState.LoggedOut,
                        is AuthState.Busy,
                        is AuthState.AwaitingGuard,
                        is AuthState.Error -> LoginScreen(viewModel = authViewModel)

                        // Signed in → library + downloader tabs
                        is AuthState.LoggedIn -> DepotDownloaderAppMain(
                            libraryViewModel = libraryViewModel,
                            downloaderViewModel = downloaderViewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Bootsplash() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "DEPOT",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "DOWNLOADER",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0x66FFFFFF),
                    letterSpacing = (-1).sp
                )
            }
            Text(
                text = "Restoring your signed-in Steam session…",
                fontSize = 12.sp,
                color = TextSecondaryDark
            )
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp),
                strokeWidth = 2.dp
            )
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
