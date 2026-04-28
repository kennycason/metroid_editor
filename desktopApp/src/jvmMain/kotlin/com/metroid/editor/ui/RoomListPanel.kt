package com.metroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metroid.editor.data.Room

@Composable
fun RoomListPanel(
    rooms: List<Room>,
    selectedRoom: Room?,
    onRoomSelected: (Room) -> Unit,
    modifier: Modifier = Modifier
) {
    val T = EditorTheme
    val listState = rememberLazyListState()

    Surface(color = T.panelBg, modifier = modifier) {
        Column {
            Surface(color = T.panelHeader, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Rooms (${rooms.size})",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = T.textSecondary
                )
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rooms) { room ->
                    val isSelected = room == selectedRoom
                    Surface(
                        color = if (isSelected) T.selected else androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.fillMaxWidth().clickable { onRoomSelected(room) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Room \$${"%02X".format(room.roomNumber)}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = if (isSelected) T.textPrimary else T.textSecondary
                                )
                                Text(
                                    "${room.objects.size} obj, ${room.enemies.size} enemies, ${room.doors.size} doors",
                                    fontSize = 10.sp,
                                    color = T.textMuted
                                )
                            }
                        }
                    }
                    if (room != rooms.last()) {
                        Divider(color = T.divider, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
