import os
import sys
import json
import time
import math
import argparse
import numpy as np
from PIL import Image
import onnxruntime as ort
try:
    import regex as re  # 支持 \p{L} 等Unicode属性
except Exception:
    import re

# PC端FastVLM ONNX推理脚本（CPU默认）
# - 读取 FastVLM-onnx/config.json, tokenizer, projector权重
# - 预处理图片 → 视觉编码器 → projector → 与文本嵌入拼接 → 解码器自回归

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ONNX_DIR = os.path.join(BASE_DIR, 'FastVLM-onnx', 'onnx')
TOKENIZER_DIR = os.path.join(BASE_DIR, 'FastVLM-onnx')

VISION_MODEL = os.path.join(ONNX_DIR, 'vision_encoder_q4.onnx')
DECODER_MODEL = os.path.join(ONNX_DIR, 'decoder_model_merged_q4.onnx')
EMBED_MODEL = os.path.join(ONNX_DIR, 'embed_tokens_q4.onnx')

CONFIG_JSON = os.path.join(BASE_DIR, 'FastVLM-onnx', 'config.json')
TOKENIZER_JSON = os.path.join(TOKENIZER_DIR, 'tokenizer_config.json')
VOCAB_JSON = os.path.join(TOKENIZER_DIR, 'vocab.json')
MERGES_TXT = os.path.join(TOKENIZER_DIR, 'merges.txt')
SPECIAL_MAP_JSON = os.path.join(TOKENIZER_DIR, 'special_tokens_map.json')

VPROJ_L1_W = os.path.join(BASE_DIR, 'vision_projector_l1_w.bin')
VPROJ_L1_B = os.path.join(BASE_DIR, 'vision_projector_l1_b.bin')
VPROJ_L2_W = os.path.join(BASE_DIR, 'vision_projector_l2_w.bin')
VPROJ_L2_B = os.path.join(BASE_DIR, 'vision_projector_l2_b.bin')


