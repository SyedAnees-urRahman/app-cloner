package com.cloner.multiinstance.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val DefaultBadgeColors = listOf(
    0xFFEF4444, 0xFFF59E0B, 0xFF10B981,
    0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899
)

@Composable
fun CloneCustomizerDialog(
    appName: String,
    defaultNumber: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit
) {
    var label by remember { mutableStateOf("$appName #$defaultNumber") }
    var color by remember { mutableStateOf(DefaultBadgeColors.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create clone") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Clone name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Badge color", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DefaultBadgeColors.forEach { c ->
                        val ringWidth by animateDpAsState(
                            targetValue = if (c == color) 3.dp else 0.dp,
                            animationSpec = tween(150),
                            label = "colorRing"
                        )
                        Box(
                            Modifier.size(36.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(ringWidth, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(label.trim().ifBlank { "$appName #$defaultNumber" }, color)
            }) { Text("Create clone") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
