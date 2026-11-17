"""PlantDoc（背景のある実環境の葉の画像）を、葉ごとに切り出して評価用フォルダに変換するスクリプト。

PlantDoc は Web から集めた畑・庭・手持ちなどの写真で、1枚の画像に複数の葉があり、
葉ごとに枠（バウンディングボックス）とクラスが付いている。
このスクリプトは枠ごとに葉を切り出し、学習データ（train / valid）と同じ

    <出力先>/<train|test>/<作物名>/<クラス名>/*.jpg

の構成で保存する。出力した test フォルダは、そのまま次のように使える。

    python tools/evaluate_models.py --data plantdoc_crops/test
    python tools/train_wandb.py ... --test plantdoc_crops/test/Pepper

使い方（CameraApp フォルダで実行）:
    # ダウンロード（約1GB）から変換までまとめて行う
    python tools/prepare_plantdoc.py --download

    # クラス名を自分の valid フォルダのフォルダ名に合わせる（おすすめ）
    python tools/prepare_plantdoc.py --download --like "C:\\Users\\fuzho\\Downloads\\archive (2)\\valid"

    # ダウンロード済みの zip や git clone したフォルダを使う
    python tools/prepare_plantdoc.py --src plantdoc_src\\PlantDoc-master.zip
    python tools/prepare_plantdoc.py --src PlantDoc-Object-Detection-Dataset

    # ピーマンだけ、test だけ
    python tools/prepare_plantdoc.py --download --crops Pepper --splits test

PlantDoc のクラスとアプリのクラスの対応:
    - 「〜 leaf」だけのクラス（例: Bell_pepper leaf）は healthy にする。
    - アプリのモデルにある病気はそのクラスに入れる（例: Bell_pepper leaf spot → Bacterial_spot）。
    - PlantDoc にないクラスは空のフォルダを作る。evaluate_models.py はフォルダの並び順で
      クラス番号を決めるので、モデルの出力数とフォルダ数をそろえるため。
    - トマトの、モデルにない病気（細菌性斑点病・セプトリア・モザイク・黄化葉巻・ハダニ）は
      <出力先>/extra/<split>/Tomato/ に分けて保存する（評価には入らない）。
    - ブルーベリー・ラズベリー・ダイズ・カボチャはアプリにないので使わない。

注意:
    - ライセンスは CC BY 4.0（出典の表記が必要）。
    - 一部の画像は、アノテーションの画像サイズと実際の画像サイズが違う。
      EXIF の回転で縦横が入れ替わっている場合は回転してから、縮小されている場合は枠を拡大縮小してから切り出す。
    - 画像のファイル名に Windows で使えない文字（? など）が含まれるので、zip は展開せずに直接読む。

必要なライブラリ: pillow
"""
import argparse
import collections
import csv
import io
import os
import re
import shutil
import sys
import urllib.request
import zipfile

from PIL import Image, ImageOps

ZIP_URL = ("https://codeload.github.com/pratikkayal/PlantDoc-Object-Detection-Dataset/"
           "zip/refs/heads/master")
DEFAULT_ZIP = os.path.join("plantdoc_src", "PlantDoc-master.zip")
SPLITS = {"train": ("TRAIN", "train_labels.csv"), "test": ("TEST", "test_labels.csv")}

