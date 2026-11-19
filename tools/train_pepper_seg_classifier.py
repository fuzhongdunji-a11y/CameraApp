"""ML Kit の Subject Segmentation（被写体の切り抜き）で切り出した葉で、ピーマンの分類モデルを学習し直し、
今のアプリの方法と正答率・見逃しを比べるスクリプト。

学習のときと判定のときで同じ形の画像を渡すために、学習データも ML Kit で切り抜く。
ML Kit は Android の中でしか動かないので、切り抜きはスマホ（かエミュレーター）で行う（3 の手順）。

切り抜き方（--variants で選ぶ。アプリの SubjectCropper と同じ）:
    subject_mask  被写体1つずつ、輪郭の外を黒で塗る（iPhone の切り抜きに近い）
    subject_box   被写体1つずつ、外接枠で切り出すだけ（背景は残る）
    fg_mask       被写体全部をまとめて、輪郭の外を黒で塗る
    fg_box        被写体全部の外接枠で切り出すだけ
    1枚の写真から複数の被写体が出たときは、それぞれを判定して平均（mean）と最大（max）を出す。
    被写体が見つからなかった写真は、写真全体をそのまま使う。

比べる方法（PlantDoc のピーマンの写真 132枚、写真単位の3分割の交差検証。
            分割は tools/train_pepper_disease_detector.py と同じなので、--dis_rows で結果を組み合わせられる）:
    A           画像全体 → 元の分類モデル（今のアプリ）
    S0_<切り抜き>  ML Kit で切り抜き → 元の分類モデル（切り抜きだけの効果）
    S1_<切り抜き>  ML Kit で切り抜き → 切り抜いた画像で学習し直した分類モデル
    E_<切り抜き>   S1 と 2クラス検出モデル（C2）の平均（--dis_rows を付けたときだけ）
    しきい値 0.2〜0.5 ごとに、正答率・見逃し・誤検出を出す。

使い方（WSL の GPU 環境。tools/train_pepper_disease_detector.py と同じ環境でよい）:
    # 1. 切り抜く写真を集める（PlantDoc のピーマン 132枚 + PlantVillage 各クラス --n_pv 枚 + 農園の写真）
    python tools/train_pepper_seg_classifier.py prepare --holdout leaf_out/voc/pepper_holdout \\
        --pv pv/raw/color --n_pv 1000 --farm_healthy "/mnt/c/Users/.../農園の健康な写真" --seg_in seg_in

    # 2. アプリを debug ビルドでスマホに入れる（Android Studio の Run、または ./gradlew installDebug）

    # 3. スマホで切り抜く（PC とスマホを USB でつなぎ、USB デバッグを有効にしておく）
    adb push seg_in /sdcard/Android/data/com.example.cameraapp/files/
    adb shell am start -n com.example.cameraapp/.tools.SegExportActivity
    #    画面に「完了」と出たら（2,000枚で 10〜20分ほど）:
    adb pull /sdcard/Android/data/com.example.cameraapp/files/seg_out seg_out

    # 4. 学習と比較（GPU）
    python tools/train_pepper_seg_classifier.py train --seg_in seg_in --seg_out seg_out \\
        --holdout leaf_out/voc/pepper_holdout --base_model "/mnt/c/Users/.../pepper_model.h5" \\
        --dis_rows dis_out/eval_rows.json --out seg_cls_out --final

    出力: seg_cls_out/results.txt（比較の表）、seg_cls_out/eval_rows.json（写真ごとのスコア）
          --final を付けると、PlantDoc を全部使った最終モデル seg_cls_out/final_<切り抜き>.tflite も作る。

注意:
    - --base_model は、PlantDoc を学習に使っていない元のピーマンのモデル（pepper_model.h5）にする。
      71c4ly1v などは評価用の写真を学習しているので、比較が良く出すぎる。
    - 農園の健康な写真（--farm_healthy）は、学習には「健康」として入れ、評価では同じ3分割で
      「何枚を誤って病気と判定したか」だけを別に出す（病気の写真がないので見逃しは測れない）。
    - 画像は 0〜255 のままモデルに渡す（モデル内部に Rescaling(1/255) があるため。アプリと同じ）。
"""
import argparse, csv, collections, glob, json, os, random, shutil, sys, time

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train_pepper_disease_detector import PV_CLASSES, load_plantdoc  # noqa: E402

