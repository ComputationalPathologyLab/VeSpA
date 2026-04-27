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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
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

    private static final int DEFAULT_DILATION_WIDTH = 21;
    private static final int DEFAULT_DILATION_HEIGHT = 21;
    private static final int DEFAULT_EROSION_WIDTH = 3;
    private static final int DEFAULT_EROSION_HEIGHT = 3;
    private static final String DEFAULT_KERNEL_SHAPE = "ELLIPSE";

    private static final double WINDOW_WIDTH = 560;
    private static final double COLLAPSED_HEIGHT = 250;
    private static final double EXPANDED_HEIGHT = 390;

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

        Label inputModeLabel = new Label("Input region:");
        ToggleGroup inputModeGroup = new ToggleGroup();

        RadioButton wholeImageButton = new RadioButton("Whole image");
        wholeImageButton.setToggleGroup(inputModeGroup);
        wholeImageButton.setSelected(true);

        RadioButton selectedAnnotationButton = new RadioButton("Selected annotation(s)");
        selectedAnnotationButton.setToggleGroup(inputModeGroup);

        TextField dilationWidthField = new TextField(String.valueOf(DEFAULT_DILATION_WIDTH));
        TextField dilationHeightField = new TextField(String.valueOf(DEFAULT_DILATION_HEIGHT));
        TextField erosionWidthField = new TextField(String.valueOf(DEFAULT_EROSION_WIDTH));
        TextField erosionHeightField = new TextField(String.valueOf(DEFAULT_EROSION_HEIGHT));

        dilationWidthField.setPrefWidth(150);
        dilationHeightField.setPrefWidth(150);
        erosionWidthField.setPrefWidth(150);
        erosionHeightField.setPrefWidth(150);

        ComboBox<String> shapeBox = new ComboBox<>(
                FXCollections.observableArrayList("ELLIPSE", "RECT", "CROSS")
        );
        shapeBox.setValue(DEFAULT_KERNEL_SHAPE);
        shapeBox.setPrefWidth(150);

        GridPane optionsGrid = new GridPane();
        optionsGrid.setHgap(10);
        optionsGrid.setVgap(10);
        optionsGrid.setPadding(new Insets(10, 10, 10, 10));

        optionsGrid.add(new Label("Kernel width:"), 0, 0);
        optionsGrid.add(dilationWidthField, 1, 0);

        optionsGrid.add(new Label("Kernel height:"), 0, 1);
        optionsGrid.add(dilationHeightField, 1, 1);

        optionsGrid.add(new Label("Erosion width:"), 0, 2);
        optionsGrid.add(erosionWidthField, 1, 2);

        optionsGrid.add(new Label("Erosion height:"), 0, 3);
        optionsGrid.add(erosionHeightField, 1, 3);

        optionsGrid.add(new Label("Kernel shape:"), 0, 4);
        optionsGrid.add(shapeBox, 1, 4);

        TitledPane additionalOptionsPane = new TitledPane("Additional options", optionsGrid);
        additionalOptionsPane.setExpanded(false);
        additionalOptionsPane.setAnimated(false);

        Button defaultButton = new Button("Default");
        Button resetButton = new Button("Reset");
        Button runButton = new Button("Run");
        Button exitButton = new Button("Exit");

        defaultButton.visibleProperty().bind(additionalOptionsPane.expandedProperty());
        defaultButton.managedProperty().bind(additionalOptionsPane.expandedProperty());

        resetButton.visibleProperty().bind(additionalOptionsPane.expandedProperty());
        resetButton.managedProperty().bind(additionalOptionsPane.expandedProperty());

        defaultButton.setOnAction(e -> {
            dilationWidthField.setText(String.valueOf(DEFAULT_DILATION_WIDTH));
            dilationHeightField.setText(String.valueOf(DEFAULT_DILATION_HEIGHT));
            erosionWidthField.setText(String.valueOf(DEFAULT_EROSION_WIDTH));
            erosionHeightField.setText(String.valueOf(DEFAULT_EROSION_HEIGHT));
            shapeBox.setValue(DEFAULT_KERNEL_SHAPE);
        });

        resetButton.setOnAction(e -> {
            dilationWidthField.clear();
            dilationHeightField.clear();
            erosionWidthField.clear();
            erosionHeightField.clear();
            shapeBox.setValue(DEFAULT_KERNEL_SHAPE);
        });

        exitButton.setOnAction(e -> stage.close());

        runButton.setOnAction(e -> {
            int dilationWidth;
            int dilationHeight;
            int erosionWidth;
            int erosionHeight;

            try {
                dilationWidth = Integer.parseInt(dilationWidthField.getText().trim());
                dilationHeight = Integer.parseInt(dilationHeightField.getText().trim());
                erosionWidth = Integer.parseInt(erosionWidthField.getText().trim());
                erosionHeight = Integer.parseInt(erosionHeightField.getText().trim());
            } catch (NumberFormatException ex) {
                showMessage(Alert.AlertType.ERROR, "Input Error", "All width and height values must be integers.");
                return;
            }

            if (dilationWidth <= 0 || dilationHeight <= 0 || erosionWidth <= 0 || erosionHeight <= 0) {
                showMessage(Alert.AlertType.ERROR, "Input Error", "All width and height values must be greater than 0.");
                return;
            }

            String kernelShape = shapeBox.getValue();
            boolean useSelectedAnnotations = selectedAnnotationButton.isSelected();

            runSegmentation(
                    qupath,
                    dilationWidth,
                    dilationHeight,
                    erosionWidth,
                    erosionHeight,
                    kernelShape,
                    useSelectedAnnotations
            );
        });

        GridPane inputGrid = new GridPane();
        inputGrid.setHgap(10);
        inputGrid.setVgap(10);
        inputGrid.add(inputModeLabel, 0, 0);
        inputGrid.add(wholeImageButton, 1, 0);
        inputGrid.add(selectedAnnotationButton, 1, 1);

        VBox inputPanel = new VBox(8, inputGrid);
        inputPanel.setPadding(new Insets(14, 14, 8, 14));
        inputPanel.setStyle(
                "-fx-background-color: #d3d3d3;" +
                "-fx-border-color: #b0b0b0;" +
                "-fx-border-width: 1;"
        );

        VBox optionsPanel = new VBox(additionalOptionsPane);
        optionsPanel.setStyle(
                "-fx-background-color: #d3d3d3;" +
                "-fx-border-color: #b0b0b0;" +
                "-fx-border-width: 1;"
        );

        VBox rightPanel = new VBox(12, inputPanel, optionsPanel);
        rightPanel.setAlignment(Pos.TOP_LEFT);
        rightPanel.setFillWidth(true);

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
        root.setStyle("-fx-background-color: #e6e6e6;");

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
            if (expanded) {
                stage.setHeight(EXPANDED_HEIGHT);
            } else {
                stage.setHeight(COLLAPSED_HEIGHT);
            }
            stage.centerOnScreen();
        });

        stage.show();
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
                                 int dilationWidth,
                                 int dilationHeight,
                                 int erosionWidth,
                                 int erosionHeight,
                                 String kernelShape,
                                 boolean useSelectedAnnotations) {

        try {
            String pythonExec = ensurePythonConfigured();
            if (pythonExec == null) {
                return;
            }

            File extractedScript = extractBundledPythonScript();
            if (extractedScript == null || !extractedScript.exists()) {
                showMessage(Alert.AlertType.ERROR, "Script Error", "Could not extract bundled vessel segmentation script.");
                return;
            }

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

                    RegionRequest request = RegionRequest.createInstance(
                            server.getPath(),
                            1.0,
                            roi
                    );

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
                    "--dilation-kernel-width", String.valueOf(dilationWidth),
                    "--dilation-kernel-height", String.valueOf(dilationHeight),
                    "--erosion-kernel-width", String.valueOf(erosionWidth),
                    "--erosion-kernel-height", String.valueOf(erosionHeight),
                    "--dilation-kernel-shape", kernelShape
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
            hierarchy.fireHierarchyChangedEvent(parentObject);
        } else {
            hierarchy.addObjects(objects);
            hierarchy.fireHierarchyChangedEvent(this);
        }

        System.out.println("Objects imported from " + contourCsv.getName() + ": " + objects.size());
        return objects.size();
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