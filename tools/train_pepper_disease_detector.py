"""ピーマンの葉を「病気（pepper_spot）／健康（pepper_healthy）」の2クラスで検出するモデルを
MediaPipe Model Maker で学習し、今のアプリの方法と正答率・見逃しを比べるスクリプト。

学習データ:
    - PlantVillage のピーマン（Pepper,_bell___Bacterial_spot / Pepper,_bell___healthy）。
      単色背景で葉が1枚なので、色から葉の外接枠を自動で付ける。
    - PlantDoc のピーマン（tools/train_leaf_detector.py が作る pepper_holdout）。写真単位で k 分割し、
      評価する1分割以外を学習に使う（背景のある写真を増やすため3倍にして入れる）。

比べる方法（どれも同じ PlantDoc の写真で、k 分割の交差検証）:
    A  画像全体 → 分類モデル（今のアプリ）
    B  葉の検出モデルで葉を切り出し → 分類モデル（検出した葉の平均）
    C  この2クラス検出モデル（病気の最高スコア）
    C2 この2クラス検出モデル（病気 ÷（病気 + 健康））
    E  B と C2 の平均
    しきい値 0.2〜0.5 ごとに、正答率・見逃し・誤検出を出す（見逃しを減らしたいときはしきい値を下げる）。

使い方（WSL の GPU 環境。Python 3.11）:
    # 1. 環境（初回だけ）
    pip install mediapipe-model-maker "mediapipe==0.10.14" "setuptools<70" opencv-python-headless
    #    GPU を使うには TensorFlow 2.15 の GPU 版:  pip install "tensorflow[and-cuda]==2.15.1"
    # 2. PlantVillage のピーマンだけ取得（約80MB）
    git clone --depth 1 --filter=blob:none --sparse https://github.com/spMohanty/PlantVillage-Dataset pv
    (cd pv && git sparse-checkout set "raw/color/Pepper,_bell___Bacterial_spot" "raw/color/Pepper,_bell___healthy")
    # 3. 葉の検出モデルと pepper_holdout（まだなら）
    python tools/train_leaf_detector.py --src plantdoc_src/PlantDoc-master.zip --out leaf_out
    # 4. この学習と比較
    python tools/train_pepper_disease_detector.py --holdout leaf_out/voc/pepper_holdout \\
        --pv pv/raw/color --leaf_detector leaf_out/model/leaf_detector.tflite \\
        --classifier app/src/main/assets/pepper_model.tflite --out dis_out --n_pv 1000 --epochs 30

    出力: dis_out/fold*/model/det.tflite（分割ごとの検出モデル）、dis_out/results.txt（比較の表）
          --final を付けると、PlantDoc を全部使った最終モデル dis_out/final/model/det.tflite も作る。

注意:
    - PlantDoc で学習し直した分類モデル（71c4ly1v など）を --classifier に使うと、評価用の写真が
      その学習に含まれているため、A・B の結果が良く出すぎる。
"""
import argparse, glob, json, os, random, shutil, xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

CLS = {'Bell_pepper leaf spot': 'pepper_spot', 'Bell_pepper leaf': 'pepper_healthy'}
PV_CLASSES = [('Pepper,_bell___Bacterial_spot', 'pepper_spot'), ('Pepper,_bell___healthy', 'pepper_healthy')]


def write_voc(d, stem, img, objs):
    os.makedirs(f'{d}/images', exist_ok=True)
    os.makedirs(f'{d}/Annotations', exist_ok=True)
    img.save(f'{d}/images/{stem}.jpg', quality=92)
    W, H = img.size
    o = ''.join(f'<object><name>{c}</name><bndbox><xmin>{x0}</xmin><ymin>{y0}</ymin><xmax>{x1}</xmax>'
                f'<ymax>{y1}</ymax></bndbox></object>' for c, (x0, y0, x1, y1) in objs)
    with open(f'{d}/Annotations/{stem}.xml', 'w') as f:
        f.write(f'<annotation><filename>{stem}.jpg</filename><size><width>{W}</width><height>{H}</height>'
                f'<depth>3</depth></size>{o}</annotation>')


