package io.github.ra5on.titanos.agent.settings

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.agent.app.AppHooks
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.figure.FigureClock
import io.github.ra5on.titanos.agent.voice.Speech

@Composable
fun SettingsPage(model: AgentModel, part: String, onPart: (String) -> Unit, hooks: AppHooks, speech: Speech, clock: FigureClock, onDeleteAll: () -> Unit, onFeature: (String) -> Unit, onKnow: () -> Unit) {}
