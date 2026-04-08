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
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;
// 

public class VesselSegmentationExtension implements QuPathExtension {

    private static final String PYTHON_EXEC_ENV = "QUPATH_VESSEL_PYTHON";
    // Script is bundled at the root of the JAR (build.gradle uses scripts/ as resource srcDir)
    private static final String PYTHON_SCRIPT_RESOURCE = "/vessels_segmentation.py";
    private static final String PREF_PYTHON_PATH = "pythonExecutablePath";

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

    private String getSavedPythonPath() {
        return Preferences.userNodeForPackage(VesselSegmentationExtension.class)
                .get(PREF_PYTHON_PATH, "");
    }

    private void savePythonPath(String path) {
        Preferences.userNodeForPackage(VesselSegmentationExtension.class)
                .put(PREF_PYTHON_PATH, path);
    }

    private String resolvePythonExecutable() throws Exception {
        // 1. Saved user preference (set via Configure Python dialog)
        String saved = getSavedPythonPath();
        if (!saved.isBlank()) {
            File f = new File(saved);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }

        // 2. Environment variable override
        String envPath = System.getenv(PYTHON_EXEC_ENV);
        if (envPath != null && !envPath.isBlank()) {
            File f = new File(envPath);
            if (f.exists() && f.canExecute()) {
                savePythonPath(f.getAbsolutePath());
                return f.getAbsolutePath();
            }
            throw new IllegalStateException(
                    "Environment variable " + PYTHON_EXEC_ENV + " is set but not executable: " + envPath);
        }

        // 3. Auto-detect from PATH and common install locations
        String detected = autoDetectPython();
        if (detected != null) {
            savePythonPath(detected);
            return detected;
        }

        throw new IllegalStateException(
                "No Python executable found.\n\n" +
                "Please install Python with the required packages, then use:\n" +
                "Extensions > Vessel Segmentation > Configure Python...");
    }

    private String autoDetectPython() {
        // PATH search first
        for (String name : new String[]{"python3", "python"}) {
            String found = findExecutable(name);
            if (found != null) return found;
        }

        // Common install locations (macOS / Linux / Windows)
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = os.contains("win");

        String[] candidates;
        if (isWindows) {
            candidates = new String[]{
                home + "\\miniconda3\\python.exe",
                home + "\\anaconda3\\python.exe",
                home + "\\AppData\\Local\\Programs\\Python\\Python311\\python.exe",
                home + "\\AppData\\Local\\Programs\\Python\\Python312\\python.exe",
                "C:\\Python311\\python.exe",
                "C:\\Python312\\python.exe",
            };
        } else {
            candidates = new String[]{
                "/opt/homebrew/bin/python3",
                "/usr/local/bin/python3",
                home + "/miniconda3/bin/python3",
                home + "/miniforge3/bin/python3",
                home + "/opt/anaconda3/bin/python3",
                home + "/anaconda3/bin/python3",
                "/usr/bin/python3",
            };
        }

        for (String path : candidates) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private boolean testPythonExecutable(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int code = p.waitFor();
            return code == 0 && output.toLowerCase().startsWith("python");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks that all required Python packages are importable.
     * Returns null if all packages are present, or an error message listing what is missing.
     */
    private String checkRequiredPackages(String pythonExec) {
        String checkScript =
            "import sys\n" +
            "missing = []\n" +
            "for mod, pkg in [('cv2','opencv-python'),('numpy','numpy'),('skimage','scikit-image'),('pandas','pandas')]:\n" +
            "    try: __import__(mod)\n" +
            "    except ImportError: missing.append(pkg)\n" +
            "if missing:\n" +
            "    print('MISSING:' + ','.join(missing))\n" +
            "    sys.exit(1)\n";
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExec, "-c", checkScript);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (output.startsWith("MISSING:")) {
                String missing = output.substring("MISSING:".length());
                return "Missing Python packages: " + missing + "\n\n" +
                       "Install them by running:\n" +
                       "  pip install " + missing.replace(",", " ") + "\n\n" +
                       "Then use Extensions > Vessel Segmentation > Configure Python...\n" +
                       "to point the plugin at a Python that has these packages.";
            }
            return null;
        } catch (Exception e) {
            return "Could not check Python packages: " + e.getMessage();
        }
    }

    private void showConfigureDialog(QuPathGUI qupath) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Configure Python — VeSpA");
        dialog.setResizable(false);

        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setPrefWidth(560);

        // Title
        Label title = new Label("Python Configuration");
        title.setFont(Font.font(null, FontWeight.BOLD, 14));

        // Python path row
        Label pathLabel = new Label("Python executable:");
        TextField pathField = new TextField(getSavedPythonPath());
        pathField.setPrefWidth(360);

