package org.rett.gitlab.pipelines;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.rett.gitlab.pipelines.git.GitService;
import org.rett.gitlab.pipelines.lights.LightsControl;
import org.rett.gitlab.pipelines.notifier.NotifierService;
import org.jetbrains.annotations.NotNull;

public class StartupInitialization implements StartupActivity {

    private static final Logger logger = Logger.getInstance(StartupInitialization.class);

    @Override
    public void runActivity(@NotNull Project project) {
        //Get service so it's initialized
        project.getService(NotifierService.class);
        project.getService(LightsControl.class);
        project.getService(BackgroundUpdateService.class);
        logger.debug("Running startup initialization (reloading git repositories)");
        project.getService(GitService.class).reloadGitRepositories();
    }
}
