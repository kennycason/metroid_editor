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
import com.metroid.editor.data.Room
import com.metroid.editor.rom.MetroidRomData

@Composable
fun RoomInfoPanel(
    room: Room,
    metroidData: MetroidRomData,
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

            Spacer(Modifier.height(12.dp))

            InfoSection("Objects (${room.objects.size})") {
                room.objects.forEachIndexed { idx, obj ->
                    InfoRow(
                        "Obj $idx",
                        "pos=(${"$%X".format(obj.posX)},${"$%X".format(obj.posY)}) " +
                                "struct=${"$%02X".format(obj.structIndex)} pal=${obj.palette}"
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Enemies (${room.enemies.size})") {
                room.enemies.forEachIndexed { idx, enemy ->
                    InfoRow(
                        "Enemy $idx",
                        "type=${"$%02X".format(enemy.type)} " +
                                "pos=(${"$%X".format(enemy.posX)},${"$%X".format(enemy.posY)}) " +
                                "slot=${"$%02X".format(enemy.slot)}"
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoSection("Doors (${room.doors.size})") {
                room.doors.forEachIndexed { idx, door ->
                    InfoRow(
                        "Door $idx",
                        "info=${"$%02X".format(door.info)} " +
                                "side=${if (door.side == 0) "right" else "left"}"
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
        Text(
            label,
            fontSize = 11.sp,
            color = Color(0xFF8080A0)
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFC0C0E0)
        )
    }
}
