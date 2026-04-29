<p align="center">
  <img src="vespa_logo.png" width="180" alt="VeSpA logo"/>
</p>

<h1 align="center">VeSpA: Vessel Spatial Analysis for QuPath</h1>

<p align="center">
  <b>Annotation-guided vessel segmentation and measurement directly inside QuPath</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/QuPath-extension-blue"/>
  <img src="https://img.shields.io/badge/QuPath-0.6.0-blue"/>
  <img src="https://img.shields.io/badge/Java-21-orange"/>
  <img src="https://img.shields.io/badge/Python-3.9%2B-green"/>
  <img src="https://img.shields.io/badge/status-active-success"/>
</p>

---

## Overview

**VeSpA** (**Vessel Spatial Analysis**) is a QuPath extension for vessel segmentation in histological images. It combines QuPath's annotation and object hierarchy with a Python-based image-processing pipeline, allowing users to segment vascular structures within selected regions and return the resulting vessel detections directly to QuPath.

The extension is designed for annotation-based workflows where biologically meaningful regions are first defined in QuPath and then processed reproducibly. Segmentation results are imported as QuPath detection objects, preserving image coordinates and enabling downstream measurement, visualization, filtering, and spatial analysis.

---

## Key Features

- **Annotation-aware processing**: segment vessels inside selected QuPath annotations.
- **Multi-annotation support**: process several selected annotations in a single run.
- **Whole-image mode**: process the entire image when annotation-based analysis is not required.
- **Integrated Python configuration**: configure, test, install, reset, and validate the Python environment from the plugin GUI.
- **Preset-based segmentation**: choose from practical presets such as `Balanced`, `Sensitive`, `Fragmented walls`, and `Strict cleanup`.
- **Advanced tuning**: expose thresholding, lumen detection, wall repair, morphology, lumen expansion, and vessel filtering parameters.
- **QuPath-native outputs**: import vessel contours as detection objects with measurements.
- **Progress feedback**: show processing progress for selected annotations and whole-image workflows.

---

## Workflow

```text
QuPath image and annotation selection
        |
        v
VeSpA Java extension
        |
        v
Region export as PNG
        |
        v
Python vessel segmentation pipeline
        |
        v
Contour and measurement CSV output
        |
        v
QuPath object import
        |
        v
Vessel detections and annotation-level summaries
```

---

## Requirements

### QuPath

- QuPath compatible with the `qupath-gui-fx:0.6.0` API.
- Java 21 runtime/toolchain.

### Python

VeSpA requires a Python executable with the following packages:

- `opencv-python>=4.10,<5`
- `numpy>=1.26,<3`
- `scikit-image>=0.24,<1`
- `pandas>=2.2,<3`

The plugin includes a Python configuration window that can create and manage a dedicated VeSpA environment for you.

---

## Installation

### 1. Build the extension

From the project root:

```bash
./gradlew clean build
```

The built plugin JAR will be created at:

```text
build/libs/qupath-extension-vessel-segmentation-1-0.0.1.jar
```

### 2. Install in QuPath

1. Open QuPath.
2. Open the QuPath extensions folder or use QuPath's extension manager if available.
3. Copy the JAR file into the QuPath extensions folder.
4. Restart QuPath completely.
5. Open the plugin from:

```text
Extensions > Vessel Segmentation > Run Vessel Segmentation
```

---

## Python Configuration

Open the plugin and click **Configure Python** from the VeSpA sidebar.

The configuration window allows you to:

- browse for a Python executable;
- auto-detect common Python installations;
- test the selected executable;
- check whether required packages are available;
- install dependencies into a dedicated VeSpA environment;
- reset the configured Python environment.

If Python is configured correctly, the main plugin window will show **Python Ready**. If the configured executable is missing or reset, the plugin will show **Python Missing** until a valid Python path is saved again.

---

## Usage

### Annotation-based segmentation

1. Open an image in QuPath.
2. Draw or select one or more annotations.
3. Open **Extensions > Vessel Segmentation > Run Vessel Segmentation**.
4. Select **Selected annotation(s)**.
5. Choose a segmentation preset or adjust parameters manually.
6. Click **Run Segmentation**.

VeSpA exports each selected annotation, processes it, and imports vessel detections as children of the corresponding annotation.

### Whole-image segmentation

1. Open an image in QuPath.
2. Open the VeSpA plugin.
3. Select **Whole image**.
4. Choose a preset or adjust parameters.
5. Click **Run Segmentation**.

Whole-image processing may require substantial memory depending on image size, image type, and system specifications.

---

## Segmentation Presets

Presets do not change the underlying algorithm. They apply named groups of parameter values to the existing segmentation controls.

