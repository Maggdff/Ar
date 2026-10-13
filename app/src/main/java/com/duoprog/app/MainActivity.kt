package com.duoprog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.duoprog.app.data.CurriculumLoader
import com.duoprog.app.data.model.CurriculumUnit
import com.duoprog.app.data.model.Lesson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppRoot() } }
    }
}

/** شريط متحرك أعلى التطبيق */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DevBanner() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier.fillMaxWidth().background(Color(0xFF58CC02)).padding(vertical = 6.dp)
        ) {
            Text(
                text = "developed by maggd alosimi      ★      ".repeat(6),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 0,
                    velocity = 60.dp
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { (context.applicationContext as? DuoProgApplication)?.repository }
    var units by remember { mutableStateOf<List<CurriculumUnit>>(emptyList()) }
    var selected by remember { mutableStateOf<CurriculumUnit?>(null) }
    var lesson by remember { mutableStateOf<Lesson?>(null) }

    LaunchedEffect(Unit) {
        units = withContext(Dispatchers.IO) { CurriculumLoader.load(context) }
        try { repo?.ensureStatsInitialized() } catch (_: Exception) {}
    }

    val progress by (repo?.observeLessonProgress()
        ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    val completed = remember(progress) { progress.filter { it.completed }.map { it.lessonId }.toSet() }

    BackHandler(enabled = lesson != null || selected != null) {
        if (lesson != null) lesson = null else selected = null
    }

    Scaffold(topBar = {
        Column {
            DevBanner()
            TopAppBar(title = { Text(bidi(lesson?.title ?: selected?.title ?: "C++")) })
        }
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val l = lesson
            val unit = selected
            when {
                l != null -> LessonScreen(l, onExit = { lesson = null })
                unit == null -> LazyColumn(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(units) { u ->
                        Card(Modifier.fillMaxWidth().clickable { selected = u }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(bidi(u.title), style = MaterialTheme.typography.titleMedium)
                                Text(bidi(u.description), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(unit.lessons.sortedBy { it.order }) { les ->
                        Card(Modifier.fillMaxWidth().clickable { lesson = les }) {
                            Row(
                                Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(bidi(les.title), Modifier.weight(1f))
                                if (les.id in completed) Text("✅")
                            }
                        }
                    }
                    item { Button(onClick = { selected = null }) { Text("رجوع") } }
                }
            }
        }
    }
}