def pv_box(a):
    """PlantVillage（単色背景）の葉の外接枠を色から取る。取れなければ画像全体。"""
    import cv2
    hsv = cv2.cvtColor(a, cv2.COLOR_RGB2HSV)
    m = ((hsv[..., 1] > 50) & (hsv[..., 2] > 30)).astype(np.uint8) * 255
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    n, lab, st, _ = cv2.connectedComponentsWithStats(m)
    full = (2, 2, a.shape[1] - 2, a.shape[0] - 2)
    if n < 2:
        return full
    k = 1 + np.argmax(st[1:, cv2.CC_STAT_AREA])
    x, y, w, h = st[k, :4]
    if w * h < 0.1 * a.shape[0] * a.shape[1]:
        return full
    return (int(x), int(y), int(x + w), int(y + h))


def load_plantdoc(holdout):
    items = []
    for xp in sorted(glob.glob(f'{holdout}/Annotations/*.xml')):
        r = ET.parse(xp).getroot()
        stem = os.path.splitext(os.path.basename(xp))[0]
        objs = [(CLS[o.find('pclass').text], tuple(int(o.find('bndbox/' + k).text) for k in ('xmin', 'ymin', 'xmax', 'ymax')))
                for o in r.findall('object') if o.find('pclass').text in CLS]
        if objs:
            items.append((stem, f'{holdout}/images/{stem}.jpg', objs))
    return items


def prepare(args):
    random.seed(args.seed)
    pd_items = load_plantdoc(args.holdout)
    random.shuffle(pd_items)
    folds = [pd_items[i::args.folds] for i in range(args.folds)]
    pv_items = []
    for c, name in PV_CLASSES:
        fs = sorted(glob.glob(f'{args.pv}/{c}/*'))
        random.shuffle(fs)
        pv_items += [(f, name) for f in fs[:args.n_pv]]
    pv_boxes = {}
    for f, _ in pv_items:
        pv_boxes[f] = pv_box(np.asarray(Image.open(f).convert('RGB')))
    targets = [(f'fold{k}', k) for k in range(args.folds)] + ([('final', None)] if args.final else [])
    for name, k in targets:
        d = os.path.join(args.out, name)
        shutil.rmtree(d, ignore_errors=True)
        for f, c in pv_items:
            write_voc(f'{d}/train', 'pv_' + os.path.splitext(os.path.basename(f))[0][:40], Image.open(f).convert('RGB'), [(c, pv_boxes[f])])
        for j in range(args.folds):
            for stem, ip, objs in folds[j]:
                img = Image.open(ip).convert('RGB')
                if j == k:
                    write_voc(f'{d}/val', stem, img, objs)
                else:
                    for rep in range(args.pd_repeat):
                        write_voc(f'{d}/train', f'{stem}_r{rep}', img, objs)
        if k is None:  # 最終モデルの検証は分割0（学習にも入っているので参考値）
            for stem, ip, objs in folds[0]:
                write_voc(f'{d}/val', stem, Image.open(ip).convert('RGB'), objs)
        print(name, '学習', len(os.listdir(f'{d}/train/images')), '枚 / 検証', len(os.listdir(f'{d}/val/images')), '枚', flush=True)
    return [t[0] for t in targets]


def train(d, args):
    from mediapipe_model_maker import object_detector
    tr = object_detector.Dataset.from_pascal_voc_folder(f'{d}/train', cache_dir=f'{d}/cache_tr')
    va = object_detector.Dataset.from_pascal_voc_folder(f'{d}/val', cache_dir=f'{d}/cache_va')
    hp = object_detector.HParams(export_dir=f'{d}/model', epochs=args.epochs, batch_size=args.batch_size)
    opt = object_detector.ObjectDetectorOptions(supported_model=getattr(object_detector.SupportedModels, args.model), hparams=hp)
    m = object_detector.ObjectDetector.create(train_data=tr, validation_data=va, options=opt)
    _, coco = m.evaluate(va, batch_size=4)
    print(d, '検証（COCO 指標）:', json.dumps({k: round(float(v), 3) for k, v in coco.items()}), flush=True)
    m.export_model('det.tflite')


