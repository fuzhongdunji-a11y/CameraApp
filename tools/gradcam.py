"""Grad-CAM によるヒートマップ作成スクリプト。

分類モデル（Keras の .h5）が画像のどこを見て判定したかを、元画像に重ねて保存する。
病斑ではなく背景や葉の縁を見ている場合、学習データや前処理の見直しが必要だと分かる。

使い方（例）:
    # フォルダ内の画像についてヒートマップを作る（サブフォルダも含む、クラスごとに先頭20枚まで）
    python tools/gradcam.py --h5 pepper_model_densenet_finetuned.h5 --images "C:\\...\\valid\\Pepper" --limit 20

    # 評価スクリプトの結果から、間違えた画像だけを対象にする
    python tools/gradcam.py --h5 pepper_model_densenet_finetuned.h5 --from-csv eval_out/Pepper_raw_predictions.csv --only-wrong

    # 正解した画像を、クラスごとに10枚ずつ対象にする（正しい場所を見て当てているかの確認）
    python tools/gradcam.py --h5 pepper_model.h5 --from-csv eval_out/Pepper_raw_predictions.csv --only-correct --limit 10 --out gradcam_correct

出力: <out>/<元のフォルダ名>/<ファイル名>_cam.png（左: 元画像、右: ヒートマップを重ねた画像）
      赤いほど、その判定に強く効いた場所。

モデルの構成は「前処理の層 → DenseNet121 → 判定の層」を想定している（学習スクリプトと同じ）。
画像は 0〜255 のまま渡す（モデル内部に Rescaling(1/255) があるため）。

必要なライブラリ: tensorflow, numpy, pillow, matplotlib
"""
import argparse
import csv
import os
import sys

import numpy as np
from PIL import Image

IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".bmp", ".webp")


def split_model(model, tf):
    """モデルを「backbone より前」「backbone」「backbone より後」に分ける。"""
    idx = None
    for i, layer in enumerate(model.layers):
        if isinstance(layer, tf.keras.Model):
            idx = i
    if idx is None:
        sys.exit("モデルの中に DenseNet121 などの入れ子のモデルが見つかりません。")
    backbone = model.layers[idx]
    pre = [l for l in model.layers[:idx] if not isinstance(l, tf.keras.layers.InputLayer)]
    post = model.layers[idx + 1:]
    if len(backbone.output.shape) != 4:
        sys.exit("backbone の出力が特徴マップ（4次元）ではありません。このスクリプトでは扱えない構成です。")
    return pre, backbone, post


def grad_cam(tf, pre, backbone, post, image, class_index=None):
    """image: (H, W, 3) の 0〜255 の配列。戻り値: (0〜1 のヒートマップ, 確率の配列, 対象クラス)"""
    x = tf.convert_to_tensor(image[None].astype(np.float32))
    for layer in pre:
        x = layer(x, training=False)
    with tf.GradientTape() as tape:
        feat = backbone(x, training=False)
        tape.watch(feat)
        y = feat
        for layer in post:
            y = layer(y, training=False)
        if class_index is None:
            class_index = int(tf.argmax(y[0]))
        score = y[:, class_index]
    grads = tape.gradient(score, feat)
    weights = tf.reduce_mean(grads, axis=(1, 2), keepdims=True)
    cam = tf.nn.relu(tf.reduce_sum(weights * feat, axis=-1))[0].numpy()
    if cam.max() > 0:
        cam = cam / cam.max()
    return cam, y[0].numpy(), class_index


def save_overlay(image, cam, title, path, alpha=0.45):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    h, w = image.shape[:2]
    cam_img = Image.fromarray(np.uint8(cam * 255)).resize((w, h), Image.BILINEAR)
    cam_big = np.asarray(cam_img, dtype=np.float32) / 255.0
    heat = plt.get_cmap("jet")(cam_big)[..., :3] * 255.0
    overlay = np.uint8(image * (1 - alpha) + heat * alpha)

    fig, axes = plt.subplots(1, 2, figsize=(8, 4.4))
    axes[0].imshow(np.uint8(image))
    axes[0].set_title("Input")
    axes[1].imshow(overlay)
    axes[1].set_title("Grad-CAM")
    for ax in axes:
        ax.axis("off")
    fig.suptitle(title, fontsize=10)
    fig.tight_layout()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fig.savefig(path, dpi=130)
    plt.close(fig)