VARIANTS = ['subject_mask', 'subject_box', 'fg_mask', 'fg_box']
S = 256  # 分類モデルの入力の大きさ


# ---------------------------------------------------------------- 1. 写真を集める

def pd_folds(holdout, folds, seed):
    """train_pepper_disease_detector.py と同じ分け方で、PlantDoc の写真を k 分割する。"""
    random.seed(seed)
    items = load_plantdoc(holdout)
    random.shuffle(items)
    out = {}
    for i in range(folds):
        for stem, ip, objs in items[i::folds]:
            y = int(any(c == 'pepper_spot' for c, _ in objs))  # 1 = 病気
            out[stem] = (i, y, ip)
    return out


def cmd_prepare(args):
    shutil.rmtree(args.seg_in, ignore_errors=True)
    n = collections.Counter()
    for stem, (_, _, ip) in pd_folds(args.holdout, args.folds, args.seed).items():
        os.makedirs(f'{args.seg_in}/pd', exist_ok=True)
        shutil.copy(ip, f'{args.seg_in}/pd/{stem}.jpg'); n['pd'] += 1
    rnd = random.Random(args.seed)
    for c, _ in PV_CLASSES:
        fs = sorted(glob.glob(f'{args.pv}/{c}/*'))
        rnd.shuffle(fs)
        d = f'{args.seg_in}/pv/{c}'
        os.makedirs(d, exist_ok=True)
        for f in fs[:args.n_pv]:
            Image.open(f).convert('RGB').save(f'{d}/{os.path.splitext(os.path.basename(f))[0]}.jpg', quality=95)
            n[c] += 1
    if args.farm_healthy:
        d = f'{args.seg_in}/farm_healthy'
        os.makedirs(d, exist_ok=True)
        for f in sorted(glob.glob(f'{args.farm_healthy}/**/*', recursive=True)):
            if os.path.splitext(f)[1].lower() in ('.jpg', '.jpeg', '.png'):
                shutil.copy(f, f'{d}/farm{n["farm"]:04d}{os.path.splitext(f)[1].lower()}'); n['farm'] += 1
    print('集めた写真:', dict(n))
    print(f'次は adb push {args.seg_in} /sdcard/Android/data/com.example.cameraapp/files/')


# ---------------------------------------------------------------- 2. 切り抜きの読み込み

def load_manifest(seg_out):
    """manifest.csv を {元画像の相対パス: [行, ...]} にする。"""
    by = collections.defaultdict(list)
    with open(f'{seg_out}/manifest.csv', encoding='utf-8') as f:
        for r in csv.DictReader(f):
            by[r['src']].append(r)
    return by


def img_arr(path):
    return np.asarray(Image.open(path).convert('RGB').resize((S, S), Image.BILINEAR), np.uint8)


def crops_for(src, man, args, variant):
    """1枚の写真の切り抜き（256×256 の uint8 の配列のリスト）。見つからなければ写真全体。"""
    kind, style = variant.split('_')
    rows = [r for r in man.get(src, []) if r['kind'] == kind]
    if kind == 'subject':
        rows = sorted(rows, key=lambda r: int(r['index']))[:args.max_subjects]
    files = [r[f'{style}_file'] for r in rows if r[f'{style}_file']]
    if not files:
        return [img_arr(f'{args.seg_in}/{src}')]
    return [img_arr(f'{args.seg_out}/{p}') for p in files]


