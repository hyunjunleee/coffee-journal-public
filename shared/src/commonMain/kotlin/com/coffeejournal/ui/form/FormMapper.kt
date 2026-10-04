package com.coffeejournal.ui.form

import com.coffeejournal.data.repo.miscNameKey
import com.coffeejournal.domain.model.BeanMode
import com.coffeejournal.domain.model.BlendComponent
import com.coffeejournal.domain.model.Category
import com.coffeejournal.domain.model.CuppingBean
import com.coffeejournal.domain.model.CuppingType
import com.coffeejournal.domain.model.Entry
import com.coffeejournal.domain.model.MiscType
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.domain.model.PackageType
import com.coffeejournal.domain.model.RecipeRef
import com.coffeejournal.domain.reference.CafeRecipes
import com.coffeejournal.domain.reference.Champions
import com.coffeejournal.domain.reference.GenericSteps
import com.coffeejournal.domain.reference.Processes
import com.coffeejournal.domain.rules.Altitude
import com.coffeejournal.domain.rules.BeanNames
import com.coffeejournal.domain.rules.BlendBeans
import com.coffeejournal.domain.rules.CuppingTypes
import com.coffeejournal.domain.rules.CvaAssessment
import com.coffeejournal.domain.rules.CvaScoring
import com.coffeejournal.domain.rules.Dates
import com.coffeejournal.domain.rules.Ids
import com.coffeejournal.domain.rules.NoteCanon
import com.coffeejournal.domain.rules.Numbers
import com.coffeejournal.domain.rules.Packages
import com.coffeejournal.domain.rules.Prices
import com.coffeejournal.domain.rules.RecipeSteps
import com.coffeejournal.domain.rules.OriginKeep
import com.coffeejournal.domain.rules.RegionText
import com.coffeejournal.domain.rules.ScaScoring
import com.coffeejournal.domain.rules.VarietyText
import com.coffeejournal.ui.nav.FormMode

/**
 * Pure conversions between [FormState] and [Entry], ported from the web save-entry / editEntry handlers.
 * No I/O here so every rule is unit-testable.
 */
internal object FormMapper {
    const val PROCESS_OTHER = "기타"
    const val DEFAULT_BAG_WEIGHT = "100"

    // ---------- opening ----------

    fun newState(
        mode: String,
        cuppingType: String?,
        now: Long,
        lastGrind: String = "",
        lastWaterType: String = "",
        draftId: String = Ids.newId(now),
    ): FormState {
        val category = when (mode) {
            FormMode.CAFE -> Category.CAFE
            FormMode.CUPPING -> Category.CUPPING
            else -> Category.BEAN
        }
        val brewLike = mode != FormMode.CAFE
        return FormState(
            mode = mode,
            draftId = draftId,
            category = category,
            createdAt = now,
            cuppingType = cuppingType?.takeIf { it in CuppingType.all } ?: CuppingType.PUBLIC,
            bagWeight = DEFAULT_BAG_WEIGHT,
            grind = if (brewLike) lastGrind else "",
            waterType = if (brewLike) lastWaterType else "",
            steps = if (brewLike) GenericSteps.example.map(StepForm::from) else emptyList(),
            attributes = ScaScoring.defaultAttributes(),
        ).let(::withStepsTime)
    }

