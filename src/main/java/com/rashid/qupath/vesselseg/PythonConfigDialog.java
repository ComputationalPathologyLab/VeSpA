package com.rashid.qupath.vesselseg;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

public class PythonConfigDialog {

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(VesselSegmentationExtension.class);

    private static final String PREF_PYTHON_EXEC = "pythonExec";

    // Compatible version ranges instead of exact pins
    private static final String OPENCV_SPEC = "opencv-python>=4.10,<5";
    private static final String NUMPY_SPEC = "numpy>=1.26,<3";
    private static final String SCIKIT_IMAGE_SPEC = "scikit-image>=0.24,<1";
    private static final String PANDAS_SPEC = "pandas>=2.2,<3";

    private final Stage stage;
    private final TextField pythonField;
    private final Label statusLabel;
    private final ProgressBar progressBar;
    private final TextArea logArea;

    private final Button browseButton;
    private final Button autoDetectButton;
    private final Button testButton;
    private final Button checkEnvButton;
    private final Button installButton;
    private final Button resetEnvButton;
    private final Button saveButton;
    private final Button cancelButton;

    private boolean saved = false;

    public PythonConfigDialog() {
        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Configure Python - VeSpA");

        Label titleLabel = new Label("Python Configuration");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label pythonLabel = new Label("Python executable:");
        pythonField = new TextField(PREFS.get(PREF_PYTHON_EXEC, ""));
        pythonField.setPrefWidth(430);

        browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> browsePython());

        autoDetectButton = new Button("Auto-detect...");
        autoDetectButton.setOnAction(e -> autoDetectPython());

        statusLabel = new Label();
        updateStatus("No Python executable configured.", "darkred");

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(720);
        progressBar.setVisible(false);

        String infoText =
                "Required Python packages (range mode build 2026):\n\n" +
                OPENCV_SPEC + "\n" +
                NUMPY_SPEC + "\n" +
                SCIKIT_IMAGE_SPEC + "\n" +
                PANDAS_SPEC + "\n\n" +
                "Install dependencies will automatically:\n" +
                "1. create or reuse a dedicated VeSpA virtual environment\n" +
                "2. upgrade pip inside that environment\n" +
                "3. install compatible package versions\n" +
                "4. validate the environment\n" +
                "5. switch VeSpA to use that environment\n";

        TextArea infoArea = new TextArea(infoText);
        infoArea.setEditable(false);
        infoArea.setWrapText(true);
        infoArea.setPrefRowCount(10);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(10);
        logArea.setPromptText("Logs will appear here...");

        testButton = new Button("Test");
        testButton.setOnAction(e -> runTestPython());

        checkEnvButton = new Button("Check environment");
        checkEnvButton.setOnAction(e -> runCheckEnvironment());

        installButton = new Button("Install dependencies");
        installButton.setOnAction(e -> runInstallDependencies());

        resetEnvButton = new Button("Reset environment");
        resetEnvButton.setOnAction(e -> runResetEnvironment());

        saveButton = new Button("Save");
        saveButton.setOnAction(e -> saveAndClose());

        cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> stage.close());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(pythonLabel, 0, 0);
        grid.add(pythonField, 0, 1, 3, 1);
        grid.add(browseButton, 3, 1);
        grid.add(autoDetectButton, 4, 1);

        HBox buttonRow1 = new HBox(10, testButton, checkEnvButton, installButton, resetEnvButton);
        HBox buttonRow2 = new HBox(10, saveButton, cancelButton);

        VBox root = new VBox(
                12,
                titleLabel,
                grid,
                statusLabel,
                progressBar,
                new Label("Environment setup"),
                infoArea,
                buttonRow1,
                new Label("Logs"),
                logArea,
                buttonRow2
        );
        root.setPadding(new Insets(15));

        stage.setScene(new Scene(root, 800, 650));
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
            updateStatus("Python executable selected.", "green");
        }
    }

    private void autoDetectPython() {
        String[] candidates = {
                "/usr/bin/python3",
                "/opt/homebrew/bin/python3",
                "/usr/local/bin/python3",
                System.getProperty("user.home") + "/miniconda3/bin/python",
                System.getProperty("user.home") + "/anaconda3/bin/python",
                System.getProperty("user.home") + "/.pyenv/shims/python3",
                "C:\\\\Users\\\\Administrator\\\\AppData\\\\Local\\\\Programs\\\\Python\\\\Python313\\\\python.exe",
                "C:\\\\Python313\\\\python.exe",
                "C:\\\\Python312\\\\python.exe",
                "C:\\\\Python311\\\\python.exe"
        };

        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                pythonField.setText(f.getAbsolutePath());
                updateStatus("Auto-detected Python executable.", "green");
                appendLog("Auto-detected Python: " + f.getAbsolutePath());
                return;
            }
        }

        showAlert(Alert.AlertType.WARNING, "Auto-detect", "No Python executable found in common locations.");
    }

    private void runTestPython() {
        String python = pythonField.getText().trim();
        if (!validatePythonPath(python, "Test Python")) {
            return;
        }

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                setBusy(true, "Testing Python...");
                updateProgress(0.25, 1.0);

                ProcessResult result = runCommand(List.of(
                        python,
                        "--version"
                ));

                updateProgress(1.0, 1.0);

                Platform.runLater(() -> {
                    appendLog(result.output);
                    if (result.exitCode == 0) {
                        updateStatus("Valid Python executable.", "green");
                    } else {
                        updateStatus("Python executable test failed.", "darkred");
                        showAlert(Alert.AlertType.ERROR, "Test Python", result.output);
                    }
                    setBusy(false, null);
                });
                return null;
            }
        };

        new Thread(task, "vespa-test-python").start();
    }

    private void runCheckEnvironment() {
        String python = pythonField.getText().trim();
        if (!validatePythonPath(python, "Check environment")) {
            return;
        }

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                setBusy(true, "Checking environment...");
                updateProgress(0.25, 1.0);

                String code =
                        "import sys\n" +
                        "print('Python executable:', sys.executable)\n" +
                        "mods = ['cv2','numpy','skimage','pandas']\n" +
                        "ok = True\n" +
                        "for m in mods:\n" +
                        "    try:\n" +
                        "        mod = __import__(m)\n" +
                        "        print(f'{m}: OK ({getattr(mod, \"__version__\", \"unknown\")})')\n" +
                        "    except Exception as e:\n" +
                        "        ok = False\n" +
                        "        print(f'{m}: MISSING ({e})')\n" +
                        "print('ENV_OK' if ok else 'ENV_INCOMPLETE')\n";

                ProcessResult result = runCommand(List.of(
                        python, "-c", code
                ));

                updateProgress(1.0, 1.0);

                Platform.runLater(() -> {
                    appendLog(result.output);
                    if (result.exitCode == 0 && result.output.contains("ENV_OK")) {
                        updateStatus("Environment is ready.", "green");
                    } else {
                        updateStatus("Environment is incomplete.", "darkorange");
                    }
                    setBusy(false, null);
                });
                return null;
            }
        };

        new Thread(task, "vespa-check-env").start();
    }

    private void runInstallDependencies() {
        String basePython = pythonField.getText().trim();
        if (!validatePythonPath(basePython, "Install dependencies")) {
            return;
        }

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                setBusy(true, "Preparing VeSpA environment...");
                appendLog("Starting VeSpA environment installation...");

                File venvDir = getDefaultVenvDir();
                String venvPython = getVenvPythonPath(venvDir);

                updateProgress(0.10, 1.0);

                if (!venvDir.exists()) {
                    appendLog("Creating virtual environment at: " + venvDir.getAbsolutePath());
                    ProcessResult createResult = runCommand(List.of(
                            basePython,
                            "-m",
                            "venv",
                            venvDir.getAbsolutePath()
                    ));
                    appendLog(createResult.output);

                    if (createResult.exitCode != 0) {
                        Platform.runLater(() -> {
                            updateStatus("Failed to create VeSpA environment.", "darkred");
                            showAlert(Alert.AlertType.ERROR, "Install dependencies",
                                    "Failed to create virtual environment:\n\n" + createResult.output);
                            setBusy(false, null);
                        });
                        return null;
                    }
                } else {
                    appendLog("Reusing existing VeSpA environment: " + venvDir.getAbsolutePath());
                }

                updateProgress(0.35, 1.0);

                appendLog("Upgrading pip...");
                ProcessResult pipUpgrade = runCommand(List.of(
                        venvPython,
                        "-m",
                        "pip",
                        "install",
                        "--upgrade",
                        "pip"
                ));
                appendLog(pipUpgrade.output);

                if (pipUpgrade.exitCode != 0) {
                    Platform.runLater(() -> {
                        updateStatus("Failed to upgrade pip.", "darkred");
                        showAlert(Alert.AlertType.ERROR, "Install dependencies",
                                "Failed to upgrade pip:\n\n" + pipUpgrade.output);
                        setBusy(false, null);
                    });
                    return null;
                }

                updateProgress(0.60, 1.0);

                appendLog("Installing compatible dependency versions... [VeSpA build 2026-range-mode]");
                List<String> installCmd = new ArrayList<>();
                installCmd.add(venvPython);
                installCmd.add("-m");
                installCmd.add("pip");
                installCmd.add("install");
                installCmd.add(OPENCV_SPEC);
                installCmd.add(NUMPY_SPEC);
                installCmd.add(SCIKIT_IMAGE_SPEC);
                installCmd.add(PANDAS_SPEC);

                ProcessResult installResult = runCommand(installCmd);
                appendLog(installResult.output);

                if (installResult.exitCode != 0) {
                    Platform.runLater(() -> {
                        updateStatus("Failed to install dependencies.", "darkred");
                        showAlert(Alert.AlertType.ERROR, "Install dependencies",
                                "Failed to install required packages:\n\n" + installResult.output);
                        setBusy(false, null);
                    });
                    return null;
                }

                updateProgress(0.85, 1.0);

                appendLog("Verifying environment...");
                String verifyCode =
                        "import cv2, numpy, skimage, pandas\n" +
                        "print('cv2', cv2.__version__)\n" +
                        "print('numpy', numpy.__version__)\n" +
                        "print('skimage', skimage.__version__)\n" +
                        "print('pandas', pandas.__version__)\n" +
                        "print('ENV_OK')\n";

                ProcessResult verifyResult = runCommand(List.of(
                        venvPython, "-c", verifyCode
                ));
                appendLog(verifyResult.output);

                updateProgress(1.0, 1.0);

                Platform.runLater(() -> {
                    if (verifyResult.exitCode == 0 && verifyResult.output.contains("ENV_OK")) {
                        pythonField.setText(venvPython);
                        PREFS.put(PREF_PYTHON_EXEC, venvPython);
                        updateStatus("VeSpA environment created and ready.", "green");
                        showAlert(Alert.AlertType.INFORMATION, "Install dependencies",
                                "VeSpA environment is ready.\n\nPython now points to:\n" + venvPython);
                    } else {
                        updateStatus("Installation finished, but verification failed.", "darkorange");
                        showAlert(Alert.AlertType.WARNING, "Install dependencies",
                                "Dependencies were installed, but environment verification failed.\n\n" + verifyResult.output);
                    }
                    setBusy(false, null);
                });

                return null;
            }
        };

        new Thread(task, "vespa-install-deps").start();
    }

    private void runResetEnvironment() {
        File venvDir = getDefaultVenvDir();

        if (!venvDir.exists()) {
            showAlert(Alert.AlertType.INFORMATION, "Reset environment", "No VeSpA environment was found.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Reset environment");
        confirm.setHeaderText("Delete VeSpA environment?");
        confirm.setContentText("This will remove:\n" + venvDir.getAbsolutePath());

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        setBusy(true, "Removing VeSpA environment...");
                        updateProgress(0.25, 1.0);

                        deleteRecursively(venvDir);

                        updateProgress(1.0, 1.0);

                        Platform.runLater(() -> {
                            appendLog("Removed environment: " + venvDir.getAbsolutePath());

                            String current = pythonField.getText().trim();
                            String venvPython = getVenvPythonPath(venvDir);
                            if (current.equals(venvPython)) {
                                pythonField.clear();
                                PREFS.remove(PREF_PYTHON_EXEC);
                            }

                            updateStatus("VeSpA environment removed.", "darkorange");
                            setBusy(false, null);
                        });

                        return null;
                    }
                };

                new Thread(task, "vespa-reset-env").start();
            }
        });
    }

    private boolean validatePythonPath(String python, String title) {
        if (python.isBlank()) {
            showAlert(Alert.AlertType.ERROR, title, "Please select a Python executable.");
            return false;
        }

        File f = new File(python);
        if (!f.exists()) {
            showAlert(Alert.AlertType.ERROR, title, "Python executable path does not exist.");
            return false;
        }
        return true;
    }

    private File getDefaultVenvDir() {
        return new File(System.getProperty("user.home"), ".vespa-env");
    }

    private String getVenvPythonPath(File venvDir) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            return new File(venvDir, "Scripts/python.exe").getAbsolutePath();
        } else {
            return new File(venvDir, "bin/python").getAbsolutePath();
        }
    }

    private ProcessResult runCommand(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a + b + "\n");
        }

        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output);
    }

    private void setBusy(boolean busy, String message) {
        Platform.runLater(() -> {
            progressBar.setVisible(busy);
            if (!busy) {
                progressBar.setProgress(0);
            }
            browseButton.setDisable(busy);
            autoDetectButton.setDisable(busy);
            testButton.setDisable(busy);
            checkEnvButton.setDisable(busy);
            installButton.setDisable(busy);
            resetEnvButton.setDisable(busy);
            saveButton.setDisable(busy);
            cancelButton.setDisable(busy);
            if (busy && message != null) {
                updateStatus(message, "darkorange");
            }
        });
    }

    private void updateStatus(String text, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setStyle("-fx-text-fill: " + color + ";");
        });
    }

    private void appendLog(String text) {
        Platform.runLater(() -> {
            logArea.appendText(text);
            if (!text.endsWith("\n")) {
                logArea.appendText("\n");
            }
        });
    }

    private void deleteRecursively(File file) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            throw new RuntimeException("Failed to delete: " + file.getAbsolutePath());
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

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.setResizable(true);
        alert.showAndWait();
    }

    private static class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}