def collect(args, man, variant, folds_pd):
    """写真ごとの (グループ, 分割, ラベル(1=病気), 切り抜きのリスト, 写真全体) を作る。"""
    photos = []
    for stem, (k, y, _) in folds_pd.items():
        src = f'pd/{stem}.jpg'
        photos.append(dict(group='pd', name=stem, fold=k, y=y, crops=crops_for(src, man, args, variant),
                           whole=img_arr(f'{args.seg_in}/{src}')))
    for c, _ in PV_CLASSES:
        y = int('Bacterial_spot' in c)
        for f in sorted(glob.glob(f'{args.seg_in}/pv/{c}/*')):
            src = f'pv/{c}/{os.path.basename(f)}'
            photos.append(dict(group='pv', name=src, fold=-1, y=y, crops=crops_for(src, man, args, variant), whole=None))
    farm = sorted(glob.glob(f'{args.seg_in}/farm_healthy/*'))
    for i, f in enumerate(farm):
        src = f'farm_healthy/{os.path.basename(f)}'
        photos.append(dict(group='farm', name=src, fold=i % args.folds, y=0, crops=crops_for(src, man, args, variant),
                           whole=img_arr(f)))
    return photos


# ---------------------------------------------------------------- 3. 学習

def train_model(tf, args, photos, hold_fold):
    """hold_fold 以外の写真の切り抜きで、元のモデルから学習し直す。hold_fold=None なら全部使う。"""
    from train_wandb import make_augmenter, set_stage, split_model
    xs, ys = [], []
    for p in photos:
        if hold_fold is not None and p['fold'] == hold_fold:
            continue
        rep = args.pd_repeat if p['group'] in ('pd', 'farm') else 1
        for _ in range(rep):
            for c in p['crops']:
                xs.append(c); ys.append(0 if p['y'] == 1 else 1)  # 出力0 = Bacterial spot、1 = healthy
    x = np.stack(xs); y = np.array(ys, np.int32)
    print(f'  学習データ {len(y)} 枚（病気 {(y == 0).sum()} / 健康 {(y == 1).sum()}）', flush=True)
    aug = make_augmenter(tf, args.augment, args.seed)
    ds = tf.data.Dataset.from_tensor_slices((x, y)).shuffle(len(y), seed=args.seed).batch(args.batch_size)
    ds = ds.map(lambda a, b: (tf.cast(a, tf.float32), b), num_parallel_calls=tf.data.AUTOTUNE)
    if aug is not None:
        ds = ds.map(lambda a, b: (aug(a, training=True), b), num_parallel_calls=tf.data.AUTOTUNE)
    ds = ds.prefetch(tf.data.AUTOTUNE)
    model = tf.keras.models.load_model(args.base_model, compile=False)
    _, backbone, head = split_model(tf, model)
    for epochs, lr, layers in ((args.epochs_head, args.lr_head, 0), (args.epochs_finetune, args.lr_finetune, args.finetune_layers)):
        if epochs <= 0:
            continue
        set_stage(tf, model, backbone, head, layers)
        model.compile(optimizer=tf.keras.optimizers.Adam(lr), loss='sparse_categorical_crossentropy', metrics=['accuracy'])
        model.fit(ds, epochs=epochs, verbose=2)
    return model


def p_dis(model, arrs):
    """病気（出力0）の確率。"""
    x = np.stack(arrs).astype(np.float32)
    return model.predict(x, batch_size=32, verbose=0)[:, 0]


# ---------------------------------------------------------------- 4. 評価

