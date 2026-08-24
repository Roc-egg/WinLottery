package roc.win.lottery.recognition

import roc.win.lottery.domain.LotteryType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** 一个已经完成单框识别的原图文字区域。 */
internal data class PpOcrRecognizedRegion(
    /** 检测器映射回原图的旋转框。 */
    val sourceBox: SourceTextBox,
    /** 单框识别得到的平台无关文字行。 */
    val line: OcrTextLine,
)

/**
 * 从离散号码格拟合投注行，并用原图整行识别结果替换存在串行误读的小框结果。
 *
 * 只有整行形成唯一合法的 `5+2` 或 `6+1` 号码结构，且不与已有合法结构冲突时才替换。
 */
internal object PpOcrBetRowRefiner {
    /** 查找密集号码行、请求整行识别，并返回去除已替换小框后的文字行。 */
    fun refine(
        regions: List<PpOcrRecognizedRegion>,
        recognizeMergedRow: (SourceTextBox) -> List<OcrTextLine>,
    ): List<OcrTextLine> = refine(regions, recognizeMergedRow, null)

    /** 中文整行全部失败后再使用英文数字模型重试旧式票面。 */
    fun refine(
        regions: List<PpOcrRecognizedRegion>,
        recognizeMergedRow: (SourceTextBox) -> List<OcrTextLine>,
        recognizeLatinMergedRow: ((SourceTextBox) -> List<OcrTextLine>)?,
        allowSeverelyDegradedFragments: Boolean = false,
        onRefinementProgress: (completedRows: Int, totalRows: Int) -> Unit = { _, _ -> },
    ): List<OcrTextLine> {
        if (regions.size < MINIMUM_ROW_FRAGMENT_COUNT) {
            onRefinementProgress(0, 0)
            return regions.map(PpOcrRecognizedRegion::line)
        }
        val anchoredRows = findAnchoredRows(regions)
        val denseNumericRows = findDenseNumericRows(regions)
        val denseRows = buildDenseRefinementRows(regions, denseNumericRows)
        val allowCropConsensus = recognizeLatinMergedRow != null
        val sparseRows =
            buildSparseRefinementRows(regions, denseNumericRows, allowSeverelyDegradedFragments)
        val geometricRows = buildGeometricRefinementRows(regions, denseNumericRows)
        val interpolatedRows = buildInterpolatedRefinementRows(regions, denseNumericRows)
        val refinementRows =
            constrainToBettingScope(
                regions = regions,
                rows = mergeRefinementRows(anchoredRows + denseRows + sparseRows + geometricRows + interpolatedRows),
            )
        if (refinementRows.isEmpty()) {
            onRefinementProgress(0, 0)
            return regions.map(PpOcrRecognizedRegion::line)
        }

        val selectedReplacements = mutableListOf<SelectedReplacement>()
        refinementRows.forEachIndexed { rowIndex, row ->
            onRefinementProgress(rowIndex, refinementRows.size)
            try {
                val primaryRecognition =
                    recognizeCandidateBoxes(
                        regions = row.evidenceRegions,
                        boxes = row.sourceBoxes,
                        recognize = recognizeMergedRow,
                        allowCropConsensus = allowCropConsensus,
                    )
                val supplementalRecognition =
                    if (primaryRecognition.replacement == null) {
                        recognizeCandidateBoxes(
                            regions = row.evidenceRegions,
                            boxes = row.supplementalSourceBoxes,
                            recognize = recognizeMergedRow,
                            allowCropConsensus = allowCropConsensus,
                        )
                    } else {
                        CandidateRecognition.EMPTY
                    }
                val fallbackRecognition =
                    if (primaryRecognition.replacement == null && supplementalRecognition.replacement == null) {
                        recognizeCandidateBoxes(
                            regions = row.evidenceRegions,
                            boxes = row.fallbackSourceBoxes,
                            recognize = recognizeMergedRow,
                            allowCropConsensus = allowCropConsensus,
                        )
                    } else {
                        CandidateRecognition.EMPTY
                    }
                val chineseReplacement =
                    primaryRecognition.replacement
                        ?: supplementalRecognition.replacement
                        ?: fallbackRecognition.replacement
                val latinRecognition =
                    if (chineseReplacement == null && recognizeLatinMergedRow != null) {
                        recognizeCandidateBoxes(
                            regions = row.evidenceRegions,
                            boxes = row.sourceBoxes + row.supplementalSourceBoxes + row.fallbackSourceBoxes,
                            recognize = recognizeLatinMergedRow,
                            allowCropConsensus = allowCropConsensus,
                            maximumCropCount = MAXIMUM_LATIN_CROP_COUNT,
                        )
                    } else {
                        CandidateRecognition.EMPTY
                    }
                val candidates =
                    primaryRecognition.candidates +
                        supplementalRecognition.candidates +
                        fallbackRecognition.candidates +
                        latinRecognition.candidates
                val mergedLine =
                    chineseReplacement
                        ?: latinRecognition.replacement
                        ?: selectExistingReplacement(row.evidenceRegions, candidates)
                if (mergedLine != null) {
                    val removed =
                        regions.filter { region ->
                            row.containsForReplacement(region)
                        }
                    selectedReplacements += SelectedReplacement(row, mergedLine, removed)
                }
            } finally {
                onRefinementProgress(rowIndex + 1, refinementRows.size)
            }
        }
        val strongReplacements =
            selectedReplacements
                .filterNot { selected -> selected.row.sourcePriority.isWeakGeometry }
        val sourceFilteredReplacements =
            selectedReplacements.filterNot { selected ->
                val signature = strictBetSignatures(selected.line.text).singleOrNull()
                val hasNearbyStrongAbove =
                    strongReplacements.any { strong ->
                        strong.row.centerY < selected.row.centerY &&
                            selected.row.centerY - strong.row.centerY <=
                            selected.row.typicalHeight * MAXIMUM_WEAK_DUPLICATE_NEIGHBOR_GAP_RATIO
                    }
                val hasNearbyStrongBelow =
                    strongReplacements.any { strong ->
                        strong.row.centerY > selected.row.centerY &&
                            strong.row.centerY - selected.row.centerY <=
                            selected.row.typicalHeight * MAXIMUM_WEAK_DUPLICATE_NEIGHBOR_GAP_RATIO
                    }
                selected.row.sourcePriority.isWeakGeometry &&
                    signature != null &&
                    hasNearbyStrongAbove &&
                    hasNearbyStrongBelow &&
                    strongReplacements.any { strong -> signature in strictBetSignatures(strong.line.text) }
            }
        val acceptedReplacements = deduplicateNearbyReplacements(sourceFilteredReplacements)
        val replacedRegions = acceptedReplacements.flatMap(SelectedReplacement::removedRegions).toSet()
        val replacements = acceptedReplacements.map(SelectedReplacement::line)
        return buildList {
            regions.filterNot(replacedRegions::contains).mapTo(this, PpOcrRecognizedRegion::line)
            addAll(replacements)
        }
    }

    /** 逐个识别候选裁图，并在同一裁图的两种图像版本形成合法共识后立即停止。 */
    private fun recognizeCandidateBoxes(
        regions: List<PpOcrRecognizedRegion>,
        boxes: List<SourceTextBox>,
        recognize: (SourceTextBox) -> List<OcrTextLine>,
        allowCropConsensus: Boolean,
        maximumCropCount: Int = Int.MAX_VALUE,
    ): CandidateRecognition {
        val candidateGroups = mutableListOf<List<OcrTextLine>>()
        boxes.distinct().take(maximumCropCount).forEach { box ->
            val candidates = recognize(box)
            candidateGroups += candidates
            val decisiveReplacement =
                selectDecisiveSingleCropReplacement(
                    regions = regions,
                    candidates = candidates,
                    allowCropConsensus = allowCropConsensus,
                )
            if (decisiveReplacement != null) {
                return CandidateRecognition(candidateGroups.flatten(), decisiveReplacement)
            }
        }
        return CandidateRecognition(
            candidates = candidateGroups.flatten(),
            replacement = selectCropConsensusReplacement(regions, candidateGroups, allowCropConsensus),
        )
    }

    /** 判断同一裁图的原图与增强图是否已经形成可提前采纳的唯一合法号码。 */
    private fun selectDecisiveSingleCropReplacement(
        regions: List<PpOcrRecognizedRegion>,
        candidates: List<OcrTextLine>,
        allowCropConsensus: Boolean,
    ): OcrTextLine? {
        if (!allowCropConsensus) return null
        val replacement = selectReplacement(regions, candidates) ?: return null
        val signature = strictBetSignatures(replacement.text).singleOrNull() ?: return null
        val supportCount = candidates.count { candidate -> signature in strictBetSignatures(candidate.text) }
        return replacement.takeIf { supportCount >= MINIMUM_CROP_CONSENSUS_COUNT }
    }

    /** 合并同一物理行的重复结果，并保留更可靠定位及双方的碎片清理范围。 */
    private fun deduplicateNearbyReplacements(replacements: List<SelectedReplacement>): List<SelectedReplacement> {
        val accepted = mutableListOf<SelectedReplacement>()
        replacements.sortedBy { replacement -> replacement.row.centerY }.forEach { candidate ->
            val signature = strictBetSignatures(candidate.line.text).singleOrNull()
            val duplicateIndex =
                if (signature == null) {
                    -1
                } else {
                    accepted.indexOfFirst { existing ->
                        candidate.isDuplicateOf(existing, signature)
                    }
                }
            if (duplicateIndex < 0) {
                accepted += candidate
                return@forEach
            }

            val existing = accepted[duplicateIndex]
            val hasSameExplicitLineMarker =
                candidate.explicitCircledLineMarkers.intersect(existing.explicitCircledLineMarkers).isNotEmpty()
            val preferred =
                when {
                    hasSameExplicitLineMarker && candidate.hasHigherConfidenceThan(existing) -> candidate
                    hasSameExplicitLineMarker -> existing
                    candidate.hasExplicitCircledMarker && !existing.hasExplicitCircledMarker -> candidate
                    existing.hasExplicitCircledMarker && !candidate.hasExplicitCircledMarker -> existing
                    candidate.hasDecisiveEvidenceAdvantageOver(existing) -> candidate
                    existing.hasDecisiveEvidenceAdvantageOver(candidate) -> existing
                    candidate.isPreferredTo(existing) -> candidate
                    else -> existing
                }
            val hasSameSignature =
                signature == strictBetSignatures(existing.line.text).singleOrNull()
            accepted[duplicateIndex] =
                preferred.copy(
                    removedRegions =
                        if (hasSameSignature) {
                            (existing.removedRegions + candidate.removedRegions).distinct()
                        } else {
                            preferred.removedRegions
                        },
                )
        }
        return accepted
    }

    /** 判断两个合法结果是否来自同一物理投注行。 */
    private fun SelectedReplacement.isDuplicateOf(
        other: SelectedReplacement,
        signature: StrictBetSignature,
    ): Boolean {
        val centerGap = abs(row.centerY - other.row.centerY)
        val typicalHeight = max(row.typicalHeight, other.row.typicalHeight)
        val sameSignature = signature == strictBetSignatures(other.line.text).singleOrNull()
        val closeIdenticalCenter =
            sameSignature && centerGap <= typicalHeight * MAXIMUM_IDENTICAL_REPLACEMENT_CENTER_GAP_RATIO
        val overlappingCrop =
            centerGap <= typicalHeight * MAXIMUM_OVERLAPPING_REPLACEMENT_CENTER_GAP_RATIO &&
                line.bounds.verticalOverlapRatio(other.line.bounds) >= MINIMUM_REPLACEMENT_VERTICAL_OVERLAP_RATIO
        if (sameSignature) return closeIdenticalCenter || overlappingCrop
        val closelyOverlappingConflict =
            centerGap <= typicalHeight * MAXIMUM_CONFLICTING_REPLACEMENT_CENTER_GAP_RATIO &&
                line.bounds.verticalOverlapRatio(other.line.bounds) >= MINIMUM_REPLACEMENT_VERTICAL_OVERLAP_RATIO
        val hasSameExplicitLineMarker =
            explicitCircledLineMarkers.intersect(other.explicitCircledLineMarkers).isNotEmpty()
        val sameMarkerOverlappingConflict =
            hasSameExplicitLineMarker &&
                line.bounds.verticalOverlapRatio(other.line.bounds) >= MINIMUM_REPLACEMENT_VERTICAL_OVERLAP_RATIO
        val hasReliableConflictResolution =
            hasSameExplicitLineMarker ||
                hasExplicitCircledMarker != other.hasExplicitCircledMarker ||
                hasDecisiveEvidenceAdvantageOver(other) ||
                other.hasDecisiveEvidenceAdvantageOver(this)
        return sameMarkerOverlappingConflict || (closelyOverlappingConflict && hasReliableConflictResolution)
    }

    /** 仅在一方证据明显完整、另一方只有少量碎片时允许解决重叠异号冲突。 */
    private fun SelectedReplacement.hasDecisiveEvidenceAdvantageOver(other: SelectedReplacement): Boolean =
        row.evidenceRegions.size >= MINIMUM_RELIABLE_REPLACEMENT_EVIDENCE_COUNT &&
            other.row.evidenceRegions.size <= MAXIMUM_DEGRADED_REPLACEMENT_EVIDENCE_COUNT

    /** 判断候选文字或原始证据中是否存在明确的圆圈注序号。 */
    private val SelectedReplacement.hasExplicitCircledMarker: Boolean
        get() =
            line.text.any { character -> character.toString() in CIRCLED_ROW_MARKERS } ||
                row.evidenceRegions.any { region -> region.compactText in CIRCLED_ROW_MARKERS }

    /** 返回整行候选文字中明确出现的圆圈注序号，用于识别同一物理行的异号重复。 */
    private val SelectedReplacement.explicitCircledLineMarkers: Set<String>
        get() =
            line.text
                .map(Char::toString)
                .filterTo(mutableSetOf()) { character -> character in CIRCLED_ROW_MARKERS }

    /** 仅比较整行 OCR 置信度，供相同明确行号的冲突候选择优。 */
    private fun SelectedReplacement.hasHigherConfidenceThan(other: SelectedReplacement): Boolean =
        (line.confidence ?: 0f) > (other.line.confidence ?: 0f)

    /** 计算两个归一化文字框纵向交集占较矮文字框的比例。 */
    private fun NormalizedBounds.verticalOverlapRatio(other: NormalizedBounds): Float {
        val overlap = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val minimumHeight = minOf(bottom - top, other.bottom - other.top)
        return if (minimumHeight > 0f) overlap / minimumHeight else 0f
    }

    /** 按定位来源强度和整行置信度选择同一物理行的保留结果。 */
    private fun SelectedReplacement.isPreferredTo(other: SelectedReplacement): Boolean =
        row.sourcePriority.weight > other.row.sourcePriority.weight ||
            (
                row.sourcePriority.weight == other.row.sourcePriority.weight &&
                    (line.confidence ?: 0f) > (other.line.confidence ?: 0f)
            )

    /** 优先要求全部裁图一致；冲突时只接受同一裁图至少两个图像版本形成的共识。 */
    private fun selectCropConsensusReplacement(
        regions: List<PpOcrRecognizedRegion>,
        candidateGroups: List<List<OcrTextLine>>,
        allowCropConsensus: Boolean,
    ): OcrTextLine? {
        selectReplacement(regions, candidateGroups.flatten())?.let { return it }
        if (!allowCropConsensus) return null
        val explicitMarkerConsensus =
            candidateGroups.mapNotNull(::selectExplicitMarkerCropConsensus)
        selectUniqueConsensusCandidate(explicitMarkerConsensus)?.let { return it }
        val cropConsensus =
            candidateGroups.mapNotNull { candidates ->
                val replacement = selectReplacement(regions, candidates) ?: return@mapNotNull null
                val signature = strictBetSignatures(replacement.text).singleOrNull() ?: return@mapNotNull null
                val supportCount = candidates.count { candidate -> signature in strictBetSignatures(candidate.text) }
                replacement.takeIf { supportCount >= MINIMUM_CROP_CONSENSUS_COUNT }
            }
        return selectReplacement(regions, cropConsensus)
    }

    /** 同一裁图至少两个图像版本均含明确圆圈行号时，返回其唯一合法号码共识。 */
    private fun selectExplicitMarkerCropConsensus(candidates: List<OcrTextLine>): OcrTextLine? {
        val supported =
            candidates.mapNotNull { candidate ->
                if ((candidate.confidence ?: 0f) < MINIMUM_MERGED_ROW_CONFIDENCE) return@mapNotNull null
                if (candidate.text.none { character -> character.toString() in CIRCLED_ROW_MARKERS }) {
                    return@mapNotNull null
                }
                strictBetSignatures(candidate.text).singleOrNull()?.let { signature -> signature to candidate }
            }
        if (supported.size < MINIMUM_CROP_CONSENSUS_COUNT) return null
        val signatures = supported.map(Pair<StrictBetSignature, OcrTextLine>::first).toSet()
        if (signatures.size != 1) return null
        return supported.maxByOrNull { candidate -> candidate.second.confidence ?: 0f }?.second
    }

    /** 从多个已独立形成的裁图共识中选择唯一号码，不参考可能误读的旧碎片文字。 */
    private fun selectUniqueConsensusCandidate(candidates: List<OcrTextLine>): OcrTextLine? {
        val valid =
            candidates.mapNotNull { candidate ->
                strictBetSignatures(candidate.text).singleOrNull()?.let { signature -> signature to candidate }
            }
        if (valid.map(Pair<StrictBetSignature, OcrTextLine>::first).toSet().size != 1) return null
        return valid.maxByOrNull { candidate -> candidate.second.confidence ?: 0f }?.second
    }

    /** 用多条投注结构锚点收紧纵向范围，排除相邻票号、日期和摘要行。 */
    private fun constrainToBettingScope(
        regions: List<PpOcrRecognizedRegion>,
        rows: List<RefinementRow>,
    ): List<RefinementRow> {
        if (rows.isEmpty()) return emptyList()
        val typicalHeight =
            rows
                .map(RefinementRow::typicalHeight)
                .filter { height -> height > 0f }
                .sorted()
                .medianOrNull()
                ?: return rows
        val anchorCenters =
            regions
                .filter { region -> region.isBettingScopeAnchor }
                .map { region -> region.sourceBox.center.y }
                .sorted()
        val anchorRows = mutableListOf<MutableList<Float>>()
        anchorCenters.forEach { center ->
            val current =
                anchorRows.lastOrNull()?.takeIf { group ->
                    abs(group.average() - center) <=
                        typicalHeight * MAXIMUM_BETTING_SCOPE_ANCHOR_CLUSTER_GAP_RATIO
                }
            if (current == null) {
                anchorRows += mutableListOf(center)
            } else {
                current += center
            }
        }
        if (anchorRows.size < MINIMUM_BETTING_SCOPE_ANCHOR_ROW_COUNT) return rows
        val minimumCenter = anchorRows.first().average().toFloat()
        val maximumCenter = anchorRows.last().average().toFloat()
        if (
            maximumCenter - minimumCenter <
            typicalHeight * MINIMUM_BETTING_SCOPE_ANCHOR_SPAN_RATIO
        ) {
            return rows
        }
        val padding = typicalHeight * BETTING_SCOPE_VERTICAL_PADDING_RATIO
        return rows.filter { row -> row.centerY in minimumCenter - padding..maximumCenter + padding }
    }

    /** 从圆圈序号、旧票 A-E 行标和行尾倍数列建立逐行裁图。 */
    private fun findAnchoredRows(regions: List<PpOcrRecognizedRegion>): List<RefinementRow> {
        val numericCandidates = regions.filter { region -> region.isShortNumericFragment }
        val typicalHeight =
            numericCandidates
                .map { region -> region.sourceBox.averageHeight }
                .filter { height -> height > 0f }
                .sorted()
                .medianOrNull()
                ?: return emptyList()
        return mergeRefinementRows(
            findRowsFromAnchorColumn(
                regions = regions,
                typicalHeight = typicalHeight,
                isExplicitMarker = { region -> region.compactText in CIRCLED_ROW_MARKERS },
                isMarkerCandidate = { region ->
                    region.compactText in CIRCLED_ROW_MARKERS || region.compactText.isSingleRowIndexDigit
                },
            ) +
                findRowsFromAnchorColumn(
                    regions = regions,
                    typicalHeight = typicalHeight,
                    isExplicitMarker = { region -> region.compactText.isLegacyRowMarker },
                    isMarkerCandidate = { region -> region.compactText.isLegacyRowMarker },
                ) +
                findRowsFromAnchorColumn(
                    regions = regions,
                    typicalHeight = typicalHeight,
                    isExplicitMarker = { region -> region.compactText.isRowMultiplierMarker },
                    isMarkerCandidate = { region -> region.compactText.isRowMultiplierMarker },
                ),
        )
    }

