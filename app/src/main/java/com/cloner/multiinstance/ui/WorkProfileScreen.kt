package com.cloner.multiinstance.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloner.multiinstance.engine.workprofile.WorkProfileManager
import com.cloner.multiinstance.model.InstalledApp
import com.cloner.multiinstance.ui.components.AppPickerSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState

/**
 * Work Profile mode (Island/Shelter method): real installs in a managed
 * profile. Reliable for almost all apps — no hooking involved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkProfileScreen() {
    val context = LocalContext.current
    var provisioned by remember { mutableStateOf<Boolean?>(null) }
    var workApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<InstalledApp?>(null) }

    fun refresh() {
        loading = true
        Thread {
            val p = WorkProfileManager.isProvisioned(context)
            val apps = if (p) WorkProfileManager.getWorkProfileApps(context) else emptyList()
            provisioned = p
            workApps = apps
            loading = false
        }.apply { isDaemon = true }.start()
    }

    LaunchedEffect(Unit) { refresh() }

    // Re-check when returning to this screen
    LaunchedEffect(provisioned) { }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(
            "Work Profile Clones",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Real installs in an isolated work profile — works with almost all apps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        when {
            loading || provisioned == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            provisioned == false -> {
                WorkProfileSetupCard(onDone = { refresh() })
            }
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showPicker = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clone app to work profile") }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (workApps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No apps in work profile yet.\nTap above to clone one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(workApps, key = { it.packageName }) { app ->
                            WorkAppRow(
                                app = app,
                                onLaunch = {
                                    val ok = WorkProfileManager.launchWorkApp(context, app.packageName)
                                    if (!ok) Toast.makeText(context, "Could not launch", Toast.LENGTH_SHORT).show()
                                },
                                onDelete = { deleteTarget = app }
                            )
                        }
                    }
                }
            }
        }
    }

    if (installing != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Installing…") },
            text = { Text("Installing ${installing} into the work profile. This takes a few seconds.") },
            confirmButton = {}
        )
    }

    if (showPicker) {
        var allApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
        LaunchedEffect(Unit) {
            Thread {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val pkgs = pm.getInstalledPackages(0)
                val list = pkgs.mapNotNull { pi ->
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    if (ai.packageName == context.packageName) return@mapNotNull null
                    if ((ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
                    // Skip already-cloned
                    if (workApps.any { it.packageName == ai.packageName }) return@mapNotNull null
                    InstalledApp(
                        appName = runCatching { pm.getApplicationLabel(ai).toString() }
                            .getOrDefault(ai.packageName),
                        packageName = ai.packageName
                    )
                }.sortedBy { it.appName.lowercase() }
                allApps = list
            }.apply { isDaemon = true }.start()
        }
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AppPickerSheet(
                apps = allApps,
                onPick = { picked ->
                    showPicker = false
                    val apk = WorkProfileManager.apkPathOf(context, picked.packageName)
                    if (apk == null) {
                        Toast.makeText(context, "APK not found", Toast.LENGTH_SHORT).show()
                    } else {
                        installing = picked.appName
                        WorkProfileManager.installApkToWorkProfile(context, apk) { ok, msg ->
                            installing = null
                            Toast.makeText(
                                context,
                                if (ok) "Install started" else "Failed: $msg",
                                Toast.LENGTH_LONG
                            ).show()
                            // Refresh after a delay so the install can complete
                            Thread {
                                Thread.sleep(4000)
                                refresh()
                            }.apply { isDaemon = true }.start()
                        }
                    }
                }
            )
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove from work profile?") },
            text = { Text("\"${target.appName}\" will be uninstalled from the work profile.") },
            confirmButton = {
                TextButton(onClick = {
                    WorkProfileManager.uninstallWorkApp(context, target.packageName) { refresh() }
                    deleteTarget = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun WorkProfileSetupCard(onDone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val cmd = remember { WorkProfileManager.adbCommand() }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(8.dp))
                Text("One-time setup", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            val steps = listOf(
                "1. On your phone: enable Developer options → USB debugging.",
                "2. Connect the phone to a PC with ADB installed.",
                "3. Run this command on the PC:",
            )
            steps.forEach { s ->
                Text(s, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Text(
                    cmd,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Note: on some phones you must remove all Google accounts first, then re-add them after. " +
                    "If it says \"already provisioned\", a work profile exists — remove it in Settings → Accounts first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(cmd))
                    Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy command") }
                Button(onClick = { onDone() }) { Text("I've run it — check") }
            }
        }
    }
}

@Composable
private fun WorkAppRow(
    app: InstalledApp,
    onLaunch: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, fontWeight = FontWeight.SemiBold)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onLaunch) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Launch",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
