package com.example

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.example.R
import com.example.data.AppEntity
import com.example.data.DownloadEntity
import com.example.data.SubmissionEntity
import com.example.data.UserEntity
import com.example.utils.ApkInstaller
import com.example.utils.StorageManager
import com.example.utils.NotificationHelper
import com.example.viewmodel.StoreViewModel
import java.io.File
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

val LocalUninstallEnabled = compositionLocalOf { false }

class MainActivity : ComponentActivity() {
    private var packageReceiver: android.content.BroadcastReceiver? = null
    private var lastInstalledAppsRefresh = 0L

    private val viewModel: StoreViewModel by viewModels {
        StoreViewModel.Factory(applicationContext as Application)
    }

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastInstalledAppsRefresh > 30_000L) {
            viewModel.refreshInstalledApps()
            lastInstalledAppsRefresh = now
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission enabled for Dark Store!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications disabled. Standard tray updates will be hidden.", Toast.LENGTH_LONG).show()
        }
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        if (readGranted) {
            Toast.makeText(this, "System storage access granted!", Toast.LENGTH_SHORT).show()
        }
        com.example.widget.DarkStoreWidget.updateAllWidgets(this)
    }

    override fun onDestroy() {
        packageReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Optimize Coil Image Loading cache to maximize interface smoothness
        try {
            val imageLoader = ImageLoader.Builder(applicationContext)
                .memoryCache {
                    MemoryCache.Builder(applicationContext)
                        .maxSizePercent(0.25) // use up to 25% of available RAM 
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(applicationContext.cacheDir.resolve("image_cache"))
                        .maxSizePercent(0.04) // reasonable portion of disk cache
                        .build()
                }
                .crossfade(true)
                .respectCacheHeaders(false) // reuse local cache ignoring remote HTTP override headers
                .build()
            coil.Coil.setImageLoader(imageLoader)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to configure custom Coil cache: ${e.message}")
        }
        
        NotificationHelper.createNotificationChannel(applicationContext)

        // Start background real-time synchronization service
        try {
            val serviceIntent = Intent(this, com.example.utils.StoreBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed starting StoreBackgroundService: ${e.message}")
        }

        // Request modern POST_NOTIFICATIONS permission gracefully for Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Request storage system permissions for accurate storage capacity reading
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            val storageNeeded = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                storageNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                storageNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (storageNeeded.isNotEmpty()) {
                requestStoragePermissionLauncher.launch(storageNeeded.toTypedArray())
            }
        }

        enableEdgeToEdge()
        
        // Register dynamic package installation BroadcastReceiver
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent?) {
                val packageName = intent?.data?.schemeSpecificPart
                if (packageName != null) {
                    android.util.Log.d("MainActivity", "App update detected: ${intent.action} for $packageName")
                    viewModel.refreshMarketplace()
                    viewModel.refreshInstalledApps()
                    com.example.widget.DarkStoreWidget.updateAllWidgets(context)

                    if (intent.action == Intent.ACTION_PACKAGE_ADDED) {
                        val appsList = viewModel.apps.value
                        val matchedApp = appsList.find { it.packageName == packageName }
                        val appName = matchedApp?.name ?: "Application"

                        // Clear download record from the download queue so it doesn't linger
                        lifecycleScope.launch {
                            matchedApp?.let {
                                viewModel.cancelDownload(it.id)
                            }
                        }

                        // Send high-priority installation success notification!
                        NotificationHelper.showInstallSuccess(
                            context,
                            appName,
                            packageName.hashCode()
                        )
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            packageReceiver = receiver
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to register package dynamic receiver: ${e.message}", e)
        }

        // Force widget update with real storage figures on app startup
        com.example.widget.DarkStoreWidget.updateAllWidgets(applicationContext)

        setContent {
            val context = LocalContext.current
            var showSplash by remember { mutableStateOf(true) }
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
            val isAmoledMode by viewModel.isAmoledMode.collectAsStateWithLifecycle()
            val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
            val isTermsAccepted by viewModel.isTermsAccepted.collectAsStateWithLifecycle()
            val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
            val userName by viewModel.userName.collectAsStateWithLifecycle()

            // State for update checks
            var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }

            // Automatic background update checking on launch
            LaunchedEffect(Unit) {
                val prefs = context.getSharedPreferences("dark_store_pref", android.content.Context.MODE_PRIVATE)
                val cachedCode = prefs.getInt("cached_latest_version_code", 0)
                val cachedName = prefs.getString("cached_latest_version_name", "") ?: ""
                val cachedUrl = prefs.getString("cached_apk_download_url", "") ?: ""
                val cachedTitle = prefs.getString("cached_update_title", "") ?: ""
                val cachedMessage = prefs.getString("cached_update_message", "") ?: ""
                val cachedForce = prefs.getBoolean("cached_force_update", false)
                
                val currentCode = getInstalledVersionCode(context)
                val currentName = getInstalledVersionName(context)
                
                // If a cached forced update is already known, immediately pre-set state
                val isCachedUpdateNeeded = (cachedCode > currentCode) || 
                        (cachedName.isNotBlank() && cachedName != currentName)
                if (isCachedUpdateNeeded && cachedForce) {
                    updateState = UpdateState.UpdateRequired(
                        latestVersionCode = cachedCode,
                        latestVersionName = cachedName,
                        apkDownloadUrl = cachedUrl,
                        updateTitle = cachedTitle,
                        updateMessage = cachedMessage,
                        forceUpdate = cachedForce,
                        offlineMode = !isNetworkAvailable(context)
                    )
                }
                
                try {
                    val config = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.example.data.FirebaseService.fetchUpdateConfig()
                    }
                    if (config != null) {
                        // Update cache
                        prefs.edit().apply {
                            putInt("cached_latest_version_code", config.latestVersionCode)
                            putString("cached_latest_version_name", config.latestVersionName)
                            putString("cached_apk_download_url", config.apkDownloadUrl)
                            putString("cached_update_title", config.updateTitle)
                            putString("cached_update_message", config.updateMessage)
                            putBoolean("cached_force_update", config.forceUpdate)
                            apply()
                        }
                        
                        val isUpdateNeeded = (config.latestVersionCode > currentCode) || 
                                (config.latestVersionName.isNotBlank() && config.latestVersionName != currentName)
                        if (isUpdateNeeded) {
                            updateState = UpdateState.UpdateRequired(
                                latestVersionCode = config.latestVersionCode,
                                latestVersionName = config.latestVersionName,
                                apkDownloadUrl = config.apkDownloadUrl,
                                updateTitle = config.updateTitle,
                                updateMessage = config.updateMessage,
                                forceUpdate = config.forceUpdate,
                                offlineMode = false
                            )
                        } else {
                            updateState = UpdateState.NoUpdateNeeded
                        }
                    } else {
                        // Offline or error loading update config
                        if (isCachedUpdateNeeded) {
                            if (cachedForce) {
                                updateState = UpdateState.UpdateRequired(
                                    latestVersionCode = cachedCode,
                                    latestVersionName = cachedName,
                                    apkDownloadUrl = cachedUrl,
                                    updateTitle = cachedTitle,
                                    updateMessage = cachedMessage,
                                    forceUpdate = cachedForce,
                                    offlineMode = true
                                )
                            } else {
                                updateState = UpdateState.NoUpdateNeeded
                            }
                        } else {
                            updateState = UpdateState.NoUpdateNeeded
                        }
                    }
                } catch (e: Exception) {
                    if (isCachedUpdateNeeded && cachedForce) {
                        updateState = UpdateState.UpdateRequired(
                            latestVersionCode = cachedCode,
                            latestVersionName = cachedName,
                            apkDownloadUrl = cachedUrl,
                            updateTitle = cachedTitle,
                            updateMessage = cachedMessage,
                            forceUpdate = cachedForce,
                            offlineMode = true
                        )
                    } else {
                        updateState = UpdateState.NoUpdateNeeded
                    }
                }
            }

            // Fade out splash shortly
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2200)
                showSplash = false
            }

            // Centralized Google Play theme controller
            MaterialTheme(
                colorScheme = if (isDarkMode) darkColorScheme(
                    primary = Color(0xFF34D399),
                    background = if (isAmoledMode) Color.Black else Color(0xFF111111),
                    surface = if (isAmoledMode) Color.Black else Color(0xFF1F1F1F)
                ) else lightColorScheme(
                    primary = Color(0xFF01875F),
                    background = Color(0xFFF8F9FA),
                    surface = Color(0xFFFFFFFF)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (isDarkMode) (if (isAmoledMode) Color.Black else Color(0xFF111111)) else Color(0xFFF8F9FA)
                ) {
                    AnimatedContent(
                        targetState = showSplash,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(400)) togetherWith
                            fadeOut(animationSpec = tween(400))
                        },
                        label = "SplashToDashboard"
                    ) { isSplash ->
                        if (isSplash) {
                            PlayStoreSplashScreen(onSkip = { showSplash = false })
                        } else {
                            when (updateState) {
                                is UpdateState.Checking -> {
                                    // Lightweight loading screen
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF0B0D12)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator(color = Color(0xFF34D399))
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = "Checking for updates...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                                is UpdateState.UpdateRequired -> {
                                    UpdateRequiredScreen(
                                        update = updateState as UpdateState.UpdateRequired,
                                        context = context,
                                        onSkip = {
                                            updateState = UpdateState.NoUpdateNeeded
                                        }
                                    )
                                }
                                is UpdateState.NoUpdateNeeded -> {
                                    if (!isLoggedIn) {
                                        com.example.view.AuthScreen(
                                            viewModel = viewModel
                                        )
                                    } else if (!isTermsAccepted && userEmail.isNotBlank() && userEmail != "guest@darkroot.io") {
                                        com.example.view.TermsAgreementDialog(
                                            viewModel = viewModel,
                                            onAccept = {
                                                viewModel.markTermsAcceptedForEmail(userEmail, userName)
                                            }
                                        )
                                    } else {
                                        PlayStoreMainDashboard(
                                            viewModel = viewModel,
                                            isDarkMode = isDarkMode,
                                            onThemeToggle = { viewModel.setDarkMode(!isDarkMode) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// 1. CHIC GOOGLE PLAY SPLASH SCREEN
// ========================================================
@Composable
fun PlayStoreSplashScreen(onSkip: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation_angle"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12)) // Matrix dark slate void
            .clickable { onSkip() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(240.dp)
            ) {
                // Outer Alarm Ring 1: Soft radial glow background Aura
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(1.0f)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF00AAFF).copy(alpha = 0.5f), Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                        .alpha(glowAlpha)
                )

                // Outer Alarm Ring 2: Broadcast/pulse wave circle (Thin tech cyan)
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .scale(1.05f)
                        .border(
                            width = 1.dp,
                            color = Color(0xFF00AAFF).copy(alpha = glowAlpha),
                            shape = CircleShape
                        )
                )

                // Outer Alarm Ring 3: Broadcast wave inner circle
                Box(
                    modifier = Modifier
                        .size(175.dp)
                        .scale(0.9f)
                        .border(
                            width = 1.5.dp,
                            color = Color(0xFF00D2FF).copy(alpha = glowAlpha * 1.3f),
                            shape = CircleShape
                        )
                )

                // Rotating Alarm Clock/Timer dial ticks (with absolutely no solid card/box background)
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .rotate(rotationAngle)
                        .drawBehind {
                            val strokeWidth = 3f
                            val color = Color(0xFF00AAFF).copy(alpha = 0.85f)
                            val numTicks = 32
                            val radius = size.minDimension / 2
                            val tickLength = 12f
                            for (i in 0 until numTicks) {
                                val angleDeg = (i * 360f / numTicks)
                                val angleRad = Math.toRadians(angleDeg.toDouble())
                                val startX = (center.x + (radius - tickLength) * Math.cos(angleRad)).toFloat()
                                val startY = (center.y + (radius - tickLength) * Math.sin(angleRad)).toFloat()
                                val endX = (center.x + radius * Math.cos(angleRad)).toFloat()
                                val endY = (center.y + radius * Math.sin(angleRad)).toFloat()
                                drawLine(
                                    color = color,
                                    start = androidx.compose.ui.geometry.Offset(startX, startY),
                                    end = androidx.compose.ui.geometry.Offset(endX, endY),
                                    strokeWidth = strokeWidth
                                )
                            }
                        }
                )

                // Beautiful brand logo centered with transparent surrounding background
                Box(
                    modifier = Modifier
                        .size(105.dp)
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(listOf(Color(0xFF007FFF), Color(0xFF00E5FF))),
                            shape = CircleShape
                        )
                        .padding(4.dp), // Space inside the neon glowing ring
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_app_logo_new),
                        contentDescription = "Dark Store App Logo Symbol",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape), // Perfect circular crop
                        contentScale = ContentScale.Fit // Prevent stretching/distortion of aspect ratio
                    )
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Premium DarkRoot Store main title
            Text(
                text = "DarkRoot Store",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 1.2.sp
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Premium edition description
            Text(
                text = "MODULAR DISCOVERY & ARCHIVE ACCESS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.8.sp,
                    color = Color(0xFF00D2FF)
                )
            )

            Spacer(modifier = Modifier.height(48.dp))

            CircularProgressIndicator(
                color = Color(0xFF00AAFF),
                strokeWidth = 3.dp,
                modifier = Modifier.size(24.dp)
            )
        }

        Text(
            text = "DARKROOT",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Color.Gray.copy(alpha = 0.8f),
                letterSpacing = 1.5.sp
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
        )
    }
}

// ========================================================
// 2. SHIMMER & ELEGANT IMAGE LOADER (NO SPINNER SYSTEM)
// ========================================================
@Composable
fun ShimmerBrush(showShimmer: Boolean = true, targetValue: Float = 1000f): Brush {
    return if (showShimmer) {
        val shimmerColors = listOf(
            Color.LightGray.copy(alpha = 0.6f),
            Color.LightGray.copy(alpha = 0.2f),
            Color.LightGray.copy(alpha = 0.6f),
        )

        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
        val translateAnim = transition.animateFloat(
            initialValue = 0f,
            targetValue = targetValue,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(durationMillis = 1000, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart
            ),
            label = "shimmer_anim"
        )

        Brush.linearGradient(
            colors = shimmerColors,
            start = androidx.compose.ui.geometry.Offset.Zero,
            end = androidx.compose.ui.geometry.Offset(x = translateAnim.value, y = translateAnim.value)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Transparent),
            start = androidx.compose.ui.geometry.Offset.Zero,
            end = androidx.compose.ui.geometry.Offset.Zero
        )
    }
}

@Composable
fun ElegantImageLoader(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    coil.compose.SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ShimmerBrush())
            )
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF374151), Color(0xFF1F2937))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "Failed to load",
                    tint = Color.Gray.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    )
}

