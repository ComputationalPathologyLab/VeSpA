# VeSpA GUI Prototype

This folder is an isolated prototype area. It intentionally keeps the original
plugin files untouched.

## What is included

- `src/main/java/com/rashid/qupath/vesselseg/VesselSegmentationExtension.java`
  is a copied prototype of the extension with a dashboard-style GUI.
- `src/main/java/com/rashid/qupath/vesselseg/PythonConfigDialog.java`
  is copied so the prototype can compile independently.
- `build.gradle` and `settings.gradle` compile only files inside this `test`
  folder.

## Preset behavior

The segmentation preset buttons do not add a new Python algorithm. They fill the
existing parameter fields with named parameter groups:

- `Balanced`: original default values.
- `Sensitive`: lower object-size thresholds and more permissive lumen filters.
- `Fragmented walls`: stronger wall closing and lumen expansion.
- `Strict cleanup`: stricter area and lumen filters to reduce noise.

The existing `parseParameters()` method and Python command arguments are still
used after a preset fills the fields.
