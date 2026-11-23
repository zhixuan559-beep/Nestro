package app.nestro.ui

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.nestro.data.UserMemoryEntity

/** Owns the UI state for user and automatic memory management. */
@Stable
internal class MemoryUiState(preferences: SharedPreferences) {
    val globalMemory: MutableState<String> = mutableStateOf("")
    val memoryDraft: MutableState<String> = mutableStateOf("")
    val manualMemoryDraft: MutableState<String> = mutableStateOf("")
    val manualMemoryCategory: MutableState<String> = mutableStateOf("preference")
    val manualMemoryCategoryExpanded: MutableState<Boolean> = mutableStateOf(false)
    val automaticMemories: MutableState<List<UserMemoryEntity>> = mutableStateOf(emptyList())
    val automaticMemoryEnabled: MutableState<Boolean> =
        mutableStateOf(preferences.getBoolean("automatic_memory_enabled", false))
    val memoryEdits: MutableState<Map<String, String>> = mutableStateOf(emptyMap())
    val memoryStatus: MutableState<String> = mutableStateOf("")
}
