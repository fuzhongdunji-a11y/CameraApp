"""分類モデル（tflite）の精度評価スクリプト。

アプリに入っている *_model.tflite を、検証用画像フォルダで評価する。
作物ごとに 正解率・適合率・再現率・F1・混同行列 と、PC上での推論時間を出力する。

使い方（例）:
    # ピーマンだけ評価
    python tools/evaluate_models.py --data "C:\\Users\\fuzho\\Downloads\\archive (2)\\valid" --crop Pepper

    # データフォルダにある作物をすべて評価
    python tools/evaluate_models.py --data "C:\\Users\\fuzho\\Downloads\\archive (2)\\valid"

    # 修正前のアプリと同じ入力（/255 の二重正規化）で評価して比較する
    python tools/evaluate_models.py --data "...\\valid" --crop Pepper --preprocess double

フォルダ構成:
    <data>/<作物名>/<クラス名>/*.jpg     （学習時の image_dataset_from_directory と同じ構成）

クラスの番号は Keras と同じく、クラスのフォルダ名を文字コード順に並べた順番になる。
アプリと同じく「healthy を含むフォルダ = 健康、それ以外 = 病気」の2値でも集計する。

必要なライブラリ: numpy, pillow, tensorflow（または ai-edge-litert）。matplotlib があれば混同行列の画像も保存する。
"""
import argparse
import csv
import os
import sys
import time

import numpy as np
from PIL import Image

IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
DEFAULT_MODEL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                 "..", "app", "src", "main", "assets")


def load_interpreter(model_path):
    try:
        import tensorflow as tf
        return tf.lite.Interpreter(model_path=model_path)
    except ImportError:
        from ai_edge_litert.interpreter import Interpreter
        return Interpreter(model_path=model_path)


def find_model(model_dir, crop):
    """作物フォルダ名（例: Pepper）に対応する tflite（例: pepper_model.tflite）を探す。"""
    crop_l = crop.lower()
    for name in sorted(os.listdir(model_dir)):
        if not name.lower().endswith("_model.tflite"):
            continue
        key = name.lower()[:-len("_model.tflite")]
        if crop_l.startswith(key) or key.startswith(crop_l):
            return os.path.join(model_dir, name)
    return None


def list_images(crop_dir):
    classes = sorted(d for d in os.listdir(crop_dir)
                     if os.path.isdir(os.path.join(crop_dir, d)))
    items = []
    for idx, cls in enumerate(classes):
        for root, _, files in os.walk(os.path.join(crop_dir, cls)):
            for f in sorted(files):
                if f.lower().endswith(IMAGE_EXTS):
                    items.append((os.path.join(root, f), idx))
    return classes, items


def prf(tp, fp, fn):
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return precision, recall, f1


def save_confusion_png(cm, labels, title, path):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return False
    fig, ax = plt.subplots(figsize=(1.6 * len(labels) + 2.5, 1.6 * len(labels) + 2))
    ax.imshow(cm, cmap="Blues")
    ax.set_xticks(range(len(labels)))
    ax.set_yticks(range(len(labels)))
    ax.set_xticklabels(labels, rotation=45, ha="right")
    ax.set_yticklabels(labels)
    ax.set_xlabel("Predicted")
    ax.set_ylabel("True")
    ax.set_title(title)
    thresh = cm.max() / 2 if cm.max() else 0
    for i in range(len(labels)):
        for j in range(len(labels)):
            ax.text(j, i, str(cm[i, j]), ha="center", va="center",
                    color="white" if cm[i, j] > thresh else "black")
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)
    return True


