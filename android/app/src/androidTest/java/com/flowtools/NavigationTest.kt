package com.flowtools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flowtools.session.ConnectionState
import com.flowtools.ui.screens.HomeScreen
import com.flowtools.ui.theme.FlowToolsTheme
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeShowsQuestionAndRemoteInOneTap() {
        var opened = false
        compose.setContent {
            FlowToolsTheme {
                HomeScreen(
                    ConnectionState.Connected, "PC de trabalho",
                    onOpenRemote = { opened = true },
                    onOpenText = {}, onOpenFiles = {}, onOpenAudio = {}, onPair = {},
                )
            }
        }
        compose.onNodeWithText("O que quer fazer?").assertIsDisplayed()
        compose.onNodeWithText("Controlo remoto").assertIsDisplayed()
        compose.onNodeWithText("Controlo remoto").performClick()
        assert(opened)
    }
}
