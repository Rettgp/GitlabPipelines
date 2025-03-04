package org.rett.gitlab.pipelines.gitlab;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Strings;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.io.HttpRequests;
import org.rett.gitlab.pipelines.BackgroundUpdateService;
import org.rett.gitlab.pipelines.HostAndProjectPath;
import org.rett.gitlab.pipelines.Jackson;
import org.rett.gitlab.pipelines.PipelineJobStatus;
import org.rett.gitlab.pipelines.PipelineTo;
import org.rett.gitlab.pipelines.JobsTo;
import org.rett.gitlab.pipelines.config.ConfigProvider;
import org.rett.gitlab.pipelines.config.Mapping;
import org.rett.gitlab.pipelines.config.PipelineViewerConfigApp;
import org.rett.gitlab.pipelines.config.PipelineViewerConfigProject;
import org.rett.gitlab.pipelines.config.TokenType;
import org.rett.gitlab.pipelines.git.GitService;
import org.rett.gitlab.pipelines.gitlab.mapping.Data;
import org.rett.gitlab.pipelines.gitlab.mapping.DetailedStatus;
import org.rett.gitlab.pipelines.gitlab.mapping.Edge;
import org.rett.gitlab.pipelines.gitlab.mapping.MergeRequest;
import org.rett.gitlab.pipelines.gitlab.mapping.PipelineNode;
import org.rett.gitlab.pipelines.notifier.NotifierService;
import org.rett.gitlab.pipelines.ui.TokenDialog;
import org.rett.gitlab.pipelines.ui.UntrackedRemoteNotification;
import org.rett.gitlab.pipelines.ui.UntrackedRemoteNotificationState;
import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeException;
import dev.failsafe.RetryPolicy;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.client.utils.URIBuilder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitlabService implements Disposable {

    public static final String ACCESS_TOKEN_CREDENTIALS_ATTRIBUTE = CredentialAttributesKt.generateServiceName("GitlabService", "accessToken");


    private static final Logger logger = Logger.getInstance(GitlabService.class);

    private static final Pattern REMOTE_GIT_SSH_PATTERN = Pattern.compile("git@(?<host>.*):(?<projectPath>.*)(\\.git)?");
    @SuppressWarnings("RegExpRedundantEscape")
    private static final Pattern REMOTE_GIT_HTTP_PATTERN = Pattern.compile("(?<scheme>https?:\\/\\/)(?<url>.*)(\\.git)?");
    private static final Pattern REMOTE_BEST_GUESS_PATTERN = Pattern.compile("(?<host>https?://[^/]*)/(?<projectPath>.*)");
    private static final List<String> INCOMPATIBLE_REMOTES = Arrays.asList("github.com", "bitbucket.com");
    private static final RetryPolicy<String> RETRY_POLICY = RetryPolicy.<String>builder()
            .handle(IOException.class, LoginException.class)
            .withDelay(Duration.ofSeconds(1))
            .withMaxRetries(5)
            .build();

    private final ConfigProvider config = ApplicationManager.getApplication().getService(ConfigProvider.class);
    private final Project project;
    private final Map<Mapping, List<PipelineJobStatus>> pipelineInfos = new HashMap<>();
    private final Set<Mapping> openTokenDialogsByMapping = new HashSet<>();
    private final List<MergeRequest> mergeRequests = new ArrayList<>();
    final GitService gitService;
    private boolean isCheckingForUnmappedRemotes;


    public GitlabService(Project project) {
        this.project = project;
        gitService = project.getService(GitService.class);
    }

    public void updatePipelineInfos(boolean triggeredByUser) throws IOException {
        final Map<Mapping, List<PipelineJobStatus>> newMappingToPipelines = new HashMap<>();
        for (Map.Entry<Mapping, List<PipelineTo>> entry : loadPipelines(triggeredByUser).entrySet()) {
            final List<PipelineJobStatus> jobStatuses = entry.getValue().stream()
                    .map(pipeline -> new PipelineJobStatus(pipeline.getId(), pipeline.getRef(),
                            entry.getKey().getGitlabProjectId(), pipeline.getCreatedAt(),
                            pipeline.getUpdatedAt(), pipeline.getStatus(),
                            pipeline.getWebUrl(), pipeline.getSource(), pipeline.getJobs()))
                    .sorted(Comparator.comparing(PipelineJobStatus::getUpdateTime, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .collect(Collectors.toList());

            newMappingToPipelines.put(entry.getKey(), jobStatuses);
        }
        synchronized (pipelineInfos) {
            pipelineInfos.clear();
            pipelineInfos.putAll(newMappingToPipelines);
        }
    }

    public void updateFromGraphQl() {
        final Map<Mapping, List<PipelineJobStatus>> localPipelineInfos = new HashMap<>(pipelineInfos);
        mergeRequests.clear();
        try {
            for (Mapping mapping : localPipelineInfos.keySet()) {
                logger.debug("Loading merge requests for remote ", mapping.getRemote());
                final List<String> sourceBranches = new ArrayList<>(gitService.getTrackedBranches(mapping));
                final Optional<Data> data = GraphQl.makeCall(mapping.getHost(), ConfigProvider.getToken(mapping), mapping.getProjectPath(), sourceBranches, true);
                if (data.isPresent()) {
                    final List<MergeRequest> newMergeRequests = data.get().getProject().getMergeRequests().getEdges().stream().map(Edge::getMergeRequest).collect(Collectors.toList());
                    mergeRequests.addAll(newMergeRequests);
                    logger.debug("Loaded ", mergeRequests.size(), " pipelines for remote ", mapping.getRemote());

                    final Map<String, List<MergeRequest>> mergeRequestsBySourceBranch = mergeRequests.stream().collect(Collectors.groupingBy(MergeRequest::getSourceBranch));
                    final Map<Integer, List<PipelineNode>> pipelinesByIid = data.get().getProject().getPipelines().getNodes().stream()
                            .collect(Collectors.groupingBy(x -> Integer.parseInt(x.getId().substring(x.getId().lastIndexOf("/") + 1))));
                    for (PipelineJobStatus pipelineJobStatus : localPipelineInfos.get(mapping)) {
                        final List<MergeRequest> mergeRequestsForPipeline = mergeRequestsBySourceBranch.get(pipelineJobStatus.branchName);
                        if (mergeRequestsForPipeline != null && mergeRequestsForPipeline.size() > 0) {
                            pipelineJobStatus.mergeRequestLink = mergeRequestsForPipeline.get(0).getWebUrl();
                        }
                        final List<PipelineNode> pipelineNodesForPipeline = pipelinesByIid.get(pipelineJobStatus.getId());
                        if (pipelineNodesForPipeline != null && pipelineNodesForPipeline.size() > 0) {
                            final DetailedStatus detailedStatus = pipelineNodesForPipeline.get(0).getDetailedStatus();
                            if (detailedStatus != null) {
                                pipelineJobStatus.statusGroup = detailedStatus.getGroup();
                            }
                        }
                    }
                } else {
                    logger.debug("Unable to load merge requests for remote ", mapping.getRemote());
                }
            }
        } catch (Exception e) {
            logger.info("Unable to load merge requests", e);
        }
    }

    public Map<Mapping, List<PipelineJobStatus>> getPipelineInfos() {
        synchronized (pipelineInfos) {
            return pipelineInfos;
        }
    }

    public List<MergeRequest> getMergeRequests() {
        synchronized (pipelineInfos) {
            return mergeRequests;
        }
    }

    public void checkForUnmappedRemotes(boolean triggeredByUser) {
        //Locks don't work here for some reason
        if (isCheckingForUnmappedRemotes) {
            return;
        }
        isCheckingForUnmappedRemotes = true;
        try {
            ConfigProvider.getInstance().aquireLock();
            final List<GitRepository> gitRepositories = gitService.getAllGitRepositories();
            logger.debug("Checking for unmapped remotes");
            for (GitRepository gitRepository : gitRepositories) {
                for (GitRemote remote : gitRepository.getRemotes()) {
                    for (String url : remote.getUrls()) {
                        if (!PipelineViewerConfigProject.getInstance(project).isEnabled()) {
                            //Make sure no further remotes are processed if multiple are found and the user chose to disable for the project
                            logger.debug("Disabled for project ", project.getName());
                            return;
                        }
                        if (ConfigProvider.getInstance().getIgnoredRemotes().contains(url)) {
                            logger.debug("Remote ", url, " is ignored");
                            continue;
                        }
                        if (PipelineViewerConfigApp.getInstance().getRemotesAskAgainNextTime().contains(url) && !triggeredByUser) {
                            logger.debug("Remote ", url, " is ignored until next plugin load and reload was not triggered by user. Not showing notification.");
                            continue;
                        }
                        if (INCOMPATIBLE_REMOTES.stream().anyMatch(x -> url.toLowerCase().contains(x))) {
                            logger.debug("Remote URL ", url, " is incompatible");
                            continue;
                        }
                        if (UntrackedRemoteNotification.getAlreadyOpenNotifications(project).stream().anyMatch(x -> x.getUrl().equals(url))) {
                            logger.debug("Remote URL ", url, " is already waiting for an answer");
                            continue;
                        }
                        if (config.getMappingByRemoteUrl(url) == null) {
                            final Optional<HostAndProjectPath> hostProjectPathFromRemote = GitlabService.getHostProjectPathFromRemote(url);

                            if (isCiDisabledForGitlabProject(url, hostProjectPathFromRemote.orElse(null))) {
                                return;
                            }
                            if (hostProjectPathFromRemote.isPresent()) {
                                final String host = hostProjectPathFromRemote.get().getHost();
                                final String projectPath = hostProjectPathFromRemote.get().getProjectPath();
                                if (PipelineViewerConfigApp.getInstance().getAlwaysMonitorHosts().contains(host)) {
                                    logger.debug("Host ", host, " is in the list of hosts for which to always monitor projects");
                                    final String token = ConfigProvider.getToken(host, projectPath);
                                    final Optional<Mapping> optionalMapping = createMappingWithProjectNameAndId(url, host, projectPath, token, TokenType.PERSONAL);
                                    final NotifierService notifierService = project.getService(NotifierService.class);
                                    if (optionalMapping.isPresent()) {
                                        logger.debug("Successfully created mapping ", optionalMapping.get(), ". Will use it");
                                        notifierService.showInfo("Gitlab Pipeline Viewer will monitor project " + project.getName());
                                        ConfigProvider.getInstance().getMappings().add(optionalMapping.get());
                                        project.getService(BackgroundUpdateService.class).update(project, false);
                                        continue;
                                    } else {
                                        logger.info("Unable to automatically create mapping for project on host " + host);
                                        notifierService.showError("Unable to automatically create mapping for project on host " + host);
                                    }
                                }
                            }

                            logger.debug("Showing notification for untracked remote ", url);
                            new UntrackedRemoteNotification(project, url, hostProjectPathFromRemote.orElse(null)).notify(project);
                            logger.debug("Notifying project ", project, " that a new notification is shown");
                            project.getMessageBus().syncPublisher(UntrackedRemoteNotificationState.UNTRACKED_REMOTE_FOUND).handle(true);
                        }
                    }
                }
            }
        } finally {
            isCheckingForUnmappedRemotes = false;
        }
    }

    private boolean isCiDisabledForGitlabProject(String url, HostAndProjectPath hostProjectPathFromRemote) {
        if (hostProjectPathFromRemote == null) {
            logger.debug("Unable to determine if CI is enabled for " + url + " because host and project path could not be parsed");
            return false;
        }

        final String host = hostProjectPathFromRemote.getHost();
        final String projectPath = hostProjectPathFromRemote.getProjectPath();
        final Optional<Data> data = GraphQl.makeCall(host, ConfigProvider.getToken(url, host), projectPath, Collections.emptyList(), false);

        if (data.isEmpty()) {
            logger.debug("Unable to determine if CI is enabled for " + url + " because the graphql query failed");
            return false;
        }
        if (data.get().getProject().isJobsEnabled()) {
            logger.info("CI is enabled for " + url);
            return false;
        }
        final NotificationGroup notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("org.rett.gitlab.pipelines.disabledCi");
        notificationGroup.createNotification("Gitlab Pipeline Viewer - CI disabled", "Gitlab CI is disabled for " + url + ". Ignoring it.", NotificationType.INFORMATION, null).notify(project);
        ConfigProvider.getInstance().getIgnoredRemotes().add(url);
        logger.info("Added " + url + " to list of ignored remotes because CI is disabled for its gitlab project");
        return true;
    }

    public static Optional<Mapping> createMappingWithProjectNameAndId(String remoteUrl, String host, String projectPath, String token, TokenType tokenType) {
        final Optional<Data> data = GraphQl.makeCall(host, token, projectPath, Collections.emptyList(), false);
        if (data.isEmpty()) {
            return Optional.empty();
        }

        final Mapping mapping = new Mapping();
        final org.rett.gitlab.pipelines.gitlab.mapping.Project project = data.get().getProject();
        logger.info("Determined project name " + project.getName() + " and id " + project.getId() + " for remote " + remoteUrl + " because GraphQl call returned a response");
        mapping.setRemote(remoteUrl);
        mapping.setGitlabProjectId(project.getId());
        mapping.setProjectName(project.getName());
        mapping.setHost(host);
        mapping.setProjectPath(projectPath);
        ConfigProvider.saveToken(mapping, token, tokenType);
        return Optional.of(mapping);
    }

    private Map<Mapping, List<PipelineTo>> loadPipelines(boolean triggeredByUser) throws IOException {
        final Map<Mapping, List<PipelineTo>> projectToPipelines = new HashMap<>();
        final List<GitRepository> nonIgnoredRepositories = gitService.getNonIgnoredRepositories();
        if (nonIgnoredRepositories.isEmpty()) {
            logger.debug("No non-ignored git repositories");
            return Collections.emptyMap();
        }
        for (GitRepository nonIgnoredRepository : nonIgnoredRepositories) {
            for (GitRemote remote : nonIgnoredRepository.getRemotes()) {
                for (String url : remote.getUrls()) {
                    final Mapping mapping = ConfigProvider.getInstance().getMappingByRemoteUrl(url);
                    if (mapping == null) {
                        logger.debug("No mapping found for remote url ", url);
                        continue;
                    }
                    if (PipelineViewerConfigApp.getInstance().getRemotesAskAgainNextTime().contains(url) && !triggeredByUser) {
                        logger.debug("Remote ", url, " is ignored until next plugin load and reload was not triggered by user. Not loading pipelines.");
                        continue;
                    }
                    logger.debug("Loading pipelines for remote ", mapping.getRemote());
                    final List<PipelineTo> pipelines = loadPipelines(mapping);
                    logger.debug("Loaded ", pipelines.size(), " pipelines for remote ", mapping.getRemote());

                    projectToPipelines.put(mapping, pipelines);
                }
            }
        }

        return projectToPipelines;
    }

    private List<PipelineTo> loadPipelines(Mapping mapping) throws IOException {
        final List<PipelineTo> pipelines = new ArrayList<>();
        try {
            if (openTokenDialogsByMapping.contains(mapping)) {
                //No sense making queries
                logger.debug("Not loading pipelines. Token dialog open for ", mapping);
                return Collections.emptyList();
            }
            //Note: Gitlab GraphQL does not return the ref (branch name): https://gitlab.com/gitlab-org/gitlab/-/issues/230405
            pipelines.addAll(makePipelinesUrlCall(1, mapping));
            for (int i = 0; i < pipelines.size(); i++) {
                List<JobsTo> jobs = makePipelineJobsUrlCall(pipelines.get(i).getId(), mapping);
                pipelines.get(i).setJobs(jobs);
                logger.debug("Found jobs for pipeline " + pipelines.get(i).getId() + " - " + jobs.toString());
            }
        } catch (FailsafeException | LoginException e) {
            if (e instanceof FailsafeException && e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            logger.debug("Login exception while loading pipelines", e);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (openTokenDialogsByMapping.contains(mapping)) {
                    logger.debug("Not showing another token dialog for ", mapping);
                    //Just to make sure
                    return;
                }
                final Pair<String, TokenType> tokenAndType = ConfigProvider.getTokenAndType(mapping.getRemote(), mapping.getHost());
                final String oldToken = Strings.isNullOrEmpty(tokenAndType.getLeft()) ? "<empty>" : tokenAndType.getLeft();
                final String oldTokenForLog = Strings.isNullOrEmpty(tokenAndType.getLeft()) ? "<empty>" : "with length " + tokenAndType.getLeft().length();
                final TokenType tokenType = tokenAndType.getRight();
                logger.info("Showing input dialog for token for remote " + mapping.getRemote() + " with old token " + oldTokenForLog);
                final TokenType preselectedTokenType = tokenAndType.getLeft() == null ? TokenType.PERSONAL : tokenType;
                openTokenDialogsByMapping.add(mapping);
                final TokenDialog tokenDialog = new TokenDialog("Unable to log in to gitlab. Please enter the access token for access to " + mapping.getRemote() + ". Enter nothing to delete it.", oldToken, preselectedTokenType);
                final Optional<Pair<String, TokenType>> response = tokenDialog.showDialog();
                openTokenDialogsByMapping.remove(mapping);

                if (response.isEmpty()) {
                    logger.info("Token dialog cancelled, not changing anything. Will not load pipelines until next plugin load or triggered manually");
                    PipelineViewerConfigApp.getInstance().getRemotesAskAgainNextTime().add(mapping.getRemote());
                } else if (Strings.isNullOrEmpty(response.get().getLeft())) {
                    logger.info("No token entered, setting token to null for remote " + mapping.getRemote());
                    ConfigProvider.saveToken(mapping, null, response.get().getRight());
                } else {
                    logger.info("New token entered for remote " + mapping.getRemote());
                    ConfigProvider.saveToken(mapping, response.get().getLeft(), response.get().getRight());
                }
                PipelineViewerConfigApp.getInstance().getRemotesAskAgainNextTime().remove(mapping.getRemote());
                project.getService(BackgroundUpdateService.class).update(project, false);
                //Token has changed, user probably wants to retry

            });
            return Collections.emptyList();
        }
        return pipelines;
    }

    private List<PipelineTo> makePipelinesUrlCall(int page, Mapping mapping) throws IOException, LoginException {
        String url;
        try {
            URIBuilder uriBuilder = new URIBuilder(mapping.getHost() + "/api/v4/projects/" + mapping.getGitlabProjectId() + "/pipelines");

            uriBuilder.addParameter("page", String.valueOf(page))
                    .addParameter("per_page", "25");


            url = uriBuilder.build().toString();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        final String json = Failsafe.with(RETRY_POLICY).get(() -> makeApiCall(url, ConfigProvider.getToken(mapping)));
        return Jackson.OBJECT_MAPPER.readValue(json, new TypeReference<>() {
        });
    }

    private List<JobsTo> makePipelineJobsUrlCall(int pipelineId, Mapping mapping) throws IOException, LoginException {
        String url;
        try {
            URIBuilder uriBuilder = new URIBuilder(mapping.getHost() + "/api/v4/projects/" + mapping.getGitlabProjectId() + "/pipelines/" + pipelineId + "/jobs");
            url = uriBuilder.build().toString();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        final String json = Failsafe.with(RETRY_POLICY).get(() -> makeApiCall(url, ConfigProvider.getToken(mapping)));
        return Jackson.OBJECT_MAPPER.readValue(json, new TypeReference<>() {
        });
    }

    public static String makeApiCall(String url, String accessToken) throws IOException, LoginException {
        try {
            URIBuilder uriBuilder = new URIBuilder(url);

            if (accessToken != null) {
                logger.debug("Using access token for access to ", url);
                uriBuilder.addParameter("private_token", accessToken);
            }

            url = uriBuilder.build().toString();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        final String response;
        final String cleanedUrl = accessToken == null ? url : url.replace(accessToken, "<accessToken>");
        if (GitlabAccessLogger.GITLAB_ACCESS_LOGGER.isDebugEnabled()) {
            GitlabAccessLogger.GITLAB_ACCESS_LOGGER.debug("Calling ", cleanedUrl);
        }
        try {
            response = HttpRequests.request(url)
                    .connectTimeout(ConfigProvider.getInstance().getConnectTimeoutSeconds() * 1000)
                    .readTimeout(ConfigProvider.getInstance().getConnectTimeoutSeconds() * 1000)
                    .readString();
        } catch (IOException e) {
            if (e instanceof HttpRequests.HttpStatusException) {
                HttpRequests.HttpStatusException statusException = (HttpRequests.HttpStatusException) e;
                //Unfortunately gitlab returns a 404 if the project was found but could not be accessed. We must interpret 404 like 401
                if (statusException.getStatusCode() == 401 || statusException.getStatusCode() == 404) {
                    logger.info("Unable to load pipelines. Interpreting as login error. Status code " + statusException.getStatusCode() + ". Message: " + statusException.getMessage());
                    throw new LoginException("Unable to login to " + cleanedUrl);
                } else {
                    throw new IOException("Unable to access " + cleanedUrl + ". Status code: " + statusException.getStatusCode() + ". Status message: " + e.getMessage());
                }
            }
            throw new IOException("Unable to access " + cleanedUrl + ". Error message: " + e.getMessage(), e);
        }

        return response;
    }

    public String getGitlabHtmlBaseUrl(String projectId) {
        final Mapping mapping = ConfigProvider.getInstance().getMappingByProjectId(projectId);
        return mapping.getHost() + "/" + mapping.getProjectPath();
    }

    public static Optional<HostAndProjectPath> getHostProjectPathFromRemote(String remote) {
        final Optional<Mapping> similarMapping = ConfigProvider.getInstance().getMappings().stream()
                .filter(x -> remote.startsWith(x.getHost()))
                .findFirst();
        if (similarMapping.isPresent()) {
            logger.debug("Found existing mapping for host ", similarMapping.get().getHost(), " and remote ", similarMapping.get().getRemote());
            final String host = similarMapping.get().getHost();
            final String projectPath = getCleanProjectPath(remote.substring(similarMapping.get().getHost().length()));
            logger.debug("Found existing mapping for host ", similarMapping.get().getHost(), " and remote ", similarMapping.get().getRemote());
            final HostAndProjectPath hostAndProjectPath = new HostAndProjectPath(host, projectPath);
            logger.info("Determined host " + hostAndProjectPath.getHost() + " and project path " + hostAndProjectPath.getProjectPath() + " for http remote " + remote + " from similar mapping");
            return Optional.of(hostAndProjectPath);
        }
        final Matcher sshMatcher = REMOTE_GIT_SSH_PATTERN.matcher(remote);
        if (sshMatcher.matches()) {
            final HostAndProjectPath hostAndProjectPath = new HostAndProjectPath("https://" + sshMatcher.group("host"), StringUtils.removeEnd(sshMatcher.group("projectPath"), ".git"));
            logger.info("Determined host " + hostAndProjectPath.getHost() + " and project path " + hostAndProjectPath.getProjectPath() + " from ssh remote " + remote);
            return Optional.of(hostAndProjectPath);
        }
        final Matcher httpMatcher = REMOTE_GIT_HTTP_PATTERN.matcher(remote);
        if (httpMatcher.matches()) {
            if (remote.startsWith("https://gitlab.com")) {
                final String host = "https://gitlab.com";
                final String projectPath = getCleanProjectPath(remote.substring("https://gitlab.com/".length()));
                final HostAndProjectPath hostAndProjectPath = new HostAndProjectPath(host, projectPath);
                logger.debug("Recognized gitlab.com HTTPS remote - determined ", hostAndProjectPath);
                return Optional.of(hostAndProjectPath);
            }
            //For self hosted instances it's impossible to determine which part of the path is part of the host or the project.
            //So we try each part of the path and see if we get a response that looks like gitlab
            final String fullUrl = httpMatcher.group("url");
            StringBuilder testUrl = new StringBuilder(httpMatcher.group("scheme"));
            for (String part : fullUrl.split("/")) {
                testUrl.append(part).append("/");

                final String response;
                try {
                    logger.debug("Trying URL ", testUrl);
                    response = ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            return HttpRequests
                                    .request(testUrl.toString())
                                    .connectTimeout(ConfigProvider.getInstance().getConnectTimeoutSeconds() * 1000)
                                    .readTimeout(ConfigProvider.getInstance().getConnectTimeoutSeconds() * 1000)
                                    .readString();
                        } catch (Exception e) {
                            logger.info("Unable to retrieve host and project path from remote " + remote, e);
                            return null;
                        }
                    }).get();
                } catch (Exception e) {
                    logger.info("Unable to retrieve host and project path from remote " + remote, e);
                    return tryBestGuessForRemote(remote);
                }
                if (response == null) {
                    return tryBestGuessForRemote(remote);
                }
                if (response.toLowerCase().contains("gitlab")) {
                    final HostAndProjectPath hostAndProjectPath = new HostAndProjectPath(StringUtils.removeEndIgnoreCase(testUrl.toString(), "/"), getCleanProjectPath(remote.substring(testUrl.length())));
                    logger.info("Determined host " + hostAndProjectPath.getHost() + " and project path " + hostAndProjectPath.getProjectPath() + " from http remote " + remote + " because that host returned a response containing 'gitlab'");
                    return Optional.of(hostAndProjectPath);
                }
                logger.debug("Response from ", testUrl, " does not contain \"gitlab\"");
            }
        }
        logger.info("Unable to parse remote " + remote);
        return tryBestGuessForRemote(remote);
    }

    @NotNull
    private static Optional<HostAndProjectPath> tryBestGuessForRemote(String remote) {
        logger.debug("Trying to parse helpful data for dialog from ", remote);
        final Matcher bestGuessMatcher = REMOTE_BEST_GUESS_PATTERN.matcher(remote);
        if (bestGuessMatcher.matches()) {
            final String host = bestGuessMatcher.group("host");
            final String projectPath = StringUtils.removeEnd(bestGuessMatcher.group("projectPath"), ".git");
            logger.debug("Best guess: Host: ", host, ". Project path: ", projectPath);
            return Optional.of(new HostAndProjectPath(host, projectPath));
        }
        logger.info("Unable to find any meaningful data in remote " + remote);
        return Optional.empty();
    }

    private static String getCleanProjectPath(String projectPath) {
        return StringUtils.removeStart(StringUtils.removeEndIgnoreCase(projectPath, ".git"), "/");
    }

    @Override
    public void dispose() {

    }

    static class LoginException extends Exception {

        public LoginException(String message) {
            super(message);
        }
    }


}
