package org.rett.gitlab.pipelines;

import com.intellij.util.messages.Topic;
import org.rett.gitlab.pipelines.config.Mapping;

import java.util.EventListener;
import java.util.List;
import java.util.Map;

public interface ReloadListener extends EventListener {

    Topic<ReloadListener> RELOAD = Topic.create("Reload triggered", ReloadListener.class);

    void reload(Map<Mapping, List<PipelineJobStatus>> pipelineInfos);

}
