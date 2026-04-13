import cv2
import numpy as np
from skimage.measure import label, regionprops_table
import pandas as pd
from pathlib import Path
import argparse
import sys

sys.stdout.reconfigure(line_buffering=True)


def save_contours_csv(refined_contours, csv_path):
    rows = []
    for contour_id, cnt in enumerate(refined_contours):
        cnt = cnt.reshape(-1, 2)
        for point_order, (x, y) in enumerate(cnt):
            rows.append([contour_id, point_order, float(x), float(y)])

    df = pd.DataFrame(rows, columns=["contour_id", "point_order", "x", "y"])
    df.to_csv(csv_path, index=False)


def process_image(image_path, output_folder, dilation_kernel_size, dilation_kernel_shape):
    base_name = Path(image_path).stem
    image_output_dir = Path(output_folder) / base_name
    image_output_dir.mkdir(parents=True, exist_ok=True)

    img_bgr = cv2.imread(image_path)
    if img_bgr is None:
        raise ValueError(f"Could not load image: {image_path}")

    img_bgr_float = img_bgr.astype(np.float32) / 255.0

    K = 1 - np.max(img_bgr_float, axis=2)
    Y = (1 - img_bgr_float[:, :, 0] - K) / (1 - K + 1e-10)
    Y_channel = (Y * 255).astype(np.uint8)

    del img_bgr_float, K, Y

    _, binary = cv2.threshold(Y_channel, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    del Y_channel

    kernel_dilate = cv2.getStructuringElement(dilation_kernel_shape, dilation_kernel_size)
    dilated = cv2.dilate(binary, kernel_dilate, iterations=1)
    del binary, kernel_dilate

    kernel_erode = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    eroded = cv2.erode(dilated, kernel_erode, iterations=2)
    del dilated, kernel_erode

    contours, _ = cv2.findContours(eroded, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    del eroded

    refined_contours = []
    for cnt in contours:
        epsilon = 0.0000001 * cv2.arcLength(cnt, True)
        approx = cv2.approxPolyDP(cnt, epsilon, True)
        if len(approx) >= 3:
            refined_contours.append(approx)

    filled_mask = np.zeros(img_bgr.shape[:2], dtype=np.uint8)
    cv2.drawContours(filled_mask, refined_contours, -1, 255, -1)

    label_img = label(filled_mask > 0)
    props = regionprops_table(
        label_img,
        img_bgr,
        properties=[
            "area",
            "axis_major_length",
            "axis_minor_length",
            "eccentricity",
            "orientation"
        ]
    )
    measurements_df = pd.DataFrame(props)

    contours_csv = image_output_dir / "vessel_contours.csv"
    save_contours_csv(refined_contours, contours_csv)

    measurements_csv = image_output_dir / f"{base_name}_measurements.csv"
    measurements_df.to_csv(measurements_csv, index=True)

    binary_path = image_output_dir / f"{base_name}_binary.png"
    cv2.imwrite(str(binary_path), filled_mask)

    img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    overlay = np.zeros_like(img_rgb)
    overlay[:, :, 1] = filled_mask
    filled_overlay = cv2.addWeighted(img_rgb, 0.7, overlay, 0.3, 0)
    overlay_bgr = cv2.cvtColor(filled_overlay, cv2.COLOR_RGB2BGR)

    overlay_path = image_output_dir / f"{base_name}_overlay.png"
    cv2.imwrite(str(overlay_path), overlay_bgr)

    print(f"Contours CSV: {contours_csv}")
    print(f"Measurements CSV: {measurements_csv}")
    print(f"Binary mask: {binary_path}")
    print(f"Overlay: {overlay_path}")
    print(f"Total vessels: {len(measurements_df)}")
    print("VESSEL_SEGMENTATION_SUCCESS")


def process_folder(input_folder, output_folder, dilation_kernel_size, dilation_kernel_shape):
    input_path = Path(input_folder)
    png_files = sorted(input_path.glob("*.png"))

    if not png_files:
        print(f"No PNG files found in {input_folder}")
        return

    print(f"Found {len(png_files)} PNG files to process")

    for png_file in png_files:
        process_image(
            str(png_file),
            output_folder,
            dilation_kernel_size,
            dilation_kernel_shape
        )


def main():
    parser = argparse.ArgumentParser(description="QuPath vessel segmentation with contour export")

    parser.add_argument("input_folder", type=str, help="Path to input folder containing PNG images")
    parser.add_argument("output_folder", type=str, help="Path to output folder")

    parser.add_argument("--dilation-kernel-width", type=int, default=21)
    parser.add_argument("--dilation-kernel-height", type=int, default=21)
    parser.add_argument(
        "--dilation-kernel-shape",
        type=str,
        default="ELLIPSE",
        choices=["ELLIPSE", "RECT", "CROSS"]
    )

    args = parser.parse_args()

    if args.dilation_kernel_shape == "ELLIPSE":
        dilation_kernel_shape = cv2.MORPH_ELLIPSE
    elif args.dilation_kernel_shape == "RECT":
        dilation_kernel_shape = cv2.MORPH_RECT
    elif args.dilation_kernel_shape == "CROSS":
        dilation_kernel_shape = cv2.MORPH_CROSS
    else:
        raise ValueError(f"Unsupported kernel shape: {args.dilation_kernel_shape}")

    dilation_kernel_size = (
        args.dilation_kernel_width,
        args.dilation_kernel_height
    )

    input_folder = Path(args.input_folder)
    if not input_folder.exists():
        raise ValueError(f"Input folder does not exist: {input_folder}")

    output_folder = Path(args.output_folder)
    output_folder.mkdir(parents=True, exist_ok=True)

    print(f"Dilation kernel width: {args.dilation_kernel_width}")
    print(f"Dilation kernel height: {args.dilation_kernel_height}")
    print(f"Dilation kernel shape: {args.dilation_kernel_shape}")

    process_folder(
        str(input_folder),
        str(output_folder),
        dilation_kernel_size,
        dilation_kernel_shape
    )


if __name__ == "__main__":
    main()