# アプリの各モデルのクラス。(探すときのキーワード, --like を使わないときのフォルダ名)
# 既定のフォルダ名は PlantVillage（New Plant Diseases Dataset）の名前。
APP_CLASSES = {
    "Apple": [("scab", "Apple___Apple_scab"),
              ("black_rot", "Apple___Black_rot"),
              ("rust", "Apple___Cedar_apple_rust"),
              ("healthy", "Apple___healthy")],
    "Cherry": [("powdery", "Cherry_(including_sour)___Powdery_mildew"),
               ("healthy", "Cherry_(including_sour)___healthy")],
    "Corn": [("gray", "Corn_(maize)___Cercospora_leaf_spot Gray_leaf_spot"),
             ("rust", "Corn_(maize)___Common_rust_"),
             ("northern", "Corn_(maize)___Northern_Leaf_Blight"),
             ("healthy", "Corn_(maize)___healthy")],
    "Grape": [("black_rot", "Grape___Black_rot"),
              ("esca", "Grape___Esca_(Black_Measles)"),
              ("leaf_blight", "Grape___Leaf_blight_(Isariopsis_Leaf_Spot)"),
              ("healthy", "Grape___healthy")],
    "Peach": [("bacterial", "Peach___Bacterial_spot"),
              ("healthy", "Peach___healthy")],
    "Pepper": [("bacterial", "Pepper,_bell___Bacterial_spot"),
               ("healthy", "Pepper,_bell___healthy")],
    "Potato": [("early", "Potato___Early_blight"),
               ("late", "Potato___Late_blight"),
               ("healthy", "Potato___healthy")],
    "Strawberry": [("scorch", "Strawberry___Leaf_scorch"),
                   ("healthy", "Strawberry___healthy")],
    "Tomato": [("early", "Tomato___Early_blight"),
               ("late", "Tomato___Late_blight"),
               ("mold", "Tomato___Leaf_Mold"),
               ("healthy", "Tomato___healthy")],
}

# PlantDoc のクラス → (作物, キーワード)。キーワードが None はモデルにない病気（extra に保存）。
PLANTDOC_MAP = {
    "Apple leaf": ("Apple", "healthy"),
    "Apple Scab Leaf": ("Apple", "scab"),
    "Apple rust leaf": ("Apple", "rust"),
    "Cherry leaf": ("Cherry", "healthy"),
    "Corn Gray leaf spot": ("Corn", "gray"),
    "Corn rust leaf": ("Corn", "rust"),
    "Corn leaf blight": ("Corn", "northern"),
    "grape leaf": ("Grape", "healthy"),
    "grape leaf black rot": ("Grape", "black_rot"),
    "Peach leaf": ("Peach", "healthy"),
    "Bell_pepper leaf": ("Pepper", "healthy"),
    "Bell_pepper leaf spot": ("Pepper", "bacterial"),
    "Potato leaf": ("Potato", "healthy"),
    "Potato leaf early blight": ("Potato", "early"),
    "Potato leaf late blight": ("Potato", "late"),
    "Strawberry leaf": ("Strawberry", "healthy"),
    "Tomato leaf": ("Tomato", "healthy"),
    "Tomato Early blight leaf": ("Tomato", "early"),
    "Tomato leaf late blight": ("Tomato", "late"),
    "Tomato mold leaf": ("Tomato", "mold"),
    "Tomato leaf bacterial spot": ("Tomato", None),
    "Tomato Septoria leaf spot": ("Tomato", None),
    "Tomato leaf mosaic virus": ("Tomato", None),
    "Tomato leaf yellow virus": ("Tomato", None),
    "Tomato two spotted spider mites leaf": ("Tomato", None),
}


def norm(name):
    return re.sub(r"[^a-z0-9]+", "_", name.lower())


def safe_name(name):
    """Windows で使えない文字を _ に置き換える。"""
    name = re.sub(r'[<>:"/\\|?*%]', "_", name)
    return name.strip(" .") or "img"


def download(url, path):
    if os.path.exists(path):
        print(f"ダウンロード済み: {path}")
        return
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".part"
    print(f"ダウンロード中: {url}")
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r  {done / 1e6:.0f} / {total / 1e6:.0f} MB", end="", flush=True)
            else:
                print(f"\r  {done / 1e6:.0f} MB", end="", flush=True)
    print()
    os.replace(tmp, path)
    print(f"保存しました: {path}")


class Source:
    """zip ファイルか、展開済み（git clone した）フォルダから読む。"""

    def __init__(self, src):
        self.zip = None
        if os.path.isfile(src):
            self.zip = zipfile.ZipFile(src)
            names = self.zip.namelist()
            csvs = [n for n in names if n.endswith("/train_labels.csv") or n == "train_labels.csv"]
            if not csvs:
                sys.exit(f"zip の中に train_labels.csv が見つかりません: {src}")
            self.prefix = csvs[0][:-len("train_labels.csv")]
            self.members = set(names)
        elif os.path.isdir(src):
            self.root = src
            if not os.path.exists(os.path.join(src, "train_labels.csv")):
                sys.exit(f"フォルダに train_labels.csv が見つかりません: {src}")
        else:
            sys.exit(f"見つかりません: {src}（--download を付けるとダウンロードします）")

    def read(self, rel):
        if self.zip:
            name = self.prefix + rel
            if name not in self.members:
                return None
            return self.zip.read(name)
        path = os.path.join(self.root, *rel.split("/"))
        if not os.path.exists(path):
            return None
        with open(path, "rb") as f:
            return f.read()


