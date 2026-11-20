package io.github.ra5on.titanos.agent.pages

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.Project

// Vorläufig: die Seiten entstehen in eigenen Dateien und ersetzen diese hier.

@Composable fun PlannedPage(model: AgentModel, onOpenChat: (String) -> Unit) {}
@Composable fun MemoryPage(model: AgentModel, query: String, draft: String, onOpenChat: (String) -> Unit) {}
@Composable fun KnowledgePage(model: AgentModel, onEditProject: (Project) -> Unit, onOpenProject: (Project) -> Unit) {}
@Composable fun NotesPanel(model: AgentModel, onClose: () -> Unit) {}
@Composable fun ProjectList(model: AgentModel, onOpen: (Project) -> Unit, onNew: () -> Unit) {}
@Composable fun ProjectSheet(model: AgentModel, project: Project, onClose: () -> Unit, onOpen: (String) -> Unit, onEdit: () -> Unit, onNew: () -> Unit) {}
@Composable fun ProjectDialog(model: AgentModel, project: Project?, onClose: () -> Unit) {}
@Composable fun FeatureView(model: AgentModel, id: String, onClose: () -> Unit) {}
@Composable fun NasPicker(model: AgentModel, onClose: () -> Unit, onPick: (List<String>) -> Unit) {}
