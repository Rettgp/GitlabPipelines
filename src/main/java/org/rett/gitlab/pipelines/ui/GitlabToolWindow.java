package org.rett.gitlab.pipelines.ui;

import com.google.common.base.Strings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.TextTransferable;
import org.rett.gitlab.pipelines.BackgroundUpdateService;
import org.rett.gitlab.pipelines.DateTime;
import org.rett.gitlab.pipelines.PipelineFilter;
import org.rett.gitlab.pipelines.PipelineJobStatus;
import org.rett.gitlab.pipelines.JobStatus;
import org.rett.gitlab.pipelines.ReloadListener;
import org.rett.gitlab.pipelines.UrlOpener;
import org.rett.gitlab.pipelines.config.ConfigChangedListener;
import org.rett.gitlab.pipelines.config.ConfigProvider;
import org.rett.gitlab.pipelines.config.Mapping;
import org.rett.gitlab.pipelines.config.PipelineViewerConfigApp;
import org.rett.gitlab.pipelines.config.PipelineViewerConfigProject;
import org.rett.gitlab.pipelines.git.GitService;
import org.rett.gitlab.pipelines.gitlab.GitlabService;
import org.rett.gitlab.pipelines.lights.LightsControl;
import git4idea.GitUtil;
import git4idea.branch.GitBrancher;
import git4idea.repo.GitRepository;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@SuppressWarnings({"Convert2Lambda", "DialogTitleCapitalization", "ReplaceNullCheck"})
public class GitlabToolWindow {

    private static final Logger logger = Logger.getInstance(GitlabToolWindow.class);

    private static final String GITLAB_URL_PLACEHOLDER = "%GITLAB_URL%";
    private static final String PROJECT_ID_PLACEHOLDER = "%PROJECT_ID%";
    private static final String SOURCE_BRANCH_PLACEHOLDER = "%SOURCE_BRANCH%";
    private static final String TARGET_BRANCH_PLACEHOLDER = "%TARGET_BRANCH%";
    private static final String NEW_MERGE_REQUEST_URL_TEMPLATE = "%GITLAB_URL%/-/merge_requests/new?utf8=%E2%9C%93&merge_request%5Bsource_project_id%5D=%PROJECT_ID%&merge_request%5Bsource_branch%5D=%SOURCE_BRANCH%&merge_request%5Btarget_project_id%5D=%PROJECT_ID%";
    private static final String NEW_MERGE_REQUEST_URL_TARGET_BRANCH_POSTFIX = "&merge_request%5Btarget_branch%5D=%TARGET_BRANCH%";

    private final Banner banner = new Banner();
    private JPanel toolWindowContent;
    private final JTable pipelineTable;
    private JScrollPane tableScrollPane;
    private JPanel tablePanel;
    private boolean initialLoad = true;

    private final PipelineTableModel tableModel;

    private final GitlabService gitlabService;
    private final BackgroundUpdateService backgroundUpdateService;
    private final MessageBus messageBus;
    private final PipelineFilter statusFilter;
    private TableRowSorter<PipelineTableModel> tableSorter;
    private final Project project;
    private final GitService gitService;

    private JCheckBox showForAllCheckbox;
    private Map<PipelineJobStatus, Container> jobsCellCache = new HashMap();
    JPanel actionPanel;


