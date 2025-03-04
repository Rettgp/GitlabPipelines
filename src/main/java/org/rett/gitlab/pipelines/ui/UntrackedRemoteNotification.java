// (C) 2021 PPI AG
package org.rett.gitlab.pipelines.ui;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.rett.gitlab.pipelines.BackgroundUpdateService;
import org.rett.gitlab.pipelines.HostAndProjectPath;
import org.rett.gitlab.pipelines.config.ConfigChangedListener;
import org.rett.gitlab.pipelines.config.ConfigProvider;
import org.rett.gitlab.pipelines.config.Mapping;
import org.rett.gitlab.pipelines.config.PipelineViewerConfigApp;
import org.rett.gitlab.pipelines.config.PipelineViewerConfigProject;
import org.rett.gitlab.pipelines.gitlab.GitlabService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * @author PPI AG
 */
public class UntrackedRemoteNotification extends Notification {

    private static final Logger logger = Logger.getInstance(GitlabService.class);

    private final String url;
    private final Project project;
    private final HostAndProjectPath hostProjectPathFromRemote;

    public UntrackedRemoteNotification(Project project, String url, @Nullable HostAndProjectPath hostProjectPathFromRemote) {
        super(getNotificationGroupId(), "Untracked remote found", "The remote " + url + " is not tracked by Gitlab Pipeline Viewer.", NotificationType.INFORMATION);
        this.url = url;
        this.project = project;
        this.hostProjectPathFromRemote = hostProjectPathFromRemote;

        final AnAction openDialog = new AnAction("Choose How to Handle Remote") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                openDialogForUnmappedRemote();
                expire();
            }
        };
        addAction(openDialog);
        final AnAction ignoreRemote = new AnAction("Ignore This Remote") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                logger.info("Ignoring remote " + url + " for project " + project.getName());
                ConfigProvider.getInstance().getIgnoredRemotes().add(url);
                expire();
            }
        };
        addAction(ignoreRemote);
        whenExpired(() -> {
            final boolean isOpen = !getAlreadyOpenNotifications(project).isEmpty();
            logger.debug("Notifying project ", project, " if notifications are open with value ", isOpen);
            project.getMessageBus().syncPublisher(UntrackedRemoteNotificationState.UNTRACKED_REMOTE_FOUND).handle(isOpen);
        });
    }


    @NotNull
    private static String getNotificationGroupId() {
        return NotificationGroupManager.getInstance().getNotificationGroup("org.rett.gitlab.pipelines.unmappedRemote").getDisplayId();
    }

    public static List<UntrackedRemoteNotification> getAlreadyOpenNotifications(Project project) {
        final UntrackedRemoteNotification[] openNotifications = NotificationsManager.getNotificationsManager().getNotificationsOfType(UntrackedRemoteNotification.class, project);
        return Arrays.asList(openNotifications);
    }


    public void openDialogForUnmappedRemote() {
        logger.info("Showing dialog for untracked " + url);

        final UnmappedRemoteDialog.Response response;
        final Disposable disposable = Disposer.newDisposable();
        try {
            if (hostProjectPathFromRemote != null) {
                response = new UnmappedRemoteDialog(url, hostProjectPathFromRemote.getHost(), hostProjectPathFromRemote.getProjectPath(), disposable).showDialog();
            } else {
                response = new UnmappedRemoteDialog(url, disposable).showDialog();
            }

            if (response.getCancel() != UnmappedRemoteDialog.Cancel.ASK_AGAIN) {
                PipelineViewerConfigApp.getInstance().getRemotesAskAgainNextTime().remove(url);
            }
            if (response.getCancel() == UnmappedRemoteDialog.Cancel.IGNORE_REMOTE) {
                ConfigProvider.getInstance().getIgnoredRemotes().add(url);
                logger.info("Added " + url + " to list of ignored remotes");
                return;
            } else if (response.getCancel() == UnmappedRemoteDialog.Cancel.IGNORE_PROJECT) {
                PipelineViewerConfigProject.getInstance(project).setEnabled(false);
                logger.info("Disabling pipeline viewer for project " + project.getName());
                ApplicationManager.getApplication().getMessageBus().syncPublisher(ConfigChangedListener.CONFIG_CHANGED).configChanged();
                return;
            } else if (response.getCancel() == UnmappedRemoteDialog.Cancel.ASK_AGAIN) {
                logger.debug("User chose to be asked again about url ", url);
                PipelineViewerConfigApp.getInstance().getRemotesAskAgainNextTime().add(url);
                return;
            }

            final Mapping mapping = response.getMapping();

            logger.info("Adding mapping " + mapping);
            ConfigProvider.getInstance().getMappings().add(mapping);
            project.getService(BackgroundUpdateService.class).update(project, false);
        } finally {
            Disposer.dispose(disposable);
        }
    }

    public String getUrl() {
        return url;
    }
}
