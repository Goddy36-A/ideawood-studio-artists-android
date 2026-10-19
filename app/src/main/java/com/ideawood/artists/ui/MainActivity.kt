package com.ideawood.artists.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

private val Dark = darkColorScheme(
    primary = Color(0xFFFFD84D), onPrimary = Color(0xFF1A1400),
    secondary = Color(0xFFFF3DCB), background = Color(0xFF10131C), surface = Color(0xFF181C28),
    surfaceVariant = Color(0xFF222838),
)

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot(vm) }
            }
        }
    }
}

private val TABS = listOf("Song", "Timing", "Look", "Visuals", "Export")

@Composable
fun AppRoot(vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var tapMode by remember { mutableStateOf(false) }
    val job by vm.job.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // notification permission (Android 13+) so the progress notification is visible while rendering in the background
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    if (tapMode) { TapSyncScreen(vm) { tapMode = false }; return }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, name ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text("${i + 1}") }, label = { Text(name) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            JobCard(job, onCancel = { com.ideawood.artists.engine.JobRunner.cancel() }, onDismiss = { com.ideawood.artists.engine.JobRunner.clearResult() })
            when (tab) {
                0 -> SongScreen(vm) { tapMode = true }
                1 -> TimingScreen(vm)
                2 -> LookScreen(vm)
                3 -> VisualsScreen(vm)
                else -> ExportScreen(vm)
            }
        }
    }
}
