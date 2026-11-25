package com.greencloud.ui;

import com.greencloud.env.SimulatedEnvironmentalDataProvider;
import com.greencloud.model.TaskPriority;
import com.greencloud.simulation.SimulationEngine;
import com.greencloud.simulation.SimulationEngine.ScheduledTaskSubmission;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ==============================================================================================
 * CONCURRENCY ARCHITECTURE & THREAD ISOLATION NOTE:
 * ==============================================================================================
 * This JavaFX application strictly enforces a clean architectural separation between the UI
 * Application Thread and the TaskScheduler's internal execution engine:
 *
 * 1. Pre-Run Staging Isolation: Tasks are assembled and staged into an immutable staging list
 *    before the simulation loop starts. The UI thread never interacts directly with the live
 *    TaskScheduler's PriorityQueue, synchronized blocks, or preemption stack.
 *
 * 2. Mid-Run Lightweight Observation: During active simulation, the engine runs on a dedicated
 *    background worker thread. Status updates (elapsed/remaining timers, completed count) are
 *    emitted as one-way immutable notifications dispatched to the JavaFX Application Thread via
 *    Platform.runLater(...). No live scheduler locks or blocking UI operations occur during ticks.
 *
 * 3. Post-Run Decoupled Analytics: Once the simulation terminates and persists its run artifacts
 *    to results/events.csv and results/summary.csv, the UI loads and renders the final analytics
 *    from disk via ResultsLoader. This prevents any race conditions, deadlock risks, or UI
 *    stutters associated with polling high-frequency concurrent queues during execution.
 * ==============================================================================================
 */
public class GreenCloudDashboardApp extends Application {

    private static final Logger LOGGER = Logger.getLogger(GreenCloudDashboardApp.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final ObservableList<StagedTask> stagedTasks = FXCollections.observableArrayList();
    private final ObservableList<EventRecord> eventRecords = FXCollections.observableArrayList();
    private final ExecutorService simExecutor = Executors.newSingleThreadExecutor();

    private TabPane tabPane;
    private Tab preRunTab;
    private Tab postRunTab;

    // Pre-run controls
    private Spinner<Integer> durationSpinner;
    private Spinner<Integer> tickIntervalSpinner;
    private ComboBox<String> taskTypeCombo;
    private TextField taskIdField;
    private ComboBox<TaskPriority> priorityCombo;
    private Spinner<Integer> taskDurationSpinner;
    private Spinner<Double> carbonThresholdSpinner;
    private Spinner<Integer> arrivalDelaySpinner;
    private TextField sourcePathField;
    private TextField destPathField;
    private Spinner<Integer> recordCountSpinner;
    private HBox backupFieldsBox;
    private HBox batchFieldsBox;
    private TableView<StagedTask> stagedTableView;
    private Button runSimulationButton;
    private Button addStagedTaskButton;
    private Button loadDefaultsButton;
    private Button clearStagedButton;
    private Button removeSelectedButton;

    // Mid-run indicators
    private Label timerLabel;
    private Label completedCounterLabel;
    private Label statusLabel;
    private ProgressBar runProgressBar;

    // Post-run controls
    private Label postRunHeaderLabel;
    private Label postRunStatusMsg;
    private Label metricCarbonRateVal;
    private Label metricCarbonRateSub;
    private Label metricPreemptRateVal;
    private Label metricPreemptRateSub;
    private Label metricAvgWaitVal;
    private Label metricAvgWaitSub;
    private Label metricTasksCountVal;
    private Label metricTasksCountSub;
    private LineChart<Number, Number> carbonLineChart;
    private BarChart<String, Number> eventBarChart;
    private TableView<EventRecord> eventsTableView;
    private ComboBox<String> eventTypeFilterCombo;
    private ComboBox<String> priorityFilterCombo;
    private TextField taskSearchField;
    private FilteredList<EventRecord> filteredEvents;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Green Cloud Carbon-Aware Task Scheduler Dashboard");

        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        preRunTab = new Tab("1. Pre-Run Launcher", createPreRunLauncherPane());
        postRunTab = new Tab("2. Post-Run Analytics", createPostRunAnalyticsPane());

        tabPane.getTabs().addAll(preRunTab, postRunTab);

        BorderPane root = new BorderPane();
        root.setCenter(tabPane);

        Scene scene = new Scene(root, 1150, 780);
        String cssPath = getClass().getResource("/com/greencloud/ui/dashboard.css") != null
                ? getClass().getResource("/com/greencloud/ui/dashboard.css").toExternalForm()
                : null;
        if (cssPath != null) {
            scene.getStylesheets().add(cssPath);
        }

        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> {
            simExecutor.shutdownNow();
            Platform.exit();
        });

        // Initialize default staged tasks
        loadRealisticDefaults();

        // Check if results/ directory already exists and pre-populate analytics tab if present
        Path defaultResults = Paths.get("results");
        if (new File("results/events.csv").exists() && new File("results/summary.csv").exists()) {
            loadResultsFromDirectory(defaultResults, false);
        } else {
            resetPostRunMetrics("No simulation results loaded yet");
        }

        primaryStage.show();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. PRE-RUN LAUNCHER VIEW
    // ─────────────────────────────────────────────────────────────────────────────

