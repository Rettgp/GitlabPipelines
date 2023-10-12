package de.sist.gitlab.pipelinemonitor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.ZonedDateTime;
import java.util.StringJoiner;

@SuppressWarnings("unused")
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobsTo {

    @JsonProperty("id")
    private Long id;
    @JsonProperty("status")
    private String status;
    @JsonProperty("stage")
    private String stage;
    @JsonProperty("name")
    private String name;

    @JsonProperty("id")
    public Long getId() {
        return id;
    }
    @JsonProperty("id")
    public void setId(Long id) {
        this.id = id;
    }

    @JsonProperty("status")
    public String getStatus() {
        return status;
    }
    @JsonProperty("status")
    public void setStatus(String status) {
        this.status = status;
    }

    @JsonProperty("stage")
    public String getStage() { return stage; }
    @JsonProperty("stage")
    public void setStage(String stage) { this.stage = stage; }

    @JsonProperty("name")
    public String getName() { return name; }
    @JsonProperty("name")
    public void setName(String name) { this.name = name; }

    @Override
    public String toString() {
        return new StringJoiner(", ", JobsTo.class.getSimpleName() + "[", "]")
                .add("id='" + id + "'")
                .add("status='" + status + "'")
                .add("stage='" + stage + "'")
                .add("name='" + name + "'")
                .toString();
    }
}
