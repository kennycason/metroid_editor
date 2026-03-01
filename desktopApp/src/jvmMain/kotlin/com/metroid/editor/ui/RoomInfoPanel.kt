package com.metroid.editor.ui

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
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Surface(
        color = Color(0xFF161630),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp)
        ) {
            Text(
                room.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFFD0D0FF)
            )

            Spacer(Modifier.height(12.dp))

            InfoSection("Room Properties") {
                InfoRow("Room #", "$%02X".format(room.roomNumber))
                InfoRow("Area", room.area.displayName)
                InfoRow("Palette", "${room.palette}")
                InfoRow("ROM Offset", "$%06X".format(room.romOffset))
                InfoRow("Data Size", "${room.rawData.size} bytes")
            }

            if (spaceBudget != null) {
                Spacer(Modifier.height(12.dp))

                val pct = spaceBudget.percentUsed
                val barColor = when {
                    pct >= 95 -> Color(0xFFFF4444)
                    pct >= 80 -> Color(0xFFFFAA44)
                    else -> Color(0xFF44CC44)
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
                        color = Color(0xFF0A0A1A),
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
                    val structDesc = "Struct ${"$%02X".format(obj.structIndex)} (${rowCount}r×${macroCount}m)"
                    InfoRow(
                        "Obj $idx",
                        "(${"$%X".format(obj.posX)},${"$%X".format(obj.posY)}) $structDesc pal=${obj.palette}"
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Enemies (${room.enemies.size})") {
                room.enemies.forEachIndexed { idx, enemy ->
                    val name = MetroidNames.enemyName(enemy.type)
                    val color = if (MetroidNames.isItem(enemy.type)) Color(0xFF88FF88) else Color(0xFFFF8888)
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
                        "info=${"$%02X".format(door.info)} ${if (door.side == 0) "→ right" else "← left"}"
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
                    color = Color(0xFF606080),
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF8E6FFF),
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Surface(
        color = Color(0xFF1A1A38),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp), content = content)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFF8080A0))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFC0C0E0))
    }
}

@Composable
fun InfoRowColored(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFF8080A0))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = valueColor)
    }
}
