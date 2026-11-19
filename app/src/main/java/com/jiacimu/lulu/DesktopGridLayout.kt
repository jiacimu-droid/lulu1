package com.jiacimu.lulu

internal const val DESKTOP_COLUMNS = 4
internal const val DESKTOP_ROWS = 7
internal const val SIX_ROW_DESKTOP_ROWS = 6
internal const val DESKTOP_SLOT_COUNT = DESKTOP_COLUMNS * DESKTOP_ROWS
internal const val LEGACY_DESKTOP_ROWS = 8

internal data class DesktopGridCell(val row: Int, val column: Int)

/** Integral pixels keep the drawn cells, hit targets and drag positions on the same grid. */
internal data class DesktopGridGeometry(val cellPx: Int, val leftPx: Int, val topPx: Int) {
    fun x(column: Int): Int = leftPx + column * cellPx
    fun y(row: Int): Int = topPx + row * cellPx

    fun cellAt(pointerX: Float, pointerY: Float): DesktopGridCell? {
        if (cellPx == 0 || pointerX < leftPx || pointerX >= x(DESKTOP_COLUMNS) ||
            pointerY < topPx || pointerY >= y(DESKTOP_ROWS)) return null
        return DesktopGridCell(
            row = ((pointerY - topPx) / cellPx).toInt(),
            column = ((pointerX - leftPx) / cellPx).toInt(),
        )
    }

    companion object {
        fun fit(widthPx: Int, heightPx: Int): DesktopGridGeometry {
            val cell = minOf(widthPx / DESKTOP_COLUMNS, heightPx / DESKTOP_ROWS)
            return DesktopGridGeometry(cell, (widthPx - cell * DESKTOP_COLUMNS) / 2,
                (heightPx - cell * DESKTOP_ROWS) / 2)
        }
    }
}

internal fun desktopPageStart(page: Int): Int = page.coerceAtLeast(0) * DESKTOP_SLOT_COUNT
internal fun desktopPageForSlot(slot: Int): Int = slot.coerceAtLeast(0) / DESKTOP_SLOT_COUNT
internal fun desktopSlotIndex(page: Int, row: Int, col: Int): Int =
    desktopPageStart(page) + row * DESKTOP_COLUMNS + col

internal fun desktopStoredPageCount(slotCount: Int): Int =
    maxOf(1, (slotCount + DESKTOP_SLOT_COUNT - 1) / DESKTOP_SLOT_COUNT)

internal fun desktopAllocatedPageCount(neededSlots: Int): Int =
    maxOf(2, desktopPageForSlot(maxOf(neededSlots - 1, 0)) + 2)

internal fun desktopMoveLauncher(slots: List<String?>, from: Int, to: Int): List<String?> {
    if (from !in slots.indices || to !in slots.indices || from == to || slots[from] == null) return slots
    val updated = slots.toMutableList()
    updated[from] = slots[to]
    updated[to] = slots[from]
    val wanted = desktopPageStart(desktopAllocatedPageCount(updated.indexOfLast { it != null } + 1))
    return List(wanted) { updated.getOrNull(it) }
}

internal fun readDesktopSlots(raw: String?): List<String?> =
    raw?.split('|')?.map { it.takeIf(String::isNotBlank) }.orEmpty()

internal data class DesktopGridLayout(val slots: List<String?>, val widgets: List<LuluDesktopWidget>)

