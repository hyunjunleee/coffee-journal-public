package com.coffeejournal.ui.form

import com.coffeejournal.data.backup.BackupCodec
import com.coffeejournal.data.backup.BackupSnapshot
import com.coffeejournal.data.repo.SettingsRepository
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.domain.model.RecipeRef
import com.coffeejournal.domain.model.RecipeStep
import com.coffeejournal.domain.reference.GenericSteps
import com.coffeejournal.domain.rules.Dates
import com.coffeejournal.ui.nav.FormMode
import com.coffeejournal.ui.notify.MemorySettingsDao
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 기본 레시피: one of 내 레시피 that a new brew record starts with. */
class DefaultRecipeTest {
    private val now = Dates.toMillis(LocalDate(2026, 10, 4), 8, 0)
    private val steps = listOf(RecipeStep("0:00", "40", "30", "뜸"), RecipeStep("0:30", "110", "20", "2차"), RecipeStep("1:10", "100", "", "3차"))
    private val morning = MyRecipe(
        id = "r1", name = "아침 3단", dose = "15", water = "250", temp = "93", dripper = "V60", filter = "하리오 01", grind = "코만단테 22클릭",
        time = "", steps = steps, createdAt = now - 10,
    )
    private val plain = MyRecipe(id = "r2", name = "간단 1:16", dose = "16", water = "256", temp = "92", dripper = "오리가미", createdAt = now)

    private fun newBrew(lastGrind: String = "", lastWater: String = "") =
        FormMapper.newState(FormMode.EXTRACT, null, now, lastGrind = lastGrind, lastWaterType = lastWater)

    @Test fun aNewBrew_startsAsIfTheRecipeWasApplied() {
        val base = newBrew(lastGrind = "EK43 9", lastWater = "삼다수")
        val s = DefaultRecipe.startWith(base, morning)
        assertEquals(listOf("15", "250", "93", "V60", "하리오 01", "코만단테 22클릭"), listOf(s.dose, s.water, s.temp, s.dripper, s.filter, s.grind))
        assertEquals(steps, s.steps.map { it.toStep() })
        assertEquals(RecipeRef("아침 3단", steps), s.appliedRecipeRef)
        // the time the steps give, not the example's
        assertEquals("1:10", s.time)
        assertEquals("삼다수", s.waterType, "the last water stays: a recipe has none")
        assertNull(s.openLauncher)
        // it is the same as tapping 이 레시피 적용 on that form, apart from the time
        assertEquals(FormMapper.applyMyRecipe(base, morning), s)
    }

    @Test fun aRecipeWithoutGrindOrSteps_keepsTheLastGrind_andNoExampleTime() {
        val s = DefaultRecipe.startWith(newBrew(lastGrind = "EK43 9"), plain)
        assertEquals("EK43 9", s.grind)
        assertTrue(s.steps.isEmpty())
        assertEquals("", s.time, "the example steps' time is not the recipe's")
        assertEquals(RecipeRef("간단 1:16", emptyList()), s.appliedRecipeRef)
        assertEquals("2:00", DefaultRecipe.startWith(newBrew(), plain.copy(time = "2:00")).time)
    }

    @Test fun onlyANewBrew_takesIt() {
        assertTrue(DefaultRecipe.appliesTo(FormArgs(FormMode.EXTRACT)))
        assertFalse(DefaultRecipe.appliesTo(FormArgs(FormMode.EXTRACT, entryId = "e1")), "an edit keeps its own recipe")
        assertFalse(DefaultRecipe.appliesTo(FormArgs(FormMode.EXTRACT, againFrom = "e1")), "같은 커피 다시 brings the record's")
        assertFalse(DefaultRecipe.appliesTo(FormArgs(FormMode.CAFE)))
        assertFalse(DefaultRecipe.appliesTo(FormArgs(FormMode.CUPPING)))
    }

    @Test fun resolved_byItsId_andAGoneOneIsNone() {
        val all = listOf(morning, plain)
        assertEquals(morning, DefaultRecipe.resolve("r1", all))
        assertNull(DefaultRecipe.resolve("gone", all))
        assertNull(DefaultRecipe.resolve(null, all))
        assertNull(DefaultRecipe.resolve("", all))
    }

    @Test fun theDefault_leadsTheList_andSaysSo() {
        assertEquals(listOf("r2", "r1"), DefaultRecipe.ordered(listOf(morning, plain), null).map { it.id })
        assertEquals(listOf("r1", "r2"), DefaultRecipe.ordered(listOf(plain, morning), "r1").map { it.id })
        assertEquals("기본 · ★★★", DefaultRecipe.badge(morning.copy(rating = 3), isDefault = true))
        assertEquals("기본", DefaultRecipe.badge(morning, isDefault = true))
        assertEquals("", DefaultRecipe.badge(morning, isDefault = false))
        assertEquals("15g : 250g · 93°C · V60", DefaultRecipe.spec(morning))
        assertEquals("?g : ?g · ?°C · ", DefaultRecipe.spec(MyRecipe(id = "x", name = "x", createdAt = now)))
    }

    @Test fun theRecipesStepsAsTheyCame_areNotALogOfTheUsersOwn() {
        val s = DefaultRecipe.startWith(newBrew(), morning)
        assertFalse(FormMapper.hasOwnStepLog(s), "the timer replaces them without asking")
        val changed = s.copy(steps = s.steps.mapIndexed { i, st -> if (i == 0) st.copy(wait = "35") else st })
        assertTrue(FormMapper.hasOwnStepLog(changed), "a changed row is the user's own")
        assertTrue(FormMapper.hasOwnStepLog(s.copy(steps = s.steps.dropLast(1))))
        // the example a form without a recipe starts with, as before
        assertFalse(FormMapper.hasOwnStepLog(newBrew()))
        assertEquals(GenericSteps.example, newBrew().steps.map { it.toStep() })
    }

    @Test fun theStore_keepsTheId_asAJournalSetting_andForgetsADeletedRecipe() = runTest {
        val settings = SettingsRepository(MemorySettingsDao())
        val store = DefaultRecipeStore(settings)
        assertNull(store.id())
        store.set("r1")
        assertEquals("r1", store.id())
        assertEquals("r1", settings.get(DefaultRecipe.KEY))
        assertFalse(SettingsRepository.isDeviceKey(DefaultRecipe.KEY), "a backup carries it")
        store.clearIf("r2")
        assertEquals("r1", store.id(), "deleting another recipe leaves it")
        store.clearIf("r1")
        assertNull(store.id())
        assertNull(settings.get(DefaultRecipe.KEY))
        store.set("r2")
        store.set(null)
        assertNull(store.id())
    }

    @Test fun aBackup_carriesIt_withTheRecipes() {
        val codec = BackupCodec()
        val snapshot = BackupSnapshot(myRecipes = listOf(morning, plain), settings = mapOf(DefaultRecipe.KEY to "r1"))
        val decoded = codec.decode(codec.encode(snapshot))
        assertEquals("r1", decoded.settings[DefaultRecipe.KEY])
        assertEquals(morning, DefaultRecipe.resolve(decoded.settings[DefaultRecipe.KEY], decoded.myRecipes))
    }
}
