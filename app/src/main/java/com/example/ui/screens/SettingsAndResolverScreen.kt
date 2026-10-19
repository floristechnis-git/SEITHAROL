package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.data.model.ResolverNode
import com.example.data.model.TrustZone
import com.example.ui.CascadeViewModel
import com.example.ui.theme.*

@Composable
fun SettingsAndResolverScreen(
    viewModel: CascadeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nodes by viewModel.resolverNodes.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val circuitBreakerState by viewModel.circuitBreakerState.collectAsState()
    val packVersion by viewModel.currentPackVersion.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SeitharolBg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 140.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_btn")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SeitharolDark)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Resolver & Audio Engine", fontFamily = SyneFontFamily, color = SeitharolDark, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Direct on-device resolution pipeline", fontFamily = OutfitFontFamily, color = SeitharolTextSecondary, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Overview Card
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                color = SeitharolWhite
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Audio Pipeline Invariants",
                        fontFamily = SyneFontFamily,
                        color = SeitharolDark,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Zone 1: On-Device (Direct InnerTube + QuickJS)\n• Zone 2: Self-hosted Node\n• Zone 3: Community Resolver Federation",
                        fontFamily = OutfitFontFamily,
                        color = SeitharolTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = SeitharolDivider)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Engine Pack", fontFamily = OutfitFontFamily, color = SeitharolTextSecondary, fontSize = 11.sp)
                            Text(packVersion, fontFamily = OutfitFontFamily, color = SeitharolDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("Status", fontFamily = OutfitFontFamily, color = SeitharolTextSecondary, fontSize = 11.sp)
                            Text(circuitBreakerState.name, fontFamily = OutfitFontFamily, color = SeitharolGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("Signatures", fontFamily = OutfitFontFamily, color = SeitharolTextSecondary, fontSize = 11.sp)
                            Text("Verified", fontFamily = OutfitFontFamily, color = SeitharolDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        item {
            Text(
                text = "Configured Resolver Nodes",
                fontFamily = SyneFontFamily,
                color = SeitharolDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(nodes) { node ->
            ResolverNodeCard(
                node = node,
                onSelect = { viewModel.selectResolverNode(node.id) }
            )
        }
    }
}

@Composable
fun ResolverNodeCard(
    node: ResolverNode,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect),
        color = if (node.isSelected) SeitharolWhite else SeitharolWhite.copy(alpha = 0.7f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = node.isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = SeitharolDark)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.name,
                        fontFamily = OutfitFontFamily,
                        color = SeitharolDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (node.trustZone) {
                            TrustZone.ZONE_1_DEVICE -> SeitharolGreen.copy(alpha = 0.2f)
                            TrustZone.ZONE_2_OWN_RESOLVER -> Color(0xFFFFA000).copy(alpha = 0.2f)
                            TrustZone.ZONE_3_COMMUNITY_RESOLVER -> Color(0xFFFF5252).copy(alpha = 0.2f)
                        }
                    ) {
                        Text(
                            text = when (node.trustZone) {
                                TrustZone.ZONE_1_DEVICE -> "ZONE 1"
                                TrustZone.ZONE_2_OWN_RESOLVER -> "ZONE 2"
                                TrustZone.ZONE_3_COMMUNITY_RESOLVER -> "ZONE 3"
                            },
                            color = when (node.trustZone) {
                                TrustZone.ZONE_1_DEVICE -> SeitharolDark
                                TrustZone.ZONE_2_OWN_RESOLVER -> Color(0xFFE65100)
                                TrustZone.ZONE_3_COMMUNITY_RESOLVER -> Color(0xFFC62828)
                            },
                            fontSize = 9.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${node.url} • ${node.pingMs}ms latency • ${(node.successRate * 100).toInt()}% success",
                    fontFamily = OutfitFontFamily,
                    color = SeitharolTextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
