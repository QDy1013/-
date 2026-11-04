"""Python 评测引擎的多场考试改期和撤销回归。"""
from datetime import datetime, timedelta
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from engine.pipeline import Model, Pipeline


def main():
    pipeline = Pipeline(Model(ROOT / "engine" / "model.json"))
    start = datetime(2026, 11, 4, 10)

    def send(index, text, group="A", sender="王老师"):
        return pipeline.process({
            "msg_id": str(index),
            "group_id": group,
            "group_name": "课程通知群",
            "sender": sender,
            "text": text,
            "ts": (start + timedelta(minutes=index)).isoformat(),
        })

    send(1, "数据库期中考试明天9点，地点：教一101")
    send(2, "操作系统期中考试后天14点\n地点：教二305\n带：学生证")
    db, os = pipeline.active_cards()
    assert db.kind == os.kind == "exam"
    assert os.place == "教二305" and os.bring == "学生证"
    original = os.deadline
    send(3, "更正：数据库期中考试改到后天上午10点。")
    assert db.deadline == datetime(2026, 11, 6, 10) and db.updated and os.deadline == original
    send(4, "期中考试改到后天下午3点。")
    send(5, "高等数学期中考试改到后天下午4点。")
    send(6, "期中考试取消。")
    send(7, "操作系统期中考试改到后天下午4点。", group="B")
    send(8, "操作系统期中考试改到后天下午4点。", sender="同学")
    assert pipeline.active_cards() == [db, os] and os.deadline == original
    send(9, "取消数据库期中考试。")
    assert pipeline.active_cards() == [os] and "1" not in pipeline.by_source
    send(10, "考试原定后天下午2点，改为后天下午5点。")
    assert pipeline.active_cards() == [os] and os.deadline == datetime(2026, 11, 6, 17)
    print("✓ 多场考试匹配、取消与群边界全部通过")


if __name__ == "__main__":
    main()