def collect_images(args):
    """(画像パス, 正解ラベルまたは None) のリストを返す。"""
    items = []
    if args.from_csv:
        count = {}
        with open(args.from_csv, newline="", encoding="utf-8-sig") as fp:
            for row in csv.DictReader(fp):
                if args.only_wrong and row["true"] == row["pred"]:
                    continue
                if args.only_correct and row["true"] != row["pred"]:
                    continue
                # --limit は正解ラベルごとの枚数の上限
                count[row["true"]] = count.get(row["true"], 0) + 1
                if args.limit and count[row["true"]] > args.limit:
                    continue
                items.append((row["path"], row["true"]))
        return items
    if os.path.isfile(args.images):
        return [(args.images, None)]
    for root, _, files in sorted(os.walk(args.images)):
        picked = [f for f in sorted(files) if f.lower().endswith(IMAGE_EXTS)]
        if args.limit:
            picked = picked[:args.limit]
        label = os.path.basename(root) if root != args.images else None
        items += [(os.path.join(root, f), label) for f in picked]
    return items


def main():
    ap = argparse.ArgumentParser(description="Grad-CAM のヒートマップを作成する")
    ap.add_argument("--h5", required=True, help="Keras モデル（.h5 / .keras）のパス")
    ap.add_argument("--images", help="画像ファイル、または画像フォルダ（サブフォルダも対象）")
    ap.add_argument("--from-csv", help="evaluate_models.py が出力した *_predictions.csv")
    ap.add_argument("--only-wrong", action="store_true", help="--from-csv のうち、間違えた画像だけを対象にする")
    ap.add_argument("--only-correct", action="store_true", help="--from-csv のうち、正解した画像だけを対象にする")
    ap.add_argument("--class-index", type=int, default=None,
                    help="ヒートマップの対象クラス番号。省略するとモデルが予測したクラス")
    ap.add_argument("--limit", type=int, default=0, help="フォルダごと（CSV の場合は正解ラベルごと）の枚数の上限")
    ap.add_argument("--out", default="gradcam_out", help="保存先フォルダ")
    args = ap.parse_args()
    if args.only_wrong and args.only_correct:
        ap.error("--only-wrong と --only-correct は同時に指定できません。")
    if not args.images and not args.from_csv:
        ap.error("--images か --from-csv のどちらかを指定してください。")

    import tensorflow as tf

    model = tf.keras.models.load_model(args.h5, compile=False)
    pre, backbone, post = split_model(model, tf)
    size = model.input_shape[1] or 256
    print(f"backbone: {backbone.name} / 入力 {size}x{size} / 出力 {model.output_shape[-1]} クラス")

    items = collect_images(args)
    if not items:
        sys.exit("対象の画像がありません。")
    for n, (path, label) in enumerate(items, 1):
        image = np.asarray(Image.open(path).convert("RGB").resize((size, size), Image.BILINEAR),
                           dtype=np.float32)
        cam, probs, cls = grad_cam(tf, pre, backbone, post, image, args.class_index)
        pred = int(np.argmax(probs))
        title = f"pred: class {pred} ({probs[pred]:.2f})"
        if args.class_index is not None:
            title += f" / CAM: class {cls}"
        if label:
            title = f"true: {label} / " + title
        sub = label or "images"
        name = os.path.splitext(os.path.basename(path))[0] + "_cam.png"
        save_overlay(image, cam, title, os.path.join(args.out, sub, name))
        if n % 20 == 0 or n == len(items):
            print(f"  {n}/{len(items)} 枚", flush=True)
    print(f"保存先: {args.out}")


if __name__ == "__main__":
    main()
