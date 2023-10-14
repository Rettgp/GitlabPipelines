// (C) 2022 PPI AG
package org.rett.gitlab.pipelines.gitlab.mapping;

import java.util.List;

public class Pipelines {
    private List<PipelineNode> nodes = null;

    public List<PipelineNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<PipelineNode> nodes) {
        this.nodes = nodes;
    }
}
