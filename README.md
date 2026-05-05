<p align="center">
  <img src="src/main/resources/images/vespa_logo.png" width="160" alt="VeSpA logo"/>
</p>

<h1 align="center">VeSpA: Vessel Spatial Analysis</h1>

<p align="center">
  <em>Annotation-guided vessel segmentation and morphological quantification for histological images, directly inside QuPath</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/QuPath-0.6.0-1a6e8e?style=flat-square"/>
  <img src="https://img.shields.io/badge/Java-21-e07f2a?style=flat-square"/>
  <img src="https://img.shields.io/badge/Python-3.9%2B-4a8f3f?style=flat-square"/>
  <img src="https://img.shields.io/badge/status-active-3ba07a?style=flat-square"/>
</p>

---

## Abstract

Quantification of vascular architecture in tissue sections is central to understanding tumour microenvironments, organ development, and pathological remodelling. Yet existing tools require either manual delineation or disconnected, script-only pipelines that sacrifice spatial context. Here we present **VeSpA** (**Ve**ssel **Sp**atial **A**nalysis), a QuPath extension that bridges annotation-based region selection with a morphologically rigorous image-processing pipeline, returning vessel detections directly to the QuPath object hierarchy. By operating in the CMYK Yellow colour space and combining adaptive thresholding, iterative morphological repair, and shape-constrained lumen detection, VeSpA recovers intact vessel boundaries—including open or fragmented walls—and delivers per-vessel measurements (area, axis lengths, eccentricity, orientation) alongside annotation-level summaries. The entire workflow runs inside a familiar QuPath interface, preserving image coordinates and enabling immediate downstream spatial analysis without leaving the platform.

---

## Background and Motivation

Vascular density, vessel morphology, and the spatial distribution of blood vessels are established histopathological indicators across a spectrum of diseases. Manual counting is observer-dependent and impractical at scale; fully automated pipelines often discard the biological context encoded in annotated regions of interest. VeSpA addresses this gap by tightly integrating a parameter-controllable computer vision pipeline with QuPath's annotation and object hierarchy, so that:

- Regions of biological interest defined by the pathologist or researcher directly scope segmentation.
- Results are imported as first-class QuPath detection objects, inheriting the coordinate system of the original image.
- Every morphological parameter is accessible and tunable from the GUI without editing scripts.

---

## Algorithm

VeSpA processes each selected region through a deterministic, multi-stage pipeline.

```
Input image (PNG export from QuPath annotation)
         │
         ▼
 1. Colour-space transform
    RGB → CMYK Yellow channel
         │
         ▼
 2. Adaptive thresholding
    Otsu (automatic) or percentile (manual)
         │
         ▼
 3. Morphological cleanup
    Dilation → Erosion
    (configurable kernel size, shape, iterations)
         │
         ▼
 4. Contour filtering
    Minimum area threshold applied to initial contours
         │
         ▼
 5. Lumen detection and filling
    a. Wall repair — morphological closing to bridge fragmented boundaries
    b. Background labelling — border flood-fill to identify true background
    c. Candidate isolation — pixels that are neither background nor wall
    d. Shape filtering — area, Crofton circularity, eccentricity criteria
    e. Lumen expansion — validated lumens dilated and merged onto walls
         │
         ▼
 6. Final area filter
    Post-lumen-fill connected-component size filter
         │
         ▼
 7. Measurement extraction
    scikit-image regionprops: area, major/minor axis, eccentricity, orientation
         │
         ▼
 8. Contour extraction → CSV
    OpenCV findContours; Douglas–Peucker approximation (ε = 10⁻⁷ × arc length)
         │
         ▼
 9. QuPath object import (Java)
    Polygon ROIs reconstructed from contour CSV, translated to image coordinates,
    filtered to annotation boundary, added to object hierarchy with measurements
```

The CMYK Yellow channel was chosen because it selectively enhances eosin-stained structures against haematoxylin-dominated backgrounds in standard H&E tissue sections, improving signal separation without manual colour deconvolution.

---

## Key Features

