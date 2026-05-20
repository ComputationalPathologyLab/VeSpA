package com.rashid.qupath.vesselseg;

import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.TMAGrid;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;

public class VesselSegmentationExtension implements QuPathExtension {

    private enum InputRegionMode {
        SELECTED_ANNOTATIONS,
        TMA_CORES,
        WHOLE_IMAGE
    }

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(VesselSegmentationExtension.class);

    private static final String PREF_PYTHON_EXEC = "pythonExec";
    private static final String PYTHON_SCRIPT_RESOURCE = "/scripts/vessels_segmentation.py";
    private static final String LOGO_RESOURCE = "/images/vespa_logo.png";

    private static final int DEFAULT_LUMEN_AREA_MIN = 200;
    private static final int DEFAULT_LUMEN_AREA_MAX = 80000;
    private static final double DEFAULT_LUMEN_CIRCULARITY_MIN = 0.20;
    private static final double DEFAULT_LUMEN_ECCENTRICITY_MAX = 0.97;

    private static final int DEFAULT_WALL_CLOSE_KSIZE = 28;
    private static final int DEFAULT_WALL_CLOSE_ITER = 2;

    private static final int DEFAULT_DILATION_WIDTH = 21;
    private static final int DEFAULT_DILATION_HEIGHT = 21;
    private static final int DEFAULT_DILATION_ITER = 1;

    private static final int DEFAULT_EROSION_WIDTH = 3;
    private static final int DEFAULT_EROSION_HEIGHT = 3;
    private static final int DEFAULT_EROSION_ITER = 2;

    private static final int DEFAULT_LUMEN_EXPAND_KSIZE = 5;
    private static final int DEFAULT_LUMEN_EXPAND_ITER = 3;

    private static final int DEFAULT_VESSEL_AREA_MIN = 500;
    private static final String DEFAULT_KERNEL_SHAPE = "ELLIPSE";

    private static final String DEFAULT_THRESHOLD_MODE = "otsu";
    private static final int DEFAULT_PERCENTILE = 10;

    private static final double WINDOW_WIDTH = 980;
    private static final double WINDOW_HEIGHT = 820;


    private static class ExportTask {
        String baseName;
        double xOffset;
        double yOffset;
        ImagePlane plane;
        PathObject parentObject;
        ROI selectedROI;
        File inputDir;

        ExportTask(String baseName,
                   double xOffset,
                   double yOffset,
                   ImagePlane plane,
                   PathObject parentObject,
                   ROI selectedROI,
                   File inputDir) {
            this.baseName = baseName;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.plane = plane;
            this.parentObject = parentObject;
            this.selectedROI = selectedROI;
            this.inputDir = inputDir;
        }
    }

    private static class VesselMeasurement {
        double area;
        double axisMajorLength;
        double axisMinorLength;
        double eccentricity;
        double orientation;

        VesselMeasurement(double area,
                          double axisMajorLength,
                          double axisMinorLength,
                          double eccentricity,
                          double orientation) {
            this.area = area;
            this.axisMajorLength = axisMajorLength;
            this.axisMinorLength = axisMinorLength;
            this.eccentricity = eccentricity;
            this.orientation = orientation;
        }
    }

    private static class ParameterFields {
        ComboBox<String> thresholdMode = new ComboBox<>(
                FXCollections.observableArrayList("otsu", "percentile")
        );
        TextField percentile = new TextField(String.valueOf(DEFAULT_PERCENTILE));

        TextField lumenAreaMin = new TextField(String.valueOf(DEFAULT_LUMEN_AREA_MIN));
        TextField lumenAreaMax = new TextField(String.valueOf(DEFAULT_LUMEN_AREA_MAX));
        TextField lumenCircularityMin = new TextField(String.valueOf(DEFAULT_LUMEN_CIRCULARITY_MIN));
        TextField lumenEccentricityMax = new TextField(String.valueOf(DEFAULT_LUMEN_ECCENTRICITY_MAX));

        TextField wallCloseKsize = new TextField(String.valueOf(DEFAULT_WALL_CLOSE_KSIZE));
        TextField wallCloseIter = new TextField(String.valueOf(DEFAULT_WALL_CLOSE_ITER));

        TextField dilationWidth = new TextField(String.valueOf(DEFAULT_DILATION_WIDTH));
        TextField dilationHeight = new TextField(String.valueOf(DEFAULT_DILATION_HEIGHT));
        TextField dilationIter = new TextField(String.valueOf(DEFAULT_DILATION_ITER));

        TextField erosionWidth = new TextField(String.valueOf(DEFAULT_EROSION_WIDTH));
        TextField erosionHeight = new TextField(String.valueOf(DEFAULT_EROSION_HEIGHT));
        TextField erosionIter = new TextField(String.valueOf(DEFAULT_EROSION_ITER));

        ComboBox<String> kernelShape = new ComboBox<>(
                FXCollections.observableArrayList("ELLIPSE", "RECT", "CROSS")
        );

        TextField lumenExpandKsize = new TextField(String.valueOf(DEFAULT_LUMEN_EXPAND_KSIZE));
        TextField lumenExpandIter = new TextField(String.valueOf(DEFAULT_LUMEN_EXPAND_ITER));

        TextField vesselAreaMin = new TextField(String.valueOf(DEFAULT_VESSEL_AREA_MIN));

        ParameterFields() {
            thresholdMode.setValue(DEFAULT_THRESHOLD_MODE);
            thresholdMode.setPrefWidth(120);

            percentile.setPromptText("1-99");
            thresholdMode.valueProperty().addListener((obs, oldValue, newValue) -> updatePercentileFieldState());
            updatePercentileFieldState();

            kernelShape.setValue(DEFAULT_KERNEL_SHAPE);
            kernelShape.setPrefWidth(120);

            for (TextField field : List.of(
                    percentile,
                    lumenAreaMin, lumenAreaMax, lumenCircularityMin, lumenEccentricityMax,
                    wallCloseKsize, wallCloseIter,
                    dilationWidth, dilationHeight, dilationIter,
                    erosionWidth, erosionHeight, erosionIter,
                    lumenExpandKsize, lumenExpandIter, vesselAreaMin
            )) {
                field.setPrefWidth(120);
            }
        }

        void setDefaults() {
            thresholdMode.setValue(DEFAULT_THRESHOLD_MODE);
            percentile.setText(String.valueOf(DEFAULT_PERCENTILE));
            updatePercentileFieldState();

            lumenAreaMin.setText(String.valueOf(DEFAULT_LUMEN_AREA_MIN));
            lumenAreaMax.setText(String.valueOf(DEFAULT_LUMEN_AREA_MAX));
            lumenCircularityMin.setText(String.valueOf(DEFAULT_LUMEN_CIRCULARITY_MIN));
            lumenEccentricityMax.setText(String.valueOf(DEFAULT_LUMEN_ECCENTRICITY_MAX));

            wallCloseKsize.setText(String.valueOf(DEFAULT_WALL_CLOSE_KSIZE));
            wallCloseIter.setText(String.valueOf(DEFAULT_WALL_CLOSE_ITER));

            dilationWidth.setText(String.valueOf(DEFAULT_DILATION_WIDTH));
            dilationHeight.setText(String.valueOf(DEFAULT_DILATION_HEIGHT));
            dilationIter.setText(String.valueOf(DEFAULT_DILATION_ITER));

            erosionWidth.setText(String.valueOf(DEFAULT_EROSION_WIDTH));
            erosionHeight.setText(String.valueOf(DEFAULT_EROSION_HEIGHT));
            erosionIter.setText(String.valueOf(DEFAULT_EROSION_ITER));

            kernelShape.setValue(DEFAULT_KERNEL_SHAPE);

            lumenExpandKsize.setText(String.valueOf(DEFAULT_LUMEN_EXPAND_KSIZE));
            lumenExpandIter.setText(String.valueOf(DEFAULT_LUMEN_EXPAND_ITER));

            vesselAreaMin.setText(String.valueOf(DEFAULT_VESSEL_AREA_MIN));
        }

