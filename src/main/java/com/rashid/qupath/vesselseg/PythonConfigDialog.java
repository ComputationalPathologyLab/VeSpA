package com.rashid.qupath.vesselseg;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    private static final String ACCENT = "#0f7f7a";
    private static final String TEXT = "#17313b";
    private static final String MUTED = "#60717c";
    private static final String PANEL = "#ffffff";
    private static final String BACKGROUND = "#eef4f7";
    private static final String BORDER = "#d7e2e6";

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
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 22px; -fx-text-fill: " + TEXT + ";");

        Label subtitleLabel = new Label("Configure the Python environment used by VeSpA");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");

        Label pythonLabel = new Label("Python executable:");
        pythonField = new TextField(PREFS.get(PREF_PYTHON_EXEC, ""));
        pythonField.setPrefWidth(430);
        pythonField.setStyle(textFieldStyle());

        browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> browsePython());

        autoDetectButton = new Button("Auto-detect...");
        autoDetectButton.setOnAction(e -> autoDetectPython());

        statusLabel = new Label();
        String configuredPython = pythonField.getText().trim();
        if (!configuredPython.isBlank() && new File(configuredPython).exists()) {
            updateStatus("Valid Python executable.", "green");
        } else {
            updateStatus("No Python executable configured.", "darkred");
        }

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        String infoText =
                "Required Python packages:\n\n" +
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
        infoArea.setPrefRowCount(8);
        infoArea.setStyle(textAreaStyle());

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(8);
        logArea.setPromptText("Logs will appear here...");
        logArea.setStyle(textAreaStyle());

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

        for (Button button : List.of(browseButton, autoDetectButton, testButton, checkEnvButton, installButton,
                resetEnvButton, cancelButton)) {
            button.setStyle(secondaryButtonStyle());
        }
        saveButton.setStyle(primaryButtonStyle());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(pythonLabel, 0, 0);
        grid.add(pythonField, 0, 1, 3, 1);
        grid.add(browseButton, 3, 1);
        grid.add(autoDetectButton, 4, 1);

        HBox buttonRow1 = new HBox(10, testButton, checkEnvButton, installButton, resetEnvButton);
        HBox buttonRow2 = new HBox(10, saveButton, cancelButton);
        buttonRow2.setStyle("-fx-alignment: center-right;");

        VBox pythonPanel = card(
                new Label("Python executable"),
                grid,
                statusLabel,
                progressBar
        );

        VBox envPanel = card(
                new Label("Environment setup"),
                infoArea,
                buttonRow1
        );

        VBox logPanel = card(
                new Label("Logs"),
                logArea
        );

        VBox root = new VBox(
                12,
                new VBox(2, titleLabel, subtitleLabel),
                pythonPanel,
                envPanel,
                logPanel,
                buttonRow2
        );
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-color: " + BACKGROUND + ";");

        stage.setScene(new Scene(root, 820, 690));
        stage.setMinWidth(760);
        stage.setMinHeight(620);
    }

    private VBox card(Label title, Node... children) {
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: " + TEXT + ";");

        VBox box = new VBox(10);
        box.setPadding(new Insets(14));
        box.setStyle(cardStyle());
        box.getChildren().add(title);
        box.getChildren().addAll(children);
        return box;
    }

    private String cardStyle() {
        return "-fx-background-color: " + PANEL + ";" +
                "-fx-border-color: " + BORDER + ";" +
                "-fx-border-width: 1;" +
                "-fx-background-radius: 8;" +
                "-fx-border-radius: 8;" +
                "-fx-effect: dropshadow(gaussian, rgba(18,44,55,0.08), 8, 0, 0, 2);";
    }

    private String primaryButtonStyle() {
        return "-fx-background-color: " + ACCENT + ";" +
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

    private String textFieldStyle() {
        return "-fx-background-color: white;" +
                "-fx-border-color: #bdd0d5;" +
                "-fx-background-radius: 7;" +
                "-fx-border-radius: 7;" +
                "-fx-padding: 6 8 6 8;";
    }

    private String textAreaStyle() {
        return "-fx-control-inner-background: #f7fafb;" +
                "-fx-font-family: monospace;" +
                "-fx-font-size: 11px;" +
                "-fx-text-fill: #334852;" +
                "-fx-background-radius: 7;" +
                "-fx-border-radius: 7;";
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
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                setBusy(true, "Searching for Python...");
                appendLog("Searching for Python in common locations...");

                Set<String> candidates = new LinkedHashSet<>();

                // macOS / Linux common locations
                candidates.add("/usr/bin/python3");
                candidates.add("/opt/homebrew/bin/python3");
                candidates.add("/usr/local/bin/python3");
                candidates.add(System.getProperty("user.home") + "/miniconda3/bin/python");
                candidates.add(System.getProperty("user.home") + "/anaconda3/bin/python");
                candidates.add(System.getProperty("user.home") + "/.pyenv/shims/python3");

                // Windows common fixed locations
                String userHome = System.getProperty("user.home");
                candidates.add(userHome + "\\AppData\\Local\\Programs\\Python\\Python313\\python.exe");
                candidates.add(userHome + "\\AppData\\Local\\Programs\\Python\\Python312\\python.exe");
                candidates.add(userHome + "\\AppData\\Local\\Programs\\Python\\Python311\\python.exe");

                // Windows: try Python launcher
                candidates.addAll(findPythonViaPyLauncher());

                // Windows: scan common install folders
                candidates.addAll(findWindowsPythonCandidates());

                // Windows: fallback recursive scan of C:\
                candidates.addAll(findPythonAnywhereInCDrive());

                for (String path : candidates) {
                    File f = new File(path);
                    if (f.exists() && f.isFile()) {
                        Platform.runLater(() -> {
                            pythonField.setText(f.getAbsolutePath());
                            updateStatus("Auto-detected Python executable.", "green");
                            appendLog("Auto-detected Python: " + f.getAbsolutePath());
                            setBusy(false, null);
                        });
                        return null;
                    }
                }

                Platform.runLater(() -> {
                    setBusy(false, null);
                    showAlert(Alert.AlertType.WARNING, "Auto-detect", "No Python executable found.");
                });

                return null;
            }
        };

        new Thread(task, "vespa-auto-detect-python").start();
    }

    private List<String> findPythonViaPyLauncher() {
        List<String> found = new ArrayList<>();

        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return found;
        }

        try {
            ProcessResult result = runCommand(List.of("py", "-0p"));
            appendLog("Trying Windows Python launcher (py -0p)...");
            appendLog(result.output);

            if (result.exitCode == 0) {
                String[] lines = result.output.split("\\R");
                for (String line : lines) {
                    line = line.trim();
                    if (line.contains(":")) {
                        int idx = line.indexOf(":");
                        String possiblePath = line.substring(idx + 1).trim();
                        if (possiblePath.toLowerCase().endsWith("python.exe")) {
                            File f = new File(possiblePath);
                            if (f.exists()) {
                                found.add(f.getAbsolutePath());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            appendLog("Windows Python launcher not available.");
        }

        return found;
    }

    private List<String> findWindowsPythonCandidates() {
        List<String> found = new ArrayList<>();

        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return found;
        }

        appendLog("Scanning common Windows Python install folders...");

        String userHome = System.getProperty("user.home");

        File localPrograms = new File(userHome, "AppData\\Local\\Programs\\Python");
        collectPythonExecutables(localPrograms, found);

        File rootDrive = new File("C:\\");
        File[] rootDirs = rootDrive.listFiles();
        if (rootDirs != null) {
            for (File dir : rootDirs) {
                if (dir.isDirectory() && dir.getName().matches("Python\\d+")) {
                    File exe = new File(dir, "python.exe");
                    if (exe.exists()) {
                        found.add(exe.getAbsolutePath());
                    }
                }
            }
        }

        return found;
    }

    private void collectPythonExecutables(File parentDir, List<String> found) {
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
            return;
        }

        File[] dirs = parentDir.listFiles();
        if (dirs == null) {
            return;
        }

        for (File dir : dirs) {
            if (dir.isDirectory() && dir.getName().matches("Python\\d+")) {
                File exe = new File(dir, "python.exe");
                if (exe.exists()) {
                    found.add(exe.getAbsolutePath());
                }
            }
        }
    }

    private List<String> findPythonAnywhereInCDrive() {
        List<String> found = new ArrayList<>();

        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return found;
        }

        appendLog("Scanning C:\\ for python.exe (fallback scan)...");

        File cDrive = new File("C:\\");
        scanForPythonRecursively(cDrive, found, 6);

        return found;
    }

    private void scanForPythonRecursively(File dir, List<String> found, int maxDepth) {
        if (dir == null || maxDepth < 0 || !dir.exists() || !dir.isDirectory()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            try {
                if (file.isDirectory()) {
                    String name = file.getName().toLowerCase();

                    if (name.equals("windows") ||
                            name.equals("program files") ||
                            name.equals("program files (x86)") ||
                            name.equals("programdata") ||
                            name.equals("$recycle.bin") ||
                            name.equals("system volume information")) {
                        continue;
                    }

                    scanForPythonRecursively(file, found, maxDepth - 1);

                } else if (file.isFile() && file.getName().equalsIgnoreCase("python.exe")) {
                    found.add(file.getAbsolutePath());
                }
            } catch (Exception ignored) {
            }
        }
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

                appendLog("Installing compatible dependency versions...");
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
        boolean hasConfiguredPython = !pythonField.getText().trim().isBlank() || !PREFS.get(PREF_PYTHON_EXEC, "").isBlank();

        if (!venvDir.exists() && !hasConfiguredPython) {
            showAlert(Alert.AlertType.INFORMATION, "Reset environment", "No VeSpA environment or configured Python was found.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Reset environment");
        confirm.setHeaderText("Reset VeSpA Python configuration?");
        String resetMessage = "This will clear the configured Python executable.";
        if (venvDir.exists()) {
            resetMessage += "\n\nIt will also remove:\n" + venvDir.getAbsolutePath();
        }
        confirm.setContentText(resetMessage);

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        setBusy(true, "Removing VeSpA environment...");
                        updateProgress(0.25, 1.0);

                        boolean removedVenv = venvDir.exists();
                        if (venvDir.exists()) {
                            deleteRecursively(venvDir);
                        }

                        updateProgress(1.0, 1.0);

                        Platform.runLater(() -> {
                            if (removedVenv) {
                                appendLog("Removed environment: " + venvDir.getAbsolutePath());
                            } else {
                                appendLog("Cleared configured Python executable.");
                            }

                            pythonField.clear();
                            PREFS.remove(PREF_PYTHON_EXEC);
                            updateStatus("Python configuration reset.", "darkorange");
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
            boolean ok = "green".equalsIgnoreCase(color);
            boolean warning = "darkorange".equalsIgnoreCase(color);
            statusLabel.setText(text);
            statusLabel.setStyle("-fx-background-color: " + (ok ? "#dff4ee" : warning ? "#fff8e8" : "#fff0f0") + ";" +
                    "-fx-text-fill: " + (ok ? "#12664f" : warning ? "#805b00" : "#9f2d2d") + ";" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: " + (ok ? "#b7ded3" : warning ? "#efd9a4" : "#e4b7b7") + ";" +
                    "-fx-border-radius: 8;" +
                    "-fx-padding: 8 10 8 10;");
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
        updateStatus("Valid Python executable.", "green");
        saved = true;
        stage.close();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(title);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        Label contentLabel = new Label(content);
        contentLabel.setWrapText(true);
        contentLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #334852;");

        Label icon = new Label(type == Alert.AlertType.INFORMATION ? "i" : "!");
        icon.setMinSize(42, 42);
        icon.setPrefSize(42, 42);
        icon.setAlignment(javafx.geometry.Pos.CENTER);
        boolean error = type == Alert.AlertType.ERROR;
        icon.setStyle("-fx-background-color: " + (error ? "#fff0f0" : "#dff4ee") + ";" +
                "-fx-text-fill: " + (error ? "#9f2d2d" : "#12664f") + ";" +
                "-fx-font-size: 24px;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 21;" +
                "-fx-border-color: " + (error ? "#e4b7b7" : "#b7ded3") + ";" +
                "-fx-border-radius: 21;");

        Button okButton = new Button("OK");
        okButton.setStyle(primaryButtonStyle());
        okButton.setOnAction(e -> dialog.close());

        HBox body = new HBox(16, icon, new VBox(8, titleLabel, contentLabel));
        body.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        HBox footer = new HBox(okButton);
        footer.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox root = new VBox(18, body, footer);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color: " + BACKGROUND + ";");

        dialog.setScene(new Scene(root, 480, 190));
        dialog.setResizable(false);
        dialog.showAndWait();
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
