package org.rgv.flowreduxtest

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.koin.androidContext
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.reduxkotlin.ReducerForActionType
import org.reduxkotlin.Store
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.createStore
import org.reduxkotlin.middleware
import org.reduxkotlin.reducerForActionType
import org.rgv.flowreduxtest.ui.theme.FlowReduxTestTheme
import kotlin.reflect.KClass

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appStore: AppStore = koinInject()

            LaunchedEffect(appStore) {
                appStore.stateFlow.collect { _ ->
                    if (appStore.lastAction == ExitApp) {
                        finish()
                    }
                }
            }

            FlowReduxTestTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val appStore: AppStore = koinInject()
    val appState by appStore.stateFlow.collectAsState()

    BackHandler(enabled = appState.currentScreen != Screen.HOME) {
        appStore.dispatchAction(NavigationAction.NavigateBack)
    }

    when (appState.currentScreen) {
        Screen.HOME -> HomeScreen()
        Screen.PROFILE -> ProfileScreen()
        Screen.SETTINGS -> SettingsScreen()
    }
}


@Composable
fun HomeScreen() {

    val appStore: AppStore = koinInject()
    val appState by appStore.stateFlow.collectAsState()
    val dispatcher: ActionDispatcher = appStore
    var showExitDialog by remember { mutableStateOf(false) }

    BackHandler {
        showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit App") },
            text = { Text("Are you sure you want to exit the app?") },
            confirmButton = {
                Button(onClick = {
                    dispatcher.dispatchAction(ExitApp)
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                Button(onClick = { showExitDialog = false }) {
                    Text("No")
                }
            }
        )
    }

    Column {
        Text("Counter: ${appState.homeState.counter}")
        Button(onClick = { dispatcher.dispatchAction(HomeAction.Increment) }) {
            Text("+")
        }
        Button(onClick = { dispatcher.dispatchAction(HomeAction.Decrement) }) {
            Text("-")
        }
        Button(onClick = {
            dispatcher.dispatchAction(HomeAction.FetchData1)
            dispatcher.dispatchAction(HomeAction.FetchData2)
        }) {
            Text("Fetch Data")
        }
        if (appState.homeState.isLoading1 || appState.homeState.isLoading2) {
            CircularProgressIndicator()
        } else {
            Text("Data 1: ${appState.homeState.data1 ?: "No data"}")
            Text("Data 2: ${appState.homeState.data2 ?: "No data"}")
        }
        Button(onClick = { dispatcher.dispatchAction(NavigationAction.NavigateToProfile("myUserId")) }) {
            Text("Go to Profile")
        }
    }
}


class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApplication)
            modules(appModule)
        }
    }
}

//////////// KOIN ////////////

val appModule = module {
    single {
        createStore(appReducer, AppState(), applyMiddleware(createThunkMiddleware(get(), get())))
    }

    single { CoroutineScope(Dispatchers.Default) }

    single { AppStore(get(), get()) }

    single<Set<TypedSideEffect<*, *>>> {
        setOf(
            TypedSideEffect(LoggingSideEffect(), Action::class) { it },
            TypedSideEffect(FetchData1SideEffect(), HomeAction.FetchData1::class) { it.homeState },
            TypedSideEffect(FetchData2SideEffect(), HomeAction.FetchData2::class) { it.homeState }
        )
    }


}


//////////// REDUX-CORE ////////////

interface ActionDispatcher {
    fun dispatchAction(action: Action)
}

sealed interface Action

interface SideEffect<in STATE, in ACTION : Action> {
    suspend operator fun invoke(state: STATE, action: ACTION): Action?
}

class TypedSideEffect<STATE, ACTION : Action>(
    val sideEffect: SideEffect<STATE, ACTION>,
    val actionType: KClass<ACTION>,
    val stateSelector: (AppState) -> STATE
)

//////////// Global Actions  ////////////
enum class Screen {
    HOME, PROFILE, SETTINGS
}

