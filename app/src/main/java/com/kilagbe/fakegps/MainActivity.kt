package com.kilagbe.fakegps

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.kilagbe.fakegps.ui.*
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val f = File(filesDir, "crash_log.txt")
                f.appendText("\n---- " + Date().toString() + " ----\n")
                f.appendText(android.util.Log.getStackTraceString(throwable))
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Configuration.getInstance().userAgentValue = packageName
        setContent {
            FakeGPSTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun CrashLogDialog(context: Context) {
    val crashLogFile = remember { File(context.filesDir, "crash_log.txt") }
    var crashLogContent by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (crashLogFile.exists()) {
            val text = crashLogFile.readText()
            if (text.isNotBlank()) crashLogContent = text
        }
    }

    val content = crashLogContent
    if (content != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("ক্র্যাশ লগ পাওয়া গেছে") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(content, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("crash log", content))
                }) { Text("কপি করুন", color = Teal) }
            },
            dismissButton = {
                TextButton(onClick = {
                    crashLogFile.delete()
                    crashLogContent = null
                }) { Text("মুছে ফেলুন", color = Color(0xFFDC2626)) }
            }
        )
    }
}

@Composable
fun PermissionRequestScreen(onRequestClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = Teal,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "লোকেশন পারমিশন দরকার",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "অ্যাপটি চালাতে লোকেশন পারমিশন দিতে হবে। এরপর Developer Options থেকে " +
                "\"Select mock location app\"-এ গিয়ে এই অ্যাপ বেছে নিতে হবে।",
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRequestClick,
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) {
            Text("পারমিশন দিন")
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { LocationRepository(context) }

    CrashLogDialog(context)

    var permissionsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!permissionsGranted) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            val restored = repo.restoreFromBackupIfEmpty()
            if (restored) {
                Toast.makeText(context, "আগের সেভ করা লোকেশন ফিরিয়ে আনা হয়েছে", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (!permissionsGranted) {
        PermissionRequestScreen {
            val permissionsToRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
        return
    }

    var tab by remember { mutableStateOf("map") }
    val saved by repo.savedLocationsFlow.collectAsState(initial = emptyList())

    Scaffold(
        containerColor = BgColor,
        bottomBar = {
            NavigationBar(containerColor = SurfaceColor) {
                NavigationBarItem(
                    selected = tab == "map",
                    onClick = { tab = "map" },
                    icon = { Icon(Icons.Filled.Map, contentDescription = null) },
                    label = { Text("ম্যাপ") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Teal, selectedTextColor = Teal)
                )
                NavigationBarItem(
                    selected = tab == "saved",
                    onClick = { tab = "saved" },
                    icon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                    label = { Text("সেভড") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Teal, selectedTextColor = Teal)
                )
                NavigationBarItem(
                    selected = tab == "settings",
                    onClick = { tab = "settings" },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("সেটিংস") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Teal, selectedTextColor = Teal)
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                "map" -> MapScreen(repo)
                "saved" -> SavedScreen(repo = repo, saved = saved)
                "settings" -> SettingsScreen(repo)
            }
        }
    }
}

fun startMock(context: android.content.Context, lat: Double, lng: Double, name: String) {
    val intent = Intent(context, MockLocationService::class.java).apply {
        action = MockLocationService.ACTION_START
        putExtra(MockLocationService.EXTRA_LAT, lat)
        putExtra(MockLocationService.EXTRA_LNG, lng)
        putExtra(MockLocationService.EXTRA_NAME, name)
    }
    ContextCompat.startForegroundService(context, intent)
}

fun stopMock(context: android.content.Context) {
    val intent = Intent(context, MockLocationService::class.java).apply {
        action = MockLocationService.ACTION_STOP
    }
    context.startService(intent)
}

fun startAutoCycle(context: android.content.Context, minutes: Int) {
    val intent = Intent(context, MockLocationService::class.java).apply {
        action = MockLocationService.ACTION_START_CYCLE
        putExtra(MockLocationService.EXTRA_INTERVAL_MINUTES, minutes)
    }
    ContextCompat.startForegroundService(context, intent)
}

fun startBubble(context: android.content.Context) {
    ContextCompat.startForegroundService(context, Intent(context, FloatingBubbleService::class.java))
}

fun stopBubble(context: android.content.Context) {
    context.stopService(Intent(context, FloatingBubbleService::class.java))
}

@SuppressLint("MissingPermission")
fun fetchCurrentLocation(context: Context, onResult: (Double, Double) -> Unit) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var best: Location? = null
    for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
        try {
            val loc = lm.getLastKnownLocation(provider)
            if (loc != null && (best == null || loc.accuracy < best!!.accuracy)) best = loc
        } catch (_: Exception) { }
    }
    if (best != null) {
        onResult(best.latitude, best.longitude)
        return
    }
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onResult(location.latitude, location.longitude)
            try { lm.removeUpdates(this) } catch (_: Exception) { }
        }
    }
    try {
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
            LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
    } catch (_: Exception) { }
}

