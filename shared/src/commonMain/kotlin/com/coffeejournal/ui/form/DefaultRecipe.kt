package com.coffeejournal.ui.form

import com.coffeejournal.data.repo.SettingsRepository
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.ui.nav.FormMode
import kotlinx.coroutines.flow.Flow

/**
 * 기본 레시피: one of 내 레시피 that every new brew record (직접 내린 커피) starts with, as if "이 레시피 적용" was
 * tapped. A record being edited, "같은 커피 다시" (the copied record's recipe), a café record and a cupping never take it,
 * and a draft left last time wins over it. Kept as the recipe's id under [KEY], a journal setting: a backup carries it.
 */
object DefaultRecipe {
    const val KEY = "default-recipe"

    /** The recipe [id] names, or null: none chosen, or that recipe is gone (deleted, or not in a restored backup). */
    fun resolve(id: String?, recipes: List<MyRecipe>): MyRecipe? =
        id?.takeIf { it.isNotBlank() }?.let { key -> recipes.firstOrNull { it.id == key } }

    /**
     * A new brew form starting with [r]: its recipe applied, the last grind kept when the recipe has none. The time the
     * example steps gave the new form is not kept: the recipe's own (or its steps') or none.
     */
    fun startWith(state: FormState, r: MyRecipe): FormState =
        FormMapper.applyMyRecipe(state.copy(time = ""), r).copy(grind = r.grind.ifBlank { state.grind })

    /** Whether the form [args] open takes the default recipe (a brew; "같은 커피 다시" brings its own). */
    fun appliesTo(args: FormArgs): Boolean =
        args.entryId == null && args.againFrom == null && args.mode == FormMode.EXTRACT

    /** "15g : 240g · 92°C · 오리가미": how a recipe card sums it up. */
    fun spec(r: MyRecipe): String = "${r.dose.ifBlank { "?" }}g : ${r.water.ifBlank { "?" }}g · ${r.temp.ifBlank { "?" }}°C · ${r.dripper}"

    /** A recipe card's right corner: "기본" for the default, then its stars. */
    fun badge(r: MyRecipe, isDefault: Boolean): String =
        listOfNotNull(DefaultRecipeTexts.BADGE.takeIf { isDefault }, "★".repeat(r.rating).takeIf { r.rating > 0 }).joinToString(" · ")

    /** The default first, then the newest first (the order 내 레시피 lists them in). */
    fun ordered(recipes: List<MyRecipe>, defaultId: String?): List<MyRecipe> =
        recipes.sortedWith(compareByDescending<MyRecipe> { it.id == defaultId }.thenByDescending { it.createdAt })
}

object DefaultRecipeTexts {
    const val BADGE = "기본"
    const val SET = "기본으로 지정"
    const val UNSET = "기본 해제"
    const val SAVE_AS_DEFAULT = "기본 레시피로 지정"
    const val SAVE_AS_DEFAULT_HINT = "새 기록(직접 내린 커피)이 이 레시피로 채워진 채 열려요."
    const val SECTION = "기본 레시피"
    const val NONE = "지정한 기본 레시피가 없어요. 내 레시피 중 하나를 기본으로 지정하면 새 기록(직접 내린 커피)이 그 레시피로 채워진 채 열려요."
    const val PICK = "내 레시피에서 고르기"
    const val CHANGE = "다른 레시피로 바꾸기"
    const val ABOUT_LIST = "\"기본으로 지정\"한 레시피는 새 기록(직접 내린 커피)을 열 때 미리 채워져요."
    const val ABOUT = "새 기록(직접 내린 커피)을 열면 이 레시피의 드리퍼·필터·분쇄도·원두량·물량·온도·붓기 단계가 채워져 있어요."

    /** The form's line while it holds the default recipe as it opened with it. */
    fun startedWith(name: String) = "⭐ 기본 레시피 \"$name\"로 채웠어요. 다른 레시피를 적용하거나 고쳐 써도 돼요."

    /** The chooser's 직접 내린 커피 line. */
    fun chooserHint(name: String) = "기본 레시피 \"$name\"로 시작"

    /** The detail's toast after saving as a recipe. */
    fun savedAsDefault(name: String) = "\"$name\" 레시피를 저장하고 기본 레시피로 지정했어요."
}

/** The default recipe's id in the settings table ([DefaultRecipe.KEY]); deleting that recipe clears it ([clearIf]). */
class DefaultRecipeStore(private val settings: SettingsRepository) {
    fun observeId(): Flow<String?> = settings.observe(DefaultRecipe.KEY)

    suspend fun id(): String? = settings.get(DefaultRecipe.KEY)?.takeIf { it.isNotBlank() }

    /** Makes [id] the default, or none with null. */
    suspend fun set(id: String?) {
        if (id.isNullOrBlank()) settings.delete(DefaultRecipe.KEY) else settings.put(DefaultRecipe.KEY, id)
    }

    /** A recipe [id] being deleted is no longer the default. */
    suspend fun clearIf(id: String) {
        if (id() == id) settings.delete(DefaultRecipe.KEY)
    }
}
