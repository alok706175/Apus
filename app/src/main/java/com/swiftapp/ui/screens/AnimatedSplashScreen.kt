package com.swiftapp.ui.screens

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import com.swiftapp.R
import kotlinx.coroutines.delay

/**
 * Animated Splash Screen directly rendering the exact 15 user-uploaded frames:
 * - Frame 1: 0.00s (Blank white screen)
 * - Frame 2: 0.25s
 * - Frame 3: 0.50s
 * - Frame 4: 0.75s
 * - Frame 5: 1.00s
 * - Frame 6: 1.25s
 * - Frame 7: 1.50s
 * - Frame 8: 1.75s
 * - Frame 9: 2.00s (Full orange bird)
 * - Frame 10: 2.25s (Squircle fades in)
 * - Frame 11: 2.50s (Squircle ~97% opacity)
 * - Frame 12: 2.75s (Squircle 100% with white bird)
 * - Frame 13: 3.00s (Hold)
 * - Frame 14: 3.25s (Hold)
 * - Frame 15: 3.50s (Hold final state, then navigate to main app)
 */
@Composable
fun AnimatedSplashScreen(
    onSplashFinished: () -> Unit
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.let {
            val insetsController = WindowCompat.getInsetsController(it, view)
            insetsController.isAppearanceLightStatusBars = true
            insetsController.isAppearanceLightNavigationBars = true
        }
        onDispose {}
    }

    val frameResources = remember {
        intArrayOf(
            R.drawable.splash_frame_1,
            R.drawable.splash_frame_2,
            R.drawable.splash_frame_3,
            R.drawable.splash_frame_4,
            R.drawable.splash_frame_5,
            R.drawable.splash_frame_6,
            R.drawable.splash_frame_7,
            R.drawable.splash_frame_8,
            R.drawable.splash_frame_9,
            R.drawable.splash_frame_10,
            R.drawable.splash_frame_11,
            R.drawable.splash_frame_12,
            R.drawable.splash_frame_13,
            R.drawable.splash_frame_14,
            R.drawable.splash_frame_15
        )
    }

    var currentFrameIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        // Step through each of the exact 15 frames at 250ms intervals
        for (i in frameResources.indices) {
            currentFrameIndex = i
            delay(250)
        }
        // Small delay to hold final frame before smooth crossfade to home screen
        delay(200)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = frameResources[currentFrameIndex]),
            contentDescription = "Splash Frame",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

@Preview(showBackground = true, widthDp = 1080, heightDp = 1920)
@Composable
fun SplashScreenPreview() {
    AnimatedSplashScreen(onSplashFinished = {})
}