def resolve_classes(like, crops):
    """作物ごとに {キーワード: フォルダ名} と、作るべき全フォルダ名を返す。"""
    result = {}
    for crop in crops:
        defs = APP_CLASSES[crop]
        like_dir = os.path.join(like, crop) if like else None
        if like_dir and os.path.isdir(like_dir):
            folders = sorted(d for d in os.listdir(like_dir)
                             if os.path.isdir(os.path.join(like_dir, d)))
            mapping = {}
            for kw, _ in defs:
                hits = [d for d in folders if kw in norm(d)]
                if len(hits) != 1:
                    sys.exit(f"[{crop}] {like_dir} の中で「{kw}」を含むフォルダが {len(hits)} 個あります"
                             f"（{folders}）。--like を外すと既定の名前を使います。")
                mapping[kw] = hits[0]
            if len(folders) != len(defs):
                print(f"[{crop}] 注意: {like_dir} のフォルダ数（{len(folders)}）が想定（{len(defs)}）と違います。")
            result[crop] = (mapping, folders)
        else:
            if like:
                print(f"[{crop}] {like_dir} がないので、既定のフォルダ名を使います。")
            result[crop] = ({kw: name for kw, name in defs}, [name for _, name in defs])
    return result


def open_image(data, ann_w, ann_h, strict=False):
    """画像を開き、アノテーションのサイズに合わせるための (画像, x倍率, y倍率) を返す。
    合わせられないときは (None, 0, 0)。strict=True のときは拡大縮小での合わせ込みをしない
    （同じファイル名に別の画像のアノテーションが混ざっている場合）。"""
    img = Image.open(io.BytesIO(data))
    img.load()
    w, h = img.size
    if (w, h) == (ann_w, ann_h):
        return img, 1.0, 1.0
    if strict:
        return None, 0, 0
    if ann_w <= 0 or ann_h <= 0:
        return img, 1.0, 1.0
    rotated = ImageOps.exif_transpose(img)
    if rotated.size == (ann_w, ann_h):
        return rotated, 1.0, 1.0
    if (h, w) == (ann_w, ann_h):
        # EXIF が残っていないが縦横が入れ替わっているもの。向きが分からないので使わない。
        return None, 0, 0
    return img, w / ann_w, h / ann_h


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--download", action="store_true", help=f"GitHub から zip（約1GB）をダウンロードする（保存先 {DEFAULT_ZIP}）")
    ap.add_argument("--src", default=None, help="PlantDoc の zip、または git clone したフォルダ")
    ap.add_argument("--out", default="plantdoc_crops", help="出力先フォルダ")
    ap.add_argument("--like", default=None,
                    help="クラスのフォルダ名を合わせる学習データのフォルダ（例: archive (2)\\valid）")
    ap.add_argument("--crops", nargs="*", default=list(APP_CLASSES), help="対象の作物（例: Pepper Tomato）")
    ap.add_argument("--splits", nargs="*", default=["train", "test"], choices=list(SPLITS),
                    help="PlantDoc の train / test のどちらを変換するか")
    ap.add_argument("--min_size", type=int, default=32, help="枠の短い辺がこれより小さい葉は捨てる（ピクセル）")
    ap.add_argument("--pad", type=float, default=0.0, help="枠の周りに足す余白（枠の幅・高さに対する割合。例: 0.1）")
    ap.add_argument("--clean", action="store_true", help="出力先を消してから作り直す")
    args = ap.parse_args()

    unknown = [c for c in args.crops if c not in APP_CLASSES]
    if unknown:
        sys.exit(f"知らない作物です: {unknown}（使えるもの: {list(APP_CLASSES)}）")

    src = args.src
    if args.download:
        src = src or DEFAULT_ZIP
        download(ZIP_URL, src)
    elif not src:
        src = DEFAULT_ZIP if os.path.exists(DEFAULT_ZIP) else None
        if not src:
            sys.exit("--download か --src を指定してください。")
    source = Source(src)

    classes = resolve_classes(args.like, args.crops)
    if args.clean and os.path.isdir(args.out):
        shutil.rmtree(args.out)

    manifest_rows = []
    counts = collections.Counter()
    skipped = collections.Counter()
    for split in args.splits:
        img_dir, csv_name = SPLITS[split]
        rows = list(csv.DictReader(io.StringIO(source.read(csv_name).decode("utf-8-sig"))))
        for crop in args.crops:
            for folder in classes[crop][1]:
                os.makedirs(os.path.join(args.out, split, crop, folder), exist_ok=True)

        # 同じファイル名で別の画像のアノテーションが混ざっていることがあるので、
        # (ファイル名, アノテーションの幅, 高さ) ごとにまとめる。
        by_file = collections.OrderedDict()
        sizes = collections.defaultdict(set)
        for r in rows:
            key = (r["filename"], int(r["width"]), int(r["height"]))
            by_file.setdefault(key, []).append(r)
            sizes[r["filename"]].add(key[1:])

        for (fname, ann_w, ann_h), boxes in by_file.items():
            boxes = [b for b in boxes if b["class"] in PLANTDOC_MAP
                     and PLANTDOC_MAP[b["class"]][0] in args.crops]
            if not boxes:
                continue
            data = source.read(f"{img_dir}/{fname}")
            if data is None:
                skipped["画像ファイルがない"] += len(boxes)
                continue
            try:
                img, sx, sy = open_image(data, ann_w, ann_h,
                                         strict=len(sizes[fname]) > 1)
            except Exception as e:
                print(f"読めない画像: {fname}（{e}）")
                skipped["画像が読めない"] += len(boxes)
                continue
            if img is None:
                skipped["画像とアノテーションが合わない"] += len(boxes)
                continue
            img = img.convert("RGB")
            W, H = img.size
            stem = safe_name(os.path.splitext(fname)[0])
            for k, b in enumerate(boxes):
                crop, kw = PLANTDOC_MAP[b["class"]]
                x0, y0 = float(b["xmin"]) * sx, float(b["ymin"]) * sy
                x1, y1 = float(b["xmax"]) * sx, float(b["ymax"]) * sy
                pw, ph = (x1 - x0) * args.pad, (y1 - y0) * args.pad
                x0, y0 = max(0, int(x0 - pw)), max(0, int(y0 - ph))
                x1, y1 = min(W, int(round(x1 + pw))), min(H, int(round(y1 + ph)))
                if min(x1 - x0, y1 - y0) < args.min_size:
                    skipped["枠が小さい"] += 1
                    continue
                if kw is None:
                    folder = safe_name(b["class"])
                    out_dir = os.path.join(args.out, "extra", split, crop, folder)
                else:
                    folder = classes[crop][0][kw]
                    out_dir = os.path.join(args.out, split, crop, folder)
                os.makedirs(out_dir, exist_ok=True)
                out_path = os.path.join(out_dir, f"{stem}_{k}.jpg")
                img.crop((x0, y0, x1, y1)).save(out_path, quality=95)
                counts[(split, crop, folder if kw else "extra: " + folder)] += 1
                manifest_rows.append([split, crop, folder, kw is None, b["class"], fname,
                                      x0, y0, x1, y1, os.path.relpath(out_path, args.out)])

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "manifest.csv"), "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["split", "crop", "class", "extra", "plantdoc_class", "source_file",
                    "x0", "y0", "x1", "y1", "path"])
        w.writerows(manifest_rows)

    print(f"\n保存先: {os.path.abspath(args.out)}")
    for split in args.splits:
        print(f"\n[{split}]")
        for crop in args.crops:
            print(f"  {crop}")
            for folder in classes[crop][1]:
                print(f"    {folder}: {counts[(split, crop, folder)]} 枚")
            for (s, c, f), n in sorted(counts.items()):
                if s == split and c == crop and f.startswith("extra: "):
                    print(f"    （評価に入らない）{f[7:]}: {n} 枚")
    if skipped:
        print("\n使わなかった葉: " + "、".join(f"{k} {v}" for k, v in skipped.items()))
    print("\n一覧: manifest.csv（どの画像のどの枠から切り出したか）")


if __name__ == "__main__":
    main()