def evaluate(args):
    import mediapipe as mp
    import tensorflow as tf
    from mediapipe.tasks import python as mpt
    from mediapipe.tasks.python import vision

    def mkdet(p, n):
        return vision.ObjectDetector.create_from_options(vision.ObjectDetectorOptions(
            base_options=mpt.BaseOptions(model_asset_path=p), max_results=n, score_threshold=0.01))

    def run(det, img):
        return det.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.asarray(img))).detections

    leaf = mkdet(args.leaf_detector, 10)
    it = tf.lite.Interpreter(model_path=args.classifier)
    it.allocate_tensors()
    ii, oo = it.get_input_details()[0], it.get_output_details()[0]
    S = ii['shape'][1]

    def p_dis(img):  # 分類モデルの出力0 = Bacterial spot、1 = healthy
        a = np.array(img.resize((S, S), Image.BILINEAR), np.float32)[None]
        it.set_tensor(ii['index'], a)
        it.invoke()
        return float(it.get_tensor(oo['index'])[0][0])

    rows = []
    for k in range(args.folds):
        dd = mkdet(f'{args.out}/fold{k}/model/det.tflite', 25)
        for xp in sorted(glob.glob(f'{args.out}/fold{k}/val/Annotations/*.xml')):
            r = ET.parse(xp).getroot()
            img = Image.open(xp.replace('Annotations', 'images').replace('.xml', '.jpg')).convert('RGB')
            y = int(any(o.find('name').text == 'pepper_spot' for o in r.findall('object')))  # 1 = 病気
            a = p_dis(img)
            good = [d for d in run(leaf, img) if d.categories[0].score >= 0.3][:5]
            ps = [p_dis(img.crop((d.bounding_box.origin_x, d.bounding_box.origin_y,
                                  d.bounding_box.origin_x + d.bounding_box.width, d.bounding_box.origin_y + d.bounding_box.height)))
                  for d in good if d.bounding_box.width >= 16 and d.bounding_box.height >= 16]
            b = float(np.mean(ps)) if ps else a
            dets = run(dd, img)
            sp = max([d.categories[0].score for d in dets if d.categories[0].category_name == 'pepper_spot'] + [0.0])
            he = max([d.categories[0].score for d in dets if d.categories[0].category_name == 'pepper_healthy'] + [0.0])
            c2 = sp / (sp + he) if sp + he > 0 else 0.5
            rows.append(dict(fold=k, file=os.path.basename(xp), y=y, A=a, B=b, C=sp, C2=c2, E=(b + c2) / 2))
    with open(f'{args.out}/eval_rows.json', 'w') as f:
        json.dump(rows, f, indent=1)
    names = {'A': 'A 画像全体 → 分類モデル（今のアプリ）', 'B': 'B 葉を切り出し → 分類モデル（平均）',
             'C': 'C 病気/健康の検出モデル（病気の最高スコア）', 'C2': 'C2 病気/健康の検出モデル（病気÷(病気+健康)）',
             'E': 'E B と C2 の平均'}
    y = np.array([r['y'] for r in rows])
    lines = [f'写真 {len(y)} 枚（病気 {y.sum()} / 健康 {(1 - y).sum()}）、{args.folds} 分割の交差検証']
    for key, nm in names.items():
        s = np.array([r[key] for r in rows])
        lines.append(nm)
        for t in (0.2, 0.3, 0.4, 0.5):
            p = s >= t
            lines.append(f'   しきい値 {t}: 正答率 {(p == y).mean():.1%}  見逃し {int(((~p) & (y == 1)).sum())}/{y.sum()}'
                         f'  誤検出 {int((p & (y == 0)).sum())}/{(1 - y).sum()}')
    text = '\n'.join(lines)
    print(text)
    with open(f'{args.out}/results.txt', 'w') as f:
        f.write(text + '\n')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--holdout', required=True, help='train_leaf_detector.py が作った pepper_holdout')
    ap.add_argument('--pv', required=True, help='PlantVillage の raw/color フォルダ')
    ap.add_argument('--leaf_detector', required=True, help='葉の検出モデル（leaf_detector.tflite）')
    ap.add_argument('--classifier', required=True, help='ピーマンの分類モデル（tflite）')
    ap.add_argument('--out', default='dis_out')
    ap.add_argument('--folds', type=int, default=3)
    ap.add_argument('--n_pv', type=int, default=1000, help='PlantVillage から各クラス何枚使うか')
    ap.add_argument('--pd_repeat', type=int, default=3, help='PlantDoc の写真を何回入れるか')
    ap.add_argument('--epochs', type=int, default=30)
    ap.add_argument('--batch_size', type=int, default=8)
    ap.add_argument('--model', default='MOBILENET_MULTI_AVG')
    ap.add_argument('--seed', type=int, default=0)
    ap.add_argument('--final', action='store_true', help='PlantDoc を全部使った最終モデルも作る')
    ap.add_argument('--skip_train', action='store_true', help='学習済みの fold*/model を使って評価だけする')
    args = ap.parse_args()
    if not args.skip_train:
        for name in prepare(args):
            train(os.path.join(args.out, name), args)
    evaluate(args)


if __name__ == '__main__':
    main()
