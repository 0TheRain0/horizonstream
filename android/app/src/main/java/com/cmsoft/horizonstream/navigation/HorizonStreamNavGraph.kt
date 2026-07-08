package com.cmsoft.horizonstream.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cmsoft.horizonstream.main.HelpScreen
import com.cmsoft.horizonstream.main.HomeScreen
import com.cmsoft.horizonstream.main.MainViewModel
import com.cmsoft.horizonstream.manual.EditManualConsoleScreen
import com.cmsoft.horizonstream.settings.SettingsScreen

@Composable
fun HorizonStreamNavGraph(mainViewModel: MainViewModel) {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                navController = navController,
                viewModel = mainViewModel
            )
        }
        composable("settings") {
            SettingsScreen(navController = navController)
        }
        composable("help") {
            HelpScreen(navController = navController)
        }
        composable("register/{host}?broadcast={broadcast}&manualHostId={manualHostId}") { backStackEntry ->
            val host = backStackEntry.arguments?.getString("host")
            EditManualConsoleScreen(
                navController = navController,
                manualHostId = 0L,
                prefilledHost = if (host == "null") null else host
            )
        }
        composable("edit_manual_console/{manualHostId}") { backStackEntry ->
            val manualHostId = backStackEntry.arguments?.getString("manualHostId")?.toLongOrNull()
            EditManualConsoleScreen(
                navController = navController,
                manualHostId = manualHostId
            )
        }
    }
}