// ========================================================
// 3. STUNNING DYNAMIC LOGO HANDLER
// ========================================================
@Composable
fun AppLogo(
    logoUrl: String,
    appName: String,
    packageName: String,
    modifier: Modifier = Modifier
) {
    // Override general duplicate placeholders
    val actualUrl = remember(logoUrl, packageName) {
        val hasGenericPlaceholder = logoUrl.isBlank() || 
                                    logoUrl.contains("game-logo.png") || 
                                    logoUrl.contains("img_app_icon") ||
                                    logoUrl.contains("img_app_logo_new")
        
        when {
            hasGenericPlaceholder -> {
                when {
                    packageName.contains("brave", ignoreCase = true) -> 
                        "https://raw.githubusercontent.com/brave/brave-browser/master/assets/brave-logo-color.png"
                    packageName.contains("emulator", ignoreCase = true) || packageName.contains("retro", ignoreCase = true) -> 
                        "https://cdn-icons-png.flaticon.com/512/566/566373.png"
                    packageName.contains("aero", ignoreCase = true) || packageName.contains("music", ignoreCase = true) -> 
                        "https://cdn-icons-png.flaticon.com/512/4612/4612571.png"
                    else -> ""
                }
            }
            else -> logoUrl
        }
    }

    if (actualUrl.isNotEmpty()) {
        val context = LocalContext.current
        val imageRequest = remember(actualUrl, context) {
            coil.request.ImageRequest.Builder(context)
                .data(actualUrl)
                .crossfade(true)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .placeholder(R.drawable.img_app_logo_new)
                .error(R.drawable.img_app_logo_new)
                .fallback(R.drawable.img_app_logo_new)
                .build()
        }
        ElegantImageLoader(
            model = imageRequest,
            contentDescription = "$appName Logo",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        // Procedurally render an distinct eye-catching logo based on name hashing
        val logoInfo = remember(appName) {
            val nameHash = Math.abs(appName.hashCode())
            val firstChar = appName.trim().take(1).uppercase()
            val gradient = when (nameHash % 5) {
                0 -> Brush.linearGradient(listOf(Color(0xFF0F9D58), Color(0xFF34D399))) // Green
                1 -> Brush.linearGradient(listOf(Color(0xFF4285F4), Color(0xFF64B5F6))) // Blue
                2 -> Brush.linearGradient(listOf(Color(0xFFEA4335), Color(0xFFFF8A80))) // Red
                3 -> Brush.linearGradient(listOf(Color(0xFFFBBC05), Color(0xFFFFD54F))) // Yellow
                else -> Brush.linearGradient(listOf(Color(0xFF9C27B0), Color(0xFFBA68C8))) // Purple
            }
            Pair(firstChar, gradient)
        }
        val firstChar = logoInfo.first
        val gradient = logoInfo.second

        Box(
            modifier = modifier
                .background(gradient)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = firstChar,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ========================================================
// 3. MAIN STORE DASHBOARD LAYOUT
// ========================================================
@Composable
fun PlayStoreMainDashboard(
    viewModel: StoreViewModel,
    isDarkMode: Boolean,
    onThemeToggle: () -> Unit
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val unfilteredApps by viewModel.unfilteredApps.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val installedPackages by viewModel.installedPackages.collectAsStateWithLifecycle()
    val installedAppsInfo by viewModel.installedAppsInfo.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val isAmoledMode by viewModel.isAmoledMode.collectAsStateWithLifecycle()
    val userRole by viewModel.userRole.collectAsStateWithLifecycle()
    val userUid by viewModel.userUid.collectAsStateWithLifecycle()
    val submissions by viewModel.submissions.collectAsStateWithLifecycle()
    val isTermsAccepted by viewModel.isTermsAccepted.collectAsStateWithLifecycle()
    val isEcosystemPolicyAccepted by viewModel.isEcosystemPolicyAccepted.collectAsStateWithLifecycle()
    val devName by viewModel.devName.collectAsStateWithLifecycle()
    val isDeveloper by viewModel.isDeveloper.collectAsStateWithLifecycle()

    val isAdmin = userRole == "admin" || userEmail.equals("davidstha900@gmail.com", ignoreCase = true) || userUid == "JN4BPhEKBBRUb5hpMdQJQmRrjiq1"
    val context = LocalContext.current

    var isUninstallEnabled by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isUninstallEnabled = com.example.utils.ApkInstaller.isDeviceAdminActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var activeTab by remember { mutableStateOf("Apps") } // Apps, Games, Library, Console
    var showDetailsApp by remember { mutableStateOf<AppEntity?>(null) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showUserAppSubmissionForm by remember { mutableStateOf(false) }
    var appSubmittingUpdateFor by remember { mutableStateOf<com.example.data.SubmissionEntity?>(null) }

    // Premium & Upcoming states
    val purchasedAppIds by viewModel.purchasedAppIds.collectAsStateWithLifecycle()
    val preRegisteredAppIds by viewModel.preRegisteredAppIds.collectAsStateWithLifecycle()
    var purchaseAppTarget by remember { mutableStateOf<AppEntity?>(null) }

    val notices by viewModel.notices.collectAsStateWithLifecycle()
    var activeNoticeToShow by remember { mutableStateOf<com.example.data.NoticeEntity?>(null) }

    val currentActivity = context as? android.app.Activity
    LaunchedEffect(notices, currentActivity?.intent) {
        val currentUnfilteredApps = viewModel.unfilteredApps.value
        val viewNoticeId = currentActivity?.intent?.getStringExtra("view_notice_id")
        if (viewNoticeId != null) {
            val foundNotice = notices.find { it.id == viewNoticeId }
            if (foundNotice != null) {
                activeNoticeToShow = foundNotice
                currentActivity.intent?.removeExtra("view_notice_id")
            }
        }

        val openScreen = currentActivity?.intent?.getStringExtra("open_screen")
        val appId = currentActivity?.intent?.getStringExtra("app_id")
        
        if (openScreen != null) {
            when (openScreen) {
                "submissions" -> {
                    if (isLoggedIn && isAdmin) {
                        activeTab = "Console"
                    }
                }
                "app_details", "updates" -> {
                    if (appId != null) {
                        val foundApp = currentUnfilteredApps.find { it.id == appId || it.packageName == appId }
                        if (foundApp != null) {
                            showDetailsApp = foundApp
                        }
                    }
                }
                "announcements" -> {
                    activeTab = "Settings"
                }
            }
            currentActivity.intent?.removeExtra("open_screen")
            currentActivity.intent?.removeExtra("app_id")
        }
    }

    // Refresh installed app info from PackageManager when details dialog is shown
    LaunchedEffect(showDetailsApp) {
        if (showDetailsApp != null) {
            viewModel.refreshInstalledApps()
        }
    }

    // Automatically switch target if logged out of console session or not an admin
    LaunchedEffect(isLoggedIn, isAdmin) {
        if ((!isLoggedIn || !isAdmin) && activeTab == "Console") {
            activeTab = "Apps"
        }
    }

    // Dynamic color assignments
    val backgroundColor = if (isDarkMode) (if (isAmoledMode) Color.Black else Color(0xFF111111)) else Color(0xFFF8F9FA)
    val cardBgColor = if (isDarkMode) (if (isAmoledMode) Color.Black else Color(0xFF1E1E1E)) else Color(0xFFFFFFFF)
    val cardBorderColor = if (isDarkMode) (if (isAmoledMode) Color(0xFF1A1A1A) else Color(0xFF2A2A2A)) else Color(0xFFE0E0E0)
    val textPrimary = if (isDarkMode) Color(0xFFE8EAED) else Color(0xFF202124)
    val textSecondary = if (isDarkMode) Color(0xFF9AA0A6) else Color(0xFF5F6368)
    val accentGreen = if (isDarkMode) Color(0xFF34D399) else Color(0xFF01875F)

    // Store review ratings override map locally to provide real updates
    var localAppRatings by remember { mutableStateOf(mapOf<String, Pair<String, Int>>()) }

    val onAppClick = remember {
        { app: AppEntity -> showDetailsApp = app }
    }
    
    val onActionClick = remember(viewModel, context) {
        { app: AppEntity ->
            val isCurrentlyInstalled = viewModel.installedAppsInfo.value.containsKey(app.packageName)
            handleAppActionButton(app, isCurrentlyInstalled, viewModel, context)
        }
    }

    val onCancelClick = remember(viewModel) {
        { id: String -> viewModel.cancelDownload(id) }
    }

    val onInstallClick = remember(context) {
        { dl: DownloadEntity ->
            if (dl.localFilePath != null) {
                ApkInstaller.installApk(context, File(dl.localFilePath))
            }
        }
    }

    CompositionLocalProvider(LocalUninstallEnabled provides isUninstallEnabled) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        bottomBar = {
            // Elegant Material 3 bottom navigation bar replicated from Google Play Store
            NavigationBar(
                containerColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFFFFFFF),
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                val tabs = mutableListOf(
                    Triple("Games", "Games", Icons.Default.PlayArrow),
                    Triple("Apps", "Apps", Icons.Default.Home),
                    Triple("Library", "My Library", Icons.Default.List)
                ).apply {
                    if (isLoggedIn && isAdmin) {
                        add(Triple("Console", "Dev Portal", Icons.Default.Build))
                    }
                    add(Triple("Profile", "Profile", Icons.Default.AccountCircle))
                    add(Triple("Settings", "Settings", Icons.Default.Settings))
                }
                tabs.forEach { (tabId, label, icon) ->
                    NavigationBarItem(
                        selected = activeTab == tabId,
                        onClick = { 
                            activeTab = tabId
                            if (tabId == "Games") {
                                viewModel.selectCategory("Games")
                            } else if (tabId == "Apps" && selectedCategory == "Games") {
                                viewModel.selectCategory("All")
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = accentGreen,
                            selectedTextColor = accentGreen,
                            indicatorColor = if (isDarkMode) Color(0xFF2E2E2E) else Color(0xFFE6F4EA),
                            unselectedIconColor = textSecondary,
                            unselectedTextColor = textSecondary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(backgroundColor)
        ) {
            // Check internet status and active announcements
            val isOnline by viewModel.isInternetAvailable.collectAsStateWithLifecycle()
            val announcements = notices.filter { it.targetAppId == "critical_announcement" }

            AnimatedVisibility(
                visible = !isOnline,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFEF5350))
                        .padding(vertical = 8.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Offline icon",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "No internet connection. Viewing offline database.",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Top alert announcements in red color and danger symbol
            announcements.forEach { announcement ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFC62828)) // Rich solid classic warning red
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)))
                        .padding(vertical = 4.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val urlPattern = """https?://[^\s]+""".toRegex()
                    val detectedUrl = urlPattern.find(announcement.message)?.value 
                        ?: urlPattern.find(announcement.title)?.value
 
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Critical announcement banner icon",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = announcement.title.uppercase(),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = announcement.message,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 8.5.sp,
                                lineHeight = 11.sp
                            )
                        }

                        if (detectedUrl != null) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Card(
                                onClick = {
                                    try {
                                        val uri = android.net.Uri.parse(detectedUrl)
                                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                            setPackage("com.android.chrome")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val uri = android.net.Uri.parse(detectedUrl)
                                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (ex: Exception) {
                                            android.widget.Toast.makeText(context, "No web browser found.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFFC62828)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward,
                                        contentDescription = "Redirect icon",
                                        modifier = Modifier.size(11.dp),
                                        tint = Color(0xFFC62828)
                                    )
                                    Text(
                                        text = "CHROME",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.3.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. Google Play Styled Search Card Header
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .shadow(elevation = 2.dp, shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFFFFFFF)
                ),
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search icon",
                        tint = textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(10.dp))
                    
                    // Compact dynamic Text Field beautifully blended into search bar with zero unnecessary padding
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { text -> viewModel.updateSearchQuery(text) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_field")
                            .padding(vertical = 8.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = textPrimary,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(textPrimary),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search apps & games...",
                                        color = textSecondary,
                                        fontSize = 14.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.updateSearchQuery("") },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Compact Authentic Profile Avatar button on Google Search bar
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF34D399), Color(0xFF4285F4))))
                            .clickable { activeTab = "Profile" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = userName.trim().take(1).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 2. Discover Content Board based on selected Navigation Tab with smooth GPU-accelerated animated transitions to prevent lagging
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                },
                label = "dashboard_tab_transition_animation",
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { targetTab ->
                when (targetTab) {
                    "Games" -> {
                        DiscoveryTabContent(
                            isDarkMode = isDarkMode,
                            category = "Games",
                            apps = apps,
                            downloads = downloads,
                            installedAppsInfo = installedAppsInfo,
                            installedPackages = installedPackages,
                            isRefreshing = isRefreshing,
                            localAppRatings = localAppRatings,
                            accentGreen = accentGreen,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            onAppClick = onAppClick,
                            purchasedAppIds = purchasedAppIds,
                            preRegisteredAppIds = preRegisteredAppIds,
                            onBuyPremiumClick = { purchaseAppTarget = it },
                            onPreRegisterClick = { app ->
                                viewModel.preRegisterApp(app.id)
                                Toast.makeText(context, "Successfully Pre-registered for ${app.name}! You will be notified when this upcoming software goes live.", Toast.LENGTH_LONG).show()
                            },
                            onActionClick = onActionClick
                        )
                    }
                    "Apps" -> {
                        // Apps board with category pills overlay
                        DiscoveryTabContent(
                            isDarkMode = isDarkMode,
                            category = selectedCategory,
                            apps = apps,
                            downloads = downloads,
                            installedAppsInfo = installedAppsInfo,
                            installedPackages = installedPackages,
                            isRefreshing = isRefreshing,
                            localAppRatings = localAppRatings,
                            accentGreen = accentGreen,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            onAppClick = onAppClick,
                            showPillNavbar = true,
                            selectedPill = selectedCategory,
                            onPillSelect = { viewModel.selectCategory(it) },
                            purchasedAppIds = purchasedAppIds,
                            preRegisteredAppIds = preRegisteredAppIds,
                            onBuyPremiumClick = { purchaseAppTarget = it },
                            onPreRegisterClick = { app ->
                                viewModel.preRegisterApp(app.id)
                                Toast.makeText(context, "Successfully Pre-registered for ${app.name}! You will be notified when this upcoming software goes live.", Toast.LENGTH_LONG).show()
                            },
                            onActionClick = onActionClick
                        )
                    }
                    "Library" -> {
                        LibraryTabContent(
                            apps = apps,
                            downloads = downloads,
                            installedAppsInfo = installedAppsInfo,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            accentGreen = accentGreen,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            onAppClick = onAppClick,
                            onCancelClick = onCancelClick,
                            onInstallClick = onInstallClick,
                            onActionClick = onActionClick
                        )
                    }
                    "Console" -> {
                        if (isAdmin) {
                            ConsoleTabContent(
                                viewModel = viewModel,
                                apps = unfilteredApps,
                                accentGreen = accentGreen,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                cardBgColor = cardBgColor,
                                cardBorderColor = cardBorderColor,
                                userEmail = userEmail,
                                onShowAppDetails = { app -> showDetailsApp = app }
                            )
                        }
                    }
                    "Profile" -> {
                        ProfileTabContent(
                            viewModel = viewModel,
                            isDarkMode = isDarkMode,
                            isLoggedIn = isLoggedIn,
                            userName = userName,
                            userEmail = userEmail,
                            submissions = remember(submissions, userEmail) {
                                submissions.filter { it.submittedBy.equals(userEmail, ignoreCase = true) }
                            },
                            onTriggerSubmitForm = { showUserAppSubmissionForm = true },
                            onRefreshSubmissions = { viewModel.refreshSubmissions() },
                            onLogin = { email, name -> viewModel.loginWithGoogle(email, name) },
                            onLogout = { viewModel.logout() },
                            onThemeToggle = onThemeToggle,
                            onUpdateDeveloperName = { viewModel.updateDeveloperName(it) },
                            onRequestUpdate = { appSubmittingUpdateFor = it },
                            onShowAppDetails = { app -> showDetailsApp = app },
                            accentGreen = accentGreen,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor
                        )
                    }
                    "Settings" -> {
                        SettingsTabContent(
                            isDarkMode = isDarkMode,
                            onThemeToggle = onThemeToggle,
                            viewModel = viewModel,
                            accentGreen = accentGreen,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor
                        )
                    }
                }
            }
        }
    }

    // 3. Dynamic Account Dialog Trigger (replaced by full-screen Profile tab panel)

    if (showUserAppSubmissionForm) {
        AddNewAppForm(
            isForAdmin = isAdmin,
            userEmail = userEmail,
            defaultDeveloperName = devName,
            onDismiss = { showUserAppSubmissionForm = false },
            onSubmit = { appData ->
                if (isAdmin) {
                    viewModel.addOrUpdateAppInCatalog(appData) { success ->
                        if (success) {
                            Toast.makeText(context, "Direct deployment committed!", Toast.LENGTH_SHORT).show()
                            showUserAppSubmissionForm = false
                            viewModel.refreshMarketplace(force = true)
                        } else {
                            Toast.makeText(context, "Transmission error.", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    viewModel.submitAppForReview(
                        name = appData.name,
                        packageName = appData.packageName,
                        description = appData.description,
                        apkUrl = appData.apkUrl,
                        screenshots = appData.screenshots,
                        logo = appData.logo,
                        category = appData.category,
                        version = appData.version,
                        hasAds = appData.hasAds
                    ) { success, msg ->
                        Toast.makeText(context, msg ?: "Submission dispatched", Toast.LENGTH_SHORT).show()
                        if (success) showUserAppSubmissionForm = false
                    }
                }
            }
        )
    }

    if (appSubmittingUpdateFor != null) {
        val originalSub = appSubmittingUpdateFor!!
        val mappedSubApp = AppEntity(
            id = originalSub.id,
            name = originalSub.name,
            developer = originalSub.developer,
            version = originalSub.version,
            size = "18 MB",
            category = originalSub.category,
            rating = "4.8",
            description = originalSub.description,
            logo = originalSub.logo,
            screenshots = originalSub.screenshots,
            apkUrl = originalSub.apkUrl,
            packageName = originalSub.packageName,
            isFeatured = false,
            isPopular = true,
            isRecent = true,
            versionCode = 1,
            isApproved = true,
            submittedBy = originalSub.submittedBy,
            hasAds = originalSub.hasAds
        )
        AddNewAppForm(
            existingApp = mappedSubApp,
            isForAdmin = isAdmin,
            userEmail = userEmail,
            defaultDeveloperName = devName,
            onDismiss = { appSubmittingUpdateFor = null },
            onSubmit = { appData ->
                viewModel.submitAppForReview(
                    name = appData.name,
                    packageName = appData.packageName,
                    description = appData.description,
                    apkUrl = appData.apkUrl,
                    screenshots = appData.screenshots,
                    logo = appData.logo,
                    category = appData.category,
                    version = appData.version,
                    hasAds = appData.hasAds
                ) { success, msg ->
                    Toast.makeText(context, msg ?: "Update request dispatched", Toast.LENGTH_SHORT).show()
                    if (success) appSubmittingUpdateFor = null
                }
            }
        )
    }

    // 4. Highly detailed specifications dialog overlay
    showDetailsApp?.let { app ->
        val activeDl = downloads.find { it.id == app.id }
        val installedInfo = installedAppsInfo[app.packageName]
        val dynamicRatingInfo = localAppRatings[app.id] ?: Pair(app.rating, 120 + Math.abs(app.id.hashCode() % 350))

        AppDetailsDialog(
            app = app,
            downloadState = activeDl,
            installedInfo = installedInfo,
            currentRating = dynamicRatingInfo.first,
            currentReviewsCount = dynamicRatingInfo.second,
            accentGreen = accentGreen,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            cardBgColor = cardBgColor,
            isDarkMode = isDarkMode,
            isPurchased = purchasedAppIds.contains(app.id),
            isRegistered = preRegisteredAppIds.contains(app.id),
            isAdmin = isAdmin,
            onBuyClick = { purchaseAppTarget = app; showDetailsApp = null },
            onRegisterClick = {
                viewModel.preRegisterApp(app.id)
                Toast.makeText(context, "Successfully Pre-registered for ${app.name}! You will be notified when this software goes live.", Toast.LENGTH_LONG).show()
            },
            onDismiss = { showDetailsApp = null },
            onAction = {
                val isCurrentlyInstalled = installedInfo != null
                handleAppActionButton(app, isCurrentlyInstalled, viewModel, context)
            },
            onDeleteDl = {
                viewModel.cancelDownload(app.id)
            },
            onReviewSubmit = { stars, comment ->
                // Calculate dynamic rated average locally on screen!
                val totalSReviews = dynamicRatingInfo.second + 1
                val oldAverage = dynamicRatingInfo.first.toFloatOrNull() ?: 4.5f
                val newAverage = ((oldAverage * dynamicRatingInfo.second) + stars) / totalSReviews
                val formattedStr = String.format("%.1f", newAverage)
                localAppRatings = localAppRatings + (app.id to Pair(formattedStr, totalSReviews))
                
                Toast.makeText(context, "Review posted! Global community rating computed.", Toast.LENGTH_LONG).show()
            },
            onReportSubmit = { reason ->
                viewModel.reportApp(app.id, userEmail, reason) { success, msg ->
                    Toast.makeText(context, msg ?: "Failed to submit report", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    purchaseAppTarget?.let { app ->
        SimulatedPaymentCheckoutDialog(
            app = app,
            accentGreen = accentGreen,
            onDismiss = { purchaseAppTarget = null },
            onPurchaseConfirmed = {
                viewModel.purchaseApp(app.id)
                purchaseAppTarget = null
                Toast.makeText(context, "${app.name} purchased successfully in checkout simulation! Ready to download.", Toast.LENGTH_LONG).show()
                showDetailsApp = app
            }
        )
    }

    if (activeNoticeToShow != null) {
        NoticeDetailsDialog(
            notice = activeNoticeToShow!!,
            onDismiss = { activeNoticeToShow = null }
        )
    }
    }
}

// ========================================================
// 4. DISCOVERY TABS CONTENT VIEWS (APPS & GAMES)
// ========================================================
@Composable
fun DiscoveryTabContent(
    isDarkMode: Boolean,
    category: String,
    apps: List<AppEntity>,
    downloads: List<DownloadEntity>,
    installedAppsInfo: Map<String, com.example.utils.ApkInstaller.InstalledAppInfo>,
    installedPackages: Set<String> = emptySet(),
    isRefreshing: Boolean,
    localAppRatings: Map<String, Pair<String, Int>>,
    accentGreen: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    onAppClick: (AppEntity) -> Unit,
    showPillNavbar: Boolean = false,
    selectedPill: String = "All",
    onPillSelect: (String) -> Unit = {},
    purchasedAppIds: Set<String> = emptySet(),
    preRegisteredAppIds: Set<String> = emptySet(),
    onBuyPremiumClick: (AppEntity) -> Unit = {},
    onPreRegisterClick: (AppEntity) -> Unit = {},
    onActionClick: (AppEntity) -> Unit
) {
    val categories = remember { listOf("All", "Utilities", "Games", "Tools", "Entertainment") }
    
    val downloadsMap = remember(downloads) { downloads.associateBy { it.id } }
    
    // Filter Games out for Games tab specifically
    val appsToRender = remember(apps, category, selectedPill) {
        if (category == "Games") {
            apps.filter { it.category.equals("Games", ignoreCase = true) }
        } else {
            apps
        }
    }
    val featuredList = remember(appsToRender) { appsToRender.filter { it.isFeatured } }
    val premiumApps = remember(appsToRender) { appsToRender.filter { it.isPremium } }
    val upcomingApps = remember(appsToRender) { appsToRender.filter { it.isUpcoming } }
    val standardApps = remember(appsToRender) { appsToRender.filter { !it.isUpcoming } }

    var visibleCount by remember(standardApps) { mutableStateOf(3) }
    val visibleStandardApps = remember(standardApps, visibleCount) { standardApps.take(visibleCount) }

    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslate by shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer_offset"
    )
    // Only run the shimmer animation when the skeleton is actually visible.
    // Keeping an InfiniteTransition running during normal scroll wastes GPU frames.
    val activeShimmerTranslate = if (isRefreshing && appsToRender.isEmpty()) shimmerTranslate else 0f

    val listState = rememberLazyListState()

    // Use snapshotFlow so we only react when the last-visible index genuinely changes,
    // not on every pixel of scroll. distinctUntilChanged() suppresses redundant emissions.
    LaunchedEffect(listState, standardApps.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                val totalItems = listState.layoutInfo.totalItemsCount
                if (lastVisibleIndex >= totalItems - 3 && visibleCount < standardApps.size) {
                    visibleCount = (visibleCount + 3).coerceAtMost(standardApps.size)
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Store Play Carousel
        item {
            Spacer(modifier = Modifier.height(6.dp))
            val bannerAlpha = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                bannerAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing)
                )
            }
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = bannerAlpha.value
                    translationY = (1f - bannerAlpha.value) * -10f
                }
            ) {
                PromotedAppsCarousel(
                    featuredList = featuredList,
                    allApps = appsToRender,
                    isDarkMode = isDarkMode,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    accentColor = accentGreen,
                    onAppClick = onAppClick
                )
            }
        }

        // Horizontal Category Filter Pills
        if (showPillNavbar && category != "Games") {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(categories, key = { it }) { cat ->
                        val isSelected = selectedPill == cat
                        Button(
                            onClick = { onPillSelect(cat) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) accentGreen else cardBgColor,
                                contentColor = if (isSelected) Color.White else textSecondary
                            ),
                            shape = RoundedCornerShape(50.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            border = if (!isSelected) BorderStroke(1.dp, cardBorderColor) else null,
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = cat,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Refresh loader state with skeleton loading components to improve perceived performance
        if (isRefreshing && appsToRender.isEmpty()) {
            // Elegant loading status indicator
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = accentGreen,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Syncing Dark Store databases...",
                        color = textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Featured recommendations skeleton header
            item {
                Text(
                    text = "Featured recommendations",
                    color = textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 3 Featured app skeletons in LazyRow to match layout hierarchy perfectly
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(3) {
                        FeaturedAppCardSkeleton(
                            isDarkMode = isDarkMode,
                            cardBgColor = cardBgColor,
                            cardBorderColor = cardBorderColor,
                            shimmerTranslate = activeShimmerTranslate
                        )
                    }
                }
            }

            // Recommended for you skeleton header
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Recommended for you",
                    color = textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 4 App item skeletons vertically matching vertical list layout
            items(4) {
                AppItemCardSkeleton(
                    isDarkMode = isDarkMode,
                    cardBgColor = cardBgColor,
                    cardBorderColor = cardBorderColor,
                    shimmerTranslate = activeShimmerTranslate
                )
            }
        } else if (appsToRender.isEmpty()) {
            item {
                EmptyCatalogStateCard(
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    accentGreen = accentGreen,
                    cardBgColor = cardBgColor,
                    cardBorderColor = cardBorderColor
                )
            }
        } else {
            // Horizontal Spotlight Carousel list
            if (featuredList.isNotEmpty()) {
                item {
                    Text(
                        text = "Featured recommendations",
                        color = textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(featuredList, key = { _, app -> app.id }) { index, app ->
                            val overriddenRating = localAppRatings[app.id]?.first ?: app.rating
                            
                            FeaturedAppCardView(
                                app = app,
                                activeRating = overriddenRating,
                                cardBgColor = cardBgColor,
                                cardBorderColor = cardBorderColor,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                accentGreen = accentGreen,
                                onClick = { onAppClick(app) }
                            )
                        }
                    }
                }
            }

            if (premiumApps.isNotEmpty()) {
                item {
                    Text(
                        text = "Premium Highlights",
                        color = textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 6.dp)
                    ) {
                        items(premiumApps, key = { it.id }) { app ->
                            val isPurchased = purchasedAppIds.contains(app.id)
                            PremiumAppCardView(
                                app = app,
                                purchased = isPurchased,
                                cardBgColor = cardBgColor,
                                cardBorderColor = cardBorderColor,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                accentGreen = accentGreen,
                                onClick = { onAppClick(app) },
                                onBuyClick = { onBuyPremiumClick(app) }
                            )
                        }
                    }
                }
            }

            if (upcomingApps.isNotEmpty()) {
                item {
                    Text(
                        text = "Upcoming Releases (Pre-register)",
                        color = textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 6.dp)
                    ) {
                        items(upcomingApps, key = { it.id }) { app ->
                            val isRegistered = preRegisteredAppIds.contains(app.id)
                            UpcomingAppCardView(
                                app = app,
                                registered = isRegistered,
                                cardBgColor = cardBgColor,
                                cardBorderColor = cardBorderColor,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                accentGreen = accentGreen,
                                onClick = { onAppClick(app) },
                                onRegisterClick = { onPreRegisterClick(app) }
                            )
                        }
                    }
                }
            }

            // Standard Packages item list header
            item {
                Text(
                    text = "Recommended for you",
                    color = textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Packages list
            itemsIndexed(visibleStandardApps, key = { _, app -> app.id }) { index, app ->
                val dlState = remember(downloadsMap, app.id) { downloadsMap[app.id] }
                val installedInfo = remember(installedPackages, app.packageName) { installedAppsInfo[app.packageName] }
                val overriddenRating = remember(localAppRatings, app.id, app.rating) { localAppRatings[app.id]?.first ?: app.rating }

                val isPurchased = purchasedAppIds.contains(app.id)
                val isRegistered = preRegisteredAppIds.contains(app.id)

                AppItemCardView(
                    app = app,
                    downloadState = dlState,
                    installedInfo = installedInfo,
                    overriddenRating = overriddenRating,
                    accentGreen = accentGreen,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    cardBgColor = cardBgColor,
                    cardBorderColor = cardBorderColor,
                    purchased = isPurchased,
                    registered = isRegistered,
                    onBuyClick = { onBuyPremiumClick(app) },
                    onRegisterClick = { onPreRegisterClick(app) },
                    onClick = { onAppClick(app) },
                    onActionClick = { onActionClick(app) }
                )
            }

            if (visibleCount < standardApps.size) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = accentGreen,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ========================================================
// 4.B PREMIUM AND UPCOMING ITEM CARD VIEWS
// ========================================================
@Composable
fun PremiumAppCardView(
    app: AppEntity,
    purchased: Boolean,
    cardBgColor: Color,
    cardBorderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    accentGreen: Color,
    onClick: () -> Unit,
    onBuyClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(152.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                AppLogo(
                    logoUrl = app.logo,
                    appName = app.name,
                    packageName = app.packageName,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = app.name,
                color = textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.developer,
                color = textSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            
            if (purchased) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(accentGreen.copy(alpha = 0.15f), RoundedCornerShape(15.dp))
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Purchased",
                        color = accentGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = onBuyClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    shape = RoundedCornerShape(15.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                ) {
                    Text(
                        text = app.price.ifEmpty { "$1.99" },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
fun UpcomingAppCardView(
    app: AppEntity,
    registered: Boolean,
    cardBgColor: Color,
    cardBorderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    accentGreen: Color,
    onClick: () -> Unit,
    onRegisterClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(152.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                AppLogo(
                    logoUrl = app.logo,
                    appName = app.name,
                    packageName = app.packageName,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = app.name,
                color = textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.category,
                color = textSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            
            if (registered) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(accentGreen.copy(alpha = 0.15f), RoundedCornerShape(15.dp))
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.wrapContentSize()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = accentGreen, modifier = Modifier.size(10.dp))
                        Text(
                            text = "Registered",
                            color = accentGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Button(
                    onClick = onRegisterClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                    shape = RoundedCornerShape(15.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                ) {
                    Text(
                        text = "Pre-register",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ========================================================
// 5. SEVERAL PLAY-STORE-LIKE AUXILIARY CARDS
// ========================================================

@Composable
fun PlayStoreBannerHero(accentColor: Color) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(accentColor, accentColor.copy(alpha = 0.5f))
                    )
                )
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.65f),
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "DARK STORE EXCLUSIVE",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "High-Speed Access",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "Secure local package deployment & fast Firebase catalog synchronizer.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Overlay controller icon illustrative graphic
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Graphic indicator",
                modifier = Modifier
                    .size(90.dp)
                    .align(Alignment.CenterEnd)
                    .alpha(0.15f),
                tint = Color.White
            )
        }
    }
}

fun shimmerBrush(isDarkMode: Boolean, translateAnim: Float): Brush {
    val shimmerColors = if (isDarkMode) {
        listOf(
            Color(0xFF2A2A2A),
            Color(0xFF1F1F1F),
            Color(0xFF2A2A2A)
        )
    } else {
        listOf(
            Color(0xFFE2E8F0),
            Color(0xFFF1F5F9),
            Color(0xFFE2E8F0)
        )
    }

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim)
    )
}

@Composable
fun FeaturedAppCardSkeleton(
    isDarkMode: Boolean,
    cardBgColor: Color,
    cardBorderColor: Color,
    shimmerTranslate: Float
) {
    val brush = shimmerBrush(isDarkMode, shimmerTranslate)
    Card(
        modifier = Modifier
            .width(160.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Logo skeleton
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(brush)
            )
            
            Spacer(modifier = Modifier.height(10.dp))
            
            // Title skeleton
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(13.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Developer skeleton
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Rating / Size row skeleton
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Star skeleton
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(brush)
                )
                // Rating val skeleton
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(brush)
                )
                Spacer(modifier = Modifier.width(4.dp))
                // Size skeleton
                Box(
                    modifier = Modifier
                        .width(30.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(brush)
                )
            }
        }
    }
}

@Composable
fun AppItemCardSkeleton(
    isDarkMode: Boolean,
    cardBgColor: Color,
    cardBorderColor: Color,
    shimmerTranslate: Float
) {
    val brush = shimmerBrush(isDarkMode, shimmerTranslate)
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo skeleton
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(brush)
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Details Column
            Column(modifier = Modifier.weight(1f)) {
                // Title
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                // Developer & Category
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                // Rating/size row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(brush)
                    )
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(brush)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .width(35.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(brush)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Install/Update action button skeleton
            Box(
                modifier = Modifier
                    .size(70.dp, 28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(brush)
            )
        }
    }
}

@Composable
fun FeaturedAppCardView(
    app: AppEntity,
    activeRating: String,
    cardBgColor: Color,
    cardBorderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    accentGreen: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                AppLogo(
                    logoUrl = app.logo,
                    appName = app.name,
                    packageName = app.packageName,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                
                Spacer(modifier = Modifier.height(10.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.name,
                        color = textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(end = if (app.hasAds) 55.dp else 0.dp) // Leave safety room for top-right Ad badge if name is long
                    )
                }
                
                Text(
                    text = app.developer,
                    color = textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = activeRating,
                    color = textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "stars rating",
                    tint = Color(0xFFF1A80A),
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = app.size,
                    color = textSecondary,
                    fontSize = 10.sp
                )
            }
        }
        if (app.hasAds) {
            AdBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
            )
        }
      }
    }
}

@Composable
fun AdBadge(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.img_ad_badge),
        contentDescription = "Contains Ads",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(4.dp))
    )
}

@Composable
fun AppItemCardView(
    app: AppEntity,
    downloadState: DownloadEntity?,
    installedInfo: com.example.utils.ApkInstaller.InstalledAppInfo?,
    overriddenRating: String,
    accentGreen: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    purchased: Boolean = false,
    registered: Boolean = false,
    onBuyClick: () -> Unit = {},
    onRegisterClick: () -> Unit = {},
    onClick: () -> Unit,
    onActionClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Logo bounding box representing percentage ring on download trigger
                    Box(contentAlignment = Alignment.Center) {
                        AppLogo(
                            logoUrl = app.logo,
                            appName = app.name,
                            packageName = app.packageName,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )

                        // Draw thin percentage progress circle directly around logo as specified
                        if (downloadState?.status == "DOWNLOADING") {
                            CircularProgressIndicator(
                                progress = downloadState.progress / 100f,
                                color = accentGreen,
                                trackColor = Color.Transparent,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(58.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = if (app.hasAds) 55.dp else 0.dp) // Leave safety room for top-right Ad badge if name is long
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = app.name,
                                color = textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        Text(
                        text = "${app.developer} • ${app.category}",
                        color = textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Rating Star",
                                tint = Color(0xFFF1A80A),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = overriddenRating,
                                fontSize = 11.sp,
                                color = textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Divider(
                            modifier = Modifier
                                .height(10.dp)
                                .width(1.dp),
                            color = textSecondary.copy(alpha = 0.3f)
                        )

                        Text(
                            text = app.size,
                            fontSize = 11.sp,
                            color = textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Contextual action button layout as per user version matching rules
                Box(contentAlignment = Alignment.Center) {
                    val context = LocalContext.current
                    if (downloadState?.status == "DOWNLOADING") {
                        IconButton(
                            onClick = onActionClick,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel download",
                                tint = textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        val isInstalled = remember(installedInfo) { installedInfo != null }
                        val hasUpdate = remember(isInstalled, app.versionCode, app.version, installedInfo) {
                            isInstalled && (
                                app.versionCode > (installedInfo?.versionCode ?: 0L) ||
                                (installedInfo?.versionName != null && !app.version.trim().equals(installedInfo.versionName.trim(), ignoreCase = true))
                            )
                        }

                        when {
                            app.isUpcoming -> {
                                if (registered) {
                                    OutlinedButton(
                                        onClick = {},
                                        shape = RoundedCornerShape(20.dp),
                                        border = BorderStroke(1.dp, accentGreen),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentGreen),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                                            Text("Registered", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = onRegisterClick,
                                        shape = RoundedCornerShape(20.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF9C27B0),
                                            contentColor = Color.White
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Pre-register", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            app.isPremium && !purchased && !isInstalled -> {
                                Button(
                                    onClick = onBuyClick,
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFF9800),
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    modifier = Modifier.height(32.dp).testTag("premium_buy_button_${app.id}")
                                ) {
                                    Text(app.price.ifEmpty { "$1.99" }, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                            downloadState?.status == "DOWNLOADED" -> {
                                Button(
                                    onClick = onActionClick,
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accentGreen,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("install_action_button_${app.id}")
                                ) {
                                    Text("Install", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            isInstalled && hasUpdate -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Update Button
                                    Button(
                                        onClick = onActionClick,
                                        shape = RoundedCornerShape(20.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = accentGreen,
                                            contentColor = Color.White
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("update_action_button_${app.id}")
                                    ) {
                                        Text("Update", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Open Button
                                    OutlinedButton(
                                        onClick = { ApkInstaller.launchApp(context, app.packageName) },
                                        shape = RoundedCornerShape(20.dp),
                                        border = BorderStroke(1.dp, accentGreen),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentGreen),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("open_action_button_${app.id}")
                                    ) {
                                        Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            isInstalled -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Open Button
                                    Button(
                                        onClick = { ApkInstaller.launchApp(context, app.packageName) },
                                        shape = RoundedCornerShape(20.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFE6F4EA),
                                            contentColor = Color(0xFF01875F)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("open_action_button_${app.id}")
                                    ) {
                                        Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Uninstall Button
                                    if (LocalUninstallEnabled.current) {
                                        OutlinedButton(
                                            onClick = { ApkInstaller.uninstallApp(context, app.packageName) },
                                            shape = RoundedCornerShape(20.dp),
                                            border = BorderStroke(1.dp, Color(0xFFEF5350)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            modifier = Modifier
                                                .height(32.dp)
                                                .testTag("uninstall_action_button_${app.id}")
                                        ) {
                                            Text("Uninstall", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            else -> {
                                Button(
                                    onClick = onActionClick,
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accentGreen,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("install_action_button_${app.id}")
                                ) {
                                    Text("Install", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Real-time progress bar description inside card
            if (downloadState?.status == "DOWNLOADING") {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(textSecondary.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Downloading: ",
                                color = textSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            // Show real-time downloading percentage
                            Text(
                                text = "${downloadState.progress}%",
                                color = accentGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Text(
                            text = downloadState.downloadSpeed,
                            color = accentGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = downloadState.progress / 100f,
                        color = accentGreen,
                        trackColor = textSecondary.copy(alpha = 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }
        if (app.hasAds) {
            AdBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
            )
        }
      }
    }
}

// ========================================================
// 6. MY LIBRARY STATEBOARD (Streamlined Play Manager)
// ========================================================
@Composable
fun LibraryTabContent(
    apps: List<AppEntity>,
    downloads: List<DownloadEntity>,
    installedAppsInfo: Map<String, com.example.utils.ApkInstaller.InstalledAppInfo>,
    textPrimary: Color,
    textSecondary: Color,
    accentGreen: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    onAppClick: (AppEntity) -> Unit,
    onCancelClick: (String) -> Unit,
    onInstallClick: (DownloadEntity) -> Unit,
    onActionClick: (AppEntity) -> Unit
) {
    val context = LocalContext.current
    val installedAppsInCatalog = remember(apps, installedAppsInfo) {
        apps.filter { installedAppsInfo.containsKey(it.packageName) }
    }
    val activeDownloads = remember(downloads, installedAppsInfo) {
        downloads.filter { 
            (it.status == "DOWNLOADING" || it.status == "DOWNLOADED") && !installedAppsInfo.containsKey(it.packageName)
        }
    }
    val appsMap = remember(apps) { apps.associateBy { it.id } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active downloads list with speed
        if (activeDownloads.isNotEmpty()) {
            item {
                Text(
                    text = "Active & In Progress (${activeDownloads.size})",
                    color = textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            itemsIndexed(activeDownloads, key = { _, dl -> dl.id }) { index, dl ->
                val fullApp = appsMap[dl.id]
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppLogo(
                                logoUrl = fullApp?.logo ?: "",
                                appName = dl.name,
                                packageName = dl.packageName,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    dl.name,
                                    color = textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    dl.packageName,
                                    color = textSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))

                            if (dl.status == "DOWNLOADING") {
                                OutlinedButton(
                                    onClick = { onCancelClick(dl.id) },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(28.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                                ) {
                                    Text("Cancel", fontSize = 11.sp)
                                }
                            } else if (dl.status == "DOWNLOADED") {
                                Button(
                                    onClick = { onInstallClick(dl) },
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Install", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }

                        if (dl.status == "DOWNLOADING") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Progress: ${dl.progress}% (Speed: ${dl.downloadSpeed})",
                                    fontSize = 10.sp,
                                    color = accentGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = dl.progress / 100f,
                                color = accentGreen,
                                trackColor = textSecondary.copy(alpha = 0.2f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
            }
        }

        // Active Devices Installed packages list
        item {
            Text(
                text = "Installed Apps (${installedAppsInCatalog.size})",
                color = textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (installedAppsInCatalog.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No installed packages detected from our catalog index.",
                            color = textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            itemsIndexed(installedAppsInCatalog, key = { _, app -> app.id }) { index, app ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAppClick(app) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppLogo(
                            logoUrl = app.logo,
                            appName = app.name,
                            packageName = app.packageName,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.name,
                                color = textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Developer: ${app.developer}",
                                color = textSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Play Store Double Button layout UX
                        val installedInfo = remember(installedAppsInfo.keys, app.packageName) { installedAppsInfo[app.packageName] }
                        val hasUpdate = remember(installedInfo, app.versionCode, app.version) {
                            installedInfo != null && (
                                app.versionCode > installedInfo.versionCode ||
                                (!app.version.trim().equals(installedInfo.versionName.trim(), ignoreCase = true))
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (hasUpdate) {
                                // Real-time Store Version is newer -> Show Update and Open!
                                // Update Button
                                Button(
                                    onClick = { onActionClick(app) },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("UPDATE", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }

                                // Open Button
                                OutlinedButton(
                                    onClick = { ApkInstaller.launchApp(context, app.packageName) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accentGreen),
                                    border = BorderStroke(1.dp, accentGreen.copy(0.3f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("OPEN", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                // Installed version is up-to-date -> Show Open and Uninstall!
                                // Uninstall
                                if (LocalUninstallEnabled.current) {
                                    OutlinedButton(
                                        onClick = { ApkInstaller.uninstallApp(context, app.packageName) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                                        border = BorderStroke(1.dp, Color(0xFFEF5350).copy(0.3f)),
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("UNINSTALL", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    }
                                }

                                // Open
                                Button(
                                    onClick = { ApkInstaller.launchApp(context, app.packageName) },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("OPEN", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

// ========================================================
// REUSABLE SUBMISSION CARD COMPOSABLE
// ========================================================
@Composable
fun DeveloperSubmissionCard(
    sub: com.example.data.SubmissionEntity,
    surfaceCol: Color,
    borderCol: Color,
    textPrimaryCol: Color,
    textSecondaryCol: Color,
    accentGreen: Color,
    isDarkMode: Boolean,
    onRequestUpdate: (com.example.data.SubmissionEntity) -> Unit,
    onEditOptions: (com.example.data.SubmissionEntity) -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceCol),
        border = BorderStroke(1.dp, borderCol)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentGreen.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sub.name.trim().take(1).uppercase(),
                        color = accentGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sub.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = textPrimaryCol
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "v${sub.version}",
                            color = textSecondaryCol,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "•",
                            color = textSecondaryCol.copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )
                        Text(
                            text = sub.category,
                            color = textSecondaryCol,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                val (statusLabel, statusColor, statusBg) = when {
                    sub.status.equals("approved", ignoreCase = true) || sub.status.equals("live", ignoreCase = true) -> {
                        Triple("Approved", Color(0xFF10B981), Color(0xFFE6F4EA))
                    }
                    sub.status.equals("rejected", ignoreCase = true) -> {
                        Triple("Rejected", Color(0xFFEF4444), Color(0xFFFEF2F2))
                    }
                    else -> {
                        Triple("In Review", Color(0xFFF59E0B), Color(0xFFFFFBEB))
                    }
                }

                val finalStatusBg = if (isDarkMode) statusColor.copy(alpha = 0.15f) else statusBg

                Box(
                    modifier = Modifier
                        .background(finalStatusBg, RoundedCornerShape(50))
                        .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            if (sub.status.equals("rejected", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDarkMode) Color(0xFF2C1616) else Color(0xFFFEF2F2)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "REJECTION FEEDBACK",
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = Color(0xFFEF4444),
                                letterSpacing = 0.5.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = sub.feedback.ifBlank { "No feedback details provided by reviewer." },
                            fontSize = 11.sp,
                            color = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFFB91C1C),
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onRequestUpdate(sub) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fix & Re-submit app details", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (sub.status.equals("approved", ignoreCase = true) || sub.status.equals("live", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { onEditOptions(sub) },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit / Update App", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { onRequestUpdate(sub) },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, borderCol),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = accentGreen, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Modify Metadata", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textPrimaryCol)
                    }
                }
            }
        }
    }
}

// ========================================================
// 7. DEVELOPER PROFILE & REAL-TIME CONSOLE PANEL
// ========================================================
@Composable
fun ProfileTabContent(
    viewModel: com.example.viewmodel.StoreViewModel,
    isDarkMode: Boolean,
    isLoggedIn: Boolean,
    userName: String,
    userEmail: String,
    submissions: List<com.example.data.SubmissionEntity> = emptyList(),
    onTriggerSubmitForm: () -> Unit = {},
    onRefreshSubmissions: () -> Unit = {},
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onThemeToggle: () -> Unit,
    onUpdateDeveloperName: (String) -> Unit = {},
    onRequestUpdate: (com.example.data.SubmissionEntity) -> Unit = {},
    onShowAppDetails: (com.example.data.AppEntity) -> Unit = {},
    accentGreen: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBgColor: Color,
    cardBorderColor: Color
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isAuthenticating by remember { mutableStateOf(false) }

    val sharedPrefs = remember(context) { context.getSharedPreferences("dark_store_prefs", android.content.Context.MODE_PRIVATE) }
    val devBioState by viewModel.devBio.collectAsStateWithLifecycle()
    val devBio = devBioState.ifBlank { "Independent developer building professional tools for the community." }
    var isEditingBio by remember { mutableStateOf(false) }
    var editedBio by remember(devBio) { mutableStateOf(devBio) }

    var selectedSubTab by remember { mutableStateOf("Live") }
    var activeDetailSubmission by remember { mutableStateOf<com.example.data.SubmissionEntity?>(null) }
    var editOptionsForApp by remember { mutableStateOf<com.example.data.SubmissionEntity?>(null) }
    var showUpdateScreenshotsFormFor by remember { mutableStateOf<com.example.data.SubmissionEntity?>(null) }
    var showPushUpdateFormFor by remember { mutableStateOf<com.example.data.SubmissionEntity?>(null) }

    LaunchedEffect(isLoggedIn, Unit) {
        if (isLoggedIn) {
            onRefreshSubmissions()
        }
    }

    val bgCol = if (isDarkMode) Color(0xFF13151C) else Color(0xFFF8FAFC)
    val surfaceCol = if (isDarkMode) Color(0xFF1D202B) else Color(0xFFFFFFFF)
    val borderCol = if (isDarkMode) Color(0xFF292E3D) else Color(0xFFE2E8F0)
    val textPrimaryCol = textPrimary
    val textSecondaryCol = textSecondary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgCol)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .widthIn(max = 600.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Screen Title Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Developer Console",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = textPrimaryCol,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isLoggedIn) "Verified publisher registry & tracking console" else "Sandbox environment gateway",
                        fontSize = 11.sp,
                        color = textSecondaryCol,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                if (isLoggedIn) {
                    IconButton(
                        onClick = {
                            onRefreshSubmissions()
                            Toast.makeText(context, "Syncing console databases...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(surfaceCol, CircleShape)
                            .border(1.dp, borderCol, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh data",
                            tint = accentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            if (isAuthenticating) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = accentGreen,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Connecting secure developer workspace...",
                        fontSize = 14.sp,
                        color = textSecondaryCol,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (isLoggedIn) {
                val isDeveloper by viewModel.isDeveloper.collectAsStateWithLifecycle()
                val devWebsite by viewModel.devWebsite.collectAsStateWithLifecycle()
                val devGithub by viewModel.devGithub.collectAsStateWithLifecycle()
                val devName by viewModel.devName.collectAsStateWithLifecycle()

                if (!isDeveloper) {
                    var websiteInput by remember { mutableStateOf("") }
                    var githubInput by remember { mutableStateOf("") }
                    var bioInput by remember { mutableStateOf("") }
                    var registering by remember { mutableStateOf(false) }
                    val resolvedDevName = remember(userName, userEmail) {
                        userName.ifBlank { userEmail.substringBefore("@").replaceFirstChar { it.uppercase() } }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = surfaceCol),
                        border = BorderStroke(1.dp, borderCol)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(accentGreen.copy(alpha = 0.25f), Color(0xFF2563EB).copy(alpha = 0.1f))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Build,
                                    contentDescription = "Become Developer",
                                    tint = accentGreen,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Become a Verified Developer",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = textPrimaryCol
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Submit your developer profile to unlock application submissions, update releases, and real-time App Store play console monitoring.",
                                fontSize = 12.sp,
                                color = textSecondaryCol,
                                textAlign = TextAlign.Center,
                                lineHeight = 17.sp
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(accentGreen.copy(alpha = 0.05f), Color(0xFF2563EB).copy(alpha = 0.03f))
                                        ),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .border(
                                        1.dp,
                                        accentGreen.copy(alpha = 0.15f),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(accentGreen.copy(alpha = 0.12f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = "Developer Identity icon",
                                            tint = accentGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            "Developer Name (Linked Account)",
                                            color = textSecondaryCol,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.3.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = resolvedDevName,
                                            color = textPrimaryCol,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = websiteInput,
                                onValueChange = { websiteInput = it },
                                label = { Text("Website (Optional)", fontSize = 11.sp) },
                                placeholder = { Text("e.g. https://mycoolapps.com") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dev_registration_website_input")
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = githubInput,
                                onValueChange = { githubInput = it },
                                label = { Text("GitHub Username (Optional)", fontSize = 11.sp) },
                                placeholder = { Text("e.g. github_dev_user") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dev_registration_github_input")
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = bioInput,
                                onValueChange = { if (it.length <= 160) bioInput = it },
                                label = { Text("Developer Bio / Headline (Optional)", fontSize = 11.sp) },
                                placeholder = { Text("e.g. Building open-source utilities and tools...") },
                                maxLines = 3,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dev_registration_bio_input")
                            )

                            Spacer(modifier = Modifier.height(22.dp))

                            Button(
                                onClick = {
                                    registering = true
                                    viewModel.registerDeveloper(
                                        devName = resolvedDevName.trim(),
                                        website = websiteInput.trim(),
                                        github = githubInput.trim(),
                                        bio = bioInput.trim()
                                    ) { success, msg ->
                                        registering = false
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                                enabled = !registering,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("dev_registration_submit_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentGreen,
                                    contentColor = Color.White
                                )
                            ) {
                                if (registering) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Register Developer Account", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDarkMode) Color(0xFF2C1616) else Color(0xFFFEF2F2)
                        ),
                        border = BorderStroke(
                            1.dp, 
                            if (isDarkMode) Color(0xFFEF4444).copy(alpha = 0.2f) else Color(0xFFFCA5A5).copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLogout()
                                    Toast.makeText(context, "Logged out of safe developer session.", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.ExitToApp, contentDescription = null, tint = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFFB91C1C), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Sign Out Developer Session",
                                color = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFFB91C1C),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {                    // Verified Developer Card Block
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = surfaceCol),
                        border = BorderStroke(1.dp, borderCol)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // High fidelity colored banner
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(96.dp)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(accentGreen.copy(alpha = 0.2f), Color(0xFF2563EB).copy(alpha = 0.15f))
                                        )
                                    )
                            ) {
                                // Verified Badge on top right of banner
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(14.dp)
                                        .background(
                                            if (isDarkMode) Color(0xFF0F172A).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.9f), 
                                            RoundedCornerShape(20.dp)
                                        )
                                        .border(1.dp, if (isDarkMode) Color(0xFF10B981).copy(alpha = 0.3f) else Color(0xFF34D399).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Color(0xFF10B981), CircleShape)
                                    )
                                    Text(
                                        text = "VERIFIED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (isDarkMode) Color(0xFF34D399) else Color(0xFF047857),
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }

                            // Content overlapping banner
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp, vertical = 0.dp)
                                    .offset(y = (-40).dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val initialLetter = userName.trim().take(1).uppercase()
                                
                                Box(
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(accentGreen, Color(0xFF10B981), Color(0xFF2563EB))
                                            )
                                        )
                                        .border(3.dp, surfaceCol, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = initialLetter,
                                        color = Color.White,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 38.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                var isEditingName by remember { mutableStateOf(false) }
                                var editedName by remember(userName) { mutableStateOf(userName) }

                                if (isEditingName) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        OutlinedTextField(
                                            value = editedName,
                                            onValueChange = { editedName = it },
                                            label = { Text("Developer Display Name", fontSize = 11.sp) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(max = 56.dp)
                                                .testTag("profile_dev_name_input"),
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp),
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = {
                                                if (editedName.isNotBlank()) {
                                                    onUpdateDeveloperName(editedName)
                                                    isEditingName = false
                                                    Toast.makeText(context, "Developer credentials updated across all instances!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(accentGreen.copy(alpha = 0.15f), CircleShape)
                                                .testTag("profile_save_dev_name_button")
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = "Save", tint = accentGreen)
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = {
                                                isEditingName = false
                                                editedName = userName
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(Color.Red.copy(alpha = 0.1f), CircleShape)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Red)
                                        }
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier
                                            .clickable { isEditingName = true }
                                            .clip(RoundedCornerShape(8.dp))
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = userName,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = textPrimaryCol,
                                            fontSize = 21.sp,
                                            modifier = Modifier.testTag("profile_dev_name_text")
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit Developer Name",
                                            tint = accentGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = userEmail,
                                    color = textSecondaryCol,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 2.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // Interactive Developer Tagline / Bio Section
                                if (isEditingBio) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = editedBio,
                                            onValueChange = { if (it.length <= 160) editedBio = it },
                                            label = { Text("Developer Bio / Company Tagline", fontSize = 11.sp) },
                                            placeholder = { Text("e.g. Building open source Android utilities") },
                                            modifier = Modifier.fillMaxWidth(),
                                            maxLines = 3,
                                            shape = RoundedCornerShape(12.dp),
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${160 - editedBio.length} chars left",
                                                fontSize = 10.sp,
                                                color = textSecondaryCol,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { isEditingBio = false }) {
                                                Text("Cancel", color = Color.Red, fontSize = 11.sp)
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Button(
                                                onClick = {
                                                    viewModel.updateDeveloperBio(editedBio)
                                                    isEditingBio = false
                                                    Toast.makeText(context, "Developer bio saved successfully!", Toast.LENGTH_SHORT).show()
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                                                modifier = Modifier.height(28.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                            ) {
                                                Text("Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable { isEditingBio = true }
                                            .background(if (isDarkMode) Color(0xFF0F172A).copy(alpha = 0.4f) else Color(0xFFF8FAFC))
                                            .border(1.dp, borderCol, RoundedCornerShape(16.dp))
                                            .padding(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "PUBLISHER LOGLINE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black,
                                                color = accentGreen,
                                                letterSpacing = 1.sp
                                            )
                                            Icon(Icons.Default.Edit, contentDescription = null, tint = accentGreen, modifier = Modifier.size(10.dp))
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = devBio.ifBlank { "Click to add a custom developer tagline bio about your apps and brand..." },
                                            color = if (devBio.isBlank()) textSecondaryCol.copy(alpha = 0.7f) else textPrimaryCol,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center,
                                            style = androidx.compose.ui.text.TextStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                                            lineHeight = 16.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                if (devWebsite.isNotBlank() || devGithub.isNotBlank()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (devWebsite.isNotBlank()) {
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.5f) else Color(0xFFF1F5F9))
                                                    .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(Icons.Default.Info, contentDescription = "Website", tint = accentGreen, modifier = Modifier.size(13.dp))
                                                Text(devWebsite, fontSize = 11.sp, color = textSecondaryCol, fontWeight = FontWeight.Medium)
                                            }
                                        }

                                        if (devGithub.isNotBlank()) {
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.5f) else Color(0xFFF1F5F9))
                                                    .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = "GitHub Username", tint = accentGreen, modifier = Modifier.size(13.dp))
                                                Text("@$devGithub", fontSize = 11.sp, color = textSecondaryCol, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Beautiful Dashboard Quick Stat Counters Replicated from Pro developer panels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val totalCount = submissions.size
                    val pendingCount = submissions.count { it.status.equals("pending", ignoreCase = true) || it.status.equals("review", ignoreCase = true) }
                    val liveCount = submissions.count { it.status.equals("approved", ignoreCase = true) || it.status.equals("live", ignoreCase = true) }
                    val rejectedCount = submissions.count { it.status.equals("rejected", ignoreCase = true) }

                    class StatData(val label: String, val count: Int, val color: Color, val icon: ImageVector)
                    listOf(
                        StatData("Total Sub", totalCount, Color(0xFF3B82F6), Icons.Default.List),
                        StatData("Pending", pendingCount, Color(0xFFF59E0B), Icons.Default.Build),
                        StatData("Live Apps", liveCount, Color(0xFF10B981), Icons.Default.CheckCircle),
                        StatData("Rejected", rejectedCount, Color(0xFFEF4444), Icons.Default.Warning)
                    ).forEach { item ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(72.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = surfaceCol),
                            border = BorderStroke(1.dp, borderCol)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Left colored accent line
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(item.color)
                                        .align(Alignment.CenterStart)
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.count.toString(),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Black,
                                            color = item.color
                                        )
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = null,
                                            tint = item.color.copy(alpha = 0.25f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.label,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = textSecondaryCol,
                                        maxLines = 1,
                                        letterSpacing = 0.2.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Prominent "Publish New Application" Hero Button
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTriggerSubmitForm() }
                        .testTag("profile_trigger_user_submission_form"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = surfaceCol),
                    border = BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(accentGreen, Color(0xFF2563EB))
                        )
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    colors = if (isDarkMode) {
                                        listOf(accentGreen.copy(alpha = 0.12f), Color(0xFF2563EB).copy(alpha = 0.05f))
                                    } else {
                                        listOf(accentGreen.copy(alpha = 0.06f), Color(0xFF2563EB).copy(alpha = 0.03f))
                                    }
                                )
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(accentGreen, Color(0xFF10B981))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Publish New Application",
                                    fontWeight = FontWeight.ExtraBold,
                                    color = textPrimaryCol,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Deploy software packages, target build criteria, and store resources.",
                                    color = textSecondaryCol,
                                    fontSize = 11.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = accentGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Systematic Submissions Tab Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(surfaceCol, RoundedCornerShape(12.dp))
                        .border(1.dp, borderCol, RoundedCornerShape(12.dp))
                        .padding(4.dp)
                ) {
                    val liveSubmissions = submissions.filter { it.status.equals("approved", ignoreCase = true) || it.status.equals("live", ignoreCase = true) }
                    val trackerSubmissions = submissions.filter { !it.status.equals("approved", ignoreCase = true) && !it.status.equals("live", ignoreCase = true) }

                    // Tab 1: Live Apps
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedSubTab == "Live") accentGreen else Color.Transparent)
                            .clickable { selectedSubTab = "Live" }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (selectedSubTab == "Live") Color.White else Color(0xFF10B981),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Live Apps (${liveSubmissions.size})",
                                color = if (selectedSubTab == "Live") Color.White else textPrimaryCol,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Tab 2: Tracker
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedSubTab == "Tracker") accentGreen else Color.Transparent)
                            .clickable { selectedSubTab = "Tracker" }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = if (selectedSubTab == "Tracker") Color.White else Color(0xFFFFB300),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Tracker (${trackerSubmissions.size})",
                                color = if (selectedSubTab == "Tracker") Color.White else textPrimaryCol,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val activeList = if (selectedSubTab == "Live") {
                    submissions.filter { it.status.equals("approved", ignoreCase = true) || it.status.equals("live", ignoreCase = true) }
                } else {
                    submissions.filter { !it.status.equals("approved", ignoreCase = true) && !it.status.equals("live", ignoreCase = true) }
                }

                if (activeList.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = surfaceCol),
                        border = BorderStroke(1.dp, borderCol)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = if (selectedSubTab == "Live") Icons.Default.CheckCircle else Icons.Default.Build,
                                contentDescription = "Empty submissions",
                                tint = textSecondaryCol.copy(alpha = 0.5f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = if (selectedSubTab == "Live") "No live applications yet" else "No pending submissions",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimaryCol
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (selectedSubTab == "Live") "Deploy your submitted software package or await administrator audit feedback logs." else "Register a new software catalog item above to initialize tracking logs.",
                                fontSize = 11.sp,
                                color = textSecondaryCol,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                        }
                    }
                } else {
                    // items() renders cards lazily — only visible submissions are composed at any time
                    if (selectedSubTab == "Tracker") {
                        val liveSubmissions = submissions.filter { it.status.equals("approved", ignoreCase = true) || it.status.equals("live", ignoreCase = true) }
                        val livePackages = liveSubmissions.map { it.packageName }.toSet()
                        val (appUpdatesTracker, newAppsTracker) = activeList.partition { sub ->
                            livePackages.contains(sub.packageName) || submissions.filter { it.packageName == sub.packageName }.any { it.createdAt < sub.createdAt }
                        }

                        if (newAppsTracker.isNotEmpty()) {
                            Text(
                                text = "NEW APP SUBMISSIONS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = accentGreen,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp)
                            )
                            newAppsTracker.sortedByDescending { it.createdAt }.forEach { sub ->
                                DeveloperSubmissionCard(
                                    sub = sub,
                                    surfaceCol = surfaceCol,
                                    borderCol = borderCol,
                                    textPrimaryCol = textPrimaryCol,
                                    textSecondaryCol = textSecondaryCol,
                                    accentGreen = accentGreen,
                                    isDarkMode = isDarkMode,
                                    onRequestUpdate = onRequestUpdate,
                                    onEditOptions = { editOptionsForApp = it },
                                    onClick = { activeDetailSubmission = sub }
                                )
                            }
                        }

                        if (appUpdatesTracker.isNotEmpty()) {
                            if (newAppsTracker.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(18.dp))
                            }
                            Text(
                                text = "PENDING VERSION UPDATES",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFF59E0B),
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp)
                            )
                            appUpdatesTracker.sortedByDescending { it.createdAt }.forEach { sub ->
                                DeveloperSubmissionCard(
                                    sub = sub,
                                    surfaceCol = surfaceCol,
                                    borderCol = borderCol,
                                    textPrimaryCol = textPrimaryCol,
                                    textSecondaryCol = textSecondaryCol,
                                    accentGreen = Color(0xFFF59E0B),
                                    isDarkMode = isDarkMode,
                                    onRequestUpdate = onRequestUpdate,
                                    onEditOptions = { editOptionsForApp = it },
                                    onClick = { activeDetailSubmission = sub }
                                )
                            }
                        }
                    } else {
                        activeList.forEach { sub ->
                            DeveloperSubmissionCard(
                                sub = sub,
                                surfaceCol = surfaceCol,
                                borderCol = borderCol,
                                textPrimaryCol = textPrimaryCol,
                                textSecondaryCol = textSecondaryCol,
                                accentGreen = accentGreen,
                                isDarkMode = isDarkMode,
                                onRequestUpdate = onRequestUpdate,
                                onEditOptions = { editOptionsForApp = it },
                                onClick = { activeDetailSubmission = sub }
                            )
                        }
                    }
                }

                // ========================================================
                // POPUP 1: SUBMISSION DETAILS VIEW OVERLAY (when clicked)
                // ========================================================
                if (activeDetailSubmission != null) {
                    val sub = activeDetailSubmission!!
                    Dialog(
                        onDismissRequest = { activeDetailSubmission = null },
                        properties = DialogProperties(
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = true
                        )
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = surfaceCol
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(surfaceCol)
                            ) {
                                // Dynamic Navigation Header / App Bar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isDarkMode) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { activeDetailSubmission = null },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowBack,
                                            contentDescription = "Go back",
                                            tint = textPrimaryCol
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Conformity Audit Center",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            color = accentGreen,
                                            letterSpacing = 1.sp
                                        )
                                        Text(
                                            text = "Submission Report",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = textPrimaryCol
                                        )
                                    }
                                    
                                    val statusText = sub.status.uppercase()
                                    val statusColor = when (sub.status.lowercase()) {
                                        "approved", "live" -> Color(0xFF10B981)
                                        "rejected" -> Color(0xFFEF4444)
                                        else -> Color(0xFFFFB300)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                            .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(statusColor, CircleShape)
                                            )
                                            Text(
                                                text = statusText,
                                                color = statusColor,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    // 1. Hero / App Identity Panel
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.5f) else Color(0xFFF8FAFC)),
                                        border = BorderStroke(1.dp, borderCol)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            // App Icon Placeholder with dynamic gradient
                                            Box(
                                                modifier = Modifier
                                                    .size(72.dp)
                                                    .background(
                                                        Brush.linearGradient(
                                                            listOf(accentGreen, Color(0xFF2563EB))
                                                        ),
                                                        RoundedCornerShape(16.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (sub.logo.isNotBlank()) "" else sub.name.take(1).uppercase(),
                                                    color = Color.White,
                                                    fontSize = 32.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                                if (sub.logo.isNotBlank()) {
                                                    AsyncImage(
                                                        model = sub.logo,
                                                        contentDescription = "App Logo",
                                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = sub.name,
                                                    fontSize = 22.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = textPrimaryCol
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Person,
                                                        contentDescription = null,
                                                        tint = accentGreen,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Text(
                                                        text = "By ${sub.developer}",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = textPrimaryCol
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Sender: ${sub.submittedBy}",
                                                    fontSize = 11.sp,
                                                    color = textSecondaryCol
                                                )
                                            }
                                        }
                                    }

                                    // 2. Timeline Pipeline Tracker Panel
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            text = "VERIFICATION PIPELINE PROGRESS",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            color = textSecondaryCol,
                                            letterSpacing = 0.6.sp
                                        )
                                        
                                        val isApproved = sub.status.equals("approved", ignoreCase = true) || sub.status.equals("live", ignoreCase = true)
                                        val isRejected = sub.status.equals("rejected", ignoreCase = true)
                                        val isPending = !isApproved && !isRejected

                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (isDarkMode) Color(0xFF0F172A).copy(alpha = 0.4f) else Color(0xFFF1F5F9).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                                .border(1.dp, borderCol, RoundedCornerShape(20.dp))
                                                .padding(18.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            // Step 1: Dispatched
                                            TimelineStepRow(
                                                title = "Application Dispatched",
                                                subtitle = "Software package registered & indexed into evaluation queue.",
                                                statusIcon = Icons.Default.CheckCircle,
                                                statusColor = accentGreen,
                                                isLast = false,
                                                textPrimaryCol = textPrimaryCol,
                                                textSecondaryCol = textSecondaryCol,
                                                borderCol = borderCol
                                            )

                                            // Step 2: Evaluation
                                            val step2Icon = if (isApproved || isRejected) Icons.Default.CheckCircle else Icons.Default.Refresh
                                            val step2Color = if (isApproved || isRejected) accentGreen else Color(0xFFFFB300)
                                            val step2Desc = if (isApproved || isRejected) "Console administrative clearance completed successfully." else "Auditors are evaluating targets, build parameters, and policy constraints."
                                            TimelineStepRow(
                                                title = "Evaluation Review",
                                                subtitle = step2Desc,
                                                statusIcon = step2Icon,
                                                statusColor = step2Color,
                                                isLast = false,
                                                textPrimaryCol = textPrimaryCol,
                                                textSecondaryCol = textSecondaryCol,
                                                borderCol = borderCol
                                            )

                                            // Step 3: Deployment Result
                                            val step3Icon = when {
                                                isApproved -> Icons.Default.CheckCircle
                                                isRejected -> Icons.Default.Warning
                                                else -> Icons.Default.Info
                                            }
                                            val step3Color = when {
                                                isApproved -> Color(0xFF10B981)
                                                isRejected -> Color(0xFFEF4444)
                                                else -> textSecondaryCol.copy(alpha = 0.5f)
                                            }
                                            val step3Desc = when {
                                                isApproved -> "Live on marketplace storefront for catalog distributions."
                                                isRejected -> "Audit declined. Refusal log issued with details below."
                                                else -> "Pending deployment and final storefront compilation."
                                            }
                                            TimelineStepRow(
                                                title = "Deployment Stage",
                                                subtitle = step3Desc,
                                                statusIcon = step3Icon,
                                                statusColor = step3Color,
                                                isLast = true,
                                                textPrimaryCol = textPrimaryCol,
                                                textSecondaryCol = textSecondaryCol,
                                                borderCol = borderCol
                                            )
                                        }
                                    }

                                    // 3. Decline/Feedback alert box (if rejected)
                                    if (sub.status.equals("rejected", ignoreCase = true)) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(20.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isDarkMode) Color(0xFF3B1E1E) else Color(0xFFFFF1F1)
                                            ),
                                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f))
                                        ) {
                                            Column(modifier = Modifier.padding(18.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Warning,
                                                        contentDescription = null,
                                                        tint = Color(0xFFEF4444),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Text(
                                                        "DECLINATION CONSTRAINTS FEEDBACK",
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 11.sp,
                                                        color = Color(0xFFEF4444),
                                                        letterSpacing = 0.5.sp
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = sub.feedback.ifBlank { "No detailed critique logged by administrator." },
                                                    fontSize = 13.sp,
                                                    color = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFF991B1B),
                                                    lineHeight = 18.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }

                                    // 4. Technical specifications Grid
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            text = "TECHNICAL SPECIFICATIONS",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            color = textSecondaryCol,
                                            letterSpacing = 0.6.sp
                                        )

                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    MetadataBadge(
                                                        icon = Icons.Default.Info,
                                                        label = "PACKAGE IDENTIFIER",
                                                        value = sub.packageName,
                                                        accentColor = accentGreen,
                                                        surfaceCol = if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.4f) else Color(0xFFF1F5F9).copy(alpha = 0.6f),
                                                        borderCol = borderCol,
                                                        textPrimaryCol = textPrimaryCol,
                                                        textSecondaryCol = textSecondaryCol
                                                    )
                                                }
                                                Box(modifier = Modifier.weight(1f)) {
                                                    MetadataBadge(
                                                        icon = Icons.Default.Build,
                                                        label = "BUILD VERSION",
                                                        value = "v${sub.version}",
                                                        accentColor = accentGreen,
                                                        surfaceCol = if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.4f) else Color(0xFFF1F5F9).copy(alpha = 0.6f),
                                                        borderCol = borderCol,
                                                        textPrimaryCol = textPrimaryCol,
                                                        textSecondaryCol = textSecondaryCol
                                                    )
                                                }
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    MetadataBadge(
                                                        icon = Icons.Default.Menu,
                                                        label = "STORE CATEGORY",
                                                        value = sub.category,
                                                        accentColor = accentGreen,
                                                        surfaceCol = if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.4f) else Color(0xFFF1F5F9).copy(alpha = 0.6f),
                                                        borderCol = borderCol,
                                                        textPrimaryCol = textPrimaryCol,
                                                        textSecondaryCol = textSecondaryCol
                                                    )
                                                }
                                                Box(modifier = Modifier.weight(1f)) {
                                                    MetadataBadge(
                                                        icon = Icons.Default.Check,
                                                        label = "MONETIZATION ADS",
                                                        value = if (sub.hasAds) "Contains Advertisements" else "Clean Build / No Ads",
                                                        accentColor = accentGreen,
                                                        surfaceCol = if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.4f) else Color(0xFFF1F5F9).copy(alpha = 0.6f),
                                                        borderCol = borderCol,
                                                        textPrimaryCol = textPrimaryCol,
                                                        textSecondaryCol = textSecondaryCol
                                                    )
                                                }
                                            }
                                            if (sub.apkUrl.isNotBlank()) {
                                                MetadataBadge(
                                                    icon = Icons.Default.Share,
                                                    label = "DISTRIBUTION SOURCE URL (APK / TARGET)",
                                                    value = sub.apkUrl,
                                                    accentColor = Color(0xFF2563EB),
                                                    surfaceCol = if (isDarkMode) Color(0xFF1E293B).copy(alpha = 0.4f) else Color(0xFFF1F5F9).copy(alpha = 0.6f),
                                                    borderCol = borderCol,
                                                    textPrimaryCol = textPrimaryCol,
                                                    textSecondaryCol = textSecondaryCol
                                                )
                                            }
                                        }
                                    }

                                    // 5. App Logs & Description
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            text = "MANIFEST SUMMARY & RELEASE NOTES",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            color = textSecondaryCol,
                                            letterSpacing = 0.6.sp
                                        )

                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (isDarkMode) Color(0xFF0F172A).copy(alpha = 0.4f) else Color(0xFFF1F5F9).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                                .border(1.dp, borderCol, RoundedCornerShape(20.dp))
                                                .padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = sub.description.ifBlank { "No detailed release notes or catalogued description provided with this submission build." },
                                                fontSize = 13.sp,
                                                color = textPrimaryCol,
                                                lineHeight = 19.sp
                                            )
                                        }
                                    }

                                    // 6. Graphical Screenshots Carousel
                                    if (sub.screenshots.isNotBlank()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(
                                                text = "SUBMITTED GRAPHICAL SCREENSHOTS",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Black,
                                                color = textSecondaryCol,
                                                letterSpacing = 0.6.sp
                                            )

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                sub.screenshots.split(",").forEach { url ->
                                                    if (url.isNotBlank()) {
                                                        Card(
                                                            shape = RoundedCornerShape(16.dp),
                                                            modifier = Modifier.size(width = 160.dp, height = 260.dp),
                                                            border = BorderStroke(1.dp, borderCol)
                                                        ) {
                                                            AsyncImage(
                                                                model = url,
                                                                contentDescription = "screenshot",
                                                                modifier = Modifier.fillMaxSize(),
                                                                contentScale = ContentScale.Crop
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Bottom Footer Area with close button
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isDarkMode) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                                        .padding(16.dp)
                                ) {
                                    Button(
                                        onClick = { activeDetailSubmission = null },
                                        colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                    ) {
                                        Text(
                                            text = "DISMISS REPORT AUDIT",
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 13.sp,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ========================================================
                // POPUP 2: EDIT OPTIONS POPUP (edited screenshot & push update)
                // ========================================================
                if (editOptionsForApp != null) {
                    val app = editOptionsForApp!!
                    Dialog(onDismissRequest = { editOptionsForApp = null }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.96f)
                                .padding(vertical = 16.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = surfaceCol),
                            border = BorderStroke(1.dp, borderCol)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "Update Application Profile",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = accentGreen
                                )
                                Text(
                                    text = "Configure adjustments or push software update for ${app.name}.",
                                    fontSize = 14.sp,
                                    color = textPrimaryCol,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Button 1: Edited Screenshot
                                Button(
                                    onClick = {
                                        showUpdateScreenshotsFormFor = app
                                        editOptionsForApp = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Update Screenshots", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }

                                // Button 2: Push Update
                                Button(
                                    onClick = {
                                        showPushUpdateFormFor = app
                                        editOptionsForApp = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Push Version Update", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }

                                TextButton(onClick = { editOptionsForApp = null }) {
                                    Text("Cancel", color = textSecondaryCol, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // ========================================================
                // POPUP 3: SCREENSHOTS UPDATE FORM DIALOG
                // ========================================================
                if (showUpdateScreenshotsFormFor != null) {
                    val app = showUpdateScreenshotsFormFor!!
                    val existingScreens = remember(app) {
                        val list = app.screenshots.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        List(6) { index -> if (index < list.size) list[index] else "" }
                    }

                    var s1 by remember { mutableStateOf(existingScreens[0]) }
                    var s2 by remember { mutableStateOf(existingScreens[1]) }
                    var s3 by remember { mutableStateOf(existingScreens[2]) }
                    var s4 by remember { mutableStateOf(existingScreens[3]) }
                    var s5 by remember { mutableStateOf(existingScreens[4]) }
                    var s6 by remember { mutableStateOf(existingScreens[5]) }

                    val ssUploadingStates = remember { mutableStateListOf(false, false, false, false, false, false) }
                    var targetSlot by remember { mutableStateOf(-1) }

                    val pickScreenshotLauncher = rememberLauncherForActivityResult(
                        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null && targetSlot in 0..5) {
                            val slot = targetSlot
                            ssUploadingStates[slot] = true
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val inputStream = context.contentResolver.openInputStream(uri)
                                    val bytes = inputStream?.readBytes()
                                    inputStream?.close()
                                    if (bytes != null) {
                                        val contentType = "image/jpeg"
                                        val fileName = "screenshot_${System.currentTimeMillis()}.jpg"
                                        val url = com.example.data.FirebaseAuthService.uploadFile(contentType, fileName, bytes)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            ssUploadingStates[slot] = false
                                            if (url != null) {
                                                when (slot) {
                                                    0 -> s1 = url
                                                    1 -> s2 = url
                                                    2 -> s3 = url
                                                    3 -> s4 = url
                                                    4 -> s5 = url
                                                    5 -> s6 = url
                                                }
                                                Toast.makeText(context, "Screenshot ${slot + 1} uploaded successfully!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Cloud upload skipped. Pasting local cache...", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } else {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            ssUploadingStates[slot] = false
                                        }
                                    }
                                } catch (ex: Exception) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        ssUploadingStates[slot] = false
                                        Toast.makeText(context, "Failure: ${ex.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }

                    Dialog(onDismissRequest = { showUpdateScreenshotsFormFor = null }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.96f)
                                .padding(vertical = 12.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = surfaceCol),
                            border = BorderStroke(1.dp, borderCol)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(20.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "Update App Screenshots",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = textPrimaryCol
                                )
                                Text(
                                    text = "Select from device or input URLs for up to 6 screenshots. Submitting replaces existing screenshots and flags administrative queue audit verification.",
                                    fontSize = 11.sp,
                                    color = textSecondaryCol,
                                    lineHeight = 16.sp
                                )

                                Divider(color = borderCol.copy(alpha = 0.5f))

                                // Grid of 6 screenshots picker
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val slots = listOf(
                                        Triple(0, s1, { v: String -> s1 = v }),
                                        Triple(1, s2, { v: String -> s2 = v }),
                                        Triple(2, s3, { v: String -> s3 = v }),
                                        Triple(3, s4, { v: String -> s4 = v }),
                                        Triple(4, s5, { v: String -> s5 = v }),
                                        Triple(5, s6, { v: String -> s6 = v })
                                    )

                                    slots.chunked(2).forEach { pair ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            pair.forEach { (index, value, setter) ->
                                                Card(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(130.dp)
                                                        .clickable {
                                                            targetSlot = index
                                                            pickScreenshotLauncher.launch("image/*")
                                                        },
                                                    shape = RoundedCornerShape(12.dp),
                                                    colors = CardDefaults.cardColors(containerColor = borderCol.copy(alpha = 0.15f)),
                                                    border = BorderStroke(1.dp, borderCol.copy(alpha = 0.4f))
                                                ) {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        if (ssUploadingStates[index]) {
                                                            CircularProgressIndicator(color = accentGreen, modifier = Modifier.size(24.dp))
                                                        } else if (value.isNotBlank()) {
                                                            Box(modifier = Modifier.fillMaxSize()) {
                                                                AsyncImage(
                                                                    model = value,
                                                                    contentDescription = "screenshot",
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentScale = ContentScale.Crop
                                                                )
                                                                IconButton(
                                                                    onClick = { setter("") },
                                                                    modifier = Modifier
                                                                        .align(Alignment.TopEnd)
                                                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                                                        .size(24.dp)
                                                                ) {
                                                                    Icon(Icons.Default.Delete, contentDescription = "delete", tint = Color.White, modifier = Modifier.size(12.dp))
                                                                }
                                                            }
                                                        } else {
                                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                                Icon(Icons.Default.AddCircle, contentDescription = null, tint = accentGreen, modifier = Modifier.size(20.dp))
                                                                Spacer(modifier = Modifier.height(4.dp))
                                                                Text("Slot ${index + 1}", fontSize = 10.sp, color = textSecondaryCol, fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Divider(color = borderCol.copy(alpha = 0.5f))

                                // Text inputs for manual URLs fallback
                                listOf(
                                    "Slot 1 Screenshot URL" to s1,
                                    "Slot 2 Screenshot URL" to s2,
                                    "Slot 3 Screenshot URL" to s3
                                ).forEachIndexed { index, (label, value) ->
                                    OutlinedTextField(
                                        value = value,
                                        onValueChange = { newValue ->
                                            when (index) {
                                                0 -> s1 = newValue
                                                1 -> s2 = newValue
                                                2 -> s3 = newValue
                                            }
                                        },
                                        label = { Text(label, fontSize = 10.sp) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = accentGreen,
                                            unfocusedBorderColor = borderCol
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Button(
                                    onClick = {
                                        val combined = listOf(s1, s2, s3, s4, s5, s6).filter { it.isNotBlank() }.joinToString(",")
                                        viewModel.submitAppForReview(
                                            name = app.name,
                                            packageName = app.packageName,
                                            description = app.description,
                                            apkUrl = app.apkUrl,
                                            screenshots = combined,
                                            logo = app.logo,
                                            category = app.category,
                                            version = app.version,
                                            hasAds = app.hasAds
                                        ) { success, msg ->
                                            Toast.makeText(context, msg ?: "Screenshots update dispatched to Admin", Toast.LENGTH_SHORT).show()
                                            if (success) {
                                                showUpdateScreenshotsFormFor = null
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().height(44.dp)
                                ) {
                                    Text("SUBMIT FOR AUDIT REVIEW", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                TextButton(
                                    onClick = { showUpdateScreenshotsFormFor = null },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Text("Cancel", color = textSecondaryCol, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // ========================================================
                // POPUP 4: PUSH VERSION UPDATE FORM DIALOG
                // ========================================================
                if (showPushUpdateFormFor != null) {
                    val app = showPushUpdateFormFor!!
                    var verInput by remember { mutableStateOf(app.version) }
                    var apkInput by remember { mutableStateOf(app.apkUrl) }
                    var descInput by remember { mutableStateOf(app.description) }
                    var catInput by remember { mutableStateOf(app.category) }
                    var adsInput by remember { mutableStateOf(app.hasAds) }

                    Dialog(onDismissRequest = { showPushUpdateFormFor = null }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.96f)
                                .padding(vertical = 12.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = surfaceCol),
                            border = BorderStroke(1.dp, borderCol)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(20.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "Push App Version Update",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = textPrimaryCol
                                )
                                Text(
                                    text = "Update the software version name, release logs (changelog details), and APK links. This triggers immediate admin queue auditing verification.",
                                    fontSize = 11.sp,
                                    color = textSecondaryCol,
                                    lineHeight = 16.sp
                                )

                                Divider(color = borderCol.copy(alpha = 0.5f))

                                // Inputs
                                OutlinedTextField(
                                    value = verInput,
                                    onValueChange = { verInput = it },
                                    label = { Text("Version Name *") },
                                    placeholder = { Text("e.g. v2.1.0") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accentGreen,
                                        unfocusedBorderColor = borderCol
                                    )
                                )

                                OutlinedTextField(
                                    value = apkInput,
                                    onValueChange = { apkInput = it },
                                    label = { Text("APK Executable Link *") },
                                    placeholder = { Text("e.g. https://...") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accentGreen,
                                        unfocusedBorderColor = borderCol
                                    )
                                )
                                if (apkInput.contains("drive.google.com", ignoreCase = true) || apkInput.contains("docs.google.com", ignoreCase = true)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFE0F2FE), RoundedCornerShape(10.dp))
                                            .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Drive Support",
                                            tint = Color(0xFF0284C7),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Google Drive link detected! Clean direct downloads and virus scans confirmation bypass are fully supported and automated.",
                                            fontSize = 10.sp,
                                            color = Color(0xFF0369A1),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = catInput,
                                    onValueChange = { catInput = it },
                                    label = { Text("App Category") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accentGreen,
                                        unfocusedBorderColor = borderCol
                                    )
                                )

                                OutlinedTextField(
                                    value = descInput,
                                    onValueChange = { descInput = it },
                                    label = { Text("Release Notes / Changelog *") },
                                    placeholder = { Text("e.g. Fixed core bugs and updated UI components.") },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().height(100.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accentGreen,
                                        unfocusedBorderColor = borderCol
                                    )
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Contains Advertisements", fontSize = 12.sp, color = textPrimaryCol)
                                    Switch(
                                        checked = adsInput,
                                        onCheckedChange = { adsInput = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = accentGreen)
                                    )
                                }

                                Divider(color = borderCol.copy(alpha = 0.5f))

                                Button(
                                    onClick = {
                                        if (verInput.isNotBlank() && apkInput.isNotBlank() && descInput.isNotBlank()) {
                                            viewModel.submitAppForReview(
                                                name = app.name,
                                                packageName = app.packageName,
                                                description = descInput,
                                                apkUrl = apkInput,
                                                screenshots = app.screenshots,
                                                logo = app.logo,
                                                category = catInput,
                                                version = verInput,
                                                hasAds = adsInput
                                            ) { success, msg ->
                                                Toast.makeText(context, msg ?: "Version update queued for Admin audit", Toast.LENGTH_SHORT).show()
                                                if (success) {
                                                    showPushUpdateFormFor = null
                                                }
                                            }
                                        } else {
                                            Toast.makeText(context, "All marked fields (*) are required.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().height(44.dp)
                                ) {
                                    Text("SUBMIT FOR AUDIT REVIEW", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                TextButton(
                                    onClick = { showPushUpdateFormFor = null },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Text("Cancel", color = textSecondaryCol, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Sign Out Button Card Row
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDarkMode) Color(0xFF2C1616) else Color(0xFFFEF2F2)
                    ),
                    border = BorderStroke(
                        1.dp, 
                        if (isDarkMode) Color(0xFFEF4444).copy(alpha = 0.2f) else Color(0xFFFCA5A5).copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onLogout()
                                Toast.makeText(context, "Logged out of safe developer session.", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null, tint = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFFB91C1C), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Sign Out Developer Session",
                            color = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFFB91C1C),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
                }

                Spacer(modifier = Modifier.height(32.dp))

            } else {
                // ================= Anonymous Guest Frame =================
                var customEmail by remember { mutableStateOf("guest.dev@gmail.com") }
                var customName by remember { mutableStateOf("External Developer") }
                var showCustomFields by remember { mutableStateOf(false) }

                if (!showCustomFields) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(if (isDarkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Guest",
                                tint = textSecondaryCol.copy(alpha = 0.7f),
                                modifier = Modifier.size(44.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Guest Developer Workspace",
                            fontWeight = FontWeight.Bold,
                            color = textPrimaryCol,
                            fontSize = 18.sp
                        )

                        Text(
                            text = "Unauthenticated sandbox environment",
                            color = textSecondaryCol,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Connect an active Developer Profile to submit your custom apps, deploy updates, configure build attributes, and monitor your submissions live.",
                            fontSize = 12.sp,
                            color = textSecondaryCol,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Button(
                            onClick = { showCustomFields = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentGreen,
                                contentColor = Color.White
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Connect Google Account",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                } else {
                    // Google Authentication Form Style
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceCol, RoundedCornerShape(24.dp))
                            .border(1.dp, borderCol, RoundedCornerShape(24.dp))
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "Google Developer Identity",
                            color = textPrimaryCol,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Configure your authorized play console registry profile",
                            color = textSecondaryCol,
                            fontSize = 12.sp
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            label = { Text("Display Name", fontSize = 11.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_auth_display_name")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = customEmail,
                            onValueChange = { customEmail = it },
                            label = { Text("Google Account Email Address", fontSize = 11.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_auth_email")
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = {
                                if (customEmail.isNotBlank() && customName.isNotBlank()) {
                                    isAuthenticating = true
                                    coroutineScope.launch {
                                        kotlinx.coroutines.delay(1200)
                                        onLogin(customEmail.trim(), customName.trim())
                                        isAuthenticating = false
                                        Toast.makeText(context, "Successfully authorized as: $customName", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Name and Email are required.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("custom_auth_submit_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentGreen,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Authorize Sandbox Session", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        TextButton(
                            onClick = { showCustomFields = false },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Back to Selection", color = accentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// 7. GOOGLE PLAY ACCOUNT PROFILE WINDOW
// ========================================================
@Composable
fun GooglePlayAccountDialog(
    isDarkMode: Boolean,
    isLoggedIn: Boolean,
    userName: String,
    userEmail: String,
    submissions: List<SubmissionEntity> = emptyList(),
    onTriggerSubmitForm: () -> Unit = {},
    onRefreshSubmissions: () -> Unit = {},
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onThemeToggle: () -> Unit,
    onUpdateDeveloperName: (String) -> Unit = {},
    onRequestUpdate: (SubmissionEntity) -> Unit = {},
    onShowAppDetails: (com.example.data.AppEntity) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isAuthenticating by remember { mutableStateOf(false) }

    LaunchedEffect(isLoggedIn, Unit) {
        if (isLoggedIn) {
            onRefreshSubmissions()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val bgCol = if (isDarkMode) Color(0xFF13151C) else Color(0xFFFFFFFF)
        val surfaceCol = if (isDarkMode) Color(0xFF1D202B) else Color(0xFFF8FAFC)
        val borderCol = if (isDarkMode) Color(0xFF292E3D) else Color(0xFFE2E8F0)
        val textPrimaryCol = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF1E293B)
        val textSecondaryCol = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = bgCol),
            border = BorderStroke(1.dp, borderCol)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top header bar with G-Logo or Shield icon, and a Close button.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = null,
                            tint = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF2563EB),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "DarkRoot Developer Account",
                            fontWeight = FontWeight.Bold,
                            color = textPrimaryCol,
                            fontSize = 14.sp,
                            letterSpacing = 0.2.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .background(if (isDarkMode) Color(0xFF292E3D) else Color(0xFFF1F5F9), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = textPrimaryCol,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isAuthenticating) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = if (isDarkMode) Color(0xFF34D399) else Color(0xFF01875F),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Securing developer authentication token...",
                            fontSize = 13.sp,
                            color = textSecondaryCol,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (isLoggedIn) {
                    // Profile Info Header Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = surfaceCol),
                        border = BorderStroke(1.dp, borderCol)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val initialLetter = userName.trim().take(1).uppercase()
                            
                            // Beautiful user avatar circular image/letter with deep rich colors
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF2563EB), Color(0xFF3B82F6), Color(0xFF60A5FA))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initialLetter,
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 28.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            var isEditingName by remember { mutableStateOf(false) }
                            var editedName by remember(userName) { mutableStateOf(userName) }

                            if (isEditingName) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    OutlinedTextField(
                                        value = editedName,
                                        onValueChange = { editedName = it },
                                        label = { Text("Developer Name", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f).heightIn(max = 56.dp).testTag("edit_dev_name_input"),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = {
                                            if (editedName.isNotBlank()) {
                                                onUpdateDeveloperName(editedName)
                                                isEditingName = false
                                            }
                                        },
                                        modifier = Modifier.size(36.dp).testTag("save_dev_name_button")
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = "Save", tint = Color(0xFF10B981))
                                    }
                                    IconButton(
                                        onClick = {
                                            isEditingName = false
                                            editedName = userName
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Red)
                                    }
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .clickable { isEditingName = true }
                                        .clip(RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = userName,
                                        fontWeight = FontWeight.Bold,
                                        color = textPrimaryCol,
                                        fontSize = 16.sp,
                                        modifier = Modifier.testTag("dev_name_text")
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit Developer Name",
                                        tint = textSecondaryCol,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }

                            Text(
                                text = userEmail,
                                color = textSecondaryCol,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 2.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Verified Tag
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .background(
                                        if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF1F5F9), 
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF10B981), CircleShape)
                                )
                                Text(
                                    text = "VERIFIED PUBLISHING PARTNER",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDarkMode) Color(0xFF6EE7B7) else Color(0xFF047857),
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ACTION MENU LIST
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = bgCol),
                        border = BorderStroke(1.dp, borderCol)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Publish Application option row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        onTriggerSubmitForm()
                                    }
                                    .testTag("trigger_user_submission_form")
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(if (isDarkMode) Color(0xFF1E293B) else Color(0xFFEFF6FF), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF2563EB),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Publish New Application",
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimaryCol,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Upload premium APKs or game builds",
                                        color = textSecondaryCol,
                                        fontSize = 11.sp
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = null,
                                    tint = textSecondaryCol,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Divider(color = borderCol.copy(alpha = 0.5f))

                            // Submissions option row
                            var showMySubmissionsDialog by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showMySubmissionsDialog = true }
                                    .testTag("view_submitted_apps_button")
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(if (isDarkMode) Color(0xFF1E293B) else Color(0xFFECFDF5), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = null,
                                        tint = if (isDarkMode) Color(0xFF34D399) else Color(0xFF059669),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "My Submissions Tracker",
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimaryCol,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "View live statuses & reviewer feedback",
                                        color = textSecondaryCol,
                                        fontSize = 11.sp
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (isDarkMode) Color(0xFF0F172A) else Color(0xFFECFDF5), 
                                                RoundedCornerShape(50)
                                            )
                                            .border(1.dp, if (isDarkMode) Color(0xFF34D399).copy(alpha = 0.4f) else Color(0xFF10B981).copy(alpha = 0.3f), RoundedCornerShape(50))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = submissions.size.toString(),
                                            color = if (isDarkMode) Color(0xFF34D399) else Color(0xFF047857),
                                            fontWeight = FontWeight.Black,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward,
                                        contentDescription = null,
                                        tint = textSecondaryCol,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (showMySubmissionsDialog) {
                                MySubmissionsStatusDialog(
                                    isDarkMode = isDarkMode,
                                    submissions = submissions,
                                    onDismiss = { showMySubmissionsDialog = false },
                                    onRequestUpdate = { sub ->
                                        showMySubmissionsDialog = false
                                        onRequestUpdate(sub)
                                    },
                                    onShowAppDetails = { app ->
                                        onShowAppDetails(app)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Sign Out Button Card Row
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDarkMode) Color(0xFF2C1616) else Color(0xFFFEF2F2)
                        ),
                        border = BorderStroke(
                            1.dp, 
                            if (isDarkMode) Color(0xFFEF4444).copy(alpha = 0.2f) else Color(0xFFFCA5A5).copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLogout()
                                    Toast.makeText(context, "Logged out of safe session.", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "Sign Out from Console",
                                color = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFFB91C1C),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    // ================= Anonymous Guest Frame =================
                    var customEmail by remember { mutableStateOf("guest.dev@gmail.com") }
                    var customName by remember { mutableStateOf("External Developer") }
                    var showCustomFields by remember { mutableStateOf(false) }

                    if (!showCustomFields) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(if (isDarkMode) Color(0xFF1E293B) else Color(0xFFF1F5F9)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = "Guest",
                                    tint = textSecondaryCol.copy(alpha = 0.7f),
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Guest Developer Workspace",
                                fontWeight = FontWeight.Bold,
                                color = textPrimaryCol,
                                fontSize = 16.sp
                            )

                            Text(
                                text = "Unauthenticated sandbox environment",
                                color = textSecondaryCol,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Connect a Developer Profile to submit applications, deploy custom revisions, and query real-time metadata.",
                                fontSize = 11.sp,
                                color = textSecondaryCol,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = { showCustomFields = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFFFFFFF),
                                    contentColor = textPrimaryCol
                                ),
                                border = BorderStroke(1.dp, borderCol),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Sign-In with Google Account",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = textPrimaryCol
                                    )
                                }
                            }
                        }
                    } else {
                        // Google Authentication Form Style
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Google Developer Identity",
                                color = textPrimaryCol,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Text(
                                text = "Fill your preferred credentials to authorize a developer console account.",
                                color = textSecondaryCol,
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.Start).padding(top = 2.dp)
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            OutlinedTextField(
                                value = customName,
                                onValueChange = { customName = it },
                                label = { Text("Developer Display Name", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = textPrimaryCol,
                                    unfocusedTextColor = textPrimaryCol,
                                    focusedBorderColor = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF2563EB),
                                    unfocusedBorderColor = borderCol
                                )
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = customEmail,
                                onValueChange = { customEmail = it },
                                label = { Text("Developer Contact Email", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = textPrimaryCol,
                                    unfocusedTextColor = textPrimaryCol,
                                    focusedBorderColor = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF2563EB),
                                    unfocusedBorderColor = borderCol
                                )
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    if (customEmail.isBlank() || !customEmail.contains("@")) {
                                        Toast.makeText(context, "Please enter a valid Google Account email", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    isAuthenticating = true
                                    coroutineScope.launch {
                                        kotlinx.coroutines.delay(1000)
                                        onLogin(customEmail, customName)
                                        isAuthenticating = false
                                        showCustomFields = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDarkMode) Color(0xFF3B82F6) else Color(0xFF2563EB)
                                )
                            ) {
                                Text("AUTHENTICATE DEV ID", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            TextButton(
                                onClick = { showCustomFields = false },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("Abandon Sign-In", fontSize = 12.sp, color = textSecondaryCol)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = borderCol.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(16.dp))

                // Beautiful standard option row for Dark Mode Theme Settings
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(surfaceCol)
                        .border(1.dp, borderCol, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Theme Icon",
                            tint = if (isDarkMode) Color(0xFFFFB300) else Color(0xFF01875F),
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Dark Mode Configuration",
                                color = textPrimaryCol,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Adjust app theme dynamically",
                                color = textSecondaryCol,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { onThemeToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF34D399),
                            checkedTrackColor = Color(0xFF102A24),
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color.LightGray.copy(alpha = 0.3f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Dark Store Client Developer Services. Sandbox operations, session credentials and application uploads remain locally encrypted.",
                    fontSize = 10.sp,
                    color = textSecondaryCol.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

// ========================================================
// MY SUBMISSIONS STATUS DIALOG WITH ADMIN PANEL STYLE
// ========================================================
@Composable
fun MySubmissionsStatusDialog(
    isDarkMode: Boolean,
    submissions: List<SubmissionEntity>,
    onDismiss: () -> Unit,
    onRequestUpdate: (SubmissionEntity) -> Unit,
    onShowAppDetails: (com.example.data.AppEntity) -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss) {
        val bgCol = if (isDarkMode) Color(0xFF13151C) else Color(0xFFFFFFFF)
        val surfaceCol = if (isDarkMode) Color(0xFF1D202B) else Color(0xFFF8FAFC)
        val borderCol = if (isDarkMode) Color(0xFF292E3D) else Color(0xFFE2E8F0)
        val textPrimaryColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF1E293B)
        val textSecondaryColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = bgCol),
            border = BorderStroke(1.dp, borderCol)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(if (isDarkMode) Color(0xFF1E293B) else Color(0xFFEFF6FF), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.List, 
                                contentDescription = "Submissions Icon",
                                tint = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF2563EB),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Developer Console",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF2563EB),
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "My Applications Status",
                                fontWeight = FontWeight.ExtraBold,
                                color = textPrimaryColor,
                                fontSize = 16.sp
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss, 
                        modifier = Modifier
                            .size(28.dp)
                            .background(if (isDarkMode) Color(0xFF292E3D) else Color(0xFFF1F5F9), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close, 
                            contentDescription = "Close", 
                            tint = textPrimaryColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = borderCol.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(16.dp))

                if (submissions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "No submissions",
                                tint = textSecondaryColor.copy(alpha = 0.4f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No sandboxed app submissions detected.",
                                color = textSecondaryColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(submissions, key = { it.id }) { sub ->
                            val isApprovedState = sub.status == "Approved"
                            val isRejectedState = sub.status == "Rejected"
                            val (statusBg, statusFg, statusTxt) = when {
                                isApprovedState -> Triple(
                                    if (isDarkMode) Color(0xFF064E3B) else Color(0xFFD1FAE5),
                                    if (isDarkMode) Color(0xFF34D399) else Color(0xFF065F46),
                                    "Approved"
                                )
                                isRejectedState -> Triple(
                                    if (isDarkMode) Color(0xFF7F1D1D) else Color(0xFFFEE2E2),
                                    if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFF991B1B),
                                    "Rejected"
                                )
                                else -> Triple(
                                    if (isDarkMode) Color(0xFF78350F) else Color(0xFFFEF3C7),
                                    if (isDarkMode) Color(0xFFFBBF24) else Color(0xFF92400E),
                                    "Pending Review"
                                )
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("user_sub_card_" + sub.id)
                                    .clickable {
                                        onDismiss()
                                        onShowAppDetails(sub.toAppEntity())
                                    },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = surfaceCol),
                                border = BorderStroke(1.dp, borderCol)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // AppLogo matching professional guidelines
                                        AppLogo(
                                            logoUrl = if (sub.screenshots.contains(",")) sub.screenshots.substringBefore(",") else sub.screenshots,
                                            appName = sub.name,
                                            packageName = sub.packageName,
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                        
                                        Spacer(modifier = Modifier.width(12.dp))
                                        
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = sub.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = textPrimaryColor
                                            )
                                            Text(
                                                text = sub.packageName,
                                                fontSize = 11.sp,
                                                color = textSecondaryColor,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "v${sub.version} • ${sub.category}",
                                                fontSize = 11.sp,
                                                color = textSecondaryColor.copy(alpha = 0.8f),
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        
                                        // Status Badge
                                        Surface(
                                            color = statusBg,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, statusFg.copy(alpha = 0.2f)),
                                            modifier = Modifier.padding(start = 4.dp)
                                        ) {
                                            Text(
                                                text = statusTxt,
                                                color = statusFg,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = sub.description,
                                        fontSize = 12.sp,
                                        color = textPrimaryColor.copy(alpha = 0.9f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = 16.sp
                                    )

                                    // Feedback Block
                                    if (sub.feedback.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        val feedbackBoxBg = if (isApprovedState) {
                                            if (isDarkMode) Color(0xFF0F1C15) else Color(0xFFECFDF5)
                                        } else if (isRejectedState) {
                                            if (isDarkMode) Color(0xFF2D1414) else Color(0xFFFEF2F2)
                                        } else {
                                            if (isDarkMode) Color(0xFF241C0F) else Color(0xFFFFFBEB)
                                        }
                                        val feedbackTxtColor = if (isApprovedState) {
                                            if (isDarkMode) Color(0xFF6EE7B7) else Color(0xFF065F46)
                                        } else if (isRejectedState) {
                                            if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFF991B1B)
                                        } else {
                                            if (isDarkMode) Color(0xFFFCD34D) else Color(0xFF92400E)
                                        }

                                        Surface(
                                            color = feedbackBoxBg,
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, feedbackTxtColor.copy(alpha = 0.15f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Text(
                                                    text = "Reviewer Feedback",
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 10.sp,
                                                    color = feedbackTxtColor,
                                                    letterSpacing = 0.5.sp
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = sub.feedback,
                                                    fontSize = 11.sp,
                                                    color = textPrimaryColor.copy(alpha = 0.9f),
                                                    lineHeight = 15.sp
                                                )
                                            }
                                        }
                                    }

                                    if (isApprovedState) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                onDismiss() // Dismiss the submissions dialog
                                                onRequestUpdate(sub) // Trigger update action
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(36.dp)
                                                .testTag("my_submissions_request_update_" + sub.id),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isDarkMode) Color(0xFF3B82F6) else Color(0xFF2563EB)
                                            ),
                                            contentPadding = PaddingValues(vertical = 0.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh, 
                                                contentDescription = "Refresh Icon", 
                                                modifier = Modifier.size(14.dp), 
                                                tint = Color.White
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Request App Update", 
                                                color = Color.White, 
                                                fontSize = 11.sp, 
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// 8a. SETTINGS & SYSTEM CONTROL PANEL
// ========================================================
@Composable
fun SettingsTabContent(
    isDarkMode: Boolean,
    onThemeToggle: () -> Unit,
    viewModel: StoreViewModel,
    accentGreen: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBgColor: Color,
    cardBorderColor: Color
) {
    val context = LocalContext.current
    val isAmoledMode by viewModel.isAmoledMode.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val autoInstall by viewModel.autoInstall.collectAsStateWithLifecycle()
    val notifyNewApps by viewModel.notifyNewApps.collectAsStateWithLifecycle()
    val notifyUpdates by viewModel.notifyUpdates.collectAsStateWithLifecycle()
    val notifyAnnouncements by viewModel.notifyAnnouncements.collectAsStateWithLifecycle()
    val notifySubmissions by viewModel.notifySubmissions.collectAsStateWithLifecycle()
    var apkCacheSize by remember { mutableStateOf(StorageManager.getApkCacheSize(context)) }

    val notices by viewModel.notices.collectAsStateWithLifecycle()
    val userRole by viewModel.userRole.collectAsStateWithLifecycle()
    val isAdmin = userRole == "admin"
    var previewNotice by remember { mutableStateOf<com.example.data.NoticeEntity?>(null) }
    var showPoliciesDialog by remember { mutableStateOf(false) }

    if (showPoliciesDialog) {
        com.example.view.EcosystemPolicyDialog(
            viewModel = viewModel,
            isMandatoryAccept = false,
            onDismiss = { showPoliciesDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = textPrimary
                ),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Manage system preferences, downloads and storage parameters",
                style = MaterialTheme.typography.bodySmall.copy(color = textSecondary)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Keep Android Open Campaign Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("keep_android_open_banner_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDarkMode) Color(0xFF131A26) else Color(0xFFF1F5F9)
                ),
                border = BorderStroke(1.5.dp, accentGreen.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(accentGreen.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Keep Android Open Logo",
                            tint = accentGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "KEEP ANDROID OPEN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentGreen,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Keep Android an open ecosystem. Competing app stores and creators deserve fair access and zero artificial restrictions. Support freedom and choice on your device.",
                            fontSize = 12.sp,
                            color = textPrimary,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                try {
                                    val uri = android.net.Uri.parse("https://keepandroidopen.org")
                                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                        setPackage("com.android.chrome")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val uri = android.net.Uri.parse("https://keepandroidopen.org")
                                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    } catch (ex: Exception) {
                                        Toast.makeText(context, "No web browser found.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentGreen,
                                contentColor = if (isDarkMode) Color.Black else Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                "TAKE ACTION IN CHROME",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Section: System Legal & Policies
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("settings_policies_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor),
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "LEGAL & POLICIES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = accentGreen,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("DARK Store Terms & Agreement", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("View the guidelines for developers, uploading rules, and store safety responsibilities.", color = textSecondary, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { showPoliciesDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accentGreen.copy(alpha = 0.15f), contentColor = accentGreen),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp).testTag("settings_view_policies_btn")
                        ) {
                            Text("VIEW", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Section 1: Appearance
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor),
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "APPEARANCE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = accentGreen,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Prominent theme toggling buttons (Light / Dark)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { if (isDarkMode) onThemeToggle() },
                            modifier = Modifier.weight(1f).height(38.dp).testTag("light_mode_selector_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isDarkMode) accentGreen else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                contentColor = if (!isDarkMode) (if (isDarkMode) Color.Black else Color.White) else textSecondary
                            )
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("LIGHT MODE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { if (!isDarkMode) onThemeToggle() },
                            modifier = Modifier.weight(1f).height(38.dp).testTag("dark_mode_selector_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkMode) accentGreen else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                contentColor = if (isDarkMode) (if (isDarkMode) Color.Black else Color.White) else textSecondary
                            )
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DARK MODE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Dark Theme Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = "Dark mode icon",
                                tint = textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Dark Mode Theme", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Switch to an eye-friendly dark tone", color = textSecondary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = { onThemeToggle() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentGreen,
                                checkedTrackColor = accentGreen.copy(alpha = 0.4f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Amoled Mode Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Amoled black mode icon",
                                tint = textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Pure OLED Black", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Save battery with ultra-deep blacks", color = textSecondary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = isAmoledMode,
                            onCheckedChange = { 
                                viewModel.setAmoledMode(it) 
                                Toast.makeText(context, "Pure OLED Black optimized!", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentGreen,
                                checkedTrackColor = accentGreen.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }
        }

        // Section 2: Download & Cache Management
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor),
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DOWNLOAD & STORAGE CAPABILITIES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = accentGreen,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Wi-Fi Only Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Wi-Fi Only icon",
                                tint = textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Download via Wi-Fi Only", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Pause downloads on mobile cellular network", color = textSecondary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = { viewModel.setWifiOnly(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentGreen,
                                checkedTrackColor = accentGreen.copy(alpha = 0.4f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Auto Install Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Auto install icon",
                                tint = textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Auto Start Installation", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Open system installer immediately upon download success", color = textSecondary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = autoInstall,
                            onCheckedChange = { viewModel.setAutoInstall(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentGreen,
                                checkedTrackColor = accentGreen.copy(alpha = 0.4f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // APK Storage Cleanup Section
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("APK File Storage Cache", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                val sizeStr = StorageManager.formatSizePublic(apkCacheSize)
                                Text("Currently occupying $sizeStr space", color = textSecondary, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Button(
                                onClick = {
                                    val deleted = StorageManager.clearApkCache(context)
                                    apkCacheSize = StorageManager.getApkCacheSize(context)
                                    if (deleted) {
                                        Toast.makeText(context, "Apk Cache cleared successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Nothing to clear or cache empty.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (apkCacheSize > 0) Color(0xFFEF5350).copy(alpha = 0.15f) else Color.Transparent,
                                    contentColor = if (apkCacheSize > 0) Color(0xFFEF5350) else textSecondary
                                ),
                                border = if (apkCacheSize == 0L) BorderStroke(1.dp, cardBorderColor) else null,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = "CLEAR CACHE", 
                                    fontSize = 11.sp, 
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section 2.5: Notification Preferences Customizer
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("notification_settings_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor),
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "NOTIFICATION PREFERENCES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = accentGreen,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // New App Alerts Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New Apps Alerts Icon",
                                tint = textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("New App Releases", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Get notified when any new apps are released", color = textSecondary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = notifyNewApps,
                            onCheckedChange = { viewModel.setNotifyNewApps(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentGreen,
                                checkedTrackColor = accentGreen.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.testTag("toggle_new_apps_alerts")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // App Update Alerts Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Updates Alerts Icon",
                                tint = textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("App Update Alerts", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Stay notified about updates of installed apps", color = textSecondary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = notifyUpdates,
                            onCheckedChange = { viewModel.setNotifyUpdates(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentGreen,
                                checkedTrackColor = accentGreen.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.testTag("toggle_update_alerts")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Admin Announcement Alerts Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "Announcements Icon",
                                tint = textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Admin Announcements", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Receive global notifications and channel status feeds", color = textSecondary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = notifyAnnouncements,
                            onCheckedChange = { viewModel.setNotifyAnnouncements(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentGreen,
                                checkedTrackColor = accentGreen.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.testTag("toggle_announcements_alerts")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Submissions Status Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = "Submissions Alerts Icon",
                                tint = textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Developer Submission Alerts", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Approval, rejection, and review state alerts", color = textSecondary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = notifySubmissions,
                            onCheckedChange = { viewModel.setNotifySubmissions(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentGreen,
                                checkedTrackColor = accentGreen.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.testTag("toggle_submissions_alerts")
                        )
                    }
                }
            }
        }

        // Section 3: System Status
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor),
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "SYSTEM REGISTRY & STATUS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = accentGreen,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Activity Mode", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Keep downloading and installing active in background", color = textSecondary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE6F4EA), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "ENABLED",
                                color = Color(0xFF01875F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Real-Time Background Battery Optimization Exemption Handler
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Real-Time Background Delivery", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                            val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
                                pm.isIgnoringBatteryOptimizations(context.packageName)
                            } else {
                                true
                            }
                            Text(
                                if (isIgnoring) "Exempted from system standby restriction. Live updates!" else "Restricted by system battery optimization. Notices may delay.", 
                                color = textSecondary, 
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        var isIgnoringOpt by remember { 
                            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                            mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) pm.isIgnoringBatteryOptimizations(context.packageName) else true)
                        }
                        val optLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                        DisposableEffect(optLifecycleOwner, context) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                                    isIgnoringOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) pm.isIgnoringBatteryOptimizations(context.packageName) else true
                                }
                            }
                            optLifecycleOwner.lifecycle.addObserver(observer)
                            onDispose {
                                optLifecycleOwner.lifecycle.removeObserver(observer)
                            }
                        }

                        Button(
                            onClick = {
                                if (isIgnoringOpt) {
                                    Toast.makeText(context, "App is already exempted for uninterrupted real-time pushes!", Toast.LENGTH_SHORT).show()
                                } else {
                                    try {
                                        val intent = Intent().apply {
                                            action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (ex: Exception) {
                                            Toast.makeText(context, "Please whitelist Dark Store manually in System App Info Battery Settings.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isIgnoringOpt) Color(0xFFE6F4EA) else Color(0xFFFFF4E5),
                                contentColor = if (isIgnoringOpt) Color(0xFF01875F) else Color(0xFFB06000)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = if (isIgnoringOpt) "EXEMPTED" else "OPTIMIZE",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Database Persistence", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Encrypted local SQL engine via Room", color = textSecondary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE6F4EA), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "ACTIVE",
                                color = Color(0xFF01875F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Device Administrator", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Unlocks application uninstallation capabilities", color = textSecondary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        var isDeviceAdminActive by remember { mutableStateOf(false) }
                        val lifecycleOwnerForAdmin = androidx.compose.ui.platform.LocalLifecycleOwner.current

                        DisposableEffect(lifecycleOwnerForAdmin, context) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                    isDeviceAdminActive = com.example.utils.ApkInstaller.isDeviceAdminActive(context)
                                }
                            }
                            lifecycleOwnerForAdmin.lifecycle.addObserver(observer)
                            onDispose {
                                lifecycleOwnerForAdmin.lifecycle.removeObserver(observer)
                            }
                        }

                        Button(
                            onClick = {
                                if (isDeviceAdminActive) {
                                    com.example.utils.ApkInstaller.removeDeviceAdmin(context)
                                    isDeviceAdminActive = false
                                    Toast.makeText(context, "Device admin deactivated", Toast.LENGTH_SHORT).show()
                                } else {
                                    com.example.utils.ApkInstaller.requestDeviceAdmin(context)
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDeviceAdminActive) Color(0xFFE6F4EA) else Color(0xFFFCE8E6),
                                contentColor = if (isDeviceAdminActive) Color(0xFF01875F) else Color(0xFFC5221F)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = if (isDeviceAdminActive) "ACTIVE" else "ACTIVATE",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Section 4: About
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor),
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "DARK STORE",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = textPrimary,
                            letterSpacing = 1.5.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "VERSION 2.5.0 (STABLE)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = accentGreen,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.0.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Dark Store is a sleek, high-performance private application repository built for seamless local package installations, remote developer deployment, and automated over-the-air update services.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "© 2026 Dark Store Repository. All Rights Reserved.",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = textSecondary.copy(alpha = 0.7f),
                            fontSize = 9.sp
                        )
                    )
                }
            }
        }

        // Section notices list
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("settings_notices_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor),
                border = BorderStroke(1.dp, cardBorderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ANNOUNCEMENTS & NOTICE OPTIONS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = accentGreen,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Realtime Sync Alerts", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Fetch latest notifications from administrators", color = textSecondary, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                viewModel.refreshNotices()
                                Toast.makeText(context, "Scanning notifications server...", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accentGreen.copy(alpha = 0.15f), contentColor = accentGreen),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("SYNC", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = cardBorderColor)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text("NOTICE HISTORY BOARD", fontSize = 12.sp, color = textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (notices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(textSecondary.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No system notices published yet.",
                                color = textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            notices.forEach { notice ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(textSecondary.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                                        .border(1.dp, cardBorderColor, RoundedCornerShape(10.dp))
                                        .clickable { previewNotice = notice }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (notice.imageUrl.isNotBlank()) Icons.Default.Info else Icons.Default.Notifications,
                                        contentDescription = "Notice list icon",
                                        tint = if (notice.imageUrl.isNotBlank()) Color(0xFF00AAFF) else {
                                            if (notice.targetAppId == "critical_announcement") Color(0xFFEF5350) else accentGreen
                                        },
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = notice.title,
                                                color = textPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (notice.targetAppId == "critical_announcement") {
                                                Box(
                                                    modifier = Modifier
                                                        .background(Color(0xFFEF5350).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "CRITICAL",
                                                        color = Color(0xFFEF5350),
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = notice.message,
                                            color = textSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (isAdmin) {
                                        IconButton(
                                            onClick = {
                                                viewModel.deleteNotice(notice.id) { success ->
                                                    if (success) {
                                                        Toast.makeText(context, "Purged announcement successfully.", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, "Notice deletion failed.", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(28.dp).testTag("delete_notice_btn_${notice.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete announcement",
                                                tint = Color(0xFFEF5350),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward,
                                        contentDescription = "Read full notice",
                                        tint = textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (previewNotice != null) {
        NoticeDetailsDialog(
            notice = previewNotice!!,
            onDismiss = { previewNotice = null }
        )
    }
}

// ========================================================
// 8. DEVELOPER PORTAL (Pin protected sandbox)
// ========================================================
@Composable
fun ConsoleTabContent(
    viewModel: StoreViewModel,
    apps: List<AppEntity>,
    accentGreen: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    userEmail: String,
    onShowAppDetails: (com.example.data.AppEntity) -> Unit = {}
) {
    val userRole by viewModel.userRole.collectAsStateWithLifecycle()
    val userUid by viewModel.userUid.collectAsStateWithLifecycle()
    val submissions by viewModel.submissions.collectAsStateWithLifecycle()
    val termsAgreements by viewModel.termsAgreements.collectAsStateWithLifecycle()
    val devName by viewModel.devName.collectAsStateWithLifecycle()
    
    val isAdmin = userRole == "admin" || userEmail.equals("davidstha900@gmail.com", ignoreCase = true) || userUid == "JN4BPhEKBBRUb5hpMdQJQmRrjiq1"
    
    if (!isAdmin) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Access Denied",
                tint = Color(0xFFEF5350),
                modifier = Modifier.size(60.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "Access Denied",
                fontWeight = FontWeight.Bold,
                color = textPrimary,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "The Developer Portal is restricted to Administrators only. Regular users cannot submit applications.",
                color = textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        return
    }

    val displayedApps = remember(isAdmin, apps, userEmail) {
        if (isAdmin) apps else apps.filter { it.submittedBy.equals(userEmail, ignoreCase = true) }
    }

    var pinValue by remember { mutableStateOf("") }
    // Secure default access authorization: normal developers bypass security pin, admins verify pin "4321" once
    var isAuthorized by remember(userEmail, userRole) { mutableStateOf(!isAdmin) }
    
    var showAddForm by remember { mutableStateOf(false) }
    var showSendNoticeForm by remember { mutableStateOf(false) }
    var editingApp by remember { mutableStateOf<AppEntity?>(null) }
    var editingSubmission by remember { mutableStateOf<SubmissionEntity?>(null) }
    var appSubmittingUpdateFor by remember { mutableStateOf<SubmissionEntity?>(null) }
    var showPushUpdateFormFor by remember { mutableStateOf<SubmissionEntity?>(null) }
    var showUpdateScreenshotsFormFor by remember { mutableStateOf<SubmissionEntity?>(null) }
    val isDarkMode = androidx.compose.foundation.isSystemInDarkTheme()
    var submissionToReject by remember { mutableStateOf<SubmissionEntity?>(null) }
    var rejectionReason by remember { mutableStateOf("") }
    var submissionToApprove by remember { mutableStateOf<SubmissionEntity?>(null) }
    var approvalFeedback by remember { mutableStateOf("") }
    
    // Advanced powerful audit states
    val expandedSubmissions = remember { mutableStateMapOf<String, Boolean>() }
    val submissionChecks = remember { mutableStateMapOf<String, Set<String>>() }
    var screenshotDialogUrl by remember { mutableStateOf<String?>(null) }
    
    // Admin Subviews Segment selection: "SUBMISSIONS" vs "CATALOG"
    var adminSegmentIndex by remember { mutableStateOf(0) }
    var adminSearchQuery by remember { mutableStateOf("") }

    val filteredSubmissions = remember(submissions, adminSearchQuery) {
        if (adminSearchQuery.isBlank()) {
            submissions
        } else {
            submissions.filter {
                it.name.contains(adminSearchQuery, ignoreCase = true) ||
                        it.packageName.contains(adminSearchQuery, ignoreCase = true) ||
                        it.developer.contains(adminSearchQuery, ignoreCase = true) ||
                        it.submittedBy.contains(adminSearchQuery, ignoreCase = true)
            }
        }
    }

    val filteredApps = remember(displayedApps, adminSearchQuery) {
        if (adminSearchQuery.isBlank()) {
            displayedApps
        } else {
            displayedApps.filter {
                it.name.contains(adminSearchQuery, ignoreCase = true) ||
                        it.packageName.contains(adminSearchQuery, ignoreCase = true) ||
                        it.developer.contains(adminSearchQuery, ignoreCase = true) ||
                        it.submittedBy.contains(adminSearchQuery, ignoreCase = true)
            }
        }
    }

    var appToManageSuspension by remember { mutableStateOf<AppEntity?>(null) }
    var suspensionDialogReason by remember { mutableStateOf("") }
    var isSuspensionDialogActive by remember { mutableStateOf(false) }

    // Update config admin fields
    val currentUpdateConfig by viewModel.updateConfig.collectAsStateWithLifecycle()
    var uVersionCode by remember { mutableStateOf("") }
    var uVersionName by remember { mutableStateOf("") }
    var uApkUrl by remember { mutableStateOf("") }
    var uTitle by remember { mutableStateOf("") }
    var uMessage by remember { mutableStateOf("") }
    var uForceUpdate by remember { mutableStateOf(false) }
    var uHasInitialized by remember { mutableStateOf(false) }
    var uPushStatusMessage by remember { mutableStateOf("") }
    var uPushIsLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentUpdateConfig) {
        currentUpdateConfig?.let { cfg ->
            if (!uHasInitialized) {
                uVersionCode = cfg.latestVersionCode.toString()
                uVersionName = cfg.latestVersionName
                uApkUrl = cfg.apkDownloadUrl
                uTitle = cfg.updateTitle
                uMessage = cfg.updateMessage
                uForceUpdate = cfg.forceUpdate
                uHasInitialized = true
            }
        }
    }
    
    val context = LocalContext.current

    // Trigger submissions refresh whenever the screen becomes visible or authorization changes
    LaunchedEffect(userRole, userEmail) {
        viewModel.refreshSubmissions()
        viewModel.refreshMarketplace()
        viewModel.refreshTermsAgreements()
    }

    if (!isAuthorized) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked console",
                tint = accentGreen,
                modifier = Modifier.size(60.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                "Developer Authorization Console",
                fontWeight = FontWeight.Bold,
                color = textPrimary,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                "Access developer portal of Dark Store to manage catalog entries, delete packages, or upload applications directly.",
                color = textSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = pinValue,
                onValueChange = { pinValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_password_field"),
                placeholder = { Text("Developer Security PIN") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedBorderColor = accentGreen,
                    unfocusedBorderColor = cardBorderColor
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (pinValue == "4321") {
                        isAuthorized = true
                    } else {
                        Toast.makeText(context, "Incorrect credential PIN!", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("admin_auth_submit_button")
            ) {
                Text("AUTHORIZE CONSOLE", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        // ─── NEW REDESIGNED ADMIN PANEL ───────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── HEADER ─────────────────────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isAdmin) "⚡ ADMIN CONTROL CENTER" else "🛠 DEVELOPER PORTAL",
                            color = accentGreen,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = if (isAdmin) "Manage catalog, submissions & deployments" else "Submit and track your app reviews",
                            color = textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    if (isAdmin) {
                        OutlinedButton(
                            onClick = { isAuthorized = false; pinValue = "" },
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, cardBorderColor),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(13.dp), tint = textSecondary)
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Lock", fontSize = 11.sp, color = textSecondary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── ADMIN IDENTITY CARD ────────────────────────────────────────────────
            if (isAdmin) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = accentGreen.copy(alpha = 0.07f)),
                        border = BorderStroke(1.dp, accentGreen.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(accentGreen.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = userEmail.ifBlank { "A" }.take(1).uppercase(),
                                    color = accentGreen,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(userEmail.ifBlank { "davidstha900@gmail.com" }, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .background(accentGreen.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Text("ADMINISTRATOR", color = accentGreen, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Box(modifier = Modifier.size(6.dp).background(Color(0xFF10B981), RoundedCornerShape(50)))
                                        Text("Active", color = Color(0xFF10B981), fontSize = 10.sp)
                                    }
                                }
                            }
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = accentGreen.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }

            // ── ANALYTICS DASHBOARD ─────────────────────────────────────────────────
            if (isAdmin) {
                item {
                    val liveCount = apps.size
                    val pendingCount = submissions.count { it.status == "Pending" }
                    val approvedCount = submissions.count { it.status == "Approved" }
                    val rejectedCount = submissions.count { it.status == "Rejected" }
                    val totalSubmissions = submissions.size
                    val complianceRate = if (totalSubmissions == 0) 100 else (approvedCount * 100) / totalSubmissions
                    val categoriesList = remember { listOf("Utilities", "Games", "Tools", "Entertainment") }
                    val categoryCounts = remember(apps) {
                        categoriesList.associateWith { cat -> apps.count { it.category.equals(cat, ignoreCase = true) } }
                    }
                    val maxCat = remember(categoryCounts) { (categoryCounts.values.maxOrNull() ?: 1).coerceAtLeast(1) }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBgColor),
                        border = BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Analytics, contentDescription = null, tint = accentGreen, modifier = Modifier.size(18.dp))
                                    Text("TELEMETRY", color = accentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.sp)
                                }
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF10B981).copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text("● LIVE", color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }

                            // 4 stat tiles
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val stats = listOf(
                                    Triple("Live Apps", liveCount, Color(0xFF38BDF8)),
                                    Triple("Pending", pendingCount, Color(0xFFFBBF24)),
                                    Triple("Approved", approvedCount, Color(0xFF34D399)),
                                    Triple("Rejected", rejectedCount, Color(0xFFF87171))
                                )
                                stats.forEach { (label, value, color) ->
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(color.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
                                            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(value.toString(), color = color, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                                        Text(label, color = textSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                                    }
                                }
                            }

                            HorizontalDivider(color = cardBorderColor)

                            // Compliance bar
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("Compliance Rate", fontSize = 12.sp, color = textPrimary, fontWeight = FontWeight.SemiBold)
                                    Text("Approved vs total submissions", fontSize = 10.sp, color = textSecondary)
                                }
                                Text(
                                    "$complianceRate%",
                                    color = if (complianceRate >= 75) Color(0xFF10B981) else if (complianceRate >= 50) Color(0xFFF59E0B) else Color(0xFFEF4444),
                                    fontSize = 16.sp, fontWeight = FontWeight.ExtraBold
                                )
                            }
                            val compColor = if (complianceRate >= 75) Color(0xFF10B981) else if (complianceRate >= 50) Color(0xFFF59E0B) else Color(0xFFEF4444)
                            Box(modifier = Modifier.fillMaxWidth().height(7.dp).background(cardBorderColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))) {
                                Box(modifier = Modifier.fillMaxWidth(complianceRate / 100f).height(7.dp).background(compColor, RoundedCornerShape(4.dp)))
                            }

                            HorizontalDivider(color = cardBorderColor)

                            // Category distribution
                            Text("CATALOG DISTRIBUTION", fontSize = 10.sp, color = textSecondary, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                            categoryCounts.forEach { (cat, count) ->
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(cat, color = textPrimary, fontSize = 11.sp, modifier = Modifier.width(85.dp))
                                    Box(modifier = Modifier.weight(1f).height(8.dp).background(textSecondary.copy(alpha = 0.08f), RoundedCornerShape(4.dp))) {
                                        val frac = if (maxCat > 0) count.toFloat() / maxCat else 0f
                                        Box(modifier = Modifier.fillMaxWidth(frac).height(8.dp).background(accentGreen, RoundedCornerShape(4.dp)))
                                    }
                                    Text("$count", color = textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }
                }
            }

            // ── QUICK ACTION BUTTONS ───────────────────────────────────────────────
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Add App
                    Button(
                        onClick = { showAddForm = true },
                        modifier = Modifier.weight(1f).height(50.dp).testTag("admin_add_app_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                            Text("ADD APP", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp)
                        }
                    }
                    // Send Notice
                    Button(
                        onClick = { showSendNoticeForm = true },
                        modifier = Modifier.weight(1f).height(50.dp).testTag("admin_send_notice_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                            Text("NOTICE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp)
                        }
                    }
                    // Sync
                    Button(
                        onClick = {
                            viewModel.refreshMarketplace(force = true)
                            viewModel.refreshSubmissions()
                            viewModel.refreshTermsAgreements()
                            viewModel.refreshUpdateConfig()
                            Toast.makeText(context, "Syncing cloud nodes...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = cardBgColor, contentColor = textPrimary),
                        border = BorderStroke(1.dp, cardBorderColor),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp), tint = textSecondary)
                            Text("SYNC", color = textSecondary, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp)
                        }
                    }
                }
            }

            // ── SEGMENTED TAB BAR ─────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val tabs = listOf(
                            Pair("Submissions", Icons.Default.Inbox),
                            Pair("Live Catalog", Icons.Default.Store),
                            Pair("Push Update", Icons.Default.SystemUpdate)
                        )
                        tabs.forEachIndexed { idx, (label, icon) ->
                            val selected = adminSegmentIndex == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) accentGreen else Color.Transparent)
                                    .clickable {
                                        adminSegmentIndex = idx
                                        adminSearchQuery = ""
                                        if (idx == 2) viewModel.refreshUpdateConfig()
                                    }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (selected) Color.White else textSecondary)
                                    Text(label, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = if (selected) Color.White else textSecondary, letterSpacing = 0.2.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ── SEARCH BAR ────────────────────────────────────────────────────────
            if (adminSegmentIndex == 0 || adminSegmentIndex == 1) {
                item {
                    OutlinedTextField(
                        value = adminSearchQuery,
                        onValueChange = { adminSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search by name, package, or developer…", fontSize = 12.sp, color = textSecondary) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = accentGreen, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (adminSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { adminSearchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = null, tint = textSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            focusedContainerColor = cardBgColor,
                            unfocusedContainerColor = cardBgColor,
                            focusedBorderColor = accentGreen,
                            unfocusedBorderColor = cardBorderColor
                        )
                    )
                }
            }

            // ── SUBMISSIONS TAB ──────────────────────────────────────────────────
            if (adminSegmentIndex == 0) {
                if (filteredSubmissions.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Inbox, contentDescription = null, tint = textSecondary, modifier = Modifier.size(48.dp))
                                Text("No submissions found", color = textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(if (adminSearchQuery.isBlank()) "No apps have been submitted yet." else "No results for \"$adminSearchQuery\"", color = textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    // Group by status
                    val pendingSubs = filteredSubmissions.filter { it.status == "Pending" }
                    val approvedSubs = filteredSubmissions.filter { it.status == "Approved" }
                    val rejectedSubs = filteredSubmissions.filter { it.status == "Rejected" }

                    fun statusColor(status: String) = when (status) {
                        "Approved" -> Color(0xFF10B981)
                        "Rejected" -> Color(0xFFEF4444)
                        else -> Color(0xFFF59E0B)
                    }
                    fun statusIcon(status: String) = when (status) {
                        "Approved" -> Icons.Default.CheckCircle
                        "Rejected" -> Icons.Default.Cancel
                        else -> Icons.Default.HourglassEmpty
                    }

                    if (pendingSubs.isNotEmpty()) {
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                                Text("PENDING REVIEW (${pendingSubs.size})", color = Color(0xFFF59E0B), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                            }
                        }
                        items(pendingSubs, key = { it.id }) { sub ->
                            AdminSubmissionCard(
                                sub = sub,
                                isAdmin = isAdmin,
                                accentGreen = accentGreen,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                cardBgColor = cardBgColor,
                                cardBorderColor = cardBorderColor,
                                statusColor = statusColor(sub.status),
                                statusIcon = statusIcon(sub.status),
                                onApprove = { submissionToApprove = sub; approvalFeedback = "" },
                                onReject = { submissionToReject = sub; rejectionReason = "" },
                                onEdit = { editingSubmission = sub },
                                onCardClick = { onShowAppDetails(sub.toAppEntity()) }
                            )
                        }
                    }
                    if (approvedSubs.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                                Text("APPROVED (${approvedSubs.size})", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                            }
                        }
                        items(approvedSubs, key = { it.id }) { sub ->
                            AdminSubmissionCard(
                                sub = sub,
                                isAdmin = isAdmin,
                                accentGreen = accentGreen,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                cardBgColor = cardBgColor,
                                cardBorderColor = cardBorderColor,
                                statusColor = statusColor(sub.status),
                                statusIcon = statusIcon(sub.status),
                                onApprove = { submissionToApprove = sub; approvalFeedback = "" },
                                onReject = { submissionToReject = sub; rejectionReason = "" },
                                onEdit = { editingSubmission = sub },
                                onCardClick = { onShowAppDetails(sub.toAppEntity()) }
                            )
                        }
                    }
                    if (rejectedSubs.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Cancel, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                                Text("REJECTED (${rejectedSubs.size})", color = Color(0xFFEF4444), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                            }
                        }
                        items(rejectedSubs, key = { it.id }) { sub ->
                            AdminSubmissionCard(
                                sub = sub,
                                isAdmin = isAdmin,
                                accentGreen = accentGreen,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                cardBgColor = cardBgColor,
                                cardBorderColor = cardBorderColor,
                                statusColor = statusColor(sub.status),
                                statusIcon = statusIcon(sub.status),
                                onApprove = { submissionToApprove = sub; approvalFeedback = "" },
                                onReject = { submissionToReject = sub; rejectionReason = "" },
                                onEdit = { editingSubmission = sub },
                                onCardClick = { onShowAppDetails(sub.toAppEntity()) }
                            )
                        }
                    }
                }
            }

            // ── LIVE CATALOG TAB ─────────────────────────────────────────────────
            if (adminSegmentIndex == 1) {
                if (filteredApps.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Store, contentDescription = null, tint = textSecondary, modifier = Modifier.size(48.dp))
                                Text("Catalog is empty", color = textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Add your first app using the ADD APP button above.", color = textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    items(filteredApps, key = { it.id }) { app ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBgColor),
                            border = BorderStroke(1.dp, if (app.isSuspended) Color(0xFFEF4444).copy(alpha = 0.4f) else cardBorderColor)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // App icon
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(accentGreen.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (app.logo.isNotBlank()) {
                                        AsyncImage(model = app.logo, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    } else {
                                        Text(app.name.take(1).uppercase(), color = accentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(app.name, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                                        if (app.isSuspended) {
                                            Box(modifier = Modifier.background(Color(0xFFEF4444).copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                                Text("SUSPENDED", color = Color(0xFFEF4444), fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                                            }
                                        }
                                    }
                                    Text("${app.developer}  •  v${app.version}  •  ${app.category}", color = textSecondary, fontSize = 11.sp, maxLines = 1)
                                    Text(app.packageName, color = textSecondary.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1)
                                }
                            }
                            // Action row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { editingApp = app },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, accentGreen.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp), tint = accentGreen)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Edit", fontSize = 11.sp, color = accentGreen, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { showPushUpdateFormFor = app.toSubmissionEntity(); },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFF6366F1))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Update", fontSize = 11.sp, color = Color(0xFF6366F1), fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { appToManageSuspension = app; suspensionDialogReason = ""; isSuspensionDialogActive = true },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, (if (app.isSuspended) Color(0xFF10B981) else Color(0xFFEF4444)).copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    val suspendColor = if (app.isSuspended) Color(0xFF10B981) else Color(0xFFEF4444)
                                    Icon(if (app.isSuspended) Icons.Default.PlayArrow else Icons.Default.Block, contentDescription = null, modifier = Modifier.size(13.dp), tint = suspendColor)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (app.isSuspended) "Resume" else "Suspend", fontSize = 11.sp, color = suspendColor, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // ── PUSH UPDATE TAB ───────────────────────────────────────────────────
            if (isAdmin && adminSegmentIndex == 2) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBgColor),
                        border = BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(modifier = Modifier.size(36.dp).background(Color(0xFF6366F1).copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(20.dp))
                                }
                                Column {
                                    Text("In-App Update Control", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = textPrimary)
                                    Text("Push mandatory or optional APK updates to all users", color = textSecondary, fontSize = 11.sp)
                                }
                            }

                            HorizontalDivider(color = cardBorderColor)

                            // Live Preview Banner
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = accentGreen.copy(alpha = 0.05f)),
                                border = BorderStroke(1.dp, accentGreen.copy(alpha = 0.2f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Icon(Icons.Default.Preview, contentDescription = null, tint = accentGreen, modifier = Modifier.size(14.dp))
                                            Text("LIVE DIALOG PREVIEW", color = accentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .background((if (uForceUpdate) Color(0xFFEF4444) else Color(0xFF10B981)).copy(alpha = 0.12f), RoundedCornerShape(5.dp))
                                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                        ) {
                                            Text(if (uForceUpdate) "⚠ MANDATORY" else "✓ OPTIONAL", color = if (uForceUpdate) Color(0xFFEF4444) else Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = cardBgColor), border = BorderStroke(1.dp, cardBorderColor.copy(alpha = 0.5f))) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(uTitle.ifBlank { "New Update Available!" }, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("Version: ${uVersionName.ifBlank { "—" }}  (Build ${uVersionCode.ifBlank { "0" }})", color = textSecondary, fontSize = 11.sp)
                                            Text(uMessage.ifBlank { "Update details will appear here…" }, color = textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = cardBorderColor)

                            // Form fields
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = uVersionCode,
                                    onValueChange = { uVersionCode = it },
                                    label = { Text("Version Code *", fontSize = 11.sp) },
                                    placeholder = { Text("e.g. 12", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, focusedBorderColor = accentGreen, unfocusedBorderColor = cardBorderColor)
                                )
                                OutlinedTextField(
                                    value = uVersionName,
                                    onValueChange = { uVersionName = it },
                                    label = { Text("Version Name *", fontSize = 11.sp) },
                                    placeholder = { Text("e.g. 1.2.0", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, focusedBorderColor = accentGreen, unfocusedBorderColor = cardBorderColor)
                                )
                            }
                            OutlinedTextField(
                                value = uApkUrl,
                                onValueChange = { uApkUrl = it },
                                label = { Text("APK Download URL *", fontSize = 11.sp) },
                                placeholder = { Text("https://…", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, focusedBorderColor = accentGreen, unfocusedBorderColor = cardBorderColor)
                            )
                            OutlinedTextField(
                                value = uTitle,
                                onValueChange = { uTitle = it },
                                label = { Text("Update Dialog Title *", fontSize = 11.sp) },
                                placeholder = { Text("e.g. Dark Store v1.2 is here!", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, focusedBorderColor = accentGreen, unfocusedBorderColor = cardBorderColor)
                            )
                            OutlinedTextField(
                                value = uMessage,
                                onValueChange = { uMessage = it },
                                label = { Text("Update Message / Changelog *", fontSize = 11.sp) },
                                placeholder = { Text("What's new in this version…", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth().height(100.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, focusedBorderColor = accentGreen, unfocusedBorderColor = cardBorderColor)
                            )

                            // Force update toggle
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = if (uForceUpdate) Color(0xFFEF4444).copy(alpha = 0.05f) else cardBgColor),
                                border = BorderStroke(1.dp, if (uForceUpdate) Color(0xFFEF4444).copy(alpha = 0.3f) else cardBorderColor)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Force Update", color = textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text("Users cannot dismiss this update dialog", color = textSecondary, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = uForceUpdate,
                                        onCheckedChange = { uForceUpdate = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFEF4444), uncheckedTrackColor = cardBorderColor)
                                    )
                                }
                            }

                            // Status message
                            if (uPushStatusMessage.isNotBlank()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (uPushStatusMessage.contains("✅") || uPushStatusMessage.contains("success", ignoreCase = true))
                                            Color(0xFF10B981).copy(alpha = 0.08f) else Color(0xFFEF4444).copy(alpha = 0.08f)
                                    )
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val isSuccess = uPushStatusMessage.contains("✅") || uPushStatusMessage.contains("success", ignoreCase = true)
                                        Icon(if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error, contentDescription = null, tint = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                        Text(uPushStatusMessage, color = textPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                                    }
                                }
                            }

                            // Push button
                            Button(
                                onClick = {
                                    val vCode = uVersionCode.trim().toIntOrNull()
                                    if (vCode == null || uVersionName.isBlank() || uApkUrl.isBlank() || uTitle.isBlank() || uMessage.isBlank()) {
                                        uPushStatusMessage = "❌ All fields are required. Version Code must be a number."
                                        return@Button
                                    }
                                    uPushIsLoading = true
                                    uPushStatusMessage = ""
                                    val cfg = com.example.data.UpdateConfigEntity(
                                        latestVersionCode = vCode,
                                        latestVersionName = uVersionName.trim(),
                                        apkDownloadUrl = uApkUrl.trim(),
                                        updateTitle = uTitle.trim(),
                                        updateMessage = uMessage.trim(),
                                        forceUpdate = uForceUpdate
                                    )
                                    viewModel.saveUpdateConfig(cfg) { success, errorMsg ->
                                        uPushIsLoading = false
                                        uPushStatusMessage = if (success) {
                                            "✅ Update config pushed to Firebase RTDB successfully!"
                                        } else {
                                            "❌ Push failed: ${errorMsg ?: "Unknown error. Check Firebase rules."}"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (uForceUpdate) Color(0xFFEF4444) else Color(0xFF6366F1)),
                                enabled = !uPushIsLoading
                            ) {
                                if (uPushIsLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    if (uPushIsLoading) "PUSHING TO FIREBASE…" else "🚀 PUSH ${if (uForceUpdate) "MANDATORY" else "OPTIONAL"} UPDATE",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp
                                )
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.refreshUpdateConfig()
                                        uHasInitialized = false
                                        uPushStatusMessage = "⟳ Pulled live config from Firebase"
                                    },
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, cardBorderColor)
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp), tint = textSecondary)
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("Pull Live", fontSize = 11.sp, color = textSecondary)
                                }
                                OutlinedButton(
                                    onClick = {
                                        uVersionCode = ""; uVersionName = ""; uApkUrl = ""; uTitle = ""; uMessage = ""; uForceUpdate = false; uPushStatusMessage = ""
                                    },
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, cardBorderColor)
                                ) {
                                    Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(14.dp), tint = textSecondary)
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("Clear Form", fontSize = 11.sp, color = textSecondary)
                                }
                            }
                        }
                    }
                }
            }

        } // end LazyColumn

        // ── OVERLAY FORMS (outside LazyColumn, drawn on top) ──────────────────
        if (showAddForm || editingApp != null) {
            val appToEdit = editingApp
            AddNewAppForm(
                existingApp = appToEdit,
                isForAdmin = isAdmin,
                userEmail = userEmail,
                defaultDeveloperName = devName,
                onDismiss = { showAddForm = false; editingApp = null },
                onSubmit = { appData ->
                    if (isAdmin) {
                        viewModel.addOrUpdateAppInCatalog(appData) { success ->
                            Toast.makeText(context, if (success) "App saved to catalog!" else "Save failed.", Toast.LENGTH_SHORT).show()
                            if (success) { showAddForm = false; editingApp = null }
                        }
                    } else {
                        viewModel.submitAppForReview(
                            name = appData.name, packageName = appData.packageName, description = appData.description,
                            apkUrl = appData.apkUrl, screenshots = appData.screenshots, logo = appData.logo,
                            category = appData.category, version = appData.version, hasAds = appData.hasAds
                        ) { success, msg ->
                            Toast.makeText(context, msg ?: if (success) "Submitted!" else "Failed", Toast.LENGTH_SHORT).show()
                            if (success) { showAddForm = false; editingApp = null }
                        }
                    }
                }
            )
        }

        if (editingSubmission != null) {
            val sub = editingSubmission!!
            AddNewAppForm(
                existingApp = AppEntity(
                    id = sub.id, name = sub.name, developer = sub.developer, version = sub.version,
                    size = "18 MB", category = sub.category, rating = "4.5", description = sub.description,
                    logo = sub.logo, screenshots = sub.screenshots, apkUrl = sub.apkUrl,
                    packageName = sub.packageName, isFeatured = false, isPopular = true,
                    isRecent = true, versionCode = 1, isApproved = true, submittedBy = sub.submittedBy, hasAds = sub.hasAds
                ),
                isForAdmin = isAdmin,
                userEmail = userEmail,
                defaultDeveloperName = devName,
                onDismiss = { editingSubmission = null },
                onSubmit = { appData ->
                    val updatedSub = sub.copy(
                        name = appData.name, packageName = appData.packageName, version = appData.version,
                        description = appData.description, apkUrl = appData.apkUrl, screenshots = appData.screenshots,
                        logo = appData.logo, category = appData.category, developer = appData.developer, hasAds = appData.hasAds
                    )
                    viewModel.editSubmissionDetails(updatedSub) { success ->
                        Toast.makeText(context, if (success) "Submission updated!" else "Failed to save changes.", Toast.LENGTH_SHORT).show()
                        if (success) editingSubmission = null
                    }
                }
            )
        }

        if (appSubmittingUpdateFor != null) {
            val originalSub = appSubmittingUpdateFor!!
            val mappedSubApp = AppEntity(
                id = originalSub.id,
                name = originalSub.name,
                developer = originalSub.developer,
                version = originalSub.version,
                size = "18 MB",
                category = originalSub.category,
                rating = "4.5",
                description = originalSub.description,
                logo = originalSub.logo,
                screenshots = originalSub.screenshots,
                apkUrl = originalSub.apkUrl,
                packageName = originalSub.packageName,
                isFeatured = false,
                isPopular = true,
                isRecent = true,
                versionCode = 1,
                isApproved = true,
                submittedBy = originalSub.submittedBy,
                hasAds = originalSub.hasAds
            )
            AddNewAppForm(
                existingApp = mappedSubApp,
                isForAdmin = isAdmin,
                userEmail = userEmail,
                defaultDeveloperName = devName,
                onDismiss = { appSubmittingUpdateFor = null },
                onSubmit = { appData ->
                    viewModel.submitAppForReview(
                        name = appData.name,
                        packageName = appData.packageName,
                        description = appData.description,
                        apkUrl = appData.apkUrl,
                        screenshots = appData.screenshots,
                        logo = appData.logo,
                        category = appData.category,
                        version = appData.version,
                        hasAds = appData.hasAds
                    ) { success, msg ->
                        Toast.makeText(context, msg ?: "Update request dispatched", Toast.LENGTH_SHORT).show()
                        if (success) appSubmittingUpdateFor = null
                    }
                }
            )
        }

        if (showSendNoticeForm) {
            SendNoticeFormDialog(
                apps = apps,
                onDismiss = { showSendNoticeForm = false },
                onSubmit = { notice, fcmServerKey ->
                    viewModel.sendNotice(notice, fcmServerKey) { success, msg ->
                        Toast.makeText(context, msg ?: "Announcement published", Toast.LENGTH_LONG).show()
                        if (success) {
                            showSendNoticeForm = false
                        }
                    }
                }
            )
        }

        if (submissionToApprove != null) {
            val sub = submissionToApprove!!
            AlertDialog(
                onDismissRequest = { submissionToApprove = null },
                shape = RoundedCornerShape(20.dp),
                containerColor = cardBgColor,
                title = {
                    Text(
                        text = "Specify Approval Feedback (Optional)",
                        fontWeight = FontWeight.Bold,
                        color = accentGreen,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "You can write optional approval feedback explaining guidelines matched or congratulating the developer. This feedback is saved in the database and visible to the developer.",
                            color = textSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = approvalFeedback,
                            onValueChange = { approvalFeedback = it },
                            label = { Text("Approval Feedback", color = textSecondary) },
                            singleLine = false,
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = accentGreen,
                                unfocusedIndicatorColor = cardBorderColor,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("approval_feedback_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.approveSubmission(sub, feedback = approvalFeedback.ifBlank { "Approved and published inside Dark Store catalog." }) { success, msg ->
                                Toast.makeText(context, msg ?: "Submission Approved", Toast.LENGTH_SHORT).show()
                                submissionToApprove = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("confirm_approve_btn")
                    ) {
                        Text("APPROVE APP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { submissionToApprove = null },
                        modifier = Modifier.testTag("cancel_approve_btn")
                    ) {
                        Text("CANCEL", color = textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            )
        }

        if (submissionToReject != null) {
            val sub = submissionToReject!!
            AlertDialog(
                onDismissRequest = { submissionToReject = null },
                shape = RoundedCornerShape(20.dp),
                containerColor = cardBgColor,
                title = {
                    Text(
                        text = "Specify Rejection Reason",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF5350),
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Please write a concise reason explaining why the application '${sub.name}' was rejected. This reason will be dispatched instantly to the developer's client device via FCM.",
                            color = textSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = rejectionReason,
                            onValueChange = { rejectionReason = it },
                            label = { Text("Reason for Rejection", color = textSecondary) },
                            singleLine = false,
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = accentGreen,
                                unfocusedIndicatorColor = cardBorderColor,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("rejection_reason_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.rejectSubmission(sub, reason = rejectionReason.ifBlank { "Submission did not satisfy safety regulations." }) { success, msg ->
                                Toast.makeText(context, msg ?: "Submission Rejected", Toast.LENGTH_SHORT).show()
                                submissionToReject = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("confirm_reject_btn")
                    ) {
                        Text("REJECT APP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { submissionToReject = null },
                        modifier = Modifier.testTag("cancel_reject_btn")
                    ) {
                        Text("CANCEL", color = textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            )
        }

        if (isSuspensionDialogActive && appToManageSuspension != null) {
            val app = appToManageSuspension!!
            AlertDialog(
                onDismissRequest = { isSuspensionDialogActive = false; appToManageSuspension = null },
                shape = RoundedCornerShape(20.dp),
                containerColor = cardBgColor,
                title = {
                    Text(
                        text = if (app.isSuspended) "Unsuspend Application" else "Suspend Application",
                        fontWeight = FontWeight.Bold,
                        color = if (app.isSuspended) Color(0xFF10B981) else Color(0xFFEF5350),
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (app.isSuspended) 
                                "Are you sure you want to reactivate and unsuspend '${app.name}'? Regulating its visibility back to standard users."
                            else 
                                "Enter the reason for suspending '${app.name}'. Suspended applications are filtered out from the marketplace views of normal users.",
                            color = textSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = suspensionDialogReason,
                            onValueChange = { suspensionDialogReason = it },
                            label = { Text("Reason for action", color = textSecondary) },
                            singleLine = false,
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = if (app.isSuspended) Color(0xFF10B981) else Color(0xFFEF5350),
                                unfocusedIndicatorColor = cardBorderColor,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("suspension_reason_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val targetState = !app.isSuspended
                            viewModel.suspendApp(app.id, targetState, suspensionDialogReason) { success, msg ->
                                Toast.makeText(context, msg ?: "Action performed", Toast.LENGTH_SHORT).show()
                                if (success) {
                                    isSuspensionDialogActive = false
                                    appToManageSuspension = null
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (app.isSuspended) Color(0xFF10B981) else Color(0xFFEF5350)
                        )
                    ) {
                        Text(if (app.isSuspended) "UNSUSPEND" else "SUSPEND", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { isSuspensionDialogActive = false; appToManageSuspension = null }) {
                        Text("CANCEL", color = textSecondary)
                    }
                }
            )
        }

        if (showPushUpdateFormFor != null) {
            val app = showPushUpdateFormFor!!
            var verInput by remember { mutableStateOf(app.version) }
            var apkInput by remember { mutableStateOf(app.apkUrl) }
            var descInput by remember { mutableStateOf(app.description) }

            Dialog(onDismissRequest = { showPushUpdateFormFor = null }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Push App Version Update",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = textPrimary
                        )
                        Text(
                            text = "Update the software version name, release logs (changelog details), and APK links. This will immediately update the live application in the store catalog.",
                            fontSize = 11.sp,
                            color = textSecondary,
                            lineHeight = 16.sp
                        )

                        HorizontalDivider(color = cardBorderColor)

                        OutlinedTextField(
                            value = verInput,
                            onValueChange = { verInput = it },
                            label = { Text("Version Name *") },
                            placeholder = { Text("e.g. v2.1.0") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary,
                                focusedBorderColor = accentGreen,
                                unfocusedBorderColor = cardBorderColor
                            )
                        )

                        OutlinedTextField(
                            value = apkInput,
                            onValueChange = { apkInput = it },
                            label = { Text("APK Download URL *") },
                            placeholder = { Text("e.g. https://...") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary,
                                focusedBorderColor = accentGreen,
                                unfocusedBorderColor = cardBorderColor
                            )
                        )

                        OutlinedTextField(
                            value = descInput,
                            onValueChange = { descInput = it },
                            label = { Text("Changelog / Description *") },
                            placeholder = { Text("What's new in this release...") },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary,
                                focusedBorderColor = accentGreen,
                                unfocusedBorderColor = cardBorderColor
                            )
                        )

                        HorizontalDivider(color = cardBorderColor)

                        Button(
                            onClick = {
                                if (verInput.isNotBlank() && apkInput.isNotBlank() && descInput.isNotBlank()) {
                                    val originalApp = apps.find { it.id == app.id }
                                    if (originalApp != null) {
                                        val updatedApp = originalApp.copy(
                                            version = verInput.trim(),
                                            apkUrl = apkInput.trim(),
                                            description = descInput.trim()
                                        )
                                        viewModel.addOrUpdateAppInCatalog(updatedApp) { success ->
                                            if (success) {
                                                Toast.makeText(context, "Version update successfully deployed!", Toast.LENGTH_SHORT).show()
                                                viewModel.refreshMarketplace(force = true)
                                                showPushUpdateFormFor = null
                                            } else {
                                                Toast.makeText(context, "Failed to deploy version update.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } else {
                                        Toast.makeText(context, "Error: App entity not found.", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "All fields are required.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Text("DEPLOY UPDATE INSTANTLY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        TextButton(
                            onClick = { showPushUpdateFormFor = null },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Cancel", color = textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (showUpdateScreenshotsFormFor != null) {
            val app = showUpdateScreenshotsFormFor!!
            val existingScreens = remember(app) {
                val list = app.screenshots.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                List(6) { index -> if (index < list.size) list[index] else "" }
            }

            var s1 by remember { mutableStateOf(existingScreens[0]) }
            var s2 by remember { mutableStateOf(existingScreens[1]) }
            var s3 by remember { mutableStateOf(existingScreens[2]) }
            var s4 by remember { mutableStateOf(existingScreens[3]) }
            var s5 by remember { mutableStateOf(existingScreens[4]) }
            var s6 by remember { mutableStateOf(existingScreens[5]) }

            val ssUploadingStates = remember { mutableStateListOf(false, false, false, false, false, false) }
            var targetSlot by remember { mutableStateOf(-1) }
            val coroutineScope = rememberCoroutineScope()

            val pickScreenshotLauncher = rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null && targetSlot in 0..5) {
                    val slot = targetSlot
                    ssUploadingStates[slot] = true
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val bytes = inputStream?.readBytes()
                            inputStream?.close()
                            if (bytes != null) {
                                val contentType = "image/jpeg"
                                val fileName = "screenshot_${System.currentTimeMillis()}.jpg"
                                val url = com.example.data.FirebaseAuthService.uploadFile(contentType, fileName, bytes)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    ssUploadingStates[slot] = false
                                    if (url != null) {
                                        when (slot) {
                                            0 -> s1 = url
                                            1 -> s2 = url
                                            2 -> s3 = url
                                            3 -> s4 = url
                                            4 -> s5 = url
                                            5 -> s6 = url
                                        }
                                        Toast.makeText(context, "Screenshot ${slot + 1} uploaded successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Cloud upload failed.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    ssUploadingStates[slot] = false
                                }
                            }
                        } catch (ex: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                ssUploadingStates[slot] = false
                                Toast.makeText(context, "Failure: ${ex.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            Dialog(onDismissRequest = { showUpdateScreenshotsFormFor = null }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Update Application Screenshots",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = textPrimary
                        )
                        Text(
                            text = "Upload up to 6 screenshots (minimum 3 required) to show off your application on the storefront catalog detail panels.",
                            fontSize = 11.sp,
                            color = textSecondary,
                            lineHeight = 16.sp
                        )

                        HorizontalDivider(color = cardBorderColor)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val slots = listOf(s1, s2, s3, s4, s5, s6)
                            for (i in 0 until 6 step 3) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (j in i until i + 3) {
                                        val slotUrl = slots[j]
                                        val isUploading = ssUploadingStates[j]

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(0.6f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(textSecondary.copy(alpha = 0.08f))
                                                .border(1.dp, cardBorderColor, RoundedCornerShape(8.dp))
                                                .clickable {
                                                    targetSlot = j
                                                    pickScreenshotLauncher.launch("image/*")
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isUploading) {
                                                CircularProgressIndicator(color = accentGreen, modifier = Modifier.size(24.dp))
                                            } else if (slotUrl.isNotBlank()) {
                                                AsyncImage(
                                                    model = slotUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(4.dp)
                                                        .size(18.dp)
                                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                                                        .clickable {
                                                            when (j) {
                                                                0 -> s1 = ""
                                                                1 -> s2 = ""
                                                                2 -> s3 = ""
                                                                3 -> s4 = ""
                                                                4 -> s5 = ""
                                                                5 -> s6 = ""
                                                            }
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                                                }
                                            } else {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(Icons.Default.Add, contentDescription = null, tint = textSecondary, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text("Slot ${j + 1}", fontSize = 9.sp, color = textSecondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = cardBorderColor)

                        Button(
                            onClick = {
                                val screenshotUrls = listOf(s1, s2, s3, s4, s5, s6).map { it.trim() }.filter { it.isNotEmpty() }
                                if (screenshotUrls.size < 3) {
                                    Toast.makeText(context, "Please provide at least 3 screenshots.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val finalScreenshotsStr = screenshotUrls.joinToString(",")

                                val originalApp = apps.find { it.id == app.id }
                                if (originalApp != null) {
                                    val updatedApp = originalApp.copy(screenshots = finalScreenshotsStr)
                                    viewModel.addOrUpdateAppInCatalog(updatedApp) { success ->
                                        if (success) {
                                            Toast.makeText(context, "Screenshots successfully updated!", Toast.LENGTH_SHORT).show()
                                            viewModel.refreshMarketplace(force = true)
                                            showUpdateScreenshotsFormFor = null
                                        } else {
                                            Toast.makeText(context, "Failed to update screenshots.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, "Error: App entity not found.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Text("SAVE SCREENSHOTS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        TextButton(
                            onClick = { showUpdateScreenshotsFormFor = null },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Cancel", color = textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// ========================================================
// ADMIN SUBMISSION CARD — Compact, grouped, action-ready
// ========================================================
@Composable
fun AdminSubmissionCard(
    sub: SubmissionEntity,
    isAdmin: Boolean,
    accentGreen: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBgColor: Color,
    cardBorderColor: Color,
    statusColor: Color,
    statusIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    onCardClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onCardClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Top row: icon + info + status badge
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(accentGreen.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (sub.logo.isNotBlank()) {
                        AsyncImage(model = sub.logo, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    } else {
                        Text(sub.name.take(1).uppercase(), color = accentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(sub.name, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    Text("v${sub.version}  •  ${sub.category}  •  ${sub.developer}", color = textSecondary, fontSize = 10.sp, maxLines = 1)
                    Text(sub.submittedBy, color = textSecondary.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1)
                }
                // Status badge
                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(12.dp))
                        Text(sub.status.uppercase(), color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            // Package name
            Text(sub.packageName, color = textSecondary.copy(alpha = 0.5f), fontSize = 10.sp)

            // Feedback if rejected
            if (sub.status == "Rejected" && sub.feedback.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.07f))
                ) {
                    Text(
                        text = "Reason: ${sub.feedback}",
                        modifier = Modifier.padding(8.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
            if (sub.status == "Approved" && sub.feedback.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.07f))
                ) {
                    Text(
                        text = "Feedback: ${sub.feedback}",
                        modifier = Modifier.padding(8.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // Admin action buttons
            if (isAdmin) {
                HorizontalDivider(color = cardBorderColor.copy(alpha = 0.5f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Approve
                    Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f).height(34.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        enabled = sub.status != "Approved"
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Approve", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    // Reject
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f).height(34.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        enabled = sub.status != "Rejected"
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFFEF4444))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reject", fontSize = 11.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    }
                    // Edit
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f).height(34.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, cardBorderColor),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp), tint = accentGreen)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit", fontSize = 11.sp, color = accentGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ========================================================
// 9. HIGH FIDELITY SPECIFICATIONS OVERLAY SHEET (PLAY STORE TYPE)
// ========================================================
// ========================================================
@Composable
fun AppDetailsDialog(
    app: AppEntity,
    downloadState: DownloadEntity?,
    installedInfo: com.example.utils.ApkInstaller.InstalledAppInfo?,
    currentRating: String,
    currentReviewsCount: Int,
    accentGreen: Color,
    textPrimary: Color,
    textSecondary: Color,
    cardBgColor: Color,
    isDarkMode: Boolean,
    isPurchased: Boolean = false,
    isRegistered: Boolean = false,
    isAdmin: Boolean = false,
    onBuyClick: () -> Unit = {},
    onRegisterClick: () -> Unit = {},
    onDismiss: () -> Unit,
    onAction: () -> Unit,
    onDeleteDl: () -> Unit,
    onReviewSubmit: (Int, String) -> Unit,
    onReportSubmit: (String) -> Unit
) {
    val context = LocalContext.current
    val isInstalled = installedInfo != null
    var isWritingReview by remember { mutableStateOf(false) }
    var inputRatingStars by remember { mutableStateOf(5) }
    var inputReviewText by remember { mutableStateOf("") }
    var activeLightboxImageIndex by remember { mutableStateOf<Int?>(null) }

    val screenshotList = remember(app.screenshots) {
        app.screenshots.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    // Mock static review logs to make review pages interactive
    val defaultReviews = remember(app.id) {
        listOf(
            Triple("Rahul Sharma", 5, "Instant clean installation. Matches the description flawlessly!"),
            Triple("Ananya Verma", 4, "Extremely polished and lightweight. Would love dynamic auto-updates.")
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = cardBgColor
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // Header Image Backdrop with back close button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(accentGreen.copy(0.2f), Color.Transparent)
                            )
                        )
                        .padding(16.dp)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(Color.Black.copy(0.4f), CircleShape)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(accentGreen.copy(0.2f), RoundedCornerShape(4.dp))
                            .border(1.dp, accentGreen.copy(0.3f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = app.category.uppercase(),
                            color = accentGreen,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Core App Specs Row
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AppLogo(
                                logoUrl = app.logo,
                                appName = app.name,
                                packageName = app.packageName,
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            if (downloadState?.status == "DOWNLOADING") {
                                CircularProgressIndicator(
                                    progress = downloadState.progress / 100f,
                                    color = accentGreen,
                                    trackColor = Color.Transparent,
                                    strokeWidth = 3.3.dp,
                                    modifier = Modifier.size(82.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = if (app.hasAds) 75.dp else 0.dp) // Provide safety spacing for Ad badge
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = app.name,
                                    color = textPrimary,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            Text(
                                text = "Developer: ${app.developer}",
                                color = textSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Package: ${app.packageName}",
                                color = textSecondary.copy(0.6f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (app.hasAds) {
                        AdBadge(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 20.dp, top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Numerical statistics summary bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailBadgeRowItem(
                        title = "RATING",
                        value = "$currentRating ★",
                        sub = "$currentReviewsCount reviews",
                        colorText = textPrimary,
                        colorTextSub = textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    val versionSubText = if (installedInfo != null) {
                        val detailsHasUpdate = app.versionCode > installedInfo.versionCode || 
                            !app.version.trim().equals(installedInfo.versionName.trim(), ignoreCase = true)
                        if (detailsHasUpdate) {
                            "Installed: ${installedInfo.versionName} (v${installedInfo.versionCode}) • Update!"
                        } else {
                            "Installed: ${installedInfo.versionName} (v${installedInfo.versionCode})"
                        }
                    } else {
                        "v${app.versionCode} • Stable release"
                    }

                    DetailBadgeRowItem(
                        title = "VERSION",
                        value = app.version,
                        sub = versionSubText,
                        colorText = textPrimary,
                        colorTextSub = textSecondary,
                        modifier = Modifier.weight(1.5f)
                    )
                    DetailBadgeRowItem(
                        title = "FILE SIZE",
                        value = app.size,
                        sub = "Standard resource",
                        colorText = textPrimary,
                        colorTextSub = textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Detailed descriptive scroll field
                Box(modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp)) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = "DESCRIPTION",
                            color = textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        var isDescExpanded by remember { mutableStateOf(false) }
                        Text(
                            text = app.description,
                            color = textPrimary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            maxLines = if (isDescExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (app.description.length > 120) {
                            Text(
                                text = if (isDescExpanded) "Show Less" else "Read More",
                                color = accentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { isDescExpanded = !isDescExpanded }
                                    .padding(vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "SCREENSHOT PREVIEWS",
                            color = textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Screenshot galleries
                        if (screenshotList.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                itemsIndexed(screenshotList, key = { index, sUrl -> "${index}_$sUrl" }) { index, sUrl ->
                                    val screenshotRequest = remember(sUrl, context) {
                                        coil.request.ImageRequest.Builder(context)
                                            .data(sUrl)
                                            .crossfade(true)
                                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                            .placeholder(R.drawable.img_app_logo_new)
                                            .error(R.drawable.img_app_logo_new)
                                            .fallback(R.drawable.img_app_logo_new)
                                            .build()
                                    }
                                    ElegantImageLoader(
                                        model = screenshotRequest,
                                        contentDescription = "App Preview Frame",
                                        modifier = Modifier
                                            .width(135.dp)
                                            .height(240.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(textSecondary.copy(alpha = 0.1f))
                                            .clickable {
                                                activeLightboxImageIndex = index
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .background(textSecondary.copy(0.05f), RoundedCornerShape(10.dp))
                                    .border(1.dp, textSecondary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No screenshots available.", color = textSecondary, fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Play Store-like write-review segment
                        Text(
                            text = "RATE THIS APPLICATION",
                            color = textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        if (!isAdmin) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        textSecondary.copy(alpha = 0.05f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .border(
                                        1.dp,
                                        textSecondary.copy(alpha = 0.15f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Rating Restriction Info",
                                        tint = textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Only system administrators are authorized to submit ratings and reviews.",
                                        color = textSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        } else if (!isWritingReview) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isWritingReview = true }
                                    .background(
                                        textSecondary.copy(alpha = 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (i in 1..5) {
                                        Icon(
                                            imageVector = Icons.Outlined.Star,
                                            contentDescription = "Unfilled rate star",
                                            tint = textSecondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Write a public review",
                                    color = accentGreen,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            // Active interactive stars writing board
                            Card(
                                colors = CardDefaults.cardColors(containerColor = textSecondary.copy(0.04f)),
                                border = BorderStroke(1.dp, textSecondary.copy(alpha = 0.15f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Select rating stars", color = textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    ) {
                                        for (i in 1..5) {
                                            val isSelected = i <= inputRatingStars
                                            Icon(
                                                imageVector = if (isSelected) Icons.Filled.Star else Icons.Outlined.Star,
                                                contentDescription = "Rate app star click",
                                                tint = if (isSelected) Color(0xFFF1A80A) else textSecondary,
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clickable { inputRatingStars = i }
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = inputReviewText,
                                        onValueChange = { inputReviewText = it },
                                        placeholder = { Text("Tell the community your experience with this package (optional)...", fontSize = 12.sp) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = textPrimary,
                                            unfocusedTextColor = textPrimary
                                        ),
                                        minLines = 2
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(onClick = { isWritingReview = false; inputReviewText = "" }) {
                                            Text("CANCEL", color = textSecondary)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                if (inputReviewText.isNotBlank()) {
                                                    onReviewSubmit(inputRatingStars, inputReviewText)
                                                    isWritingReview = false
                                                    inputReviewText = ""
                                                } else {
                                                    Toast.makeText(context, "Please enter some review text!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = accentGreen)
                                        ) {
                                            Text("SUBMIT", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Render user feedback posts
                        Text(
                            text = "COMMUNITY REVIEWS",
                            color = textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        defaultReviews.forEach { (reviewer, stars, feedback) ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(reviewer, color = textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Row {
                                        for (j in 1..5) {
                                            Icon(
                                                imageVector = Icons.Filled.Star,
                                                contentDescription = "",
                                                tint = if (j <= stars) Color(0xFFF1A80A) else textSecondary.copy(alpha = 0.3f),
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                }
                                Text(feedback, color = textPrimary.copy(alpha = 0.8f), fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Divider(color = textSecondary.copy(alpha = 0.1f))
                            }
                        }

                        // Report App Section
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "SAFETY & POLICIES",
                            color = textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        var isReporting by remember { mutableStateOf(false) }
                        var reportReason by remember { mutableStateOf("") }

                        if (!isReporting) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isReporting = true }
                                    .background(textSecondary.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Report",
                                    tint = Color(0xFFEF5350),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Report application flag / policy violation",
                                    color = Color(0xFFEF5350),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = textSecondary.copy(0.04f)),
                                border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.2f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Why are you reporting this application?",
                                        color = textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = reportReason,
                                        onValueChange = { reportReason = it },
                                        placeholder = { Text("E.g. Malware, scam, annoying ads, copyright infringement, etc...", fontSize = 11.sp) },
                                        modifier = Modifier.fillMaxWidth().testTag("app_report_reason_field"),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = textPrimary,
                                            unfocusedTextColor = textPrimary,
                                            focusedBorderColor = Color(0xFFEF5350),
                                            unfocusedBorderColor = textSecondary.copy(alpha = 0.3f)
                                        ),
                                        minLines = 2
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(onClick = { isReporting = false; reportReason = "" }) {
                                            Text("CANCEL", color = textSecondary)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                if (reportReason.isNotBlank()) {
                                                    onReportSubmit(reportReason)
                                                    isReporting = false
                                                    reportReason = ""
                                                } else {
                                                    Toast.makeText(context, "Please enter a report description!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                                        ) {
                                            Text("SUBMIT REPORT", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(30.dp))
                    }
                }

                // Sticky download execution controller bar at very bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(textSecondary.copy(alpha = 0.05f))
                        .padding(16.dp)
                ) {
                    if (downloadState?.status == "DOWNLOADING") {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Fetching package data: ${downloadState.progress}%",
                                    color = accentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = downloadState.downloadSpeed,
                                    color = accentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = downloadState.progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                                color = accentGreen,
                                trackColor = textSecondary.copy(alpha = 0.15f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = onDeleteDl,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("CANCEL DOWNLOAD", color = Color(0xFFEF5350), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        val hasUpdate = isInstalled && (
                            app.versionCode > (installedInfo?.versionCode ?: 0L) ||
                            (installedInfo?.versionName != null && !app.version.trim().equals(installedInfo.versionName.trim(), ignoreCase = true))
                        )
                        if (isInstalled) {
                            if (hasUpdate) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { ApkInstaller.launchApp(context, app.packageName) },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1.1f)
                                            .height(46.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentGreen),
                                        border = BorderStroke(1.5.dp, accentGreen),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "", tint = accentGreen)
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("OPEN", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    }

                                    Button(
                                        onClick = onAction,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1.2f)
                                            .height(46.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = accentGreen)
                                    ) {
                                        Icon(
                                            imageVector = if (downloadState?.status == "DOWNLOADED") Icons.Default.CheckCircle else Icons.Default.KeyboardArrowDown,
                                            contentDescription = "",
                                            tint = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (downloadState?.status == "DOWNLOADED") "INSTALL" else "UPDATE",
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            } else {
                                // Double Actions UX Replicas
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (LocalUninstallEnabled.current) {
                                        OutlinedButton(
                                            onClick = { ApkInstaller.uninstallApp(context, app.packageName) },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .weight(1.1f)
                                                .height(46.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                                            border = BorderStroke(1.5.dp, Color(0xFFEF5350)),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "", tint = Color(0xFFEF5350))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("UNINSTALL", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                        }
                                    }

                                    Button(
                                        onClick = { ApkInstaller.launchApp(context, app.packageName) },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(if (LocalUninstallEnabled.current) 1.2f else 1.0f)
                                            .height(46.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = accentGreen)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "", tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("OPEN", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        } else {
                            if (app.isUpcoming) {
                                if (isRegistered) {
                                    OutlinedButton(
                                        onClick = {},
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accentGreen),
                                        border = BorderStroke(1.5.dp, accentGreen)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = "", tint = accentGreen)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("PRE-REGISTERED", fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Button(
                                        onClick = onRegisterClick,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = "", tint = Color.White)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("PRE-REGISTER FOR ITEM", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            } else if (app.isPremium && !isPurchased) {
                                Button(
                                    onClick = onBuyClick,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                                ) {
                                    Icon(Icons.Default.ShoppingCart, contentDescription = "", tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("BUY FOR ${app.price.ifEmpty { "$1.99" }}", fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            } else {
                                Button(
                                    onClick = onAction,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = accentGreen)
                                ) {
                                    Icon(
                                        imageVector = if (downloadState?.status == "DOWNLOADED") Icons.Default.CheckCircle else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "",
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (downloadState?.status == "DOWNLOADED") "INSTALL" else "INSTALL",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (activeLightboxImageIndex != null) {
        ScreenshotLightboxDialog(
            screenshots = screenshotList,
            initialIndex = activeLightboxImageIndex!!,
            appName = app.name,
            onDismiss = { activeLightboxImageIndex = null }
        )
    }
}

@Composable
fun DetailBadgeRowItem(
    title: String,
    value: String,
    sub: String,
    colorText: Color,
    colorTextSub: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(colorTextSub.copy(0.04f), RoundedCornerShape(10.dp))
            .border(1.dp, colorTextSub.copy(0.08f), RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 8.sp, color = colorTextSub, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                value,
                fontSize = 12.sp,
                color = colorText,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                sub,
                fontSize = 8.sp,
                color = colorTextSub,
                fontWeight = FontWeight.Light,
                maxLines = 1
            )
        }
    }
}

// ========================================================
// 10. EMPTY CATALOG ARCHIVE WARNING CARD
// ========================================================
@Composable
fun EmptyCatalogStateCard(
    textPrimary: Color,
    textSecondary: Color,
    accentGreen: Color,
    cardBgColor: Color,
    cardBorderColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Catalog empty info symbol",
                tint = accentGreen,
                modifier = Modifier.size(60.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "No Apps Published",
                fontSize = 16.sp,
                color = textPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Dark Store catalog contains no packages at this time. Authorize within the Developer Console tab to upload and deploy application archives directly.",
                color = textSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )
        }
    }
}


private fun handleAppActionButton(
    app: AppEntity,
    isInstalled: Boolean,
    viewModel: StoreViewModel,
    context: android.content.Context
) {
    val downloadState = viewModel.downloads.value.find { it.id == app.id }
    val installedInfo = ApkInstaller.getInstalledAppInfo(context, app.packageName)
    val currentlyInstalled = installedInfo != null
    val hasUpdate = currentlyInstalled && (
        app.versionCode > (installedInfo?.versionCode ?: 0L) ||
        (installedInfo?.versionName != null && !app.version.trim().equals(installedInfo.versionName.trim(), ignoreCase = true))
    )

    if (downloadState?.status == "DOWNLOADED" && downloadState.localFilePath != null) {
        val file = File(downloadState.localFilePath)
        val packageInfo = if (file.exists()) {
            try {
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        if (packageInfo == null) {
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                // Ignore
            }
            Toast.makeText(context, "Cached APK is corrupt or missing. Restarting download...", Toast.LENGTH_SHORT).show()
            viewModel.downloadAndInstallApp(app)
        } else {
            ApkInstaller.installApk(context, file)
        }
    } else if (hasUpdate) {
        viewModel.downloadAndInstallApp(app)
        Toast.makeText(context, "Downloading update for: ${app.name}", Toast.LENGTH_SHORT).show()
    } else if (currentlyInstalled) {
        ApkInstaller.launchApp(context, app.packageName)
    } else {
        viewModel.downloadAndInstallApp(app)
        Toast.makeText(context, "Starting fetch sequence: ${app.name}", Toast.LENGTH_SHORT).show()
    }
}

// ========================================================
// 11. FORM DIALOG TO REGISTER OR APPEND STORE APP TO FIREBASE
// ========================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddNewAppForm(
    existingApp: AppEntity? = null,
    isForAdmin: Boolean = false,
    userEmail: String = "",
    defaultDeveloperName: String = "",
    onDismiss: () -> Unit,
    onSubmit: (AppEntity) -> Unit
) {
    var id by remember { mutableStateOf(existingApp?.id ?: "app_${System.currentTimeMillis()}_${(1000..9999).random()}") }
    var name by remember { mutableStateOf(existingApp?.name ?: "") }
    var developer by remember { mutableStateOf(existingApp?.developer ?: defaultDeveloperName.ifBlank { "Developer" }) }
    var version by remember { mutableStateOf(existingApp?.version ?: "") }
    var size by remember { mutableStateOf(existingApp?.size ?: "") }
    var category by remember { mutableStateOf(existingApp?.category ?: "Utilities") }
    var rating by remember { mutableStateOf(existingApp?.rating ?: "") }
    var description by remember { mutableStateOf(existingApp?.description ?: "") }
    var logoUrl by remember { mutableStateOf(existingApp?.logo ?: "") }
    
    val initialScreenshots = remember(existingApp) {
        val list = existingApp?.screenshots?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        List(6) { index -> if (index < list.size) list[index] else "" }
    }
    
    var ss1 by remember { mutableStateOf(initialScreenshots[0]) }
    var ss2 by remember { mutableStateOf(initialScreenshots[1]) }
    var ss3 by remember { mutableStateOf(initialScreenshots[2]) }
    var ss4 by remember { mutableStateOf(initialScreenshots[3]) }
    var ss5 by remember { mutableStateOf(initialScreenshots[4]) }
    var ss6 by remember { mutableStateOf(initialScreenshots[5]) }

    val uploadingStates = remember { mutableStateListOf(false, false, false, false, false, false) }
    var activePickingSlotIndex by remember { mutableStateOf(-1) }
    var isUploadingLogo by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    fun uploadFileFromUri(uri: Uri, isLogo: Boolean, onFinished: (String?) -> Unit) {
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes == null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read image bytes.", Toast.LENGTH_SHORT).show()
                        onFinished(null)
                    }
                    return@launch
                }
                
                val contentType = "image/jpeg"
                val fileName = if (isLogo) "logo_${System.currentTimeMillis()}.jpg" else "screenshot_${System.currentTimeMillis()}.jpg"
                
                val uploadedUrl = com.example.data.FirebaseAuthService.uploadFile(contentType, fileName, bytes)
                if (uploadedUrl != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onFinished(uploadedUrl)
                    }
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Firebase upload skipped. Trying backup server...", Toast.LENGTH_SHORT).show()
                    }
                    try {
                        val base64String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val formBody = okhttp3.FormBody.Builder()
                            .add("image", base64String)
                            .build()

                        val request = okhttp3.Request.Builder()
                            .url("https://api.imgbb.com/1/upload?key=a046c848dfa5230136f107106d4bb187")
                            .post(formBody)
                            .build()

                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val bodyString = response.body?.string() ?: ""
                            val match = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(bodyString)
                            val fallbackUrl = match?.groupValues?.get(1)?.replace("\\/", "/")
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                onFinished(fallbackUrl)
                            }
                        } else {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(context, "Backup offline. Upload failed completely.", Toast.LENGTH_SHORT).show()
                                onFinished(null)
                            }
                        }
                    } catch (ex: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, "Upload failed: ${ex.message}", Toast.LENGTH_SHORT).show()
                            onFinished(null)
                        }
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    onFinished(null)
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val idx = activePickingSlotIndex
            if (idx == 99) {
                isUploadingLogo = true
                uploadFileFromUri(uri, isLogo = true) { url ->
                    isUploadingLogo = false
                    if (url != null) {
                        logoUrl = url
                        Toast.makeText(context, "App logo updated successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to upload app logo.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (idx in 0..5) {
                uploadingStates[idx] = true
                uploadFileFromUri(uri, isLogo = false) { url ->
                    uploadingStates[idx] = false
                    if (url != null) {
                        Toast.makeText(context, "Screenshot ${idx + 1} uploaded successfully!", Toast.LENGTH_SHORT).show()
                        when (idx) {
                            0 -> ss1 = url
                            1 -> ss2 = url
                            2 -> ss3 = url
                            3 -> ss4 = url
                            4 -> ss5 = url
                            5 -> ss6 = url
                        }
                    } else {
                        Toast.makeText(context, "Failed to upload screenshot ${idx + 1}.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            imagePickerLauncher.launch("image/*")
        } else {
            Toast.makeText(context, "Permission denied. Can't select photos from device without media permission.", Toast.LENGTH_LONG).show()
        }
    }

    var apkUrl by remember { mutableStateOf(existingApp?.apkUrl ?: "") }

    var packageName by remember { mutableStateOf(existingApp?.packageName ?: "") }
    var isFeatured by remember { mutableStateOf(existingApp?.isFeatured ?: false) }
    var versionCodeInput by remember { mutableStateOf(existingApp?.versionCode?.toString() ?: "1") }
    var hasAds by remember { mutableStateOf(existingApp?.hasAds ?: false) }
    var isPremium by remember { mutableStateOf(existingApp?.isPremium ?: false) }
    var price by remember { mutableStateOf(existingApp?.price ?: "") }
    var isUpcoming by remember { mutableStateOf(existingApp?.isUpcoming ?: false) }

    val categories = remember { listOf("Utilities", "Games", "Tools", "Entertainment") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF01875F), Color(0xFF0F9D58))
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Go Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (existingApp == null) "Submit New Package" else "Edit Application Profile",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Google Play Developer Console • Workspace Sync",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp)
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF01875F), modifier = Modifier.size(20.dp))
                                Text("Application Metadata", fontWeight = FontWeight.Bold, color = Color(0xFF01875F), fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("App Name *") },
                                placeholder = { Text("e.g. Brave Browser Mod") },
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF01875F)) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("add_name_field")
                            )
                            
                            if (isForAdmin) {
                                OutlinedTextField(
                                    value = developer,
                                    onValueChange = { developer = it },
                                    label = { Text("Developer Team *") },
                                    leadingIcon = { Icon(Icons.Default.AccountBox, contentDescription = null, tint = Color(0xFF01875F)) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LaunchedEffect(defaultDeveloperName) {
                                    if (defaultDeveloperName.isNotBlank()) {
                                        developer = defaultDeveloperName
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Default.AccountBox, contentDescription = null, tint = Color(0xFF01875F).copy(alpha = 0.7f))
                                        Column {
                                            Text("Registered Publisher Team", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(1.dp))
                                            Text(
                                                text = developer.ifBlank { "Unverified Developer" },
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                            
                            OutlinedTextField(
                                value = version,
                                onValueChange = { version = it },
                                label = { Text("Package Version Name (e.g., 1.5.0)") },
                                placeholder = { Text("1.0.0") },
                                leadingIcon = { Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFF01875F)) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = versionCodeInput,
                                onValueChange = { newValue ->
                                    if (newValue.all { it.isDigit() }) {
                                        versionCodeInput = newValue
                                    }
                                },
                                label = { Text("Package Version Code * (e.g. 100)") },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF01875F)) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                )
                            )
                            
                            OutlinedTextField(
                                value = size,
                                onValueChange = { size = it },
                                label = { Text("Binary Size (e.g., 15 MB)") },
                                placeholder = { Text("24 MB") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF01875F)) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = packageName,
                                onValueChange = { packageName = it },
                                label = { Text("Unique Package ID * (e.g., com.brave.mod)") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF01875F)) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("App Marketplace Category", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF01875F))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                categories.forEach { cat ->
                                    val activeCat = category == cat
                                    FilterChip(
                                        selected = activeCat,
                                        onClick = { category = cat },
                                        label = { Text(cat, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFF01875F),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.AddCircle, contentDescription = null, tint = Color(0xFF01875F), modifier = Modifier.size(20.dp))
                                Text("Creative Assets & Screenshots", fontWeight = FontWeight.Bold, color = Color(0xFF01875F), fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = logoUrl,
                                    onValueChange = { logoUrl = it },
                                    label = { Text("Custom Logo URL *") },
                                    placeholder = { Text("https://example.com/logo.png") },
                                    leadingIcon = { Icon(Icons.Default.Face, contentDescription = null, tint = Color(0xFF01875F)) },
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                    modifier = Modifier.weight(1f)
                                )

                                Button(
                                    onClick = {
                                        activePickingSlotIndex = 99
                                        val permissionToRequest = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                            android.Manifest.permission.READ_MEDIA_IMAGES
                                        } else {
                                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                                        }
                                        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                            context,
                                            permissionToRequest
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                        if (hasPermission) {
                                            imagePickerLauncher.launch("image/*")
                                        } else {
                                            permissionLauncher.launch(permissionToRequest)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF01875F).copy(alpha = 0.12f), contentColor = Color(0xFF01875F)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(56.dp).padding(top = 4.dp)
                                ) {
                                    if (isUploadingLogo) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF01875F)
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(id = android.R.drawable.ic_menu_upload),
                                            contentDescription = "Upload Logo",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Logo", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (isForAdmin) {
                                OutlinedTextField(
                                    value = rating,
                                    onValueChange = { rating = it },
                                    label = { Text("Initial Community Rating (e.g., 4.8)") },
                                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF01875F)) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LaunchedEffect(Unit) {
                                    if (rating.isBlank()) {
                                        rating = "4.5"
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))
                            Column {
                                Text("App Screenshot Previews", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF01875F))
                                Text("Provide between 3 and 6 screenshots for the optimal store photo layout.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val slots = remember(ss1, ss2, ss3, ss4, ss5, ss6) {
                                    listOf(
                                        Triple("Slot 1 (Primary screenshot) *", ss1, { v: String -> ss1 = v }),
                                        Triple("Slot 2 (Screenshot 2) *", ss2, { v: String -> ss2 = v }),
                                        Triple("Slot 3 (Screenshot 3) *", ss3, { v: String -> ss3 = v }),
                                        Triple("Slot 4 (Screenshot 4 - optional)", ss4, { v: String -> ss4 = v }),
                                        Triple("Slot 5 (Screenshot 5 - optional)", ss5, { v: String -> ss5 = v }),
                                        Triple("Slot 6 (Screenshot 6 - optional)", ss6, { v: String -> ss6 = v })
                                    )
                                }
                                
                                slots.forEachIndexed { idx, (label, ssValue, onSsChange) ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                color = Color(0xFF01875F),
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (uploadingStates[idx]) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = Color(0xFF01875F)
                                                )
                                            }
                                        }
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = ssValue,
                                                onValueChange = onSsChange,
                                                placeholder = { Text("Paste URL or Upload photo") },
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                                modifier = Modifier.weight(1f),
                                                singleLine = true,
                                                shape = RoundedCornerShape(10.dp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF01875F),
                                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                                )
                                            )
                                            
                                            Button(
                                                onClick = {
                                                    activePickingSlotIndex = idx
                                                    val permissionToRequest = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                                        android.Manifest.permission.READ_MEDIA_IMAGES
                                                    } else {
                                                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                                                    }
                                                    val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                                        context,
                                                        permissionToRequest
                                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    
                                                    if (hasPermission) {
                                                        imagePickerLauncher.launch("image/*")
                                                    } else {
                                                        permissionLauncher.launch(permissionToRequest)
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF01875F).copy(alpha = 0.12f), contentColor = Color(0xFF01875F)),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.height(42.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = android.R.drawable.ic_menu_upload),
                                                    contentDescription = "Upload Photo",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Pick", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        
                                        if (ssValue.isNotBlank()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                AsyncImage(
                                                    model = ssValue,
                                                    contentDescription = "Thumbnail draft",
                                                    modifier = Modifier
                                                        .width(60.dp)
                                                        .height(106.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(Color.LightGray.copy(alpha = 0.2f)),
                                                    contentScale = ContentScale.Crop,
                                                    error = painterResource(id = R.drawable.img_app_logo_new)
                                                )
                                                Column {
                                                    Text("Loaded thumbnail draft (9:16)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Text(ssValue, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        text = "Clear URL",
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier
                                                            .clickable { onSsChange("") }
                                                            .padding(vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF01875F), modifier = Modifier.size(20.dp))
                                Text("Distribution & Monetization", fontWeight = FontWeight.Bold, color = Color(0xFF01875F), fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(2.dp))

                            OutlinedTextField(
                                value = apkUrl,
                                onValueChange = { apkUrl = it },
                                label = { Text(if (isUpcoming) "Direct Download APK Url Link (Optional)" else "Direct Download APK Url Link *") },
                                placeholder = { Text("https://example.com/app.apk") },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color(0xFF01875F)) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("add_apk_url_field")
                            )
                            if (apkUrl.contains("drive.google.com", ignoreCase = true) || apkUrl.contains("docs.google.com", ignoreCase = true)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFE0F2FE), RoundedCornerShape(10.dp))
                                        .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Drive Support",
                                        tint = Color(0xFF0284C7),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Google Drive link detected! Clean direct downloads and virus scans confirmation bypass are fully supported and automated.",
                                        fontSize = 10.sp,
                                        color = Color(0xFF0369A1),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("Detailed description of software components") },
                                placeholder = { Text("Write a compelling explanation of features...") },
                                leadingIcon = { Icon(Icons.Default.List, contentDescription = null, tint = Color(0xFF01875F)) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Features & Options", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF01875F))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isFeatured,
                                    onCheckedChange = { isFeatured = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF01875F))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Promote to Featured slide carousel", fontSize = 12.sp)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().testTag("has_ads_checkbox_row"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = hasAds,
                                    onCheckedChange = { hasAds = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF03A9F4))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Contains advertising (Show 'AD' badge)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().testTag("is_premium_checkbox_row"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isPremium,
                                    onCheckedChange = {
                                        isPremium = it
                                        if (!it) price = "" 
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFF9800))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Premium App (Requires payment simulation)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            if (isPremium) {
                                OutlinedTextField(
                                    value = price,
                                    onValueChange = { price = it },
                                    label = { Text("App Pricing / Price Tag * (e.g. $1.99)") },
                                    leadingIcon = { Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color(0xFFFF9800)) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("premium_price_field"),
                                    placeholder = { Text("$2.99") }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().testTag("is_upcoming_checkbox_row"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isUpcoming,
                                    onCheckedChange = { isUpcoming = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF9C27B0))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Upcoming Release (Mark as Pre-register only)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(44.dp).testTag("cancel_app_form_button"),
                        border = BorderStroke(1.2.dp, Color.Gray),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            val isApkUrlRequired = !isUpcoming
                            if (name.isBlank() || (isApkUrlRequired && apkUrl.isBlank()) || packageName.isBlank()) {
                                val msg = if (isApkUrlRequired) {
                                    "Mandatory requirements: Name, Package ID, and Direct Apk Url must be specified."
                                } else {
                                    "Mandatory requirements: Name and Package ID must be specified."
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val screenshotUrls = listOf(ss1, ss2, ss3, ss4, ss5, ss6).map { it.trim() }.filter { it.isNotEmpty() }
                            if (screenshotUrls.size < 3 || screenshotUrls.size > 6) {
                                Toast.makeText(context, "Please provide between 3 and 6 screenshots for the optimal app store photo ratio layout. You currently have ${screenshotUrls.size} screenshot(s).", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            val finalScreenshotsStr = screenshotUrls.joinToString(",")
                            onSubmit(
                                AppEntity(
                                    id = id.trim(),
                                    name = name.trim(),
                                    developer = if (developer.isBlank()) "Community Dev" else developer.trim(),
                                    version = if (version.isBlank()) "1.0.0" else version.trim(),
                                    size = if (size.isBlank()) "18 MB" else size.trim(),
                                    category = category,
                                    rating = if (rating.isBlank()) "4.5" else rating.trim(),
                                    description = if (description.isBlank()) "Standard safe installation package." else description.trim(),
                                    logo = logoUrl.trim(),
                                    screenshots = finalScreenshotsStr,
                                    apkUrl = apkUrl.trim(),
                                    packageName = packageName.trim(),
                                    isFeatured = isFeatured,
                                    isPremium = isPremium,
                                    price = if (isPremium) (if (price.isBlank()) "$1.99" else price.trim()) else "",
                                    isUpcoming = isUpcoming,
                                    isPopular = true,
                                    isRecent = true,
                                    versionCode = versionCodeInput.trim().toIntOrNull() ?: 1,
                                    isApproved = if (existingApp != null) existingApp.isApproved else isForAdmin,
                                    submittedBy = if (existingApp != null) existingApp.submittedBy else userEmail,
                                    hasAds = hasAds
                                )
                            )
                        },
                        modifier = Modifier.weight(1.5f).height(44.dp).testTag("submit_app_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF01875F)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (existingApp == null) "DEPLOY APKS" else "SAVE CHANGES",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ========================================================
// 11.B SIMULATED PURCHASE / CHECKOUT DIALOG
// ========================================================
@Composable
fun SimulatedPaymentCheckoutDialog(
    app: AppEntity,
    accentGreen: Color,
    onDismiss: () -> Unit,
    onPurchaseConfirmed: () -> Unit
) {
    var selectedMethod by remember { mutableStateOf("Play Balance") }
    var isProcessing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    Dialog(onDismissRequest = { if (!isProcessing) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentSize()
                .border(1.dp, Color(0xFF01875F), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Google Play Purchase",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(14.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    AppLogo(
                        logoUrl = app.logo,
                        appName = app.name,
                        packageName = app.packageName,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.developer,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = app.price.ifEmpty { "$1.99" },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = accentGreen
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Choose Payment Method",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                val paymentMethods = listOf(
                    Triple("Play Balance", "Play Balance ($25.00 remaining)", Icons.Default.Star),
                    Triple("Visa Card", "Visa ending in •••• 5678", Icons.Default.CheckCircle),
                    Triple("Play Points", "Redeem 200 Play Points", Icons.Default.Star)
                )
                
                paymentMethods.forEach { (mId, mLabel, mIcon) ->
                    val isSel = selectedMethod == mId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isProcessing) { selectedMethod = mId }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = { if (!isProcessing) selectedMethod = mId },
                            colors = RadioButtonDefaults.colors(selectedColor = accentGreen)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(mIcon, contentDescription = null, tint = if (isSel) accentGreen else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = mLabel,
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                if (isProcessing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("payment_processing_indicator"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = accentGreen, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Authorizing sandbox payment...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(40.dp),
                            border = BorderStroke(1.dp, Color.Gray),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        
                        Button(
                            onClick = {
                                isProcessing = true
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(1200)
                                    isProcessing = false
                                    onPurchaseConfirmed()
                                }
                            },
                            modifier = Modifier.weight(1.5f).height(40.dp).testTag("purchase_simulation_confirm"),
                            colors = ButtonDefaults.buttonColors(containerColor = accentGreen),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("BUY WITH ONE-TAP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// 12. ANNOUNCEMENTS AND BIASED NOTIFICATION DETAILS DIALOG
// ========================================================
@Composable
fun NoticeDetailsDialog(
    notice: com.example.data.NoticeEntity,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1218)),
            border = BorderStroke(1.dp, Color(0xFF232A36))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF00AAFF).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "SYSTEM NOTICE",
                            color = Color(0xFF00AAFF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close description", tint = Color.LightGray)
                    }
                }
                
                Spacer(modifier = Modifier.height(14.dp))
                
                if (notice.imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = notice.imageUrl,
                        contentDescription = "Announcement photograph",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                Text(
                    text = notice.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.15.sp
                    )
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                val timeStr = java.text.SimpleDateFormat("MMM dd, yyyy - HH:mm", java.util.Locale.getDefault()).format(java.util.Date(notice.timestamp))
                Text(
                    text = "Sent on $timeStr",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                
                Spacer(modifier = Modifier.height(14.dp))
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .weight(1f, fill = false)
                ) {
                    Text(
                        text = notice.message,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(18.dp))
                
                val urlPattern = """https?://[^\s]+""".toRegex()
                val detectedUrl = urlPattern.find(notice.message)?.value 
                    ?: urlPattern.find(notice.title)?.value

                if (detectedUrl != null) {
                    val context = LocalContext.current
                    Button(
                        onClick = {
                            try {
                                val uri = android.net.Uri.parse(detectedUrl)
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    setPackage("com.android.chrome")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val uri = android.net.Uri.parse(detectedUrl)
                                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    android.widget.Toast.makeText(context, "No web browser found.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = "Chrome Redirect icon",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("RUN IN GOOGLE CHROME", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00AAFF)),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("I UNDERSTAND", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun SendNoticeFormDialog(
    apps: List<AppEntity>,
    onDismiss: () -> Unit,
    onSubmit: (com.example.data.NoticeEntity, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var targetAppId by remember { mutableStateOf("all") }
    var isUploading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val sharedPrefs = remember { context.getSharedPreferences("dark_store_fcm_prefs", android.content.Context.MODE_PRIVATE) }
    var fcmServerKey by remember { 
        val stored = sharedPrefs.getString("fcm_server_key", "") ?: ""
        mutableStateOf(stored.ifBlank { "63dH-KuA8Y9q-V4wAZGpf_e6gFxYrhGp_qEu0LWDDik" })
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isUploading = true
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes == null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isUploading = false
                            Toast.makeText(context, "Failed to read image bytes.", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val contentType = "image/jpeg"
                    val fileName = "notice_${System.currentTimeMillis()}.jpg"
                    val uploadedUrl = com.example.data.FirebaseAuthService.uploadFile(contentType, fileName, bytes)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        isUploading = false
                        if (uploadedUrl != null) {
                            imageUrl = uploadedUrl
                            Toast.makeText(context, "Notice photo uploaded successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Upload failed. Please enter URL manually.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        isUploading = false
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151922)),
            border = BorderStroke(1.dp, Color(0xFF283141))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = "Broadcast System Announcement",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFF00AAFF),
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Send a localized push notification & announcement notice to client environments.",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.LightGray)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth().testTag("notice_title_field"),
                        label = { Text("Announcement Title", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00AAFF),
                            unfocusedBorderColor = Color(0xFF283141)
                        )
                    )
                }

                item {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .testTag("notice_message_field"),
                        label = { Text("Announcement Message", color = Color.Gray) },
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00AAFF),
                            unfocusedBorderColor = Color(0xFF283141)
                        )
                    )
                }

                item {
                    Text("Target Audience Segment", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { targetAppId = "all" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (targetAppId == "all") Color(0xFF00AAFF) else Color(0xFF1E2633)
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("All Users", fontSize = 10.sp, color = Color.White)
                        }
                        
                        Button(
                            onClick = { 
                                if (apps.isNotEmpty()) {
                                    targetAppId = apps.first().packageName
                                } else {
                                    targetAppId = "all"
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (targetAppId != "all" && targetAppId != "critical_announcement") Color(0xFF00AAFF) else Color(0xFF1E2633)
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Target App", fontSize = 10.sp, color = Color.White)
                        }

                        Button(
                            onClick = { targetAppId = "critical_announcement" },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (targetAppId == "critical_announcement") Color(0xFFEF5350) else Color(0xFF1E2633)
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = "Alert", tint = Color.White, modifier = Modifier.size(10.dp))
                                Text("Alert Banner", fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }
                }

                if (targetAppId != "all" && targetAppId != "critical_announcement" && apps.isNotEmpty()) {
                    item {
                        var expandedDropDown by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val selectedAppName = apps.find { it.packageName == targetAppId }?.name ?: targetAppId
                            Button(
                                onClick = { expandedDropDown = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2633))
                            ) {
                                Text("Selected: $selectedAppName", color = Color.White, fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = expandedDropDown,
                                onDismissRequest = { expandedDropDown = false },
                                modifier = Modifier.background(Color(0xFF151922))
                            ) {
                                apps.forEach { app ->
                                    DropdownMenuItem(
                                        text = { Text(app.name, color = Color.White) },
                                        onClick = {
                                            targetAppId = app.packageName
                                            expandedDropDown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add Notice Photo Link / Uploaded Media", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = imageUrl,
                            onValueChange = { imageUrl = it },
                            modifier = Modifier.fillMaxWidth().testTag("notice_image_field"),
                            placeholder = { Text("https://image-link-url.com/photo.jpg", color = Color.DarkGray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00AAFF),
                                unfocusedBorderColor = Color(0xFF283141)
                            )
                        )
                        Button(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isUploading,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF283141), contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Upload Photo", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isUploading) " uploading photo..." else "Choose Photo Uploader", fontSize = 11.sp)
                        }
                    }
                }

                item {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Firebase Cloud Messaging Options", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = fcmServerKey,
                            onValueChange = { fcmServerKey = it },
                            modifier = Modifier.fillMaxWidth().testTag("notice_fcm_key_field"),
                            label = { Text("FCM Legacy Server Key", color = Color.Gray) },
                            placeholder = { Text("Enter Server Key from Firebase settings", color = Color.DarkGray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00AAFF),
                                unfocusedBorderColor = Color(0xFF283141)
                            )
                        )
                        Text(
                            text = "Enable Legacy Cloud Messaging API in Google Firebase Console. If configured, a push alert will be broadcasted to all terminals.",
                            fontSize = 10.sp,
                            color = Color.LightGray.copy(alpha = 0.6f)
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color.Gray),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
                        ) {
                            Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        
                        Button(
                            onClick = {
                                if (title.isBlank() || message.isBlank()) {
                                    Toast.makeText(context, "Fields cannot be blank!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                // Persist server key inside Shared Preferences
                                sharedPrefs.edit().putString("fcm_server_key", fcmServerKey.trim()).apply()

                                val newNotice = com.example.data.NoticeEntity(
                                    id = "ntc_" + System.currentTimeMillis(),
                                    title = title,
                                    message = message,
                                    imageUrl = imageUrl,
                                    timestamp = System.currentTimeMillis(),
                                    targetAppId = targetAppId
                                )
                                onSubmit(newNotice, fcmServerKey)
                            },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00AAFF))
                        ) {
                            Text("PUBLISH & PUSH", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// 12. FULLSCREEN INTERACTIVE LIGHTBOX VIEW FOR SCREENSHOTS
// ========================================================
@Composable
fun ScreenshotLightboxDialog(
    screenshots: List<String>,
    initialIndex: Int,
    appName: String,
    onDismiss: () -> Unit
) {
    var currentIndex by remember { mutableStateOf(initialIndex) }
    var isZoomed by remember(currentIndex) { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isZoomed) 1.8f else 1.0f, label = "zoom_state_anim")
    
    // Accumulate drag displacements for swiping gestures
    var dragAmountAccumulated by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF90B0D12)) // Dark sleek immersive cosmic background
        ) {
            // Main image viewer box with swipe detection and double tap / click to toggle zoom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentIndex) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragAmountAccumulated > 140f) {
                                    if (currentIndex > 0) {
                                        currentIndex--
                                        isZoomed = false
                                    }
                                } else if (dragAmountAccumulated < -140f) {
                                    if (currentIndex < screenshots.size - 1) {
                                        currentIndex++
                                        isZoomed = false
                                    }
                                }
                                dragAmountAccumulated = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragAmountAccumulated += dragAmount
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = screenshots[currentIndex],
                    contentDescription = "App High Resolution Screenshot Frame",
                    error = painterResource(id = R.drawable.img_app_logo_new),
                    modifier = Modifier
                        .fillMaxSize(0.85f)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            isZoomed = !isZoomed
                        },
                    contentScale = ContentScale.Fit
                )
            }

            // Top Toolbar: Status indices and actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = appName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Screenshot ${currentIndex + 1} of ${screenshots.size}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Modern Zoom indicator badge
                    Surface(
                        onClick = { isZoomed = !isZoomed },
                        color = if (isZoomed) Color(0xFF34D399) else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier.testTag("lightbox_zoom_toggle")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Zoom toggle symbol",
                                tint = if (isZoomed) Color(0xFF0B0D12) else Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isZoomed) "1.8x Zoom" else "Fit View",
                                color = if (isZoomed) Color(0xFF0B0D12) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Close Button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .testTag("lightbox_close_button")
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close full screen screenshot viewer",
                            tint = Color.White
                        )
                    }
                }
            }

            // Left Navigation Overlay Click zones & Arrows
            if (currentIndex > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp)
                ) {
                    IconButton(
                        onClick = {
                            currentIndex--
                            isZoomed = false
                        },
                        modifier = Modifier
                            .testTag("lightbox_prev_button")
                            .size(50.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Load previous screenshot",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Right Navigation Overlay Click zones & Arrows
            if (currentIndex < screenshots.size - 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                ) {
                    IconButton(
                        onClick = {
                            currentIndex++
                            isZoomed = false
                        },
                        modifier = Modifier
                            .testTag("lightbox_next_button")
                            .size(50.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Load next screenshot",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Bottom Navigation & Mini-Thumblist Pager Strip
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .padding(bottom = 24.dp, top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Interactive dot line indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    screenshots.forEachIndexed { i, _ ->
                        val selected = i == currentIndex
                        Box(
                            modifier = Modifier
                                .size(if (selected) 8.dp else 6.dp)
                                .background(
                                    color = if (selected) Color(0xFF34D399) else Color.White.copy(alpha = 0.35f),
                                    shape = CircleShape
                                )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Clicking mini horizontal gallery strips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp)
                ) {
                    itemsIndexed(screenshots, key = { i, thumbUrl -> "$i-$thumbUrl" }) { i, thumbUrl ->
                        val isActive = i == currentIndex
                        val activeBorderColor = if (isActive) Color(0xFF34D399) else Color.White.copy(alpha = 0.4f)
                        
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(
                                    border = BorderStroke(if (isActive) 2.dp else 1.dp, activeBorderColor),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    currentIndex = i
                                    isZoomed = false
                                }
                        ) {
                            AsyncImage(
                                model = thumbUrl,
                                contentDescription = "Direct switch to screenshot $i",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// 13. PROMOTED APPS INTERACTIVE AUTO-SLIDING CAROUSEL
// ========================================================
@Composable
fun PromotedAppsCarousel(
    featuredList: List<AppEntity>,
    allApps: List<AppEntity>,
    isDarkMode: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    accentColor: Color,
    onAppClick: (AppEntity) -> Unit
) {
    // Collect promoted apps: either featuredList, or if featuredList is empty, use the first 4 apps in allApps as a fallback!
    val promotedApps = remember(featuredList, allApps) {
        if (featuredList.isNotEmpty()) {
            featuredList
        } else {
            allApps.take(4)
        }
    }

    if (promotedApps.isEmpty()) {
        // Fallback static Hero if absolutely no apps are available
        PlayStoreBannerHero(accentColor)
        return
    }

    var currentIndex by remember(promotedApps) { mutableStateOf(0) }
    var slideDirectionLeft by remember { mutableStateOf(true) }

    // Auto-slide running inside a LaunchedEffect:
    LaunchedEffect(currentIndex, promotedApps.size) {
        if (promotedApps.size > 1) {
            kotlinx.coroutines.delay(4000L)
            slideDirectionLeft = true
            currentIndex = (currentIndex + 1) % promotedApps.size
        }
    }

    val activeApp = promotedApps[currentIndex]

    // To implement touch swiping gestures easily:
    var dragAmountAccumulated by remember { mutableStateOf(0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(134.dp)
            .pointerInput(currentIndex, promotedApps.size) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAmountAccumulated > 120f) {
                            // Swipe right (load previous)
                            if (promotedApps.size > 1) {
                                slideDirectionLeft = false
                                currentIndex = if (currentIndex > 0) currentIndex - 1 else promotedApps.size - 1
                            }
                        } else if (dragAmountAccumulated < -120f) {
                            // Swipe left (load next)
                            if (promotedApps.size > 1) {
                                slideDirectionLeft = true
                                currentIndex = (currentIndex + 1) % promotedApps.size
                            }
                        }
                        dragAmountAccumulated = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragAmountAccumulated += dragAmount
                    }
                )
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFF1F5F9)),
        border = BorderStroke(1.dp, if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val cardBgGradient = remember(isDarkMode, accentColor) {
                if (isDarkMode) {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            accentColor.copy(alpha = 0.15f),
                            Color(0xFF0F172A)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White,
                            accentColor.copy(alpha = 0.1f),
                            Color.White
                        )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(cardBgGradient)
                    .clickable { onAppClick(activeApp) }
                    .padding(14.dp)
            ) {
                AnimatedContent(
                    targetState = activeApp,
                    transitionSpec = {
                        if (slideDirectionLeft) {
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width } + fadeOut())
                        } else {
                            (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut())
                        }
                    },
                    label = "promoted_app_transition",
                    modifier = Modifier.fillMaxSize()
                ) { app ->
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "PROMOTED • ${app.category.uppercase()}",
                                    color = accentColor,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Text(
                                text = app.name,
                                color = textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            
                            Text(
                                text = app.description.ifBlank { "Modern app built securely for the catalog store platform." },
                                color = textSecondary,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp, end = 8.dp)
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(start = 12.dp)
                        ) {
                            AppLogo(
                                logoUrl = app.logo,
                                appName = app.name,
                                packageName = app.packageName,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                            )
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Button(
                                onClick = { onAppClick(app) },
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text(
                                    text = "VIEW",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (promotedApps.size > 1) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        promotedApps.forEachIndexed { i, _ ->
                            val selected = i == currentIndex
                            Box(
                                modifier = Modifier
                                    .size(if (selected) 6.dp else 4.dp)
                                    .background(
                                        color = if (selected) accentColor else textSecondary.copy(alpha = 0.4f),
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineStepRow(
    title: String,
    subtitle: String,
    statusIcon: androidx.compose.ui.graphics.vector.ImageVector,
    statusColor: androidx.compose.ui.graphics.Color,
    isLast: Boolean,
    textPrimaryCol: androidx.compose.ui.graphics.Color,
    textSecondaryCol: androidx.compose.ui.graphics.Color,
    borderCol: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = androidx.compose.ui.Modifier.width(24.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier
                    .size(24.dp)
                    .background(statusColor.copy(alpha = 0.12f), androidx.compose.foundation.shape.CircleShape)
                    .border(1.5.dp, statusColor, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = androidx.compose.ui.Modifier.size(12.dp)
                )
            }
            if (!isLast) {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier
                        .width(2.dp)
                        .height(36.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(statusColor, borderCol.copy(alpha = 0.4f))
                            )
                        )
                )
            }
        }
        androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.padding(bottom = if (isLast) 0.dp else 12.dp)) {
            androidx.compose.material3.Text(
                text = title, 
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, 
                fontSize = 13.sp, 
                color = textPrimaryCol
            )
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(2.dp))
            androidx.compose.material3.Text(
                text = subtitle, 
                fontSize = 11.sp, 
                color = textSecondaryCol, 
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
fun MetadataBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    accentColor: androidx.compose.ui.graphics.Color,
    surfaceCol: androidx.compose.ui.graphics.Color,
    borderCol: androidx.compose.ui.graphics.Color,
    textPrimaryCol: androidx.compose.ui.graphics.Color,
    textSecondaryCol: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .background(surfaceCol, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .border(1.dp, borderCol, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .size(28.dp)
                .background(accentColor.copy(alpha = 0.1f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon, 
                contentDescription = null, 
                tint = accentColor, 
                modifier = androidx.compose.ui.Modifier.size(14.dp)
            )
        }
        androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                text = label, 
                fontSize = 9.sp, 
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, 
                color = textSecondaryCol, 
                letterSpacing = 0.4.sp
            )
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(1.dp))
            androidx.compose.material3.Text(
                text = value, 
                fontSize = 11.sp, 
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, 
                color = textPrimaryCol, 
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

// ========================================================
// IN-APP UPDATE SYSTEM
// ========================================================
sealed class UpdateState {
    object Checking : UpdateState()
    object NoUpdateNeeded : UpdateState()
    data class UpdateRequired(
        val latestVersionCode: Int,
        val latestVersionName: String,
        val apkDownloadUrl: String,
        val updateTitle: String,
        val updateMessage: String,
        val forceUpdate: Boolean,
        val offlineMode: Boolean = false
    ) : UpdateState()
}

private fun getInstalledVersionCode(context: android.content.Context): Int {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    } catch (e: Exception) {
        1
    }
}

private fun getInstalledVersionName(context: android.content.Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }
}

private fun isNetworkAvailable(context: android.content.Context): Boolean {
    val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    if (connectivityManager != null) {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    return false
}

@Composable
fun UpdateRequiredScreen(
    update: UpdateState.UpdateRequired,
    context: android.content.Context,
    onSkip: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var downloadProgress by remember { mutableStateOf<Int?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    
    val hasInternet = remember { isNetworkAvailable(context) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Update Icon",
                tint = Color(0xFF34D399),
                modifier = Modifier.size(72.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Dark Store Update Available",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                ),
                textAlign = TextAlign.Center
            )
        }
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E222B)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Current Version",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = getInstalledVersionName(context),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Arrow",
                        tint = Color.Gray,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Latest Version",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                        Text(
                            text = update.latestVersionName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF34D399),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Divider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = Color.White.copy(alpha = 0.1f)
                )
                
                Text(
                    text = update.updateTitle.ifBlank { "New features await!" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = update.updateMessage.ifBlank { "Please update to continue using Dark Store with latest premium features." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                
                if (!hasInternet || update.offlineMode) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF4A1521), shape = RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Offline",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "An active internet connection is required to download this update.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF8A80)
                        )
                    }
                }
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isDownloading) {
                val progress = downloadProgress ?: 0
                if (progress >= 0) {
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF34D399),
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Downloading Update: $progress%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                } else {
                    CircularProgressIndicator(
                        color = Color(0xFF34D399)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Downloading Update...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
            
            downloadError?.let { err ->
                Text(
                    text = "Download Failed: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF5252),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    if (!hasInternet && !update.offlineMode) {
                        downloadError = "No active internet connection."
                        return@Button
                    }
                    
                    isDownloading = true
                    downloadError = null
                    
                    coroutineScope.launch {
                        try {
                            val appDao = com.example.data.AppDao(context.applicationContext)
                            val repository = com.example.data.AppRepository(appDao)
                            val downloadManager = com.example.utils.CustomDownloadManager(
                                context,
                                repository
                            )
                            val downloadedFile = downloadManager.downloadSelfUpdate(update.apkDownloadUrl) { progress ->
                                downloadProgress = progress
                            }
                            
                            isDownloading = false
                            com.example.utils.ApkInstaller.installApk(context, downloadedFile)
                        } catch (e: Exception) {
                            isDownloading = false
                            downloadError = e.message ?: "Unknown error"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("update_now_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF34D399),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(25.dp),
                enabled = !isDownloading && (hasInternet && !update.offlineMode)
            ) {
                Text(
                    text = "Update Now",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        (context as? android.app.Activity)?.finishAffinity()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("exit_app_button"),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(
                        text = "Exit App",
                        color = Color.White
                    )
                }
                
                if (!update.forceUpdate) {
                    Button(
                        onClick = onSkip,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("skip_update_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text(
                            text = "Skip for Now"
                        )
                    }
                }
            }
        }
    }
}

fun com.example.data.SubmissionEntity.toAppEntity() = com.example.data.AppEntity(
    id = id,
    name = name,
    developer = developer,
    version = version,
    size = "18 MB",
    category = category,
    rating = "4.5",
    description = description,
    logo = logo,
    screenshots = screenshots,
    apkUrl = apkUrl,
    packageName = packageName,
    isFeatured = false,
    isPopular = true,
    isRecent = true,
    versionCode = 1,
    isApproved = status == "Approved",
    submittedBy = submittedBy,
    hasAds = hasAds
)

fun com.example.data.AppEntity.toSubmissionEntity() = com.example.data.SubmissionEntity(
    id = id,
    name = name,
    packageName = packageName,
    description = description,
    apkUrl = apkUrl,
    screenshots = screenshots,
    category = category,
    version = version,
    logo = logo,
    developer = developer,
    status = if (isApproved) "Approved" else "Pending",
    submittedBy = submittedBy,
    hasAds = hasAds
)

