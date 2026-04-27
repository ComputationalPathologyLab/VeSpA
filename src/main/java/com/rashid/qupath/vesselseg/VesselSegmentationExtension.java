package com.rashid.qupath.vesselseg;

import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
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
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;

public class VesselSegmentationExtension implements QuPathExtension {

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

    private static final double WINDOW_WIDTH = 720;
    private static final double COLLAPSED_HEIGHT = 300;
    private static final double EXPANDED_HEIGHT = 650;


    private static class ExportTask {
        String baseName;
        double xOffset;
        double yOffset;
        ImagePlane plane;
        PathObject parentObject;
        ROI selectedROI;

        ExportTask(String baseName,
                   double xOffset,
                   double yOffset,
                   ImagePlane plane,
                   PathObject parentObject,
                   ROI selectedROI) {
            this.baseName = baseName;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.plane = plane;
            this.parentObject = parentObject;
            this.selectedROI = selectedROI;
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

    @Override
    public void installExtension(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions > Vessel Segmentation", true);

        MenuItem runItem = new MenuItem("Run Vessel Segmentation");
        runItem.setOnAction(e -> openWindow(qupath));

        MenuItem configItem = new MenuItem("Configure Python - VeSpA");
        configItem.setOnAction(e -> {
            PythonConfigDialog dialog = new PythonConfigDialog();
            dialog.showDialog();
        });

        menu.getItems().add(runItem);
        menu.getItems().add(configItem);
    }

    private void openWindow(QuPathGUI qupath) {
        Stage stage = new Stage();
        stage.setTitle("Vessel Spatial Analysis");

        ImageView logoView = createLogoView();
        ParameterFields fields = new ParameterFields();
        fields.percentile.disableProperty().bind(fields.thresholdMode.valueProperty().isNotEqualTo("percentile"));
        fields.percentile.setStyle("-fx-background-radius: 5; -fx-border-radius: 5;");

        Label inputModeLabel = new Label("Input region:");
        ToggleGroup inputModeGroup = new ToggleGroup();

        RadioButton selectedAnnotationButton = new RadioButton("Selected annotation(s)");
        selectedAnnotationButton.setToggleGroup(inputModeGroup);
        selectedAnnotationButton.setSelected(true);

        RadioButton wholeImageButton = new RadioButton("Whole image");
        wholeImageButton.setToggleGroup(inputModeGroup);

        GridPane inputGrid = new GridPane();
        inputGrid.setHgap(12);
        inputGrid.setVgap(10);
        inputGrid.add(inputModeLabel, 0, 0);
        inputGrid.add(selectedAnnotationButton, 1, 0);
        inputGrid.add(wholeImageButton, 1, 1);

        VBox inputPanel = new VBox(8, inputGrid);
        inputPanel.setPadding(new Insets(14));
        inputPanel.setStyle(cardStyle());

        VBox parametersBox = new VBox(10);
        parametersBox.setPadding(new Insets(10));
        parametersBox.getChildren().addAll(
                createSection("Thresholding",
                        row("Threshold mode", fields.thresholdMode, "Otsu is automatic; percentile uses the value below"),
                        row("Percentile value", fields.percentile, "used only when threshold mode is percentile, usually 10")
                ),
                createSection("Lumen detection",
                        row("Min area (px²)", fields.lumenAreaMin, "ignore tiny noise holes"),
                        row("Max area (px²)", fields.lumenAreaMax, "ignore artefactually large holes"),
                        row("Circularity min", fields.lumenCircularityMin, "low values allow elongated/irregular lumens"),
                        row("Eccentricity max", fields.lumenEccentricityMax, "reject near-linear artefacts")
                ),
                createSection("Wall repair before lumen detection",
                        row("Closing kernel size", fields.wallCloseKsize, "increase if vessel walls are fragmented"),
                        row("Closing iterations", fields.wallCloseIter, "increase to improve wall closing")
                ),
                createSection("Initial morphological cleanup",
                        row("Dilation width", fields.dilationWidth, "initial binary cleanup"),
                        row("Dilation height", fields.dilationHeight, "initial binary cleanup"),
                        row("Dilation iterations", fields.dilationIter, "number of dilation passes"),
                        row("Kernel shape", fields.kernelShape, "structuring element shape"),
                        row("Erosion width", fields.erosionWidth, "boundary restoration"),
                        row("Erosion height", fields.erosionHeight, "boundary restoration"),
                        row("Erosion iterations", fields.erosionIter, "number of erosion passes")
                ),
                createSection("Lumen expansion",
                        row("Expansion kernel size", fields.lumenExpandKsize, "merge lumen onto inner wall boundary"),
                        row("Expansion iterations", fields.lumenExpandIter, "increase to bridge larger inner-wall gaps")
                ),
                createSection("Vessel filtering",
                        row("Minimum vessel area (px²)", fields.vesselAreaMin, "remove small connected components")
                )
        );

        ScrollPane scrollPane = new ScrollPane(parametersBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(360);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        TitledPane additionalOptionsPane = new TitledPane("Additional options", scrollPane);
        additionalOptionsPane.setExpanded(false);
        additionalOptionsPane.setAnimated(false);

        VBox optionsPanel = new VBox(additionalOptionsPane);
        optionsPanel.setStyle(cardStyle());

        Button defaultButton = new Button("Default");
        Button resetButton = new Button("Reset");
        Button runButton = new Button("Run");
        Button exitButton = new Button("Exit");

        for (Button button : List.of(defaultButton, resetButton, runButton, exitButton)) {
            button.setMinWidth(64);
            button.setStyle("-fx-background-radius: 7; -fx-border-radius: 7; -fx-padding: 5 12 5 12;");
        }

        defaultButton.visibleProperty().bind(additionalOptionsPane.expandedProperty());
        defaultButton.managedProperty().bind(additionalOptionsPane.expandedProperty());
        resetButton.visibleProperty().bind(additionalOptionsPane.expandedProperty());
        resetButton.managedProperty().bind(additionalOptionsPane.expandedProperty());

        defaultButton.setOnAction(e -> fields.setDefaults());
        resetButton.setOnAction(e -> clearFields(fields));
        exitButton.setOnAction(e -> stage.close());

        runButton.setOnAction(e -> {
            RunParameters params;
            try {
                params = parseParameters(fields);
            } catch (Exception ex) {
                showMessage(Alert.AlertType.ERROR, "Input Error", ex.getMessage());
                return;
            }

            boolean useSelectedAnnotations = selectedAnnotationButton.isSelected();
            runSegmentation(qupath, params, useSelectedAnnotations);
        });

        VBox rightPanel = new VBox(12, inputPanel, optionsPanel);
        rightPanel.setAlignment(Pos.TOP_LEFT);
        rightPanel.setFillWidth(true);
        HBox.setHgrow(rightPanel, Priority.ALWAYS);

        VBox leftPanel = new VBox(10, logoView);
        leftPanel.setAlignment(Pos.TOP_CENTER);
        leftPanel.setPadding(new Insets(22, 4, 0, 4));
        leftPanel.setPrefWidth(135);
        leftPanel.setMinWidth(135);
        leftPanel.setMaxWidth(135);

        HBox topPanels = new HBox(14, leftPanel, rightPanel);
        topPanels.setAlignment(Pos.TOP_LEFT);

        Region spacerLeft = new Region();
        Region spacerRight = new Region();
        HBox.setHgrow(spacerLeft, Priority.ALWAYS);
        HBox.setHgrow(spacerRight, Priority.ALWAYS);

        HBox actionButtons = new HBox(10, runButton, exitButton);
        actionButtons.setAlignment(Pos.CENTER);

        HBox buttonBar = new HBox(10, defaultButton, resetButton, spacerLeft, actionButtons, spacerRight);
        buttonBar.setAlignment(Pos.CENTER);
        buttonBar.setPadding(new Insets(4, 0, 0, 0));

        VBox root = new VBox(12, topPanels, buttonBar);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-background-color: linear-gradient(to bottom, #f4f6f8, #e7ebef);");

        Scene scene = new Scene(root, WINDOW_WIDTH, COLLAPSED_HEIGHT);
        stage.setScene(scene);

        stage.setResizable(false);
        stage.setWidth(WINDOW_WIDTH);
        stage.setMinWidth(WINDOW_WIDTH);
        stage.setMaxWidth(WINDOW_WIDTH);

        stage.setHeight(COLLAPSED_HEIGHT);
        stage.setMinHeight(COLLAPSED_HEIGHT);
        stage.setMaxHeight(EXPANDED_HEIGHT);

        stage.setMaximized(false);
        stage.centerOnScreen();

        additionalOptionsPane.expandedProperty().addListener((obs, oldVal, expanded) -> {
            stage.setHeight(expanded ? EXPANDED_HEIGHT : COLLAPSED_HEIGHT);
            stage.centerOnScreen();
        });

        stage.show();
    }

    private String cardStyle() {
        return "-fx-background-color: #ffffff;" +
                "-fx-border-color: #cfd6dd;" +
                "-fx-border-width: 1;" +
                "-fx-background-radius: 10;" +
                "-fx-border-radius: 10;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 8, 0, 0, 2);";
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

        field.setPrefWidth(125);
        field.setStyle("-fx-background-radius: 6;" +
                "-fx-border-radius: 6;" +
                "-fx-border-color: #aeb7c2;" +
                "-fx-background-color: white;" +
                "-fx-padding: 4 6 4 6;");

        Label help = createHelpIcon(hint);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, l, field, help, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox row(String label, ComboBox<String> field, String hint) {
        Label l = new Label(label);
        l.setPrefWidth(185);
        l.setStyle("-fx-text-fill: #27313a; -fx-font-size: 11px;");

        field.setPrefWidth(125);
        field.setStyle("-fx-background-radius: 6;" +
                "-fx-border-radius: 6;" +
                "-fx-border-color: #aeb7c2;" +
                "-fx-background-color: white;" +
                "-fx-padding: 2 4 2 4;");

        Label help = createHelpIcon(hint);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, l, field, help, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label createHelpIcon(String tooltipText) {
        Label help = new Label("?");
        help.setMinSize(18, 18);
        help.setPrefSize(18, 18);
        help.setMaxSize(18, 18);
        help.setAlignment(Pos.CENTER);
        help.setStyle("-fx-background-color: #e8eef5;" +
                "-fx-text-fill: #42627a;" +
                "-fx-font-weight: bold;" +
                "-fx-font-size: 11px;" +
                "-fx-background-radius: 9;" +
                "-fx-border-color: #c4d0dc;" +
                "-fx-border-radius: 9;");

        Tooltip tooltip = new Tooltip(tooltipText);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(280);
        Tooltip.install(help, tooltip);
        return help;
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

    private void runSegmentation(QuPathGUI qupath, RunParameters params, boolean useSelectedAnnotations) {

        try {
            String pythonExec = ensurePythonConfigured();
            if (pythonExec == null) {
                return;
            }

            File extractedScript = extractBundledPythonScript();

            if (qupath.getImageData() == null) {
                showMessage(Alert.AlertType.ERROR, "No Image", "No image is currently open in QuPath.");
                return;
            }

            File outputDir = Files.createTempDirectory("qupath_vessel_output").toFile();
            outputDir.deleteOnExit();

            ImageServer<BufferedImage> server = qupath.getImageData().getServer();
            File tempInputDir = Files.createTempDirectory("qupath_vessel_input").toFile();
            tempInputDir.deleteOnExit();

            List<ExportTask> exportTasks = new ArrayList<>();

            if (useSelectedAnnotations) {
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
                    showMessage(Alert.AlertType.ERROR, "No Annotation Selected", "Please select one or more annotations first.");
                    return;
                }

                int index = 1;
                for (PathObject selectedObject : validAnnotations) {
                    ROI roi = selectedObject.getROI();

                    RegionRequest request = RegionRequest.createInstance(server.getPath(), 1.0, roi);
                    BufferedImage img = server.readRegion(request);

                    String baseName = "qupath_annotation_export_" + index;
                    File tempImage = new File(tempInputDir, baseName + ".png");
                    ImageIO.write(img, "PNG", tempImage);

                    exportTasks.add(new ExportTask(
                            baseName,
                            roi.getBoundsX(),
                            roi.getBoundsY(),
                            roi.getImagePlane(),
                            selectedObject,
                            roi
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
                File tempImage = new File(tempInputDir, baseName + ".png");
                ImageIO.write(img, "PNG", tempImage);

                exportTasks.add(new ExportTask(
                        baseName,
                        0,
                        0,
                        ImagePlane.getDefaultPlane(),
                        null,
                        null
                ));
            }

            ProcessBuilder pb = new ProcessBuilder(
                    pythonExec,
                    extractedScript.getAbsolutePath(),
                    tempInputDir.getAbsolutePath(),
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
            }

            int exitCode = process.waitFor();

            if (exitCode != 0 || !log.toString().contains("VESSEL_SEGMENTATION_SUCCESS")) {
                showExpandableMessage(
                        Alert.AlertType.ERROR,
                        "Segmentation Failed",
                        log.toString().isBlank() ? "Python process failed." : log.toString()
                );
                return;
            }

            int totalAdded = 0;

            for (ExportTask task : exportTasks) {
                File imageOutputDir = new File(outputDir, task.baseName);
                File contourCsv = new File(imageOutputDir, "vessel_contours.csv");
                File measurementCsv = new File(imageOutputDir, task.baseName + "_measurements.csv");

                if (!contourCsv.exists()) {
                    System.out.println("Skipping missing contour CSV: " + contourCsv.getAbsolutePath());
                    continue;
                }

                totalAdded += importObjectsFromCsv(
                        qupath,
                        contourCsv,
                        measurementCsv,
                        task.xOffset,
                        task.yOffset,
                        task.plane,
                        task.parentObject,
                        task.selectedROI,
                        useSelectedAnnotations
                );
            }

            qupath.getImageData().getHierarchy().fireHierarchyChangedEvent(this);

            if (qupath.getViewer() != null) {
                qupath.getViewer().repaintEntireImage();
            }

            showMessage(
                    Alert.AlertType.INFORMATION,
                    "Segmentation Finished",
                    "Segmentation completed successfully.\n\nObjects added to QuPath: " + totalAdded
            );

        } catch (Exception ex) {
            ex.printStackTrace();
            showMessage(Alert.AlertType.ERROR, "Execution Error", ex.getMessage());
        }
    }

    private int importObjectsFromCsv(QuPathGUI qupath,
                                     File contourCsv,
                                     File measurementCsv,
                                     double xOffset,
                                     double yOffset,
                                     ImagePlane plane,
                                     PathObject parentObject,
                                     ROI selectedROI,
                                     boolean useSelectedAnnotations) throws Exception {

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

            if (useSelectedAnnotations && selectedROI != null) {
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

        if (useSelectedAnnotations && parentObject != null && parentObject.isAnnotation()) {
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
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
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
        alert.showAndWait();
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
