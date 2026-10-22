package ru.family.tracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.ui.child.ChildRoot
import ru.family.tracker.ui.parent.ParentRoot
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
fun FamilyTrackerRoot(settings: Settings, api: ApiClient) {
    val navController = rememberNavController()
    val startRoute = remember { settings.role.route() }

    NavHost(navController = navController, startDestination = startRoute) {
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
        composable(Routes.CHILD) { ChildRoot() }
    }
}
