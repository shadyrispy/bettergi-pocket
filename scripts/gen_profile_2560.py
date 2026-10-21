#!/usr/bin/env python3
"""profiles_2560x1440.json 生成器：基准 3200x1440 等比 x0.8（2560x1440 与基准同为 16:9 高度系
   —— 不对，3200x1440 是 20:9、2560x1440 是 16:9，宽高比不同！）

⚠️ 重要更正：2560x1440 是 16:9，与基准 3200x1440 (20:9) 宽高比不同。
   游戏引擎 UI 布局随宽高比变化（横向锚点位置不同），**纯等比缩放不成立**。
   本脚本生成的是「数学等比缩放版」（sx=sy=0.8），仅作占位与对照基准，
   2560x1440 的**可用 profile 必须从该分辨率实机截图重新标定**。
   生成文件头部 "note" 字段已标注此限制。

规则（与 ScreenProfile 运行时缩放一致）：
- 长度 2 的纯 int 数组（点）→ x0.8
- 长度 4 的纯 int 数组（rect）→ x0.8
- key ∈ {cardOrigin, pitch, cardSize, from, to} 的 int 数组 → x0.8
- rel（相对卡片，运行时统一缩放）→ 不动
- 字符串（judge/语义说明）→ 不动
- 顶层注入 "base": {"w":2560,"h":1440} + "note"
"""
import json
import sys

SCALE = 0.8
COORD_KEYS = {"cardOrigin", "pitch", "cardSize", "from", "to", "rect"}


def is_int_list(a, n):
    return isinstance(a, list) and len(a) == n and all(isinstance(x, int) for x in a)


def transform(node, key=None):
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            if k == "rel":
                out[k] = v  # 相对坐标：运行时统一缩放，不动
            elif k in COORD_KEYS and is_int_list(v, 2):
                out[k] = [round(v[0] * SCALE), round(v[1] * SCALE)]
            elif k in COORD_KEYS and is_int_list(v, 4):
                out[k] = [round(v[0] * SCALE), round(v[1] * SCALE), round(v[2] * SCALE), round(v[3] * SCALE)]
            else:
                out[k] = transform(v, k)
        return out
    if isinstance(node, list):
        # points 类：[[x,y],...] 递归；纯 2/4 int 数组按坐标缩放
        if is_int_list(node, 2):
            return [round(node[0] * SCALE), round(node[1] * SCALE)]
        if is_int_list(node, 4):
            return [round(node[0] * SCALE), round(node[1] * SCALE), round(node[2] * SCALE), round(node[3] * SCALE)]
        return [transform(x) for x in node]
    return node


def main(src, dst):
    root = json.load(open(src, encoding="utf-8"))
    out = transform(root)
    out["base"] = {"w": 2560, "h": 1440}
    out["note"] = ("数学等比缩放占位（sx=sy=0.8）。2560x1440 为 16:9，与基准 3200x1440 (20:9) 宽高比不同，"
                   "游戏 UI 横向布局不同——可用坐标必须从该分辨率实机截图重新标定，本文件仅作对照与占位。")
    json.dump(out, open(dst, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"written: {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