def evaluate_crop(crop, crop_dir, model_path, preprocess, out_dir, limit):
    classes, items = list_images(crop_dir)
    if not items:
        print(f"[{crop}] 画像が見つかりません: {crop_dir}")
        return None
    if limit:
        # クラスごとに先頭 limit 枚だけ使う（動作確認用）
        kept, count = [], {}
        for path, idx in items:
            if count.get(idx, 0) < limit:
                kept.append((path, idx))
                count[idx] = count.get(idx, 0) + 1
        items = kept

    interp = load_interpreter(model_path)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    size = int(inp["shape"][1])
    n_out = int(out["shape"][1])

    print(f"\n===== {crop} =====")
    print(f"モデル: {os.path.basename(model_path)}（出力 {n_out} クラス、入力 {size}x{size}）")
    print("クラス番号とフォルダ名:")
    for i, c in enumerate(classes):
        print(f"  {i}: {c}（{sum(1 for _, k in items if k == i)} 枚）")
    if len(classes) != n_out:
        print(f"[{crop}] フォルダ数（{len(classes)}）とモデルの出力数（{n_out}）が合いません。評価を飛ばします。")
        return None

    healthy_idx = [i for i, c in enumerate(classes) if "healthy" in c.lower()]
    if len(healthy_idx) != 1:
        print(f"[{crop}] healthy を含むフォルダが1つではありません。2値の集計は行いません。")
        healthy = None
    else:
        healthy = healthy_idx[0]
        if healthy != n_out - 1:
            print(f"[{crop}] 注意: healthy が最後のクラスではありません（番号 {healthy}）。"
                  "アプリは最後のクラスを healthy として扱っています。")

    scale = 1.0 / 255.0 if preprocess == "double" else 1.0
    cm = np.zeros((n_out, n_out), dtype=int)
    rows = []
    elapsed = 0.0
    for n, (path, true_idx) in enumerate(items, 1):
        img = Image.open(path).convert("RGB").resize((size, size), Image.BILINEAR)
        x = (np.asarray(img, dtype=np.float32) * scale)[None]
        t0 = time.perf_counter()
        interp.set_tensor(inp["index"], x)
        interp.invoke()
        probs = interp.get_tensor(out["index"])[0]
        elapsed += time.perf_counter() - t0
        pred = int(np.argmax(probs))
        cm[true_idx, pred] += 1
        rows.append([path, classes[true_idx], classes[pred]] + [f"{p:.4f}" for p in probs])
        if n % 200 == 0:
            print(f"  {n}/{len(items)} 枚", flush=True)

    total = int(cm.sum())
    accuracy = float(np.trace(cm)) / total
    print(f"\n正解率（{n_out}クラス）: {accuracy:.4f}（{int(np.trace(cm))}/{total}）")
    print("クラス別:")
    macro = []
    for i, c in enumerate(classes):
        tp = cm[i, i]
        p, r, f = prf(tp, cm[:, i].sum() - tp, cm[i, :].sum() - tp)
        macro.append(f)
        print(f"  {c}: 適合率 {p:.4f} / 再現率 {r:.4f} / F1 {f:.4f}")
    print(f"マクロ平均F1: {np.mean(macro):.4f}")
    print("混同行列（行=正解、列=予測）:")
    print(cm)

    summary = {"crop": crop, "model": os.path.basename(model_path), "preprocess": preprocess,
               "images": total, "classes": n_out, "accuracy": round(accuracy, 4),
               "macro_f1": round(float(np.mean(macro)), 4),
               "ms_per_image_pc": round(elapsed / total * 1000, 1)}

    if healthy is not None:
        # アプリと同じ判定: healthy の確率が 0.5 未満なら病気
        tp = fp = fn = tn = 0
        for row, (_, true_idx) in zip(rows, items):
            p_healthy = float(row[3 + healthy])
            pred_dis = p_healthy < 0.5
            true_dis = true_idx != healthy
            tp += pred_dis and true_dis
            fp += pred_dis and not true_dis
            fn += (not pred_dis) and true_dis
            tn += (not pred_dis) and not true_dis
        p, r, f = prf(tp, fp, fn)
        print(f"\n2値（病気を陽性）: 正解率 {(tp + tn) / total:.4f} / 適合率 {p:.4f} / "
              f"再現率 {r:.4f} / F1 {f:.4f}")
        print(f"  病気→病気 {tp}、病気→健康（見逃し） {fn}、健康→病気（誤検出） {fp}、健康→健康 {tn}")
        summary.update({"bin_accuracy": round((tp + tn) / total, 4), "bin_precision": round(p, 4),
                        "bin_recall": round(r, 4), "bin_f1": round(f, 4),
                        "tp": tp, "fn": fn, "fp": fp, "tn": tn})

    print(f"推論時間（このPC）: 1枚あたり {summary['ms_per_image_pc']} ms")

    os.makedirs(out_dir, exist_ok=True)
    tag = f"{crop}_{preprocess}"
    with open(os.path.join(out_dir, f"{tag}_predictions.csv"), "w", newline="", encoding="utf-8-sig") as fp_:
        w = csv.writer(fp_)
        w.writerow(["path", "true", "pred"] + [f"p_{c}" for c in classes])
        w.writerows(rows)
    save_confusion_png(cm, classes, f"{crop} ({preprocess})",
                       os.path.join(out_dir, f"{tag}_confusion.png"))
    return summary


def main():
    ap = argparse.ArgumentParser(description="分類モデル（tflite）の精度評価")
    ap.add_argument("--data", required=True, help="検証データのフォルダ（この下に作物名のフォルダがある）")
    ap.add_argument("--crop", help="評価する作物のフォルダ名（例: Pepper）。省略するとすべて評価")
    ap.add_argument("--model", help="tflite のパス。省略すると app/src/main/assets から作物名で探す")
    ap.add_argument("--model-dir", default=DEFAULT_MODEL_DIR, help="tflite を探すフォルダ")
    ap.add_argument("--preprocess", choices=["raw", "double"], default="raw",
                    help="raw: 0〜255のまま渡す（修正後のアプリ）。double: /255 してから渡す（修正前のアプリ）")
    ap.add_argument("--limit", type=int, default=0, help="クラスごとに使う枚数の上限（動作確認用）")
    ap.add_argument("--out", default="eval_out", help="結果の保存先フォルダ")
    args = ap.parse_args()

    if args.crop:
        crops = [args.crop]
    else:
        crops = sorted(d for d in os.listdir(args.data) if os.path.isdir(os.path.join(args.data, d)))

    summaries = []
    for crop in crops:
        crop_dir = os.path.join(args.data, crop)
        if not os.path.isdir(crop_dir):
            print(f"[{crop}] フォルダがありません: {crop_dir}")
            continue
        model_path = args.model if (args.model and args.crop) else find_model(args.model_dir, crop)
        if not model_path or not os.path.isfile(model_path):
            print(f"[{crop}] 対応する tflite が見つかりません。--model で指定してください。")
            continue
        s = evaluate_crop(crop, crop_dir, model_path, args.preprocess, args.out, args.limit)
        if s:
            summaries.append(s)

    if not summaries:
        sys.exit("評価できた作物がありません。")
    keys = []
    for s in summaries:
        keys += [k for k in s if k not in keys]
    path = os.path.join(args.out, f"summary_{args.preprocess}.csv")
    with open(path, "w", newline="", encoding="utf-8-sig") as fp_:
        w = csv.DictWriter(fp_, fieldnames=keys)
        w.writeheader()
        w.writerows(summaries)
    print(f"\nまとめ: {path}")


if __name__ == "__main__":
    main()
