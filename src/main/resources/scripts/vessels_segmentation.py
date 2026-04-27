import argparse
import re
import sys
from pathlib import Path

import cv2
import numpy as np
import pandas as pd
from skimage.measure import label, regionprops, regionprops_table

sys.stdout.reconfigure(line_buffering=True)

# Lumen detection defaults
LUMEN_AREA_MIN = 200
LUMEN_AREA_MAX = 80000
LUMEN_CIRCULARITY_MIN = 0.20
LUMEN_ECCENTRICITY_MAX = 0.97

# Wall repair defaults
WALL_CLOSE_KSIZE = 28
WALL_CLOSE_ITER = 2

# Lumen expansion defaults
LUMEN_EXPAND_KSIZE = 5
LUMEN_EXPAND_ITER = 3

# Vessel filtering
VESSEL_AREA_MIN = 500


def natural_sort_key(path: Path) -> list:
    parts = re.split(r"(\d+)", path.name)
    return [int(p) if p.isdigit() else p.lower() for p in parts]


def save_contours_csv(label_img, csv_path):
    rows = []

    labels = sorted([x for x in np.unique(label_img) if x != 0])

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

        epsilon = 0.0000001 * cv2.arcLength(cnt, True)
        approx = cv2.approxPolyDP(cnt, epsilon, True)

        if len(approx) < 3:
            continue

        pts = approx.reshape(-1, 2)

        for point_order, (x, y) in enumerate(pts):
            rows.append([output_id, point_order, float(x), float(y)])

    df = pd.DataFrame(rows, columns=["contour_id", "point_order", "x", "y"])
    df.to_csv(csv_path, index=False)


def fill_vessel_lumens(binary_walls: np.ndarray) -> np.ndarray:
    h, w = binary_walls.shape

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

    padded = np.zeros((h + 2, w + 2), dtype=np.uint8)
    padded[1:h + 1, 1:w + 1] = repaired

    cv2.floodFill(padded, None, (0, 0), 128)

    background = padded[1:h + 1, 1:w + 1] == 128
    candidate_holes = (~background & ~repaired.astype(bool)).astype(np.uint8) * 255

    labeled = label(candidate_holes)
    lumen_mask = np.zeros((h, w), dtype=np.uint8)

    for region in regionprops(labeled):
        if not (LUMEN_AREA_MIN <= region.area <= LUMEN_AREA_MAX):
            continue

        if region.eccentricity > LUMEN_ECCENTRICITY_MAX:
            continue

        perim = region.perimeter_crofton
        circularity = (4 * np.pi * region.area) / (perim ** 2) if perim > 1e-6 else 0.0

        if circularity < LUMEN_CIRCULARITY_MIN:
            continue

        lumen_mask[labeled == region.label] = 255

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


def get_cv2_kernel_shape(shape_name: str):
    shape_name = shape_name.upper()

    if shape_name == "ELLIPSE":
        return cv2.MORPH_ELLIPSE

    if shape_name == "RECT":
        return cv2.MORPH_RECT

    if shape_name == "CROSS":
        return cv2.MORPH_CROSS

    raise ValueError(f"Unsupported kernel shape: {shape_name}")


def process_image(
    image_path,
    output_folder,
    dilation_kernel_size,
    dilation_kernel_shape,
    erosion_kernel_size,
    threshold_mode="otsu",
    percentile=None,
    vessel_area_min=VESSEL_AREA_MIN
):
    base_name = Path(image_path).stem
    image_output_dir = Path(output_folder) / base_name
    image_output_dir.mkdir(parents=True, exist_ok=True)

    img_bgr = cv2.imread(str(image_path))

    if img_bgr is None:
        raise ValueError(f"Could not load image: {image_path}")

    img_float = img_bgr.astype(np.float32) / 255.0

    k_channel = 1 - np.max(img_float, axis=2)
    y_channel = (
        (1 - img_float[:, :, 0] - k_channel) /
        (1 - k_channel + 1e-10) * 255
    ).astype(np.uint8)

    if threshold_mode == "otsu":
        _, binary = cv2.threshold(
            y_channel,
            0,
            255,
            cv2.THRESH_BINARY + cv2.THRESH_OTSU
        )
        print("Thresholding: Otsu")

    elif threshold_mode == "percentile":
        if percentile is None:
            raise ValueError("Percentile thresholding selected but no percentile was provided.")

        threshold_value = int(np.percentile(y_channel, percentile))
        _, binary = cv2.threshold(
            y_channel,
            threshold_value,
            255,
            cv2.THRESH_BINARY
        )
        print(f"Thresholding: {percentile}th percentile, value={threshold_value}")

    else:
        raise ValueError(f"Invalid threshold mode: {threshold_mode}")

    kernel_dilate = cv2.getStructuringElement(
        dilation_kernel_shape,
        dilation_kernel_size
    )

    dilated = cv2.dilate(binary, kernel_dilate, iterations=1)

    kernel_erode = cv2.getStructuringElement(
        cv2.MORPH_ELLIPSE,
        erosion_kernel_size
    )

    eroded = cv2.erode(dilated, kernel_erode, iterations=2)

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

    print("Filling vessel lumens...")
    filled_mask = fill_vessel_lumens(wall_mask)

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

    measurements_csv = image_output_dir / f"{base_name}_measurements.csv"
    measurements_df.to_csv(measurements_csv, index=True)

    contours_csv = image_output_dir / "vessel_contours.csv"
    save_contours_csv(label_img, contours_csv)

    binary_path = image_output_dir / f"{base_name}_binary.png"
    cv2.imwrite(str(binary_path), clean_mask)

    overlay = np.zeros_like(img_bgr)
    overlay[:, :, 1] = clean_mask

    blended = cv2.addWeighted(img_bgr, 0.7, overlay, 0.3, 0)

    overlay_path = image_output_dir / f"{base_name}_overlay.png"
    cv2.imwrite(str(overlay_path), blended)

    n_vessels = len(measurements_df)

    print(f"Contours CSV: {contours_csv}")
    print(f"Measurements CSV: {measurements_csv}")
    print(f"Binary mask: {binary_path}")
    print(f"Overlay: {overlay_path}")
    print(f"Total vessels: {n_vessels}")

    if n_vessels > 0:
        print(f"Average area: {measurements_df['area'].mean():.2f}")
        print(f"Total area: {measurements_df['area'].sum():.2f}")
        print(f"Average eccentricity: {measurements_df['eccentricity'].mean():.3f}")

    print("VESSEL_SEGMENTATION_SUCCESS")

    return n_vessels


