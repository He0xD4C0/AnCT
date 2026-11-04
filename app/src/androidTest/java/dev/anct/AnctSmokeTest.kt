package dev.anct

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnctSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsRuntimeBoundaryAndCreateAction() {
        composeRule.onNodeWithText("AnCT").assertIsDisplayed()
        composeRule.onNodeWithText("New instance").assertIsDisplayed()
        composeRule.onNodeWithText("Instances").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        composeRule.onNodeWithText("New instance").performClick()
        composeRule.onNodeWithText("Create instance").assertIsDisplayed()
        composeRule.onNodeWithText("Local archive").assertIsDisplayed()
        composeRule.onNodeWithText("Local archive").performClick()
        composeRule.onNodeWithText("Choose archive").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Runtimes").assertIsDisplayed()
        composeRule.onNodeWithText("Enable experimental native backend").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Allow integrity warnings during import").performScrollTo().assertIsDisplayed()
    }
}
