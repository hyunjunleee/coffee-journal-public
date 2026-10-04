package com.coffeejournal.ui.extract

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.coffeejournal.ui.extract.compare.BrewCompareScreen
import com.coffeejournal.ui.extract.compare.BrewCompareViewModel
import com.coffeejournal.ui.extract.stats.StatsScreen
import com.coffeejournal.ui.extract.stats.StatsViewModel
import com.coffeejournal.ui.nav.Feature
import com.coffeejournal.ui.nav.Route
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Home tab (새로운 추출) plus the new-record chooser, the bean pantry, the 추출 비교 table and 통계. */
object ExtractFeature : Feature {
    override val module = module {
        viewModel { ExtractViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel { PantryViewModel(get()) }
        // the last get() is the destination's SavedStateHandle (typed input survives process death)
        viewModel { (itemId: String) -> PantryEditorViewModel(itemId.ifBlank { null }, get(), get()) }
        viewModel { (beanKey: String) -> BrewCompareViewModel(beanKey, get(), get()) }
        viewModel { StatsViewModel(get(), get()) }
        viewModel { NewRecordViewModel(get(), get(), get()) }
    }

    override fun NavGraphBuilder.routes(nav: NavHostController) {
        composable<Route.Pantry> { PantryScreen(nav) }
        composable<Route.PantryEditor> { back -> PantryEditorScreen(nav, back.toRoute<Route.PantryEditor>().itemId) }
        composable<Route.BrewCompare> { back -> BrewCompareScreen(nav, back.toRoute<Route.BrewCompare>().beanKey) }
        composable<Route.Stats> { StatsScreen(nav) }
        composable<Route.NewRecord> { NewRecordScreen(nav) }
    }
}