def table(rows, keys, names, group, thresholds=(0.2, 0.3, 0.4, 0.5)):
    rs = [r for r in rows if r['group'] == group]
    if not rs:
        return []
    y = np.array([r['y'] for r in rs])
    lines = []
    if group == 'pd':
        lines.append(f'PlantDoc のピーマンの写真 {len(y)} 枚（病気 {y.sum()} / 健康 {(1 - y).sum()}）、{len(set(r["fold"] for r in rs))} 分割の交差検証')
    else:
        lines.append(f'農園の健康な写真 {len(y)} 枚（誤って病気と判定した枚数だけ）')
    for key in keys:
        if key not in rs[0]:
            continue
        s = np.array([r[key] for r in rs])
        lines.append(names.get(key, key))
        for t in thresholds:
            p = s >= t
            if group == 'pd':
                lines.append(f'   しきい値 {t}: 正答率 {(p == y).mean():.1%}  見逃し {int(((~p) & (y == 1)).sum())}/{y.sum()}'
                             f'  誤検出 {int((p & (y == 0)).sum())}/{(1 - y).sum()}')
            else:
                lines.append(f'   しきい値 {t}: 誤検出 {int(p.sum())}/{len(y)}')
    return lines


def cmd_train(args):
    import tensorflow as tf
    tf.keras.utils.set_random_seed(args.seed)
    man = load_manifest(args.seg_out)
    folds_pd = pd_folds(args.holdout, args.folds, args.seed)
    variants = [v.strip() for v in args.variants.split(',') if v.strip()]
    for v in variants:
        if v not in VARIANTS:
            sys.exit(f'--variants は {VARIANTS} から選ぶ: {v}')
    n_none = sum(1 for rs in man.values() if all(r['kind'] == 'none' for r in rs))
    print(f'切り抜きの一覧: {len(man)} 枚（被写体なし {n_none} 枚）')
    os.makedirs(args.out, exist_ok=True)

    dis = {}
    if args.dis_rows:
        for r in json.load(open(args.dis_rows)):
            dis[os.path.splitext(r['file'])[0]] = r
    base = tf.keras.models.load_model(args.base_model, compile=False)
    rows = {}  # (group, name) -> 行
    t0 = time.time()
    for v in variants:
        print(f'\n===== 切り抜き方: {v} =====', flush=True)
        photos = collect(args, man, v, folds_pd)
        evals = [p for p in photos if p['group'] in ('pd', 'farm')]
        for p in evals:
            r = rows.setdefault((p['group'], p['name']), dict(group=p['group'], file=p['name'], fold=p['fold'], y=p['y']))
            if 'A' not in r:
                r['A'] = float(p_dis(base, [p['whole']])[0])
            s = p_dis(base, p['crops'])
            r[f'S0_{v}'] = float(s.mean()); r[f'S0max_{v}'] = float(s.max())
            r[f'n_{v}'] = len(p['crops'])
        for k in range(args.folds):
            print(f'-- 分割 {k} を評価に使う（{(time.time() - t0) / 60:.0f} 分経過）', flush=True)
            m = train_model(tf, args, photos, k)
            for p in evals:
                if p['fold'] != k:
                    continue
                s = p_dis(m, p['crops'])
                r = rows[(p['group'], p['name'])]
                r[f'S1_{v}'] = float(s.mean()); r[f'S1max_{v}'] = float(s.max())
                d = dis.get(p['name'])
                if d is not None and p['group'] == 'pd':
                    r[f'E_{v}'] = (r[f'S1_{v}'] + d['C2']) / 2
                    r['C2'] = d['C2']
            del m
            tf.keras.backend.clear_session()
        with open(f'{args.out}/eval_rows.json', 'w') as f:
            json.dump(list(rows.values()), f, indent=1)
        if args.final:
            print(f'-- 最終モデル（PlantDoc を全部使う）', flush=True)
            m = train_model(tf, args, photos, None)
            m.save(f'{args.out}/final_{v}.h5')
            from train_wandb import export_tflite
            export_tflite(tf, m, f'{args.out}/final_{v}.tflite')
            print(f'   書き出し: {args.out}/final_{v}.tflite')
            del m
            tf.keras.backend.clear_session()

    names = {'A': 'A 画像全体 → 元の分類モデル（今のアプリ）', 'C2': 'C2 病気/健康の検出モデル（参考）'}
    keys = ['A', 'C2']
    for v in variants:
        names.update({f'S0_{v}': f'S0 {v} → 元の分類モデル（平均）', f'S0max_{v}': f'S0 {v} → 元の分類モデル（最大）',
                      f'S1_{v}': f'S1 {v} → 学習し直した分類モデル（平均）', f'S1max_{v}': f'S1 {v} → 学習し直した分類モデル（最大）',
                      f'E_{v}': f'E {v}: S1（平均）と C2 の平均'})
        keys += [f'S0_{v}', f'S0max_{v}', f'S1_{v}', f'S1max_{v}', f'E_{v}']
    rs = list(rows.values())
    lines = table(rs, keys, names, 'pd')
    farm = table(rs, keys, names, 'farm')
    if farm:
        lines += [''] + farm
    lines += ['', f'学習時間: {(time.time() - t0) / 60:.0f} 分。設定: ' + json.dumps(
        {k: getattr(args, k) for k in ('variants', 'epochs_head', 'epochs_finetune', 'finetune_layers', 'lr_head',
                                       'lr_finetune', 'augment', 'pd_repeat', 'max_subjects', 'batch_size', 'seed')}, ensure_ascii=False)]
    text = '\n'.join(lines)
    print('\n' + text)
    with open(f'{args.out}/results.txt', 'w') as f:
        f.write(text + '\n')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument('--holdout', required=True, help='train_leaf_detector.py が作った pepper_holdout')
    common.add_argument('--seg_in', default='seg_in', help='スマホに送る写真のフォルダ')
    common.add_argument('--folds', type=int, default=3)
    common.add_argument('--seed', type=int, default=0, help='分割の乱数。train_pepper_disease_detector.py と同じ 0 にする')

    p = sub.add_parser('prepare', parents=[common], help='スマホで切り抜く写真を集める')
    p.add_argument('--pv', required=True, help='PlantVillage の raw/color フォルダ')
    p.add_argument('--n_pv', type=int, default=1000, help='PlantVillage から各クラス何枚使うか')
    p.add_argument('--farm_healthy', default=None, help='農園で撮った健康なピーマンの写真のフォルダ（任意）')

    t = sub.add_parser('train', parents=[common], help='切り抜いた画像で学習し直して比べる')
    t.add_argument('--seg_out', required=True, help='スマホから adb pull した seg_out')
    t.add_argument('--base_model', required=True, help='元のピーマンの分類モデル（.h5。PlantDoc で学習していないもの）')
    t.add_argument('--out', default='seg_cls_out')
    t.add_argument('--variants', default='subject_mask,subject_box', help=f'切り抜き方（カンマ区切り）: {",".join(VARIANTS)}')
    t.add_argument('--dis_rows', default=None, help='train_pepper_disease_detector.py の eval_rows.json（C2 と組み合わせる）')
    t.add_argument('--max_subjects', type=int, default=5, help='1枚の写真から使う被写体の数（面積の大きい順）')
    t.add_argument('--pd_repeat', type=int, default=3, help='PlantDoc と農園の写真を学習に何回入れるか')
    t.add_argument('--epochs_head', type=int, default=5)
    t.add_argument('--epochs_finetune', type=int, default=10)
    t.add_argument('--lr_head', type=float, default=1e-3)
    t.add_argument('--lr_finetune', type=float, default=1e-5)
    t.add_argument('--finetune_layers', type=int, default=30)
    t.add_argument('--augment', choices=['none', 'light', 'strong'], default='light')
    t.add_argument('--batch_size', type=int, default=16)
    t.add_argument('--final', action='store_true', help='PlantDoc を全部使った最終モデル（tflite）も作る')
    args = ap.parse_args()
    cmd_prepare(args) if args.cmd == 'prepare' else cmd_train(args)


if __name__ == '__main__':
    main()