        private void updatePercentileFieldState() {
            boolean usePercentile = "percentile".equalsIgnoreCase(thresholdMode.getValue());

            percentile.setDisable(!usePercentile);
            percentile.setOpacity(usePercentile ? 1.0 : 0.45);

            if (usePercentile && percentile.getText().trim().isBlank()) {
                percentile.setText(String.valueOf(DEFAULT_PERCENTILE));
            }
        }
    }

    private static class RunParameters {
        String thresholdMode;
        int percentile;

        int lumenAreaMin;
        int lumenAreaMax;
        double lumenCircularityMin;
        double lumenEccentricityMax;

        int wallCloseKsize;
        int wallCloseIter;

        int dilationWidth;
        int dilationHeight;
        int dilationIter;

        int erosionWidth;
        int erosionHeight;
        int erosionIter;

        String kernelShape;

        int lumenExpandKsize;
        int lumenExpandIter;

        int vesselAreaMin;
    }

    private enum SegmentationPreset {
        BALANCED(
                "Balanced",
                "General purpose settings for typical vessel segmentation.",
                DEFAULT_LUMEN_AREA_MIN, DEFAULT_LUMEN_AREA_MAX, DEFAULT_LUMEN_CIRCULARITY_MIN, DEFAULT_LUMEN_ECCENTRICITY_MAX,
                DEFAULT_WALL_CLOSE_KSIZE, DEFAULT_WALL_CLOSE_ITER,
                DEFAULT_DILATION_WIDTH, DEFAULT_DILATION_HEIGHT, DEFAULT_DILATION_ITER,
                DEFAULT_EROSION_WIDTH, DEFAULT_EROSION_HEIGHT, DEFAULT_EROSION_ITER,
                DEFAULT_KERNEL_SHAPE, DEFAULT_LUMEN_EXPAND_KSIZE, DEFAULT_LUMEN_EXPAND_ITER, DEFAULT_VESSEL_AREA_MIN
        ),
        SENSITIVE(
                "Sensitive",
                "Finds smaller or weaker vessels, with a higher chance of extra detections.",
                120, 100000, 0.12, 0.99,
                30, 2,
                23, 23, 1,
                3, 3, 1,
                "ELLIPSE", 5, 3, 250
        ),
        FRAGMENTED_WALLS(
                "Fragmented walls",
                "Repairs broken vessel boundaries before lumen filling.",
                200, 100000, 0.16, 0.99,
                38, 3,
                25, 25, 1,
                3, 3, 2,
                "ELLIPSE", 7, 4, 450
        ),
        STRICT_CLEANUP(
                "Strict cleanup",
                "Keeps stronger objects and reduces noise-prone detections.",
                300, 60000, 0.28, 0.94,
                24, 1,
                17, 17, 1,
                5, 5, 2,
                "ELLIPSE", 3, 2, 900
        );

        final String label;
        final String description;
        final int lumenAreaMin;
        final int lumenAreaMax;
        final double lumenCircularityMin;
        final double lumenEccentricityMax;
        final int wallCloseKsize;
        final int wallCloseIter;
        final int dilationWidth;
        final int dilationHeight;
        final int dilationIter;
        final int erosionWidth;
        final int erosionHeight;
        final int erosionIter;
        final String kernelShape;
        final int lumenExpandKsize;
        final int lumenExpandIter;
        final int vesselAreaMin;

        SegmentationPreset(String label,
                           String description,
                           int lumenAreaMin,
                           int lumenAreaMax,
                           double lumenCircularityMin,
                           double lumenEccentricityMax,
                           int wallCloseKsize,
                           int wallCloseIter,
                           int dilationWidth,
                           int dilationHeight,
                           int dilationIter,
                           int erosionWidth,
                           int erosionHeight,
                           int erosionIter,
                           String kernelShape,
                           int lumenExpandKsize,
                           int lumenExpandIter,
                           int vesselAreaMin) {
            this.label = label;
            this.description = description;
            this.lumenAreaMin = lumenAreaMin;
            this.lumenAreaMax = lumenAreaMax;
            this.lumenCircularityMin = lumenCircularityMin;
            this.lumenEccentricityMax = lumenEccentricityMax;
            this.wallCloseKsize = wallCloseKsize;
            this.wallCloseIter = wallCloseIter;
            this.dilationWidth = dilationWidth;
            this.dilationHeight = dilationHeight;
            this.dilationIter = dilationIter;
            this.erosionWidth = erosionWidth;
            this.erosionHeight = erosionHeight;
            this.erosionIter = erosionIter;
            this.kernelShape = kernelShape;
            this.lumenExpandKsize = lumenExpandKsize;
            this.lumenExpandIter = lumenExpandIter;
            this.vesselAreaMin = vesselAreaMin;
        }
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions > Vessel Segmentation", true);

        MenuItem runItem = new MenuItem("Run Vessel Segmentation");
        runItem.setOnAction(e -> openWindow(qupath));

        menu.getItems().add(runItem);
    }

