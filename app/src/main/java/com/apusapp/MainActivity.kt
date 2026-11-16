package com.apusapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apusapp.ui.components.FirstTimePermissionDialog
import com.apusapp.utils.NotificationSettingsManager
import com.apusapp.ui.screens.AppLockScreen
import com.apusapp.ui.screens.AuthScreen
import com.apusapp.ui.screens.MainScreen
import com.apusapp.ui.screens.AnimatedSplashScreen
import com.apusapp.ui.theme.PDFUtilityAppTheme
import com.apusapp.ui.viewmodel.AuthViewModel
import com.apusapp.ui.viewmodel.LanguageViewModel
import com.apusapp.ui.viewmodel.ThemeMode
import com.apusapp.ui.viewmodel.ThemeViewModel
import com.apusapp.data.model.AuthState
import com.apusapp.utils.AppLockManager
import com.apusapp.utils.AppLockType
import com.apusapp.utils.FileNamingManager
import com.apusapp.utils.HapticManager
import com.apusapp.utils.ScannerSettingsManager
import com.apusapp.utils.StorageLocationManager
import com.apusapp.utils.StoragePermissionManager
import com.apusapp.utils.StoragePermissionState

class MainActivity : FragmentActivity() {
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                AppLockManager.onScreenClosed()
            }
        }
    }

    private val incomingPdfState = androidx.compose.runtime.mutableStateOf<java.io.File?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingPdf(intent)
    }

    private fun handleIncomingPdf(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
            }
            else -> intent.data
        }
        if (uri == null) return

        try {
            var fileName = "Document.pdf"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIdx)?.let { fileName = it }
                }
            }
            if (fileName == "Document.pdf") {
                uri.lastPathSegment?.substringAfterLast('/')?.let {
                    if (it.endsWith(".pdf", ignoreCase = true)) fileName = it
                }
            }
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val temp = java.io.File(cacheDir, safeName)
            contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out -> input.copyTo(out) }
            }
            if (temp.exists() && temp.length() > 0) {
                incomingPdfState.value = temp
                com.apusapp.utils.RecentFilesManager.recordFile(this, temp)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }

        HapticManager.init(this)
        StorageLocationManager.init(this)
        FileNamingManager.init(this)
        AppLockManager.init(this)
        AppLockManager.applyPrivacyShield(this)
        ScannerSettingsManager.init(this)
        com.apusapp.utils.NotificationSettingsManager.init(this)
        com.apusapp.utils.NotificationHelper.init(this)
        com.apusapp.utils.FavoritesManager.init(this)
        StoragePermissionManager.checkPermission(this)
        
        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val languageViewModel: LanguageViewModel = viewModel()
            val authViewModel: com.apusapp.ui.viewmodel.AuthViewModel = viewModel(
                factory = com.apusapp.ui.viewmodel.AuthViewModel.provideFactory(this)
            )
            val themeMode by themeViewModel.themeMode.collectAsState()
            val authState by authViewModel.authState.collectAsState()
            val isSessionUnlocked by AppLockManager.isSessionUnlocked.collectAsState()
            val lockType by AppLockManager.lockTypeFlow.collectAsState()
            val permissionState by StoragePermissionManager.permissionStateFlow.collectAsState()
            var showSplashScreen by remember { mutableStateOf(true) }
            var showFirstTimePermissionDialog by remember {
                mutableStateOf(
                    permissionState != StoragePermissionState.GRANTED &&
                    !StoragePermissionManager.hasPromptedFirstTime(this@MainActivity) &&
                    // Notification pehle dikhao — storage dialog sirf tab initially true ho
                    // jab notification already prompted ho chuka ho
                    NotificationSettingsManager.hasPromptedFirstTime(this@MainActivity)
                )
            }
            LaunchedEffect(permissionState) {
                if (permissionState == StoragePermissionState.GRANTED) {
                    showFirstTimePermissionDialog = false
                }
            }

            val requestStorageLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { _ ->
                StoragePermissionManager.checkPermission(this@MainActivity)
            }

            val manageStorageLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                StoragePermissionManager.checkPermission(this@MainActivity)
            }

            val requestNotificationLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                NotificationSettingsManager.setNotificationEnabled(this@MainActivity, isGranted)
                NotificationSettingsManager.setPromptedFirstTime(this@MainActivity, true)
                if (permissionState == StoragePermissionState.DENIED &&
                    !StoragePermissionManager.hasPromptedFirstTime(this@MainActivity)) {
                    showFirstTimePermissionDialog = true
                }
            }

            fun requestAppPermissions() {
                StoragePermissionManager.setPromptedFirstTime(this@MainActivity, true)
                showFirstTimePermissionDialog = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        manageStorageLauncher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageStorageLauncher.launch(intent)
                    }
                } else {
                    val permissions = arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                    requestStorageLauncher.launch(permissions)
                }
            }

            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> false
            }

            PDFUtilityAppTheme(darkTheme = darkTheme, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isAuthNeeded = authState is com.apusapp.data.model.AuthState.Unauthenticated
                    val isLocked = !isSessionUnlocked && lockType != AppLockType.NONE

                    Crossfade(
                        targetState = showSplashScreen,
                        animationSpec = tween(350),
                        label = "SplashScreenCrossfade"
                    ) { isSplash ->
                        if (isSplash) {
                            AnimatedSplashScreen(
                                onSplashFinished = {
                                    showSplashScreen = false
                                }
                            )
                        } else {
                            Crossfade(
                                targetState = isAuthNeeded,
                                animationSpec = tween(300),
                                label = "AuthCrossfade"
                            ) { needsAuth ->
                        if (needsAuth) {
                            com.apusapp.ui.screens.AuthScreen(
                                authViewModel = authViewModel,
                                languageViewModel = languageViewModel,
                                onAuthSuccess = {},
                                onContinueAsGuest = {}
                            )
                        } else {
                            Crossfade(
                                targetState = isLocked,
                                animationSpec = tween(250),
                                label = "AppLockTransition"
                            ) { locked ->
                                if (locked) {
                                    AppLockScreen(
                                        languageViewModel = languageViewModel,
                                        onUnlocked = {
                                            AppLockManager.unlockSession()
                                        }
                                    )
                                } else {
                                    LaunchedEffect(Unit) {
                                        handleIncomingPdf(intent)
                                    }
                                    val externalPdfFile by incomingPdfState
                                    MainScreen(
                                        themeViewModel = themeViewModel,
                                        languageViewModel = languageViewModel,
                                        authViewModel = authViewModel,
                                        initialPdfFile = externalPdfFile
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val isReadyForHomePermissions = !showSplashScreen && !isAuthNeeded && !isLocked

            LaunchedEffect(isReadyForHomePermissions) {
                        if (isReadyForHomePermissions && !NotificationSettingsManager.hasPromptedFirstTime(this@MainActivity)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                NotificationSettingsManager.setNotificationEnabled(this@MainActivity, true)
                                NotificationSettingsManager.setPromptedFirstTime(this@MainActivity, true)
                                if (permissionState == StoragePermissionState.DENIED &&
                                    !StoragePermissionManager.hasPromptedFirstTime(this@MainActivity)) {
                                    showFirstTimePermissionDialog = true
                                }
                            }
                        }
                    }

                    if (isReadyForHomePermissions && showFirstTimePermissionDialog) {
                        FirstTimePermissionDialog(
                            languageViewModel = languageViewModel,
                            onAllow = {
                                requestAppPermissions()
                            },
                            onDeny = {
                                StoragePermissionManager.setPromptedFirstTime(this@MainActivity, true)
                                showFirstTimePermissionDialog = false
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        AppLockManager.onAppForegrounded()
        AppLockManager.applyPrivacyShield(this)
    }

    override fun onResume() {
        super.onResume()
        AppLockManager.onAppForegrounded()
        AppLockManager.applyPrivacyShield(this)
        StoragePermissionManager.checkPermission(this)
    }

    override fun onStop() {
        super.onStop()
        AppLockManager.onAppBackgrounded()
    }
}
