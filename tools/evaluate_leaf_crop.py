"""画像全体で分類する場合と、葉を検出して切り出してから分類する場合の正答率を比べるスクリプト。

tools/train_leaf_detector.py が作った pepper_holdout（検出モデルの学習に使っていないピーマンの写真）を使う。
写真に病気の葉が1枚でもあれば「病気」、なければ「健康」を正解とする。

使い方:
    python tools/evaluate_leaf_crop.py leaf_out/voc/pepper_holdout leaf_out/model/leaf_detector.tflite \
        app/src/main/assets/pepper_model.tflite [検出のしきい値 0.3]

分類モデルは出力0 = Bacterial spot、出力1 = healthy の2クラスを想定（アプリのピーマンのモデル）。
"""
import sys, glob, os, json, collections, xml.etree.ElementTree as ET
import numpy as np, tensorflow as tf, mediapipe as mp
from PIL import Image
from mediapipe.tasks import python as mpt
from mediapipe.tasks.python import vision
holdout, det_path, cls_path = sys.argv[1], sys.argv[2], sys.argv[3]
SCORE = float(sys.argv[4]) if len(sys.argv) > 4 else 0.3
det = vision.ObjectDetector.create_from_options(vision.ObjectDetectorOptions(
    base_options=mpt.BaseOptions(model_asset_path=det_path), max_results=10, score_threshold=0.05))
it = tf.lite.Interpreter(model_path=cls_path); it.allocate_tensors()
ii, oo = it.get_input_details()[0], it.get_output_details()[0]; S = ii['shape'][1]
def p_dis(img):  # 出力0 = Bacterial spot、1 = healthy
    a = np.array(img.convert('RGB').resize((S, S), Image.BILINEAR), np.float32)[None]
    it.set_tensor(ii['index'], a); it.invoke(); return float(it.get_tensor(oo['index'])[0][0])
def iou(a, b):
    x0, y0, x1, y1 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    i = max(0, x1-x0)*max(0, y1-y0); return i / ((a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - i + 1e-9)
res = collections.defaultdict(lambda: np.zeros((2, 2), int))  # [真(0病気,1健康), 予測]
leafres = collections.defaultdict(lambda: np.zeros((2, 2), int))
hit = tot = nodet = 0
for xp in sorted(glob.glob(f'{holdout}/Annotations/*.xml')):
    r = ET.parse(xp).getroot(); ip = f'{holdout}/images/' + r.find('filename').text
    img = Image.open(ip).convert('RGB'); W, H = img.size
    gts = [([int(o.find('bndbox/'+k).text) for k in ('xmin', 'ymin', 'xmax', 'ymax')], o.find('pclass').text) for o in r.findall('object')]
    gts = [(b, c) for b, c in gts if c.startswith('Bell_pepper')]
    if not gts: continue
    y = 0 if any(c.endswith('spot') for _, c in gts) else 1
    pw = p_dis(img); res['A 画像全体'][y, int(pw < 0.5)] += 1
    out = det.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.asarray(img)))
    dets = sorted([(d.categories[0].score, [d.bounding_box.origin_x, d.bounding_box.origin_y, d.bounding_box.origin_x + d.bounding_box.width, d.bounding_box.origin_y + d.bounding_box.height]) for d in out.detections], reverse=True)
    for b, _ in gts:
        tot += 1; hit += any(iou(b, d) >= 0.5 for s, d in dets if s >= SCORE)
    good = [d for s, d in dets if s >= SCORE][:5]
    if not good: nodet += 1
    crops = [img.crop(tuple(map(int, d))) for d in good if d[2]-d[0] >= 16 and d[3]-d[1] >= 16]
    ps = [p_dis(c) for c in crops]
    pb = ps[0] if ps else pw; res['B 検出1位の葉を切り出し'][y, int(pb < 0.5)] += 1
    pc = max(ps) if ps else pw; res['C 検出した葉（最大5枚）のどれかが病気なら病気'][y, int(pc < 0.5)] += 1
    pm = float(np.mean(ps)) if ps else pw; res['D 検出した葉の平均'][y, int(pm < 0.5)] += 1
    pg = max(p_dis(img.crop(tuple(b))) for b, _ in gts); res['E 参考: 正解の枠すべてで切り出し、どれかが病気なら病気'][y, int(pg < 0.5)] += 1
print(f'画像 {sum(res["A 画像全体"].sum(1))} 枚（病気 {res["A 画像全体"][0].sum()} / 健康 {res["A 画像全体"][1].sum()}）、検出のしきい値 {SCORE}')
print(f'葉の検出: 正解の枠 {tot} 個のうち {hit} 個を IoU 0.5 以上で検出（{hit/tot:.1%}）、検出ゼロの画像 {nodet} 枚')
for k, cm in res.items():
    acc = np.trace(cm)/cm.sum(); rec = cm[0, 0]/max(1, cm[0].sum()); spec = cm[1, 1]/max(1, cm[1].sum())
    print(f'{k}: 正答率 {acc:.1%}  病気の再現率 {rec:.1%}（見逃し {cm[0,1]}）  健康の正答 {spec:.1%}（誤検出 {cm[1,0]}）')