    private void openWindow(QuPathGUI qupath) {
        Stage stage = new Stage();
        stage.setTitle("VeSpA - Vessel Spatial Analysis");

        ImageView logoView = createLogoView();
        ParameterFields fields = new ParameterFields();
        applyPreset(fields, SegmentationPreset.BALANCED);

        ToggleGroup inputModeGroup = new ToggleGroup();

        RadioButton selectedAnnotationButton = new RadioButton("Selected annotation(s)");
        selectedAnnotationButton.setToggleGroup(inputModeGroup);
        selectedAnnotationButton.setSelected(true);
        selectedAnnotationButton.setMinWidth(165);
        selectedAnnotationButton.setStyle("-fx-text-fill: #27313a;");

        RadioButton tmaCoresButton = new RadioButton("TMA cores");
        tmaCoresButton.setToggleGroup(inputModeGroup);
        tmaCoresButton.setMinWidth(95);
        tmaCoresButton.setStyle("-fx-text-fill: #27313a;");

        RadioButton wholeImageButton = new RadioButton("Whole image");
        wholeImageButton.setToggleGroup(inputModeGroup);
        wholeImageButton.setMinWidth(115);
        wholeImageButton.setStyle("-fx-text-fill: #27313a;");

        int selectedAnnotationCount = countSelectedAnnotations(qupath);
        boolean pythonReady = isPythonConfigured();

        Label titleLabel = new Label("Vessel Spatial Analysis");
        titleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #162a36;");

        Label subtitleLabel = new Label("Annotation-based vessel segmentation inside QuPath");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #60717c;");

        Label pythonChip = statusChip(pythonReady ? "Python Ready" : "Python Missing", pythonReady);
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(12, new VBox(2, titleLabel, subtitleLabel), headerSpacer, pythonChip);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox inputModeRow = new HBox(16, selectedAnnotationButton, tmaCoresButton, wholeImageButton);
        inputModeRow.setMinWidth(320);
        inputModeRow.setPrefWidth(470);
        inputModeRow.setAlignment(Pos.CENTER_LEFT);

        VBox inputPanel = createSection("Input Region",
                row("Mode", inputModeRow, "Choose the image region used for segmentation"),
                row("Current selection", new Label(selectedAnnotationCount + " annotation(s) selected"), "Detected from QuPath's current selection")
        );

        Label presetDescription = new Label(SegmentationPreset.BALANCED.description);
        presetDescription.setWrapText(true);
        presetDescription.setStyle("-fx-text-fill: #536671; -fx-font-size: 11px;");

        Button balancedButton = presetButton(SegmentationPreset.BALANCED.label);
        Button sensitiveButton = presetButton(SegmentationPreset.SENSITIVE.label);
        Button fragmentedButton = presetButton(SegmentationPreset.FRAGMENTED_WALLS.label);
        Button strictButton = presetButton(SegmentationPreset.STRICT_CLEANUP.label);
        List<Button> presetButtons = List.of(balancedButton, sensitiveButton, fragmentedButton, strictButton);

        balancedButton.setOnAction(e -> selectPreset(fields, SegmentationPreset.BALANCED, presetDescription, presetButtons, balancedButton));
        sensitiveButton.setOnAction(e -> selectPreset(fields, SegmentationPreset.SENSITIVE, presetDescription, presetButtons, sensitiveButton));
        fragmentedButton.setOnAction(e -> selectPreset(fields, SegmentationPreset.FRAGMENTED_WALLS, presetDescription, presetButtons, fragmentedButton));
        strictButton.setOnAction(e -> selectPreset(fields, SegmentationPreset.STRICT_CLEANUP, presetDescription, presetButtons, strictButton));
        styleSelectedPreset(presetButtons, balancedButton);

        VBox presetPanel = createSection("Segmentation Preset",
                row("Preset", new HBox(8, balancedButton, sensitiveButton, fragmentedButton, strictButton), "Presets fill the existing parameter fields"),
                row("Behavior", presetDescription, "The selected preset description")
        );

        VBox quickControlsPanel = createSection("Quick Controls",
                row("Threshold mode", fields.thresholdMode, "Otsu is automatic; percentile uses the value below"),
                row("Percentile value", fields.percentile, "Used only when threshold mode is percentile"),
                row("Minimum vessel area", fields.vesselAreaMin, "Remove small connected components")
        );

        VBox advancedParameters = new VBox(10,
                createSection("Lumen Detection",
                        row("Min area (px²)", fields.lumenAreaMin, "ignore tiny noise holes"),
                        row("Max area (px²)", fields.lumenAreaMax, "ignore artefactually large holes"),
                        row("Circularity min", fields.lumenCircularityMin, "low values allow elongated/irregular lumens"),
                        row("Eccentricity max", fields.lumenEccentricityMax, "reject near-linear artefacts")
                ),
                createSection("Wall Repair",
                        row("Closing kernel size", fields.wallCloseKsize, "increase if vessel walls are fragmented"),
                        row("Closing iterations", fields.wallCloseIter, "increase to improve wall closing")
                ),
                createSection("Morphology",
                        row("Dilation width", fields.dilationWidth, "initial binary cleanup"),
                        row("Dilation height", fields.dilationHeight, "initial binary cleanup"),
                        row("Dilation iterations", fields.dilationIter, "number of dilation passes"),
                        row("Kernel shape", fields.kernelShape, "structuring element shape"),
                        row("Erosion width", fields.erosionWidth, "boundary restoration"),
                        row("Erosion height", fields.erosionHeight, "boundary restoration"),
                        row("Erosion iterations", fields.erosionIter, "number of erosion passes")
                ),
                createSection("Lumen Expansion",
                        row("Expansion kernel size", fields.lumenExpandKsize, "merge lumen onto inner wall boundary"),
                        row("Expansion iterations", fields.lumenExpandIter, "increase to bridge larger inner-wall gaps")
                )
        );

        ScrollPane advancedScrollPane = new ScrollPane(advancedParameters);
        advancedScrollPane.setFitToWidth(true);
        advancedScrollPane.setPrefViewportHeight(210);
        advancedScrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        TitledPane advancedPane = new TitledPane("Advanced Tuning", advancedScrollPane);
        advancedPane.setExpanded(false);
        advancedPane.setAnimated(false);
        advancedPane.setStyle("-fx-font-weight: bold;");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        Label progressLabel = new Label("0%");
        progressLabel.setMinWidth(82);
        progressLabel.setStyle("-fx-text-fill: #536671; -fx-font-size: 11px;");
        HBox progressBox = new HBox(10, progressBar, progressLabel);
        progressBox.setAlignment(Pos.CENTER_LEFT);
        progressBox.setPrefWidth(520);
        progressBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        TextArea logPreview = new TextArea("Ready. Choose a region and run segmentation.");
        logPreview.setEditable(false);
        logPreview.setWrapText(true);
        logPreview.setPrefRowCount(2);
        logPreview.setPrefWidth(520);
        logPreview.setMaxWidth(Double.MAX_VALUE);
        logPreview.setStyle("-fx-control-inner-background: #f7fafb; -fx-font-family: monospace; -fx-font-size: 11px;");

        Button runButton = new Button("Run Segmentation");
        Button resetButton = new Button("Reset");
        Button exitButton = new Button("Exit");
        runButton.setStyle(primaryButtonStyle());
        resetButton.setStyle(secondaryButtonStyle());
        exitButton.setStyle(secondaryButtonStyle());
        runButton.setMaxWidth(Double.MAX_VALUE);

        VBox runPanel = createSection("Run",
                row("Action", new HBox(10, runButton, resetButton, exitButton), "Start segmentation or reset values"),
                rowWide("Progress", progressBox, "Updates after each annotation completes"),
                rowWide("Log", logPreview, "Compact run status")
        );

        Button configButton = new Button("Configure Python");
        configButton.setStyle(secondaryButtonStyle());

        VBox imageStatusCard = statusCard("Image", qupath.getImageData() == null ? "No image open" : "Image open", qupath.getImageData() != null);
        VBox selectionStatusCard = statusCard("Selection", selectedAnnotationCount + " annotation(s)", selectedAnnotationCount > 0);
        VBox pythonStatusCard = statusCard("Python", pythonReady ? "Ready" : "Needs setup", pythonReady);

        VBox sidebar = new VBox(14,
                logoView,
                new Label("VeSpA"),
                imageStatusCard,
                selectionStatusCard,
                pythonStatusCard,
                configButton,
                new Label("v0.0.1 GUI prototype")
        );

        configButton.setOnAction(e -> {
            PythonConfigDialog dialog = new PythonConfigDialog();
            dialog.showDialog();
            refreshPythonStatus(pythonChip, pythonStatusCard);
        });
        sidebar.setAlignment(Pos.TOP_CENTER);
        sidebar.setPadding(new Insets(12));
        sidebar.setPrefWidth(190);
        sidebar.setStyle("-fx-background-color: #ffffff;" +
                "-fx-border-color: #d7e0e5;" +
                "-fx-border-width: 0 1 0 0;");
        sidebar.getChildren().get(1).setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #17313b;");

        VBox mainPanel = new VBox(10, header, inputPanel, presetPanel, quickControlsPanel, runPanel, advancedPane);
        mainPanel.setPadding(new Insets(14));
        HBox.setHgrow(mainPanel, Priority.ALWAYS);

        ScrollPane mainScrollPane = new ScrollPane(mainPanel);
        mainScrollPane.setFitToWidth(true);
        mainScrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        HBox.setHgrow(mainScrollPane, Priority.ALWAYS);

        HBox root = new HBox(sidebar, mainScrollPane);
        root.setStyle("-fx-background-color: #eef4f7;");

        resetButton.setOnAction(e -> {
            applyPreset(fields, SegmentationPreset.BALANCED);
            presetDescription.setText(SegmentationPreset.BALANCED.description);
            styleSelectedPreset(presetButtons, balancedButton);
            progressBar.setProgress(0);
            progressLabel.setText("0%");
            logPreview.setText("Reset to Balanced preset.");
        });
        exitButton.setOnAction(e -> stage.close());

        runButton.setOnAction(e -> {
            RunParameters params;
            try {
                params = parseParameters(fields);
            } catch (Exception ex) {
                showMessage(Alert.AlertType.ERROR, "Input Error", ex.getMessage());
                return;
            }

            InputRegionMode inputRegionMode = selectedAnnotationButton.isSelected()
                    ? InputRegionMode.SELECTED_ANNOTATIONS
                    : tmaCoresButton.isSelected()
                    ? InputRegionMode.TMA_CORES
                    : InputRegionMode.WHOLE_IMAGE;
            runSegmentation(
                    qupath,
                    params,
                    inputRegionMode,
                    progressBar,
                    progressLabel,
                    logPreview,
                    List.of(runButton, resetButton, exitButton, configButton)
            );
        });

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        stage.setScene(scene);

        var visualBounds = Screen.getPrimary().getVisualBounds();
        stage.setResizable(true);
        stage.setMinWidth(760);
        stage.setMinHeight(560);
        stage.setWidth(Math.min(WINDOW_WIDTH, visualBounds.getWidth() * 0.98));
        stage.setHeight(Math.min(WINDOW_HEIGHT, visualBounds.getHeight() * 0.96));

        stage.setMaximized(false);
        stage.centerOnScreen();

        stage.show();
    }

