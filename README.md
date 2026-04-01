# VeSpA: A QuPath Plugin for Vessel Spatial Analysis

**VeSpA (Vessel Spatial Analysis)** is a QuPath extension that enables annotation-aware vessel segmentation through seamless integration of QuPath with an external Python-based image processing pipeline. The plugin allows users to perform spatially constrained segmentation directly on regions of interest (ROIs) within whole-slide or microscopy images and returns results as structured objects within the QuPath environment.

---

## Summary

Spatial characterization of vascular structures is critical in histopathology, oncology, and tissue microenvironment analysis. Existing tools often require exporting image regions and performing segmentation externally, limiting interactivity and reproducibility.  

VeSpA addresses this limitation by integrating region-aware vessel segmentation directly within QuPath. The plugin enables users to select one or more annotations, perform segmentation using a Python-based pipeline, and reintegrate detected vessels as structured objects within the QuPath hierarchy. This workflow preserves spatial context and facilitates downstream quantitative analysis.

---

## Key Features

- **Annotation-aware segmentation**  
  Segmentation is constrained to user-defined ROIs, ensuring biologically relevant results.

- **Multi-annotation support**  
  Multiple annotations can be processed in a single run, with results mapped back to their respective regions.

- **Seamless QuPath integration**  
  Images are processed directly from the QuPath viewer without manual export.

- **Bidirectional workflow**  
  Results are returned as detection objects within QuPath, preserving spatial relationships.

- **External processing pipeline**  
  Leverages Python libraries for robust image processing:
  - OpenCV
  - NumPy
  - scikit-image
  - pandas

- **Export of quantitative outputs**  
  Segmentation masks, overlays, and measurements are saved for further analysis.

---

## System Architecture

The plugin follows a modular architecture: