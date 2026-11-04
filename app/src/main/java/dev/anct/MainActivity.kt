package dev.anct

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.anct.ui.AnctApp
import dev.anct.ui.theme.AnctTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnctTheme {
                AnctApp()
            }
        }
    }
}

