package com.metroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metroid.editor.data.MetroidNames
import com.metroid.editor.data.Room
import com.metroid.editor.rom.RogueDawnRoomRef

@Composable
fun RogueDawnRoomInfoPanel(
    room: Room,
    roomRef: RogueDawnRoomRef?,
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
                InfoRow("Pointer", roomRef?.pointer?.displayName ?: "--")
                InfoRow("ROM Offset", "$%06X".format(room.romOffset))
                InfoRow("Data Size", "${room.rawData.size} bytes")
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Objects (${room.objects.size})") {
                room.objects.forEachIndexed { idx, obj ->
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Object $idx at (${obj.posX},${obj.posY})", fontSize = 11.sp, color = T.textPrimary)
                            Text("pal ${obj.palette}", fontSize = 10.sp, color = T.textMuted)
                        }
                        Text(
                            "Struct ${'$'}${"%02X".format(obj.structIndex)}",
                            fontSize = 10.sp,
                            color = T.textMuted
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Enemies (${room.enemies.size})") {
                room.enemies.forEachIndexed { idx, enemy ->
                    val name = MetroidNames.enemyName(enemy.type, room.area)
                    InfoRow(
                        "Enemy $idx",
                        "$name (${"$%X".format(enemy.posX)},${"$%X".format(enemy.posY)})"
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Doors (${room.doors.size})") {
                room.doors.forEachIndexed { idx, door ->
                    InfoRow(
                        "Door $idx",
                        "info=${"$%02X".format(door.info)} ${if (door.side == 0) "right" else "left"}"
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Raw Data") {
                val hex = room.rawData.take(96).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Text(
                    hex + if (room.rawData.size > 96) " ..." else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = T.textMuted,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
