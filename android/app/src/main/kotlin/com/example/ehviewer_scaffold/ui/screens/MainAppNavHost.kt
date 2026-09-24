package com.example.ehviewer_scaffold.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

import androidx.compose.runtime.CompositionLocalProvider
import com.example.ehviewer_scaffold.utils.LocalAnimatedVisibilityScope
import com.example.ehviewer_scaffold.utils.LocalSharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainAppNavHost() {
    val navController = rememberNavController()

    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this@SharedTransitionLayout
        ) {
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.fillMaxSize(),
                enterTransition = { 
                    slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.9f)) { it }
                },
                exitTransition = {
                    slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.9f)) { -it / 3 }
                },
                popEnterTransition = {
                    slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.9f)) { -it / 3 }
                },
                popExitTransition = {
                    slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.9f)) { it }
                }
            ) {
            composable("home") { backStackEntry ->
                CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                    HomeScreen(
                        onGalleryClick = { gid, token, coverUrl ->
                    val encodedCover = android.net.Uri.encode(coverUrl)
                    navController.navigate("detail/$gid/$token?coverUrl=$encodedCover") {
                        launchSingleTop = true
                    }
                },
                onNavigateToDownloads = {
                    navController.navigate("downloads")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                },
                onNavigateToLogin = {
                    navController.navigate("login")
                }
            )
        }
        }

        // Tag search result screen — HomeScreen pre-loaded with tag query.
        // Separate route so the back stack returns correctly to the caller (detail page).
        composable(
            route = "tag_search/{query}",
            arguments = listOf(navArgument("query") { type = NavType.StringType })
        ) { backStackEntry ->
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                val rawQuery = backStackEntry.arguments?.getString("query").orEmpty()
                val query = URLDecoder.decode(rawQuery, "UTF-8")
                HomeScreen(
                    initialQuery = query,
                onGalleryClick = { gid, token, coverUrl ->
                    val encodedCover = android.net.Uri.encode(coverUrl)
                    navController.navigate("detail/$gid/$token?coverUrl=$encodedCover") {
                        launchSingleTop = true
                    }
                },
                onNavigateToDownloads = {
                    navController.navigate("downloads")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                },
                onNavigateToLogin = {
                    navController.navigate("login")
                },
                onBack = { navController.popBackStack() }
            )
        }
        }

        composable("downloads") {
            DownloadsScreen(
                onBack = { navController.popBackStack() },
                // Finished downloads were a dead end before this: the pages were
                // on disk but the app offered no way to open them.
                onOpenGallery = { gid, token ->
                    navController.navigate("reader/$gid/$token?offline=true")
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateToLogin = { navController.navigate("login") },
                onNavigateToReaderSettings = { navController.navigate("settings_reader") },
                onNavigateToAppearanceSettings = { navController.navigate("settings_appearance") },
                onNavigateToDownloadSettings = { navController.navigate("settings_download") },
                onNavigateToWebConfig = { navController.navigate("settings_web_config") },
                onNavigateToSearchSettings = { navController.navigate("settings_search") },
                onNavigateToAdvancedSettings = { navController.navigate("settings_advanced") },
                onNavigateToSecuritySettings = { navController.navigate("settings_security") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings_reader") {
            ReaderSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings_appearance") {
            AppearanceSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings_download") {
            DownloadSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings_web_config") {
            EhWebConfigScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings_search") {
            SearchSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings_advanced") {
            AdvancedSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings_security") {
            SecuritySettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "detail/{gid}/{token}?coverUrl={coverUrl}",
            arguments = listOf(
                navArgument("gid") { type = NavType.StringType },
                navArgument("token") { type = NavType.StringType },
                navArgument("coverUrl") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                }
            ),
            enterTransition = {
                fadeIn(animationSpec = tween(260)) + scaleIn(initialScale = 0.92f, animationSpec = tween(260))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(200))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.92f, animationSpec = tween(200))
            }
        ) { backStackEntry ->
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                val gid = backStackEntry.arguments?.getString("gid").orEmpty()
                val token = backStackEntry.arguments?.getString("token").orEmpty()
                val rawCover = backStackEntry.arguments?.getString("coverUrl").orEmpty()
                val coverUrl = if (rawCover.isNotEmpty()) {
                    try { URLDecoder.decode(rawCover, "UTF-8") } catch (_: Exception) { null }
                } else null
                GalleryDetailScreen(
                    gid = gid,
                    token = token,
                initialCoverUrl = coverUrl,
                onReadClick = { g, t ->
                    navController.navigate("reader/$g/$t")
                },
                onReadPageClick = { g, t, page ->
                    navController.navigate("reader/$g/$t?page=$page")
                },
                onViewMoreComments = { g, t ->
                    navController.navigate("comments/$g/$t")
                },
                onViewMoreThumbnails = { g, t ->
                    navController.navigate("thumbnails/$g/$t")
                },
                onTagSearch = { tagQuery ->
                    // URL-encode to safely transport "namespace:tag" through the route
                    val encoded = URLEncoder.encode(tagQuery, "UTF-8")
                    // Collapse any previously visited search screen before pushing a new
                    // one. Without this the stack grew without bound:
                    //   tag_search -> detail -> tag_search -> detail -> tag_search ...
                    // and every stale HomeScreen kept loading images in the background,
                    // which is what made the UI stutter and behave unpredictably.
                    navController.navigate("tag_search/$encoded") {
                        // Keep at most one search result screen alive at a time.
                        if (navController.currentDestination?.route?.startsWith("tag_search") == true) {
                            popUpTo("tag_search/{query}") { inclusive = true }
                        }
                        launchSingleTop = true
                        restoreState = false
                    }
                },
                onBack = { navController.popBackStack() }
            )
            }
        }

        composable(
            route = "comments/{gid}/{token}",
            arguments = listOf(
                navArgument("gid") { type = NavType.StringType },
                navArgument("token") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val gid = backStackEntry.arguments?.getString("gid").orEmpty()
            val token = backStackEntry.arguments?.getString("token").orEmpty()
            GalleryCommentsScreen(
                gid = gid,
                token = token,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "thumbnails/{gid}/{token}",
            arguments = listOf(
                navArgument("gid") { type = NavType.StringType },
                navArgument("token") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val gid = backStackEntry.arguments?.getString("gid").orEmpty()
            val token = backStackEntry.arguments?.getString("token").orEmpty()
            GalleryThumbnailsScreen(
                gid = gid,
                token = token,
                onThumbnailClick = { page ->
                    navController.navigate("reader/$gid/$token?page=$page")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "reader/{gid}/{token}?page={page}&offline={offline}",
            arguments = listOf(
                navArgument("gid") { type = NavType.StringType },
                navArgument("token") { type = NavType.StringType },
                navArgument("page") {
                    type = NavType.IntType
                    defaultValue = 0
                },
                navArgument("offline") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val gid = backStackEntry.arguments?.getString("gid").orEmpty()
            val token = backStackEntry.arguments?.getString("token").orEmpty()
            val page = backStackEntry.arguments?.getInt("page") ?: 0
            val offline = backStackEntry.arguments?.getBoolean("offline") ?: false
            GalleryReaderScreen(
                gid = gid,
                token = token,
                initialPage = page,
                offline = offline,
                onBack = { navController.popBackStack() }
            )
        }

        // Ensure other compose functions close their composition locals where needed, 
        // for simplicity we only apply the local provider to the ones supporting transitions.
        
        composable("login") { backStackEntry ->
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                LoginScreen(onBack = { navController.popBackStack() })
            }
        }
    }
        }
    }
}
