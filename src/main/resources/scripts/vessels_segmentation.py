import argparse
import cv2
import numpy as np
from skimage.measure import label, regionprops_table, regionprops
import pandas as pd
from pathlib import Path
import re
import sys


# Make printed logs appear immediately in the QuPath Java process
try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass


# ─────────────────────────────────────────────
#  TUNABLE PARAMETERS
# ─────────────────────────────────────────────

# Lumen detection
LUMEN_AREA_MIN = 200                # px²  – ignore tiny noise holes
LUMEN_AREA_MAX = 80_000             # px²  – ignore artefactually large holes
LUMEN_CIRCULARITY_MIN = 0.20        # 0–1  – low to allow elongated/irregular shapes
LUMEN_ECCENTRICITY_MAX = 0.97       # reject near-perfect lines (fragmentation artefacts)

# Wall-repair closing kernel (used before lumen detection)
WALL_CLOSE_KSIZE = 28               # px   – increase if vessel walls are very fragmented
WALL_CLOSE_ITER = 2                 # increase to improve wall closing

# Initial morphological cleanup
DILATE_KSIZE = 21                   # px   – dilation kernel for initial binary cleanup
DILATE_ITER = 1
ERODE_KSIZE = 3                     # px   – erosion kernel for initial binary cleanup
ERODE_ITER = 2

# Lumen expansion (merges lumen onto inner wall boundary after detection)
LUMEN_EXPAND_KSIZE = 5              # px   – expansion kernel size
LUMEN_EXPAND_ITER = 3               # increase to bridge larger inner-wall gaps

# Minimum vessel area to keep after all filtering
VESSEL_AREA_MIN = 500               # px²


# ─────────────────────────────────────────────
#  CORE LUMEN-FILLING LOGIC
# ─────────────────────────────────────────────

def fill_vessel_lumens(binary_walls):
    """
    Detect and fill vessel lumens in four steps:
      1. Repair fragmented walls via morphological closing (lumen detection only).
      2. Flood-fill from the image border to identify true background;
         candidate lumens are everything that is neither background nor wall.
      3. Filter candidates by area, eccentricity, and circularity.
      4. Expand validated lumens by a few px and merge onto the original
         (pre-repair) wall mask to preserve contour precision.

    Returns the filled mask.
    """
    h, w = binary_walls.shape

    # ── 1. Repair fragmented walls ─────────────────────────────────────
    kernel = cv2.getStructuringElement(
        cv2.MORPH_ELLIPSE,
        (WALL_CLOSE_KSIZE, WALL_CLOSE_KSIZE)
    )
    repaired = cv2.morphologyEx(
        binary_walls,
        cv2.MORPH_CLOSE,
        kernel,
        iterations=WALL_CLOSE_ITER
    )

    # ── 2. Identify candidate lumen regions ────────────────────────────
    padded = np.zeros((h + 2, w + 2), dtype=np.uint8)
    padded[1:h + 1, 1:w + 1] = repaired
    cv2.floodFill(padded, None, (0, 0), 128)   # 128 = "background" label

    background = padded[1:h + 1, 1:w + 1] == 128
    candidate_holes = (~background & ~repaired.astype(bool)).astype(np.uint8) * 255

    # ── 3. Filter candidates by shape ─────────────────────────────────
    labeled = label(candidate_holes)
    lumen_mask = np.zeros((h, w), dtype=np.uint8)

    for region in regionprops(labeled):
        if not (LUMEN_AREA_MIN <= region.area <= LUMEN_AREA_MAX):
            continue

        if region.eccentricity > LUMEN_ECCENTRICITY_MAX:
            continue

        perim = region.perimeter_crofton
        circ = (4 * np.pi * region.area) / (perim ** 2) if perim > 1e-6 else 0.0

        if circ < LUMEN_CIRCULARITY_MIN:
            continue

        lumen_mask[labeled == region.label] = 255

    # ── 4. Expand and merge onto original walls ────────────────────────
    expand_kernel = cv2.getStructuringElement(
        cv2.MORPH_ELLIPSE,
        (LUMEN_EXPAND_KSIZE, LUMEN_EXPAND_KSIZE)
    )
    lumen_expanded = cv2.dilate(
        lumen_mask,
        expand_kernel,
        iterations=LUMEN_EXPAND_ITER
    )

    return cv2.bitwise_or(binary_walls, lumen_expanded)


# ─────────────────────────────────────────────
#  PLUGIN-SUPPORT UTILITIES
# ─────────────────────────────────────────────

