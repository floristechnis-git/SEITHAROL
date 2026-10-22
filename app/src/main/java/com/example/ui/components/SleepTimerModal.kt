package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerModal(
    currentMinutesLeft: Int,
    onSetTimer: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val modalSheetState = rememberModalBottomSheetState()
    var selectedMinutes by remember { mutableIntStateOf(if (currentMinutesLeft > 0) currentMinutesLeft else 25) }

    val options = listOf(0, 10, 15, 20, 25, 30, 45, 60)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modalSheetState,
        containerColor = SeitharolBg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 14.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SeitharolHandleBar)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("sleep_timer_modal"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Stop audio in",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = SeitharolDark
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                options.forEach { mins ->
                    val isSelected = selectedMinutes == mins
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .clickable { selectedMinutes = mins },
                        shape = RoundedCornerShape(19.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = when {
                                    mins == 0 -> "Off (Don't stop)"
                                    isSelected -> "$mins minutes"
                                    else -> "$mins min"
                                },
                                color = if (isSelected) SeitharolDark else SeitharolTextSecondary,
                                fontSize = if (isSelected) 17.sp else 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Start / Turn Off Button (uses primary accent so text is never white-on-white in Dark Mode)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable {
                        onSetTimer(selectedMinutes)
                        onDismiss()
                    }
                    .testTag("sleep_timer_start_btn"),
                shape = RoundedCornerShape(24.dp),
                color = SeitharolPillBg
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (selectedMinutes == 0) "Turn Off Timer" else "Start Timer",
                        color = SeitharolPillText,
                        fontSize = 15.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
