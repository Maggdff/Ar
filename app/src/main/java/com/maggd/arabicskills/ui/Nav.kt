package com.maggd.arabicskills.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

private data class Tab(val label: String, val icon: ImageVector, val screen: Screen)

@Composable
fun BottomTabs(vm: AppViewModel) {
    val tabs = listOf(
        Tab("الرئيسية", Icons.Filled.Home, Screen.Home),
        Tab("الدروس", Icons.Filled.MenuBook, Screen.Lessons),
        Tab("اختبار", Icons.Filled.Quiz, Screen.QuizSetup()),
        Tab("بطاقات", Icons.Filled.Style, Screen.Cards),
        Tab("إحصائيات", Icons.Filled.BarChart, Screen.Stats),
    )
    NavigationBar {
        for (t in tabs) {
            val selected = vm.current == t.screen
            NavigationBarItem(
                selected = selected,
                onClick = { vm.goTab(t.screen) },
                icon = { Icon(t.icon, contentDescription = t.label) },
                label = { Text(t.label) },
            )
        }
    }
}