def get_cv2_kernel_shape(shape_name):
    """
    Convert the kernel shape selected in the QuPath GUI to the corresponding
    OpenCV structuring element type.
    """
    shape_name = shape_name.upper()

    if shape_name == "ELLIPSE":
        return cv2.MORPH_ELLIPSE
    if shape_name == "RECT":
        return cv2.MORPH_RECT
    if shape_name == "CROSS":
        return cv2.MORPH_CROSS

    raise ValueError(f"Unsupported kernel shape: {shape_name}")


def save_contours_csv(label_img, csv_path):
    """
    Save vessel contours in the CSV format expected by the QuPath plugin.

    Output columns:
      contour_id, point_order, x, y

    The contour_id is matched to the measurement CSV index, so Java can attach
    area, major/minor axis length, eccentricity, and orientation to each vessel.
    """
    rows = []
    labels = sorted([lab for lab in np.unique(label_img) if lab != 0])

    for output_id, lab in enumerate(labels):
        single_mask = (label_img == lab).astype(np.uint8) * 255

        contours, _ = cv2.findContours(
            single_mask,
            cv2.RETR_EXTERNAL,
            cv2.CHAIN_APPROX_SIMPLE
        )

        if not contours:
            continue

        cnt = max(contours, key=cv2.contourArea)

        if len(cnt) < 3:
            continue

        # Keep the contour close to the segmented object while removing tiny digitisation noise
        epsilon = 0.0000001 * cv2.arcLength(cnt, True)
        approx = cv2.approxPolyDP(cnt, epsilon, True)

        if len(approx) < 3:
            continue

        pts = approx.reshape(-1, 2)

        for point_order, (x, y) in enumerate(pts):
            rows.append([output_id, point_order, float(x), float(y)])

    df = pd.DataFrame(rows, columns=["contour_id", "point_order", "x", "y"])
    df.to_csv(csv_path, index=False)


# ─────────────────────────────────────────────
#  PER-IMAGE PROCESSING
# ─────────────────────────────────────────────

