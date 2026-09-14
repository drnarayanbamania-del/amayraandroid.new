package com.amayra.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.amayra.scanner.ui.CameraScreen
import com.amayra.scanner.ui.CodeScannerScreen
import com.amayra.scanner.ui.EditScreen
import com.amayra.scanner.ui.HistoryScreen
import com.amayra.scanner.ui.HomeScreen
import com.amayra.scanner.ui.ResultScreen
import com.amayra.scanner.ui.SettingsScreen
import com.amayra.scanner.ui.TranslateScreen
import com.amayra.scanner.ui.theme.BrandBlueDark
import com.amayra.scanner.ui.theme.NeonBlue
import com.amayra.scanner.ui.theme.NeonPurple
import com.amayra.scanner.ui.theme.ScannerTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: ScannerViewModel = viewModel()
            val prefs by vm.prefs.collectAsState()
            val darkTheme = when (prefs.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            ScannerTheme(darkTheme = darkTheme) {
                BrandedApp(vm)
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val EDIT = "edit"
    const val RESULT = "result"
    const val HISTORY = "history"
    const val TRANSLATE = "translate"
    const val SETTINGS = "settings"
    const val CODE = "code"
}

@Composable
private fun BrandedApp(vm: ScannerViewModel) {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1200)
        showSplash = false
    }
    if (showSplash) SplashContent() else ScannerApp(vm)
}

@Composable
private fun SplashContent() {
    val fade by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(400),
        label = "fade"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "AI TEXT SCANNER",
                color = BrandBlueDark,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "BAMANIA'S TECH",
                color = NeonBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(26.dp))
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(NeonBlue, NeonPurple)))
                    .alpha(fade)
            )
        }
    }
}

@Composable
private fun ScannerApp(vm: ScannerViewModel) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(vm, nav) }
        composable(Routes.CAMERA) { CameraScreen(vm, nav) }
        composable(Routes.EDIT) { EditScreen(vm, nav) }
        composable(Routes.RESULT) { ResultScreen(vm, nav) }
        composable(Routes.HISTORY) { HistoryScreen(vm, nav) }
        composable(Routes.TRANSLATE) { TranslateScreen(vm, nav) }
        composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
        composable(Routes.CODE) { CodeScannerScreen(nav) }
    }
}
