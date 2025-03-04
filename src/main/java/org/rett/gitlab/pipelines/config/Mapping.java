
package org.rett.gitlab.pipelines.config;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Strings;

/**
 */
public class Mapping {

    private String remote;
    private String host;
    private String projectPath;
    private String gitlabProjectId;
    private String projectName;

    public Mapping() {
    }

    public String getRemote() {
        return remote;
    }

    public void setRemote(String remote) {
        this.remote = remote;
    }

    public String getGitlabProjectId() {
        return gitlabProjectId;
    }

    public void setGitlabProjectId(String gitlabProjectId) {
        this.gitlabProjectId = gitlabProjectId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        if (host == null) {
            this.host = host;
            return;
        }
        if (!host.toLowerCase().startsWith("http://") && !host.toLowerCase().startsWith("https://")) {
            this.host = "https://" + host;
        } else {
            this.host = host;
        }
        if (this.host.endsWith("/")) {
            this.host = this.host.substring(0, host.length() - 1);
        }
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String toSerializable() {
        return Joiner.on(";").useForNull("").join(remote, host, projectPath, gitlabProjectId, projectName);
    }

    public static Mapping toMapping(String string) {
        final String[] split = string.split(";");
        Mapping mapping = new Mapping();
        mapping.setRemote(split[0]);
        mapping.setHost(split[1]);
        mapping.setProjectPath(split[2]);
        mapping.setGitlabProjectId(split[3]);
        mapping.setProjectName(split[4]);
        return mapping;
    }

    public static boolean isValid(String string) {
        return string != null && string.split(";").length == 5;
    }

    public boolean isValid() {
        return !Strings.isNullOrEmpty(remote)
                && !Strings.isNullOrEmpty(host)
                && !Strings.isNullOrEmpty(projectPath)
                && !Strings.isNullOrEmpty(projectName)
                && !Strings.isNullOrEmpty(gitlabProjectId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Mapping mapping = (Mapping) o;
        return Objects.equal(remote, mapping.remote) && Objects.equal(host, mapping.host) && Objects.equal(projectPath, mapping.projectPath) && Objects.equal(gitlabProjectId, mapping.gitlabProjectId) && Objects.equal(projectName, mapping.projectName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(remote, host, projectPath, gitlabProjectId, projectName);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("remote", remote)
                .add("host", host)
                .add("projectPath", projectPath)
                .add("gitlabProjectId", gitlabProjectId)
                .add("projectName", projectName)
                .toString();
    }
}