def process_image(
    input_path,
    output_dir,
    threshold_mode="otsu",
    percentile=None,
    dilation_kernel_size=(DILATE_KSIZE, DILATE_KSIZE),
    dilation_kernel_shape=cv2.MORPH_ELLIPSE,
    dilation_iter=DILATE_ITER,
    erosion_kernel_size=(ERODE_KSIZE, ERODE_KSIZE),
    erosion_iter=ERODE_ITER,
    vessel_area_min=VESSEL_AREA_MIN
):
    """
    Process a single image: segment vessels and fill their lumens.
    Returns the number of vessels detected.

    This function keeps the collaborator's vessel/lumen logic, but is also
    compatible with the QuPath plugin:
      - input_path and output_dir are passed from Java
      - morphology and lumen parameters are passed from the GUI
      - outputs include binary mask, overlay, measurements CSV, and contour CSV
    """
    base_name = Path(input_path).stem
    image_output_dir = Path(output_dir) / base_name
    image_output_dir.mkdir(parents=True, exist_ok=True)

    # ── Step 1: Load image once; derive all needed data from it ────────
    img_bgr = cv2.imread(input_path)

    if img_bgr is None:
        raise ValueError(f"Could not load image: {input_path}")

    # ── Step 2: Convert to CMYK Yellow channel ─────────────────────────
    img_f = img_bgr.astype(np.float32) / 255.0
    K = 1 - np.max(img_f, axis=2)
    Y_channel = ((1 - img_f[:, :, 0] - K) / (1 - K + 1e-10) * 255).astype(np.uint8)

    # ── Step 3: Threshold ──────────────────────────────────────────────
    if threshold_mode == "otsu":
        _, binary = cv2.threshold(
            Y_channel,
            0,
            255,
            cv2.THRESH_BINARY + cv2.THRESH_OTSU
        )
        print("  Thresholding: Otsu")

    elif threshold_mode == "percentile":
        if percentile is None:
            raise ValueError("threshold_mode is 'percentile' but no percentile value was provided.")

        thresh_value = int(np.percentile(Y_channel, percentile))
        _, binary = cv2.threshold(
            Y_channel,
            thresh_value,
            255,
            cv2.THRESH_BINARY
        )
        print(f"  Thresholding: {percentile}th percentile (value={thresh_value})")

    else:
        raise ValueError(f"Invalid threshold_mode: '{threshold_mode}'.")

    # ── Step 4: Initial morphological cleanup ─────────────────────────
    # The dilation kernel size, shape, and iteration count are configurable from the QuPath GUI.
    kernel_d = cv2.getStructuringElement(
        dilation_kernel_shape,
        dilation_kernel_size
    )
    dilated = cv2.dilate(binary, kernel_d, iterations=dilation_iter)

    # The erosion kernel size and iteration count are configurable from the QuPath GUI.
    # Erosion shape remains elliptical to preserve the intended biological morphology.
    kernel_e = cv2.getStructuringElement(
        cv2.MORPH_ELLIPSE,
        erosion_kernel_size
    )
    eroded = cv2.erode(dilated, kernel_e, iterations=erosion_iter)

    # ── Step 5: Contour refinement (keep large vessels only) ───────────
    contours, _ = cv2.findContours(
        eroded,
        cv2.RETR_EXTERNAL,
        cv2.CHAIN_APPROX_SIMPLE
    )

    h, w = eroded.shape[:2]
    wall_mask = np.zeros((h, w), dtype=np.uint8)

    for cnt in contours:
        if cv2.contourArea(cnt) < vessel_area_min:
            continue

        cv2.drawContours(wall_mask, [cnt], -1, 255, -1)

    # ── Step 6: Fill lumens ────────────────────────────────────────────
    print("  Filling vessel lumens …")
    filled_mask = fill_vessel_lumens(wall_mask)

    # ── Step 7: Final filtering and measurements on filled mask ────────
    # Filtering is repeated after lumen filling because lumen expansion can
    # slightly alter connected components.
    label_img_raw = label(filled_mask > 0)
    clean_mask = np.zeros_like(filled_mask, dtype=np.uint8)

    for region in regionprops(label_img_raw):
        if region.area < vessel_area_min:
            continue

        clean_mask[label_img_raw == region.label] = 255

    label_img = label(clean_mask > 0)

    props = regionprops_table(
        label_img,
        properties=[
            "area",
            "axis_major_length",
            "axis_minor_length",
            "eccentricity",
            "orientation"
        ]
    )
    measurements_df = pd.DataFrame(props)

    csv_path = image_output_dir / f"{base_name}_measurements.csv"
    measurements_df.to_csv(csv_path, index=True)
    print(f"Saved measurements: {csv_path}")

    # QuPath plugin requirement:
    # Save the polygon contour coordinates so Java can recreate vessels as QuPath objects.
    contours_csv_path = image_output_dir / "vessel_contours.csv"
    save_contours_csv(label_img, contours_csv_path)
    print(f"Saved contours for QuPath: {contours_csv_path}")

    # ── Step 8: Save filled binary mask ───────────────────────────────
    binary_path = image_output_dir / f"{base_name}_binary.png"

    if not cv2.imwrite(str(binary_path), clean_mask):
        raise IOError(f"Failed to write binary mask: {binary_path}")

    print(f"Saved filled binary mask: {binary_path}")

    # ── Step 9: Colour overlays ────────────────────────────────────────
    # Green = full vessel (walls + filled lumens)
    overlay = np.zeros_like(img_bgr)
    overlay[:, :, 1] = clean_mask   # green channel → full vessel (same in BGR and RGB)
    blended = cv2.addWeighted(img_bgr, 0.7, overlay, 0.3, 0)

    overlay_path = image_output_dir / f"{base_name}_overlay.png"

    if not cv2.imwrite(str(overlay_path), blended):
        raise IOError(f"Failed to write overlay: {overlay_path}")

    print(f"Saved overlay (green=vessel): {overlay_path}")

    # ── Step 10: Statistics ────────────────────────────────────────────
    n_vessels = len(measurements_df)

    print(f"\nVessel Statistics for {base_name}:")
    print(f"  Total vessels    : {n_vessels}")

    if n_vessels > 0:
        print(f"  Average area     : {measurements_df['area'].mean():.2f}")
        print(f"  Total area       : {measurements_df['area'].sum():.2f}")
        print(f"  Avg eccentricity : {measurements_df['eccentricity'].mean():.3f}\n")
    else:
        print("  Average area     : N/A")
        print("  Total area       : 0.00")
        print("  Avg eccentricity : N/A\n")

    return n_vessels


# ─────────────────────────────────────────────
#  FOLDER-LEVEL PROCESSING
# ─────────────────────────────────────────────

def natural_sort_key(path):
    """Sort paths so that e.g. image_2.png comes before image_10.png."""
    parts = re.split(r"(\d+)", path.name)
    return [int(p) if p.isdigit() else p.lower() for p in parts]


