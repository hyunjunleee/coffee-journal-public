package com.coffeejournal.ui.form

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.coffeejournal.di.AppScope
import com.coffeejournal.ui.form.timer.BrewClock
import com.coffeejournal.ui.form.timer.BrewTimerArgs
import com.coffeejournal.ui.form.timer.BrewTimerScreen
import com.coffeejournal.ui.form.timer.BrewTimerViewModel
import com.coffeejournal.ui.form.timer.SystemBrewClock
import com.coffeejournal.ui.nav.Feature
import com.coffeejournal.ui.nav.Route
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Record form, entry detail, my recipes and the brew timer: Koin module (view models) and full-screen routes. */
object RecordFormFeature : Feature {
    override val module: Module = module {
        // one draft store for the app: its writes run on the app scope, so the last one lands after the form closed
        single { RecordDrafts(get(), get<AppScope>()) }
        // the form's folds, kept on this device like the drafts
        single { FormFoldStore(get(), get<AppScope>()) }
        // the fourth-to-last get() is the destination's SavedStateHandle, created by Koin from the view model's CreationExtras
        // which of 내 레시피 a new brew starts with: a journal setting
        single { DefaultRecipeStore(get()) }
        viewModel { (args: FormArgs) -> RecordFormViewModel(args, get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { (entryId: String) -> EntryDetailViewModel(entryId, get(), get(), get(), get(), get()) }
        viewModelOf(::MyRecipesViewModel)
        viewModelOf(::DefaultRecipeViewModel)
        single<BrewClock> { SystemBrewClock }
        // the last get() is the destination's SavedStateHandle: a running timer survives process death
        viewModel { (args: BrewTimerArgs) -> BrewTimerViewModel(args, get(), get()) }
    }

    override fun NavGraphBuilder.routes(nav: NavHostController) {
        composable<Route.RecordForm> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.RecordForm>()
            RecordFormScreen(nav, route.mode, route.entryId, route.cuppingType, results = backStackEntry.savedStateHandle, againFrom = route.againFrom)
        }
        composable<Route.EntryDetail> { backStackEntry ->
            EntryDetailScreen(nav, backStackEntry.toRoute<Route.EntryDetail>().entryId)
        }
        composable<Route.MyRecipes> { backStackEntry -> MyRecipesScreen(nav, backStackEntry.toRoute<Route.MyRecipes>().newRecipe) }
        composable<Route.BrewTimer> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.BrewTimer>()
            BrewTimerScreen(nav, route.recipe, route.hasLog)
        }
    }
}
