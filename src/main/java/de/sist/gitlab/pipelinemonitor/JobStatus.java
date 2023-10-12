package de.sist.gitlab.pipelinemonitor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

import java.time.ZonedDateTime;
import java.util.StringJoiner;

public class JobStatus {

    private Long id;
    public String status;
    public String stage;
    public String name;
    public String iconPath;

    public JobStatus(Long id, String status, String stage, String name) {
        this.id = id;
        this.status = status;
        this.stage = stage;
        this.name = name;
        if (this.status.equals("failed")) {
            this.iconPath = "toolWindow/failed.png";
        } else if (this.status.equals("success")) {
            this.iconPath = "toolWindow/success.png";
        } else if (this.status.equals("running")) {
            this.iconPath = "toolWindow/running.png";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobStatus)) {
            return false;
        }
        JobStatus that = (JobStatus) o;
        return Objects.equal(id, that.id);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStatus() {
        return status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    public String getStage() {
        return stage;
    }
    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, status, stage, name);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", JobStatus.class.getSimpleName() + "[", "]")
                .add("Status='" + status + "'")
                .add("Stage=" + stage)
                .add("Name='" + name + "'")
                .toString();
    }
}