def process_folder(
    input_folder,
    output_folder,
    threshold_mode="otsu",
    percentile=None,
    dilation_kernel_size=(DILATE_KSIZE, DILATE_KSIZE),
    dilation_kernel_shape=cv2.MORPH_ELLIPSE,
    dilation_iter=DILATE_ITER,
    erosion_kernel_size=(ERODE_KSIZE, ERODE_KSIZE),
    erosion_iter=ERODE_ITER,
    vessel_area_min=VESSEL_AREA_MIN
):
    """Process all PNG files in the input folder."""
    png_files = sorted(Path(input_folder).glob("*.png"), key=natural_sort_key)

    if not png_files:
        print(f"No PNG files found in {input_folder}")
        return

    mode_label = f"percentile ({percentile}th)" if threshold_mode == "percentile" else threshold_mode

    print(f"Found {len(png_files)} PNG files to process")
    print(f"Threshold mode: {mode_label}")
    print(f"Dilation kernel size: {dilation_kernel_size}")
    print(f"Dilation iterations: {dilation_iter}")
    print(f"Erosion kernel size: {erosion_kernel_size}")
    print(f"Erosion iterations: {erosion_iter}")
    print(f"Vessel area minimum: {vessel_area_min}")
    print("=" * 60)

    total_vessels = 0

    for i, png_file in enumerate(png_files, 1):
        print(f"\nProcessing [{i}/{len(png_files)}]: {png_file.name}")
        print("-" * 60)

        try:
            total_vessels += process_image(
                str(png_file),
                output_folder,
                threshold_mode,
                percentile,
                dilation_kernel_size,
                dilation_kernel_shape,
                dilation_iter,
                erosion_kernel_size,
                erosion_iter,
                vessel_area_min
            )

        except (ValueError, IOError) as e:
            print(f"Error processing {png_file.name}: {e}")

        except Exception:
            print(f"Unexpected error processing {png_file.name}")
            raise

    print("\n" + "=" * 60)
    print("Processing complete!")
    print(f"Total vessels detected across all images: {total_vessels}")
    print(f"Output saved to: {output_folder}")


# ─────────────────────────────────────────────
#  ENTRY POINT
# ─────────────────────────────────────────────

def get_threshold_mode():
    """
    Prompt the user to choose a thresholding method.
    Returns (mode, percentile) where percentile is None for Otsu mode.

    This function is kept for standalone/script use.
    The QuPath plugin does not call this interactive prompt.
    """
    def prompt_percentile(default=10):
        """Prompt for a percentile value in 1–99, with a suggested default."""
        while True:
            raw = input(f"  Enter percentile (1–99) [suggested: {default}]: ").strip()

            if raw == "":
                print(f"→ Selected: {default}th percentile thresholding\n")
                return default

            if raw.isdigit() and 1 <= int(raw) <= 99:
                value = int(raw)
                print(f"→ Selected: {value}th percentile thresholding\n")
                return value

            print("  Invalid input. Please enter a whole number between 1 and 99.")

    print("\n" + "=" * 60)
    print("SELECT THRESHOLDING METHOD")
    print("=" * 60)
    print("  [1] Otsu thresholding (automatic)")
    print("  [2] Percentile thresholding (manual)")
    print("=" * 60)

    while True:
        choice = input("Enter your choice (1 or 2): ").strip()

        if choice == "1":
            print("Selected: Otsu thresholding\n")
            return "otsu", None

        if choice == "2":
            return "percentile", prompt_percentile()

        print("  Invalid input. Please enter 1 or 2.")


