package com.maggd.arabicskills.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)?,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Green, titleContentColor = Color.White, navigationIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
fun ScrollColumn(pad: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionCard(title: String? = null, container: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            this.content()
        }
    }
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 16.sp, lineHeight = 28.sp)
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier, shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label, fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
fun BigButton(text: String, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    if (secondary) {
        OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(14.dp)) {
            Text(text, fontSize = 16.sp)
        }
    } else {
        Button(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(14.dp)) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PercentBar(percent: Int, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { percent.coerceIn(0, 100) / 100f },
        modifier = modifier.fillMaxWidth().height(8.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
fun Tag(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
