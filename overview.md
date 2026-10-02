# 2560 标定推进 + 脚本归置

## 一、脚本归置（防重复造轮子）
- 新增 **`dsl/scripts/calib_toolkit.py`**：标定工具箱（adb 原语/截图/OCR/`detect_row_top` 卡框顶检测/
  `tmatch` 模板匹配/`bright_bbox` 控件包围盒/`run_flow`+`logs`+`crashes`），文末附任一分辨率通用 7 步流程
- 9 个一次性 bench/settle/inertia 脚本 → `scripts/archive/`（archive 现 38 个）；删 `__pycache__`
- `dsl/README.md` 增脚本分工约定：**改坐标 → calib_toolkit；新分辨率 → 先 calibrate_profile 换算、再 calib_toolkit 实测纠偏**；一次性试验一律进 archive/
- ⚠️ 工具箱首战抓坑：PaddleOCR 参数拼写必须 `use_doc_unwarping`（拼错 → 降级 → UVDoc 开 → bbox 畸变，
  曾据此误判"布局漂移"）

## 二、2560 网格重大纠正（e2e 实证）
| 项 | 旧（错） | 新（实测） | 依据 |
|---|---|---|---|
| weapon cardOrigin | (248,**120**) | (248,**192**) | Lv.90@402 − 210 |
| artifact cardOrigin | (248,**494**) | (248,**290**) | +20@498 − 208 |
| artifact visibleRows | 3 | **4** | 行 290/568/846/1124，row3 被底栏遮挡=滑动锚 |
| pitchY | 278/279 | 278/279（按标签行差复核） | 行差 |
| advance distance | 720 | **837 / 834** | =3 行 |
| weapon starStrip rel | [29,170,167,212] | **[2,166,194,212]** | 旧框裁边 2477px→5★误判4星；新框 2671px→5星（divisor 555 沿用） |

> 根因方法论：**+20/Lv.90 标签在卡底（cardtop+208~210），禁止把标签 y 当卡顶**。旧值下
> votes 脱靶（武器全 rarity=1）、扫描实际错行。

## 三、e2e 实证（Bluestacks 2560，0 crash）
- **weapon_scan**：若水/帷间/天空/裁断/狼的/苍古 全 **rarity=5 locked=true** ✓（修复前全 rarity=1）
- **artifact_scan**：total=1386，多件异名（止于妙想/宏伟梦醒/阔步跌坠/天授×3）、rarity 5/4、
  locked/favorited 逐卡区分，导出 12 件
- PINK_LOCK 锁区 198-207px >> 阈值 60 ✓；单测全绿；dsl/json canonical 已同步

## 四、2560 剩余待标定（需导航对应界面，下一轮）
- character_scan：char_interface / char_popup / panels.char_profile·talent·constellation
- artifact_lock + auto_equip：artifact_manage screens+panels、dialogs（equipConfirm/filterPanel）
- setfilter_calibrate：select_3col、set_filter_popup
- 方法：照 calib_toolkit 7 步流程 + 上述方法论 4 条
