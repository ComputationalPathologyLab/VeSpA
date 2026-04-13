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
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
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
    private static final String PREF_OUTPUT_DIR = "outputDir";

    private static final String PYTHON_SCRIPT_RESOURCE = "/scripts/vessels_segmentation.py";

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
        stage.setTitle("Vessel Segmentation");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        Label outputLabel = new Label("Output folder:");
        TextField outputField = new TextField(PREFS.get(PREF_OUTPUT_DIR, ""));
        outputField.setPrefWidth(260);

        Button browseButton = new Button("Browse");
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Output Folder");
            File dir = chooser.showDialog(stage);
            if (dir != null) {
                outputField.setText(dir.getAbsolutePath());
            }
        });

        Label inputModeLabel = new Label("Input region:");
        ToggleGroup inputModeGroup = new ToggleGroup();

        RadioButton wholeImageButton = new RadioButton("Whole image");
        wholeImageButton.setToggleGroup(inputModeGroup);
        wholeImageButton.setSelected(true);

        RadioButton selectedAnnotationButton = new RadioButton("Selected annotation(s)");
        selectedAnnotationButton.setToggleGroup(inputModeGroup);

        Label widthLabel = new Label("Kernel width:");
        TextField widthField = new TextField("21");

        Label heightLabel = new Label("Kernel height:");
        TextField heightField = new TextField("21");

        Label shapeLabel = new Label("Kernel shape:");
        ComboBox<String> shapeBox = new ComboBox<>(
                FXCollections.observableArrayList("ELLIPSE", "RECT", "CROSS")
        );
        shapeBox.setValue("ELLIPSE");

        Button runButton = new Button("Run");

        runButton.setOnAction(e -> {
            String outputPath = outputField.getText().trim();

            int kernelWidth;
            int kernelHeight;

            try {
                kernelWidth = Integer.parseInt(widthField.getText().trim());
                kernelHeight = Integer.parseInt(heightField.getText().trim());
            } catch (NumberFormatException ex) {
                showMessage(Alert.AlertType.ERROR, "Input Error", "Kernel width and height must be integers.");
                return;
            }

            if (kernelWidth <= 0 || kernelHeight <= 0) {
                showMessage(Alert.AlertType.ERROR, "Input Error", "Kernel width and height must be greater than 0.");
                return;
            }

            if (outputPath.isBlank()) {
                showMessage(Alert.AlertType.ERROR, "Input Error", "Please choose an output folder.");
                return;
            }

            PREFS.put(PREF_OUTPUT_DIR, outputPath);

            String kernelShape = shapeBox.getValue();
            boolean useSelectedAnnotations = selectedAnnotationButton.isSelected();

            runSegmentation(qupath, outputPath, kernelWidth, kernelHeight, kernelShape, useSelectedAnnotations);
        });

        grid.add(outputLabel, 0, 0);
        grid.add(outputField, 1, 0);
        grid.add(browseButton, 2, 0);

        grid.add(inputModeLabel, 0, 1);
        grid.add(wholeImageButton, 1, 1);
        grid.add(selectedAnnotationButton, 1, 2);

        grid.add(widthLabel, 0, 3);
        grid.add(widthField, 1, 3);

        grid.add(heightLabel, 0, 4);
        grid.add(heightField, 1, 4);

        grid.add(shapeLabel, 0, 5);
        grid.add(shapeBox, 1, 5);

        grid.add(runButton, 1, 6);

        Scene scene = new Scene(grid, 520, 300);
        stage.setScene(scene);
        stage.show();
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
                                 String outputPath,
                                 int kernelWidth,
                                 int kernelHeight,
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

            File outputDir = new File(outputPath);
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                showMessage(Alert.AlertType.ERROR, "Output Error", "Could not create output folder:\n" + outputPath);
                return;
            }

            ImageServer<BufferedImage> server = qupath.getImageData().getServer();
            File tempInputDir = Files.createTempDirectory("qupath_vessel_input").toFile();

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
                    outputPath,
                    "--dilation-kernel-width", String.valueOf(kernelWidth),
                    "--dilation-kernel-height", String.valueOf(kernelHeight),
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
                File contourCsv = new File(new File(outputPath, task.baseName), "vessel_contours.csv");

                if (!contourCsv.exists()) {
                    System.out.println("Skipping missing contour CSV: " + contourCsv.getAbsolutePath());
                    continue;
                }

                totalAdded += importObjectsFromCsv(
                        qupath,
                        contourCsv,
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
                    "Segmentation completed successfully.\n\nObjects added to QuPath: " + totalAdded +
                            "\nResults exported to:\n" + outputPath
            );

        } catch (Exception ex) {
            ex.printStackTrace();
            showMessage(Alert.AlertType.ERROR, "Execution Error", ex.getMessage());
        }
    }

    private int importObjectsFromCsv(QuPathGUI qupath,
                                     File contourCsv,
                                     double xOffset,
                                     double yOffset,
                                     ImagePlane plane,
                                     PathObject parentObject,
                                     ROI selectedROI,
                                     boolean useSelectedAnnotations) throws Exception {

        Map<Integer, List<Point2>> contourMap = new LinkedHashMap<>();

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

        for (List<Point2> points : contourMap.values()) {
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
            objects.add(obj);
        }

        var hierarchy = qupath.getImageData().getHierarchy();

        if (useSelectedAnnotations && parentObject != null && parentObject.isAnnotation()) {
            parentObject.addChildObjects(objects);
            hierarchy.fireHierarchyChangedEvent(parentObject);
        } else {
            hierarchy.addObjects(objects);
            hierarchy.fireHierarchyChangedEvent(this);
        }

        System.out.println("Objects imported from " + contourCsv.getName() + ": " + objects.size());
        return objects.size();
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