package ru.family.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ru.family.tracker.ui.theme.FamilyTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FamilyTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Placeholder: role-based UI is added in Task 2.
                }
            }
        }
    }
}
