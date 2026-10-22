package com.maggd.waipanalyzer.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.maggd.waipanalyzer.R
import com.maggd.waipanalyzer.core.Confidence
import com.maggd.waipanalyzer.core.RemoteEndpoint
import com.maggd.waipanalyzer.core.Role
import com.maggd.waipanalyzer.data.CaptureRepository
import com.maggd.waipanalyzer.data.GeoInfo
import java.util.Locale

class Actions(
    val startCapture: () -> Unit,
    val stopCapture: () -> Unit,
    val pickFile: () -> Unit,
    val setLanguage: (String?) -> Unit
)

@Composable
fun AppScreen(vm: MainViewModel, actions: Actions) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val msg = state.message
    if (msg != null) {
        val text = if (msg.arg != null) stringResource(msg.resId, msg.arg) else stringResource(msg.resId)
        LaunchedEffect(msg) {
            snackbar.showSnackbar(text)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            CreditBanner()
            val titles = listOf(R.string.tab_capture, R.string.tab_import, R.string.tab_results, R.string.tab_about)
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t), maxLines = 1, fontSize = 13.sp) })
                }
            }
            when (tab) {
                0 -> CaptureTab(vm, actions, onResults = { tab = 2 })
                1 -> ImportTab(vm, actions, onResults = { tab = 2 })
                2 -> ResultsTab(vm)
                else -> AboutTab(actions)
            }
        }
    }
}

/** The required credit line, pinned above everything else. */
@Composable
fun CreditBanner() {
    Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.credit), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(stringResource(R.string.app_name), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

@Composable
private fun Section(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
private fun CallStatus(callLikely: Boolean, hasData: Boolean) {
    if (!hasData) return
    Badge(
        stringResource(if (callLikely) R.string.call_detected else R.string.call_not_detected),
        if (callLikely) Color(0xFF2E7D32) else Color(0xFF78909C)
    )
}

// ------------------------------------------------------------------ capture

@Composable
private fun CaptureTab(vm: MainViewModel, actions: Actions, onResults: () -> Unit) {
    val state by vm.state.collectAsState()
    var consent by remember { mutableStateOf(false) }
    val r = state.result
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section {
            Text(stringResource(R.string.capture_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.capture_desc), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.capture_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            if (state.live) {
                Button(onClick = actions.stopCapture, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_stop)) }
            } else {
                Button(onClick = { consent = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.importing) { Text(stringResource(R.string.btn_start)) }
            }
        }
        if (state.live || r.packets > 0) {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (state.live) R.string.status_running else R.string.status_stopped),
                        fontWeight = FontWeight.Bold
                    )
                    if (state.live) CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                }
                Text(stringResource(R.string.stat_packets, r.packets.toString()))
                Text(stringResource(R.string.stat_duration, Fmt.duration(r.durationMs)))
                CallStatus(r.callLikely, r.packets > 0)
                if (state.live && r.packets == 0L) Text(stringResource(R.string.hint_make_call), style = MaterialTheme.typography.bodySmall)
                if (r.endpoints.isNotEmpty()) OutlinedButton(onClick = onResults, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_view_results, r.endpoints.size.toString())) }
            }
        }
    }
    if (consent) {
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { Text(stringResource(R.string.consent_title)) },
            text = { Text(stringResource(R.string.consent_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { consent = false; actions.startCapture() }) { Text(stringResource(R.string.consent_accept)) } },
            dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(R.string.consent_cancel)) } }
        )
    }
}

// ------------------------------------------------------------------ import

@Composable
private fun ImportTab(vm: MainViewModel, actions: Actions, onResults: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section {
            Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.import_desc), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.pickFile, modifier = Modifier.fillMaxWidth(), enabled = !state.live && !state.importing) { Text(stringResource(R.string.btn_pick)) }
            if (state.importing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.importing))
                }
            }
        }
        val r = state.result
        if (!state.live && !state.importing && state.fileName != null) {
            Section {
                Text(state.fileName.orEmpty(), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                r.format?.let { Text(stringResource(R.string.label_format, it.uppercase())) }
                Text(stringResource(R.string.stat_packets, r.packets.toString()))
                if (r.unsupportedPackets > 0) Text(stringResource(R.string.stat_unsupported, r.unsupportedPackets.toString()), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.stat_duration, Fmt.duration(r.durationMs)))
                CallStatus(r.callLikely, r.packets > 0)
                OutlinedButton(onClick = onResults, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_view_results, r.endpoints.size.toString())) }
            }
        }
    }
}

// ------------------------------------------------------------------ results

