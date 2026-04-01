<p align="center">
  <img src="vespa_logo.png" width="180" alt="VeSpA logo"/>
</p>

<h1 align="center">Vessel Spatial Analysis for QuPath</h1>

<p align="center">
  <b>Annotation-based vessel segmentation directly inside QuPath</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/QuPath-plugin-blue"/>
  <img src="https://img.shields.io/badge/Python-3.9%2B-green"/>
  <img src="https://img.shields.io/badge/license-Apache--2.0-orange"/>
  <img src="https://img.shields.io/badge/status-active-success"/>
</p>

---

## Overview

**VeSpA (Vessel Spatial Analysis)** is a QuPath extension for performing **annotation based vessel segmentation** directly within the QuPath environment.

The plugin integrates a Python-based image processing pipeline with QuPath, enabling:

- segmentation of vessels within selected annotations
- automatic reintegration of results into the image hierarchy
- preservation of spatial context for downstream analysis

---

## Why use VeSpA?

- No manual image export required  
- Works directly on QuPath annotations  
- Supports **multiple annotations in a single run**  
- Returns results instantly to QuPath  
- Fully reproducible pipeline  
- Lightweight and extensible  

---

## Key Features

### Annotation-aware segmentation
- Works with any ROI shape (polygon, freehand, rectangle)
- Ensures segmentation results remain within biological regions

### Multi-annotation processing
- Select multiple annotations
- Process them in one run
- Results are mapped back to each annotation

### Seamless QuPath integration
- Extracts image regions via QuPath API
- Returns detections directly to viewer

### Python-powered processing
Uses:

- OpenCV
- NumPy
- scikit-image
- pandas

---

## Architecture

The plugin follows a modular architecture:

``` text
QuPath (GUI & ROI selection)
↓
Java Plugin (data extraction & orchestration)
↓
Region export (PNG)
↓
Python segmentation pipeline
↓
Contour & measurement extraction (CSV)
↓
Java Plugin (parsing & filtering)
↓
QuPath object hierarchy (detections)
```