class BpeTokenizerPy:
    def __init__(self, base_dir: str):
        self.base_dir = base_dir
        self.vocab = {}
        self.id_to_token = {}
        self.bpe_ranks = {}
        self.chat_template = None
        self.special_to_id = {}
        self.id_to_special = {}
        self.bos_id = None
        self.eos_id = None
        self.pad_id = None
        self.image_id = None
        self.pattern = re.compile(r"''s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+") if hasattr(re, 'compile') else re.compile(r"\S+|\s+")
        self.byte_encoder = self._build_bytes_to_unicode()
        self.byte_decoder = {v: k for k, v in self.byte_encoder.items()}
        self._load_files()

    def _resolve_dir(self):
        direct = self.base_dir
        if os.path.exists(os.path.join(direct, 'vocab.json')):
            return direct
        sib = os.path.join(os.path.dirname(direct), 'tokenizer')
        return sib if os.path.exists(os.path.join(sib, 'vocab.json')) else direct

    def _load_files(self):
        tdir = self._resolve_dir()
        # vocab
        with open(os.path.join(tdir, 'vocab.json'), 'r', encoding='utf-8') as f:
            vj = json.load(f)
        for k, vid in vj.items():
            self.vocab[k] = int(vid)
            self.id_to_token[int(vid)] = k
        # merges
        with open(os.path.join(tdir, 'merges.txt'), 'r', encoding='utf-8') as f:
            rank = 0
            for line in f:
                ln = line.strip()
                if not ln or ln.startswith('#'):
                    continue
                a, b = ln.split(' ')
                self.bpe_ranks[(a, b)] = rank
                rank += 1
        # tokenizer_config
        tok_cfg = {}
        cfg_path = os.path.join(tdir, 'tokenizer_config.json')
        if os.path.exists(cfg_path):
            tok_cfg = json.load(open(cfg_path, 'r', encoding='utf-8'))
            self.chat_template = tok_cfg.get('chat_template', None)
            added = tok_cfg.get('added_tokens_decoder', {})
            for k, v in added.items():
                tid = int(k)
                content = v.get('content', '')
                self.special_to_id[content] = tid
                self.id_to_special[tid] = content
                if content == '<image>':
                    self.image_id = tid
        # special_tokens_map.json
        sm_path = os.path.join(tdir, 'special_tokens_map.json')
        if os.path.exists(sm_path):
            sm = json.load(open(sm_path, 'r', encoding='utf-8'))
            if 'eos_token' in sm:
                content = sm['eos_token'].get('content', '')
                if content in self.special_to_id:
                    self.eos_id = self.special_to_id[content]
            if 'pad_token' in sm:
                content = sm['pad_token'].get('content', '')
                if content in self.special_to_id:
                    self.pad_id = self.special_to_id[content]

    def _build_bytes_to_unicode(self):
        bs = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
        cs = bs[:]
        n = 0
        for b in range(256):
            if b not in bs:
                bs.append(b)
                cs.append(256 + n)
                n += 1
        return {b: chr(c) for b, c in zip(bs, cs)}

    def _get_pairs(self, word):
        pairs = set()
        prev = None
        for ch in word:
            if prev is not None:
                pairs.add((prev, ch))
            prev = ch
        return pairs

    def _bpe(self, token: str):
        if not token:
            return []
        word = list(token)
        pairs = self._get_pairs(word)
        if not pairs:
            return [token]
        while True:
            bigram = min(pairs, key=lambda p: self.bpe_ranks.get(p, 10**9))
            if bigram not in self.bpe_ranks:
                break
            first, second = bigram
            new_word = []
            i = 0
            while i < len(word):
                j = i
                found = False
                while j < len(word):
                    if word[j] == first:
                        found = True
                        break
                    j += 1
                if not found:
                    new_word.extend(word[i:])
                    break
                new_word.extend(word[i:j])
                i = j
                if i < len(word) - 1 and word[i] == first and word[i + 1] == second:
                    new_word.append(first + second)
                    i += 2
                else:
                    new_word.append(word[i])
                    i += 1
            word = new_word
            if len(word) == 1:
                break
            pairs = self._get_pairs(word)
        return word

    def apply_chat_template(self, user_prompt: str) -> str:
        system = "<|im_start|>system\nYou are a helpful assistant. Please answer in English only.<|im_end|>\n"
        user = f"<|im_start|>user\n<image>\n{user_prompt}<|im_end|>\n"
        assistant_head = "<|im_start|>assistant\n"
        return system + user + assistant_head

    def tokenize_to_ids(self, text: str):
        ids = []
        i = 0
        specials = list(self.special_to_id.keys())
        while i < len(text):
            # 找到下一个特殊token
            next_pos = None
            next_tok = None
            for tok in specials:
                idx = text.find(tok, i)
                if idx >= 0 and (next_pos is None or idx < next_pos):
                    next_pos = idx
                    next_tok = tok
            if next_pos is None:
                segment = text[i:]
                ids.extend(self._encode_segment(segment))
                break
            else:
                if next_pos > i:
                    ids.extend(self._encode_segment(text[i:next_pos]))
                ids.append(self.special_to_id[next_tok])
                i = next_pos + len(next_tok)
        return np.array(ids, dtype=np.int64)

    def _encode_segment(self, segment: str):
        if not segment:
            return []
        out = []
        for m in self.pattern.finditer(segment):
            piece = m.group(0)
            # bytes→unicode
            encoded = ''.join(self.byte_encoder[b] for b in piece.encode('utf-8'))
            # bpe
            for tok in self._bpe(encoded):
                out.append(self.vocab.get(tok, self.vocab.get('<unk>', 0)))
        return out

    def decode(self, ids: list) -> str:
        bytes_list = []
        for tid in ids:
            if tid in self.id_to_special:
                continue
            tok = self.id_to_token.get(int(tid))
            if tok is None:
                continue
            for ch in tok:
                b = self.byte_decoder.get(ch)
                if b is not None:
                    bytes_list.append(b)
        try:
            return bytes(bytearray(bytes_list)).decode('utf-8').strip()
        except Exception:
            return bytes(bytearray(bytes_list)).decode(errors='ignore').strip()


def load_json(path):
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)


def preprocess_image(path):
    img = Image.open(path).convert('RGB')
    # 统一 Resize 到 1024x1024（与移动端一致），不裁剪
    img = img.resize((1024, 1024), Image.BICUBIC)
    arr = np.asarray(img, dtype=np.float32) / 255.0
    # HWC → CHW
    chw = np.transpose(arr, (2, 0, 1))
    # batch=1
    return chw.reshape(1, 3, 1024, 1024)


def gelu(x):
    return 0.5 * x * (1.0 + np.tanh(np.sqrt(2.0 / np.pi) * (x + 0.044715 * np.power(x, 3))))


def load_bin_f32(path):
    with open(path, 'rb') as f:
        data = np.frombuffer(f.read(), dtype=np.float32)
    return data


def project_vision(x_2d, hidden_expected, mm_hidden_expected):
    # x_2d: [T, feat_dim]; projector权重决定输入/输出维度
    l1_w_raw = load_bin_f32(VPROJ_L1_W)
    l1_b_raw = load_bin_f32(VPROJ_L1_B)
    # 从权重推断维度
    hidden = l1_b_raw.size
    mm_hidden = l1_w_raw.size // hidden
    l1_w = l1_w_raw.reshape(hidden, mm_hidden)
    l1_b = l1_b_raw.reshape(hidden)
    l2_w = load_bin_f32(VPROJ_L2_W).reshape(hidden, hidden)
    l2_b = load_bin_f32(VPROJ_L2_B).reshape(hidden)

    # 若输入特征维与权重输入不符，直接报错
    if x_2d.shape[1] != mm_hidden:
        raise ValueError(f"Projector expects mm_hidden={mm_hidden}, but got feat_dim={x_2d.shape[1]}")

    y = x_2d @ l1_w.T + l1_b
    y = gelu(y)
    y = y @ l2_w.T + l2_b
    return y.astype(np.float32)  # [T, hidden]


