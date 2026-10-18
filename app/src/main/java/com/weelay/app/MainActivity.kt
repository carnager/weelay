package com.weelay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.weelay.app.relay.RelayClient
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import java.net.URLDecoder
import java.net.URLEncoder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.weelay.app.relay.ConnectionState
import com.weelay.app.ui.screens.BufferListScreen
import com.weelay.app.ui.screens.ChatScreen
import com.weelay.app.ui.screens.ConnectScreen
import com.weelay.app.ui.screens.SettingsScreen
import com.weelay.app.ui.theme.WeelayTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var relayClient: RelayClient

    private val pendingBuffer = mutableStateOf<String?>(null)
    private val deepLinkTrigger = mutableStateOf(0)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i("Weelay", "POST_NOTIFICATIONS granted=$granted")
        }

    /**
     * On Android 13+ POST_NOTIFICATIONS is a runtime permission. Without it the foreground service
     * still runs, but every highlight and private-message notification is silently dropped — so
     * ask for it up front rather than letting notifications appear broken.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleDeepLink(uri: Uri?) {
        Log.w("Weelay", "handleDeepLink: $uri")
        if (uri?.scheme == "weelay" && uri.host == "chat") {
            val raw = uri.encodedPath?.trimStart('/') ?: return
            val buffer = URLDecoder.decode(raw, "UTF-8")
            Log.w("Weelay", "Deep link buffer: $buffer")
            if (buffer.isNotBlank()) {
                pendingBuffer.value = buffer
                deepLinkTrigger.value++
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.w("Weelay", "onNewIntent: action=${intent.action} data=${intent.data}")
        handleDeepLink(intent.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The UI is always dark, so the system bars need light icons regardless of the system
        // theme; the default (auto) style draws dark icons over the dark top bar in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        ensureNotificationPermission()
        handleDeepLink(intent.data)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                relayClient.checkConnectionOnResume()
            }
        }
        setContent {
            WeelayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val trigger = deepLinkTrigger.value

                    // Handle deep link when app is already past connect screen
                    LaunchedEffect(trigger) {
                        val buffer = pendingBuffer.value ?: return@LaunchedEffect
                        val currentRoute = navController.currentDestination?.route
                        // Only handle here if we're past the connect screen
                        if (currentRoute != "connect") {
                            pendingBuffer.value = null
                            val encoded = URLEncoder.encode(buffer, "UTF-8")
                            Log.w("Weelay", "Deep link navigating to chat/$encoded")
                            navController.navigate("chat/$encoded")
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "connect",
                        enterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { it / 4 },
                                animationSpec = tween(200)
                            ) + fadeIn(animationSpec = tween(200))
                        },
                        exitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { -it / 4 },
                                animationSpec = tween(200)
                            ) + fadeOut(animationSpec = tween(150))
                        },
                        popEnterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { -it / 4 },
                                animationSpec = tween(200)
                            ) + fadeIn(animationSpec = tween(200))
                        },
                        popExitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { it / 4 },
                                animationSpec = tween(200)
                            ) + fadeOut(animationSpec = tween(150))
                        }
                    ) {
                        composable("connect") {
                            ConnectScreen(
                                onConnected = {
                                    val deepLink = pendingBuffer.value
                                    if (deepLink != null) {
                                        pendingBuffer.value = null
                                        val encoded = URLEncoder.encode(deepLink, "UTF-8")
                                        navController.navigate("buffers") {
                                            popUpTo("connect") { inclusive = true }
                                        }
                                        navController.navigate("chat/$encoded")
                                    } else {
                                        navController.navigate("buffers") {
                                            popUpTo("connect") { inclusive = true }
                                        }
                                    }
                                }
                            )
                        }
                        composable("buffers") {
                            BufferListScreen(
                                onBufferClick = { bufferFullName ->
                                    val encoded = URLEncoder.encode(bufferFullName, "UTF-8")
                                    navController.navigate("chat/$encoded")
                                },
                                onHighlightClick = { bufferFullName, messageId ->
                                    val encoded = URLEncoder.encode(bufferFullName, "UTF-8")
                                    navController.navigate("chat/$encoded?messageId=$messageId")
                                },
                                onSettingsClick = {
                                    navController.navigate("settings")
                                }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onDisconnected = {
                                    navController.navigate("connect") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(
                            route = "chat/{bufferFullName}?messageId={messageId}",
                            arguments = listOf(
                                navArgument("bufferFullName") { type = NavType.StringType },
                                navArgument("messageId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                }
                            )
                        ) { backStackEntry ->
                            val bufferFullName = URLDecoder.decode(
                                backStackEntry.arguments?.getString("bufferFullName") ?: "", "UTF-8"
                            )
                            val targetMessageId = backStackEntry.arguments?.getLong("messageId") ?: -1L
                            ChatScreen(
                                bufferFullName = bufferFullName,
                                targetMessageId = if (targetMessageId != -1L) targetMessageId else null,
                                onBack = { navController.popBackStack() },
                                onNavigateToBuffer = { targetBuffer ->
                                    val encoded = URLEncoder.encode(targetBuffer, "UTF-8")
                                    navController.navigate("chat/$encoded")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