    public GitlabToolWindow(Project project) {
        this.project = project;
        gitlabService = project.getService(GitlabService.class);
        backgroundUpdateService = project.getService(BackgroundUpdateService.class);
        messageBus = project.getMessageBus();
        statusFilter = project.getService(PipelineFilter.class);
        gitService = project.getService(GitService.class);

        tableModel = new PipelineTableModel();
        final MessageBusConnection messageBusConnection = messageBus.connect();
        messageBusConnection.subscribe(ReloadListener.RELOAD, new ReloadListener() {
            @Override
            public void reload(Map<Mapping, List<PipelineJobStatus>> pipelineInfos) {
                if (project.isDisposed()) {
                    messageBusConnection.disconnect();
                    return;
                }
                ApplicationManager.getApplication().invokeLater(GitlabToolWindow.this::updatePipelinesDisplay);
            }
        });
        if (!gitlabService.getPipelineInfos().isEmpty()) {
            //Window was not displayed on startup and didn't receive any events, so we need to update the pipelines now
            ApplicationManager.getApplication().invokeLater(this::updatePipelinesDisplay);
        }
        updateTableWhenMonitoringMultipleRemotesButOnlyShowingPipelinesForOne();
        pipelineTable = new JBTable(tableModel) {
            @Override
            public TableCellRenderer getCellRenderer(int row, int column) {
                TableCellRenderer cellRenderer = super.getCellRenderer(row, column);
                if (column == 0) {
                    return getProjectCellRenderer();
                }
                if (column == 1) {
                    return getBranchCellRenderer();
                }
                if (column == 2) {
                    return getStatusCellRenderer();
                }
                if (column == 3) {
                    return getAuthorCellRenderer();
                }
                if (column == 4) {
                    return getDateCellRenderer();
                }
                if (column == 5) {
                    return getCommitCellRenderer();
                }
                if (column == 6 || column == 7) {
                    return getLinkCellRenderer();
                }
                return cellRenderer;
            }

            @NotNull
            @Override
            public Component prepareRenderer(@NotNull TableCellRenderer renderer, int rowIndex, int columnIndex) {
                Component component = super.prepareRenderer(renderer, rowIndex, columnIndex);
                int rendererWidth = component.getPreferredSize().width;
                TableColumn column = getColumnModel().getColumn(columnIndex);
                column.setPreferredWidth(Math.max(rendererWidth + getIntercellSpacing().width, column.getPreferredWidth()));
                return component;
            }
        };
        pipelineTable.setAutoCreateRowSorter(true);
        pipelineTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        pipelineTable.setUpdateSelectionOnSort(true);
        pipelineTable.getTableHeader().setReorderingAllowed(false);

        MouseAdapter urlMouseAdapter = getBranchTableMouseAdapter();
        pipelineTable.addMouseListener(urlMouseAdapter);
        pipelineTable.addMouseMotionListener(urlMouseAdapter);

        pipelineTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pipelineTable.setCellSelectionEnabled(true);

        pipelineTable.setIntercellSpacing(new Dimension(5, 0));

        createTablePanel(project);
        handleEnabledState(project);
        messageBusConnection.subscribe(ConfigChangedListener.CONFIG_CHANGED, new ConfigChangedListener() {
            @Override
            public void configChanged() {
                if (project.isDisposed()) {
                    messageBusConnection.disconnect();
                    return;
                }
                handleEnabledState(project);
                showForAllCheckbox.setSelected(PipelineViewerConfigProject.getInstance(project).isShowPipelinesForAll());
                toggleShowForAllCheckboxVisibility();
            }
        });
        toggleShowForAllCheckboxVisibility();

    }

