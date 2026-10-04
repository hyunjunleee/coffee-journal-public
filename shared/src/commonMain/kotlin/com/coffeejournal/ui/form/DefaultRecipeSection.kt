package com.coffeejournal.ui.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.coffeejournal.data.repo.MyRecipeRepository
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.ui.nav.Route
import com.coffeejournal.ui.theme.AppType
import com.coffeejournal.ui.theme.GhostButton
import com.coffeejournal.ui.theme.HairlineCard
import com.coffeejournal.ui.theme.HintText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** 설정's 기본 레시피: [recipe] is null when none is chosen (or it is gone); nothing is shown until [loaded]. */
data class DefaultRecipeUi(val loaded: Boolean = false, val recipe: MyRecipe? = null, val hasRecipes: Boolean = false)

/** The 기본 레시피 as 설정 shows it ([DefaultRecipe]). */
class DefaultRecipeViewModel(recipes: MyRecipeRepository, private val store: DefaultRecipeStore) : ViewModel() {
    val state: StateFlow<DefaultRecipeUi> = combine(store.observeId(), recipes.observeAll()) { id, list ->
        DefaultRecipeUi(loaded = true, recipe = DefaultRecipe.resolve(id, list), hasRecipes = list.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultRecipeUi())

    fun clear() { viewModelScope.launch { store.set(null) } }
}

/** 설정's 기본 레시피: the recipe new brews start with, and the way to 내 레시피 to choose or change it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DefaultRecipeSettingsSection(nav: NavHostController) {
    val vm = koinViewModel<DefaultRecipeViewModel>()
    val ui by vm.state.collectAsStateWithLifecycle()
    val pick = { nav.navigate(Route.MyRecipes(newRecipe = false)) }
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag("default-recipe-settings")) {
        if (!ui.loaded) return@Column
        val r = ui.recipe
        if (r == null && !ui.hasRecipes) {
            // nothing to pick yet: 내 레시피 opens with the new-recipe form
            HintText(DefaultRecipeTexts.NO_RECIPES)
            GhostButton(DefaultRecipeTexts.MAKE, small = true, onClick = { nav.navigate(Route.MyRecipes(newRecipe = true)) }, modifier = Modifier.padding(top = 8.dp))
        } else if (r == null) {
            HintText(DefaultRecipeTexts.NONE)
            GhostButton(DefaultRecipeTexts.PICK, small = true, onClick = pick, modifier = Modifier.padding(top = 8.dp))
        } else {
            HairlineCard {
                Text(r.name, style = AppType.cardTitle)
                Text(DefaultRecipe.spec(r), style = AppType.monoValue, modifier = Modifier.padding(top = 4.dp))
                Text(if (r.steps.isEmpty()) "단계 없음" else "단계 ${r.steps.size}개", style = AppType.faint, modifier = Modifier.padding(top = 4.dp))
            }
            HintText(DefaultRecipeTexts.ABOUT, Modifier.padding(top = 6.dp))
            // wraps at a large font size instead of squeezing the second button
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(DefaultRecipeTexts.CHANGE, small = true, onClick = pick)
                GhostButton(DefaultRecipeTexts.UNSET, small = true, onClick = vm::clear)
            }
        }
    }
}
