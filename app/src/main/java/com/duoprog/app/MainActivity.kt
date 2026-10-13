package com.duoprog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.duoprog.app.data.CurriculumLoader
import com.duoprog.app.data.model.CurriculumUnit
import com.duoprog.app.engine.runMiniCpp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppRoot() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var units by remember { mutableStateOf<List<CurriculumUnit>>(emptyList()) }
    var selected by remember { mutableStateOf<CurriculumUnit?>(null) }

    LaunchedEffect(Unit) { units = CurriculumLoader.load(context) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(selected?.title ?: "تعلّم البرمجة") })
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            val unit = selected
            if (unit == null) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(units) { u ->
                        Card(Modifier.fillMaxWidth().clickable { selected = u }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(u.title, style = MaterialTheme.typography.titleMedium)
                                Text(u.description, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(unit.lessons) { l ->
                        Card(Modifier.fillMaxWidth()) {
                            Text(l.title, Modifier.padding(16.dp))
                        }
                    }
                    item { Button(onClick = { selected = null }) { Text("رجوع") } }
                }
            }
        }
    }
}