    private void updateTableWhenMonitoringMultipleRemotesButOnlyShowingPipelinesForOne() {
        messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void fileOpenedSync(@NotNull FileEditorManager source, @NotNull VirtualFile file, @NotNull Pair<FileEditor[], FileEditorProvider[]> editors) {
                if (gitlabService.getPipelineInfos().size() > 1 && !showForAllCheckbox.isSelected()) {
                    updatePipelinesDisplay();
                }
            }

            @Override
            public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                if (gitlabService.getPipelineInfos().size() > 1 && !showForAllCheckbox.isSelected()) {
                    updatePipelinesDisplay();
                }
            }
        });
    }

    private void handleEnabledState(Project project) {
        final boolean enabled = PipelineViewerConfigProject.getInstance(project).isEnabled();
        logger.debug("Enabled for project: ", enabled);
        ApplicationManager.getApplication().invokeLater(() -> {
            toolWindowContent.setEnabled(enabled);
            pipelineTable.setEnabled(enabled);
            tablePanel.setEnabled(enabled);
            tableScrollPane.setEnabled(enabled);

            if (!enabled) {
                tableModel.clearRows();
                tableModel.fireTableDataChanged();
            }
        });
    }

    private JBPopupMenu getBranchPopupMenu(PipelineJobStatus selectedPipelineStatus) {
        JBPopupMenu branchPopupMenu = new JBPopupMenu();
        branchPopupMenu.add(new AbstractAction("Show traffic lights for this branch") {
            @Override
            public void actionPerformed(ActionEvent e) {
                PipelineViewerConfigProject.getInstance(project).setShowLightsForBranch(selectedPipelineStatus.getBranchName());
                runLoadPipelinesTask();
            }
        });
        branchPopupMenu.add(new AbstractAction("Never show results for this branch") {
            @Override
            public void actionPerformed(ActionEvent e) {
                ConfigProvider.getInstance().getBranchesToIgnore(project).add(selectedPipelineStatus.getBranchName());
                runLoadPipelinesTask();
            }
        });
        branchPopupMenu.add(new AbstractAction("Always show results for this branch") {
            @Override
            public void actionPerformed(ActionEvent e) {
                ConfigProvider.getInstance().getBranchesToWatch(project).add(selectedPipelineStatus.getBranchName());
                runLoadPipelinesTask();
            }
        });
        branchPopupMenu.add(new AbstractAction("Create merge request for this branch") {
            @Override
            public void actionPerformed(ActionEvent e) {
                openMergeRequestUrlForSelectedBranch(selectedPipelineStatus);
            }
        });
        branchPopupMenu.add(new AbstractAction("Checkout this branch") {
            @Override
            public void actionPerformed(ActionEvent e) {
                GitBrancher brancher = GitBrancher.getInstance(project);
                brancher.checkout(selectedPipelineStatus.getBranchName(), false, GitUtil.getRepositoryManager(project).getRepositories(), null);
            }
        });
        branchPopupMenu.add(new AbstractAction("Copy pipeline URL to clipboard") {
            @Override
            public void actionPerformed(ActionEvent e) {
                CopyPasteManager.getInstance().setContents(new TextTransferable(selectedPipelineStatus.getPipelineLink()));
            }
        });
        if (selectedPipelineStatus.getMergeRequestLink() != null) {
            branchPopupMenu.add(new AbstractAction("Copy merge request URL to clipboard") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    CopyPasteManager.getInstance().setContents(new TextTransferable(selectedPipelineStatus.getMergeRequestLink()));
                }
            });
        }

        return branchPopupMenu;
    }

    private void openMergeRequestUrlForSelectedBranch(PipelineJobStatus pipelineJobStatus) {
        String url = getNewMergeRequestUrl(pipelineJobStatus);
        logger.info("Opening URL " + url);
        UrlOpener.openUrl(url);
    }

    @NotNull
    private String getNewMergeRequestUrl(PipelineJobStatus pipelineJobStatus) {
        ConfigProvider config = ConfigProvider.getInstance();
        String url = NEW_MERGE_REQUEST_URL_TEMPLATE
                .replace(GITLAB_URL_PLACEHOLDER, gitlabService.getGitlabHtmlBaseUrl(pipelineJobStatus.getProjectId()))
                .replace(PROJECT_ID_PLACEHOLDER, pipelineJobStatus.getProjectId())
                .replace(SOURCE_BRANCH_PLACEHOLDER, pipelineJobStatus.getBranchName());
        if (config.getMergeRequestTargetBranch(project) != null) {
            url += NEW_MERGE_REQUEST_URL_TARGET_BRANCH_POSTFIX.replace(TARGET_BRANCH_PLACEHOLDER, config.getMergeRequestTargetBranch(project));
        }
        return url;
    }

    private PipelineJobStatus getSelectedBranch(Point pointClicked) {
        SelectedCell cell;
        try {
            cell = getSelectedTableCell(pointClicked);
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
        if (cell.rowIndex == -1 || cell.rowIndex > tableModel.getRows().size()) {
            return null;
        }
        return tableModel.getRows().get(cell.rowIndex);
    }

    private SelectedCell getSelectedTableCell(Point pointClicked) {
        int viewRow = pipelineTable.getSelectedRow();
        int selectedColumn = pipelineTable.columnAtPoint(pointClicked);
        int modelRow = pipelineTable.convertRowIndexToModel(viewRow);

        return new SelectedCell(modelRow, selectedColumn, tableModel.getValueAt(modelRow, selectedColumn));
    }

    private void createTablePanel(Project project) {
        AnActionButton refreshActionButton = new AnActionButton("Refresh", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                backgroundUpdateService.update(project, true);
            }

            @Override
            public JComponent getContextComponent() {
                return pipelineTable;
            }

        };

        AnActionButton turnOffLightsAction = new AnActionButton("Turn Off Lights", IconLoader.getIcon("/trafficLightsOff.png", GitlabToolWindow.class)) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                LightsControl.turnOffAllLights();
            }

            @Override
            public JComponent getContextComponent() {
                return pipelineTable;
            }
        };

        AnActionButton copyCurrentGitHash = new AnActionButton("Copy current git hash to clipboard", "Copy current git hash to clipboard", null) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                gitService.copyHashToClipboard();
            }

            @Override
            public JComponent getContextComponent() {
                return pipelineTable;
            }
        };


        DefaultActionGroup actionGroup = new DefaultActionGroup(refreshActionButton, turnOffLightsAction, copyCurrentGitHash);

        ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, actionGroup, true);
        actionToolbar.setTargetComponent(this.getContent());

        actionPanel = new JPanel(new MigLayout("ins 0, fill", "[left]0[left, fill]push[right]", "center"));
        actionPanel.add(actionToolbar.getComponent());
        SearchTextField filterField = new SearchTextField(false, null);
        filterField.getTextEditor().setToolTipText("Filter by substrings of branch names");

        filterField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                try {
                    String text = e.getDocument().getText(0, e.getDocument().getLength());
                    if (text == null || text.isEmpty()) {
                        tableSorter.setRowFilter(null);
                    } else {
                        RowFilter<PipelineTableModel, Integer> filter = new RowFilter<>() {
                            @Override
                            public boolean include(Entry<? extends PipelineTableModel, ? extends Integer> entry) {
                                return entry.getModel().getRows().get(entry.getIdentifier()).getBranchNameDisplay().toLowerCase().contains(text.toLowerCase());
                            }
                        };
                        tableSorter.setRowFilter(filter);
                    }
                } catch (BadLocationException ex) {
                    logger.error(e);
                }
            }
        });
        showForAllCheckbox = new JCheckBox("Show all");
        showForAllCheckbox.setToolTipText("Switch between display of pipelines for current project and all associated projects");
        toggleShowForAllCheckboxVisibility();
        showForAllCheckbox.setSelected(PipelineViewerConfigProject.getInstance(project).isShowPipelinesForAll());
        showForAllCheckbox.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                if (ConfigProvider.getInstance().isConfigOpen()) {
                    return;
                }
                updatePipelinesDisplay();
                PipelineViewerConfigProject.getInstance(project).setShowPipelinesForAll(showForAllCheckbox.isSelected());
            }
        });
        actionPanel.add(filterField);

        addBanner(project);

        tablePanel.add(actionPanel, BorderLayout.NORTH, 0);
        tablePanel.add(new JBScrollPane(pipelineTable), BorderLayout.CENTER, 1);
    }

    private void addBanner(Project project) {
        banner.setText("Waiting for decision how to handle untracked remote");

        final AbstractAction openDialogAction = new AbstractAction("Open dialog") {
            @Override
            public void actionPerformed(ActionEvent e) {
                final List<UntrackedRemoteNotification> alreadyOpenNotifications = UntrackedRemoteNotification.getAlreadyOpenNotifications(project);
                for (UntrackedRemoteNotification openNotification : alreadyOpenNotifications) {
                    openNotification.expire();
                    openNotification.openDialogForUnmappedRemote();
                }
            }
        };
        banner.addAction(openDialogAction);

        project.getMessageBus().connect().subscribe(UntrackedRemoteNotificationState.UNTRACKED_REMOTE_FOUND, (UntrackedRemoteNotificationState) isOpen -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                logger.debug("Setting banner visible: ", isOpen);
                if (isOpen) {
                    actionPanel.add(banner);
                    final List<UntrackedRemoteNotification> openNotifications = UntrackedRemoteNotification.getAlreadyOpenNotifications(project);
                    //Sometimes the number of notification dialogs is not yet correct and it thinks we don't have any open
                    if (openNotifications.size() <= 1) {
                        openDialogAction.putValue(Action.NAME, "Open dialog");
                        banner.setText("Waiting for decision how to handle untracked remote");
                    } else {
                        openDialogAction.putValue(Action.NAME, "Open " + openNotifications.size() + " dialogs");
                        banner.setText("Waiting for decision how to handle untracked remotes");
                    }
                } else {
                    actionPanel.remove(banner);
                }
            });
        });


        actionPanel.remove(banner);

    }

    private void toggleShowForAllCheckboxVisibility() {
        final boolean doShow = gitService.getNonIgnoredRepositories().size() > 1;
        if (doShow) {
            if (Arrays.asList(actionPanel.getComponents()).contains(showForAllCheckbox)) {
                return;
            }
            actionPanel.add(showForAllCheckbox, actionPanel.getComponents().length - 1);
        } else {
            if (!Arrays.asList(actionPanel.getComponents()).contains(showForAllCheckbox)) {
                return;
            }
            actionPanel.remove(showForAllCheckbox);
        }

    }

    private void runLoadPipelinesTask() {
        final boolean started = backgroundUpdateService.startBackgroundTask();
        if (!started) {
            backgroundUpdateService.update(this.project, true);
        }
    }

    private void updatePipelinesDisplay() {
        tableScrollPane.setEnabled(true);
        toggleShowForAllCheckboxVisibility();

        jobsCellCache.clear();
        tableModel.clearRows();
        tableModel.setRows(getStatusesToShow());
        logger.debug(String.format("Showing %d statuses for %d projects", tableModel.getRows().size(), gitlabService.getPipelineInfos().size()));
        final TableColumn column = pipelineTable.getColumn(pipelineTable.getColumnName(0));
        final boolean multipleProjects = !tableModel.getRows().isEmpty() && tableModel.getRows().stream().map(x -> x.projectId).collect(Collectors.toSet()).size() == 1;
        if (multipleProjects || !showForAllCheckbox.isSelected()) {
            column.setMinWidth(0);
            column.setMaxWidth(0);
            column.setPreferredWidth(0);
            column.setWidth(0);
        } else {
            column.setMinWidth(15);
            column.setMaxWidth(500);
            column.setPreferredWidth(75);
            column.setWidth(75);
        }
        tableModel.fireTableDataChanged();

        if (initialLoad) {
            //Prevent resetting the sorting selected by the user on next update
            sortTableByBranchName();
            initialLoad = false;
        }
    }

    private Container getPipelineStatusContainer(PipelineJobStatus pipeline) {
        if (jobsCellCache.containsKey(pipeline)) {
            return jobsCellCache.get(pipeline);
        }

        JPanel jobsPanel = new JPanel() {
            @Override
            public String getToolTipText(MouseEvent event) {
                int offset = 5 + 15 + 5 + 32;
                Component[] components = super.getComponents();
                for(int i = 0; i < components.length; i+=2 ) {
                    int stageIndex = (int)Math.floor(i / 2);
                    int startX = stageIndex * offset;
                    int endX = startX + 32;
                    if (event.getX() >= startX && event.getX() <= endX) {
                        JBLabel label = (JBLabel)components[i];
                        return label.getToolTipText();
                    }
                }
                return super.getToolTipText(event);
            }
        };
        BoxLayout boxLayout = new BoxLayout(jobsPanel, BoxLayout.X_AXIS);
        jobsPanel.setLayout(boxLayout);
        List<JobStatus> jobs = pipeline.getJobs();
        for (int i = 0; i < jobs.size(); i++) {
            final String iconPath = jobs.get(i).iconPath;
            final Icon statusIcon = IconLoader.getIcon(iconPath, GitlabToolWindow.class);
            JBLabel label = new JBLabel(statusIcon);
            label.setToolTipText(jobs.get(i).name + ": " + jobs.get(i).status);
            label.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            label.setAlignmentY(Component.CENTER_ALIGNMENT);
            label.setAlignmentX(Component.CENTER_ALIGNMENT);
            jobsPanel.add(label);
            if (jobs.size() > 1 && i < jobs.size() - 1){
                final Icon dividerIcon = IconLoader.getIcon("toolWindow/divider.svg", GitlabToolWindow.class);
                final JBLabel divider = new JBLabel(dividerIcon);
                label.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
                divider.setAlignmentY(Component.CENTER_ALIGNMENT);
                divider.setAlignmentX(Component.CENTER_ALIGNMENT);
                jobsPanel.add(divider);
            }
        }

        jobsCellCache.put(pipeline, jobsPanel);
        return jobsPanel;
    }

    /**
     * Returns a list of statuses containing for each branch either all statuses up to the last final run (failed or successful) if such a run exists or otherwise just the latest run.
     *
     * @return List of filtered statuses.
     */
    private List<PipelineJobStatus> getStatusesToShow() {
        List<PipelineJobStatus> newRows = new ArrayList<>();
        for (Map.Entry<Mapping, List<PipelineJobStatus>> mappingAndPipelines : gitlabService.getPipelineInfos().entrySet()) {

            //If pipelines for multiple projects exist and pipelines are only to be shown for the current one skip all others
            final Mapping mapping = mappingAndPipelines.getKey();
            if (gitlabService.getPipelineInfos().size() > 1 && !showForAllCheckbox.isSelected()) {
                final GitRepository currentRepository = gitService.guessCurrentRepository();
                final GitRepository repoForMapping = gitService.getRepositoryByRemoteUrl(mapping.getRemote());
                if (!Objects.equals(repoForMapping, currentRepository)) {
                    logger.debug("Not showing pipelines for ", repoForMapping, " because it doesn't match the current repo ", currentRepository);
                    continue;
                }
            }

            List<PipelineJobStatus> statuses = new ArrayList<>(statusFilter.filterPipelines(mapping, mappingAndPipelines.getValue(), false));
            statuses.sort(Comparator.comparing(x -> ((PipelineJobStatus) x).creationTime).reversed());
            Map<String, List<PipelineJobStatus>> branchesToStatuses = statuses.stream().collect(Collectors.groupingBy(PipelineJobStatus::getBranchNameDisplay));
            logger.debug("Found ", branchesToStatuses.size(), " branches to show pipelines for");
            for (Map.Entry<String, List<PipelineJobStatus>> entry : branchesToStatuses.entrySet()) {
                logger.debug("Display Job Statuses: ", entry.getValue());
                newRows.addAll(entry.getValue());
            }
        }
        newRows.sort(Comparator.comparing(x -> ((PipelineJobStatus) x).creationTime).reversed());
        return newRows;
    }

    private void sortTableByBranchName() {
        tableSorter = new TableRowSorter<>(tableModel);
        pipelineTable.setRowSorter(tableSorter);
        List<RowSorter.SortKey> sortKeys = new ArrayList<>();
        sortKeys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
        sortKeys.add(new RowSorter.SortKey(1, SortOrder.ASCENDING));
        sortKeys.add(new RowSorter.SortKey(4, SortOrder.DESCENDING));
        tableSorter.setSortKeys(sortKeys);
        tableSorter.sort();
    }

    public JPanel getContent() {
        return toolWindowContent;
    }

    private void createUIComponents() {
    }

    @NotNull
    private MouseAdapter getBranchTableMouseAdapter() {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                PipelineJobStatus selectedPipelineStatus;
                try {
                    selectedPipelineStatus = getSelectedBranch(e.getPoint());
                } catch (Exception ex) {
                    //Can happen in some (unknown) cases, probably when the table is updated while the mouse is clicked
                    logger.debug(ex);
                    return;
                }
                if (selectedPipelineStatus == null) {
                    return;
                }
                if (e.getButton() == MouseEvent.BUTTON3) {
                    getBranchPopupMenu(selectedPipelineStatus).show(e.getComponent(), e.getX(), e.getY());
                }

                if (e.getButton() == MouseEvent.BUTTON1) {
                    int viewRow = pipelineTable.getSelectedRow();
                    if (viewRow == -1) {
                        return;
                    }

                    int selectedColumn = pipelineTable.columnAtPoint(e.getPoint());
                    if (selectedColumn != 6 && selectedColumn != 7) {
                        return;
                    }

                    String url;
                    if (selectedColumn == 6) {
                        url = selectedPipelineStatus.getPipelineLink();
                    } else {
                        if (selectedPipelineStatus.mergeRequestLink != null) {
                            url = selectedPipelineStatus.mergeRequestLink;
                        } else {
                            url = getNewMergeRequestUrl(selectedPipelineStatus);
                        }
                    }

                    logger.debug("Opening URL ", url);
                    UrlOpener.openUrl(url);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int columnIndex = pipelineTable.columnAtPoint(e.getPoint());
                int rowIndex = pipelineTable.rowAtPoint(e.getPoint());
                if (columnIndex == 6 && rowIndex > -1 && rowIndex < tableModel.getRowCount()) {
                    pipelineTable.setCursor(new Cursor(Cursor.HAND_CURSOR));
                } else {
                    pipelineTable.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
                }
            }
        };
    }

    private TableCellRenderer getLinkCellRenderer() {
        return new TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                final String url = (String) value;
                if (url == null && column == 4) {
                    //Pipeline empty, shouldn't happen, but who knows...
                    logger.warn("Empty URL for pipeline");
                    return new JBLabel("");
                }
                final JLabel label;
                if (PipelineViewerConfigApp.getInstance().getDisplayType() == PipelineViewerConfigApp.DisplayType.ICON) {
                    if (value == null) {
                        label = new JBLabel(IconLoader.getIcon("/toolWindow/add.png", GitlabToolWindow.class));
                    } else {
                        label = new JBLabel(IconLoader.getIcon("/toolWindow/external_link_arrow.png", GitlabToolWindow.class));
                    }
                } else {
                    //Links and IDs
                    if (value == null) {
                        //Show a link to create a new merge request
                        label = new JBLabel(IconLoader.getIcon("/toolWindow/add.png", GitlabToolWindow.class));
                    } else {
                        if (PipelineViewerConfigApp.getInstance().getDisplayType() == PipelineViewerConfigApp.DisplayType.LINK) {
                            label = new JBLabel(url);
                        } else {
                            label = new JBLabel(url.substring(url.lastIndexOf("/") + 1));
                        }
                        label.setForeground(JBColor.BLUE);
                        Map<TextAttribute, Object> attributes = new HashMap<>(label.getFont().getAttributes());
                        attributes.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
                        label.setFont(label.getFont().deriveFont(attributes));
                    }
                }
                if (column == 6 && PipelineViewerConfigApp.getInstance().getDisplayType() != PipelineViewerConfigApp.DisplayType.LINK) {
                    label.setToolTipText(url);
                }
                return label;
            }
        };
    }


    private TableCellRenderer getDateCellRenderer() {
        return new TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                ZonedDateTime dateTime = (ZonedDateTime) value;
                return new JLabel(DateTime.formatDateTime(dateTime));
            }
        };
    }

    private TableCellRenderer getProjectCellRenderer() {
        return new TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                String projectId = (String) value;
                if (ConfigProvider.getInstance().getMappingByProjectId(projectId) != null) {
                    return new JLabel(Strings.nullToEmpty(ConfigProvider.getInstance().getMappingByProjectId(projectId).getProjectName()));
                }
                return new JBLabel();
            }
        };
    }

    private TableCellRenderer getBranchCellRenderer() {
        return new TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                String branchName = (String) value;
                JLabel jLabel = new JLabel(branchName);

                if (Objects.equals(PipelineViewerConfigProject.getInstance(project).getShowLightsForBranch(), branchName)) {
                    jLabel.setIcon(IconLoader.getIcon("/trafficLights.png", GitlabToolWindow.class));
                } else if (PipelineFilter.isMatch(branchName, ConfigProvider.getInstance().getBranchesToWatch(project))) {
                    jLabel.setIcon(AllIcons.General.InspectionsEye);
                }

                return jLabel;
            }
        };
    }

    private TableCellRenderer getStatusCellRenderer() {
        return new TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                PipelineJobStatus pipeline = (PipelineJobStatus)value;
                return getPipelineStatusContainer(pipeline);
            }
        };
    }

    private TableCellRenderer getAuthorCellRenderer() {
        return new TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Pair authorInfo = (Pair)value;
                JLabel label = new JLabel((String)authorInfo.first);
                if (authorInfo.second != null) {
                    label = new JBLabel((Icon)authorInfo.second);
                    label.setPreferredSize(new Dimension(32, 32));
                    label.setToolTipText((String)authorInfo.first);
                }

                return label;
            }
        };
    }

    private TableCellRenderer getCommitCellRenderer() {
        return new TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                return new JLabel((String)value);
            }
        };
    }

    private static class PipelineTableModel extends AbstractTableModel {
        private List<PipelineJobStatus> rows = new ArrayList<>();
        public List<TableRowDefinition> definitions = Arrays.asList(
                new TableRowDefinition("Project", x -> x.projectId),
                new TableRowDefinition("Branch", PipelineJobStatus::getBranchNameDisplay),
                new TableRowDefinition("Status", x -> {
                    return x;
                }),
                new TableRowDefinition("Author", x -> {
                    Pair authorInfo = new Pair(x.author, x.authorAvatar);
                    return authorInfo;
                }),
                new TableRowDefinition("Time", x -> x.creationTime),
                new TableRowDefinition("Commit", x -> {
                    return x.commitTitle;
                }),
                new TableRowDefinition("Pipeline", x -> x.pipelineLink),
                new TableRowDefinition("MR", x -> x.mergeRequestLink)
        );

        public List<PipelineJobStatus> getRows() {
            return this.rows;
        }

        public void setRows(List<PipelineJobStatus> rows) {
            this.rows = rows;
        }

        public void clearRows() {
            this.rows.clear();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return definitions.size();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PipelineJobStatus row = this.rows.get(rowIndex);
            return definitions.get(columnIndex).tableModelRowFunction.apply(row);
        }

        @Override
        public String getColumnName(int columnIndex) {
            return definitions.get(columnIndex).title;
        }
    }


    private static class TableRowDefinition {
        public String title;
        public Function<PipelineJobStatus, Object> tableModelRowFunction;

        public TableRowDefinition(String title, Function<PipelineJobStatus, Object> tableModelRowFunction) {
            this.title = title;
            this.tableModelRowFunction = tableModelRowFunction;
        }
    }

    private static class SelectedCell {
        private final int rowIndex;
        private final int columnIndex;
        private final Object cellContent;

        public SelectedCell(int rowIndex, int columnIndex, Object cellContent) {
            this.rowIndex = rowIndex;
            this.columnIndex = columnIndex;
            this.cellContent = cellContent;
        }
    }
}