    /** 从一个重复锚点列拟合投注行，兼容热敏纸轻微弯曲造成的号码中心偏移。 */
    private fun findRowsFromAnchorColumn(
        regions: List<PpOcrRecognizedRegion>,
        typicalHeight: Float,
        isExplicitMarker: (PpOcrRecognizedRegion) -> Boolean,
        isMarkerCandidate: (PpOcrRecognizedRegion) -> Boolean,
    ): List<RefinementRow> {
        val explicitMarkers = regions.filter(isExplicitMarker)
        if (explicitMarkers.isEmpty()) return emptyList()
        val markerX = explicitMarkers.map { it.sourceBox.center.x }.sorted().medianOrNull() ?: return emptyList()
        val markerCandidates =
            regions
                .filter { region ->
                    isMarkerCandidate(region) &&
                        abs(region.sourceBox.center.x - markerX) <= typicalHeight * MAXIMUM_MARKER_COLUMN_OFFSET_RATIO
                }.sortedBy { region -> region.sourceBox.center.y }
                .deduplicateMarkerRows(typicalHeight, isExplicitMarker)
        val markers = markerCandidates.longestMarkerChain(typicalHeight, isExplicitMarker)
        if (markers.size < MINIMUM_INDEXED_ROW_COUNT) return emptyList()

        val globalSlope = estimateGlobalTextSlope(regions)
        val axisLength = sqrt(1f + globalSlope * globalSlope)
        val axisX = 1f / axisLength
        val axisY = globalSlope / axisLength
        val perpendicularX = -axisY
        val perpendicularY = axisX
        val minimumMarkerY =
            markers
                .first()
                .sourceBox.center.y - typicalHeight * INDEXED_VERTICAL_SCOPE_PADDING_RATIO
        val maximumMarkerY =
            markers
                .last()
                .sourceBox.center.y + typicalHeight * INDEXED_VERTICAL_SCOPE_PADDING_RATIO
        val scopeRegions =
            regions
                .filter { region -> region.sourceBox.center.y in minimumMarkerY..maximumMarkerY }
                .filter { region -> region.isReplaceableBetRowFragment || isExplicitMarker(region) }
        val scopePoints = scopeRegions.flatMap { region -> region.sourceBox.points }
        if (scopePoints.isEmpty()) return emptyList()
        val minimumLong =
            scopePoints.minOf { point -> point.x * axisX + point.y * axisY } -
                typicalHeight * LONG_AXIS_PADDING_RATIO
        val maximumLong =
            scopePoints.maxOf { point -> point.x * axisX + point.y * axisY } +
                typicalHeight * LONG_AXIS_PADDING_RATIO

        fun point(
            long: Float,
            short: Float,
        ): FloatPoint =
            FloatPoint(
                x = long * axisX + short * perpendicularX,
                y = long * axisY + short * perpendicularY,
            )

        return markers.map { marker ->
            val markerCenter = marker.sourceBox.center
            val markerShort = markerCenter.x * perpendicularX + markerCenter.y * perpendicularY
            val centerShort = markerShort - typicalHeight * INDEXED_CENTER_UPWARD_OFFSET_RATIO
            val minimumShort = centerShort - typicalHeight * INDEXED_HALF_HEIGHT_RATIO
            val maximumShort = centerShort + typicalHeight * INDEXED_HALF_HEIGHT_RATIO
            val baselinePoint = point((minimumLong + maximumLong) / 2f, centerShort)
            val intercept = baselinePoint.y - globalSlope * baselinePoint.x
            val evidence =
                regions.filter { region ->
                    region.isReplaceableBetRowFragment &&
                        region.sourceBox.center.distanceToLine(globalSlope, intercept) <=
                        typicalHeight * INDEXED_REPLACEMENT_HEIGHT_RATIO
                }
            RefinementRow(
                sourceBoxes =
                    listOf(
                        SourceTextBox(
                            topLeft = point(minimumLong, minimumShort),
                            topRight = point(maximumLong, minimumShort),
                            bottomRight = point(maximumLong, maximumShort),
                            bottomLeft = point(minimumLong, maximumShort),
                            confidence = marker.sourceBox.confidence,
                        ),
                    ),
                evidenceRegions = evidence,
                baselineSlope = globalSlope,
                baselineIntercept = intercept,
                typicalHeight = typicalHeight,
                replacementHeightRatio = INDEXED_REPLACEMENT_HEIGHT_RATIO,
                sourcePriority = RefinementSourcePriority.ANCHORED,
            )
        }
    }

    /** 合并同一高度的重复锚点候选，并优先保留明确锚点。 */
    private fun List<PpOcrRecognizedRegion>.deduplicateMarkerRows(
        typicalHeight: Float,
        isExplicitMarker: (PpOcrRecognizedRegion) -> Boolean,
    ): List<PpOcrRecognizedRegion> {
        val groups = mutableListOf<MutableList<PpOcrRecognizedRegion>>()
        forEach { candidate ->
            val group = groups.lastOrNull()
            if (
                group != null &&
                abs(group.map { it.sourceBox.center.y }.average() - candidate.sourceBox.center.y) <=
                typicalHeight * MAXIMUM_DUPLICATE_MARKER_GAP_RATIO
            ) {
                group += candidate
            } else {
                groups += mutableListOf(candidate)
            }
        }
        return groups.map { group ->
            group.firstOrNull(isExplicitMarker)
                ?: group.maxBy { region -> region.line.confidence ?: 0f }
        }
    }

    /** 返回间距稳定且包含明确锚点的最长连续行链。 */
    private fun List<PpOcrRecognizedRegion>.longestMarkerChain(
        typicalHeight: Float,
        isExplicitMarker: (PpOcrRecognizedRegion) -> Boolean,
    ): List<PpOcrRecognizedRegion> {
        if (isEmpty()) return emptyList()
        val chains = mutableListOf<MutableList<PpOcrRecognizedRegion>>()
        forEach { marker ->
            val current = chains.lastOrNull()
            val gap = current?.lastOrNull()?.let { previous -> marker.sourceBox.center.y - previous.sourceBox.center.y }
            if (
                current != null && gap != null &&
                gap in typicalHeight * MINIMUM_MARKER_ROW_GAP_RATIO..typicalHeight * MAXIMUM_MARKER_ROW_GAP_RATIO
            ) {
                current += marker
            } else {
                chains += mutableListOf(marker)
            }
        }
        return chains
            .filter { chain -> chain.any(isExplicitMarker) }
            .maxByOrNull(List<PpOcrRecognizedRegion>::size)
            .orEmpty()
    }

    /** 让密集拟合行共享投注区横向范围，避免漏框把后区或蓝球裁掉。 */
    private fun buildDenseRefinementRows(
        allRegions: List<PpOcrRecognizedRegion>,
        denseRows: List<List<PpOcrRecognizedRegion>>,
    ): List<RefinementRow> {
        if (denseRows.isEmpty()) return emptyList()
        val denseRegions = denseRows.flatten()
        val typicalHeight =
            denseRegions
                .map { region -> region.sourceBox.averageHeight }
                .filter { height -> height > 0f }
                .sorted()
                .medianOrNull()
                ?: return emptyList()
        val minimumY = denseRegions.minOf { region -> region.sourceBox.center.y } - typicalHeight
        val maximumY = denseRegions.maxOf { region -> region.sourceBox.center.y } + typicalHeight
        val extentRegions =
            allRegions.filter { region ->
                region.isReplaceableBetRowFragment &&
                    region.sourceBox.center.y in minimumY..maximumY &&
                    region.sourceBox.averageHeight in
                    typicalHeight * MINIMUM_FRAGMENT_HEIGHT_RATIO..typicalHeight * MAXIMUM_FRAGMENT_HEIGHT_RATIO
            }
        return denseRows.map { row -> buildDenseRefinementRow(row, extentRegions) }
    }

    /** 使用至少包含两个号码的长框补充稀疏行，并复用可靠密集行的横向投注区范围。 */
    private fun buildSparseRefinementRows(
        allRegions: List<PpOcrRecognizedRegion>,
        denseRows: List<List<PpOcrRecognizedRegion>>,
        allowSeverelyDegradedFragments: Boolean,
    ): List<RefinementRow> {
        if (denseRows.isEmpty()) return emptyList()
        val denseRegions = denseRows.flatten()
        val typicalHeight =
            denseRegions
                .map { region -> region.sourceBox.averageHeight }
                .filter { height -> height > 0f }
                .sorted()
                .medianOrNull()
                ?: return emptyList()
        val minimumY =
            denseRegions.minOf { region -> region.sourceBox.center.y } -
                typicalHeight * SPARSE_VERTICAL_SCOPE_PADDING_RATIO
        val maximumY =
            denseRegions.maxOf { region -> region.sourceBox.center.y } +
                typicalHeight * SPARSE_VERTICAL_SCOPE_PADDING_RATIO
        val candidates =
            allRegions.filter { region ->
                val box = region.sourceBox
                val compact = region.compactText
                val numberTokenCount = extractTwoDigitTokens(compact).size
                val isSeverelyDegradedWideFragment =
                    allowSeverelyDegradedFragments &&
                        compact.length <= MAXIMUM_GEOMETRIC_FRAGMENT_LENGTH &&
                        compact.any(Char::isDigit) &&
                        box.averageWidth >=
                        box.averageHeight * MINIMUM_SEVERELY_DEGRADED_SPARSE_WIDTH_HEIGHT_RATIO
                val hasEnoughNumericEvidence =
                    numberTokenCount >= MINIMUM_SPARSE_ROW_NUMBER_TOKEN_COUNT ||
                        (
                            numberTokenCount == 1 &&
                                compact.count(Char::isDigit) >= MINIMUM_DEGRADED_SPARSE_DIGIT_COUNT
                        ) ||
                        isSeverelyDegradedWideFragment
                hasEnoughNumericEvidence &&
                    box.averageWidth >= box.averageHeight * MINIMUM_SPARSE_ROW_WIDTH_HEIGHT_RATIO &&
                    box.center.y in minimumY..maximumY
            }
        val globalSlope = estimateGlobalTextSlope(allRegions)
        return candidates.map { region ->
            buildDenseRefinementRow(
                regions = listOf(region),
                extentRegions = denseRegions,
                sourcePriority = RefinementSourcePriority.SPARSE,
                baselineSlope = globalSlope,
            )
        }
    }

