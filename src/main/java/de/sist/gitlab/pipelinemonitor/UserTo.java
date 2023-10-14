package de.sist.gitlab.pipelinemonitor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.IconLoader;
import de.sist.gitlab.pipelinemonitor.ui.GitlabToolWindow;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.URL;
import java.time.ZonedDateTime;
import java.util.StringJoiner;

@SuppressWarnings("unused")
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserTo {
    private static final Logger logger = Logger.getInstance(GitlabToolWindow.class);

    @JsonProperty("id")
    private Long id;
    @JsonProperty("username")
    private String username;
    @JsonProperty("name")
    private String name;
    @JsonProperty("avatar_url")
    private String avatarUrl;

    private Icon avatarIcon;

    @JsonProperty("id")
    public Long getId() {
        return id;
    }
    @JsonProperty("id")
    public void setId(Long id) {
        this.id = id;
    }

    @JsonProperty("username")
    public String getUsername() { return username; }
    @JsonProperty("username")
    public void setUsername(String username) { this.username = username; }

    @JsonProperty("name")
    public String getName() { return name; }
    @JsonProperty("name")
    public void setName(String name) { this.name = name; }

    @JsonProperty("avatar_url")
    public String getAvatarUrl() { return avatarUrl; }
    @JsonProperty("avatar_url")
    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
        try {
            Image image = ImageIO.read(new URL(this.avatarUrl));
            setAvatarIcon(new ImageIcon(image.getScaledInstance(32, 32, Image.SCALE_FAST)));
        } catch (IOException e) {
            logger.error(e);
        }
    }

    public Icon getAvatarIcon() { return avatarIcon; }
    public void setAvatarIcon(Icon icon) { this.avatarIcon = icon; }

    @Override
    public String toString() {
        return new StringJoiner(", ", UserTo.class.getSimpleName() + "[", "]")
                .add("id='" + id + "'")
                .add("username='" + username + "'")
                .add("name='" + name + "'")
                .add("avatarUrl='" + avatarUrl + "'")
                .toString();
    }
}