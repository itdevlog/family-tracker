package ru.family.tracker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.family.tracker.parent.SosAlertBus
import ru.family.tracker.ui.child.ChildRoot
import ru.family.tracker.ui.parent.ParentRoot
import ru.family.tracker.ui.parent.SosAlertScreen
import ru.family.tracker.ui.role.RoleScreen

object Routes {
    const val ROLE = "role"
    const val PARENT = "parent"
    const val CHILD = "child"
}

private fun Role?.route(): String = when (this) {
    Role.PARENT -> Routes.PARENT
    Role.CHILD -> Routes.CHILD
    null -> Routes.ROLE
}

/**
 * Root navigation graph. A saved role wins over the role picker, so rotation and
 * process recreation land on the same root the user selected.
 */
@Composable
fun FamilyTrackerRoot(container: AppContainer) {
    val navController = rememberNavController()
    val settings = container.settings
    val api = container.api
    val startRoute = remember { settings.role.route() }

    // One inset owner for the whole graph, including the SOS overlay: status/navigation
    // bars never cover the alert buttons, the notification card or any screen header.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.ROLE) {
                RoleScreen(
                    onRoleSelected = { role ->
                        settings.role = role
                        navController.navigate(role.route()) {
                            popUpTo(Routes.ROLE) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.PARENT) { ParentRoot(settings = settings, api = api) }
            composable(Routes.CHILD) {
                ChildRoot(
                    settings = settings,
                    api = api,
                    sosSender = container.sosSender,
                )
            }
        }

        // The SOS alert is app-global: a parent's alert must cover the child/role screens
        // too (the parent may share this phone or be on the pairing screen).
        val alert by SosAlertBus.alert.collectAsStateWithLifecycle()
        alert?.let { current ->
            SosAlertScreen(
                alert = current,
                api = api,
                onClose = { SosAlertBus.clear() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