    /** 在已确认的密集投注区内按基线聚类短框，补回文字被误成字母或单数字的整行。 */
    private fun buildGeometricRefinementRows(
        allRegions: List<PpOcrRecognizedRegion>,
        denseRows: List<List<PpOcrRecognizedRegion>>,
    ): List<RefinementRow> {
        if (denseRows.size < MINIMUM_GEOMETRIC_SCOPE_ROW_COUNT) return emptyList()
        val denseRegions = denseRows.flatten()
        val typicalHeight =
            denseRegions
                .map { region -> region.sourceBox.averageHeight }
                .filter { height -> height > 0f }
                .sorted()
                .medianOrNull()
                ?: return emptyList()
        val minimumY = denseRegions.minOf { region -> region.sourceBox.center.y } - typicalHeight
        val maximumY = denseRegions.maxOf { region -> region.sourceBox.center.y } + typicalHeight
        val globalSlope = estimateGlobalTextSlope(allRegions)
        val candidates =
            allRegions
                .filter { region ->
                    val box = region.sourceBox
                    val compact = region.compactText
                    compact.length in 1..MAXIMUM_GEOMETRIC_FRAGMENT_LENGTH &&
                        box.center.y in minimumY..maximumY &&
                        box.averageHeight in
                        typicalHeight * MINIMUM_FRAGMENT_HEIGHT_RATIO..typicalHeight * MAXIMUM_FRAGMENT_HEIGHT_RATIO &&
                        box.averageWidth <= box.averageHeight * MAXIMUM_GEOMETRIC_FRAGMENT_WIDTH_HEIGHT_RATIO
                }.sortedBy { region ->
                    val center = region.sourceBox.center
                    center.y - globalSlope * center.x
                }
        val groups = mutableListOf<MutableList<PpOcrRecognizedRegion>>()
        candidates.forEach { candidate ->
            val center = candidate.sourceBox.center
            val coordinate = center.y - globalSlope * center.x
            val current =
                groups.lastOrNull()?.takeIf { group ->
                    val groupCoordinate =
                        group
                            .map { region ->
                                val groupCenter = region.sourceBox.center
                                groupCenter.y - globalSlope * groupCenter.x
                            }.average()
                    abs(groupCoordinate - coordinate) <= typicalHeight * GEOMETRIC_ROW_CLUSTER_HEIGHT_RATIO
                }
            if (current == null) {
                groups += mutableListOf(candidate)
            } else {
                current += candidate
            }
        }
        return groups
            .filter { group -> group.size >= MINIMUM_ROW_FRAGMENT_COUNT }
            .filter { group ->
                group.count { region -> region.compactText.any(Char::isDigit) } >=
                    MINIMUM_GEOMETRIC_DIGIT_FRAGMENT_COUNT
            }.filter { group ->
                group.maxOf { region -> region.sourceBox.center.x } -
                    group.minOf { region -> region.sourceBox.center.x } >=
                    typicalHeight * MINIMUM_ROW_SPAN_HEIGHT_RATIO
            }.map { group ->
                buildDenseRefinementRow(
                    regions = group,
                    extentRegions = denseRegions,
                    sourcePriority = RefinementSourcePriority.GEOMETRIC,
                    baselineSlope = globalSlope,
                )
            }
    }

    /** 在密集基线出现异常大间距且目标处仍有短框证据时，补充一个受限的中间裁图。 */
    private fun buildInterpolatedRefinementRows(
        allRegions: List<PpOcrRecognizedRegion>,
        denseRows: List<List<PpOcrRecognizedRegion>>,
    ): List<RefinementRow> {
        if (denseRows.size < MINIMUM_INTERPOLATION_SCOPE_ROW_COUNT) return emptyList()
        val denseRegions = denseRows.flatten()
        val typicalHeight =
            denseRegions
                .map { region -> region.sourceBox.averageHeight }
                .filter { height -> height > 0f }
                .sorted()
                .medianOrNull()
                ?: return emptyList()
        val globalSlope = estimateGlobalTextSlope(allRegions)
        val rowCoordinates =
            denseRows
                .map { row -> row.map { region -> region.sourceBox.center.shortCoordinate(globalSlope) }.average() }
                .sorted()
        val gaps = rowCoordinates.zipWithNext { upper, lower -> lower - upper }
        val typicalGap =
            gaps
                .map(Double::toFloat)
                .sorted()
                .medianOrNull()
                ?.toDouble() ?: return emptyList()
        if (typicalGap <= 0.0) return emptyList()
        val extentPoints = denseRegions.flatMap { region -> region.sourceBox.points }
        val minimumX = extentPoints.minOf(FloatPoint::x)
        val maximumX = extentPoints.maxOf(FloatPoint::x)
        return rowCoordinates
            .zipWithNext()
            .mapNotNull { (upper, lower) ->
                val gap = lower - upper
                if (
                    gap < typicalGap * MINIMUM_INTERPOLATED_GAP_RATIO ||
                    gap > typicalGap * MAXIMUM_INTERPOLATED_GAP_RATIO
                ) {
                    return@mapNotNull null
                }
                val targetCoordinate = (upper + lower) / 2.0
                val evidence =
                    allRegions.filter { region ->
                        val box = region.sourceBox
                        val compact = region.compactText
                        val shortAxisDistance = abs(box.center.shortCoordinate(globalSlope) - targetCoordinate)
                        val maximumShortAxisDistance = typicalHeight * INTERPOLATED_EVIDENCE_HEIGHT_RATIO
                        val minimumFragmentHeight = typicalHeight * MINIMUM_FRAGMENT_HEIGHT_RATIO
                        val maximumFragmentHeight = typicalHeight * MAXIMUM_FRAGMENT_HEIGHT_RATIO
                        val acceptableHeight = box.averageHeight in minimumFragmentHeight..maximumFragmentHeight
                        compact.length in 1..MAXIMUM_GEOMETRIC_FRAGMENT_LENGTH &&
                            box.center.x in minimumX..maximumX &&
                            acceptableHeight &&
                            shortAxisDistance <= maximumShortAxisDistance
                    }
                if (evidence.isEmpty()) {
                    return@mapNotNull null
                }
                if (evidence.none { region -> region.compactText.any(Char::isDigit) }) {
                    return@mapNotNull null
                }
                RefinementRow(
                    sourceBoxes =
                        listOf(
                            buildMergedRowBoxAtCoordinate(
                                extentRegions = denseRegions,
                                slope = globalSlope,
                                shortCoordinate = targetCoordinate.toFloat(),
                                typicalHeight = typicalHeight,
                                confidence = evidence.minOf { region -> region.sourceBox.confidence },
                            ),
                        ),
                    evidenceRegions = evidence,
                    baselineSlope = globalSlope,
                    baselineIntercept = targetCoordinate.toFloat(),
                    typicalHeight = typicalHeight,
                    replacementHeightRatio = REPLACED_FRAGMENT_HEIGHT_RATIO,
                    sourcePriority = RefinementSourcePriority.INTERPOLATED,
                )
            }
    }

    /** 把直线拟合号码组转换为统一的整行裁图和清理基线。 */
    private fun buildDenseRefinementRow(
        regions: List<PpOcrRecognizedRegion>,
        extentRegions: List<PpOcrRecognizedRegion>,
        sourcePriority: RefinementSourcePriority = RefinementSourcePriority.DENSE,
        baselineSlope: Float? = null,
    ): RefinementRow {
        val slope = baselineSlope ?: fitBaseline(regions).first
        val intercept =
            regions
                .map { region ->
                    val center = region.sourceBox.center
                    center.y - slope * center.x
                }.average()
                .toFloat()
        return RefinementRow(
            sourceBoxes = listOf(buildMergedRowBox(regions, extentRegions, slope)),
            evidenceRegions = regions,
            baselineSlope = slope,
            baselineIntercept = intercept,
            typicalHeight = regions.map { it.sourceBox.averageHeight }.sorted().medianOrNull() ?: 0f,
            replacementHeightRatio = REPLACED_FRAGMENT_HEIGHT_RATIO,
            sourcePriority = sourcePriority,
        )
    }

