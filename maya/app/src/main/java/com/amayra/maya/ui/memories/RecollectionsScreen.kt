package com.amayra.maya.ui.memories

import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amayra.maya.MayaApplication
import com.amayra.maya.data.MemoryEntity
import com.amayra.maya.ui.theme.TextDim
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recollections & Quests — Myra-style memory hub. Every tile is a real
 * MemoryEntity from the store the AI reads/writes; nothing is display-only.
 *
 * Kinds: calendar | task | schedule | personal | preference | favorite | interest | fact
 */

private val Cyan = Color(0xFF22D3EE)
private val Pink = Color(0xFFEC4899)
private val Purple = Color(0xFFA855F7)
private val Amber = Color(0xFFF59E0B)
private val Card = Color(0xFF141926)
private val NeonDim = Color(0xFF9AA3B5)

private val KINDS = listOf("calendar", "task", "schedule", "personal", "preference", "favorite", "interest", "fact")

private fun kindLabel(kind: String): String = when (kind) {
    "task" -> "Tasks"
    "calendar" -> "Calendar"
    "schedule" -> "Schedule"
    "personal" -> "Personal"
    "preference" -> "Preferences"
    "favorite" -> "Favorites"
    "interest" -> "Interests"
    else -> "Facts"
}

private fun kindColor(kind: String): Color = when (kind) {
    "calendar" -> Cyan
    "task" -> Pink
    "schedule" -> Purple
    "personal" -> Color(0xFF60A5FA)
    "preference" -> Amber
    "favorite" -> Pink
    "interest" -> Purple
    else -> NeonDim
}

private val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)

@Composable
fun RecollectionsScreen(app: MayaApplication) {
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<MemoryEntity>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var newMemory by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf("personal") }

    suspend fun refresh() { memories = app.db.memoryDao.all() }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Text("Recollections & Quests", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(
            "${memories.count { !it.done }} open · ${memories.size} total",
            color = NeonDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))

        // Search field.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(12.dp))
                .border(1.dp, NeonDim.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, tint = NeonDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = Brush.verticalGradient(listOf(Cyan, Cyan)),
                decorationBox = { inner ->
                    Box { if (query.isEmpty()) Text("Search memories & tasks...", color = NeonDim, fontSize = 14.sp); inner() }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))

        // Filter chips (horizontal scroll).
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All") + KINDS.map { kindLabel(it) }) { chip ->
                val selected = filter == chip
                val tint = if (chip == "All") Cyan else kindColor(KINDS.firstOrNull { kindLabel(it) == chip } ?: "fact")
                Box(
                    Modifier
                        .background(if (selected) tint.copy(alpha = 0.2f) else Card, RoundedCornerShape(999.dp))
                        .border(1.dp, if (selected) tint else tint.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                        .clickable { filter = chip }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(chip, color = if (selected) tint else NeonDim, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Add-memory row.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(12.dp))
                .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = newMemory,
                onValueChange = { newMemory = it },
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = Brush.verticalGradient(listOf(Pink, Pink)),
                decorationBox = { inner ->
                    Box { if (newMemory.isEmpty()) Text("Add a memory or quest...", color = NeonDim, fontSize = 14.sp); inner() }
                },
                modifier = Modifier.weight(1f)
            )
            // Kind picker (cycles on tap).
            Box(
                Modifier
                    .clickable {
                        newKind = KINDS[(KINDS.indexOf(newKind) + 1).mod(KINDS.size)]
                    }
                    .border(1.dp, kindColor(newKind), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(kindLabel(newKind), color = kindColor(newKind), fontSize = 12.sp)
            }
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .background(Pink.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                    .clickable {
                        if (newMemory.isNotBlank()) {
                            scope.launch {
                                app.db.memoryDao.insert(
                                    MemoryEntity(kind = newKind, content = newMemory.trim(), importance = 3)
                                )
                                newMemory = ""
                                refresh()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, "Add memory", tint = Pink, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        // Filtered list.
        val filtered = memories.filter { m ->
            val matchesFilter = filter == "All" || kindLabel(m.kind) == filter
            val matchesQuery = query.isBlank() || m.content.contains(query, ignoreCase = true)
            matchesFilter && matchesQuery
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { m ->
                MemoryTile(
                    memory = m,
                    onPin = {
                        scope.launch {
                            app.db.memoryDao.insert(m.copy(pinned = !m.pinned, updatedAt = System.currentTimeMillis()))
                            refresh()
                        }
                    },
                    onDone = {
                        scope.launch {
                            app.db.memoryDao.insert(m.copy(done = !m.done, updatedAt = System.currentTimeMillis()))
                            refresh()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            app.db.memoryDao.delete(m.id)
                            refresh()
                        }
                    }
                )
            }
            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MemoryTile(
    memory: MemoryEntity,
    onPin: () -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit
) {
    val tint = kindColor(memory.kind)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(14.dp))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .clickable(onClick = onDone)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .border(1.dp, tint, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(kindLabel(memory.kind), color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.size(8.dp))
            Text(fmt.format(Date(memory.createdAt)), color = NeonDim, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.PushPin, if (memory.pinned) "Unpin" else "Pin",
                tint = if (memory.pinned) tint else NeonDim.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onPin)
            )
            Spacer(Modifier.size(10.dp))
            Icon(
                Icons.Filled.Close, "Delete",
                tint = NeonDim.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onDelete)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            memory.content,
            color = if (memory.done) NeonDim else Color.White,
            fontSize = 14.sp,
            textDecoration = if (memory.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
        )
        if (memory.pinned) {
            Spacer(Modifier.height(4.dp))
            Text("📌 Pinned", color = tint, fontSize = 11.sp)
        }
    }
}
