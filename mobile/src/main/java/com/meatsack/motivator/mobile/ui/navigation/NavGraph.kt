package com.meatsack.motivator.mobile.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.meatsack.motivator.mobile.ui.debug.DebugScreen
import com.meatsack.motivator.mobile.ui.library.LibraryScreen
import com.meatsack.motivator.mobile.ui.settings.SettingsScreen
import com.meatsack.motivator.mobile.ui.theme.LocalThemeChoice
import com.meatsack.motivator.mobile.ui.theme.ThemeChoice

/** Route for the temporary diagnostics screen (not part of the bottom-nav [Screen] set). */
private const val DEBUG_ROUTE = "debug"

enum class Screen(
    val route: String,
    val vitalsLabel: String,
    val bubblegumLabel: String,
    val icon: ImageVector,
) {
    Library("library", "ARSENAL", "Library", Icons.AutoMirrored.Filled.List),
    Settings("settings", "CONFIG", "Settings", Icons.Default.Settings),
    ;

    fun label(theme: ThemeChoice): String =
        if (theme == ThemeChoice.BUBBLEGUM) bubblegumLabel else vitalsLabel
}

@Composable
fun MeatsackNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val theme = LocalThemeChoice.current

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.label(theme)) },
                        label = {
                            Text(screen.label(theme), style = MaterialTheme.typography.labelSmall)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Screen.Library.route) { LibraryScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(onOpenDebug = { navController.navigate(DEBUG_ROUTE) })
            }
            // Temporary diagnostics screen — not a bottom-nav tab; reached from Settings.
            // See docs/debug/triggering-investigation.md.
            composable(DEBUG_ROUTE) { DebugScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
