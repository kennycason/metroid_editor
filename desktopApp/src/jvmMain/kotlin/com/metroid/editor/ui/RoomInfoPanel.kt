package com.metroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metroid.editor.data.MetroidNames
import com.metroid.editor.data.Room
import com.metroid.editor.rom.MetroidRomData
import com.metroid.editor.rom.RoomEncoder

@Composable
fun RoomInfoPanel(
    room: Room,
    metroidData: MetroidRomData,
    spaceBudget: RoomEncoder.SpaceBudget? = null,
    onNavigateToRoom: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val T = EditorTheme
    val scrollState = rememberScrollState()

    Surface(color = T.panelBg, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp)
        ) {
            Text(room.displayName, style = MaterialTheme.typography.titleSmall, color = T.textPrimary)

            Spacer(Modifier.height(12.dp))

            InfoSection("Room Properties") {
                InfoRow("Room #", "$%02X".format(room.roomNumber))
                InfoRow("Area", room.area.displayName)
                InfoRow("Palette", "${room.palette}")
                InfoRow("ROM Offset", "$%06X".format(room.romOffset))
                InfoRow("Data Size", "${room.rawData.size} bytes")

                val neighbors = metroidData.findRoomNeighbors(room.area, room.roomNumber)
                if (neighbors != null) {
                    Spacer(Modifier.height(4.dp))
                    InfoRow("Map Pos", "(${neighbors.mapX}, ${neighbors.mapY})")

                    val navCallback = onNavigateToRoom
                    if (navCallback != null) {
                        val links = listOfNotNull(
                            neighbors.left?.let { "\u2190" to it },
                            neighbors.right?.let { "\u2192" to it },
                            neighbors.up?.let { "\u2191" to it },
                            neighbors.down?.let { "\u2193" to it }
                        )
                        if (links.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Neighbors", fontSize = 11.sp, color = T.textSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for ((arrow, roomNum) in links) {
                                        Text(
                                            "$arrow \$${"%02X".format(roomNum)}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = T.accent,
                                            modifier = Modifier.clickable { navCallback(roomNum) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (spaceBudget != null) {
                Spacer(Modifier.height(12.dp))

                val pct = spaceBudget.percentUsed
                val barColor = when {
                    pct >= 95 -> T.errorRed
                    pct >= 80 -> T.warningOrange
                    else -> T.successGreen
                }

                InfoSection("Bank Space (${room.area.displayName})") {
                    InfoRow("Tiles", "${spaceBudget.tilesUsed}")
                    InfoRow("Rooms", "${spaceBudget.roomDataBytes} bytes")
                    InfoRow("Structs", "${spaceBudget.structDataBytes} bytes")
                    InfoRow("Ptrs", "${spaceBudget.ptrTableBytes} bytes")
                    InfoRow("Total", "${spaceBudget.totalUsed} / ${spaceBudget.totalAvailable}")
                    InfoRow("Bank free", "${spaceBudget.freeSpaceInBank} bytes")

                    Spacer(Modifier.height(4.dp))

                    Surface(
                        color = T.surfaceDim,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Surface(
                                color = barColor,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = (pct / 100f).coerceIn(0f, 1f))
                            ) {}
                        }
                    }

                    Text(
                        "${pct}% used",
                        fontSize = 10.sp,
                        color = barColor,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Objects (${room.objects.size})") {
                room.objects.forEachIndexed { idx, obj ->
                    val struct = metroidData.readStructure(room.area, obj.structIndex)
                    val macroCount = struct?.rows?.sumOf { it.macroIndices.size } ?: 0
                    val rowCount = struct?.rows?.size ?: 0
                    val sizeDesc = "${rowCount}r\u00D7${macroCount}m"
                    val posDesc = "at (${obj.posX},${obj.posY})"

                    // Guess a role based on size/position
                    val role = when {
                        rowCount >= 3 && macroCount >= 12 -> "Ceiling/Floor"
                        rowCount >= 4 && macroCount <= 4 -> "Column"
                        rowCount == 1 && macroCount >= 6 -> "Platform"
                        rowCount == 2 && macroCount >= 6 -> "Floor"
                        macroCount <= 2 && rowCount <= 2 -> "Detail"
                        else -> "Structure"
                    }

                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("$role $posDesc", fontSize = 11.sp, color = T.textPrimary)
                            Text("pal ${obj.palette}", fontSize = 10.sp, color = T.textMuted)
                        }
                        Text(
                            "Struct \$${"%02X".format(obj.structIndex)} ($sizeDesc)",
                            fontSize = 10.sp, color = T.textMuted
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Enemies (${room.enemies.size})") {
                room.enemies.forEachIndexed { idx, enemy ->
                    val name = MetroidNames.enemyName(enemy.type)
                    val color = if (MetroidNames.isItem(enemy.type)) T.sampleGreen else T.errorRed
                    InfoRowColored(
                        "Enemy $idx",
                        "$name (${"$%X".format(enemy.posX)},${"$%X".format(enemy.posY)})",
                        color
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Doors (${room.doors.size})") {
                room.doors.forEachIndexed { idx, door ->
                    InfoRow(
                        "Door $idx",
                        "info=${"$%02X".format(door.info)} ${if (door.side == 0) "\u2192 right" else "\u2190 left"}"
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Raw Data") {
                val hex = room.rawData.take(64).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Text(
                    hex + if (room.rawData.size > 64) " ..." else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = T.textMuted,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val T = EditorTheme
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = T.accent,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Surface(
        color = T.panelSection,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp), content = content)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    val T = EditorTheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = T.textSecondary)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = T.textPrimary)
    }
}

@Composable
fun InfoRowColored(label: String, value: String, valueColor: Color) {
    val T = EditorTheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = T.textSecondary)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = valueColor)
    }
}
