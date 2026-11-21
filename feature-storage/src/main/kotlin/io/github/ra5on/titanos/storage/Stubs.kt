package io.github.ra5on.titanos.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.admin.AdminEnv

internal class SmartSelection { var disk by mutableStateOf<String?>(null) }

@Composable internal fun ManagePage(env: AdminEnv, model: StorageModel, nav: StorageNav) {}
@Composable internal fun SmartPage(env: AdminEnv, model: StorageModel, nav: StorageNav, sel: SmartSelection) {}
@Composable internal fun ImportPage(env: AdminEnv, model: StorageModel, nav: StorageNav) {}
@Composable internal fun ReplacePage(env: AdminEnv, model: StorageModel, nav: StorageNav) {}
@Composable internal fun MemoryPage(env: AdminEnv) {}
