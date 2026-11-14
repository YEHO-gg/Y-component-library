# chatview

![preview](docs/preview.png)


PySide6 聊天记录视图。用法、设计取舍、测试方式见仓库文档 [docs/chatview.md](../../docs/chatview.md)。

```bash
pip install -e .            # 装成本地包，`import ycomponent` 才找得到
python examples/demo.py     # 带假消息的 demo，每 4 秒来一条
```

> 直接 `python examples/demo.py` 而不装包会 `ModuleNotFoundError`：
> 脚本模式下 `sys.path[0]` 是 `examples/`，项目根不在搜索路径里。
> 不想装包就用 `PYTHONPATH=. python examples/demo.py`。
