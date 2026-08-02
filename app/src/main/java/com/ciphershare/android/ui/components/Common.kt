package com.ciphershare.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ciphershare.android.model.DeviceStatus
import com.ciphershare.android.model.TransferStatus
import com.ciphershare.android.ui.theme.CipherShareColors

/** Small colored dot used for device/transfer status, mirrors the dot indicators throughout the desktop UI. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape)
    )
}

fun colorForDeviceStatus(status: DeviceStatus): Color = when (status) {
    DeviceStatus.ONLINE -> CipherShareColors.Success
    DeviceStatus.IDLE -> CipherShareColors.Warning
    DeviceStatus.OFFLINE -> CipherShareColors.TextMuted
}

fun colorForTransferStatus(status: TransferStatus): Color = when (status) {
    TransferStatus.COMPLETED -> CipherShareColors.Success
    TransferStatus.FAILED -> CipherShareColors.Danger
    TransferStatus.CANCELED -> CipherShareColors.TextMuted
    TransferStatus.ACTIVE -> CipherShareColors.Accent
    TransferStatus.PAUSED -> CipherShareColors.Warning
    TransferStatus.PENDING -> CipherShareColors.TextSecondary
}

/** A bordered, rounded card matching the desktop app's "Surface + Border" card look. */
@Composable
fun CipherCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .background(CipherShareColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, CipherShareColors.Border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

/** Small pill-shaped label, e.g. a device-type or status badge. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun IconLabel(icon: ImageVector, label: String, tint: Color = CipherShareColors.TextSecondary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodySmall)
    }
}
