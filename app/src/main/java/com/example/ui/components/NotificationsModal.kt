package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

data class NotificationItem(
    val title: String,
    val timeAgo: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsModal(
    onDismiss: () -> Unit
) {
    val modalSheetState = rememberModalBottomSheetState()

    val notifications = listOf(
        NotificationItem("New releases from followed artists", "1 day ago", Icons.Outlined.MusicNote),
        NotificationItem("Your Weekly Mix has been updated", "3 days ago", Icons.Outlined.QueueMusic),
        NotificationItem("Discover fresh trending tracks in your region", "5 days ago", Icons.Outlined.NewReleases)
    )

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
                .testTag("notifications_modal")
        ) {
            Text(
                text = "Notifications",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                color = SeitharolDark,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            notifications.forEach { item ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = SeitharolWhite
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = SeitharolCardElevated
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = SeitharolDark,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = SeitharolDark
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.timeAgo,
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                color = SeitharolTextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
