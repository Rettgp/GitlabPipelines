package org.rett.gitlab.pipelines.gitlab;

import org.rett.gitlab.pipelines.HostAndProjectPath;
import org.rett.gitlab.pipelines.Jackson;
import junit.framework.TestCase;

import java.util.Optional;

public class GitlabServiceTest extends TestCase {

    public void testGetProjectPathFromRemote() throws Exception {
        final Optional<HostAndProjectPath> optional = GitlabService.getHostProjectPathFromRemote("https://selfthosted.com/gitlab/project/intellij_gitlab_pipeline_monitor.git");
        System.out.println(optional.get());
//        final Optional<GitlabService.HostAndProjectPath> projectPathFromRemote = GitlabService.getProjectPathFromRemote("https://gitlab.ppi.int/tph/prod/phmaven.git");
        System.out.println(Jackson.OBJECT_MAPPER.writeValueAsString("query {\n" +
                "  project(fullPath:\"clearing/devpro/adoptopenjdk\") {\n" +
                "    name\n" +
                "    id\n" +
                "  }\n" +
                "}"));
    }
}
