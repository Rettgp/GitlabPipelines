// (C) 2021 PPI AG
package org.rett.gitlab.pipelines;

import com.google.common.base.Strings;
import com.intellij.openapi.diagnostic.Logger;
import org.rett.gitlab.pipelines.config.PipelineViewerConfigApp;

import java.io.IOException;

/**
 * @author PPI AG
 */
public class UrlOpener {

    private static final Logger logger = Logger.getInstance(UrlOpener.class);


    public static void openUrl(String url) {
        final String openerCommand = PipelineViewerConfigApp.getInstance().getUrlOpenerCommand();
        if (!Strings.isNullOrEmpty(openerCommand)) {
            final String fullCommand = openerCommand.replace("%url", url);
            logger.debug("Starting command ", fullCommand);
            try {
                Runtime.getRuntime().exec(fullCommand);
                return;
            } catch (IOException e) {
                logger.error("Unable to start command ", fullCommand);
            }
        } else {
            logger.debug("Opening default browser for ", url);
        }
        com.intellij.ide.BrowserUtil.browse(url);
    }
}
