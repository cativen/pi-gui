package dev.pi.gui.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import dev.pi.gui.commit.CommitAiGenerator
import dev.pi.gui.git.GitDiffService
import dev.pi.gui.i18n.PiBundle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Pi icon in the native Commit message toolbar. Generates text but never performs a commit. */
class GenerateCommitMessageAction : AnAction() {
    private val generating = AtomicBoolean(false)
    private val generator = CommitAiGenerator()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val messageUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)?.commitMessageUi
        val paths = includedPaths(e)
        if (paths.isEmpty()) {
            notify(project, PiBundle.message("commitAi.noChanges"), NotificationType.WARNING)
            return
        }
        if (!generating.compareAndSet(false, true)) return

        messageUi?.startLoading()
        object : Task.Backgroundable(project, PiBundle.message("commitAi.progress", paths.size), true) {
            private var generated = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = PiBundle.message("commitAi.readingDiff")
                val diff = GitDiffService.commitDiff(paths, project.basePath)
                if (diff.text.isBlank()) throw IllegalStateException(PiBundle.message("commitAi.emptyDiff"))
                indicator.text = PiBundle.message("commitAi.generating")
                val workingDir = GitDiffService.repoRootFor(paths.firstOrNull())
                    ?: project.basePath?.let(::File)
                    ?: File(System.getProperty("user.home"))
                generated = generator.generate(workingDir, diff.text, indicator)
            }

            override fun onSuccess() {
                messageControl.setCommitMessage(generated)
                messageUi?.focus()
                notify(project, PiBundle.message("commitAi.success"), NotificationType.INFORMATION)
            }

            override fun onThrowable(error: Throwable) {
                notify(
                    project,
                    PiBundle.message("commitAi.failed", error.message ?: error.javaClass.simpleName),
                    NotificationType.ERROR,
                )
            }

            override fun onFinished() {
                messageUi?.stopLoading()
                generating.set(false)
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null &&
            e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null &&
            includedPaths(e).isNotEmpty() && !generating.get()
        e.presentation.description = PiBundle.message(
            if (generating.get()) "commitAi.action.busy" else "commitAi.action.description",
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun includedPaths(e: AnActionEvent): List<String> {
        val workflow = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val changes = workflow?.getIncludedChanges()
            ?: e.getData(VcsDataKeys.SELECTED_CHANGES)?.toList().orEmpty()
        val unversioned = workflow?.getIncludedUnversionedFiles().orEmpty().map { it.path }
        return (changes.flatMap(::pathsOf) + unversioned).filter { it.isNotBlank() }.distinct()
    }

    private fun pathsOf(change: Change): List<String> = listOfNotNull(
        change.beforeRevision?.file?.path,
        change.afterRevision?.file?.path,
    ).distinct()

    private fun notify(
        project: com.intellij.openapi.project.Project,
        content: String,
        type: NotificationType,
    ) {
        NotificationGroupManager.getInstance().getNotificationGroup("Pi GUI")
            .createNotification(PiBundle.message("commitAi.title"), content, type)
            .notify(project)
    }
}
