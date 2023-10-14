package org.rett.gitlab.pipelines;

import junit.framework.TestCase;

import java.util.Collections;

public class PipelineFilterTest extends TestCase {

    public void testIsMatch() {
        assertTrue(PipelineFilter.isMatch("myBranch", Collections.singletonList("mybranch")));
        assertTrue(PipelineFilter.isMatch("mybranch", Collections.singletonList("mybranch")));
        assertTrue(PipelineFilter.isMatch("mybranch", Collections.singletonList("mybranch*")));
        assertTrue(PipelineFilter.isMatch("mybranch", Collections.singletonList("mybran*")));
        assertTrue(PipelineFilter.isMatch("mybranch", Collections.singletonList("*")));

        assertFalse(PipelineFilter.isMatch("mybranch", Collections.singletonList("mybranchx*")));
        assertFalse(PipelineFilter.isMatch("mybranch", Collections.singletonList(".*")));

    }
}
