// (C) 2022 PPI AG
package org.rett.gitlab.pipelines.debug;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.rett.gitlab.pipelines.PipelineJobStatus;
import org.rett.gitlab.pipelines.JobsTo;
import org.rett.gitlab.pipelines.notifier.NotifierService;
import org.jetbrains.annotations.NotNull;

import java.time.ZonedDateTime;
import java.util.ArrayList;

/**
 * @author PPI AG
 */
public class ShowTestNotificationAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        e.getProject().getService(NotifierService.class).showBalloonForStatus(
                new PipelineJobStatus(1, "123", "123",
                        ZonedDateTime.now(), ZonedDateTime.now(),
                        "failed", "http://www.google.de", "source", new ArrayList<JobsTo>()), 0);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        if (System.getProperty("gitlabPipelineViewerDebugging") == null) {
            e.getPresentation().setVisible(false);
        }
        e.getPresentation().setVisible(true);
    }
}