sealed class NavigationAction : Action {
    data class NavigateToProfile(val userId: String) : NavigationAction()
    data class NavigateToSettings(val fromScreen: Screen) : NavigationAction()
    data object NavigateBack : NavigationAction()
}

data object ExitApp : Action

//////////// STORE ////////////
class AppStore(
    private val store: Store<AppState>,
    private val scope: CoroutineScope
) : ActionDispatcher {

    private val _stateFlow = MutableStateFlow(AppState())
    val stateFlow: StateFlow<AppState> = _stateFlow.asStateFlow()

    var lastAction: Action? = null
        private set

    init {
        store.subscribe {
            _stateFlow.value = store.state
        }
    }

    override fun dispatchAction(action: Action) {
        store.dispatch(action)
        lastAction = action
    }
}

fun createThunkMiddleware(
    scope: CoroutineScope,
    sideEffects: Set<TypedSideEffect<*, *>>
) = middleware { store, next, action ->

    next(action)
    if (action !is Action) return@middleware Unit

    sideEffects.asFlow()
        .mapNotNull { typedEffect ->
            if (typedEffect.actionType.isInstance(action)) {
                @Suppress("UNCHECKED_CAST")
                typedEffect.stateSelector(store.state)?.let { selectedState ->
                    (typedEffect.sideEffect as SideEffect<Any, Action>).invoke(
                        selectedState,
                        action
                    )
                }
            } else null
        }
        .onEach { resultAction ->
            store.dispatch(resultAction)
        }.launchIn(scope)

}


class LoggingSideEffect : SideEffect<AppState, Action> {
    override suspend fun invoke(state: AppState, action: Action): Action? {
        println("Action dispatched: $action")
        return null
    }
}

class FetchData1SideEffect : SideEffect<HomeState, HomeAction.FetchData1> {
    override suspend fun invoke(state: HomeState, action: HomeAction.FetchData1): Action {
        delay(1000)
        val result = "Fetched data 1"
        return HomeAction.DataFetched1(result)
    }
}

class FetchData2SideEffect : SideEffect<HomeState, HomeAction.FetchData2> {
    override suspend fun invoke(state: HomeState, action: HomeAction.FetchData2): Action {
        delay(1500)
        val result = "Fetched data 2"
        return HomeAction.DataFetched2(result)
    }
}


//////////// APP ////////////
data class AppState(
    val homeState: HomeState = HomeState(),
    val profileState: ProfileState = ProfileState(),
    val settingsState: SettingsState = SettingsState(),
    val currentScreen: Screen = Screen.HOME,
    val navigationParams: Map<String, Any> = emptyMap()
)

val appReducer = reducerForActionType<AppState, Action> { state, action ->
    when (action) {
        is HomeAction -> state.copy(
            homeState = homeReducer(state.homeState, action)
        )

        is ProfileAction -> state.copy(
            profileState = profileReducer(state.profileState, action)
        )

        is SettingsAction -> state.copy(
            settingsState = settingsReducer(state.settingsState, action)
        )

        is NavigationAction -> when (action) {
            is NavigationAction.NavigateToProfile -> state.copy(
                currentScreen = Screen.PROFILE,
                navigationParams = mapOf("userId" to action.userId)
            )

            is NavigationAction.NavigateToSettings -> state.copy(
                currentScreen = Screen.SETTINGS,
                navigationParams = mapOf("fromScreen" to action.fromScreen)
            )

            NavigationAction.NavigateBack -> when (state.currentScreen) {
                Screen.PROFILE -> state.copy(
                    currentScreen = Screen.HOME,
                    navigationParams = emptyMap()
                )

                Screen.SETTINGS -> state.copy(
                    currentScreen = Screen.PROFILE,
                    navigationParams = emptyMap()
                )

                Screen.HOME -> state
            }
        }


        ExitApp -> AppState()
    }
}

//////////// HOME ////////////
data class HomeState(
    val counter: Int = 0,
    val data1: String? = null,
    val data2: String? = null,
    val isLoading1: Boolean = false,
    val isLoading2: Boolean = false
)