| Capability | Detail |
|---|---|
| Annotation-scoped segmentation | Vessels detected inside selected QuPath annotations only |
| Multi-annotation batch | All selected annotations processed in a single run with per-annotation progress |
| Whole-image mode | Full-slide processing when annotation-based scoping is not required |
| CMYK Yellow thresholding | Colour-space transform optimised for H&E staining |
| Dual threshold modes | Otsu (automatic) and percentile (manual, tunable from GUI) |
| Lumen-aware segmentation | Hollow vessel structures filled via morphologically validated lumen detection |
| Four segmentation presets | Balanced · Sensitive · Fragmented walls · Strict cleanup |
| Fully exposed parameter space | Lumen detection, wall repair, morphology, and expansion parameters all GUI-accessible |
| Native QuPath outputs | Vessel contours imported as polygon detection objects in the QuPath hierarchy |
| Per-vessel measurements | Area, major/minor axis length, eccentricity, orientation |
| Annotation-level summaries | Vessel count and aggregate area propagated to parent annotation |
| Integrated Python management | Configure, test, check, install, and reset the Python environment from the plugin |
| Progress feedback | Fine-grained progress bar updates keyed to Python pipeline stage output |

---

## Segmentation Presets

Presets load named parameter groups into the parameter fields. Users can start from a preset and then refine individual values without losing the preset as a baseline.

| Preset | Intended tissue context | Key differences from Balanced |
|---|---|---|
| **Balanced** | General-purpose; well-defined vessel boundaries | Default parameters |
| **Sensitive** | Small or faintly stained vessels; denser vascular beds | Looser lumen filters, larger dilation, lower area threshold |
| **Fragmented walls** | Discontinuous or poorly-stained vessel walls | Larger closing kernel, more closing iterations, stronger lumen expansion |
| **Strict cleanup** | Noisy sections; artefact-prone staining | Tighter circularity and eccentricity filters, higher minimum vessel area |

---

## Outputs


The Java layer reads the contour and measurement CSV files and creates QuPath detection objects with the following measurements:

- `VeSpA: Vessel ID`
- `VeSpA: Area`
- `VeSpA: Major axis length`
- `VeSpA: Minor axis length`
- `VeSpA: Eccentricity`
- `VeSpA: Orientation`

Parent annotations additionally receive:

- `Num Vessel`
- `VeSpA: Total Vessel Area`
- `VeSpA: Mean Area`, `VeSpA: Mean Major axis length`, `VeSpA: Mean Minor axis length`
- `VeSpA: Mean Eccentricity`, `VeSpA: Mean Orientation`

---

## Requirements

### QuPath

- QuPath ≥ 0.6.0 (`qupath-gui-fx:0.6.0` API)
- Java 21 runtime

### Python

- Python 3.9 or later
- `opencv-python >= 4.10, < 5`
- `numpy >= 1.26, < 3`
- `scikit-image >= 0.24, < 1`
- `pandas >= 2.2, < 3`

The plugin can create and manage a dedicated VeSpA Python environment from the **Configure Python** dialog.

---

## Installation

### 1. Build from source

```bash
./gradlew clean build
```

Output JAR:

```
build/libs/qupath-extension-vessel-segmentation-1-0.0.1.jar
```

Alternatively, use the pre-built JAR from the repository.

### 2. Install in QuPath

1. Copy the JAR into your QuPath extensions folder.
2. Restart QuPath.
3. The extension is registered automatically via Java's service loader mechanism.
4. Open it from:

```
Extensions > Vessel Segmentation > Run Vessel Segmentation
```

> Install only one VeSpA JAR at a time. Multiple JARs may cause duplicate menu entries or conflicting GUI state.

---

## Python Configuration

Open **Configure Python** from the VeSpA sidebar. The dialog allows you to:

- Browse for a Python executable or auto-detect common installations
- Test the selected executable
- Check whether required packages are installed
- Install all dependencies into a dedicated VeSpA environment
- Reset the configured executable

When Python is correctly configured, the sidebar and header display **Python Ready**. If the executable is missing or has been reset, the status shows **Python Missing** until a valid path is saved.

The bundled Python script (`vessels_segmentation.py`) is extracted from the JAR to a temporary file at runtime, so no manual script deployment is required.

---

## Usage

### Annotation-based segmentation

1. Open an image in QuPath.
2. Draw or select one or more annotations over the regions to analyse.
3. Open **Extensions > Vessel Segmentation > Run Vessel Segmentation**.
4. Select **Selected annotation(s)**.
5. Choose a preset or tune parameters manually.
6. Click **Run Segmentation**.

Each annotation is exported as a PNG, processed independently by the Python pipeline, and vessel detections are imported as children of the corresponding annotation object.

### Whole-image segmentation

1. Open an image in QuPath.
2. Open the VeSpA plugin.
3. Select **Whole image**.
4. Choose a preset or tune parameters manually.
5. Click **Run Segmentation**.

