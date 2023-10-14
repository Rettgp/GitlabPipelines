package de.sist.gitlab.pipelinemonitor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.ZonedDateTime;
import java.util.StringJoiner;

@SuppressWarnings("unused")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommitTo {

    @JsonProperty("id")
    private String id;
    @JsonProperty("title")
    private String title;
    @JsonProperty("message")
    private String message;
    @JsonProperty("author_name")
    private String authorName;

    @JsonProperty("id")
    public String getId() {
        return id;
    }
    @JsonProperty("id")
    public void setId(String id) {
        this.id = id;
    }

    @JsonProperty("title")
    public String getTitle() {
        return title;
    }
    @JsonProperty("title")
    public void setTitle(String title) { this.title = title; }

    @JsonProperty("message")
    public String getMessage() { return message; }
    @JsonProperty("message")
    public void setMessage(String message) { this.message = message; }

    @JsonProperty("author_name")
    public String getAuthorName() { return authorName; }
    @JsonProperty("author_name")
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    @Override
    public String toString() {
        return new StringJoiner(", ", CommitTo.class.getSimpleName() + "[", "]")
                .add("id='" + id + "'")
                .add("title='" + title + "'")
                .add("message='" + message + "'")
                .add("authorName='" + authorName + "'")
                .toString();
    }
}