def top_p_sample(logits, top_p=0.95, temperature=0.7, repetition_penalty=1.1, seen=None):
    if seen is None:
        seen = {}
    logits = logits.astype(np.float64)
    # 重复惩罚
    for tid, cnt in seen.items():
        if cnt > 0:
            if logits[tid] > 0:
                logits[tid] /= repetition_penalty
            else:
                logits[tid] *= repetition_penalty
    # 温度
    t = max(temperature, 1e-6)
    logits = logits / t
    # softmax
    m = np.max(logits)
    probs = np.exp(logits - m)
    probs /= np.sum(probs)
    # top-p裁剪
    idx = np.argsort(-probs)
    cumsum = np.cumsum(probs[idx])
    keep_n = np.searchsorted(cumsum, top_p) + 1
    keep_idx = idx[:keep_n]
    keep_probs = probs[keep_idx]
    keep_probs /= keep_probs.sum()
    choice = np.random.choice(keep_idx, p=keep_probs)
    return int(choice)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', type=str, required=True, help='要分析的图片路径')
    parser.add_argument('--prompt', type=str, default='Describe this image in detail in English.')
    parser.add_argument('--device', type=str, default='cpu', choices=['cpu'])
    parser.add_argument('--temperature', type=float, default=0.7)
    parser.add_argument('--top_p', type=float, default=0.95)
    parser.add_argument('--repetition_penalty', type=float, default=1.1)
    parser.add_argument('--max_new_tokens', type=int, default=96)
    parser.add_argument('--verbose', action='store_true')
    args = parser.parse_args()

    cfg = load_json(CONFIG_JSON)
    hidden = int(cfg['hidden_size'])
    mm_hidden = int(cfg['mm_hidden_size'])
    image_token_id = int(cfg.get('image_token_index', 151646))
    eos_id = int(cfg.get('eos_token_id', 151645))

    providers = ['CPUExecutionProvider']
    so = ort.SessionOptions()
    so.intra_op_num_threads = 2

    vision_sess = ort.InferenceSession(VISION_MODEL, so, providers=providers)
    embed_sess = ort.InferenceSession(EMBED_MODEL, so, providers=providers)
    dec_sess = ort.InferenceSession(DECODER_MODEL, so, providers=providers)

    # 构建与车机一致的分词器与模板
    tokenizer = BpeTokenizerPy(TOKENIZER_DIR)
    prompt_text = tokenizer.apply_chat_template(args.prompt)
    input_ids = tokenizer.tokenize_to_ids(prompt_text)[None, :]
    attn_mask_text = np.ones((1, input_ids.shape[1]), dtype=np.int64)

    # 1) 视觉预处理 + 编码
    img = preprocess_image(args.image)
    vf = vision_sess.run(None, {vision_sess.get_inputs()[0].name: img})[0].astype(np.float32)
    # 兼容两种导出：
    # 1) 视觉编码器输出mm_hidden（需外部MLP投影）: [1, T, mm_hidden]
    # 2) 已内置投影输出hidden: [1, T, hidden]
    if vf.ndim != 3 or vf.shape[0] != 1:
        raise ValueError(f"Unexpected vision output shape: {vf.shape}")
    T = vf.shape[1]
    feat_dim = vf.shape[2]

    # 2) 文本嵌入
    text_emb = embed_sess.run(None, {'input_ids': input_ids})[0].astype(np.float32)  # [1, L, hidden]
    L = text_emb.shape[1]

    # 3) Project视觉序列到hidden，并在 <image> 位置展开替换
    if feat_dim == hidden:
        vis_proj = vf.reshape(T, feat_dim)  # 已投影
    else:
        vis_proj = project_vision(vf.reshape(T, feat_dim), hidden_expected=hidden, mm_hidden_expected=mm_hidden)
    # 拼接：把 <image> 占位替换为 T 个视觉token，其余照抄
    image_pos_arr = np.where(input_ids[0] == image_token_id)[0]
    image_pos = int(image_pos_arr[0]) if image_pos_arr.size > 0 else None
    if image_pos is None:
        print('警告：未找到<image>占位，直接返回')
        return
    total_len = L - 1 + T
    inputs_embeds = np.zeros((1, total_len, hidden), dtype=np.float32)
    w = 0
    for i in range(L):
        if i == image_pos:
            inputs_embeds[0, w:w+T, :] = vis_proj
            w += T
        else:
            inputs_embeds[0, w, :] = text_emb[0, i, :]
            w += 1

    # 4) 自回归生成
    # 构造初始 mask/pos
    attn_mask = np.ones((1, total_len), dtype=np.int64)
    position_ids = np.arange(total_len, dtype=np.int64)[None, :]

    # past_key_values 为空（根据签名推断）
    inputs = { 'inputs_embeds': inputs_embeds, 'attention_mask': attn_mask, 'position_ids': position_ids }
    # 推断层数与kv形状
    layer_inputs = [k for k in dec_sess.get_inputs() if k.name.startswith('past_key_values.')]
    num_layers = max(1, len(layer_inputs) // 2)
    # 从某一项tensor info推断heads/head_dim
    kv0 = [k for k in layer_inputs if k.name.endswith('.key')][0]
    heads = kv0.shape[1]
    head_dim = kv0.shape[3]
    empty = np.zeros((1, heads, 0, head_dim), dtype=np.float32)
    for l in range(num_layers):
        inputs[f'past_key_values.{l}.key'] = empty
        inputs[f'past_key_values.{l}.value'] = empty

    out = dec_sess.run(None, inputs)
    logits = out[0]  # [1, S, vocab]
    vocab = logits.shape[-1]
    last = logits[0, -1, :]
    seen = {}
    generated = []
    temperature = args.temperature
    top_p = args.top_p
    rep = args.repetition_penalty

    # 逐步生成，单步输入
    for step in range(args.max_new_tokens):
        nid = top_p_sample(last, top_p=top_p, temperature=temperature, repetition_penalty=rep, seen=seen)
        generated.append(nid)
        seen[nid] = seen.get(nid, 0) + 1
        if args.verbose:
            try:
                tok_text = tokenizer.decode([nid]).replace('\n', '\\n')
                print(f"step={step} id={nid} tok='{tok_text}'")
            except Exception:
                pass
        if nid == eos_id:
            break
        # 嵌入新token
        tok_emb = embed_sess.run(None, {'input_ids': np.array([[nid]], dtype=np.int64)})[0]  # [1,1,hidden]
        # 构造步进输入
        # 从 out 中取 present 作为下一轮 past（假设输出顺序为 logits, present.0.key, present.0.value, ...）
        # 计算 past_len 以构造 attention_mask 与 position_ids
        k0 = out[1]
        past_len = int(k0.shape[2])  # [1, heads, past_len, head_dim]
        feed = {
            'inputs_embeds': tok_emb,
            'attention_mask': np.ones((1, past_len + 1), dtype=np.int64),
            'position_ids': np.array([[past_len]], dtype=np.int64),
        }
        idx = 1
        for l in range(num_layers):
            feed[f'past_key_values.{l}.key'] = out[idx]; feed[f'past_key_values.{l}.value'] = out[idx+1]
            idx += 2
        out = dec_sess.run(None, feed)
        last = out[0][0, -1, :]

    # BPE解码为可读中文文本
    # 裁剪到EOS并过滤特殊token
    if eos_id in generated:
        cut = generated[:generated.index(eos_id)]
    else:
        cut = generated
    text = tokenizer.decode(cut)
    print(text)


if __name__ == '__main__':
    main()