    fun fromEntry(entry: Entry, mode: String): FormState {
        val isCupping = entry.isCupping
        val (seg, sub, other) = splitProcess(entry.process, entry.processOther)
        val beanMode = entry.beanMode.ifBlank { if (entry.blendComponents.isNotEmpty()) BeanMode.CUSTOM_BLEND else BeanMode.SINGLE }
        val rows = if (beanMode == BeanMode.CUSTOM_BLEND) entry.blendComponents.map { BlendRowForm(it.name, it.grams) } else emptyList()
        val cafeBlend = beanMode == BeanMode.COMMERCIAL_BLEND && !isCupping
        val cafeRecipe = entry.isCafe && hasRecipe(entry)
        return FormState(
            mode = mode,
            editingId = entry.id,
            category = entry.category.ifBlank { Category.BEAN },
            packageType = Packages.entryPackageType(entry),
            createdAt = entry.createdAt,
            cuppingType = if (isCupping) CuppingTypes.effective(entry) else entry.cuppingType.ifBlank { CuppingType.PUBLIC },
            cuppingPlace = entry.cuppingPlace,
            cuppingBeans = entry.cuppingBeans.map(::cuppingBeanForm).ifEmpty { listOf(CuppingBeanForm()) },
            cuppingNotes = if (isCupping) entry.notes else "",
            beanMode = beanMode,
            blendRows = if (beanMode == BeanMode.CUSTOM_BLEND && rows.isEmpty()) listOf(BlendRowForm(), BlendRowForm()) else rows,
            // a café blend opens with a block per bean; one recorded before its beans could be entered gets an empty second
            blendBeans = if (cafeBlend) entry.blendComponents.drop(1).map(::beanForm).ifEmpty { listOf(BeanForm()) } else emptyList(),
            firstBeanPercent = if (cafeBlend) entry.blendComponents.firstOrNull()?.percent ?: "" else "",
            cafeName = entry.cafeName,
            name = entry.name,
            price = Prices.formatInput(entry.price),
            roastery = entry.roastery,
            selection = entry.selection,
            country = entry.country,
            region = RegionText.split(entry.region).first,
            subRegion = RegionText.split(entry.region).second,
            farmProducer = entry.farmProducer,
            washingStation = entry.washingStation,
            altitude = Altitude.forField(entry.altitude),
            variety = VarietyText.split(entry.variety).first,
            heirloomNumbers = VarietyText.split(entry.variety).second,
            loaded = LoadedOrigin(entry.region, entry.altitude, entry.variety),
            moisture = entry.moisture,
            density = entry.density,
            score = entry.score,
            process = seg,
            processSub = sub,
            processOther = other,
            roast = entry.roast,
            bagWeight = entry.bagWeight,
            arrival = entry.arrival,
            roastDate = Dates.roastDateWithYear(entry.roastDate, entry.createdAt),
            expectedNotes = NoteCanon.parseChips(entry.expectedNotes),
            roasterDesc = entry.roasterDesc,
            bagPhotos = listOf(PhotoSlot(entry.bagPhotos.getOrNull(0)), PhotoSlot(entry.bagPhotos.getOrNull(1))),
            dripper = entry.dripper,
            filter = entry.filter,
            grind = entry.grind,
            dose = entry.dose,
            water = entry.water,
            temp = entry.temp,
            time = entry.time,
            waterType = entry.waterType,
            steps = entry.steps.map(StepForm::from),
            appliedRecipeRef = entry.recipeRef,
            cafeRecipeOpen = cafeRecipe,
            cafeRecipeUsed = cafeRecipe,
            // one form per tasting: the SCA 2004 attributes and a CVA assessment (its `cva.` keys) are kept apart
            scoreForm = if (CvaScoring.present(entry.attributes, entry.attributeNotes)) ScoreForm.CVA else ScoreForm.SCA2004,
            attributes = FormNumbers.finiteAttributes(entry.attributes).filterKeys { !CvaScoring.isCvaKey(it) }
                .let { a -> if (a.any { it.value > 0 }) a else ScaScoring.defaultAttributes() },
            attributeNotes = entry.attributeNotes.filterKeys { !CvaScoring.isCvaKey(it) },
            cva = CvaScoring.fromMaps(entry.attributes, entry.attributeNotes),
            actualNotes = NoteCanon.parseChips(entry.actualNotes),
            notes = if (isCupping) "" else entry.notes,
        )
    }

    /**
     * Web editEntry: when an earlier (non-cupping) record of the same bean exists, the bag info and bag photos belong
     * to that first registration, so the form shows the lock banner and hides the bag-photo slots.
     */
    fun hasEarlierSameBean(entry: Entry, all: List<Entry>): Boolean {
        val key = BeanNames.coreBeanName(entry.name)
        if (key.isBlank()) return false
        return all.any { it.id != entry.id && !it.isCupping && it.createdAt < entry.createdAt && BeanNames.coreBeanName(it.name) == key }
    }