        Button browseBtn = new Button("Browse...");
        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Python Executable");
            if (!pathField.getText().isBlank()) {
                File existing = new File(pathField.getText()).getParentFile();
                if (existing != null && existing.exists()) fc.setInitialDirectory(existing);
            }
            File chosen = fc.showOpenDialog(dialog);
            if (chosen != null) pathField.setText(chosen.getAbsolutePath());
        });

        Button autoBtn = new Button("Auto-detect");

        HBox pathRow = new HBox(8, pathField, browseBtn, autoBtn);

        // Status label
        Label statusLabel = new Label();

        Runnable updateStatus = () -> {
            String p = pathField.getText().trim();
            if (p.isBlank()) {
                statusLabel.setText("No path entered.");
                statusLabel.setTextFill(Color.GRAY);
            } else if (testPythonExecutable(p)) {
                statusLabel.setText("✓ Valid Python executable.");
                statusLabel.setTextFill(Color.GREEN);
            } else {
                statusLabel.setText("✗ Not a valid Python executable.");
                statusLabel.setTextFill(Color.RED);
            }
        };

        autoBtn.setOnAction(e -> {
            String detected = autoDetectPython();
            if (detected != null) {
                pathField.setText(detected);
            } else {
                pathField.setText("");
                statusLabel.setText("✗ Could not auto-detect Python. Please browse or enter the path manually.");
                statusLabel.setTextFill(Color.RED);
            }
            updateStatus.run();
        });

        // Required packages info
        Label packagesTitle = new Label("Required Python packages:");
        packagesTitle.setFont(Font.font(null, FontWeight.BOLD, 12));

        TextArea packagesArea = new TextArea(
                "opencv-python   numpy   scikit-image   pandas\n\n" +
                "Install command:\n" +
                "  pip install opencv-python numpy scikit-image pandas\n\n" +
                "If using conda:\n" +
                "  conda install -c conda-forge opencv numpy scikit-image pandas"
        );
        packagesArea.setEditable(false);
        packagesArea.setPrefHeight(110);
        packagesArea.setWrapText(true);
        packagesArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");

        // Buttons
        Button testBtn = new Button("Test");
        testBtn.setOnAction(e -> updateStatus.run());

        Button saveBtn = new Button("Save");
        saveBtn.setDefaultButton(true);
        saveBtn.setOnAction(e -> {
            String p = pathField.getText().trim();
            if (p.isBlank()) {
                showMessage(Alert.AlertType.ERROR, "Invalid Path", "Please enter a Python executable path.");
                return;
            }
            savePythonPath(p);
            dialog.close();
            showMessage(Alert.AlertType.INFORMATION, "Saved",
                    "Python path saved.\nYou can now run Vessel Segmentation.");
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttonRow = new HBox(8, testBtn, saveBtn, cancelBtn);

        root.getChildren().addAll(
                title,
                pathLabel, pathRow, statusLabel,
                packagesTitle, packagesArea,
                buttonRow
        );

        // Show status for currently saved path on open
        updateStatus.run();

        dialog.setScene(new Scene(root));
        dialog.showAndWait();
    }

    private String findExecutable(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }

        String[] paths = pathEnv.split(File.pathSeparator);
        String[] candidates = {name, name + ".exe", name + ".cmd", name + ".bat"};

        for (String dir : paths) {
            for (String candidate : candidates) {
                File file = new File(dir, candidate);
                if (file.exists() && file.canExecute()) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private File extractPythonScript() throws Exception {
        var resourceStream = VesselSegmentationExtension.class.getResourceAsStream(PYTHON_SCRIPT_RESOURCE);
        if (resourceStream == null) {
            throw new IllegalStateException("Could not locate Python script resource: " + PYTHON_SCRIPT_RESOURCE);
        }

        File tempScript = Files.createTempFile("vespa_vessel_script", ".py").toFile();
        tempScript.deleteOnExit();

        try (var in = resourceStream; var out = Files.newOutputStream(tempScript.toPath())) {
            in.transferTo(out);
        }

        if (!tempScript.setExecutable(true)) {
            throw new IllegalStateException("Unable to make extracted Python script executable: " + tempScript.getAbsolutePath());
        }

        return tempScript;
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions > Vessel Segmentation", true);

        MenuItem runItem = new MenuItem("Run Vessel Segmentation");
        runItem.setOnAction(e -> openWindow(qupath));

        MenuItem configItem = new MenuItem("Configure Python...");
        configItem.setOnAction(e -> showConfigureDialog(qupath));

        menu.getItems().addAll(runItem, configItem);
    }

    private void openWindow(QuPathGUI qupath) {
        Stage stage = new Stage();
        stage.setTitle("Vessel Segmentation");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        Label outputLabel = new Label("Output folder:");
        TextField outputField = new TextField();
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
                showMessage(Alert.AlertType.ERROR, "Input Error", "Kernel width and height must be > 0.");
                return;
            }

            if (outputPath.isBlank()) {
                showMessage(Alert.AlertType.ERROR, "Input Error", "Please choose an output folder.");
                return;
            }

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

    private void runSegmentation(QuPathGUI qupath,
                                 String outputPath,
                                 int kernelWidth,
                                 int kernelHeight,
                                 String kernelShape,
                                 boolean useSelectedAnnotations) {

        try {
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

                if (selectedObjects.isEmpty()) {
                    PathObject fallback = qupath.getViewer() == null ? null : qupath.getViewer().getSelectedObject();
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

                showMessage(
                        Alert.AlertType.INFORMATION,
                        "Selected annotations detected",
                        "Found " + validAnnotations.size() + " selected annotation(s) for processing."
                );

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

            String pythonExec = resolvePythonExecutable();

            String packageError = checkRequiredPackages(pythonExec);
            if (packageError != null) {
                showExpandableMessage(Alert.AlertType.ERROR, "Missing Python Packages", packageError);
                return;
            }

            File pythonScriptFile = extractPythonScript();
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExec,
                    pythonScriptFile.getAbsolutePath(),
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
                if (parts.length < 4)
                    continue;

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
            if (points.size() < 3)
                continue;

            var roi = ROIs.createPolygonROI(points, plane);

            if (useSelectedAnnotations && selectedROI != null) {
                double cx = roi.getCentroidX();
                double cy = roi.getCentroidY();
                if (!selectedROI.contains(cx, cy))
                    continue;
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
        return "Runs vessel segmentation on whole image or all selected annotations.";
    }
}