def process_folder(
    input_folder,
    output_folder,
    dilation_kernel_size,
    dilation_kernel_shape,
    erosion_kernel_size,
    threshold_mode="otsu",
    percentile=None,
    vessel_area_min=VESSEL_AREA_MIN
):
    input_path = Path(input_folder)

    png_files = sorted(input_path.glob("*.png"), key=natural_sort_key)

    if not png_files:
        print(f"No PNG files found in {input_folder}")
        return

    print(f"Found {len(png_files)} PNG files to process")
    print(f"Threshold mode: {threshold_mode}")
    print("=" * 60)

    total_vessels = 0

    for i, png_file in enumerate(png_files, 1):
        print(f"Processing [{i}/{len(png_files)}]: {png_file.name}")
        print("-" * 60)

        total_vessels += process_image(
            image_path=str(png_file),
            output_folder=output_folder,
            dilation_kernel_size=dilation_kernel_size,
            dilation_kernel_shape=dilation_kernel_shape,
            erosion_kernel_size=erosion_kernel_size,
            threshold_mode=threshold_mode,
            percentile=percentile,
            vessel_area_min=vessel_area_min
        )

    print("=" * 60)
    print("Processing complete.")
    print(f"Total vessels detected across all images: {total_vessels}")
    print(f"Output saved to: {output_folder}")


def main():
    parser = argparse.ArgumentParser(
        description="VeSpA vessel segmentation for QuPath plugin"
    )

    parser.add_argument("input_folder", type=str)
    parser.add_argument("output_folder", type=str)

    parser.add_argument("--dilation-kernel-width", type=int, default=21)
    parser.add_argument("--dilation-kernel-height", type=int, default=21)
    parser.add_argument(
        "--dilation-kernel-shape",
        type=str,
        default="ELLIPSE",
        choices=["ELLIPSE", "RECT", "CROSS"]
    )

    parser.add_argument("--erosion-kernel-width", type=int, default=3)
    parser.add_argument("--erosion-kernel-height", type=int, default=3)

    parser.add_argument(
        "--threshold-mode",
        type=str,
        default="otsu",
        choices=["otsu", "percentile"]
    )

    parser.add_argument("--percentile", type=int, default=None)
    parser.add_argument("--vessel-area-min", type=int, default=VESSEL_AREA_MIN)

    args = parser.parse_args()

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

    print(f"Dilation kernel width: {args.dilation_kernel_width}")
    print(f"Dilation kernel height: {args.dilation_kernel_height}")
    print(f"Dilation kernel shape: {args.dilation_kernel_shape}")
    print(f"Erosion kernel width: {args.erosion_kernel_width}")
    print(f"Erosion kernel height: {args.erosion_kernel_height}")
    print(f"Vessel area minimum: {args.vessel_area_min}")

    process_folder(
        input_folder=str(input_folder),
        output_folder=str(output_folder),
        dilation_kernel_size=dilation_kernel_size,
        dilation_kernel_shape=dilation_kernel_shape,
        erosion_kernel_size=erosion_kernel_size,
        threshold_mode=args.threshold_mode,
        percentile=args.percentile,
        vessel_area_min=args.vessel_area_min
    )


if __name__ == "__main__":
    main()