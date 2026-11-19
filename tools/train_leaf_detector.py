"""PlantDoc の葉の枠で「leaf」1クラスの葉の検出モデルを学習し、tflite に書き出すスクリプト。

MediaPipe Model Maker（Apache 2.0）を使う。アプリで葉を切り出してから分類するための検出モデル。
ピーマンが写っている画像は、切り出しの効果を測るために学習から外し、pepper_holdout に保存する
（tools/evaluate_leaf_crop.py で使う）。

使い方（WSL / Linux、Python 3.11 の別環境で）:
    pip install mediapipe-model-maker "mediapipe==0.10.14" "setuptools<70"
    python tools/train_leaf_detector.py --src plantdoc_src/PlantDoc-master.zip --out leaf_out

    出力: leaf_out/voc/（変換した画像と枠）、leaf_out/model/leaf_detector.tflite

注意:
    - mediapipe はバージョンを 0.10.14 に固定する（固定しないと Model Maker と合わない版が入ることがある）。
    - Model Maker は Windows では動かない。
"""
import argparse, collections, csv, io, json, os, re, sys
from xml.sax.saxutils import escape

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from prepare_plantdoc import Source, open_image  # noqa: E402

MAX = 640  # 長い辺をこのピクセル数まで縮小して保存する


def convert(src, out):
    """PlantDoc を Pascal VOC（images/ と Annotations/）に変換する。"""
    S = Source(src)
    stats = collections.Counter()
    for split, (img_dir, csv_name) in {'train': ('TRAIN', 'train_labels.csv'), 'test': ('TEST', 'test_labels.csv')}.items():
        rows = list(csv.DictReader(io.StringIO(S.read(csv_name).decode('utf-8-sig'))))
        groups = collections.OrderedDict(); sizes = collections.defaultdict(set)
        for r in rows:
            k = (r['filename'], int(r['width']), int(r['height'])); groups.setdefault(k, []).append(r); sizes[r['filename']].add(k[1:])
        pepper_files = {r['filename'] for r in rows if r['class'].startswith('Bell_pepper')}
        for (fn, aw, ah), boxes in groups.items():
            hold = fn in pepper_files
            sub = f'pepper_holdout' if hold else ('det_train' if split == 'train' else 'det_val')
            data = S.read(f'{img_dir}/{fn}')
            if data is None: stats['missing'] += 1; continue
            try: img, sx, sy = open_image(data, aw, ah, strict=len(sizes[fn]) > 1)
            except Exception: stats['bad'] += 1; continue
            if img is None: stats['mismatch'] += 1; continue
            img = img.convert('RGB'); W, H = img.size
            sc = min(1.0, MAX / max(W, H)); img = img.resize((max(1, round(W*sc)), max(1, round(H*sc))))
            W2, H2 = img.size
            stem = re.sub(r'[^A-Za-z0-9_.-]', '_', os.path.splitext(fn)[0]) + f'_{aw}x{ah}'
            os.makedirs(f'{out}/{sub}/images', exist_ok=True); os.makedirs(f'{out}/{sub}/Annotations', exist_ok=True)
            objs = []
            for b in boxes:
                x0 = max(0, float(b['xmin'])*sx*sc); y0 = max(0, float(b['ymin'])*sy*sc)
                x1 = min(W2, float(b['xmax'])*sx*sc); y1 = min(H2, float(b['ymax'])*sy*sc)
                if x1 - x0 < 4 or y1 - y0 < 4: continue
                name = 'leaf'
                objs.append(f'<object><name>{name}</name><pclass>{escape(b["class"])}</pclass><bndbox><xmin>{int(x0)}</xmin><ymin>{int(y0)}</ymin><xmax>{int(x1)}</xmax><ymax>{int(y1)}</ymax></bndbox></object>')
            if not objs: stats['noobj'] += 1; continue
            img.save(f'{out}/{sub}/images/{stem}.jpg', quality=92)
            open(f'{out}/{sub}/Annotations/{stem}.xml', 'w').write(f'<annotation><filename>{stem}.jpg</filename><size><width>{W2}</width><height>{H2}</height><depth>3</depth></size>{"".join(objs)}</annotation>')
            stats[sub] += 1; stats[sub + '_boxes'] += len(objs)
    return dict(stats)


def train(voc, out, epochs, model_name, batch_size):
    from mediapipe_model_maker import object_detector
    train_data = object_detector.Dataset.from_pascal_voc_folder(f'{voc}/det_train', cache_dir=f'{out}/cache_train')
    val_data = object_detector.Dataset.from_pascal_voc_folder(f'{voc}/det_val', cache_dir=f'{out}/cache_val')
    print('学習', train_data.size, '枚 / 検証', val_data.size, '枚', flush=True)
    spec = getattr(object_detector.SupportedModels, model_name)
    hp = object_detector.HParams(export_dir=out, epochs=epochs, batch_size=batch_size)
    opt = object_detector.ObjectDetectorOptions(supported_model=spec, hparams=hp)
    model = object_detector.ObjectDetector.create(train_data=train_data, validation_data=val_data, options=opt)
    loss, coco = model.evaluate(val_data, batch_size=4)
    coco = {k: float(v) for k, v in coco.items()}
    print('検証の結果（COCO 指標）:', json.dumps(coco), flush=True)
    model.export_model('leaf_detector.tflite')
    with open(os.path.join(out, 'val_metrics.json'), 'w') as f:
        json.dump(coco, f, indent=1)
    print('書き出し:', os.path.join(out, 'leaf_detector.tflite'))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--src', required=True, help='PlantDoc の zip か git clone したフォルダ')
    ap.add_argument('--out', default='leaf_out')
    ap.add_argument('--epochs', type=int, default=30)
    ap.add_argument('--batch_size', type=int, default=8)
    ap.add_argument('--model', default='MOBILENET_MULTI_AVG',
                    help='MOBILENET_MULTI_AVG（入力256）/ MOBILENET_MULTI_AVG_I384（入力384）など')
    ap.add_argument('--skip_convert', action='store_true', help='変換済みの <out>/voc を使う')
    args = ap.parse_args()
    voc = os.path.join(args.out, 'voc')
    if not args.skip_convert:
        print('変換:', convert(args.src, voc))
    train(voc, os.path.join(args.out, 'model'), args.epochs, args.model, args.batch_size)


if __name__ == '__main__':
    main()