    /** 按基线高度合并同一行；存在强定位时把几何紧裁图隔离为第二层候选。 */
    private fun mergeRefinementRows(rows: List<RefinementRow>): List<RefinementRow> {
        if (rows.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<RefinementRow>>()
        rows.sortedBy(RefinementRow::centerY).forEach { row ->
            val group =
                groups.lastOrNull()?.takeIf { existing ->
                    val groupCenter = existing.map(RefinementRow::centerY).average()
                    val existingStrongRows = existing.filterNot { candidate -> candidate.sourcePriority.isWeakGeometry }
                    val anchoredStrongInvolved =
                        existingStrongRows.isNotEmpty() &&
                            !row.sourcePriority.isWeakGeometry &&
                            (
                                row.sourcePriority == RefinementSourcePriority.ANCHORED ||
                                    existingStrongRows.any { candidate ->
                                        candidate.sourcePriority == RefinementSourcePriority.ANCHORED
                                    }
                            )
                    val centerGapRatio =
                        if (anchoredStrongInvolved) {
                            MAXIMUM_ANCHORED_STRONG_ROW_CENTER_GAP_RATIO
                        } else {
                            MAXIMUM_REFINEMENT_ROW_CENTER_GAP_RATIO
                        }
                    val nearby =
                        abs(groupCenter - row.centerY) <=
                            max(existing.map(RefinementRow::typicalHeight).average(), row.typicalHeight.toDouble()) *
                            centerGapRatio
                    val sharesStrongEvidence =
                        existingStrongRows.any { candidate ->
                            candidate.evidenceRegions.any(row.evidenceRegions::contains)
                        }
                    nearby && (!anchoredStrongInvolved || sharesStrongEvidence)
                }
            if (group == null) {
                groups += mutableListOf(row)
            } else {
                group += row
            }
        }
        return groups
            .map { group ->
                val strongRows = group.filterNot { row -> row.sourcePriority.isWeakGeometry }
                val effectiveGroup =
                    when {
                        strongRows.isNotEmpty() -> {
                            strongRows
                        }

                        group.any { row -> row.sourcePriority == RefinementSourcePriority.GEOMETRIC } -> {
                            group.filter { row -> row.sourcePriority == RefinementSourcePriority.GEOMETRIC }
                        }

                        else -> {
                            group
                        }
                    }
                val primary =
                    effectiveGroup.maxWith(
                        compareBy<RefinementRow> { row -> row.sourcePriority.weight }
                            .thenBy { row -> row.evidenceRegions.size },
                    )
                primary.copy(
                    sourceBoxes =
                        effectiveGroup
                            .sortedByDescending { row -> row.sourcePriority.weight }
                            .flatMap(RefinementRow::sourceBoxes)
                            .distinct(),
                    supplementalSourceBoxes =
                        buildList {
                            effectiveGroup.flatMapTo(this, RefinementRow::supplementalSourceBoxes)
                            if (strongRows.isNotEmpty()) {
                                group
                                    .filter { row -> row.sourcePriority == RefinementSourcePriority.GEOMETRIC }
                                    .flatMapTo(this, RefinementRow::sourceBoxes)
                            }
                        }.distinct(),
                    evidenceRegions = effectiveGroup.flatMap(RefinementRow::evidenceRegions).distinct(),
                    replacementHeightRatio = effectiveGroup.maxOf(RefinementRow::replacementHeightRatio),
                )
            }.sortedBy(RefinementRow::centerY)
    }

    /** 只保留短数字或分隔符小框，再以受限直线拟合逐行提取投注号码。 */
    private fun findDenseNumericRows(regions: List<PpOcrRecognizedRegion>): List<List<PpOcrRecognizedRegion>> {
        val preliminaryCandidates =
            regions.filter { region -> region.isShortNumericFragment }
        if (preliminaryCandidates.size < MINIMUM_ROW_FRAGMENT_COUNT) return emptyList()
        val typicalHeight =
            preliminaryCandidates
                .map { region -> region.sourceBox.averageHeight }
                .filter { height -> height > 0f }
                .sorted()
                .medianOrNull()
                ?: return emptyList()
        val candidates =
            preliminaryCandidates.filter { region ->
                region.sourceBox.averageHeight in
                    typicalHeight * MINIMUM_FRAGMENT_HEIGHT_RATIO..typicalHeight * MAXIMUM_FRAGMENT_HEIGHT_RATIO
            }
        if (candidates.size < MINIMUM_ROW_FRAGMENT_COUNT) return emptyList()
        val globalSlope = estimateGlobalTextSlope(regions)
        val remaining = candidates.toMutableList()
        val rows = mutableListOf<List<PpOcrRecognizedRegion>>()
        while (remaining.size >= MINIMUM_ROW_FRAGMENT_COUNT) {
            val best = findBestRow(remaining, typicalHeight, globalSlope) ?: break
            rows += best
            remaining.removeAll(best.toSet())
        }
        return rows.sortedBy { row -> row.map { it.sourceBox.center.y }.average() }
    }

    /** 枚举两点基线并选择支持号码格最多、横向跨度最大的一行。 */
    private fun findBestRow(
        candidates: List<PpOcrRecognizedRegion>,
        typicalHeight: Float,
        globalSlope: Float,
    ): List<PpOcrRecognizedRegion>? {
        var best: RowHypothesis? = null
        for (leftIndex in 0 until candidates.lastIndex) {
            val left = candidates[leftIndex].sourceBox.center
            for (rightIndex in leftIndex + 1 until candidates.size) {
                val right = candidates[rightIndex].sourceBox.center
                val deltaX = right.x - left.x
                if (abs(deltaX) < typicalHeight * MINIMUM_PAIR_HORIZONTAL_HEIGHT_RATIO) continue
                val slope = (right.y - left.y) / deltaX
                if (abs(slope) > MAXIMUM_ROW_SLOPE || abs(slope - globalSlope) > MAXIMUM_GLOBAL_SLOPE_DEVIATION) {
                    continue
                }
                val intercept = left.y - slope * left.x
                val support =
                    candidates.filter { candidate ->
                        candidate.sourceBox.distanceToLine(slope, intercept) <=
                            max(typicalHeight, candidate.sourceBox.averageHeight) * ROW_SUPPORT_HEIGHT_RATIO
                    }
                if (support.size < MINIMUM_ROW_FRAGMENT_COUNT) continue
                val numberTokenCount = support.sumOf { region -> extractTwoDigitTokens(region.line.text).size }
                if (numberTokenCount < MINIMUM_ROW_NUMBER_TOKEN_COUNT) continue
                val horizontalSpan =
                    support.maxOf { it.sourceBox.center.x } - support.minOf { it.sourceBox.center.x }
                if (horizontalSpan < typicalHeight * MINIMUM_ROW_SPAN_HEIGHT_RATIO) continue
                val maximumCornerDistance =
                    support
                        .flatMap { region -> region.sourceBox.points }
                        .maxOf { point -> point.distanceToLine(slope, intercept) }
                if (maximumCornerDistance > typicalHeight * MAXIMUM_CORNER_DISTANCE_HEIGHT_RATIO) continue
                val meanResidual =
                    support
                        .map { candidate -> candidate.sourceBox.distanceToLine(slope, intercept) }
                        .average()
                        .toFloat()
                val hypothesis = RowHypothesis(support, numberTokenCount, horizontalSpan, meanResidual)
                if (best == null || hypothesis.isBetterThan(best)) best = hypothesis
            }
        }
        return best?.regions
    }

    /** 从长横排文字框估计当前票面的统一文字方向，避免跨行对角线被当作投注行。 */
    private fun estimateGlobalTextSlope(regions: List<PpOcrRecognizedRegion>): Float =
        regions
            .mapNotNull { region ->
                val box = region.sourceBox
                val deltaX = box.topRight.x - box.topLeft.x
                val width = box.topLeft.distanceTo(box.topRight)
                val height = box.averageHeight
                if (abs(deltaX) < MINIMUM_DIRECTION_DELTA_X || width < height * MINIMUM_DIRECTION_WIDTH_HEIGHT_RATIO) {
                    null
                } else {
                    ((box.topRight.y - box.topLeft.y) / deltaX).takeIf { slope -> abs(slope) <= MAXIMUM_ROW_SLOPE }
                }
            }.sorted()
            .medianOrNull()
            ?: 0f

    /** 根据号码中心重新拟合基线，并使用共享横向证据生成完整投注行旋转矩形。 */
    private fun buildMergedRowBox(
        regions: List<PpOcrRecognizedRegion>,
        extentRegions: List<PpOcrRecognizedRegion>,
        slope: Float,
    ): SourceTextBox {
        val axisLength = sqrt(1f + slope * slope)
        val typicalHeight = regions.map { it.sourceBox.averageHeight }.sorted().medianOrNull() ?: 0f
        val centerShort =
            regions
                .map { region ->
                    val center = region.sourceBox.center
                    center.shortCoordinate(slope) / axisLength
                }.average()
                .toFloat()
        return buildMergedRowBoxAtPerpendicularCoordinate(
            extentRegions = extentRegions.ifEmpty { regions },
            slope = slope,
            perpendicularCoordinate = centerShort,
            typicalHeight = typicalHeight,
            confidence = regions.minOf { it.sourceBox.confidence },
        )
    }

    /** 使用直线方程中的短轴坐标和共享横向范围生成完整投注行旋转矩形。 */
    private fun buildMergedRowBoxAtCoordinate(
        extentRegions: List<PpOcrRecognizedRegion>,
        slope: Float,
        shortCoordinate: Float,
        typicalHeight: Float,
        confidence: Float,
    ): SourceTextBox =
        buildMergedRowBoxAtPerpendicularCoordinate(
            extentRegions = extentRegions,
            slope = slope,
            perpendicularCoordinate = shortCoordinate / sqrt(1f + slope * slope),
            typicalHeight = typicalHeight,
            confidence = confidence,
        )

    /** 使用单位短轴投影坐标构造旋转矩形，供拟合行和插值行共享。 */
    private fun buildMergedRowBoxAtPerpendicularCoordinate(
        extentRegions: List<PpOcrRecognizedRegion>,
        slope: Float,
        perpendicularCoordinate: Float,
        typicalHeight: Float,
        confidence: Float,
    ): SourceTextBox {
        val axisLength = sqrt(1f + slope * slope)
        val axisX = 1f / axisLength
        val axisY = slope / axisLength
        val perpendicularX = -axisY
        val perpendicularY = axisX
        val extentPoints = extentRegions.flatMap { region -> region.sourceBox.points }
        var minimumLong = extentPoints.minOf { point -> point.x * axisX + point.y * axisY }
        var maximumLong = extentPoints.maxOf { point -> point.x * axisX + point.y * axisY }
        val minimumShort = perpendicularCoordinate - typicalHeight * DENSE_HALF_HEIGHT_RATIO
        val maximumShort = perpendicularCoordinate + typicalHeight * DENSE_HALF_HEIGHT_RATIO
        minimumLong -= typicalHeight * LONG_AXIS_PADDING_RATIO
        maximumLong += typicalHeight * LONG_AXIS_PADDING_RATIO

        fun point(
            long: Float,
            short: Float,
        ): FloatPoint =
            FloatPoint(
                x = long * axisX + short * perpendicularX,
                y = long * axisY + short * perpendicularY,
            )

        return SourceTextBox(
            topLeft = point(minimumLong, minimumShort),
            topRight = point(maximumLong, minimumShort),
            bottomRight = point(maximumLong, maximumShort),
            bottomLeft = point(minimumLong, maximumShort),
            confidence = confidence,
        )
    }

    /** 使用最小二乘拟合号码格中心的斜率和截距。 */
    private fun fitBaseline(regions: List<PpOcrRecognizedRegion>): Pair<Float, Float> {
        val centers = regions.map { it.sourceBox.center }
        val meanX = centers.map(FloatPoint::x).average().toFloat()
        val meanY = centers.map(FloatPoint::y).average().toFloat()
        val varianceX = centers.sumOf { point -> ((point.x - meanX) * (point.x - meanX)).toDouble() }
        val covariance = centers.sumOf { point -> ((point.x - meanX) * (point.y - meanY)).toDouble() }
        val slope = if (varianceX > MINIMUM_REGRESSION_VARIANCE) (covariance / varianceX).toFloat() else 0f
        return slope to (meanY - slope * meanX)
    }

    /** 从所有图像候选中选择唯一合法结构，并拒绝候选冲突或覆盖不同的已有合法行。 */
    private fun selectReplacement(
        regions: List<PpOcrRecognizedRegion>,
        mergedLines: List<OcrTextLine>,
    ): OcrTextLine? {
        val allowLeadingRowDigit = regions.hasLeadingSingleDigitRowMarker
        val validCandidates =
            mergedLines.mapNotNull { line ->
                if ((line.confidence ?: 0f) < MINIMUM_MERGED_ROW_CONFIDENCE) return@mapNotNull null
                val directSignature = strictBetSignatures(line.text).singleOrNull()
                if (directSignature != null) return@mapNotNull directSignature to line
                if (!allowLeadingRowDigit) return@mapNotNull null
                val cleanedText = line.text.removingFirstDigitOrNull() ?: return@mapNotNull null
                strictBetSignatures(cleanedText).singleOrNull()?.let { signature ->
                    signature to line.copy(text = cleanedText)
                }
            }
        val mergedSignatures = validCandidates.map { it.first }.toSet()
        if (mergedSignatures.size != 1) return null
        val existingText =
            regions
                .sortedBy { region -> region.sourceBox.center.x }
                .joinToString(separator = " ") { region -> region.line.text }
        val existingSignatures = strictBetSignatures(existingText)
        if (existingSignatures.isNotEmpty() && existingSignatures != mergedSignatures) return null
        return validCandidates
            .filter { candidate -> candidate.first in mergedSignatures }
            .maxByOrNull { candidate -> candidate.second.confidence ?: 0f }
            ?.second
    }

    /** 在全部整行裁图无合法结果时，用基线上唯一合法的七个数字碎片生成规范行。 */
    private fun selectExistingReplacement(
        regions: List<PpOcrRecognizedRegion>,
        mergedLines: List<OcrTextLine>,
    ): OcrTextLine? {
        val mergedSignatures =
            mergedLines
                .filter { line -> (line.confidence ?: 0f) >= MINIMUM_MERGED_ROW_CONFIDENCE }
                .flatMap { line -> strictBetSignatures(line.text) }
                .toSet()
        if (mergedSignatures.isNotEmpty()) return null
        val ordered = regions.sortedBy { region -> region.sourceBox.center.x }
        val existingText = ordered.joinToString(separator = " ") { region -> region.line.text }
        val signature = strictBetSignatures(existingText).singleOrNull() ?: return null
        val confidences = ordered.map { region -> region.line.confidence ?: return null }
        val confidence = confidences.minOrNull() ?: return null
        if (confidence < MINIMUM_EXISTING_ROW_CONFIDENCE) return null
        val bounds =
            NormalizedBounds(
                left = ordered.minOf { region -> region.line.bounds.left },
                top = ordered.minOf { region -> region.line.bounds.top },
                right = ordered.maxOf { region -> region.line.bounds.right },
                bottom = ordered.maxOf { region -> region.line.bounds.bottom },
            )
        val multiplierSuffix =
            if (ordered.any { region -> region.compactText.isRowMultiplierMarker }) " x1" else ""
        return OcrTextLine(
            text = signature.canonicalText + multiplierSuffix,
            bounds = bounds,
            confidence = confidence,
        )
    }

    /** 判断最左侧号码之前是否存在只含一个数字的短投注行标。 */
    private val List<PpOcrRecognizedRegion>.hasLeadingSingleDigitRowMarker: Boolean
        get() {
            val ordered = sortedBy { region -> region.sourceBox.center.x }
            val firstNumberIndex =
                ordered.indexOfFirst { region ->
                    extractTwoDigitTokens(region.line.text).isNotEmpty()
                }
            if (firstNumberIndex <= 0) return false
            return ordered.take(firstNumberIndex).any { region ->
                val compact = region.compactText
                compact.length in 1..MAXIMUM_DEGRADED_ROW_MARKER_LENGTH &&
                    compact.count(Char::isDigit) == 1 &&
                    extractTwoDigitTokens(compact).isEmpty()
            }
        }

    /** 删除文本中首个数字，供旧框已证明行标粘连时重新执行严格号码校验。 */
    private fun String.removingFirstDigitOrNull(): String? {
        val firstDigitIndex = indexOfFirst(Char::isDigit)
        return if (firstDigitIndex < 0) null else removeRange(firstDigitIndex, firstDigitIndex + 1)
    }

    /** 判断当前单框结果是否只包含号码、序号或主次区分隔符。 */
    private val PpOcrRecognizedRegion.isShortNumericFragment: Boolean
        get() {
            val compactText = line.text.filterNot(Char::isWhitespace)
            return compactText.length in 1..MAXIMUM_NUMERIC_FRAGMENT_LENGTH &&
                compactText.any { character -> character.isDigit() || character in ROW_MARKER_CHARACTERS }
        }

    /** 判断当前区域是否可在唯一合法整行识别成功后被安全替换。 */
    private val PpOcrRecognizedRegion.isReplaceableBetRowFragment: Boolean
        get() =
            isShortNumericFragment || compactText.isLegacyRowMarker ||
                extractTwoDigitTokens(line.text).size >= MINIMUM_REPLACEABLE_NUMBER_TOKEN_COUNT

    /** 返回去除空白后的单框文本。 */
    private val PpOcrRecognizedRegion.compactText: String
        get() = line.text.filterNot(Char::isWhitespace)

    /** 判断单框是否包含行标、行尾倍数或前后区加号等投注区专属结构。 */
    private val PpOcrRecognizedRegion.isBettingScopeAnchor: Boolean
        get() {
            val compact = compactText
            val hasPlusStructure =
                compact.any { character -> character == '+' || character == '＋' } &&
                    compact.count(Char::isDigit) >= MINIMUM_BETTING_SCOPE_PLUS_DIGIT_COUNT
            return compact in CIRCLED_ROW_MARKERS ||
                compact.isRowMultiplierMarker ||
                BETTING_SCOPE_LEGACY_ROW_MARKER_REGEX.matches(compact) ||
                hasPlusStructure
        }

    /** 判断单字符文本是否可能是退化识别后的注序号。 */
    private val String.isSingleRowIndexDigit: Boolean
        get() = length == 1 && single() in '1'..'9'

    /** 判断文本是否以旧版双色球 A-E 行标开头。 */
    private val String.isLegacyRowMarker: Boolean
        get() = LEGACY_ROW_MARKER_REGEX.containsMatchIn(this)

    /** 判断文本是否为旧版双色球行尾重复的 `x1` 倍数标记。 */
    private val String.isRowMultiplierMarker: Boolean
        get() = LEGACY_ROW_MULTIPLIER_REGEX.matches(this)

    /** 提取文本中所有两位号码，并返回满足大乐透或双色球单式约束的唯一结构集合。 */
    private fun strictBetSignatures(text: String): Set<StrictBetSignature> {
        val numbers = extractTwoDigitTokens(text)
        if (numbers.size != TOTAL_BET_NUMBER_COUNT) return emptySet()
        return buildSet {
            if (
                numbers.take(SUPER_LOTTO_PRIMARY_COUNT).isStrictlyValid(SUPER_LOTTO_PRIMARY_RANGE) &&
                numbers.drop(SUPER_LOTTO_PRIMARY_COUNT).isStrictlyValid(SUPER_LOTTO_SECONDARY_RANGE)
            ) {
                add(StrictBetSignature(LotteryType.SUPER_LOTTO, numbers))
            }
            if (
                numbers.take(DOUBLE_COLOR_BALL_PRIMARY_COUNT).isStrictlyValid(DOUBLE_COLOR_BALL_PRIMARY_RANGE) &&
                numbers.drop(DOUBLE_COLOR_BALL_PRIMARY_COUNT).isStrictlyValid(DOUBLE_COLOR_BALL_SECONDARY_RANGE)
            ) {
                add(StrictBetSignature(LotteryType.DOUBLE_COLOR_BALL, numbers))
            }
        }
    }

    /** 从独立的偶数位数字串中按两个字符拆出彩票号码。 */
    private fun extractTwoDigitTokens(text: String): List<Int> =
        COMPACT_NUMBER_RUN_REGEX
            .findAll(text)
            .flatMap { match ->
                match.value
                    .takeIf { value -> value.length % TWO_DIGIT_NUMBER_LENGTH == 0 }
                    ?.chunked(TWO_DIGIT_NUMBER_LENGTH)
                    ?.asSequence()
                    ?: emptySequence()
            }.mapNotNull(String::toIntOrNull)
            .toList()

    /** 校验号码数量隐含的范围、唯一性和票面升序。 */
    private fun List<Int>.isStrictlyValid(range: IntRange): Boolean =
        all { number -> number in range } && distinct().size == size &&
            zipWithNext().all { (left, right) -> left < right }

    /** 计算旋转框中心。 */
    private val SourceTextBox.center: FloatPoint
        get() =
            FloatPoint(
                x = points.map(FloatPoint::x).average().toFloat(),
                y = points.map(FloatPoint::y).average().toFloat(),
            )

    /** 返回旋转框四个角。 */
    private val SourceTextBox.points: List<FloatPoint>
        get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** 返回旋转框左右边高度的平均值。 */
    private val SourceTextBox.averageHeight: Float
        get() = (topLeft.distanceTo(bottomLeft) + topRight.distanceTo(bottomRight)) / 2f

    /** 返回旋转框上下边宽度的平均值。 */
    private val SourceTextBox.averageWidth: Float
        get() = (topLeft.distanceTo(topRight) + bottomLeft.distanceTo(bottomRight)) / 2f

    /** 计算旋转框中心到基线的垂直距离。 */
    private fun SourceTextBox.distanceToLine(
        slope: Float,
        intercept: Float,
    ): Float = center.distanceToLine(slope, intercept)

    /** 计算一个原图坐标点到基线的垂直距离。 */
    private fun FloatPoint.distanceToLine(
        slope: Float,
        intercept: Float,
    ): Float = abs(slope * x - y + intercept) / sqrt(slope * slope + 1f)

    /** 返回点在 `y - slope * x` 短轴上的未归一化坐标。 */
    private fun FloatPoint.shortCoordinate(slope: Float): Float = y - slope * x

    /** 返回有序浮点列表的中位数。 */
    private fun List<Float>.medianOrNull(): Float? {
        if (isEmpty()) return null
        val middle = size / 2
        return if (size % 2 == 0) (this[middle - 1] + this[middle]) / 2f else this[middle]
    }

    /** 一条候选号码基线及其排序指标。 */
    private data class RowHypothesis(
        /** 当前基线覆盖的号码小框。 */
        val regions: List<PpOcrRecognizedRegion>,
        /** 小框中已识别的两位号码数量。 */
        val numberTokenCount: Int,
        /** 基线覆盖的横向像素跨度。 */
        val horizontalSpan: Float,
        /** 小框中心到基线的平均残差。 */
        val meanResidual: Float,
    ) {
        /** 按号码数、区域数、跨度和残差依次比较两个候选。 */
        fun isBetterThan(other: RowHypothesis): Boolean =
            when {
                numberTokenCount != other.numberTokenCount -> {
                    numberTokenCount > other.numberTokenCount
                }

                regions.size != other.regions.size -> {
                    regions.size > other.regions.size
                }

                abs(horizontalSpan - other.horizontalSpan) > FLOAT_COMPARISON_TOLERANCE -> {
                    horizontalSpan > other.horizontalSpan
                }

                else -> {
                    meanResidual < other.meanResidual
                }
            }
    }

    /** 一条已经通过领域结构约束的投注号码签名。 */
    private data class StrictBetSignature(
        /** 签名对应的彩种。 */
        val lotteryType: LotteryType,
        /** 保持主区和次区顺序的全部号码。 */
        val numbers: List<Int>,
    ) {
        /** 把已严格验证的号码写成带明确主次区分隔符的规范文本。 */
        val canonicalText: String
            get() {
                val primaryCount =
                    when (lotteryType) {
                        LotteryType.SUPER_LOTTO -> SUPER_LOTTO_PRIMARY_COUNT
                        LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_PRIMARY_COUNT
                    }
                return numbers.take(primaryCount).joinToString(separator = " ") { number -> number.paddedNumber } +
                    " + " +
                    numbers.drop(primaryCount).joinToString(separator = " ") { number -> number.paddedNumber }
            }
    }

    /** 把彩票号码补齐为两位十进制文本。 */
    private val Int.paddedNumber: String
        get() = toString().padStart(TWO_DIGIT_NUMBER_LENGTH, '0')

    /** 投注行定位来源优先级。 */
    private enum class RefinementSourcePriority(
        /** 合并同一基线时使用的稳定排序权重。 */
        val weight: Int,
    ) {
        /** 由相邻可靠基线的大间距推导，只在目标处仍有图片短框证据时使用。 */
        INTERPOLATED(-3),

        /** 由投注区内短框的纯几何基线得到，只用于补充更强定位策略。 */
        GEOMETRIC(-2),

        /** 由单个长号码框补充，只在更强定位策略缺失时使用。 */
        SPARSE(-1),

        /** 仅由号码格密集拟合得到。 */
        DENSE(0),

        /** 由重复行标或倍数列锚定，通常拥有更完整的横向范围。 */
        ANCHORED(1),
        ;

        /** 标记不得污染强定位候选、且重复号码必须转人工确认的弱来源。 */
        val isWeakGeometry: Boolean
            get() = this == INTERPOLATED || this == GEOMETRIC
    }

    /** 一组已经按优先级执行的裁图候选及其唯一合法替换结果。 */
    private data class CandidateRecognition(
        /** 所有已实际执行识别的文字候选。 */
        val candidates: List<OcrTextLine>,
        /** 已通过结构与裁图共识校验的替换结果。 */
        val replacement: OcrTextLine?,
    ) {
        /** 无需执行某层候选时复用的空结果。 */
        companion object {
            /** 不含候选和替换结果的共享实例。 */
            val EMPTY = CandidateRecognition(emptyList(), null)
        }
    }

    /** 一条已经通过候选一致性校验、等待最终来源去重的替换结果。 */
    private data class SelectedReplacement(
        /** 产生该结果的行定位与来源强度。 */
        val row: RefinementRow,
        /** 唯一合法的规范投注文字行。 */
        val line: OcrTextLine,
        /** 替换成功后应移除的原始碎片。 */
        val removedRegions: List<PpOcrRecognizedRegion>,
    )

    /** 一条等待整行识别的裁图、证据与旧碎片清理基线。 */
    private data class RefinementRow(
        /** 回到原图执行整行识别的候选旋转框。 */
        val sourceBoxes: List<SourceTextBox>,
        /** 强紧裁图无唯一合法结果时才单独识别的几何紧裁图。 */
        val supplementalSourceBoxes: List<SourceTextBox> = emptyList(),
        /** 当前行已有的单框文字证据。 */
        val evidenceRegions: List<PpOcrRecognizedRegion>,
        /** 清理旧号码碎片使用的基线斜率。 */
        val baselineSlope: Float,
        /** 清理旧号码碎片使用的基线截距。 */
        val baselineIntercept: Float,
        /** 当前票面号码格的典型像素高度。 */
        val typicalHeight: Float,
        /** 旧号码中心允许偏离基线的字符高度比例。 */
        val replacementHeightRatio: Float,
        /** 当前行最可靠的几何定位来源。 */
        val sourcePriority: RefinementSourcePriority,
    ) {
        init {
            require(sourceBoxes.isNotEmpty()) { "投注行至少需要一个原图裁切框" }
        }

        /** 使用首选裁图中心建立投注行阅读顺序和去重基准。 */
        val centerY: Float
            get() = sourceBoxes.first().center.y

        /** 两层紧裁图都失败后，返回扩高和沿短轴轻移的受限备用裁图。 */
        val fallbackSourceBoxes: List<SourceTextBox>
            get() {
                val boxes =
                    (sourceBoxes + supplementalSourceBoxes)
                        .distinct()
                        .sortedByDescending { box -> box.averageHeight }
                return buildList {
                    boxes.mapTo(this) { box ->
                        box.adjustedShortAxis(shift = typicalHeight * FALLBACK_CROP_DOWNWARD_SHIFT_RATIO)
                    }
                    boxes.mapTo(this) { box ->
                        box.adjustedShortAxis(expansion = typicalHeight * FALLBACK_CROP_EXPANSION_RATIO)
                    }
                    boxes.mapTo(this) { box ->
                        box.adjustedShortAxis(shift = -typicalHeight * FALLBACK_CROP_UPWARD_SHIFT_RATIO)
                    }
                }.distinct()
            }

        /** 判断一个旧号码碎片是否属于当前整行替换范围。 */
        fun containsForReplacement(region: PpOcrRecognizedRegion): Boolean {
            val center = region.sourceBox.center
            val minimumX = sourceBoxes.minOf { box -> box.points.minOf(FloatPoint::x) }
            val maximumX = sourceBoxes.maxOf { box -> box.points.maxOf(FloatPoint::x) }
            return center.x in minimumX..maximumX &&
                center.distanceToLine(baselineSlope, baselineIntercept) <= typicalHeight * replacementHeightRatio
        }
    }

    /** 沿旋转框短轴扩展或平移裁图，保持原文字方向和置信度。 */
    private fun SourceTextBox.adjustedShortAxis(
        expansion: Float = 0f,
        shift: Float = 0f,
    ): SourceTextBox {
        val longX = topRight.x - topLeft.x
        val longY = topRight.y - topLeft.y
        val longLength = sqrt(longX * longX + longY * longY).coerceAtLeast(MINIMUM_DIRECTION_DELTA_X)
        var shortX = -longY / longLength
        var shortY = longX / longLength
        val bottomDirectionX = bottomLeft.x - topLeft.x
        val bottomDirectionY = bottomLeft.y - topLeft.y
        if (shortX * bottomDirectionX + shortY * bottomDirectionY < 0f) {
            shortX = -shortX
            shortY = -shortY
        }

        fun moved(
            point: FloatPoint,
            distance: Float,
        ): FloatPoint = FloatPoint(point.x + shortX * distance, point.y + shortY * distance)

        return SourceTextBox(
            topLeft = moved(topLeft, shift - expansion),
            topRight = moved(topRight, shift - expansion),
            bottomRight = moved(bottomRight, shift + expansion),
            bottomLeft = moved(bottomLeft, shift + expansion),
            confidence = confidence,
        )
    }

    /** 形成一条候选投注行至少需要的小框数量。 */
    private const val MINIMUM_ROW_FRAGMENT_COUNT = 3

    /** 启用纵向投注区边界至少需要的不同锚点行数量。 */
    private const val MINIMUM_BETTING_SCOPE_ANCHOR_ROW_COUNT = 3

    /** 多条锚点纵向跨度至少需要覆盖的典型字符高度倍数。 */
    private const val MINIMUM_BETTING_SCOPE_ANCHOR_SPAN_RATIO = 1.5f

    /** 同一物理行的多个结构锚点允许相差的典型字符高度比例。 */
    private const val MAXIMUM_BETTING_SCOPE_ANCHOR_CLUSTER_GAP_RATIO = 0.6f

    /** 投注区首尾锚点之外保留的典型字符高度比例。 */
    private const val BETTING_SCOPE_VERTICAL_PADDING_RATIO = 0.75f

    /** 带前后区加号的锚点至少应包含的数字位数。 */
    private const val MINIMUM_BETTING_SCOPE_PLUS_DIGIT_COUNT = 3

    /** 使用注序号布局至少需要识别到的连续行数。 */
    private const val MINIMUM_INDEXED_ROW_COUNT = 2

    /** 候选投注行至少需要已识别的两位号码数量。 */
    private const val MINIMUM_ROW_NUMBER_TOKEN_COUNT = 2

    /** 单个稀疏长框至少需要保留的两位号码数量。 */
    private const val MINIMUM_SPARSE_ROW_NUMBER_TOKEN_COUNT = 2

    /** 退化宽框只有一个完整两位数时至少还需包含的总数字位数。 */
    private const val MINIMUM_DEGRADED_SPARSE_DIGIT_COUNT = 3

    /** 几何补行至少需要两条已确认的密集投注基线来限制纵向范围。 */
    private const val MINIMUM_GEOMETRIC_SCOPE_ROW_COUNT = 2

    /** 判断基线间距异常至少需要的密集投注行数量。 */
    private const val MINIMUM_INTERPOLATION_SCOPE_ROW_COUNT = 4

    /** 几何补行至少需要两个仍含数字的短框，避免纯文字行进入识别。 */
    private const val MINIMUM_GEOMETRIC_DIGIT_FRAGMENT_COUNT = 2

    /** 长框至少包含两个两位号码时才允许作为投注行旧证据被替换。 */
    private const val MINIMUM_REPLACEABLE_NUMBER_TOKEN_COUNT = 2

    /** 单个号码、序号或分隔符候选允许的最大字符数。 */
    private const val MAXIMUM_NUMERIC_FRAGMENT_LENGTH = 4

    /** 几何补行允许的短框最大字符数，覆盖受损号码与行尾倍数片段。 */
    private const val MAXIMUM_GEOMETRIC_FRAGMENT_LENGTH = 8

    /** 退化行标允许包含的最大字符数，覆盖 `5`、`A5` 和 `A.5`。 */
    private const val MAXIMUM_DEGRADED_ROW_MARKER_LENGTH = 3

    /** 拟合基线的两个锚点至少相隔多少倍典型字符高度。 */
    private const val MINIMUM_PAIR_HORIZONTAL_HEIGHT_RATIO = 3f

    /** 三个相邻号码框形成候选行时，至少横跨多少倍典型字符高度。 */
    private const val MINIMUM_ROW_SPAN_HEIGHT_RATIO = 3f

    /** 稀疏长框相对自身高度至少需要的宽度比例。 */
    private const val MINIMUM_SPARSE_ROW_WIDTH_HEIGHT_RATIO = 2.5f

    /** 只剩零散数字的退化长框参与稀疏定位所需的更严格宽高比。 */
    private const val MINIMUM_SEVERELY_DEGRADED_SPARSE_WIDTH_HEIGHT_RATIO = 3.5f

    /** 几何补行拒绝相对高度过长的标题、摘要和流水号框。 */
    private const val MAXIMUM_GEOMETRIC_FRAGMENT_WIDTH_HEIGHT_RATIO = 5f

    /** 几何短框归入同一基线时允许的典型字符高度比例。 */
    private const val GEOMETRIC_ROW_CLUSTER_HEIGHT_RATIO = 0.32f

    /** 相对典型行距超过该比例时才允许尝试补一个中间基线。 */
    private const val MINIMUM_INTERPOLATED_GAP_RATIO = 1.28

    /** 拒绝跨度过大的基线间隔，避免跨越不同票面区块。 */
    private const val MAXIMUM_INTERPOLATED_GAP_RATIO = 2.25

    /** 插值目标收集原图短框时允许的字符高度比例。 */
    private const val INTERPOLATED_EVIDENCE_HEIGHT_RATIO = 0.38

    /** 票面基线允许的最大斜率绝对值。 */
    private const val MAXIMUM_ROW_SLOPE = 0.12f

    /** 投注行斜率相对票面全局文字方向允许的最大偏差。 */
    private const val MAXIMUM_GLOBAL_SLOPE_DEVIATION = 0.035f

    /** 判断号码格属于同一基线的字符高度比例。 */
    private const val ROW_SUPPORT_HEIGHT_RATIO = 0.3f

    /** 候选号码小框相对典型高度允许的最小比例。 */
    private const val MINIMUM_FRAGMENT_HEIGHT_RATIO = 0.55f

    /** 候选号码小框相对典型高度允许的最大比例。 */
    private const val MAXIMUM_FRAGMENT_HEIGHT_RATIO = 1.55f

    /** 稀疏长框只在可靠密集投注区上下有限范围内补充候选。 */
    private const val SPARSE_VERTICAL_SCOPE_PADDING_RATIO = 3f

    /** 同行文字角点到拟合基线最多允许的典型字符高度比例。 */
    private const val MAXIMUM_CORNER_DISTANCE_HEIGHT_RATIO = 0.9f

    /** 清理旧号码碎片时允许的基线距离比例。 */
    private const val REPLACED_FRAGMENT_HEIGHT_RATIO = 0.45f

    /** 注序号候选允许偏离明确圆圈序号列的字符高度比例。 */
    private const val MAXIMUM_MARKER_COLUMN_OFFSET_RATIO = 0.8f

    /** 同一注序号重复候选允许的纵向距离比例。 */
    private const val MAXIMUM_DUPLICATE_MARKER_GAP_RATIO = 0.35f

    /** 不同定位策略视为同一投注行的最大中心距离比例。 */
    private const val MAXIMUM_REFINEMENT_ROW_CENTER_GAP_RATIO = 0.48f

    /** 锚点与另一强行合并时使用的更严格中心距离，避免吞掉紧邻下一注。 */
    private const val MAXIMUM_ANCHORED_STRONG_ROW_CENTER_GAP_RATIO = 0.22f

    /** 弱候选重复号码转人工时，邻近强行允许的最大字符高度倍数。 */
    private const val MAXIMUM_WEAK_DUPLICATE_NEIGHBOR_GAP_RATIO = 1.75f

    /** 相同合法号码只有在中心极近时才视为同一物理投注行。 */
    private const val MAXIMUM_IDENTICAL_REPLACEMENT_CENTER_GAP_RATIO = 0.5f

    /** 最终裁图重叠去重时允许的最大中心距离比例。 */
    private const val MAXIMUM_OVERLAPPING_REPLACEMENT_CENTER_GAP_RATIO = 1.1f

    /** 异号裁图只有中心距离足够近时才允许择优，避免误删紧密排列的相邻真实投注。 */
    private const val MAXIMUM_CONFLICTING_REPLACEMENT_CENTER_GAP_RATIO = 0.65f

    /** 最终裁图至少重叠较矮框的该比例才视为同一物理行。 */
    private const val MINIMUM_REPLACEMENT_VERTICAL_OVERLAP_RATIO = 0.5f

    /** 重叠异号候选至少包含该数量的原图碎片，才视为证据完整的一方。 */
    private const val MINIMUM_RELIABLE_REPLACEMENT_EVIDENCE_COUNT = 7

    /** 重叠异号候选最多只有该数量的原图碎片时，才视为明显退化的一方。 */
    private const val MAXIMUM_DEGRADED_REPLACEMENT_EVIDENCE_COUNT = 4

    /** 相邻注序号最小行距比例。 */
    private const val MINIMUM_MARKER_ROW_GAP_RATIO = 0.55f

    /** 相邻注序号最大行距比例。 */
    private const val MAXIMUM_MARKER_ROW_GAP_RATIO = 1.8f

    /** 注序号行上下范围额外包含的字符高度比例。 */
    private const val INDEXED_VERTICAL_SCOPE_PADDING_RATIO = 1.5f

    /** 整行中心相对注序号中心向上修正的字符高度比例。 */
    private const val INDEXED_CENTER_UPWARD_OFFSET_RATIO = 0f

    /** 注序号锚定裁图的半高相对字符高度比例。 */
    private const val INDEXED_HALF_HEIGHT_RATIO = 0.4f

    /** 注序号锚定行清理旧碎片允许的基线距离比例。 */
    private const val INDEXED_REPLACEMENT_HEIGHT_RATIO = 0.45f

    /** 估计文字方向时要求的最小横坐标差。 */
    private const val MINIMUM_DIRECTION_DELTA_X = 1f

    /** 只有足够长的横排框才参与全局文字方向估计。 */
    private const val MINIMUM_DIRECTION_WIDTH_HEIGHT_RATIO = 2f

    /** 整行裁切在文字方向两端追加的字符高度比例。 */
    private const val LONG_AXIS_PADDING_RATIO = 0.45f

    /** 同一裁图形成号码共识所需的最少图像版本数。 */
    private const val MINIMUM_CROP_CONSENSUS_COUNT = 2

    /** 单条候选行最多执行的英文模型裁图数量。 */
    private const val MAXIMUM_LATIN_CROP_COUNT = 3

    /** 密集基线裁图上下各保留的典型检测框高度比例。 */
    private const val DENSE_HALF_HEIGHT_RATIO = 0.4f

    /** 整行识别可替换原小框结果的最低置信度。 */
    private const val MINIMUM_MERGED_ROW_CONFIDENCE = 0.55f

    /** 数字碎片规范化为整行时要求的最低单框置信度。 */
    private const val MINIMUM_EXISTING_ROW_CONFIDENCE = 0.55f

    /** 备用裁图在上下两侧各扩展的典型字符高度比例。 */
    private const val FALLBACK_CROP_EXPANSION_RATIO = 0.12f

    /** 检测框中心偏上时，备用裁图沿短轴向下移动的典型字符高度比例。 */
    private const val FALLBACK_CROP_DOWNWARD_SHIFT_RATIO = 0.24f

    /** 检测框中心偏下时，备用裁图沿短轴向上移动的典型字符高度比例。 */
    private const val FALLBACK_CROP_UPWARD_SHIFT_RATIO = 0.12f

    /** 防止基线回归横坐标方差接近零。 */
    private const val MINIMUM_REGRESSION_VARIANCE = 0.000_001

    /** 比较两个浮点跨度时忽略的微小误差。 */
    private const val FLOAT_COMPARISON_TOLERANCE = 0.001f

    /** 每个彩票号码固定包含的十进制字符数。 */
    private const val TWO_DIGIT_NUMBER_LENGTH = 2

    /** 支持的两种单式票都固定包含七个号码。 */
    private const val TOTAL_BET_NUMBER_COUNT = 7

    /** 大乐透前区号码数量。 */
    private const val SUPER_LOTTO_PRIMARY_COUNT = 5

    /** 双色球红球号码数量。 */
    private const val DOUBLE_COLOR_BALL_PRIMARY_COUNT = 6

    /** 大乐透前区合法范围。 */
    private val SUPER_LOTTO_PRIMARY_RANGE = 1..35

    /** 大乐透后区合法范围。 */
    private val SUPER_LOTTO_SECONDARY_RANGE = 1..12

    /** 双色球红球合法范围。 */
    private val DOUBLE_COLOR_BALL_PRIMARY_RANGE = 1..33

    /** 双色球蓝球合法范围。 */
    private val DOUBLE_COLOR_BALL_SECONDARY_RANGE = 1..16

    /** 号码小框允许出现的投注行序号或分隔符。 */
    private val ROW_MARKER_CHARACTERS =
        setOf('+', '＋', '①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩')

    /** 热敏票常见的带圆圈注序号。 */
    private val CIRCLED_ROW_MARKERS = setOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")

    /** 旧版双色球 A-E 行标，可容忍常见中英文标点或标点丢失。 */
    private val LEGACY_ROW_MARKER_REGEX = Regex("^[A-E](?:[.:：．。])?(?=\\d|$)")

    /** 投注区边界只接受独立或最多粘连一个数字的 A-E 行标。 */
    private val BETTING_SCOPE_LEGACY_ROW_MARKER_REGEX = Regex("^[A-E](?:[.:：．。])?\\d?$")

    /** 旧版双色球行尾一倍标记，可容忍数字一被识别为小写字母或大写字母。 */
    private val LEGACY_ROW_MULTIPLIER_REGEX = Regex("^[xX×][1lI]$")

    /** 匹配不属于更长流水号的偶数位号码串。 */
    private val COMPACT_NUMBER_RUN_REGEX = Regex("(?<!\\d)\\d{2,14}(?!\\d)")
}