Processing time and memory use scale with image dimensions, bit depth, and system resources.

---

## Parameter Reference

### Quick Controls

| Parameter | Default | Description |
|---|---|---|
| Threshold mode | `otsu` | `otsu` for automatic thresholding; `percentile` for manual control |
| Percentile value | `10` | Active only when threshold mode is `percentile`; valid range 1–99 |
| Minimum vessel area | `500 px²` | Connected components below this area are discarded |

### Advanced Tuning — Lumen Detection

| Parameter | Default | Description |
|---|---|---|
| Min lumen area | `200 px²` | Ignore noise holes smaller than this |
| Max lumen area | `80 000 px²` | Ignore artefactually large holes |
| Circularity min | `0.20` | 4π·area / perimeter²; low values permit elongated lumens |
| Eccentricity max | `0.97` | Rejects near-linear fragmentation artefacts |

### Advanced Tuning — Wall Repair

| Parameter | Default | Description |
|---|---|---|
| Closing kernel size | `28 px` | Increase for heavily fragmented vessel walls |
| Closing iterations | `2` | Additional passes improve closure of large gaps |

### Advanced Tuning — Morphology

| Parameter | Default | Description |
|---|---|---|
| Dilation width / height | `21 × 21 px` | Initial binary expansion |
| Dilation iterations | `1` | |
| Kernel shape | `ELLIPSE` | `ELLIPSE` · `RECT` · `CROSS` |
| Erosion width / height | `3 × 3 px` | Boundary restoration after dilation |
| Erosion iterations | `2` | |

### Advanced Tuning — Lumen Expansion

| Parameter | Default | Description |
|---|---|---|
| Expansion kernel size | `5 px` | Dilation applied to validated lumens before merging onto wall mask |
| Expansion iterations | `3` | Increase to bridge larger inner-wall gaps |

---

## Troubleshooting

**Extension does not appear in QuPath**
Confirm the JAR is in the correct extensions folder and that QuPath was fully restarted. Ensure only one VeSpA JAR is installed.

**Python Missing status**
Open **Configure Python**, select a valid executable, click **Test**, then **Save**.

**Required packages are missing**
In **Configure Python**, click **Check environment** to inspect installed packages, then **Install dependencies** to create or update the VeSpA environment.

**Segmentation fails during processing**
Check the log output in the plugin window. Confirm that `cv2`, `numpy`, `skimage`, and `pandas` are importable in the configured environment. Test with the **Balanced** preset on a small annotation first.

**Objects imported but measurements appear duplicated**
QuPath may warn about duplicate measurement names if the same annotation is processed more than once. Clear existing VeSpA detections or create fresh annotations before rerunning.

**Unexpected results with percentile thresholding**
Compare against Otsu thresholding as a reference. Low percentile values increase sensitivity at the cost of noise; values below 5 are rarely productive.

**Out-of-memory errors**
Process selected annotations rather than the whole image. Split large regions into smaller annotations. Increase QuPath and Java heap memory if your system allows. Close other memory-intensive applications during processing.

---

## Project Structure

```
qupath-vessel-segmentation-1/
├── build.gradle
├── settings.gradle
└── src/main/
    ├── java/com/rashid/qupath/vesselseg/
    │   ├── VesselSegmentationExtension.java   # Extension entry point, GUI, segmentation runner, object import
    │   └── PythonConfigDialog.java            # Python environment management dialog
    └── resources/
        ├── images/
        │   └── vespa_logo.png
        ├── scripts/
        │   └── vessels_segmentation.py        # Bundled segmentation pipeline (extracted at runtime)
        └── META-INF/services/
            └── qupath.lib.gui.extensions.QuPathExtension
```

### Building

```bash
./gradlew clean build
```

Java 21 toolchain is required. Dependencies are fetched from Maven Central and the SciJava repository. The extension registers itself via Java's service loader; no additional QuPath configuration is needed beyond copying the JAR.

---

## Notes on Reproducibility

Segmentation quality depends on staining protocol, scanner calibration, tissue preparation, and annotation strategy. The CMYK Yellow channel approach is optimised for standard H&E sections; other staining protocols may require threshold mode and parameter adjustment. Users should validate parameter choices on a representative subset of images before applying any configuration to a full dataset. All parameters are exposed in the GUI and passed deterministically to the Python pipeline, so any configuration that produces satisfactory results can be reproduced exactly by recording the parameter values used.
