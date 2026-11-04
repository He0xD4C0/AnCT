package dev.anct

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import android.Manifest
import android.os.Build
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class AnctProotE2ETest {
    @get:Rule(order = 0)
    val notificationPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: AnctApplication
        get() = composeRule.activity.application as AnctApplication

    @After
    fun cleanup() = runBlocking {
        app.graph.repository.getByName(NAME)?.let {
            val spec = app.graph.repository.decode(it.entity)
            app.graph.coordinator.stop(spec)
            app.graph.coordinator.delete(spec)
        }
        Unit
    }

    @Test
    fun createsRunsExportsAndRestoresProotEnvironment() {
        composeRule.onNodeWithTag("new_instance").performClick()
        composeRule.onNodeWithTag("instance_name").performTextInput(NAME)
        composeRule.onNodeWithTag("catalog_alpine-prroot").performScrollTo().performClick()
        composeRule.onNodeWithTag("create_list")
            .performScrollToNode(hasTestTag("create_instance"))
        composeRule.onNodeWithTag("create_instance").performClick()

        composeRule.waitUntil(180_000) {
            composeRule.onAllNodes(hasTestTag("instance_$NAME")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("instance_$NAME").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("start_instance").performClick()
        try {
            composeRule.waitUntil(90_000) {
                runBlocking {
                    val current = app.graph.repository.getByName(NAME) ?: return@runBlocking false
                    current.entity.statusSnapshot is dev.anct.core.ActualState.Running
                }
            }
        } catch (error: Throwable) {
            val current = runBlocking { app.graph.repository.getByName(NAME) }
            val logs = runBlocking {
                current?.let {
                    val spec = app.graph.repository.decode(it.entity)
                    app.graph.runtimes.get(spec.kind).readLogs(spec)
                }
            }
            throw AssertionError(
                "PRoot did not enter RUNNING. state=${current?.entity?.statusSnapshot}, logs=$logs",
                error,
            )
        }
        composeRule.onNodeWithTag("terminal_instance").performClick()
        composeRule.onNodeWithTag("terminal_view").assertIsDisplayed()

        runBlocking {
            val stored = requireNotNull(app.graph.repository.getByName(NAME))
            val spec = app.graph.repository.decode(stored.entity)
            app.graph.coordinator.stop(spec)
            val archive = app.graph.backups.createArchive(spec, restartAfterExport = false)
            val bytes = archive.readBytes()
            archive.delete()
            val deleted = app.graph.coordinator.delete(spec)
            check(deleted.isSuccess) { deleted.stderr }
            val imported = app.graph.backups.importArchive(ByteArrayInputStream(bytes))
            check(imported.spec.name == NAME)
            checkNotNull(app.graph.repository.getByName(NAME))
        }
    }

    companion object {
        private const val NAME = "e2e-alpine"
    }
}
