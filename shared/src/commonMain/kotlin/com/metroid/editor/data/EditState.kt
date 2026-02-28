package com.metroid.editor.data

import kotlinx.serialization.Serializable

@Serializable
data class MetEditProject(
    val version: Int = 1,
    val romPath: String = "",
    val romChecksum: String = "",
    val lastArea: Int = 0,
    val lastRoom: Int = -1,
    val rooms: MutableMap<String, RoomEdits> = mutableMapOf(),
    val window: WindowState = WindowState()
) {
    companion object {
        const val EXTENSION = "medit"
    }
}

@Serializable
data class WindowState(
    val width: Int = 1400,
    val height: Int = 900,
    val x: Int = -1,
    val y: Int = -1,
    val zoom: Float = 2f
)

/**
 * All edits for a single room, keyed by "areaId:roomHex" in the project map.
 * Stores the final grid diff — what changed from the original ROM.
 */
@Serializable
data class RoomEdits(
    val macroEdits: MutableList<MacroEdit> = mutableListOf(),
    val enemyChanges: MutableList<EnemyChange> = mutableListOf(),
    val doorChanges: MutableList<DoorChange> = mutableListOf()
) {
    val isEmpty: Boolean get() = macroEdits.isEmpty() && enemyChanges.isEmpty() && doorChanges.isEmpty()
}

/**
 * A single macro change: the position and the new macro index + palette.
 * We store the final state (not undo history) so the file is compact.
 */
@Serializable
data class MacroEdit(
    val x: Int,
    val y: Int,
    val macroIndex: Int,
    val palette: Int
)

@Serializable
data class EnemyChange(
    val action: ChangeAction,
    val slot: Int = 0,
    val type: Int = 0,
    val posY: Int = 0,
    val posX: Int = 0
)

@Serializable
data class DoorChange(
    val action: ChangeAction,
    val index: Int = 0,
    val info: Int = 0,
    val side: Int = 0
)

@Serializable
enum class ChangeAction { ADD, REMOVE, MODIFY }

fun roomKey(area: Area, roomNumber: Int): String = "${area.id}:${"%02X".format(roomNumber)}"
