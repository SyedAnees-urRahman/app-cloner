package com.cloner.multiinstance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloner.multiinstance.engine.CrashReporter
import com.cloner.multiinstance.engine.HookDiagnostics
import com.cloner.multiinstance.ui.DashboardScreen
import com.cloner.multiinstance.ui.WorkProfileScreen
import com.cloner.multiinstance.ui.theme.AppClonerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppClonerTheme {
                var tab by remember { mutableIntStateOf(1) } // default: Work Profile (reliable)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                text = { Text("Virtual clones") }
                            )
                            Tab(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                text = { Text("Work profile") }
                            )
                        }
                        androidx.compose.foundation.layout.Box(
                            Modifier.weight(1f).fillMaxWidth()
                        ) {
                            if (tab == 0) DashboardScreen() else WorkProfileScreen()
                        }
                    }
                }

                var crashText by remember { mutableStateOf<String?>(null) }
                var diagText by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    crashText = CrashReporter.latest(this@MainActivity)?.readText()
                    diagText = HookDiagnostics.tail(this@MainActivity)
                }
                val clipboard = LocalClipboardManager.current
                crashText?.let { full ->
                    AlertDialog(
                        onDismissRequest = { /* dismiss via buttons only */ },
                        title = { Text("Crash report", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                diagText?.let { d ->
                                    Text(
                                        "Diagnostics",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(d, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    Spacer(Modifier.height(10.dp))
                                }
                                Text(
                                    "Details",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(full.take(4000), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val combined = buildString {
                                    diagText?.let {
                                        append("=== Diagnostics ===\n").append(it).append("\n\n")
                                    }
                                    append("=== Crash report ===\n").append(full)
                                }
                                clipboard.setText(AnnotatedString(combined))
                            }) { Text("Copy report") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                CrashReporter.clear(this@MainActivity)
                                HookDiagnostics.clear(this@MainActivity)
                                crashText = null
                                diagText = null
                            }) { Text("Dismiss") }
                        }
                    )
                }
            }
        }
    }
}
