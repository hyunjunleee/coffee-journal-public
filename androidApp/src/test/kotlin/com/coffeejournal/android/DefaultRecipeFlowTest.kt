package com.coffeejournal.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coffeejournal.data.repo.MyRecipeRepository
import com.coffeejournal.data.repo.SettingsRepository
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.domain.model.RecipeStep
import com.coffeejournal.domain.rules.Dates
import com.coffeejournal.ui.extract.NewRecordTexts
import com.coffeejournal.ui.form.DefaultRecipeStore
import com.coffeejournal.ui.form.DefaultRecipeTexts
import com.coffeejournal.ui.form.DetailEvent
import com.coffeejournal.ui.form.RecordDraftTexts
import com.coffeejournal.ui.form.EntryDetailViewModel
import com.coffeejournal.ui.form.FormArgs
import com.coffeejournal.ui.form.RecordDrafts
import com.coffeejournal.ui.form.RecordFormViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * 기본 레시피: one of 내 레시피 chosen as the default (the form's ⭐ 내 레시피 cards, the 내 레시피 screen, 설정, or
 * "⭐ 내 레시피로 저장"); every new brew record starts with it, nothing else does, and a draft wins over it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class DefaultRecipeFlowTest : CoverageFlowBase() {

    private val home = hasText("+ 새 기록 추가")
    private val line = hasTestTag("default-recipe-line")
    private val steps = listOf(RecipeStep("0:00", "40", "30", "뜸"), RecipeStep("0:30", "110", "20", "2차"), RecipeStep("1:10", "100", "", "3차"))
    // 아침 3단 is the newer one: the first card of a list (newest first)
    private val base = Dates.nowMillis()
    private val morning = MyRecipe(id = "r1", name = "아침 3단", dose = "17", water = "272", temp = "93", dripper = "V60", filter = "하리오 01", steps = steps, createdAt = base)
    private val plain = MyRecipe(id = "r2", name = "간단 1:16", dose = "16", water = "256", temp = "91", dripper = "칼리타 웨이브", createdAt = base - 60_000)

    private val store get() = koinGet<DefaultRecipeStore>()
    private fun defaultId(): String? = runBlocking { store.id() }
    private fun recipes(vararg r: MyRecipe) = runBlocking { koinGet<MyRecipeRepository>().upsertAll(r.toList()) }
    private fun setDefault(id: String?) = runBlocking { store.set(id) }

    private fun backHome() {
        back()
        waitUntil("home") { has(home) }
    }

    @Test
    fun chosenOnTheFormsCards_theNextBrewStartsWithIt_andThatIsNoInput() {
        recipes(morning, plain)
        launchApp()
        openNewRecord()
        waitForText("레시피로 시작")
        clickText("⭐ 내 레시피")
        waitForText(morning.name)
        assertFalse("no default yet", has(button(DefaultRecipeTexts.UNSET)))
        // newest first: 아침 3단, 간단 1:16; choosing one moves no card, only the mark
        val unset = button(DefaultRecipeTexts.UNSET)
        clickText(DefaultRecipeTexts.SET, index = 0)
        waitUntil("아침 3단 is the default, on its own card") { defaultId() == morning.id && has(unset) && top(unset) < top(hasText(plain.name)) }
        assertTrue("the list keeps its order", top(hasText(morning.name)) < top(hasText(plain.name)))
        clickText(DefaultRecipeTexts.SET, index = 0)
        waitUntil("간단 1:16 is the default") { defaultId() == plain.id && count(unset) == 1 && top(unset) > top(hasText(plain.name)) }
        assertTrue(top(hasText(morning.name)) < top(hasText(plain.name)))
        clickText(DefaultRecipeTexts.SET, index = 0)
        waitUntil("아침 3단 again") { defaultId() == morning.id && count(unset) == 1 && top(unset) < top(hasText(plain.name)) }
        assertFalse("this form is left as it was", has(field("17")))
        // choosing it is no input: back leaves at once
        backHome()

        openNewRecord()
        waitFor(field("17"))
        listOf("272", "93", "V60", "하리오 01").forEach { assertTrue("$it filled", has(field(it))) }
        waitFor(line)
        assertTrue(has(hasText(DefaultRecipeTexts.startedWith(morning.name))))
        // another recipe applied: the form no longer holds the default, and that is input
        clickText("🏆 챔피언 레시피")
        clickText("이 비율 적용 →", index = 0)
        waitGone(line)
        back()
        clickText(RecordDraftTexts.DISCARD)
        waitUntil("home") { has(home) }

        openNewRecord()
        waitFor(field("17"))
        typeInto(namePlaceholder, "기본 레시피 원두")
        saveForm("기본 레시피 원두")
        val saved = entries().single { it.name == "기본 레시피 원두" }
        assertEquals(listOf("17", "272", "93", "V60", "하리오 01"), listOf(saved.dose, saved.water, saved.temp, saved.dripper, saved.filter))
        assertEquals(steps, saved.steps)
        assertEquals(morning.name, saved.recipeRef?.name)
    }

    @Test
    fun aCafeRecord_anEdit_andTheSameCoffeeAgain_keepTheirOwn() {
        SampleData.seed()
        recipes(morning)
        setDefault(morning.id)
        val e2 = entry("e2")!!
        launchApp()

        openNewRecord(NewRecordTexts.CAFE)
        waitForText("카페 이름")
        assertFalse(has(line))
        typeInto("예: OO카페 (서울 성수동)", "모모스 영도")
        typeInto("예: 콜롬비아 라 플라타 게이샤 워시드", "기본 레시피 없는 카페 커피")
        saveForm("기본 레시피 없는 카페 커피")
        val cafe = entries().single { it.name == "기본 레시피 없는 카페 커피" }
        assertEquals(listOf("", "", "", ""), listOf(cafe.dose, cafe.water, cafe.temp, cafe.dripper))
        assertNull(cafe.recipeRef)
        backHome()

        // 같은 커피 다시: the copied brew's recipe
        clickText("+ 새 기록 추가")
        waitForText("직접 내림 · 2026.09.23")
        clickText(e2.name)
        waitFor(hasTestTag("again-banner"))
        waitFor(field(e2.temp))
        assertFalse(has(line))
        assertFalse(has(field("272")))
        backHome()

        // an edit of a brew keeps that record's recipe
        val before = entry("e1")!!
        openViaSearch("워카", index = 1)
        clickText("수정")
        waitForText("기록 수정")
        assertFalse(has(line))
        assertFalse(has(field("272")))
        clickText("수정 저장")
        waitUntil("back on detail") { !has(hasText("기록 수정")) && has(button("수정")) }
        assertEquals(before, entry("e1"))
    }

    @Test
    fun theChooser_andSettings_showIt_unsetEndsIt_andSettingsLeadsToChoosingOne() {
        recipes(morning, plain)
        setDefault(morning.id)
        launchApp()
        clickText("+ 새 기록 추가")
        waitForText("⭐ " + DefaultRecipeTexts.chooserHint(morning.name))
        back()
        waitUntil("home") { has(home) }

        tap(hasTestTag("open-settings"))
        waitFor(hasTestTag("default-recipe-settings"))
        waitForText(morning.name)
        assertTrue(has(hasText("17g : 272g · 93°C · V60")))
        clickText(DefaultRecipeTexts.UNSET)
        waitUntil("unset") { defaultId() == null }
        waitForText(DefaultRecipeTexts.NONE)
        back()
        waitUntil("home") { has(home) }
        clickText("+ 새 기록 추가")
        waitForText(NewRecordTexts.COFFEE)
        assertFalse("no default, no hint", has(hasText(DefaultRecipeTexts.chooserHint(morning.name), substring = true)))
        back()
        waitUntil("home") { has(home) }
        tap(hasTestTag("open-settings"))
        waitForText(DefaultRecipeTexts.NONE)

        // 내 레시피 from 설정: the list, the new-recipe form closed
        clickText(DefaultRecipeTexts.PICK)
        waitForText(plain.name)
        assertFalse("the new-recipe form starts closed", has(field("예: 밝은 산미용 3단 푸어")))
        clickText(DefaultRecipeTexts.SET, index = 0)
        waitUntil("아침 3단 chosen") { defaultId() == morning.id }
        waitFor(button(DefaultRecipeTexts.UNSET))
        back()
        waitFor(hasTestTag("default-recipe-settings"))
        waitForText(morning.name)
        back()
        waitUntil("home") { has(home) }
        clickText("+ 새 기록 추가")
        waitForText("⭐ " + DefaultRecipeTexts.chooserHint(morning.name))
    }

    @Test
    fun deletingTheDefaultRecipe_leavesNone_andTheNextBrewIsPlain() {
        recipes(morning)
        setDefault(morning.id)
        launchApp()
        openNewRecord()
        waitFor(field("17"))
        clickText("⭐ 내 레시피")
        waitFor(button(DefaultRecipeTexts.UNSET))
        clickText("삭제")
        clickNode(dialogButton("삭제"))
        waitUntil("deleted and no longer the default") { defaultId() == null && runBlocking { koinGet<MyRecipeRepository>().getAll() }.isEmpty() }
        // what this form opened with stays
        assertTrue(has(field("17")))
        backHome()
        clickText("+ 새 기록 추가")
        waitForText(NewRecordTexts.COFFEE)
        assertFalse(has(hasText(DefaultRecipeTexts.chooserHint(morning.name), substring = true)))
        clickText(NewRecordTexts.BREW)
        waitForText("레시피로 시작")
        assertFalse(has(field("17")))
        assertFalse(has(line))
        backHome()
        // 설정: no recipe left, so it offers to make one
        tap(hasTestTag("open-settings"))
        waitForText(DefaultRecipeTexts.NO_RECIPES)
        clickText(DefaultRecipeTexts.MAKE)
        waitFor(field("예: 밝은 산미용 3단 푸어"))
    }

    @Test
    fun deletingTheDefault_onTheMyRecipesScreen_leavesNone() {
        recipes(morning, plain)
        setDefault(morning.id)
        launchApp()
        tap(hasTestTag("open-settings"))
        waitForText(morning.name)
        clickText(DefaultRecipeTexts.CHANGE)
        waitFor(button(DefaultRecipeTexts.UNSET))
        // 아침 3단 is the newest: the first card
        clickText("삭제", index = 0)
        clickNode(dialogButton("삭제"))
        waitUntil("deleted and no longer the default") { defaultId() == null && runBlocking { koinGet<MyRecipeRepository>().getAll() }.map { it.id } == listOf(plain.id) }
        back()
        waitForText(DefaultRecipeTexts.NONE)
        assertFalse(has(hasText(morning.name)))
    }

    // ───────────────────────── view models: the save dialog, drafts, process death ─────────────────────────

    private val vmStore = ViewModelStore()

    private inline fun <reified V : ViewModel> vm(key: String, crossinline create: () -> V): V =
        ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
        })[key, V::class.java]

    private fun formVm(key: String, handle: SavedStateHandle = SavedStateHandle()) = vm(key) {
        RecordFormViewModel(FormArgs(), koinGet(), koinGet(), koinGet(), koinGet(), koinGet(), koinGet(), handle, koinGet<RecordDrafts>(), defaultRecipe = koinGet())
    }

    private fun pump(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun savedAsMyRecipe_withTheSwitch_itBecomesTheDefault() {
        SampleData.seed()
        val detail = vm("detail") { EntryDetailViewModel("e1", koinGet(), koinGet(), koinGet(), koinGet(), koinGet()) }
        // the screen's collection: the record is read off the main thread while the state is watched
        val saved = mutableListOf<DetailEvent.RecipeSaved>()
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { detail.state.collect {} }
        scope.launch { detail.defaultRecipeName.collect {} }
        scope.launch { detail.events.filterIsInstance<DetailEvent.RecipeSaved>().collect { saved += it } }
        pump("record read") { detail.state.value.entry != null }
        detail.saveAsMyRecipe("워카 기본", asDefault = false)
        // the event comes after everything the save does
        pump("saved") { saved.size == 1 }
        assertFalse(saved[0].asDefault)
        assertNull("without the switch it is just a recipe", defaultId())
        detail.saveAsMyRecipe("워카 기본 2", asDefault = true)
        pump("saved as the default") { saved.size == 2 }
        assertTrue(saved[1].asDefault)
        val made = runBlocking { koinGet<MyRecipeRepository>().getById(defaultId()!!) }!!
        assertEquals("워카 기본 2", made.name)
        assertEquals(entry("e1")!!.steps, made.steps)
        pump("the switch's hint names it") { detail.defaultRecipeName.value == "워카 기본 2" }
        scope.cancel()
    }

    @Test
    fun aDraft_winsOverTheDefault_andStartingOverTakesTheDefaultOfNow() {
        recipes(morning, plain)
        setDefault(morning.id)
        val first = formVm("first")
        pump("loaded") { first.loaded.value }
        assertEquals("17", first.state.value.dose)
        assertFalse("the default is how the form opened", first.hasChanges())
        first.onNameTyped("초안 원두")
        first.keepDraft()
        pump("draft kept") { runBlocking { koinGet<SettingsRepository>().getAll() }.keys.any { it.startsWith("device.draft.record.new") } }

        setDefault(plain.id)
        val second = formVm("second")
        pump("loaded") { second.loaded.value }
        assertEquals("초안 원두", second.state.value.name)
        assertEquals("the draft's recipe", "17", second.state.value.dose)
        second.startOver()
        assertEquals("", second.state.value.name)
        assertEquals("today's default", "16", second.state.value.dose)

        // after process death the saved form wins, and what it opened with stays the measure
        val handle = SavedStateHandle()
        val third = formVm("third", handle)
        pump("loaded") { third.loaded.value }
        third.onNameTyped("복원될 원두")
        pump("saved state") { handle.keys().isNotEmpty() }
        setDefault(null)
        val restored = formVm("restored", SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        pump("loaded") { restored.loaded.value }
        assertEquals("복원될 원두", restored.state.value.name)
        assertEquals("16", restored.state.value.dose)
        assertTrue(restored.hasChanges())
    }


    @Test
    fun theTimer_replacesTheDefaultsRowsFreely_butAsksForAnyOtherLog() {
        SampleData.seed()
        recipes(morning)
        setDefault(morning.id)
        val fresh = formVm("fresh")
        pump("loaded") { fresh.loaded.value }
        assertEquals(steps, fresh.state.value.steps.map { it.toStep() })
        assertFalse("the rows the form opened with", fresh.timerRoute().hasLog)
        fresh.update { s -> s.copy(steps = s.steps.mapIndexed { i, st -> if (i == 0) st.copy(wait = "35") else st }) }
        assertTrue("a changed row is the user's own", fresh.timerRoute().hasLog)

        // an edit: the record's own log, though it is its recipe's rows
        val e1 = entry("e1")!!
        assertEquals(e1.recipeRef?.steps, e1.steps)
        val edit = vm("edit") {
            RecordFormViewModel(FormArgs(entryId = "e1"), koinGet(), koinGet(), koinGet(), koinGet(), koinGet(), koinGet(), SavedStateHandle(), koinGet<RecordDrafts>(), defaultRecipe = koinGet())
        }
        pump("loaded") { edit.loaded.value }
        assertTrue(edit.timerRoute().hasLog)
        assertNull(edit.startedWithDefault(edit.state.value, morning.id))
    }
}