sealed class HomeAction : Action {
    data object Increment : HomeAction()
    data object Decrement : HomeAction()
    data object FetchData1 : HomeAction()
    data object FetchData2 : HomeAction()
    data class DataFetched1(val data: String) : HomeAction()
    data class DataFetched2(val data: String) : HomeAction()
}

val homeReducer: ReducerForActionType<HomeState, HomeAction> = { state, action ->
    val ns = when (action) {
        is HomeAction.Increment -> state.copy(counter = state.counter + 1)
        is HomeAction.Decrement -> state.copy(counter = state.counter - 1)
        is HomeAction.FetchData1 -> state.copy(data1 = null, isLoading1 = true)
        is HomeAction.FetchData2 -> state.copy(data2 = null, isLoading2 = true)
        is HomeAction.DataFetched1 -> state.copy(data1 = action.data, isLoading1 = false)
        is HomeAction.DataFetched2 -> state.copy(data2 = action.data, isLoading2 = false)
    }
    ns
}

//////////// PROFILE ////////////
data class ProfileState(
    val username: String = "",
    val bio: String = "",
    val isLoading: Boolean = false
)

sealed class ProfileAction : Action {
    data class UpdateUsername(val username: String) : ProfileAction()
    data class UpdateBio(val bio: String) : ProfileAction()
    data object FetchProfile : ProfileAction()
    data class ProfileFetched(val username: String, val bio: String) : ProfileAction()
}

val profileReducer: ReducerForActionType<ProfileState, ProfileAction> = { state, action ->
    when (action) {
        is ProfileAction.UpdateUsername -> state.copy(username = action.username)
        is ProfileAction.UpdateBio -> state.copy(bio = action.bio)
        is ProfileAction.FetchProfile -> state.copy(isLoading = true)
        is ProfileAction.ProfileFetched -> state.copy(
            username = action.username,
            bio = action.bio,
            isLoading = false
        )
    }
}


@Composable
fun ProfileScreen() {
    val appStore: AppStore = koinInject()
    val appState by appStore.stateFlow.collectAsState()

    val userId = appState.navigationParams["userId"] as? String ?: "Unknown User"

    Column {
        Text("Profile Screen")
        Text("User ID: $userId")
        Text("Username: ${appState.profileState.username}")
        Text("Bio: ${appState.profileState.bio}")

        Button(onClick = {
            appStore.dispatchAction(NavigationAction.NavigateToSettings(fromScreen = Screen.PROFILE))
        }) {
            Text("Go to Settings")
        }

        Button(onClick = { appStore.dispatchAction(NavigationAction.NavigateBack) }) {
            Text("Back to Home")
        }
    }
}

//////////// SETTINGS ////////////
data class SettingsState(
    val isDarkMode: Boolean = false,
    val notificationsEnabled: Boolean = true
)

sealed class SettingsAction : Action {
    data object ToggleDarkMode : SettingsAction()
    data object ToggleNotifications : SettingsAction()
}

val settingsReducer: ReducerForActionType<SettingsState, SettingsAction> = { state, action ->
    when (action) {
        is SettingsAction.ToggleDarkMode -> state.copy(isDarkMode = !state.isDarkMode)
        is SettingsAction.ToggleNotifications -> state.copy(notificationsEnabled = !state.notificationsEnabled)
    }
}

@Composable
fun SettingsScreen() {
    val appStore: AppStore = koinInject()
    val appState by appStore.stateFlow.collectAsState()

    println(appState.currentScreen.toString())

    println(appState.navigationParams.entries.toString())

    val fromScreen = (appState.navigationParams["fromScreen"] as? Screen)?.name ?: "Unknown"

    Column {
        Text("Settings Screen")
        Text("Navigated from: $fromScreen")
        Text("Dark Mode: ${if (appState.settingsState.isDarkMode) "On" else "Off"}")
        Text("Notifications: ${if (appState.settingsState.notificationsEnabled) "Enabled" else "Disabled"}")

        Button(onClick = { appStore.dispatchAction(NavigationAction.NavigateBack) }) {
            Text("Back")
        }
    }
}