/** Keep every legal cell. Reposition overflow without overwriting an app or changing widget IDs. */
internal fun migrateDesktopGrid(
    oldSlots: List<String?>,
    oldWidgets: List<LuluDesktopWidget>,
    version: Int,
    launcherIds: List<String>,
): DesktopGridLayout {
    val legacyPageSize = when (version) {
        1 -> 16
        4 -> DESKTOP_COLUMNS * SIX_ROW_DESKTOP_ROWS
        else -> DESKTOP_COLUMNS * LEGACY_DESKTOP_ROWS
    }
    val slots = mutableListOf<String?>()
    val known = launcherIds.toSet()
    val placedIds = mutableSetOf<String>()
    val overflowApps = mutableListOf<Pair<Int, String>>()
    // Skip the two original home widgets when converting row numbers from pre-widget desktops.
    oldSlots.forEachIndexed { index, id ->
        if (id == null || id !in known || !placedIds.add(id)) return@forEachIndexed
        val mixedHome = version == 2 && index < 16
        val position = if (version == 2 && !mixedHome) index - 16 else index
        val page = when {
            mixedHome -> 0
            version == 2 -> 1 + position / legacyPageSize
            else -> position / legacyPageSize
        }
        val cell = position % legacyPageSize
        val row = cell / DESKTOP_COLUMNS + if (version < 3 && page == 0) 4 else 0
        val col = cell % DESKTOP_COLUMNS
        if (row < DESKTOP_ROWS) {
            val target = desktopSlotIndex(page, row, col)
            while (slots.size <= target) slots.add(null)
            slots[target] = id
        } else {
            overflowApps.add(page to id)
        }
    }

    val widgets = mutableListOf<LuluDesktopWidget>()
    val overflowWidgets = mutableListOf<LuluDesktopWidget>()
    oldWidgets.forEach { widget ->
        val resized = widget.copy(rows = widget.rows.coerceAtMost(DESKTOP_ROWS))
        if (luluWidgetFits(resized, widgets, slots)) widgets.add(resized)
        else overflowWidgets.add(resized)
    }
    val spillPage = maxOf(
        desktopPageForSlot(slots.indexOfLast { it != null }),
        oldWidgets.maxOfOrNull { it.page } ?: 0,
        overflowApps.maxOfOrNull { it.first } ?: 0,
    ) + 1
    overflowWidgets.forEach { widget ->
        val nearestRow = widget.row.coerceAtMost(DESKTOP_ROWS - widget.rows)
        val nearer = (0..DESKTOP_ROWS - widget.rows).sortedBy { kotlin.math.abs(it - nearestRow) }
            .map { row -> widget.copy(row = row) }
            .firstOrNull { luluWidgetFits(it, widgets, slots) }
        val relocated = nearer ?: generateSequence(spillPage) { it + 1 }
            .flatMap { page ->
                (0..DESKTOP_ROWS - widget.rows).asSequence().map { row ->
                    widget.copy(page = page, row = row)
                }
            }
            .first { luluWidgetFits(it, widgets, slots) }
        widgets.add(relocated)
    }

    val blocked = widgetDesktopCells(widgets)
    overflowApps.forEach { (page, id) ->
        val samePage = (desktopPageStart(page) until desktopPageStart(page + 1)).firstOrNull { slot ->
            slots.getOrNull(slot) == null && slot !in blocked
        }
        val destination = samePage ?: generateSequence(desktopPageStart(spillPage)) { it + 1 }
            .first { slots.getOrNull(it) == null && it !in blocked }
        while (slots.size <= destination) slots.add(null)
        slots[destination] = id
    }
    if (version == 4) {
        val storedPages = (oldSlots.size + legacyPageSize - 1) / legacyPageSize
        while (slots.size < desktopPageStart(storedPages)) slots.add(null)
    }
    return DesktopGridLayout(completeDesktopSlots(slots, widgets, launcherIds), widgets)
}

internal fun completeDesktopSlots(
    existing: List<String?>,
    widgets: List<LuluDesktopWidget>,
    launcherIds: List<String>,
): List<String?> {
    val blocked = widgetDesktopCells(widgets)
    val slots = existing.toMutableList()
    val present = slots.filterNotNull().toSet()
    var next = 0
    launcherIds.filterNot { it in present }.forEach { id ->
        while (slots.getOrNull(next) != null || next in blocked) next++
        while (slots.size <= next) slots.add(null)
        slots[next++] = id
    }
    val lastOccupied = maxOf(slots.indexOfLast { it != null }, blocked.maxOrNull() ?: -1)
    val size = maxOf(existing.size, desktopPageStart(desktopAllocatedPageCount(lastOccupied + 1)))
    return List(size) { slots.getOrNull(it) }
}

private fun widgetDesktopCells(widgets: List<LuluDesktopWidget>): Set<Int> = buildSet {
    for (widget in widgets) {
        for (row in widget.row until widget.row + widget.rows) {
            for (col in widget.col until widget.col + widget.columns) {
                add(desktopSlotIndex(widget.page, row, col))
            }
        }
    }
}