    private String cardStyle() {
        return "-fx-background-color: #ffffff;" +
                "-fx-border-color: #cfd6dd;" +
                "-fx-border-width: 1;" +
                "-fx-background-radius: 8;" +
                "-fx-border-radius: 8;" +
                "-fx-effect: dropshadow(gaussian, rgba(18,44,55,0.08), 8, 0, 0, 2);";
    }

    private String primaryButtonStyle() {
        return "-fx-background-color: #0f7f7a;" +
                "-fx-text-fill: white;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 7;" +
                "-fx-border-radius: 7;" +
                "-fx-padding: 7 14 7 14;";
    }

    private String secondaryButtonStyle() {
        return "-fx-background-color: #eef5f6;" +
                "-fx-text-fill: #20424b;" +
                "-fx-border-color: #bdd0d5;" +
                "-fx-background-radius: 7;" +
                "-fx-border-radius: 7;" +
                "-fx-padding: 7 12 7 12;";
    }

    private Button presetButton(String label) {
        Button button = new Button(label);
        button.setStyle(presetButtonStyle(false));
        button.setMinWidth(118);
        button.setPrefWidth(label.length() > 14 ? 150 : 118);
        return button;
    }

    private String presetButtonStyle(boolean selected) {
        if (selected) {
            return "-fx-background-color: #0f7f7a;" +
                    "-fx-text-fill: white;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 7;" +
                    "-fx-border-radius: 7;" +
                    "-fx-padding: 6 10 6 10;";
        }

        return "-fx-background-color: #f6fafb;" +
                "-fx-text-fill: #24444d;" +
                "-fx-border-color: #c6d7dc;" +
                "-fx-background-radius: 7;" +
                "-fx-border-radius: 7;" +
                "-fx-padding: 6 10 6 10;";
    }

    private void selectPreset(ParameterFields fields,
                              SegmentationPreset preset,
                              Label presetDescription,
                              List<Button> presetButtons,
                              Button selectedButton) {
        applyPreset(fields, preset);
        presetDescription.setText(preset.description);
        styleSelectedPreset(presetButtons, selectedButton);
    }

    private void styleSelectedPreset(List<Button> presetButtons, Button selectedButton) {
        for (Button button : presetButtons) {
            button.setStyle(presetButtonStyle(button == selectedButton));
        }
    }

    private void applyPreset(ParameterFields fields, SegmentationPreset preset) {
        fields.thresholdMode.setValue(DEFAULT_THRESHOLD_MODE);
        fields.percentile.setText(String.valueOf(DEFAULT_PERCENTILE));

        fields.lumenAreaMin.setText(String.valueOf(preset.lumenAreaMin));
        fields.lumenAreaMax.setText(String.valueOf(preset.lumenAreaMax));
        fields.lumenCircularityMin.setText(String.valueOf(preset.lumenCircularityMin));
        fields.lumenEccentricityMax.setText(String.valueOf(preset.lumenEccentricityMax));

        fields.wallCloseKsize.setText(String.valueOf(preset.wallCloseKsize));
        fields.wallCloseIter.setText(String.valueOf(preset.wallCloseIter));

        fields.dilationWidth.setText(String.valueOf(preset.dilationWidth));
        fields.dilationHeight.setText(String.valueOf(preset.dilationHeight));
        fields.dilationIter.setText(String.valueOf(preset.dilationIter));

        fields.erosionWidth.setText(String.valueOf(preset.erosionWidth));
        fields.erosionHeight.setText(String.valueOf(preset.erosionHeight));
        fields.erosionIter.setText(String.valueOf(preset.erosionIter));

        fields.kernelShape.setValue(preset.kernelShape);

        fields.lumenExpandKsize.setText(String.valueOf(preset.lumenExpandKsize));
        fields.lumenExpandIter.setText(String.valueOf(preset.lumenExpandIter));

        fields.vesselAreaMin.setText(String.valueOf(preset.vesselAreaMin));
    }

    private int countSelectedAnnotations(QuPathGUI qupath) {
        if (qupath.getImageData() == null) {
            return 0;
        }

        return (int) qupath.getImageData()
                .getHierarchy()
                .getSelectionModel()
                .getSelectedObjects()
                .stream()
                .filter(obj -> obj != null && obj.isAnnotation() && obj.getROI() != null)
                .count();
    }

    private boolean isPythonConfigured() {
        String pythonExec = PREFS.get(PREF_PYTHON_EXEC, "");
        return !pythonExec.isBlank() && new File(pythonExec).exists();
    }

    private Label statusChip(String text, boolean ok) {
        Label label = new Label(text);
        applyStatusChipStyle(label, ok);
        return label;
    }

    private void applyStatusChipStyle(Label label, boolean ok) {
        label.setStyle("-fx-background-color: " + (ok ? "#dff4ee" : "#fff2d5") + ";" +
                "-fx-text-fill: " + (ok ? "#12664f" : "#805b00") + ";" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 5 10 5 10;");
    }

    private void refreshPythonStatus(Label pythonChip, VBox pythonStatusCard) {
        boolean pythonReady = isPythonConfigured();
        pythonChip.setText(pythonReady ? "Python Ready" : "Python Missing");
        applyStatusChipStyle(pythonChip, pythonReady);
        updateStatusCard(pythonStatusCard, "Python", pythonReady ? "Ready" : "Needs setup", pythonReady);
    }

    private VBox statusCard(String label, String value, boolean ok) {
        Label title = new Label(label);
        title.setStyle("-fx-text-fill: " + statusTitleColor(label, ok) + "; -fx-font-size: 10px;");

        Label body = new Label(value);
        body.setWrapText(true);
        body.setStyle("-fx-text-fill: " + statusBodyColor(label, ok) + "; -fx-font-weight: bold;");

        VBox box = new VBox(3, title, body);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: " + statusBackground(label, ok) + ";" +
                "-fx-border-color: " + statusBorder(label, ok) + ";" +
                "-fx-background-radius: 8;" +
                "-fx-border-radius: 8;");
        return box;
    }

    private void updateStatusCard(VBox card, String label, String value, boolean ok) {
        Label title = (Label) card.getChildren().get(0);
        Label body = (Label) card.getChildren().get(1);
        title.setText(label);
        body.setText(value);
        title.setStyle("-fx-text-fill: " + statusTitleColor(label, ok) + "; -fx-font-size: 10px;");
        body.setStyle("-fx-text-fill: " + statusBodyColor(label, ok) + "; -fx-font-weight: bold;");
        card.setStyle("-fx-background-color: " + statusBackground(label, ok) + ";" +
                "-fx-border-color: " + statusBorder(label, ok) + ";" +
                "-fx-background-radius: 8;" +
                "-fx-border-radius: 8;");
    }

    private String statusBackground(String label, boolean ok) {
        if (!ok) {
            return "#fff8e8";
        }
        if ("Image".equals(label)) {
            return "#edf8fb";
        }
        if ("Selection".equals(label)) {
            return "#f1f7ee";
        }
        if ("Python".equals(label)) {
            return "#edf7f3";
        }
        return "#f6fafb";
    }

    private String statusBorder(String label, boolean ok) {
        if (!ok) {
            return "#efd9a4";
        }
        if ("Image".equals(label)) {
            return "#bddce6";
        }
        if ("Selection".equals(label)) {
            return "#c9dfc0";
        }
        if ("Python".equals(label)) {
            return "#b7ded3";
        }
        return "#d7e2e6";
    }

    private String statusTitleColor(String label, boolean ok) {
        if (!ok) {
            return "#9a7210";
        }
        if ("Image".equals(label)) {
            return "#47717d";
        }
        if ("Selection".equals(label)) {
            return "#557646";
        }
        if ("Python".equals(label)) {
            return "#4d7a6c";
        }
        return "#71838b";
    }

    private String statusBodyColor(String label, boolean ok) {
        if (!ok) {
            return "#805b00";
        }
        if ("Image".equals(label)) {
            return "#174e5d";
        }
        if ("Selection".equals(label)) {
            return "#315c25";
        }
        if ("Python".equals(label)) {
            return "#12664f";
        }
        return "#17313b";
    }

    private VBox metricCard(String label, String value) {
        Label title = new Label(label);
        title.setStyle("-fx-text-fill: #60717c; -fx-font-size: 10px;");

        Label body = new Label(value);
        body.setStyle("-fx-text-fill: #17313b; -fx-font-weight: bold;");

        VBox box = new VBox(3, title, body);
        box.setPadding(new Insets(9));
        box.setPrefWidth(130);
        box.setStyle("-fx-background-color: #f6fafb;" +
                "-fx-border-color: #d7e2e6;" +
                "-fx-background-radius: 8;" +
                "-fx-border-radius: 8;");
        return box;
    }

    private VBox createSection(String title, HBox... rows) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #243746; -fx-font-size: 12px;");

        VBox box = new VBox(8);
        box.setPadding(new Insets(12, 12, 12, 12));
        box.setStyle("-fx-background-color: #ffffff;" +
                "-fx-border-color: #d9dee5;" +
                "-fx-border-width: 1;" +
                "-fx-background-radius: 10;" +
                "-fx-border-radius: 10;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 6, 0, 0, 1);");

        Separator separator = new Separator();
        separator.setStyle("-fx-background-color: #d9dee5;");

        box.getChildren().add(titleLabel);
        box.getChildren().add(separator);
        box.getChildren().addAll(rows);
        return box;
    }

    private HBox row(String label, TextField field, String hint) {
        Label l = new Label(label);
        l.setPrefWidth(185);
        l.setStyle("-fx-text-fill: #27313a; -fx-font-size: 11px;");
        installTooltip(l, hint);

        field.setPrefWidth(125);
        field.setStyle("-fx-background-radius: 6;" +
                "-fx-border-radius: 6;" +
                "-fx-border-color: #aeb7c2;" +
                "-fx-background-color: white;" +
                "-fx-padding: 4 6 4 6;");
        installTooltip(field, hint);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, l, field, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        installTooltip(row, hint);
        return row;
    }

    private HBox row(String label, Region field, String hint) {
        Label l = new Label(label);
        l.setPrefWidth(150);
        l.setStyle("-fx-text-fill: #27313a; -fx-font-size: 11px;");
        installTooltip(l, hint);
        installTooltip(field, hint);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, l, field, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        installTooltip(row, hint);
        return row;
    }

    private HBox rowWide(String label, Region field, String hint) {
        HBox row = row(label, field, hint);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private HBox row(String label, ComboBox<String> field, String hint) {
        Label l = new Label(label);
        l.setPrefWidth(185);
        l.setStyle("-fx-text-fill: #27313a; -fx-font-size: 11px;");
        installTooltip(l, hint);

        field.setPrefWidth(125);
        field.setStyle("-fx-background-radius: 6;" +
                "-fx-border-radius: 6;" +
                "-fx-border-color: #aeb7c2;" +
                "-fx-background-color: white;" +
                "-fx-padding: 2 4 2 4;");
        installTooltip(field, hint);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, l, field, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        installTooltip(row, hint);
        return row;
    }

    private void installTooltip(javafx.scene.Node node, String tooltipText) {
        Tooltip tooltip = new Tooltip(tooltipText);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(280);
        Tooltip.install(node, tooltip);
    }

    private void clearFields(ParameterFields f) {
        for (TextField field : List.of(
                f.percentile,
                f.lumenAreaMin, f.lumenAreaMax, f.lumenCircularityMin, f.lumenEccentricityMax,
                f.wallCloseKsize, f.wallCloseIter,
                f.dilationWidth, f.dilationHeight, f.dilationIter,
                f.erosionWidth, f.erosionHeight, f.erosionIter,
                f.lumenExpandKsize, f.lumenExpandIter, f.vesselAreaMin
        )) {
            field.clear();
        }
        f.thresholdMode.setValue(DEFAULT_THRESHOLD_MODE);
        f.percentile.setText(String.valueOf(DEFAULT_PERCENTILE));
        f.kernelShape.setValue(DEFAULT_KERNEL_SHAPE);
    }

    private RunParameters parseParameters(ParameterFields f) {
        RunParameters p = new RunParameters();

        p.thresholdMode = f.thresholdMode.getValue();
        if (p.thresholdMode == null || p.thresholdMode.isBlank()) {
            throw new IllegalArgumentException("Threshold mode must be selected.");
        }

        if ("percentile".equalsIgnoreCase(p.thresholdMode)) {
            p.percentile = parsePositiveInt(f.percentile, "Percentile value");
            if (p.percentile < 1 || p.percentile > 99) {
                throw new IllegalArgumentException("Percentile value must be between 1 and 99.");
            }
        } else {
            p.percentile = DEFAULT_PERCENTILE;
        }

        p.lumenAreaMin = parsePositiveInt(f.lumenAreaMin, "Lumen min area");
        p.lumenAreaMax = parsePositiveInt(f.lumenAreaMax, "Lumen max area");
        p.lumenCircularityMin = parseDoubleRange(f.lumenCircularityMin, "Lumen circularity min", 0.0, 1.0);
        p.lumenEccentricityMax = parseDoubleRange(f.lumenEccentricityMax, "Lumen eccentricity max", 0.0, 1.0);

        p.wallCloseKsize = parsePositiveInt(f.wallCloseKsize, "Wall close kernel size");
        p.wallCloseIter = parseNonNegativeInt(f.wallCloseIter, "Wall close iterations");

        p.dilationWidth = parsePositiveInt(f.dilationWidth, "Dilation width");
        p.dilationHeight = parsePositiveInt(f.dilationHeight, "Dilation height");
        p.dilationIter = parseNonNegativeInt(f.dilationIter, "Dilation iterations");

        p.erosionWidth = parsePositiveInt(f.erosionWidth, "Erosion width");
        p.erosionHeight = parsePositiveInt(f.erosionHeight, "Erosion height");
        p.erosionIter = parseNonNegativeInt(f.erosionIter, "Erosion iterations");

        p.kernelShape = f.kernelShape.getValue();

        p.lumenExpandKsize = parsePositiveInt(f.lumenExpandKsize, "Lumen expansion kernel size");
        p.lumenExpandIter = parseNonNegativeInt(f.lumenExpandIter, "Lumen expansion iterations");

        p.vesselAreaMin = parsePositiveInt(f.vesselAreaMin, "Minimum vessel area");

        if (p.lumenAreaMin > p.lumenAreaMax) {
            throw new IllegalArgumentException("Lumen min area cannot be greater than lumen max area.");
        }

        return p;
    }

    private int parsePositiveInt(TextField field, String name) {
        int value = Integer.parseInt(field.getText().trim());
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than 0.");
        }
        return value;
    }

    private int parseNonNegativeInt(TextField field, String name) {
        int value = Integer.parseInt(field.getText().trim());
        if (value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative.");
        }
        return value;
    }

    private double parseDoubleRange(TextField field, String name, double min, double max) {
        double value = Double.parseDouble(field.getText().trim());
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    private ImageView createLogoView() {
        try {
            InputStream is = getClass().getResourceAsStream(LOGO_RESOURCE);
            if (is == null) {
                System.out.println("VeSpA logo not found at: " + LOGO_RESOURCE);
                return new ImageView();
            }

            Image image = new Image(is);
            ImageView imageView = new ImageView(image);
            imageView.setPreserveRatio(true);
            imageView.setFitWidth(110);
            imageView.setSmooth(true);
            return imageView;

        } catch (Exception e) {
            e.printStackTrace();
            return new ImageView();
        }
    }

    private String ensurePythonConfigured() {
        String pythonExec = PREFS.get(PREF_PYTHON_EXEC, "");

        if (pythonExec.isBlank() || !new File(pythonExec).exists()) {
            PythonConfigDialog dialog = new PythonConfigDialog();
            boolean ok = dialog.showDialog();
            if (!ok) {
                return null;
            }
            pythonExec = dialog.getPythonExecutable();
        }

        return pythonExec;
    }

    private File extractBundledPythonScript() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(PYTHON_SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled Python script not found in JAR: " + PYTHON_SCRIPT_RESOURCE);
            }

            File tempScript = File.createTempFile("vespa_vessel_segmentation_", ".py");
            tempScript.deleteOnExit();

            try (OutputStream out = Files.newOutputStream(tempScript.toPath())) {
                in.transferTo(out);
            }

            return tempScript;
        }
    }

    private void runSegmentation(QuPathGUI qupath,
                                 RunParameters params,
                                 InputRegionMode inputRegionMode,
                                 ProgressBar progressBar,
                                 Label progressLabel,
                                 TextArea logPreview,
                                 List<Button> controls) {

        try {
            String pythonExec = ensurePythonConfigured();
            if (pythonExec == null) {
                return;
            }

            if (qupath.getImageData() == null) {
                showMessage(Alert.AlertType.ERROR, "No Image", "No image is currently open in QuPath.");
                return;
            }

            for (Button control : controls) {
                control.setDisable(true);
            }
            progressBar.setProgress(0);
            progressLabel.setText(initialProgressLabel(inputRegionMode));
            logPreview.setText("Preparing segmentation job...");

            Thread worker = new Thread(() -> {
                try {
                    int totalAdded = runSegmentationJob(
                            qupath,
                            params,
                            inputRegionMode,
                            pythonExec,
                            progressBar,
                            progressLabel,
                            logPreview
                    );

                    Platform.runLater(() -> {
                        progressBar.setProgress(1.0);
                        progressLabel.setText("100%");
                        logPreview.appendText("\nSegmentation completed. Objects added: " + totalAdded);
                        for (Button control : controls) {
                            control.setDisable(false);
                        }
                        showMessage(
                                Alert.AlertType.INFORMATION,
                                "Segmentation Finished",
                                "Segmentation completed successfully.\n\nObjects added to QuPath: " + totalAdded
                        );
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        progressBar.setProgress(0);
                        progressLabel.setText("Failed");
                        logPreview.appendText("\nError: " + ex.getMessage());
                        for (Button control : controls) {
                            control.setDisable(false);
                        }
                        showMessage(Alert.AlertType.ERROR, "Execution Error", ex.getMessage());
                    });
                }
            }, "vespa-segmentation-runner");

            worker.setDaemon(true);
            worker.start();

        } catch (Exception ex) {
            ex.printStackTrace();
            showMessage(Alert.AlertType.ERROR, "Execution Error", ex.getMessage());
        }
    }

    private int runSegmentationJob(QuPathGUI qupath,
                                   RunParameters params,
                                   InputRegionMode inputRegionMode,
                                   String pythonExec,
                                   ProgressBar progressBar,
                                   Label progressLabel,
                                   TextArea logPreview) throws Exception {

            File extractedScript = extractBundledPythonScript();
            File outputDir = Files.createTempDirectory("qupath_vessel_output").toFile();
            outputDir.deleteOnExit();

            ImageServer<BufferedImage> server = qupath.getImageData().getServer();

            List<ExportTask> exportTasks = new ArrayList<>();
            updateRunStatus(progressBar, progressLabel, logPreview, 0, 1, "Exporting input regions...");

            if (inputRegionMode == InputRegionMode.SELECTED_ANNOTATIONS) {
                var selectionModel = qupath.getImageData().getHierarchy().getSelectionModel();
                List<PathObject> selectedObjects = new ArrayList<>(selectionModel.getSelectedObjects());

                if (selectedObjects.isEmpty() && qupath.getViewer() != null) {
                    PathObject fallback = qupath.getViewer().getSelectedObject();
                    if (fallback != null) {
                        selectedObjects.add(fallback);
                    }
                }

                List<PathObject> validAnnotations = new ArrayList<>();
                for (PathObject obj : selectedObjects) {
                    if (obj != null && obj.isAnnotation() && obj.getROI() != null) {
                        validAnnotations.add(obj);
                    }
                }

                if (validAnnotations.isEmpty()) {
                    throw new IllegalArgumentException("Please select one or more annotations first.");
                }

                int index = 1;
                for (PathObject selectedObject : validAnnotations) {
                    ROI roi = selectedObject.getROI();

                    RegionRequest request = RegionRequest.createInstance(server.getPath(), 1.0, roi);
                    BufferedImage img = server.readRegion(request);

                    String baseName = "qupath_annotation_export_" + index;
                    File taskInputDir = Files.createTempDirectory("qupath_vessel_input_" + index + "_").toFile();
                    taskInputDir.deleteOnExit();
                    File tempImage = new File(taskInputDir, baseName + ".png");
                    ImageIO.write(img, "PNG", tempImage);

                    exportTasks.add(new ExportTask(
                            baseName,
                            roi.getBoundsX(),
                            roi.getBoundsY(),
                            roi.getImagePlane(),
                            selectedObject,
                            roi,
                            taskInputDir
                    ));
                    index++;
                }

            } else if (inputRegionMode == InputRegionMode.TMA_CORES) {
                var hierarchy = qupath.getImageData().getHierarchy();
                TMAGrid tmaGrid = hierarchy.getTMAGrid();

                if (tmaGrid == null) {
                    throw new IllegalArgumentException("No TMA grid was found in the current image.");
                }

                List<TMACoreObject> validCores = new ArrayList<>();
                for (TMACoreObject core : tmaGrid.getTMACoreList()) {
                    if (core == null || core.isMissing() || core.getROI() == null) {
                        continue;
                    }
                    validCores.add(core);
                }

                if (validCores.isEmpty()) {
                    throw new IllegalArgumentException("No TMA grid was found in the current image.");
                }

                int index = 1;
                for (TMACoreObject core : validCores) {
                    ROI roi = core.getROI();

                    RegionRequest request = RegionRequest.createInstance(server.getPath(), 1.0, roi);
                    BufferedImage img = server.readRegion(request);

                    String baseName = "qupath_tma_core_export_" + index;
                    File taskInputDir = Files.createTempDirectory("qupath_vessel_tma_core_" + index + "_").toFile();
                    taskInputDir.deleteOnExit();
                    File tempImage = new File(taskInputDir, baseName + ".png");
                    ImageIO.write(img, "PNG", tempImage);

                    exportTasks.add(new ExportTask(
                            baseName,
                            roi.getBoundsX(),
                            roi.getBoundsY(),
                            roi.getImagePlane(),
                            core,
                            roi,
                            taskInputDir
                    ));
                    index++;
                }

            } else {
                BufferedImage img = server.readRegion(
                        RegionRequest.createInstance(
                                server.getPath(),
                                1.0,
                                0,
                                0,
                                server.getWidth(),
                                server.getHeight()
                        )
                );

                String baseName = "qupath_export";
                File taskInputDir = Files.createTempDirectory("qupath_vessel_input_whole_").toFile();
                taskInputDir.deleteOnExit();
                File tempImage = new File(taskInputDir, baseName + ".png");
                ImageIO.write(img, "PNG", tempImage);

                exportTasks.add(new ExportTask(
                        baseName,
                        0,
                        0,
                        ImagePlane.getDefaultPlane(),
                        null,
                        null,
                        taskInputDir
                ));
            }

            int totalTasks = exportTasks.size();
            int completedTasks = 0;
            int totalAdded = 0;

            for (ExportTask task : exportTasks) {
                updateRunStatus(
                        progressBar,
                        progressLabel,
                        logPreview,
                        completedTasks,
                        totalTasks,
                        taskProgressMessage(inputRegionMode, completedTasks + 1, totalTasks, "processing")
                );

            ProcessBuilder pb = new ProcessBuilder(
                    pythonExec,
                    extractedScript.getAbsolutePath(),
                    task.inputDir.getAbsolutePath(),
                    outputDir.getAbsolutePath(),

                    "--threshold-mode", params.thresholdMode,
                    "--percentile", String.valueOf(params.percentile),

                    "--lumen-area-min", String.valueOf(params.lumenAreaMin),
                    "--lumen-area-max", String.valueOf(params.lumenAreaMax),
                    "--lumen-circularity-min", String.valueOf(params.lumenCircularityMin),
                    "--lumen-eccentricity-max", String.valueOf(params.lumenEccentricityMax),

                    "--wall-close-ksize", String.valueOf(params.wallCloseKsize),
                    "--wall-close-iter", String.valueOf(params.wallCloseIter),

                    "--dilation-kernel-width", String.valueOf(params.dilationWidth),
                    "--dilation-kernel-height", String.valueOf(params.dilationHeight),
                    "--dilation-iter", String.valueOf(params.dilationIter),
                    "--dilation-kernel-shape", params.kernelShape,

                    "--erosion-kernel-width", String.valueOf(params.erosionWidth),
                    "--erosion-kernel-height", String.valueOf(params.erosionHeight),
                    "--erosion-iter", String.valueOf(params.erosionIter),

                    "--lumen-expand-ksize", String.valueOf(params.lumenExpandKsize),
                    "--lumen-expand-iter", String.valueOf(params.lumenExpandIter),

                    "--vessel-area-min", String.valueOf(params.vesselAreaMin)
            );

            pb.redirectErrorStream(true);

            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            StringBuilder log = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                log.append(line).append("\n");
                double stageProgress = progressFromPythonLog(line);
                if (stageProgress >= 0) {
                    updateRunStatus(
                            progressBar,
                            progressLabel,
                            logPreview,
                            completedTasks,
                            totalTasks,
                            stageProgress,
                            line
                    );
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0 || !log.toString().contains("VESSEL_SEGMENTATION_SUCCESS")) {
                    throw new IllegalStateException(log.toString().isBlank() ? "Python process failed." : log.toString());
            }

                File imageOutputDir = new File(outputDir, task.baseName);
                File contourCsv = new File(imageOutputDir, "vessel_contours.csv");
                File measurementCsv = new File(imageOutputDir, task.baseName + "_measurements.csv");

                if (!contourCsv.exists()) {
                    System.out.println("Skipping missing contour CSV: " + contourCsv.getAbsolutePath());
                    completedTasks++;
                    updateRunStatus(
                            progressBar,
                            progressLabel,
                            logPreview,
                            completedTasks,
                            totalTasks,
                            taskProgressMessage(inputRegionMode, completedTasks, totalTasks, "completed with no contour CSV")
                    );
                    continue;
                }

                totalAdded += importObjectsFromCsvOnFxThread(
                        qupath,
                        contourCsv,
                        measurementCsv,
                        task.xOffset,
                        task.yOffset,
                        task.plane,
                        task.parentObject,
                        task.selectedROI,
                        inputRegionMode
                );

                completedTasks++;
                updateRunStatus(
                        progressBar,
                        progressLabel,
                        logPreview,
                        completedTasks,
                        totalTasks,
                        taskProgressMessage(inputRegionMode, completedTasks, totalTasks, "completed")
                );
            }

            Platform.runLater(() -> {
                qupath.getImageData().getHierarchy().fireHierarchyChangedEvent(this);
                if (qupath.getViewer() != null) {
                    qupath.getViewer().repaintEntireImage();
                }
            });

            return totalAdded;
    }

    private void updateRunStatus(ProgressBar progressBar,
                                 Label progressLabel,
                                 TextArea logPreview,
                                 int completed,
                                 int total,
                                 String message) {
        updateRunStatus(progressBar, progressLabel, logPreview, completed, total, 0.0, message);
    }

    private void updateRunStatus(ProgressBar progressBar,
                                 Label progressLabel,
                                 TextArea logPreview,
                                 int completed,
                                 int total,
                                 double currentTaskProgress,
                                 String message) {
        double progress;
        if (total <= 0) {
            progress = 0;
        } else {
            double boundedTaskProgress = Math.max(0.0, Math.min(0.99, currentTaskProgress));
            progress = (completed + boundedTaskProgress) / total;
        }
        int percent = (int) Math.round(progress * 100);

        Platform.runLater(() -> {
            progressBar.setProgress(progress);
            if (total > 1) {
                progressLabel.setText(percent + "% (" + completed + "/" + total + ")");
            } else {
                progressLabel.setText(percent + "%");
            }
            logPreview.appendText("\n" + message);
            logPreview.setScrollTop(Double.MAX_VALUE);
        });
    }

    private double progressFromPythonLog(String line) {
        String text = line.toLowerCase();

        if (text.contains("found ") && text.contains("png files")) {
            return 0.05;
        }
        if (text.contains("thresholding")) {
            return 0.18;
        }
        if (text.contains("filling vessel lumens")) {
            return 0.42;
        }
        if (text.contains("saved measurements")) {
            return 0.62;
        }
        if (text.contains("saved contours")) {
            return 0.72;
        }
        if (text.contains("saved filled binary mask")) {
            return 0.82;
        }
        if (text.contains("saved overlay")) {
            return 0.92;
        }
        if (text.contains("vessel_segmentation_success")) {
            return 0.98;
        }

        return -1;
    }

    private String initialProgressLabel(InputRegionMode inputRegionMode) {
        return switch (inputRegionMode) {
            case SELECTED_ANNOTATIONS -> "0 annotations";
            case TMA_CORES -> "0 TMA cores";
            case WHOLE_IMAGE -> "Processing whole image";
        };
    }

    private String taskProgressMessage(InputRegionMode inputRegionMode, int current, int total, String state) {
        if (inputRegionMode == InputRegionMode.SELECTED_ANNOTATIONS) {
            return "Annotation " + current + "/" + total + " " + state + ".";
        }

        if (inputRegionMode == InputRegionMode.TMA_CORES) {
            return "TMA core " + current + "/" + total + " " + state + ".";
        }

        return "Whole image " + state + ".";
    }

    private int importObjectsFromCsvOnFxThread(QuPathGUI qupath,
                                               File contourCsv,
                                               File measurementCsv,
                                               double xOffset,
                                               double yOffset,
                                               ImagePlane plane,
                                               PathObject parentObject,
                                               ROI selectedROI,
                                               InputRegionMode inputRegionMode) throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger added = new AtomicInteger(0);
        AtomicReference<Exception> error = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                added.set(importObjectsFromCsv(
                        qupath,
                        contourCsv,
                        measurementCsv,
                        xOffset,
                        yOffset,
                        plane,
                        parentObject,
                        selectedROI,
                        inputRegionMode
                ));
            } catch (Exception ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });

        latch.await();

        if (error.get() != null) {
            throw error.get();
        }

        return added.get();
    }

    private int importObjectsFromCsv(QuPathGUI qupath,
                                     File contourCsv,
                                     File measurementCsv,
                                     double xOffset,
                                     double yOffset,
                                     ImagePlane plane,
                                     PathObject parentObject,
                                     ROI selectedROI,
                                     InputRegionMode inputRegionMode) throws Exception {

        Map<Integer, List<Point2>> contourMap = new LinkedHashMap<>();
        Map<Integer, VesselMeasurement> measurementMap = readMeasurementCsv(measurementCsv);

        try (BufferedReader br = new BufferedReader(new FileReader(contourCsv))) {
            String line = br.readLine();

            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 4) {
                    continue;
                }

                int contourId = Integer.parseInt(parts[0].trim());
                double x = Double.parseDouble(parts[2].trim()) + xOffset;
                double y = Double.parseDouble(parts[3].trim()) + yOffset;

                contourMap.computeIfAbsent(contourId, k -> new ArrayList<>())
                        .add(new Point2(x, y));
            }
        }

        List<PathObject> objects = new ArrayList<>();
        PathClass vesselClass = PathClass.fromString("Vessel");

        for (Map.Entry<Integer, List<Point2>> entry : contourMap.entrySet()) {
            int vesselId = entry.getKey();
            List<Point2> points = entry.getValue();

            if (points.size() < 3) {
                continue;
            }

            var roi = ROIs.createPolygonROI(points, plane);

            if (inputRegionMode != InputRegionMode.WHOLE_IMAGE && selectedROI != null) {
                double cx = roi.getCentroidX();
                double cy = roi.getCentroidY();

                if (!selectedROI.contains(cx, cy)) {
                    continue;
                }
            }

            var obj = PathObjects.createDetectionObject(roi, vesselClass);

            obj.getMeasurementList().put("VeSpA: Vessel ID", vesselId);

            VesselMeasurement m = measurementMap.get(vesselId);
            if (m != null) {
                obj.getMeasurementList().put("VeSpA: Area", m.area);
                obj.getMeasurementList().put("VeSpA: Major axis length", m.axisMajorLength);
                obj.getMeasurementList().put("VeSpA: Minor axis length", m.axisMinorLength);
                obj.getMeasurementList().put("VeSpA: Eccentricity", m.eccentricity);
                obj.getMeasurementList().put("VeSpA: Orientation", m.orientation);
            }

            objects.add(obj);
        }

        var hierarchy = qupath.getImageData().getHierarchy();

        if (parentObject != null && (parentObject.isAnnotation() || parentObject instanceof TMACoreObject)) {
            parentObject.addChildObjects(objects);
            parentObject.getMeasurementList().put("Num Vessel", objects.size());
            addParentSummaryMeasurements(parentObject, objects);
            hierarchy.fireHierarchyChangedEvent(parentObject);
        } else {
            hierarchy.addObjects(objects);
            hierarchy.fireHierarchyChangedEvent(this);
        }

        System.out.println("Objects imported from " + contourCsv.getName() + ": " + objects.size());
        return objects.size();
    }

    private void addParentSummaryMeasurements(PathObject parentObject, List<PathObject> objects) {
        if (objects.isEmpty()) {
            return;
        }

        double sumArea = 0;
        double sumMajor = 0;
        double sumMinor = 0;
        double sumEcc = 0;
        double sumOrient = 0;
        int n = 0;

        for (PathObject obj : objects) {
            var ml = obj.getMeasurementList();

            double area = ml.get("VeSpA: Area");
            if (!Double.isNaN(area)) {
                sumArea += area;
                sumMajor += ml.get("VeSpA: Major axis length");
                sumMinor += ml.get("VeSpA: Minor axis length");
                sumEcc += ml.get("VeSpA: Eccentricity");
                sumOrient += ml.get("VeSpA: Orientation");
                n++;
            }
        }

        if (n == 0) {
            return;
        }

        parentObject.getMeasurementList().put("VeSpA: Mean Area", sumArea / n);
        parentObject.getMeasurementList().put("VeSpA: Total Vessel Area", sumArea);
        parentObject.getMeasurementList().put("VeSpA: Mean Major axis length", sumMajor / n);
        parentObject.getMeasurementList().put("VeSpA: Mean Minor axis length", sumMinor / n);
        parentObject.getMeasurementList().put("VeSpA: Mean Eccentricity", sumEcc / n);
        parentObject.getMeasurementList().put("VeSpA: Mean Orientation", sumOrient / n);
    }

    private Map<Integer, VesselMeasurement> readMeasurementCsv(File measurementCsv) throws Exception {
        Map<Integer, VesselMeasurement> measurements = new LinkedHashMap<>();

        if (measurementCsv == null || !measurementCsv.exists()) {
            System.out.println("Measurement CSV not found: " + measurementCsv);
            return measurements;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(measurementCsv))) {
            String line = br.readLine();

            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 6) {
                    continue;
                }

                int vesselId = Integer.parseInt(parts[0].trim());
                double area = Double.parseDouble(parts[1].trim());
                double major = Double.parseDouble(parts[2].trim());
                double minor = Double.parseDouble(parts[3].trim());
                double eccentricity = Double.parseDouble(parts[4].trim());
                double orientation = Double.parseDouble(parts[5].trim());

                measurements.put(vesselId, new VesselMeasurement(
                        area,
                        major,
                        minor,
                        eccentricity,
                        orientation
                ));
            }
        }

        System.out.println("Measurements imported: " + measurements.size());
        return measurements;
    }

    private void showMessage(Alert.AlertType type, String title, String message) {
        Stage dialog = new Stage();
        dialog.setTitle(title);

        Label icon = new Label(type == Alert.AlertType.INFORMATION ? "i" : "!");
        icon.setMinSize(42, 42);
        icon.setPrefSize(42, 42);
        icon.setAlignment(Pos.CENTER);
        icon.setStyle("-fx-background-color: " + (type == Alert.AlertType.ERROR ? "#fff0f0" : "#dff4ee") + ";" +
                "-fx-text-fill: " + (type == Alert.AlertType.ERROR ? "#9f2d2d" : "#12664f") + ";" +
                "-fx-font-size: 24px;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 21;" +
                "-fx-border-color: " + (type == Alert.AlertType.ERROR ? "#e4b7b7" : "#b7ded3") + ";" +
                "-fx-border-radius: 21;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #17313b;");

        Label messageLabel = new Label(message);
        messageLabel.setWrapText(true);
        messageLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #334852;");

        Button okButton = new Button("OK");
        okButton.setStyle(primaryButtonStyle());
        okButton.setOnAction(e -> dialog.close());

        HBox body = new HBox(16, icon, new VBox(8, titleLabel, messageLabel));
        body.setAlignment(Pos.CENTER_LEFT);

        HBox footer = new HBox(okButton);
        footer.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(18, body, footer);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color: #eef4f7;");

        dialog.setScene(new Scene(root, 440, 180));
        dialog.setResizable(false);
        dialog.show();
    }

    private void showExpandableMessage(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);

        TextArea area = new TextArea(message);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefWidth(700);
        area.setPrefHeight(350);

        alert.getDialogPane().setContent(area);
        alert.setResizable(true);
        alert.show();
    }

    @Override
    public String getName() {
        return "Vessel Segmentation";
    }

    @Override
    public String getDescription() {
        return "Runs vessel segmentation with bundled Python script and saved per-user Python configuration.";
    }
}
