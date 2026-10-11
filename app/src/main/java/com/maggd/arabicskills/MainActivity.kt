package com.maggd.arabicskills

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maggd.arabicskills.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { AppRoot() }
        }
    }
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    when (val s = vm.current) {
        Screen.Home -> HomeScreen(vm)
        Screen.Lessons -> LessonsScreen(vm)
        is Screen.LessonDetail -> LessonDetailScreen(vm, s.lessonId)
        is Screen.QuizSetup -> QuizSetupScreen(vm, s.scope)
        Screen.Quiz -> QuizScreen(vm)
        Screen.Result -> ResultScreen(vm)
        Screen.Mistakes -> MistakesScreen(vm)
        Screen.Cards -> CardsScreen(vm)
        Screen.Stats -> StatsScreen(vm)
    }
}