@Composable
fun MapScreen(repo: LocationRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var centerLat by remember { mutableStateOf(23.8103) }
    var centerLng by remember { mutableStateOf(90.4125) }
    var showDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var jumpTarget by remember { mutableStateOf<GeoPoint?>(null) }
    var activeMarker by remember { mutableStateOf<Marker?>(null) }
    var initialCentered by remember { mutableStateOf(false) }
    val savedLocations by repo.savedLocationsFlow.collectAsState(initial = emptyList())

    val activeState by repo.activeStateFlow.collectAsState(initial = Triple(false, 23.8103, 90.4125))
    val locked = activeState.first
    val activePinLatLng: Pair<Double, Double>? =
        if (activeState.first) Pair(activeState.second, activeState.third) else null
    val activeName = savedLocations.firstOrNull {
        activeState.first && kotlin.math.abs(it.lat - activeState.second) < 0.00001 && kotlin.math.abs(it.lng - activeState.third) < 0.00001
    }?.name ?: "কাস্টম"

    LaunchedEffect(activeState) {
        if (initialCentered) return@LaunchedEffect
        initialCentered = true
        if (activeState.first) {
            centerLat = activeState.second
            centerLng = activeState.third
            jumpTarget = GeoPoint(activeState.second, activeState.third)
        } else {
            fetchCurrentLocation(context) { lat, lng ->
                centerLat = lat
                centerLng = lng
                jumpTarget = GeoPoint(lat, lng)
                scope.launch { repo.setRealLocation(lat, lng) }
            }
        }
    }

    LaunchedEffect(jumpTarget) {
        jumpTarget?.let { target ->
            mapViewRef?.controller?.animateTo(target)
        }
    }

    LaunchedEffect(savedLocations, mapViewRef) {
        val map = mapViewRef ?: return@LaunchedEffect
        map.overlays.removeAll { it is Marker && it.relatedObject == "saved" }
        val pinDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_map_pin)
        savedLocations.forEach { loc ->
            val marker = Marker(map).apply {
                position = GeoPoint(loc.lat, loc.lng)
                title = loc.name
                snippet = "ট্যাপ করে এই লোকেশন সেট করুন"
                icon = pinDrawable
                relatedObject = "saved"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { m, _ ->
                    centerLat = loc.lat
                    centerLng = loc.lng
                    jumpTarget = GeoPoint(loc.lat, loc.lng)
                    startMock(context, loc.lat, loc.lng, loc.name)
                    m.showInfoWindow()
                    true
                }
            }
            map.overlays.add(marker)
        }
        map.invalidate()
    }

    LaunchedEffect(activePinLatLng, mapViewRef) {
        val map = mapViewRef ?: return@LaunchedEffect
        activeMarker?.let { map.overlays.remove(it) }
        activeMarker = null
        activePinLatLng?.let { (lat, lng) ->
            val activeIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_map_pin_active)
            val marker = Marker(map).apply {
                position = GeoPoint(lat, lng)
                title = "সক্রিয় লোকেশন"
                icon = activeIcon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(marker)
            activeMarker = marker
        }
        map.invalidate()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(centerLat, centerLng))
                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                            val c = mapCenter
                            centerLat = c.latitude
                            centerLng = c.longitude
                            return true
                        }
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean = false
                    })
                    mapViewRef = this
                }
            }
        )

        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = if (locked) Teal else Color(0xFF94A3B8),
            modifier = Modifier.align(Alignment.Center).size(40.dp)
        )

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            shape = RoundedCornerShape(50),
            color = SurfaceColor,
            shadowElevation = 4.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (locked) Teal else Color(0xFF94A3B8), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        if (locked) "সক্রিয় — $activeName" else "লাইভ লোকেশন",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "%.5f, %.5f".format(centerLat, centerLng),
                        fontSize = 10.5.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 12.dp),
            shape = RoundedCornerShape(16.dp),
            color = SurfaceColor,
            shadowElevation = 4.dp
        ) {
            Column(modifier = Modifier.width(44.dp)) {
                IconButton(onClick = { showDialog = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Tag, contentDescription = "কোঅর্ডিনেট বসান", tint = TealDark, modifier = Modifier.size(19.dp))
                }
                HorizontalDivider(color = BorderColor, modifier = Modifier.width(44.dp))
                IconButton(
                    onClick = {
                        scope.launch {
                            if (locked) {
                                val real = repo.getRealLocation()
                                if (real != null) {
                                    centerLat = real.first
                                    centerLng = real.second
                                    jumpTarget = GeoPoint(real.first, real.second)
                                } else {
                                    fetchCurrentLocation(context) { lat, lng ->
                                        centerLat = lat
                                        centerLng = lng
                                        jumpTarget = GeoPoint(lat, lng)
                                    }
                                }
                            } else {
                                fetchCurrentLocation(context) { lat, lng ->
                                    centerLat = lat
                                    centerLng = lng
                                    jumpTarget = GeoPoint(lat, lng)
                                    scope.launch { repo.setRealLocation(lat, lng) }
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "বর্তমান লোকেশনে যান", tint = TealDark, modifier = Modifier.size(19.dp))
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (locked) {
                Surface(
                    onClick = { showSaveDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    color = SurfaceColor,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Star, contentDescription = "সেভ করুন", tint = Teal, modifier = Modifier.size(19.dp))
                    }
                }
                Surface(
                    onClick = { stopMock(context) },
                    shape = RoundedCornerShape(50),
                    color = Color(0xFFDC2626),
                    shadowElevation = 6.dp,
                    modifier = Modifier.height(44.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("বন্ধ করুন", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            } else {
                Surface(
                    onClick = { startMock(context, centerLat, centerLng, "কাস্টম") },
                    shape = RoundedCornerShape(50),
                    color = Teal,
                    shadowElevation = 6.dp,
                    modifier = Modifier.height(44.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    ) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("সেট করুন", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        SaveNameDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                scope.launch {
                    repo.addLocation(SavedLocation(name, centerLat, centerLng))
                }
                showSaveDialog = false
            }
        )
    }

    if (showDialog) {
        CoordinateDialog(
            initialLat = centerLat,
            initialLng = centerLng,
            onDismiss = { showDialog = false },
            onConfirm = { lat, lng ->
                centerLat = lat
                centerLng = lng
                jumpTarget = GeoPoint(lat, lng)
                showDialog = false
            }
        )
    }
}

@Composable
fun SaveNameDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("লোকেশনের নাম দিন") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("যেমন: বাসা, অফিস") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("সেভ করুন", color = Teal) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("বাতিল") }
        }
    )
}

@Composable
fun CoordinateDialog(
    initialLat: Double,
    initialLng: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit
) {
    var latStr by remember { mutableStateOf(initialLat.toString()) }
    var lngStr by remember { mutableStateOf(initialLng.toString()) }
    val latNum = latStr.toDoubleOrNull()
    val lngNum = lngStr.toDoubleOrNull()
    val valid = latNum != null && lngNum != null && latNum in -90.0..90.0 && lngNum in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("কোঅর্ডিনেট দিয়ে সেট করুন") },
        text = {
            Column {
                OutlinedTextField(
                    value = latStr,
                    onValueChange = { latStr = it },
                    label = { Text("Latitude") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = lngStr,
                    onValueChange = { lngStr = it },
                    label = { Text("Longitude") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!valid) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "সঠিক লেটিটিউড (-৯০ থেকে ৯০) ও লঙ্গিটিউড (-১৮০ থেকে ১৮০) দিন",
                        color = Color.Red,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(latNum!!, lngNum!!) },
                enabled = valid
            ) { Text("ঠিক আছে", color = Teal) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("বাতিল") }
        }
    )
}

@Composable
fun AddLocationDialog(onDismiss: () -> Unit, onConfirm: (String, Double, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var latStr by remember { mutableStateOf("") }
    var lngStr by remember { mutableStateOf("") }
    val latNum = latStr.toDoubleOrNull()
    val lngNum = lngStr.toDoubleOrNull()
    val valid = name.isNotBlank() && latNum != null && lngNum != null &&
        latNum in -90.0..90.0 && lngNum in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("নতুন লোকেশন যোগ করুন") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("নাম") },
                    placeholder = { Text("যেমন: বাসা") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = latStr,
                    onValueChange = { latStr = it },
                    label = { Text("Latitude") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = lngStr,
                    onValueChange = { lngStr = it },
                    label = { Text("Longitude") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(name.trim(), latNum!!, lngNum!!) },
                enabled = valid
            ) { Text("যোগ করুন", color = Teal) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("বাতিল") }
        }
    )
}

@Composable
fun SavedScreen(repo: LocationRepository, saved: List<SavedLocation>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    val activeState by repo.activeStateFlow.collectAsState(initial = Triple(false, 23.8103, 90.4125))

    val filtered = if (query.isBlank()) saved else saved.filter {
        it.name.contains(query, ignoreCase = true)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp, 18.dp, 16.dp, 4.dp)) {
                Text("সেভ করা লোকেশন", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "${saved.size}টি লোকেশন সেভ আছে",
                    fontSize = 11.5.sp,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SurfaceColor,
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("লোকেশন খুঁজুন", fontSize = 13.sp, color = Color(0xFF94A3B8))
                            }
                            inner()
                        }
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filtered) { loc ->
                    val isActive = activeState.first &&
                        kotlin.math.abs(loc.lat - activeState.second) < 0.00001 &&
                        kotlin.math.abs(loc.lng - activeState.third) < 0.00001

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SurfaceColor,
                        border = BorderStroke(1.dp, BorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .padding(13.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .background(if (isActive) Teal else Color(0xFFE6FBF7), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.LocationOn,
                                    contentDescription = null,
                                    tint = if (isActive) Color.White else TealDark,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).clickable { startMock(context, loc.lat, loc.lng, loc.name) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(loc.name, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = TextPrimary)
                                    if (isActive) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFE6FBF7)
                                        ) {
                                            Text(
                                                "সক্রিয়",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TealDark,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "%.5f, %.5f".format(loc.lat, loc.lng),
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            IconButton(onClick = { scope.launch { repo.removeLocation(loc.name) } }) {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(70.dp)) }
            }
        }

        Surface(
            onClick = { showAddDialog = true },
            shape = RoundedCornerShape(17.dp),
            color = Teal,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(54.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "নতুন লোকেশন যোগ করুন", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }

    if (showAddDialog) {
        AddLocationDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, lat, lng ->
                scope.launch { repo.addLocation(SavedLocation(name, lat, lng)) }
                showAddDialog = false
            }
        )
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF94A3B8),
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsIconRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(Color(0xFFE6FBF7), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = TealDark, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            description?.let {
                Text(it, fontSize = 11.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 1.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Teal)
        )
    }
}

