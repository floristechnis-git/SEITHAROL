package com.example.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.delay

// Exact hex colors extracted from ic_splash_logo.svg
private val SplashBackgroundColor = Color(0xFF1B1C1C)
private val BrandPrimaryTextColor = Color(0xFFE3E4E2)
private val BrandMutedTextColor = Color(0xFF9E9E9E)

@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit
) {
    val scale = remember { Animatable(0.92f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
        )
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 500)
        )
        delay(1200L)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBackgroundColor)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        // Center: Square Logo with subtle border radius
        Image(
            painter = painterResource(id = R.drawable.ic_splash_logo),
            contentDescription = "App Logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(144.dp)
                .scale(scale.value)
                .clip(RoundedCornerShape(22.dp)) // Square with subtle border radius
        )

        // Bottom Center: "from \n Floris Technis"
        Text(
            text = buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        color = BrandMutedTextColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.5.sp
                    )
                ) {
                    append("from\n")
                }
                withStyle(
                    style = SpanStyle(
                        color = BrandPrimaryTextColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp
                    )
                ) {
                    append("Floris Technis")
                }
            },
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
                .alpha(alpha.value)
        )
    }
}
