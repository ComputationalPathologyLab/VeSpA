package com.rashid.qupath.vesselseg;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.util.prefs.Preferences;

public class PythonConfigDialog {

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(VesselSegmentationExtension.class);

    private static final String PREF_PYTHON_EXEC = "pythonExec";

    private final Stage stage;
    private final TextField pythonField;
    private boolean saved = false;

    public PythonConfigDialog() {
        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Configure Python - VeSpA");

        Label titleLabel = new Label("Python Configuration");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label pythonLabel = new Label("Python executable:");
        pythonField = new TextField(PREFS.get(PREF_PYTHON_EXEC, ""));
        pythonField.setPrefWidth(420);

        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> browsePython());

        Button autoDetectButton = new Button("Auto-detect...");
        autoDetectButton.setOnAction(e -> autoDetectPython());

        Label statusLabel = new Label();
        updateStatus(statusLabel);

        TextArea infoArea = new TextArea(
                "Required Python packages:\n\n" +
                "opencv-python   numpy   scikit-image   pandas\n\n" +
                "Install command:\n" +
                "python -m pip install opencv-python numpy scikit-image pandas\n\n" +
                "If using conda:\n" +
                "conda install -c conda-forge opencv numpy scikit-image pandas"
        );
        infoArea.setEditable(false);
        infoArea.setWrapText(true);
        infoArea.setPrefRowCount(8);

        Button testButton = new Button("Test");
        testButton.setOnAction(e -> testPython(statusLabel));

        Button installButton = new Button("Install dependencies");
        installButton.setOnAction(e -> installDependencies(statusLabel));

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> saveAndClose());

        Button cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> stage.close());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(pythonLabel, 0, 0);
        grid.add(pythonField, 0, 1, 3, 1);
        grid.add(browseButton, 3, 1);
        grid.add(autoDetectButton, 4, 1);

        ToolBar buttonBar = new ToolBar(testButton, installButton, saveButton, cancelButton);

        VBox root = new VBox(12, titleLabel, grid, statusLabel, new Label("Required Python packages:"), infoArea, buttonBar);
        root.setPadding(new Insets(15));

        stage.setScene(new Scene(root, 760, 420));
    }

    public boolean showDialog() {
        stage.showAndWait();
        return saved;
    }

    public String getPythonExecutable() {
        return pythonField.getText().trim();
    }

    private void browsePython() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Python Executable");
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            pythonField.setText(file.getAbsolutePath());
        }
    }

    private void autoDetectPython() {
        String[] candidates = {
                "/usr/bin/python3",
                "/opt/homebrew/bin/python3",
                "/usr/local/bin/python3",
                System.getProperty("user.home") + "/miniconda3/bin/python",
                System.getProperty("user.home") + "/anaconda3/bin/python"
        };

        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                pythonField.setText(f.getAbsolutePath());
                return;
            }
        }

        showAlert(Alert.AlertType.WARNING, "Auto-detect", "No Python executable found in common locations.");
    }

    private void testPython(Label statusLabel) {
        String python = pythonField.getText().trim();

        if (python.isBlank()) {
            showAlert(Alert.AlertType.ERROR, "Test Python", "Please select a Python executable.");
            return;
        }

        File f = new File(python);
        if (!f.exists()) {
            showAlert(Alert.AlertType.ERROR, "Test Python", "Python executable path does not exist.");
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    python,
                    "-c",
                    "import cv2, numpy, skimage, pandas; print('OK')"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().reduce("", (a, b) -> a + b + "\n");
            }

            int code = process.waitFor();

            if (code == 0 && output.contains("OK")) {
                statusLabel.setText("✔ Valid Python executable and required packages found.");
                statusLabel.setStyle("-fx-text-fill: green;");
            } else {
                statusLabel.setText("⚠ Python found, but one or more required packages are missing.");
                statusLabel.setStyle("-fx-text-fill: darkorange;");
            }

        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Test Python", ex.getMessage());
        }
    }

    private void installDependencies(Label statusLabel) {
        String python = pythonField.getText().trim();

        if (python.isBlank()) {
            showAlert(Alert.AlertType.ERROR, "Install dependencies", "Please select a Python executable first.");
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    python,
                    "-m",
                    "pip",
                    "install",
                    "opencv-python",
                    "numpy",
                    "scikit-image",
                    "pandas"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().reduce("", (a, b) -> a + b + "\n");
            }

            int code = process.waitFor();

            if (code == 0) {
                statusLabel.setText("✔ Dependencies installed successfully.");
                statusLabel.setStyle("-fx-text-fill: green;");
            } else {
                showAlert(Alert.AlertType.ERROR, "Install dependencies", output);
            }

        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Install dependencies", ex.getMessage());
        }
    }

    private void saveAndClose() {
        String python = pythonField.getText().trim();

        if (python.isBlank()) {
            showAlert(Alert.AlertType.ERROR, "Save", "Please select a Python executable.");
            return;
        }

        File f = new File(python);
        if (!f.exists()) {
            showAlert(Alert.AlertType.ERROR, "Save", "Python executable path does not exist.");
            return;
        }

        PREFS.put(PREF_PYTHON_EXEC, python);
        saved = true;
        stage.close();
    }

    private void updateStatus(Label statusLabel) {
        String python = pythonField.getText().trim();
        if (!python.isBlank() && new File(python).exists()) {
            statusLabel.setText("✔ Python executable detected.");
            statusLabel.setStyle("-fx-text-fill: green;");
        } else {
            statusLabel.setText("No Python executable configured.");
            statusLabel.setStyle("-fx-text-fill: darkred;");
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.setResizable(true);
        alert.showAndWait();
    }
}