@Composable
fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String? = null,
    buttonText: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(Color(0xFFE6FBF7), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = TealDark, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            description?.let {
                Text(it, fontSize = 11.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 1.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClick) {
            Text(buttonText, color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SettingsScreen(repo: LocationRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoStart by remember { mutableStateOf(true) }
    var jitter by remember { mutableStateOf(false) }
    val autoCycleState by repo.autoCycleFlow.collectAsState(initial = Pair(false, 10))
    var minutesText by remember(autoCycleState.second) { mutableStateOf(autoCycleState.second.toString()) }
    val savedCount by repo.savedLocationsFlow.collectAsState(initial = emptyList())
    val bubbleEnabled by repo.bubbleEnabledFlow.collectAsState(initial = false)

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            scope.launch { repo.setBubbleEnabled(true) }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val count = repo.restoreFromUri(uri)
                Toast.makeText(
                    context,
                    when {
                        count < 0 -> "ফাইল পড়া যায়নি — এটা কি সঠিক ব্যাকআপ ফাইল?"
                        count == 0 -> "নতুন কিছু পাওয়া যায়নি (সব আগে থেকেই আছে)"
                        else -> "$count টি লোকেশন ফিরিয়ে আনা হয়েছে"
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(bubbleEnabled) {
        if (bubbleEnabled && Settings.canDrawOverlays(context)) {
            startBubble(context)
        } else {
            stopBubble(context)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("সেটিংস", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(top = 18.dp))

        SettingsSectionLabel("সাধারণ")
        Surface(shape = RoundedCornerShape(16.dp), color = SurfaceColor, border = BorderStroke(1.dp, BorderColor), modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsIconRow(Icons.Filled.PowerSettingsNew, "ফোন চালু হলে অটো-স্টার্ট", checked = autoStart) {
                    autoStart = it
                    scope.launch { repo.setAutoStart(it) }
                }
                HorizontalDivider(color = BorderColor)
                SettingsIconRow(Icons.Filled.Shuffle, "র‍্যান্ডম জিটার", "±৫ মিটার এলোমেলো ভ্যারিয়েশন", checked = jitter) {
                    jitter = it
                    scope.launch { repo.setJitter(it) }
                }
            }
        }

        SettingsSectionLabel("ফ্লোটিং বাটন")
        Surface(shape = RoundedCornerShape(16.dp), color = SurfaceColor, border = BorderStroke(1.dp, BorderColor), modifier = Modifier.fillMaxWidth()) {
            SettingsIconRow(
                Icons.Filled.TouchApp,
                "ফ্লোটিং বাটন চালু করুন",
                "অন্য অ্যাপের উপরে ভেসে থাকা টগল বাটন",
                checked = bubbleEnabled
            ) { checked ->
                if (checked) {
                    if (Settings.canDrawOverlays(context)) {
                        scope.launch { repo.setBubbleEnabled(true) }
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                } else {
                    scope.launch { repo.setBubbleEnabled(false) }
                }
            }
        }

        SettingsSectionLabel("অটো সাইকেল")
        Surface(shape = RoundedCornerShape(16.dp), color = SurfaceColor, border = BorderStroke(1.dp, BorderColor), modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsIconRow(
                    Icons.Filled.Autorenew,
                    "অটো সাইকেল চালু করুন",
                    "সেভ করা লোকেশন পালাক্রমে বদলাবে",
                    checked = autoCycleState.first
                ) { checked ->
                    val minutes = minutesText.toIntOrNull()?.coerceAtLeast(1) ?: 10
                    if (checked) {
                        if (savedCount.isEmpty()) return@SettingsIconRow
                        scope.launch { repo.setAutoCycle(true, minutes) }
                        startAutoCycle(context, minutes)
                    } else {
                        scope.launch { repo.setAutoCycle(false, minutes) }
                        stopMock(context)
                    }
                }
                if (savedCount.isEmpty()) {
                    Text(
                        "প্রথমে অন্তত একটা লোকেশন সেভ করুন",
                        color = Color(0xFFDC2626),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 14.dp, bottom = 8.dp)
                    )
                }
                HorizontalDivider(color = BorderColor)
                Row(
                    Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("কত মিনিট পরপর বদলাবে", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { text ->
                            minutesText = text.filter { it.isDigit() }
                            val minutes = minutesText.toIntOrNull()
                            if (minutes != null && minutes > 0) {
                                scope.launch { repo.setAutoCycle(autoCycleState.first, minutes) }
                                if (autoCycleState.first) startAutoCycle(context, minutes)
                            }
                        },
                        suffix = { Text("মিনিট", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.width(110.dp)
                    )
                }
            }
        }

        SettingsSectionLabel("ব্যাকআপ")
        Surface(shape = RoundedCornerShape(16.dp), color = SurfaceColor, border = BorderStroke(1.dp, BorderColor), modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsActionRow(
                    icon = Icons.Filled.Save,
                    title = "এখনই ব্যাকআপ করুন",
                    description = "Download/FakeGPS ফোল্ডারে JSON ফাইলে সেভ করবে",
                    buttonText = "ব্যাকআপ"
                ) {
                    scope.launch {
                        val ok = repo.backupNow()
                        Toast.makeText(
                            context,
                            if (ok) "ব্যাকআপ সফল হয়েছে" else "ব্যাকআপ ব্যর্থ হয়েছে",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                HorizontalDivider(color = BorderColor)
                SettingsActionRow(
                    icon = Icons.Filled.Restore,
                    title = "স্বয়ংক্রিয় রিস্টোর",
                    description = "ফোন নিজে থেকে ব্যাকআপ ফাইল খুঁজে বের করবে",
                    buttonText = "রিস্টোর"
                ) {
                    scope.launch {
                        val count = repo.restoreFromBackupMerge()
                        Toast.makeText(
                            context,
                            when {
                                count < 0 -> "ব্যাকআপ ফাইল খুঁজে পাওয়া যায়নি — নিচের অপশন দিয়ে ম্যানুয়ালি বেছে নাও"
                                count == 0 -> "নতুন কিছু পাওয়া যায়নি (সব আগে থেকেই আছে)"
                                else -> "$count টি লোকেশন ফিরিয়ে আনা হয়েছে"
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                HorizontalDivider(color = BorderColor)
                SettingsActionRow(
                    icon = Icons.Filled.FolderOpen,
                    title = "ব্যাকআপ ফাইল থেকে বেছে নিন",
                    description = "স্বয়ংক্রিয় রিস্টোর কাজ না করলে এটা ব্যবহার করো (১০০% কাজ করে)",
                    buttonText = "বেছে নিন"
                ) {
                    filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                }
            }
        }

        Text(
            "সেভ করা লোকেশন প্রতিবার আপডেট হওয়ার সময় Download/FakeGPS ফোল্ডারে অটোমেটিক ব্যাকআপ হয় — অ্যাপ আনইনস্টল করলেও হারাবে না। কোনো কারণে স্বয়ংক্রিয় রিস্টোর কাজ না করলে \"ব্যাকআপ ফাইল থেকে বেছে নিন\" ব্যবহার করো।",
            fontSize = 10.5.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )
    }
}
