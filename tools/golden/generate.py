#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

FORMAT_VERSION = 1
REFERENCE_FILES = (
    "session/manager.py",
    "session/store.py",
    "agent/context.py",
    "agent/provider.py",
    "agent/core/passive_turn.py",
)


def main() -> None:
    arguments = parse_arguments()
    python_repo = arguments.python_repo.resolve()
    output = arguments.output.resolve()
    validate_python_repo(python_repo)
    sys.path.insert(0, str(python_repo))

    history = build_history_fixture()
    prompt = build_prompt_fixture()
    sqlite = build_sqlite_fixture()

    write_json(output / "history/session-history.json", history)
    write_json(output / "prompt/message-envelope.json", prompt)
    write_json(output / "sqlite/session-store.json", sqlite)

    errors = output / "errors/http-error-mapping.json"
    if not errors.is_file():
        raise SystemExit(f"缺少人工维护的错误迁移契约夹具: {errors}")

    fixtures = []
    for fixture_id, relative_path, source in (
        ("history/session-history", "history/session-history.json", "python-reference"),
        ("prompt/message-envelope", "prompt/message-envelope.json", "python-reference"),
        ("sqlite/session-store", "sqlite/session-store.json", "python-reference"),
        ("errors/http-error-mapping", "errors/http-error-mapping.json", "migration-contract"),
    ):
        fixtures.append(
            {
                "id": fixture_id,
                "path": relative_path,
                "sha256": sha256(output / relative_path),
                "source": source,
            }
        )

    manifest = {
        "fixtures": fixtures,
        "formatVersion": FORMAT_VERSION,
        "pythonBaseline": {
            "commit": git(python_repo, "rev-parse", "HEAD"),
            "repository": "namei32/akashic-agent",
            "sourceFiles": {
                path: sha256(python_repo / path) for path in sorted(REFERENCE_FILES)
            },
        },
    }
    write_json(output / "manifest.json", manifest)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成 Python/Java Golden 基准夹具")
    parser.add_argument("--python-repo", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def validate_python_repo(repository: Path) -> None:
    missing = [path for path in REFERENCE_FILES if not (repository / path).is_file()]
    if missing:
        raise SystemExit(f"Python 参考仓库缺少文件: {', '.join(missing)}")
    changed = git(repository, "status", "--porcelain", "--", *REFERENCE_FILES)
    if changed:
        raise SystemExit("Python 参考文件存在未提交变更，拒绝生成:\n" + changed)


def build_history_fixture() -> dict[str, Any]:
    from session.manager import Session

    cases = [
        history_case(Session, "empty", [], 40, 0),
        history_case(
            Session,
            "two-complete-turns",
            [
                message("user", "第一问"),
                message("assistant", "第一答"),
                message("user", "第二问"),
                message("assistant", "第二答"),
            ],
            4,
            None,
        ),
        history_case(
            Session,
            "newest-complete-turn",
            [
                message("user", "旧问题"),
                message("assistant", "旧回答"),
                message("user", "新问题"),
                message("assistant", "新回答"),
            ],
            2,
            None,
        ),
        history_case(
            Session,
            "leading-orphan-assistant",
            [
                message("assistant", "孤立回答"),
                message("user", "有效问题"),
                message("assistant", "有效回答"),
            ],
            2,
            0,
        ),
        history_case(
            Session,
            "assistant-only",
            [message("assistant", "回答一"), message("assistant", "回答二")],
            40,
            0,
        ),
    ]
    return {
        "cases": cases,
        "formatVersion": FORMAT_VERSION,
        "normalization": [],
        "pythonEvidence": {
            "callable": "session.manager.Session.get_history",
            "path": "session/manager.py",
            "projection": "普通 user/assistant 文本消息",
        },
        "source": "python-reference",
        "suite": "history",
    }


def history_case(
    session_type: Any,
    case_id: str,
    messages: list[dict[str, str]],
    max_messages: int,
    start_index: int | None,
) -> dict[str, Any]:
    session = session_type("golden", messages=[dict(item) for item in messages])
    expected = session.get_history(max_messages=max_messages, start_index=start_index)
    return {
        "expected": {"messages": expected},
        "id": case_id,
        "input": {
            "maxCharacters": 100000,
            "maxMessages": max_messages,
            "messages": messages,
        },
        "pythonInvocation": {"maxMessages": max_messages, "startIndex": start_index},
    }


def message(role: str, content: str) -> dict[str, str]:
    return {"content": content, "role": role}


def build_prompt_fixture() -> dict[str, Any]:
    from agent.context import MessageEnvelopeBuilder

    fixed_time = datetime(2026, 7, 13, 8, 0, 0, tzinfo=timezone.utc)
    builder = MessageEnvelopeBuilder()
    cases = []
    for case_id, history, current in (
        ("without-history", [], "你好"),
        (
            "with-one-complete-turn",
            [message("user", "第一问"), message("assistant", "第一答")],
            "第二问",
        ),
    ):
        system_prompt = "你是 Namei Agent。请根据给定的对话历史直接、准确地回答当前用户消息。"
        expected = builder.build(
            history=history,
            current_message=current,
            system_prompt=system_prompt,
            context_frame="",
            channel=None,
            message_timestamp=fixed_time,
            media=None,
        )
        expected[-1] = {"content": current, "role": "user"}
        cases.append(
            {
                "expected": {"messages": expected},
                "id": case_id,
                "input": {
                    "currentMessage": current,
                    "history": history,
                    "systemPrompt": system_prompt,
                },
            }
        )
    return {
        "cases": cases,
        "formatVersion": FORMAT_VERSION,
        "normalization": [
            {
                "field": "expected.messages[last].content",
                "rule": "remove-python-current-message-time-envelope",
            }
        ],
        "pythonEvidence": {
            "callable": "agent.context.MessageEnvelopeBuilder.build",
            "path": "agent/context.py",
            "projection": "system -> history -> current user；不含 Context Frame、渠道与媒体",
        },
        "source": "python-reference",
        "suite": "prompt",
    }


def build_sqlite_fixture() -> dict[str, Any]:
    from session.store import SessionStore

    with tempfile.TemporaryDirectory(prefix="namei-golden-") as directory:
        database = Path(directory) / "sessions.db"
        store = SessionStore(database)
        store.upsert_session(
            "python-demo",
            created_at="2026-07-13T08:00:00+08:00",
            updated_at="2026-07-13T08:00:01+08:00",
            last_consolidated=0,
            metadata={"origin": "python"},
        )
        store.insert_message(
            "python-demo",
            role="user",
            content="Python 问题",
            ts="2026-07-13T08:00:00+08:00",
            seq=0,
        )
        store.insert_message(
            "python-demo",
            role="assistant",
            content="Python 回答",
            ts="2026-07-13T08:00:01+08:00",
            seq=1,
        )
        store.close()
        initial = database_state(database)

        store = SessionStore(database)
        store.insert_message(
            "python-demo",
            role="user",
            content="Java 问题",
            ts="2026-07-13T08:01:00+08:00",
            seq=2,
        )
        store.insert_message(
            "python-demo",
            role="assistant",
            content="Java 回答",
            ts="2026-07-13T08:01:01+08:00",
            seq=3,
        )
        store.upsert_session(
            "python-demo",
            created_at="2026-07-13T08:00:00+08:00",
            updated_at="2026-07-13T08:01:01+08:00",
            last_consolidated=0,
            metadata={"origin": "python"},
        )
        store.close()
        after_append = database_state(database)

    return {
        "cases": [
            {
                "expectedAfterAppend": after_append,
                "expectedInitial": initial,
                "id": "python-session-store-core",
                "javaAppend": {
                    "assistant": "Java 回答",
                    "assistantAt": "2026-07-13T08:01:01+08:00",
                    "user": "Java 问题",
                    "userAt": "2026-07-13T08:01:00+08:00",
                },
            }
        ],
        "formatVersion": FORMAT_VERSION,
        "normalization": [
            {"field": "sqlite_master", "rule": "include-core-tables-only"},
            {"field": "rows", "rule": "explicit-column-order-and-sequence-sort"},
            {"field": "last_user_at", "rule": "excluded-until-python-java-write-contract"},
        ],
        "pythonEvidence": {
            "callable": "session.store.SessionStore",
            "path": "session/store.py",
            "projection": "sessions/messages 核心 Schema、游标和文本轮次",
        },
        "source": "python-reference",
        "suite": "sqlite",
    }


def database_state(database: Path) -> dict[str, Any]:
    connection = sqlite3.connect(database)
    connection.row_factory = sqlite3.Row
    try:
        return {
            "messages": [
                dict(row)
                for row in connection.execute(
                    """
                    SELECT id, session_key, seq, role, content, tool_chain, extra, ts
                    FROM messages ORDER BY session_key, seq
                    """
                )
            ],
            "schema": {
                "messages": table_schema(connection, "messages"),
                "sessions": table_schema(connection, "sessions"),
                "uniqueMessageKeys": unique_indexes(connection, "messages"),
            },
            "sessions": [
                dict(row)
                for row in connection.execute(
                    """
                    SELECT key, created_at, updated_at, last_consolidated, metadata, next_seq
                    FROM sessions ORDER BY key
                    """
                )
            ],
        }
    finally:
        connection.close()


def table_schema(connection: sqlite3.Connection, table: str) -> list[dict[str, Any]]:
    return [
        {
            "defaultValue": row["dflt_value"],
            "name": row["name"],
            "notNull": bool(row["notnull"]),
            "primaryKey": bool(row["pk"]),
            "type": row["type"],
        }
        for row in connection.execute(f"PRAGMA table_info({table})")
    ]


def unique_indexes(connection: sqlite3.Connection, table: str) -> list[list[str]]:
    indexes = []
    for row in connection.execute(f"PRAGMA index_list({table})"):
        if not row["unique"] or row["origin"] != "u":
            continue
        columns = [
            item["name"]
            for item in connection.execute(f"PRAGMA index_info({json.dumps(row['name'])})")
        ]
        indexes.append(columns)
    return sorted(indexes)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(rendered, encoding="utf-8")
    temporary.replace(path)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(repository: Path, *arguments: str) -> str:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


if __name__ == "__main__":
    main()