def main():
    """
    Entry point.

    For standalone use:
      python vessels_segmentation.py input_folder output_folder

    For QuPath plugin use:
      Java calls this script with input/output folders and morphology parameters.
    """
    global LUMEN_AREA_MIN
    global LUMEN_AREA_MAX
    global LUMEN_CIRCULARITY_MIN
    global LUMEN_ECCENTRICITY_MAX
    global WALL_CLOSE_KSIZE
    global WALL_CLOSE_ITER
    global LUMEN_EXPAND_KSIZE
    global LUMEN_EXPAND_ITER

    parser = argparse.ArgumentParser(
        description="VeSpA vessel segmentation for standalone use and QuPath plugin integration"
    )

    parser.add_argument("input_folder", type=str)
    parser.add_argument("output_folder", type=str)

    parser.add_argument("--lumen-area-min", type=int, default=LUMEN_AREA_MIN)
    parser.add_argument("--lumen-area-max", type=int, default=LUMEN_AREA_MAX)
    parser.add_argument("--lumen-circularity-min", type=float, default=LUMEN_CIRCULARITY_MIN)
    parser.add_argument("--lumen-eccentricity-max", type=float, default=LUMEN_ECCENTRICITY_MAX)

    parser.add_argument("--wall-close-ksize", type=int, default=WALL_CLOSE_KSIZE)
    parser.add_argument("--wall-close-iter", type=int, default=WALL_CLOSE_ITER)

    parser.add_argument("--dilation-kernel-width", type=int, default=DILATE_KSIZE)
    parser.add_argument("--dilation-kernel-height", type=int, default=DILATE_KSIZE)
    parser.add_argument("--dilation-iter", type=int, default=DILATE_ITER)
    parser.add_argument(
        "--dilation-kernel-shape",
        type=str,
        default="ELLIPSE",
        choices=["ELLIPSE", "RECT", "CROSS"]
    )

    parser.add_argument("--erosion-kernel-width", type=int, default=ERODE_KSIZE)
    parser.add_argument("--erosion-kernel-height", type=int, default=ERODE_KSIZE)
    parser.add_argument("--erosion-iter", type=int, default=ERODE_ITER)

    parser.add_argument("--lumen-expand-ksize", type=int, default=LUMEN_EXPAND_KSIZE)
    parser.add_argument("--lumen-expand-iter", type=int, default=LUMEN_EXPAND_ITER)

    parser.add_argument(
        "--threshold-mode",
        type=str,
        default="otsu",
        choices=["otsu", "percentile"]
    )
    parser.add_argument("--percentile", type=int, default=None)
    parser.add_argument("--vessel-area-min", type=int, default=VESSEL_AREA_MIN)

    args = parser.parse_args()

    # Parameters used inside fill_vessel_lumens are global by design, preserving
    # the collaborator's original implementation while making them GUI-configurable.
    LUMEN_AREA_MIN = args.lumen_area_min
    LUMEN_AREA_MAX = args.lumen_area_max
    LUMEN_CIRCULARITY_MIN = args.lumen_circularity_min
    LUMEN_ECCENTRICITY_MAX = args.lumen_eccentricity_max
    WALL_CLOSE_KSIZE = args.wall_close_ksize
    WALL_CLOSE_ITER = args.wall_close_iter
    LUMEN_EXPAND_KSIZE = args.lumen_expand_ksize
    LUMEN_EXPAND_ITER = args.lumen_expand_iter

    input_folder = Path(args.input_folder)
    output_folder = Path(args.output_folder)

    if not input_folder.exists():
        raise ValueError(f"Input folder does not exist: {input_folder}")

    output_folder.mkdir(parents=True, exist_ok=True)

    dilation_kernel_shape = get_cv2_kernel_shape(args.dilation_kernel_shape)

    dilation_kernel_size = (
        args.dilation_kernel_width,
        args.dilation_kernel_height
    )

    erosion_kernel_size = (
        args.erosion_kernel_width,
        args.erosion_kernel_height
    )

    print("VeSpA parameter summary")
    print(f"Lumen area min: {LUMEN_AREA_MIN}")
    print(f"Lumen area max: {LUMEN_AREA_MAX}")
    print(f"Lumen circularity min: {LUMEN_CIRCULARITY_MIN}")
    print(f"Lumen eccentricity max: {LUMEN_ECCENTRICITY_MAX}")
    print(f"Wall close kernel size: {WALL_CLOSE_KSIZE}")
    print(f"Wall close iterations: {WALL_CLOSE_ITER}")
    print(f"Lumen expansion kernel size: {LUMEN_EXPAND_KSIZE}")
    print(f"Lumen expansion iterations: {LUMEN_EXPAND_ITER}")

    process_folder(
        input_folder=str(input_folder),
        output_folder=str(output_folder),
        threshold_mode=args.threshold_mode,
        percentile=args.percentile,
        dilation_kernel_size=dilation_kernel_size,
        dilation_kernel_shape=dilation_kernel_shape,
        dilation_iter=args.dilation_iter,
        erosion_kernel_size=erosion_kernel_size,
        erosion_iter=args.erosion_iter,
        vessel_area_min=args.vessel_area_min
    )

    # QuPath Java plugin checks this exact string to confirm successful execution.
    print("VESSEL_SEGMENTATION_SUCCESS")


if __name__ == "__main__":
    main()
