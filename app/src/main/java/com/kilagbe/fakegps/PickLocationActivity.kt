package com.kilagbe.fakegps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilagbe.fakegps.ui.*
import kotlinx.coroutines.launch

class PickLocationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FakeGPSTheme {
                PickLocationScreen(onDismiss = { finish() })
            }
        }
    }
}

@Composable
fun PickLocationScreen(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { LocationRepository(context) }
    val saved by repo.savedLocationsFlow.collectAsState(initial = emptyList())
    val activeState by repo.activeStateFlow.collectAsState(initial = Triple(false, 0.0, 0.0))
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(300.dp)
                .heightIn(max = 420.dp)
                .clickable(enabled = false) { },
            shape = RoundedCornerShape(20.dp),
            color = SurfaceColor,
            shadowElevation = 8.dp
        ) {
            Column {
                Text(
                    "লোকেশন বেছে নিন",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
                HorizontalDivider(color = BorderColor)

                if (saved.isEmpty()) {
                    Text(
                        "কোনো লোকেশন সেভ করা নেই",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(saved) { loc ->
                            val isActive = activeState.first &&
                                kotlin.math.abs(loc.lat - activeState.second) < 0.00001 &&
                                kotlin.math.abs(loc.lng - activeState.third) < 0.00001
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        startMock(context, loc.lat, loc.lng, loc.name)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.LocationOn,
                                    contentDescription = null,
                                    tint = if (isActive) Teal else Color(0xFF9AA8A5),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    loc.name,
                                    modifier = Modifier.weight(1f),
                                    color = if (isActive) Teal else TextPrimary,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                                if (isActive) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Teal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = BorderColor)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            stopMock(context)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Power,
                        contentDescription = null,
                        tint = Color(0xFFDC2626),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("বন্ধ করুন", color = Color(0xFFDC2626), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