    /**
     * "같은 커피 다시 기록": a new record of the coffee [entry] was, of the same kind, dated [now] — the bean and what is
     * known of it (a blend's beans too), the recipe of a brew, the café, its price and the recipe it told, a cupping's
     * kind, place and beans with their info — with nothing of that time's tasting: notes, scores, ranks, memos and
     * photos start empty (a photo file belongs to one record). A brew or café bean shows as the repeat of a known one.
     */
    fun again(entry: Entry, now: Long, draftId: String = Ids.newId(now)): FormState {
        val mode = EntryDisplay.formModeFor(entry.category)
        val blank = newState(mode, null, now, draftId = draftId)
        val source = fromEntry(entry, mode)
        return source.copy(
            editingId = null,
            draftId = draftId,
            createdAt = now,
            bagPhotos = blank.bagPhotos,
            attributes = blank.attributes,
            cva = blank.cva,
            attributeNotes = blank.attributeNotes,
            actualNotes = emptyList(),
            notes = "",
            cuppingNotes = "",
            cuppingBeans = source.cuppingBeans.map { b ->
                b.copy(
                    id = "", actualNotes = emptyList(), evaluation = emptyMap(), evaluationScores = emptyMap(),
                    cva = CvaAssessment(), rank = "", memo = "",
                )
            },
            repeatBean = !entry.isCupping,
            againFrom = againLabel(entry),
        )
    }