    private ScrollPane createPreRunLauncherPane() {
        VBox container = new VBox(16);
        container.setPadding(new Insets(20));

        // Section A: Simulation Settings
        VBox simConfigCard = new VBox(10);
        simConfigCard.getStyleClass().add("card");
        Label simConfigHeader = new Label("Simulation Configuration");
        simConfigHeader.getStyleClass().add("section-header");

        HBox simConfigRow = new HBox(20);
        simConfigRow.setAlignment(Pos.CENTER_LEFT);

        VBox durBox = new VBox(4);
        Label durLabel = new Label("Duration (seconds):");
        durLabel.getStyleClass().add("label-field");
        durationSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(5, 600, 20, 5));
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(120);
        durBox.getChildren().addAll(durLabel, durationSpinner);

        VBox tickBox = new VBox(4);
        Label tickLabel = new Label("Tick Interval (ms):");
        tickLabel.getStyleClass().add("label-field");
        tickIntervalSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(200, 5000, 1000, 200));
        tickIntervalSpinner.setEditable(true);
        tickIntervalSpinner.setPrefWidth(120);
        tickBox.getChildren().addAll(tickLabel, tickIntervalSpinner);

        Label providerNote = new Label("Environmental Provider: UK Grid Dynamic Carbon Intensity Simulation (Oscillation 70-310 gCO2/kWh)");
        providerNote.getStyleClass().add("sub-header");

        simConfigRow.getChildren().addAll(durBox, tickBox, providerNote);
        simConfigCard.getChildren().addAll(simConfigHeader, simConfigRow);

        // Section B: Task Staging Form
        VBox stagingCard = new VBox(12);
        stagingCard.getStyleClass().add("card");
        Label stagingHeader = new Label("Workload Staging (Pre-Run Queue Setup)");
        stagingHeader.getStyleClass().add("section-header");

        GridPane formGrid = new GridPane();
        formGrid.setHgap(14);
        formGrid.setVgap(10);

        // Row 1: Task Type, Task ID, Priority
        Label typeLabel = new Label("Task Type:");
        typeLabel.getStyleClass().add("label-field");
        taskTypeCombo = new ComboBox<>(FXCollections.observableArrayList("BackupTask", "BatchProcessingTask"));
        taskTypeCombo.setValue("BackupTask");

        Label idLabel = new Label("Task ID:");
        idLabel.getStyleClass().add("label-field");
        taskIdField = new TextField();
        taskIdField.setPromptText("Auto-generated if blank");

        Label prioLabel = new Label("Priority:");
        prioLabel.getStyleClass().add("label-field");
        priorityCombo = new ComboBox<>(FXCollections.observableArrayList(TaskPriority.CRITICAL, TaskPriority.HIGH, TaskPriority.BACKGROUND));
        priorityCombo.setValue(TaskPriority.BACKGROUND);

        formGrid.add(typeLabel, 0, 0);
        formGrid.add(taskTypeCombo, 1, 0);
        formGrid.add(idLabel, 2, 0);
        formGrid.add(taskIdField, 3, 0);
        formGrid.add(prioLabel, 4, 0);
        formGrid.add(priorityCombo, 5, 0);

        // Row 2: Duration, Carbon Threshold, Arrival Delay
        Label durMsLabel = new Label("Duration (ms):");
        durMsLabel.getStyleClass().add("label-field");
        taskDurationSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(100, 60000, 1200, 100));
        taskDurationSpinner.setEditable(true);

        Label threshLabel = new Label("Threshold (gCO2):");
        threshLabel.getStyleClass().add("label-field");
        carbonThresholdSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(10.0, 500.0, 200.0, 10.0));
        carbonThresholdSpinner.setEditable(true);

        Label delayLabel = new Label("Arrival Delay (ms):");
        delayLabel.getStyleClass().add("label-field");
        arrivalDelaySpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 120000, 0, 250));
        arrivalDelaySpinner.setEditable(true);

        formGrid.add(durMsLabel, 0, 1);
        formGrid.add(taskDurationSpinner, 1, 1);
        formGrid.add(threshLabel, 2, 1);
        formGrid.add(carbonThresholdSpinner, 3, 1);
        formGrid.add(delayLabel, 4, 1);
        formGrid.add(arrivalDelaySpinner, 5, 1);

        // Row 3: Conditional Subtype Fields
        backupFieldsBox = new HBox(10);
        backupFieldsBox.setAlignment(Pos.CENTER_LEFT);
        Label srcLabel = new Label("Source Path:");
        srcLabel.getStyleClass().add("label-field");
        sourcePathField = new TextField("/data/database");
        sourcePathField.setPrefWidth(140);
        Label dstLabel = new Label("Dest Path:");
        dstLabel.getStyleClass().add("label-field");
        destPathField = new TextField("/backup/cloud");
        destPathField.setPrefWidth(140);
        backupFieldsBox.getChildren().addAll(srcLabel, sourcePathField, dstLabel, destPathField);

        batchFieldsBox = new HBox(10);
        batchFieldsBox.setAlignment(Pos.CENTER_LEFT);
        Label batchSizeLabel = new Label("Batch Record Count:");
        batchSizeLabel.getStyleClass().add("label-field");
        recordCountSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 50000, 200, 50));
        recordCountSpinner.setEditable(true);
        batchFieldsBox.getChildren().addAll(batchSizeLabel, recordCountSpinner);
        batchFieldsBox.setVisible(false);
        batchFieldsBox.setManaged(false);

        // Toggle subtype fields visibility when type changes
        taskTypeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            boolean isBatch = "BatchProcessingTask".equalsIgnoreCase(newVal);
            backupFieldsBox.setVisible(!isBatch);
            backupFieldsBox.setManaged(!isBatch);
            batchFieldsBox.setVisible(isBatch);
            batchFieldsBox.setManaged(isBatch);
        });

        HBox formActions = new HBox(12);
        formActions.setAlignment(Pos.CENTER_LEFT);
        addStagedTaskButton = new Button("Add Task to Staged Queue");
        addStagedTaskButton.getStyleClass().add("button-accent");
        addStagedTaskButton.setOnAction(e -> handleAddStagedTask());

        loadDefaultsButton = new Button("Load Realistic Workload (6 Tasks)");
        loadDefaultsButton.setOnAction(e -> loadRealisticDefaults());

        clearStagedButton = new Button("Clear All");
        clearStagedButton.setOnAction(e -> stagedTasks.clear());

        removeSelectedButton = new Button("Remove Selected");
        removeSelectedButton.setOnAction(e -> {
            StagedTask sel = stagedTableView.getSelectionModel().getSelectedItem();
            if (sel != null) stagedTasks.remove(sel);
        });

        formActions.getChildren().addAll(addStagedTaskButton, loadDefaultsButton, removeSelectedButton, clearStagedButton);

        stagingCard.getChildren().addAll(stagingHeader, formGrid, backupFieldsBox, batchFieldsBox, formActions);

        // Section C: Staged Tasks TableView
        VBox tableCard = new VBox(8);
        tableCard.getStyleClass().add("card");
        Label tableHeader = new Label("Staged Workload Queue (Ordered by Arrival Delay):");
        tableHeader.getStyleClass().add("section-header");

        stagedTableView = createStagedTableView();
        stagedTableView.setPrefHeight(200);

        tableCard.getChildren().addAll(tableHeader, stagedTableView);

        // Section D: Run Simulation & Live Progress Indicators
        VBox runCard = new VBox(12);
        runCard.getStyleClass().add("card");

        HBox runControlsRow = new HBox(16);
        runControlsRow.setAlignment(Pos.CENTER_LEFT);

        runSimulationButton = new Button("▶ Run Simulation");
        runSimulationButton.getStyleClass().addAll("button", "button-primary");
        runSimulationButton.setStyle("-fx-font-size: 14px; -fx-padding: 10px 24px;");
        runSimulationButton.setOnAction(e -> handleRunSimulation());

        statusLabel = new Label("Status: Ready to launch");
        statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #94a3b8;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        timerLabel = new Label("Elapsed: 00:00 | Remaining: 00:00");
        timerLabel.getStyleClass().add("progress-timer-label");

        completedCounterLabel = new Label("Completed Tasks: 0 / 0");
        completedCounterLabel.getStyleClass().add("progress-counter-label");

        runControlsRow.getChildren().addAll(runSimulationButton, statusLabel, spacer, timerLabel, new Separator(), completedCounterLabel);

        runProgressBar = new ProgressBar(0.0);
        runProgressBar.setMaxWidth(Double.MAX_VALUE);
        runProgressBar.setPrefHeight(14);

        runCard.getChildren().addAll(runControlsRow, runProgressBar);

        container.getChildren().addAll(simConfigCard, stagingCard, tableCard, runCard);

        ScrollPane scrollPane = new ScrollPane(container);
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }

    private TableView<StagedTask> createStagedTableView() {
        TableView<StagedTask> table = new TableView<>(stagedTasks);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<StagedTask, String> idCol = new TableColumn<>("Task ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("taskId"));
        idCol.setPrefWidth(160);

        TableColumn<StagedTask, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("taskType"));
        typeCol.setPrefWidth(140);

        TableColumn<StagedTask, TaskPriority> prioCol = new TableColumn<>("Priority");
        prioCol.setCellValueFactory(new PropertyValueFactory<>("priority"));
        prioCol.setPrefWidth(120);
        prioCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(TaskPriority item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.name());
                    switch (item) {
                        case CRITICAL -> setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
                        case HIGH -> setStyle("-fx-text-fill: #f59e0b; -fx-font-weight: bold;");
                        case BACKGROUND -> setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold;");
                    }
                }
            }
        });

        TableColumn<StagedTask, Long> durCol = new TableColumn<>("Duration");
        durCol.setCellValueFactory(new PropertyValueFactory<>("durationMillis"));
        durCol.setPrefWidth(100);
        durCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item + " ms");
            }
        });

        TableColumn<StagedTask, Double> threshCol = new TableColumn<>("Carbon Threshold");
        threshCol.setCellValueFactory(new PropertyValueFactory<>("carbonThresholdGramsPerKwh"));
        threshCol.setPrefWidth(140);
        threshCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("%.1f gCO2/kWh", item));
            }
        });

        TableColumn<StagedTask, Long> delayCol = new TableColumn<>("Arrival Delay");
        delayCol.setCellValueFactory(new PropertyValueFactory<>("arrivalDelayMillis"));
        delayCol.setPrefWidth(120);
        delayCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : "+" + item + " ms");
            }
        });

        TableColumn<StagedTask, String> detailsCol = new TableColumn<>("Subtype Details");
        detailsCol.setCellValueFactory(cellData -> {
            StagedTask t = cellData.getValue();
            if ("BatchProcessingTask".equalsIgnoreCase(t.getTaskType())) {
                return new SimpleStringProperty(t.getRecordCount() + " records");
            } else {
                return new SimpleStringProperty(t.getSourcePath() + " -> " + t.getDestinationPath());
            }
        });
        detailsCol.setPrefWidth(220);

        table.getColumns().addAll(List.of(idCol, typeCol, prioCol, durCol, threshCol, delayCol, detailsCol));
        return table;
    }

    private void handleAddStagedTask() {
        String type = taskTypeCombo.getValue();
        String id = taskIdField.getText().trim();
        if (id.isEmpty()) {
            id = (type.startsWith("Batch") ? "batch-" : "backup-") + (System.currentTimeMillis() % 10000);
        }
        TaskPriority prio = priorityCombo.getValue();
        long dur = taskDurationSpinner.getValue();
        double thresh = carbonThresholdSpinner.getValue();
        long delay = arrivalDelaySpinner.getValue();

        StagedTask staged = new StagedTask(
                type,
                id,
                prio,
                dur,
                thresh,
                delay,
                sourcePathField.getText().trim(),
                destPathField.getText().trim(),
                recordCountSpinner.getValue()
        );

        stagedTasks.add(staged);
        taskIdField.clear();
    }

    private void loadRealisticDefaults() {
        stagedTasks.clear();
        int durSec = durationSpinner.getValue();
        long durScale = (durSec < 20) ? Math.max(300, durSec * 50L) : 1200;

        // Keep arrival stagger bounded relative to total duration so late tasks still finish
        long maxStaggerWindowMs = Math.max(2000L, (long) (durSec * 1000L * 0.45));
        long highDelay = Math.min(Math.max(500L, (long) (durSec * 150.0)), maxStaggerWindowMs / 4);
        long auditDelay = Math.min(Math.max(900L, (long) (durSec * 300.0)), maxStaggerWindowMs / 2);
        long dbDelay = Math.min(Math.max(1300L, (long) (durSec * 450.0)), (maxStaggerWindowMs * 3) / 4);
        long telemetryDelay = Math.min(Math.max(1700L, (long) (durSec * 600.0)), maxStaggerWindowMs);

        stagedTasks.add(new StagedTask("BackupTask", "bg-heavy-archive", TaskPriority.BACKGROUND, Math.max(900, (long) (durScale * 2.5)), 350.0, 100, "/var/data", "/backup/cold", 0));
        stagedTasks.add(new StagedTask("BackupTask", "crit-security-hotfix", TaskPriority.CRITICAL, Math.max(250, (long) (durScale * 0.7)), 50.0, 300, "/sec/patches", "/sec/applied", 0));
        stagedTasks.add(new StagedTask("BackupTask", "high-cache-indexing", TaskPriority.HIGH, durScale, 220.0, highDelay, "/cache/indices", "/cache/backup", 0));
        stagedTasks.add(new StagedTask("BatchProcessingTask", "bg-strict-audit", TaskPriority.BACKGROUND, durScale, 110.0, auditDelay, null, null, 100));
        stagedTasks.add(new StagedTask("BackupTask", "bg-db-maintenance", TaskPriority.BACKGROUND, (long)(durScale * 0.9), 180.0, dbDelay, "/data/db", "/backup/db", 0));
        stagedTasks.add(new StagedTask("BatchProcessingTask", "bg-telemetry-sync", TaskPriority.BACKGROUND, durScale, 280.0, telemetryDelay, null, null, 250));
    }

    private void handleRunSimulation() {
        if (stagedTasks.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Please stage at least one task before starting the simulation.");
            alert.showAndWait();
            return;
        }

        // Lock pre-run form inputs to prevent concurrent race conditions
        setPreRunFormDisabled(true);

        // Reset post-run metrics on new simulation run
        resetPostRunMetrics("Simulation in progress...");
        postRunHeaderLabel.setText("Simulation in progress... | Results pending");

        int durationSec = durationSpinner.getValue();
        int tickMs = tickIntervalSpinner.getValue();

        List<ScheduledTaskSubmission> submissions = new ArrayList<>();
        for (StagedTask item : stagedTasks) {
            submissions.add(item.toSubmission());
        }

        statusLabel.setText("Status: Simulation running...");
        statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #38bdf8;");
        runProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        // Run simulation on background executor thread
        simExecutor.submit(() -> {
            try {
                SimulationEngine engine = new SimulationEngine(
                        durationSec,
                        tickMs,
                        new SimulatedEnvironmentalDataProvider("SIMULATED-UK", 70.0, 310.0, Math.max(8.0, durationSec / 2.0), true),
                        null,
                        submissions
                );

                long totalTimeMs = durationSec * 1000L;
                engine.setProgressListener((currentTick, elapsedMs, remainingMs, completedCount, totalCount) -> {
                    Platform.runLater(() -> {
                        double progress = (totalTimeMs > 0) ? Math.min(1.0, (double) elapsedMs / totalTimeMs) : 0.0;
                        runProgressBar.setProgress(progress);
                        timerLabel.setText(String.format("Elapsed: %02d:%02d | Remaining: %02d:%02d",
                                (elapsedMs / 1000) / 60, (elapsedMs / 1000) % 60,
                                (remainingMs / 1000) / 60, (remainingMs / 1000) % 60));
                        completedCounterLabel.setText(String.format("Completed Tasks: %d / %d", completedCount, totalCount));
                    });
                });

                engine.run();

                // Upon clean completion, auto-load generated results and transition view
                Platform.runLater(() -> {
                    runProgressBar.setProgress(1.0);
                    statusLabel.setText("Status: Simulation completed! Results saved to 'results/'");
                    statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #10b981;");
                    setPreRunFormDisabled(false);

                    loadResultsFromDirectory(Paths.get("results"), true);
                    tabPane.getSelectionModel().select(postRunTab);
                });
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Simulation run failed", ex);
                Platform.runLater(() -> {
                    runProgressBar.setProgress(0.0);
                    statusLabel.setText("Status: Error during simulation: " + ex.getMessage());
                    statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #ef4444;");
                    setPreRunFormDisabled(false);
                });
            }
        });
    }

    private void setPreRunFormDisabled(boolean disabled) {
        runSimulationButton.setDisable(disabled);
        addStagedTaskButton.setDisable(disabled);
        loadDefaultsButton.setDisable(disabled);
        clearStagedButton.setDisable(disabled);
        removeSelectedButton.setDisable(disabled);
        durationSpinner.setDisable(disabled);
        tickIntervalSpinner.setDisable(disabled);
        taskTypeCombo.setDisable(disabled);
        taskIdField.setDisable(disabled);
        priorityCombo.setDisable(disabled);
        taskDurationSpinner.setDisable(disabled);
        carbonThresholdSpinner.setDisable(disabled);
        arrivalDelaySpinner.setDisable(disabled);
        sourcePathField.setDisable(disabled);
        destPathField.setDisable(disabled);
        recordCountSpinner.setDisable(disabled);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. POST-RUN ANALYTICS VIEW
    // ─────────────────────────────────────────────────────────────────────────────

    private ScrollPane createPostRunAnalyticsPane() {
        VBox container = new VBox(16);
        container.setPadding(new Insets(20));

        // Header Section
        HBox headerRow = new HBox(16);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getStyleClass().add("card");

        VBox titleBox = new VBox(4);
        Label headerTitle = new Label("Simulation Post-Run Analytics & Metrics Viewer");
        headerTitle.getStyleClass().add("section-header");
        postRunHeaderLabel = new Label("Loaded: None | Source: UK Grid Simulated");
        postRunHeaderLabel.getStyleClass().add("sub-header");
        titleBox.getChildren().addAll(headerTitle, postRunHeaderLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button loadResultsDirButton = new Button("📂 Load Results Folder...");
        loadResultsDirButton.getStyleClass().add("button-accent");
        loadResultsDirButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Results Directory (containing events.csv and summary.csv)");
            chooser.setInitialDirectory(new File("."));
            File selected = chooser.showDialog(tabPane.getScene().getWindow());
            if (selected != null) {
                loadResultsFromDirectory(selected.toPath(), true);
            }
        });

        Button reloadDefaultButton = new Button("🔄 Reload 'results/'");
        reloadDefaultButton.setOnAction(e -> loadResultsFromDirectory(Paths.get("results"), true));

        Button clearMetricsButton = new Button("Clear View");
        clearMetricsButton.setOnAction(e -> {
            resetPostRunMetrics("Cleared by user");
            postRunHeaderLabel.setText("Loaded: None | Cleared");
            postRunStatusMsg.setVisible(false);
            postRunStatusMsg.setManaged(false);
        });

        headerRow.getChildren().addAll(titleBox, spacer, loadResultsDirButton, reloadDefaultButton, clearMetricsButton);

        // Status / Error message banner
        postRunStatusMsg = new Label();
        postRunStatusMsg.setStyle("-fx-font-weight: bold; -fx-padding: 4px;");
        postRunStatusMsg.setVisible(false);
        postRunStatusMsg.setManaged(false);

        // Section A: Summary Metric Cards
        HBox cardsRow = new HBox(14);
        cardsRow.setAlignment(Pos.CENTER);

        VBox card1 = createMetricCard("Carbon-Aware Rate", "100.0%", "Non-critical tasks compliant", 1);
        VBox card2 = createMetricCard("Preemption Success", "100.0%", "Critical preempted vs waited", 2);
        VBox card3 = createMetricCard("Avg Latency (BG / CRIT)", "0 ms / 0 ms", "Submission to execution", 3);
        VBox card4 = createMetricCard("Task Outcomes", "0 Completed", "0 Failed | 0 Preempted", 4);

        HBox.setHgrow(card1, Priority.ALWAYS);
        HBox.setHgrow(card2, Priority.ALWAYS);
        HBox.setHgrow(card3, Priority.ALWAYS);
        HBox.setHgrow(card4, Priority.ALWAYS);
        cardsRow.getChildren().addAll(card1, card2, card3, card4);

        // Section B: Charts (Chart 1: Carbon Intensity LineChart, Chart 2: Event Breakdown BarChart)
        HBox chartsRow = new HBox(16);
        chartsRow.setPrefHeight(320);

        carbonLineChart = createCarbonLineChart();
        HBox.setHgrow(carbonLineChart, Priority.ALWAYS);

        eventBarChart = createEventBarChart();
        HBox.setHgrow(eventBarChart, Priority.ALWAYS);

        chartsRow.getChildren().addAll(carbonLineChart, eventBarChart);

        // Section C: Events TableView with Filters
        VBox eventsCard = new VBox(10);
        eventsCard.getStyleClass().add("card");

        HBox filterRow = new HBox(14);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        Label tableTitle = new Label("Lifecycle Events Log:");
        tableTitle.getStyleClass().add("section-header");

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        Label eventFilterLabel = new Label("Event:");
        eventFilterLabel.getStyleClass().add("label-field");
        eventTypeFilterCombo = new ComboBox<>(FXCollections.observableArrayList(
                "ALL", "SUBMITTED", "EXECUTED", "DEFERRED", "PREEMPTED", "RESUMED", "FAILED"
        ));
        eventTypeFilterCombo.setValue("ALL");
        eventTypeFilterCombo.valueProperty().addListener((obs, o, n) -> applyEventFilters());

        Label prioFilterLabel = new Label("Priority:");
        prioFilterLabel.getStyleClass().add("label-field");
        priorityFilterCombo = new ComboBox<>(FXCollections.observableArrayList(
                "ALL", "CRITICAL", "HIGH", "BACKGROUND"
        ));
        priorityFilterCombo.setValue("ALL");
        priorityFilterCombo.valueProperty().addListener((obs, o, n) -> applyEventFilters());

        Label searchLabel = new Label("Task ID:");
        searchLabel.getStyleClass().add("label-field");
        taskSearchField = new TextField();
        taskSearchField.setPromptText("Filter by taskId...");
        taskSearchField.textProperty().addListener((obs, o, n) -> applyEventFilters());

        filterRow.getChildren().addAll(tableTitle, spacer2, eventFilterLabel, eventTypeFilterCombo, prioFilterLabel, priorityFilterCombo, searchLabel, taskSearchField);

        eventsTableView = createEventsTableView();
        eventsTableView.setPrefHeight(260);

        eventsCard.getChildren().addAll(filterRow, eventsTableView);

        container.getChildren().addAll(headerRow, postRunStatusMsg, cardsRow, chartsRow, eventsCard);

        ScrollPane scrollPane = new ScrollPane(container);
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }

    private VBox createMetricCard(String title, String initialVal, String subtitle, int cardIndex) {
        VBox card = new VBox(6);
        card.getStyleClass().add("metric-card");

        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("metric-title");

        Label valLbl = new Label(initialVal);
        valLbl.getStyleClass().add("metric-value");

        Label subLbl = new Label(subtitle);
        subLbl.getStyleClass().add("metric-subtitle");

        card.getChildren().addAll(titleLbl, valLbl, subLbl);

        switch (cardIndex) {
            case 1 -> {
                metricCarbonRateVal = valLbl;
                metricCarbonRateSub = subLbl;
            }
            case 2 -> {
                metricPreemptRateVal = valLbl;
                metricPreemptRateSub = subLbl;
            }
            case 3 -> {
                metricAvgWaitVal = valLbl;
                metricAvgWaitSub = subLbl;
            }
            case 4 -> {
                metricTasksCountVal = valLbl;
                metricTasksCountSub = subLbl;
            }
        }
        return card;
    }

    private LineChart<Number, Number> createCarbonLineChart() {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Elapsed Time (s)");
        xAxis.setAutoRanging(true);

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Carbon Intensity (gCO2/kWh)");
        yAxis.setAutoRanging(true);

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Grid Carbon Intensity Over Time & Task Execution Markers");
        chart.setAnimated(false);
        chart.setCreateSymbols(true);
        return chart;
    }

    private BarChart<String, Number> createEventBarChart() {
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Decision / Outcome Category");

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Count");

        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setTitle("Scheduling Decisions & Preemption Profile");
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        return chart;
    }

    private TableView<EventRecord> createEventsTableView() {
        filteredEvents = new FilteredList<>(eventRecords, p -> true);
        TableView<EventRecord> table = new TableView<>(filteredEvents);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<EventRecord, Long> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("timestamp"));
        timeCol.setPrefWidth(120);
        timeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(TIME_FMT.format(Instant.ofEpochMilli(item)));
                }
            }
        });

        TableColumn<EventRecord, String> eventCol = new TableColumn<>("Event Type");
        eventCol.setCellValueFactory(new PropertyValueFactory<>("eventType"));
        eventCol.setPrefWidth(120);
        eventCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    switch (item) {
                        case "EXECUTED" -> setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold;");
                        case "PREEMPTED" -> setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
                        case "DEFERRED" -> setStyle("-fx-text-fill: #f59e0b; -fx-font-weight: bold;");
                        case "RESUMED" -> setStyle("-fx-text-fill: #38bdf8; -fx-font-weight: bold;");
                        case "FAILED" -> setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                        default -> setStyle("-fx-text-fill: #cbd5e1;");
                    }
                }
            }
        });

        TableColumn<EventRecord, String> idCol = new TableColumn<>("Task ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("taskId"));
        idCol.setPrefWidth(160);

        TableColumn<EventRecord, String> prioCol = new TableColumn<>("Priority");
        prioCol.setCellValueFactory(new PropertyValueFactory<>("priority"));
        prioCol.setPrefWidth(110);
        prioCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    switch (item) {
                        case "CRITICAL" -> setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
                        case "HIGH" -> setStyle("-fx-text-fill: #f59e0b; -fx-font-weight: bold;");
                        case "BACKGROUND" -> setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold;");
                        default -> setStyle("-fx-text-fill: #cbd5e1;");
                    }
                }
            }
        });

        TableColumn<EventRecord, Double> intensityCol = new TableColumn<>("Grid Carbon");
        intensityCol.setCellValueFactory(new PropertyValueFactory<>("carbonIntensityAtEvent"));
        intensityCol.setPrefWidth(120);
        intensityCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("%.1f gCO2", item));
            }
        });

        TableColumn<EventRecord, Double> threshCol = new TableColumn<>("Task Threshold");
        threshCol.setCellValueFactory(new PropertyValueFactory<>("carbonThreshold"));
        threshCol.setPrefWidth(130);
        threshCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("%.1f gCO2", item));
            }
        });

        table.getColumns().addAll(List.of(timeCol, eventCol, idCol, prioCol, intensityCol, threshCol));
        return table;
    }

    private void applyEventFilters() {
        String eventFilter = eventTypeFilterCombo.getValue();
        String prioFilter = priorityFilterCombo.getValue();
        String searchText = taskSearchField.getText().trim().toLowerCase();

        filteredEvents.setPredicate(rec -> {
            if (eventFilter != null && !"ALL".equalsIgnoreCase(eventFilter) && !rec.eventType().equalsIgnoreCase(eventFilter)) {
                return false;
            }
            if (prioFilter != null && !"ALL".equalsIgnoreCase(prioFilter) && !rec.priority().equalsIgnoreCase(prioFilter)) {
                return false;
            }
            if (!searchText.isEmpty() && !rec.taskId().toLowerCase().contains(searchText)) {
                return false;
            }
            return true;
        });
    }

    /**
     * Loads simulation results from disk using ResultsLoader and updates all UI visual components.
     * Missing or malformed files surface clear error banners rather than crashing.
     */
    public void loadResultsFromDirectory(Path directory, boolean showErrors) {
        try {
            SimulationResults results = ResultsLoader.loadFromDirectory(directory);

            postRunStatusMsg.setVisible(false);
            postRunStatusMsg.setManaged(false);

            postRunHeaderLabel.setText(String.format("Loaded from: %s | Total Recorded Events: %d",
                    directory.toAbsolutePath(), results.events().size()));

            // Update Summary Cards
            updateSummaryCards(results);

            // Update Events Table
            eventRecords.setAll(results.events());

            // Update Charts
            updateCharts(results);

        } catch (ResultsLoadingException ex) {
            LOGGER.log(Level.WARNING, "Results loading issue: " + ex.getMessage());
            postRunStatusMsg.setText("⚠ Results Loading Error: " + ex.getMessage());
            postRunStatusMsg.setStyle("-fx-text-fill: #ef4444; -fx-font-weight: bold;");
            postRunStatusMsg.setVisible(true);
            postRunStatusMsg.setManaged(true);

            resetPostRunMetrics("Load error: " + ex.getMessage());
            postRunHeaderLabel.setText("Loaded: Failed | " + ex.getMessage());

            if (showErrors) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Unable to load results:\n" + ex.getMessage());
                alert.setHeaderText("Results Loading Failed");
                alert.showAndWait();
            }
        }
    }

    private void updateSummaryCards(SimulationResults results) {
        // Carbon-aware execution rate: MUST be styled GREEN at 100%, RED below 100%
        String carbonRateStr = results.getMetric("carbon-aware execution rate");
        metricCarbonRateVal.setText(carbonRateStr);
        double carbonRate = parsePercentage(carbonRateStr);

        metricCarbonRateVal.getStyleClass().removeAll("metric-value-green", "metric-value-red", "metric-value");
        if (carbonRate >= 99.99) {
            metricCarbonRateVal.getStyleClass().add("metric-value-green");
            metricCarbonRateVal.setStyle("-fx-text-fill: #10b981; -fx-font-size: 26px; -fx-font-weight: bold;");
            metricCarbonRateSub.setText("100% compliant: Non-critical tasks within carbon limits");
        } else if (carbonRate <= 0.0) {
            metricCarbonRateVal.getStyleClass().add("metric-value-red");
            metricCarbonRateVal.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 26px; -fx-font-weight: bold;");
            metricCarbonRateSub.setText("No completed non-critical tasks or 0% compliant");
        } else {
            metricCarbonRateVal.getStyleClass().add("metric-value-red");
            metricCarbonRateVal.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 26px; -fx-font-weight: bold;");
            metricCarbonRateSub.setText(String.format(
                    "%.1f%% compliant: Some completed non-critical tasks missed carbon limits",
                    carbonRate));
        }

        // Preemption success rate
        String preemptionRateStr = results.getMetric("preemption success rate");
        metricPreemptRateVal.setText(preemptionRateStr);
        double preemptRate = parsePercentage(preemptionRateStr);
        if (preemptRate >= 99.99) {
            metricPreemptRateSub.setText("100% success: Critical tasks preempted immediately");
        } else {
            metricPreemptRateSub.setText(String.format("Critical preemption rate: %s (some queued)", preemptionRateStr));
        }

        // Latency
        String bgWait = results.getMetric("avg background wait time");
        String critLatency = results.getMetric("avg critical time-to-execution");
        metricAvgWaitVal.setText(String.format("%s / %s", bgWait, critLatency));
        metricAvgWaitSub.setText("BG queue wait vs CRITICAL dispatch latency");

        // Task counts
        String completed = results.getMetric("total completed");
        String failed = results.getMetric("total failed");
        String preempted = results.getMetric("total preempted");
        metricTasksCountVal.setText(String.format("%s Completed", completed));
        metricTasksCountSub.setText(String.format("%s Failed | %s Preempted", failed, preempted));
    }

    /**
     * Resets post-run summary metric cards, charts, and table to an idle state with a status reason.
     */
    public void resetPostRunMetrics(String reason) {
        String msg = (reason != null && !reason.isBlank()) ? reason : "Awaiting simulation run";
        if (metricCarbonRateVal != null) {
            metricCarbonRateVal.setText("-");
            metricCarbonRateVal.getStyleClass().removeAll("metric-value-green", "metric-value-red");
            metricCarbonRateVal.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 26px; -fx-font-weight: bold;");
        }
        if (metricCarbonRateSub != null) {
            metricCarbonRateSub.setText(msg);
        }
        if (metricPreemptRateVal != null) {
            metricPreemptRateVal.setText("-");
        }
        if (metricPreemptRateSub != null) {
            metricPreemptRateSub.setText(msg);
        }
        if (metricAvgWaitVal != null) {
            metricAvgWaitVal.setText("- / -");
        }
        if (metricAvgWaitSub != null) {
            metricAvgWaitSub.setText(msg);
        }
        if (metricTasksCountVal != null) {
            metricTasksCountVal.setText("-");
        }
        if (metricTasksCountSub != null) {
            metricTasksCountSub.setText(msg);
        }
        if (carbonLineChart != null) {
            carbonLineChart.getData().clear();
        }
        if (eventBarChart != null) {
            eventBarChart.getData().clear();
        }
        eventRecords.clear();
    }

    public Label getMetricCarbonRateSub() {
        return metricCarbonRateSub;
    }

    public Label getMetricPreemptRateSub() {
        return metricPreemptRateSub;
    }

    public Label getMetricAvgWaitSub() {
        return metricAvgWaitSub;
    }

    public Label getMetricTasksCountSub() {
        return metricTasksCountSub;
    }

    private double parsePercentage(String text) {
        if (text == null) return 0.0;
        try {
            String cleaned = text.replace("%", "").trim();
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void updateCharts(SimulationResults results) {
        List<EventRecord> events = results.events();

        // 1. Line Chart: Grid Carbon Intensity over time with event markers
        carbonLineChart.getData().clear();

        XYChart.Series<Number, Number> intensitySeries = new XYChart.Series<>();
        intensitySeries.setName("Carbon Intensity (gCO2/kWh)");

        XYChart.Series<Number, Number> executedSeries = new XYChart.Series<>();
        executedSeries.setName("Task Executed");

        XYChart.Series<Number, Number> preemptedSeries = new XYChart.Series<>();
        preemptedSeries.setName("Task Preempted");

        XYChart.Series<Number, Number> deferredSeries = new XYChart.Series<>();
        deferredSeries.setName("Task Deferred");

        if (!events.isEmpty()) {
            long baseTime = events.get(0).timestamp();

            for (EventRecord ev : events) {
                double elapsedSec = Math.max(0.0, (ev.timestamp() - baseTime) / 1000.0);
                intensitySeries.getData().add(new XYChart.Data<>(elapsedSec, ev.carbonIntensityAtEvent()));

                if ("EXECUTED".equalsIgnoreCase(ev.eventType())) {
                    executedSeries.getData().add(new XYChart.Data<>(elapsedSec, ev.carbonIntensityAtEvent()));
                } else if ("PREEMPTED".equalsIgnoreCase(ev.eventType())) {
                    preemptedSeries.getData().add(new XYChart.Data<>(elapsedSec, ev.carbonIntensityAtEvent()));
                } else if ("DEFERRED".equalsIgnoreCase(ev.eventType())) {
                    deferredSeries.getData().add(new XYChart.Data<>(elapsedSec, ev.carbonIntensityAtEvent()));
                }
            }
        }

        carbonLineChart.getData().addAll(List.of(intensitySeries, executedSeries, preemptedSeries, deferredSeries));

        // 2. Bar Chart: Categorical Breakdown
        eventBarChart.getData().clear();

        int compliantExec = 0;
        int criticalBypass = 0;
        int deferredCount = 0;
        int preemptionCount = 0;

        for (EventRecord ev : events) {
            if ("EXECUTED".equalsIgnoreCase(ev.eventType())) {
                if ("CRITICAL".equalsIgnoreCase(ev.priority())) {
                    criticalBypass++;
                } else {
                    compliantExec++;
                }
            } else if ("DEFERRED".equalsIgnoreCase(ev.eventType())) {
                deferredCount++;
            } else if ("PREEMPTED".equalsIgnoreCase(ev.eventType())) {
                preemptionCount++;
            }
        }

        XYChart.Series<String, Number> barSeries = new XYChart.Series<>();
        barSeries.getData().add(new XYChart.Data<>("Carbon-Compliant", compliantExec));
        barSeries.getData().add(new XYChart.Data<>("Critical Bypass", criticalBypass));
        barSeries.getData().add(new XYChart.Data<>("Deferred", deferredCount));
        barSeries.getData().add(new XYChart.Data<>("Preemptions", preemptionCount));

        eventBarChart.getData().add(barSeries);
    }

    public static void main(String[] args) {
        launch(args);
    }

    public static void launchApp(String[] args) {
        launch(GreenCloudDashboardApp.class, args);
    }
}
