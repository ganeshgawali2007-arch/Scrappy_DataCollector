package com.scraper.classroomcapture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.scraper.classroomcapture.ui.ScraperNav
import com.scraper.classroomcapture.ui.theme.ScraperTheme

/**
 * P1 host Activity. Holds no recording state — P4's foreground service owns
 * capture; this Activity only hosts navigation. Survives rotation/recreation
 * via ViewModels + Compose state (verify: rotate, background, relaunch).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScraperTheme {
                val navController = rememberNavController()
                ScraperNav(navController)
            }
        }
    }
}