@Composable
private fun ResultsTab(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val r = state.result
    if (r.endpoints.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.results_empty), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Section {
                CallStatus(r.callLikely, true)
                r.localAddress?.let { Text(stringResource(R.string.report_local, it), fontSize = 13.sp) }
                if (r.selfPublicAddresses.isNotEmpty()) Text(stringResource(R.string.report_self, r.selfPublicAddresses.joinToString(", ")), fontSize = 13.sp)
                Text(stringResource(R.string.peer_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { vm.lookupAll() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.btn_lookup_all), fontSize = 12.sp) }
                    OutlinedButton(onClick = { share(ctx, vm.report(ctx)) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.btn_share_report), fontSize = 12.sp) }
                }
                if (state.hasPcap) OutlinedButton(onClick = { sharePcap(ctx) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_share_pcap), fontSize = 12.sp) }
                Text(stringResource(R.string.lookup_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        items(r.endpoints, key = { it.ip }) { e ->
            EndpointCard(e, state.lookups[e.ip], e.ip in state.lookingUp, onLookup = { vm.lookup(e.ip) })
        }
    }
}

private fun roleColor(r: Role) = when (r) {
    Role.WHATSAPP_SERVER -> Color(0xFF1565C0)
    Role.WHATSAPP_RELAY -> Color(0xFF6A1B9A)
    Role.PEER_CANDIDATE -> Color(0xFFE65100)
    else -> Color(0xFF607D8B)
}

@Composable
private fun EndpointCard(e: RemoteEndpoint, geo: GeoInfo?, looking: Boolean, onLookup: () -> Unit) {
    val clip = LocalClipboardManager.current
    val c = roleColor(e.role)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (e.isCallRelated) 2.dp else 1.dp, if (e.isCallRelated) c else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.ip, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { clip.setText(AnnotatedString(e.ip)) }) { Text(stringResource(R.string.btn_copy), fontSize = 12.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(stringResource(MainViewModel.roleRes(e.role)), c)
                if (e.role != Role.LOCAL_NETWORK && e.role != Role.OTHER) {
                    Badge(
                        stringResource(MainViewModel.confRes(e.confidence)),
                        when (e.confidence) { Confidence.HIGH -> Color(0xFF2E7D32); Confidence.MEDIUM -> Color(0xFFF9A825); else -> Color(0xFF78909C) }
                    )
                }
            }
            Text("${e.protocols.joinToString("/")}  ·  ${stringResource(R.string.label_ports)}: ${e.ports.joinToString(", ").ifEmpty { "-" }}", fontSize = 13.sp)
            Text("${stringResource(R.string.label_traffic)}: ↑ ${e.pktsOut}  ↓ ${e.pktsIn}  ·  ${Fmt.bytes(e.bytes)}", fontSize = 13.sp)
            if (e.hostnames.isNotEmpty()) Text("${stringResource(R.string.label_hosts)}: ${e.hostnames.joinToString(", ")}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (e.reasons.isNotEmpty()) {
                Column {
                    e.reasons.forEach { Text("• " + stringResource(MainViewModel.reasonRes(it)), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
                }
            }
            if (geo != null) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        geo.countryName(Locale.getDefault())?.let { Text("${stringResource(R.string.label_country)}: $it", fontSize = 13.sp) }
                        geo.org?.let { Text("${stringResource(R.string.label_isp)}: $it", fontSize = 13.sp) }
                        geo.network?.let { Text("${stringResource(R.string.label_network)}: $it", fontSize = 12.sp) }
                        Text(stringResource(R.string.geo_source_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            } else if (e.ipClass == com.maggd.waipanalyzer.core.IpClass.PUBLIC && e.role != Role.DNS) {
                if (looking) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                else TextButton(onClick = onLookup) { Text(stringResource(R.string.btn_lookup), fontSize = 12.sp) }
            }
        }
    }
}

// ------------------------------------------------------------------ about

@Composable
private fun AboutTab(actions: Actions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section {
            Text(stringResource(R.string.language_title), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { actions.setLanguage("ar") }, modifier = Modifier.weight(1f)) { Text("العربية") }
                OutlinedButton(onClick = { actions.setLanguage("en") }, modifier = Modifier.weight(1f)) { Text("English") }
                OutlinedButton(onClick = { actions.setLanguage(null) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.lang_system), fontSize = 12.sp) }
            }
        }
        Section {
            Text(stringResource(R.string.disclaimer_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyMedium)
        }
        Section {
            Text(stringResource(R.string.privacy_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium)
        }
        Section {
            Text(stringResource(R.string.limits_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.limits_body), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------ sharing

private fun share(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun sharePcap(ctx: Context) {
    val f = CaptureRepository.pcapFile ?: return
    if (!f.exists()) return
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType("application/vnd.tcpdump.pcap")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
