package org.rett.gitlab.pipelines;

import com.google.common.base.Objects;

import javax.swing.*;
import java.time.ZonedDateTime;
import java.util.StringJoiner;
import java.util.ArrayList;
import java.util.List;

public class PipelineJobStatus {

    private final Integer id;
    public String branchName;
    public final String projectId;
    public final ZonedDateTime creationTime;
    public final ZonedDateTime updateTime;
    public final String result;
    public String statusGroup;
    public final String pipelineLink;
    public String mergeRequestLink;
    public final String source;
    public String author;
    public Icon authorAvatar;
    public String commitTitle;
    private String branchNameDisplay;
    private List<JobStatus> jobs;

    public PipelineJobStatus(Integer id, String ref, String projectId,
                             ZonedDateTime creationTime, ZonedDateTime updatedAt,
                             String result, String webUrl, String source,
                             List<JobsTo> jobs) {
        this.id = id;
        this.branchName = ref;
        this.projectId = projectId;
        this.pipelineLink = webUrl;
        this.creationTime = creationTime;
        this.updateTime = updatedAt;
        this.result = result;
        this.source = source;
        this.jobs = new ArrayList<JobStatus>();
        for (int i = jobs.size() - 1; i >= 0; i--) {
            JobsTo jobTo = jobs.get(i);
            this.author = jobTo.getUser().getName();
            this.authorAvatar = jobTo.getUser().getAvatarIcon();
            this.commitTitle = jobTo.getCommit().getTitle();
            this.jobs.add(new JobStatus(jobTo.getId(), jobTo.getStatus(), jobTo.getStage(), jobTo.getName()));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PipelineJobStatus)) {
            return false;
        }
        PipelineJobStatus that = (PipelineJobStatus) o;
        return Objects.equal(id, that.id) &&
                Objects.equal(branchName, that.branchName) &&
                Objects.equal(projectId, that.projectId) &&
                Objects.equal(creationTime, that.creationTime) &&
                Objects.equal(result, that.result) &&
                Objects.equal(source, that.source) &&
                Objects.equal(pipelineLink, that.pipelineLink);
    }

    public Integer getId() {
        return id;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getProjectId() {
        return projectId;
    }

    public ZonedDateTime getCreationTime() {
        return creationTime;
    }

    public ZonedDateTime getUpdateTime() {
        return updateTime;
    }

    public String getResult() {
        return result;
    }

    public String getPipelineLink() {
        return pipelineLink;
    }


    public String getSource() {
        return source;
    }

    public String getMergeRequestLink() {
        return mergeRequestLink;
    }

    public String getBranchNameDisplay() {
        return branchNameDisplay != null ? branchNameDisplay : branchName;
    }

    public void setBranchNameDisplay(String branchNameDisplay) {
        this.branchNameDisplay = branchNameDisplay;
    }

    public String getStatusGroup() {
        return statusGroup;
    }

    public void setStatusGroup(String statusGroup) {
        this.statusGroup = statusGroup;
    }

    public List<JobStatus> getJobs() { return this.jobs; }

    @Override
    public int hashCode() {
        return Objects.hashCode(branchName, creationTime, result, pipelineLink);
    }

    @Override
    public String toString() {
        return new StringJoiner("\n", PipelineJobStatus.class.getSimpleName() + "[", "]")
                .add("  branchName='" + branchName + "'")
                .add("  time=" + creationTime)
                .add("  result='" + result + "'")
                .add("  pipelineLink='" + pipelineLink + "'")
                .add("  mergeRequestLink='" + mergeRequestLink + "'")
                .add("  source='" + source + "'")
                .add("  jobs='" + jobs + "'")
                .add("\n")
                .toString();
    }
}