    /** "2026.09.18 · FELT 청계천": the day of [entry] and where it was, for the banner of a form filled from it. */
    fun againLabel(entry: Entry): String {
        val place = when {
            entry.isCafe -> entry.cafeName
            entry.isCupping && CuppingTypes.effective(entry) != CuppingType.HOME -> entry.cuppingPlace
            else -> ""
        }
        return listOf(Dates.ymdPadded(entry.createdAt), place.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    // ---------- saving ----------

    /** Text still sitting in a chip input box is committed before validation / saving (web save-entry). */
    fun commitPendingChips(state: FormState): FormState = state.copy(
        expectedNotes = if (state.expectedInput.isNotBlank()) NoteCanon.addChips(state.expectedNotes, state.expectedInput) else state.expectedNotes,
        expectedInput = "",
        actualNotes = if (state.actualInput.isNotBlank()) NoteCanon.addChips(state.actualNotes, state.actualInput) else state.actualNotes,
        actualInput = "",
        cuppingBeans = state.cuppingBeans.map { b ->
            b.copy(
                expectedNotes = if (b.expectedInput.isNotBlank()) NoteCanon.addChips(b.expectedNotes, b.expectedInput) else b.expectedNotes,
                expectedInput = "",
                actualNotes = if (b.actualInput.isNotBlank()) NoteCanon.addChips(b.actualNotes, b.actualInput) else b.actualNotes,
                actualInput = "",
            )
        },
    )

    fun validate(state: FormState): FormError? {
        if (state.isCupping) {
            if (state.cuppingBeans.none { it.name.isNotBlank() }) return FormError(FormField.CUPPING_BEAN_NAME, "원두 이름을 하나 이상 입력해 주세요.")
            return null
        }
        if (state.isCustomBlend && state.blendRows.count { it.name.isNotBlank() } < 2) {
            return FormError(FormField.BLEND_ROWS, "직접 블렌드는 섞은 원두를 2개 이상 적어 주세요.")
        }
        if (state.name.isBlank() && !state.isCustomBlend) return FormError(FormField.NAME, "원두 이름을 입력해 주세요.")
        return null
    }

    /** Web save-entry: builds the record. [bagPhotos] are the final stored file names (already saved). */
    fun toEntry(state: FormState, id: String, existing: Entry?, bagPhotos: List<String>, now: Long): Entry {
        val s = commitPendingChips(state)
        val isCupping = s.isCupping
        val isCafe = s.isCafe
        val isBrew = s.isBrew
        val beanMode = s.effectiveBeanMode
        val blendComponents = when (beanMode) {
            BeanMode.CUSTOM_BLEND -> s.blendRows.filter { it.name.isNotBlank() }.map { BlendComponent(it.name.trim(), FormNumbers.finiteText(it.grams.trim())) }
            BeanMode.COMMERCIAL_BLEND -> cafeBlendComponents(s)
            else -> emptyList()
        }
        val recipe = s.savesRecipe
        val cuppingBeans = if (isCupping) s.cuppingBeans.filter { it.name.isNotBlank() }.map(::cuppingBeanModel) else existing?.cuppingBeans ?: emptyList()
        val name = if (isCupping) {
            s.cuppingPlace.trim().ifBlank { cuppingBeans.firstOrNull()?.name ?: "" }
        } else {
            s.name.trim().ifBlank { if (beanMode == BeanMode.CUSTOM_BLEND) customBlendName(blendComponents) else "" }
        }
        val dose = if (beanMode == BeanMode.CUSTOM_BLEND) {
            val sum = blendComponents.sumOf { FormNumbers.finiteOrNull(it.grams) ?: 0.0 }
            if (sum > 0) Prices.trimNumber(sum) else FormNumbers.finiteText(s.dose.trim())
        } else FormNumbers.finiteText(s.dose.trim())
        return Entry(
            id = id,
            createdAt = s.createdAt.takeIf { it > 0 } ?: existing?.createdAt ?: now,
            category = s.category.ifBlank { Category.BEAN },
            beanMode = beanMode,
            blendComponents = blendComponents,
            name = name,
            country = s.country.trim(),
            region = OriginKeep.region(s.loaded.region, s.region, s.subRegion),
            altitude = OriginKeep.altitude(s.loaded.altitude, s.altitude),
            variety = OriginKeep.variety(s.loaded.variety, s.variety, s.heirloomNumbers),
            farmProducer = s.farmProducer.trim(),
            roastery = s.roastery.trim(),
            selection = s.selection.trim(),
            washingStation = s.washingStation.trim(),
            process = processValue(s.process, s.processSub),
            processOther = if (s.process == PROCESS_OTHER) s.processOther.trim() else "",
            packageType = if (isBrew) s.packageType.ifBlank { PackageType.STANDARD } else PackageType.STANDARD,
            moisture = s.moisture.trim(),
            density = s.density.trim(),
            score = s.score.trim(),
            arrival = s.arrival.trim(),
            roastDate = s.roastDate.trim(),
            roasterDesc = s.roasterDesc.trim(),
            roast = s.roast,
            // the cupping form never shows the bag fields (the fresh form still carries the 100 g default)
            bagWeight = if (isCupping) "" else FormNumbers.finiteText(s.bagWeight.trim()),
            price = if (isCupping) "" else Prices.normalize(s.price),
            cafeName = if (isCafe) s.cafeName.trim() else existing?.cafeName ?: "",
            expectedNotes = NoteCanon.joinChips(s.expectedNotes),
            actualNotes = NoteCanon.joinChips(s.actualNotes),
            // Recipe fields are saved only where the form shows them: always for 원두, for 카페 once its folded recipe
            // part was opened, never for 커핑. Hidden ones would otherwise store invented values, such as the 2:10 총
            // 추출시간 computed from the example steps every fresh brew form is seeded with.
            dripper = if (recipe) s.dripper.trim() else "",
            filter = if (recipe) s.filter.trim() else "",
            dose = if (recipe) dose else "",
            water = if (recipe) FormNumbers.finiteText(s.water.trim()) else "",
            temp = if (recipe) FormNumbers.finiteText(s.temp.trim()) else "",
            grind = if (recipe) s.grind.trim() else "",
            waterType = if (recipe) s.waterType.trim() else "",
            time = if (recipe) FormNumbers.safeTime(s.time.trim()) else "",
            notes = if (isCupping) s.cuppingNotes.trim() else s.notes.trim(),
            cuppingType = if (isCupping) s.cuppingType.ifBlank { CuppingType.PUBLIC } else existing?.cuppingType ?: "",
            cuppingPlace = if (isCupping) s.cuppingPlace.trim() else existing?.cuppingPlace ?: "",
            cuppingBeans = cuppingBeans,
            steps = if (recipe) s.steps.map { it.toStep() }.filter { !it.isEmpty } else emptyList(),
            recipeRef = if (recipe) s.appliedRecipeRef else null,
            // only the chosen form is saved (a CVA tasting has no 2004 score and the other way round)
            attributes = when {
                isCupping -> existing?.attributes ?: emptyMap()
                s.scoreForm == ScoreForm.CVA -> CvaScoring.toScores(s.cva)
                else -> s.attributes.filter { (k, v) -> !CvaScoring.isCvaKey(k) && v > 0 }
            },
            attributeNotes = when {
                isCupping -> existing?.attributeNotes ?: emptyMap()
                s.scoreForm == ScoreForm.CVA -> CvaScoring.toTexts(s.cva)
                else -> s.attributeNotes.filterKeys { !CvaScoring.isCvaKey(it) }.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
            },
            tags = existing?.tags ?: emptyList(),
            bagPhotos = bagPhotos,
            groundsPhoto = existing?.groundsPhoto,
            legacyExtra = existing?.legacyExtra,
        )
    }

    /** "허니" + "더블 퍼멘티드" → "허니(더블 퍼멘티드)"; anything else is stored as the segment value. */
    fun processValue(seg: String, sub: String): String {
        val s = sub.trim()
        return if (seg in Processes.mainSegments && s.isNotEmpty()) "$seg($s)" else seg
    }

    /** Stored process → (segment, sub type, other text). Unknown free text lands in 기타 so it is not lost. */
    fun splitProcess(process: String, processOther: String): Triple<String, String, String> {
        val parsed = BeanNames.parseFarmProducer(process)
        return when {
            parsed.farm in Processes.mainSegments -> Triple(parsed.farm, parsed.producer, "")
            process == PROCESS_OTHER -> Triple(PROCESS_OTHER, "", processOther)
            process.isNotBlank() -> Triple(PROCESS_OTHER, "", process)
            else -> Triple("", "", processOther)
        }
    }

    fun customBlendName(components: List<BlendComponent>): String = components.joinToString(" + ") { it.name }

    // ---------- café blend beans ----------

    /**
     * A café blend's beans as saved: bean 1 (the record's own fields, repeated with its share) and every later block
     * that has anything in it. An empty later 로스터리 / 로스팅 정도 / 로스팅 날짜 is saved empty, meaning bean 1's. With
     * no later bean and no share there is nothing to add to the record's fields.
     */
    fun cafeBlendComponents(s: FormState): List<BlendComponent> {
        val others = s.blendBeans.filter { !it.isBlank }
        if (others.isEmpty() && s.firstBeanPercent.isBlank()) return emptyList()
        return (listOf(s.bean(0)) + others).map(::beanComponent)
    }

    fun beanComponent(b: BeanForm): BlendComponent = BlendComponent(
        percent = Numbers.parse(b.percent)?.let(Prices::trimNumber) ?: "",
        roastery = b.roastery.trim(), selection = b.selection.trim(), country = b.country.trim(), region = OriginKeep.region(b.loaded.region, b.region, b.subRegion),
        farmProducer = b.farmProducer.trim(), washingStation = b.washingStation.trim(), altitude = OriginKeep.altitude(b.loaded.altitude, b.altitude),
        variety = OriginKeep.variety(b.loaded.variety, b.variety, b.heirloomNumbers), moisture = b.moisture.trim(), density = b.density.trim(), score = b.score.trim(),
        process = processValue(b.process, b.processSub), processOther = if (b.process == PROCESS_OTHER) b.processOther.trim() else "",
        roast = b.roast, roastDate = b.roastDate.trim(),
    )

    fun beanForm(c: BlendComponent): BeanForm {
        val (seg, sub, other) = splitProcess(c.process, c.processOther)
        return BeanForm(
            roastery = c.roastery, selection = c.selection, country = c.country,
            region = RegionText.split(c.region).first, subRegion = RegionText.split(c.region).second, farmProducer = c.farmProducer,
            washingStation = c.washingStation, altitude = Altitude.forField(c.altitude),
            variety = VarietyText.split(c.variety).first, heirloomNumbers = VarietyText.split(c.variety).second, moisture = c.moisture, density = c.density,
            score = c.score, process = seg, processSub = sub, processOther = other, roast = c.roast, roastDate = c.roastDate, percent = c.percent,
            loaded = LoadedOrigin(c.region, c.altitude, c.variety),
        )
    }

    /** "+ 원두 추가 (블렌드)": another bean block, empty; the record is a café blend from now on. */
    fun addBlendBean(s: FormState): FormState = s.copy(blendBeans = s.blendBeans + BeanForm(), beanMode = BeanMode.COMMERCIAL_BLEND)

    /** ✕ on bean [index] (the 2nd bean is 1): its block goes, and with one bean left the record is a single bean again. */
    fun removeBlendBean(s: FormState, index: Int): FormState {
        if (index < 1 || index > s.blendBeans.size) return s
        val rest = s.blendBeans.filterIndexed { i, _ -> i != index - 1 }
        if (rest.isNotEmpty()) return s.copy(blendBeans = rest)
        val mode = if (s.beanMode == BeanMode.COMMERCIAL_BLEND) BeanMode.SINGLE else s.beanMode
        return s.copy(blendBeans = rest, beanMode = mode, firstBeanPercent = "")
    }

    /** The café blend's shares typed so far, added up; null while none is (the form shows "합계 N%" once there is one). */
    fun blendPercentSum(s: FormState): Double? = BlendBeans.percentSum((0 until s.beanCount).map { s.bean(it).percent })

    // ---------- cupping beans ----------

    fun cuppingBeanModel(b: CuppingBeanForm): CuppingBean {
        val process = when {
            b.process == PROCESS_OTHER -> b.processOther.trim().ifBlank { PROCESS_OTHER }
            else -> processValue(b.process, b.processSub)
        }
        return CuppingBean(
            id = b.id,
            name = b.name.trim(),
            country = b.country.trim(),
            region = OriginKeep.region(b.loaded.region, b.region, b.subRegion),
            roastery = b.roastery.trim(),
            farmProducer = b.farmProducer.trim(),
            altitude = OriginKeep.altitude(b.loaded.altitude, b.altitude),
            variety = OriginKeep.variety(b.loaded.variety, b.variety, b.heirloomNumbers),
            price = Prices.normalize(b.price),
            rank = b.rank.trim(),
            process = process,
            roast = b.roast,
            expectedNotes = NoteCanon.joinChips(b.expectedNotes),
            actualNotes = NoteCanon.joinChips(b.actualNotes),
            evaluation = if (b.scoreForm == ScoreForm.CVA) CvaScoring.toTexts(b.cva)
            else b.evaluation.filterKeys { !CvaScoring.isCvaKey(it) }.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
            evaluationScores = if (b.scoreForm == ScoreForm.CVA) CvaScoring.toScores(b.cva)
            else b.evaluationScores.filter { (k, v) -> !CvaScoring.isCvaKey(k) && v > 0 },
            memo = b.memo.trim(),
            beanMode = if (b.beanMode == BeanMode.BLEND) BeanMode.BLEND else BeanMode.SINGLE,
            blendComponentsText = b.blendComponentsText.trim(),
        )
    }

    fun cuppingBeanForm(b: CuppingBean): CuppingBeanForm {
        val parsed = BeanNames.parseFarmProducer(b.process)
        val (seg, sub, other) = when {
            parsed.farm in Processes.mainSegments -> Triple(parsed.farm, parsed.producer, "")
            b.process.isNotBlank() -> Triple(PROCESS_OTHER, "", if (b.process == PROCESS_OTHER) "" else b.process)
            else -> Triple("", "", "")
        }
        return CuppingBeanForm(
            id = b.id,
            name = b.name,
            beanMode = if (b.beanMode == BeanMode.BLEND) BeanMode.BLEND else BeanMode.SINGLE,
            blendComponentsText = b.blendComponentsText,
            country = b.country,
            region = RegionText.split(b.region).first,
            subRegion = RegionText.split(b.region).second,
            roastery = b.roastery,
            farmProducer = b.farmProducer,
            altitude = Altitude.forField(b.altitude),
            variety = VarietyText.split(b.variety).first,
            heirloomNumbers = VarietyText.split(b.variety).second,
            loaded = LoadedOrigin(b.region, b.altitude, b.variety),
            price = Prices.formatInput(b.price),
            rank = b.rank,
            process = seg,
            processSub = sub,
            processOther = other,
            roast = b.roast,
            expectedNotes = NoteCanon.parseChips(b.expectedNotes),
            actualNotes = NoteCanon.parseChips(b.actualNotes),
            evaluation = b.evaluation.filterKeys { !CvaScoring.isCvaKey(it) },
            evaluationScores = b.evaluationScores.filterKeys { !CvaScoring.isCvaKey(it) },
            scoreForm = if (CvaScoring.present(b.evaluationScores, b.evaluation)) ScoreForm.CVA else ScoreForm.SCA2004,
            cva = CvaScoring.fromMaps(b.evaluationScores, b.evaluation),
            memo = b.memo,
        )
    }

    // ---------- name helpers ----------

    /** Web name-parse-hint: "괄호에서 인식: 로스터리: … · 출처: …". */
    fun nameParenHint(name: String): String? {
        val p = BeanNames.parseNameParens(name) ?: return null
        val hints = listOfNotNull(
            p.roastery.takeIf { it.isNotBlank() }?.let { "로스터리: $it" },
            p.source.takeIf { it.isNotBlank() }?.let { "출처: $it" },
            p.farm.takeIf { it.isNotBlank() }?.let { "농장: $it" },
            p.producer.takeIf { it.isNotBlank() }?.let { "생산자: $it" },
        )
        return if (hints.isEmpty()) null else "괄호에서 인식: ${hints.joinToString(" · ")}"
    }

    /**
     * The 로스터리 typed is not among the registered roasteries ([known]), so saving adds it to 원두 › 로스터리
     * (SaveEntryPipeline's auto-registration, which matches names the same way: case and spaces ignored).
     */
    fun isNewRoastery(roastery: String, known: List<String>): Boolean {
        if (roastery.isBlank()) return false
        val key = miscNameKey(MiscType.SOURCE, roastery)
        return known.none { miscNameKey(MiscType.SOURCE, it) == key }
    }

    /** Typing a name: the parenthesised farm/producer fills 농장(생산자) only while that field is empty. */
    fun onNameTyped(state: FormState, name: String): FormState {
        val p = BeanNames.parseNameParens(name)
        val farm = if (p != null && (p.farm.isNotBlank() || p.producer.isNotBlank()) && state.farmProducer.isBlank()) {
            BeanNames.formatFarmProducer(p.farm, p.producer)
        } else state.farmProducer
        return state.copy(name = name, farmProducer = farm, error = if (state.error?.field == FormField.NAME) null else state.error)
    }

    /**
     * Web f-name blur: pull the bean's bag info from its earliest record. Unlike the web, which overwrote
     * everything, only fields that are still empty are filled (the default bag weight counts as empty).
     */
    fun autofill(state: FormState, matches: List<Entry>): FormState {
        if (matches.isEmpty()) return state.copy(autofillBanner = false, repeatBean = false)
        val sorted = matches.sortedBy { it.createdAt }
        val first = sorted.first()
        fun registered(pick: (Entry) -> String): String = sorted.firstOrNull { pick(it).isNotBlank() }?.let(pick) ?: ""
        fun fill(current: String, value: String): String = if (current.isBlank()) value else current
        val parens = BeanNames.parseNameParens(first.name)
        var s = state.copy(
            country = fill(state.country, first.country),
            // 지역 and 세부 지역 come together, from the same record
            region = if (state.region.isBlank() && state.subRegion.isBlank()) RegionText.split(first.region).first else state.region,
            subRegion = if (state.region.isBlank() && state.subRegion.isBlank()) RegionText.split(first.region).second else state.subRegion,
            altitude = fill(state.altitude, Altitude.forField(first.altitude)),
            // the varieties and their Heirloom numbers come together, from the same record
            variety = if (state.variety.isBlank() && state.heirloomNumbers.isBlank()) VarietyText.split(first.variety).first else state.variety,
            heirloomNumbers = if (state.variety.isBlank() && state.heirloomNumbers.isBlank()) VarietyText.split(first.variety).second else state.heirloomNumbers,
            // what is filled from the earlier record is saved as that record wrote it
            loaded = LoadedOrigin(
                region = if (state.region.isBlank() && state.subRegion.isBlank()) first.region else state.loaded.region,
                altitude = if (state.altitude.isBlank()) first.altitude else state.loaded.altitude,
                variety = if (state.variety.isBlank() && state.heirloomNumbers.isBlank()) first.variety else state.loaded.variety,
            ),
            farmProducer = fill(state.farmProducer, first.farmProducer),
            roastery = fill(state.roastery, first.roastery.ifBlank { parens?.roastery ?: "" }),
            selection = fill(state.selection, first.selection.ifBlank { parens?.source ?: "" }),
            washingStation = fill(state.washingStation, first.washingStation),
            moisture = fill(state.moisture, first.moisture),
            density = fill(state.density, first.density),
            score = fill(state.score, first.score),
            bagWeight = if (state.bagWeight.isBlank() || state.bagWeight == DEFAULT_BAG_WEIGHT) first.bagWeight.ifBlank { state.bagWeight } else state.bagWeight,
            arrival = fill(state.arrival, first.arrival),
            roastDate = fill(state.roastDate, registered { it.roastDate }),
            roasterDesc = fill(state.roasterDesc, first.roasterDesc),
            expectedNotes = state.expectedNotes.ifEmpty { NoteCanon.parseChips(first.expectedNotes) },
            roast = fill(state.roast, registered { it.roast }),
        )
        if (s.process.isBlank()) {
            val (seg, sub, other) = splitProcess(registered { it.process }, registered { it.processOther })
            s = s.copy(process = seg, processSub = sub, processOther = other)
        }
        if (state.isBrew && state.price.isBlank()) {
            sorted.firstOrNull { it.isBrew && it.price.isNotBlank() }?.let { s = s.copy(price = Prices.formatInput(it.price)) }
        }
        // a café blend comes back with all its beans (bean 1 is the fields above), while the form has none of its own
        val blend = sorted.firstOrNull { BlendBeans.hasBeans(it) } ?: sorted.firstOrNull { BlendBeans.isCafeBlend(it) }
        if (blend != null && !state.isCustomBlend && !state.isCupping && state.blendBeans.all { it.isBlank } && state.firstBeanPercent.isBlank()) {
            s = s.copy(
                beanMode = BeanMode.COMMERCIAL_BLEND,
                blendBeans = blend.blendComponents.drop(1).map(::beanForm).ifEmpty { listOf(BeanForm()) },
                firstBeanPercent = blend.blendComponents.firstOrNull()?.percent ?: "",
            )
        }
        // Web lockBeanInfoFields + hideBagPhotoSection: shown for every repeat of a known bean, filled or not.
        return s.copy(autofillBanner = true, repeatBean = true)
    }

    // ---------- recipes ----------

    fun applyChampion(state: FormState, c: Champions.Champion): FormState = state.copy(
        dose = Prices.trimNumber(c.dose), water = Prices.trimNumber(c.water), temp = c.temp.toString(), tempHint = "",
        dripper = c.dripper, openLauncher = null,
    )

    fun applyCafeRecipe(state: FormState, r: CafeRecipes.Recipe): FormState = withStepsTime(
        state.copy(
            dose = r.dose, water = r.water, temp = r.temp ?: "", tempHint = r.tempRange?.let { "권장 범위: $it" } ?: "",
            dripper = r.dripper, grind = r.grind, filter = r.filter ?: state.filter, time = r.time.ifBlank { state.time },
            steps = r.steps.map(StepForm::from), appliedRecipeRef = RecipeRef(r.name, r.steps), openLauncher = null,
        )
    )

    fun applyMyRecipe(state: FormState, r: MyRecipe): FormState = withStepsTime(
        state.copy(
            dose = r.dose, water = r.water, temp = r.temp, tempHint = "", dripper = r.dripper, filter = r.filter, grind = r.grind,
            time = r.time.ifBlank { state.time }, steps = r.steps.map(StepForm::from),
            appliedRecipeRef = RecipeRef(r.name, r.steps), openLauncher = null,
        )
    )

    /** A record with any recipe value: a café record with one opens with its folded recipe part unfolded. */
    fun hasRecipe(en: Entry): Boolean =
        listOf(en.dripper, en.filter, en.grind, en.dose, en.water, en.temp, en.time, en.waterType).any { it.isNotBlank() } || en.steps.isNotEmpty()

    // ---------- steps ----------

    /**
     * Whether the step log holds rows of the user's own: anything but nothing or the example every new form starts
     * with. Replacing such a log with the brew timer's rows asks first.
     */
    fun hasOwnStepLog(state: FormState): Boolean {
        val live = state.steps.map { it.toStep() }.filter { !it.isEmpty }
        return live.isNotEmpty() && live != GenericSteps.example
    }

    /** Web updateStepsSummary: with a log present, 총 추출시간 always mirrors the computed value. */
    fun withStepsTime(state: FormState): FormState {
        val computed = stepsTime(state) ?: return state
        return if (computed == state.time) state else state.copy(time = computed)
    }

    /**
     * The 총 추출시간 the step log decides (web updateStepsSummary overwrites f-time on every input while the steps
     * give a time), or null when the field is free text. The time field uses it as its input filter, so a keystroke
     * the step log would undo is not shown either.
     */
    fun stepsTime(state: FormState): String? {
        val steps = state.steps.map { it.toStep() }.filter { !it.isEmpty }
        if (steps.isEmpty()) return null
        return RecipeSteps.formatSec(RecipeSteps.summary(steps).totalTimeSec)
    }
}