| Preset | Intended use |
| --- | --- |
| `Balanced` | General-purpose vessel segmentation using default values. |
| `Sensitive` | More permissive detection for smaller or weaker vessels; may increase false positives. |
| `Fragmented walls` | Stronger wall repair and lumen expansion for broken or discontinuous vessel boundaries. |
| `Strict cleanup` | More conservative filtering to reduce noise-prone detections. |

Users can start from a preset and then modify individual parameters in **Quick Controls** or **Advanced Tuning**.

---

## Parameters

### Quick Controls

- **Threshold mode**: choose automatic `otsu` thresholding or manual `percentile` thresholding.
- **Percentile value**: used only when `percentile` thresholding is selected.
- **Minimum vessel area**: removes small connected components from the final segmentation.

### Advanced Tuning

- **Lumen detection**: controls lumen candidate area, circularity, and eccentricity filtering.
- **Wall repair**: controls morphological closing used to repair fragmented walls before lumen detection.
- **Morphology**: controls dilation and erosion used for initial vessel cleanup.
- **Lumen expansion**: controls how validated lumens are merged back onto vessel walls.

---

## Outputs

For each processed region, the Python pipeline produces:

- a binary vessel mask;
- an overlay image;
- vessel measurements as CSV;
- vessel contour coordinates as CSV.

The Java extension reads the contour and measurement outputs and creates QuPath detection objects with measurements such as:

- `VeSpA: Vessel ID`
- `VeSpA: Area`
- `VeSpA: Major axis length`
- `VeSpA: Minor axis length`
- `VeSpA: Eccentricity`
- `VeSpA: Orientation`

For annotation-based runs, parent annotations also receive summary measurements, including vessel count and aggregate vessel area.

---

## Troubleshooting

### The extension does not appear in QuPath

- Confirm that the JAR is placed in the QuPath extensions folder.
- Restart QuPath completely after copying the JAR.
- Make sure only one active VeSpA JAR is installed. Multiple versions may cause confusing menu or GUI behavior.

### Python is shown as missing

- Open **Configure Python**.
- Select a valid Python executable.
- Click **Test** to confirm that the executable runs.
- Click **Save**.
- The main plugin window should update to **Python Ready** after the configuration window closes.

### Required Python packages are missing

- Open **Configure Python**.
- Click **Check environment** to inspect installed packages.
- Click **Install dependencies** to create or update the VeSpA environment.
- If installation fails, check network access and Python permissions.

### Reset environment says no VeSpA environment was found

VeSpA can use either a dedicated environment or a manually selected Python executable. If no dedicated VeSpA environment exists, reset will still clear the configured Python executable. After reset, configure Python again and save a valid executable.

### Segmentation fails during processing

- Check the log output shown in the plugin window.
- Confirm that Python can import `cv2`, `numpy`, `skimage`, and `pandas`.
- Try a smaller annotation to verify that the pipeline works on a limited region.
- Try the `Balanced` preset before changing advanced parameters.

### Percentile thresholding behaves unexpectedly

- Ensure the **Percentile value** is between `1` and `99`.
- Low percentiles may increase sensitivity but can introduce noise.
- Compare results against `otsu` thresholding before committing to a manual value.

### Very large images or annotations may run out of memory

If the image or selected region is too large, the system may run out of memory. This depends on image dimensions, bit depth, number of selected annotations, available RAM, Java memory settings, and Python memory availability.

Recommended mitigations:

- process selected annotations instead of the whole image;
- split very large regions into smaller annotations;
- close other memory-intensive applications;
- increase QuPath/Java memory if appropriate;
- test settings on a smaller region before running a full dataset.

### Objects are imported but measurements look duplicated

QuPath may warn about duplicate measurement names if the same annotation is processed repeatedly. Consider clearing previous VeSpA detections or using fresh annotations before rerunning segmentation.

---

## Development

### Build

```bash
./gradlew clean build
```

### Main source files

```text
src/main/java/com/rashid/qupath/vesselseg/VesselSegmentationExtension.java
src/main/java/com/rashid/qupath/vesselseg/PythonConfigDialog.java
src/main/resources/scripts/vessels_segmentation.py
src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension
```

### Extension registration

QuPath discovers the extension through Java's service loader file:

```text
src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension
```

The file should contain:

```text
com.rashid.qupath.vesselseg.VesselSegmentationExtension
```

---

## Notes

VeSpA is intended to support reproducible vessel segmentation workflows inside QuPath. Segmentation quality depends on staining, image acquisition, annotation strategy, and parameter selection. Users should validate results against representative images before applying the workflow to large-scale analyses.
