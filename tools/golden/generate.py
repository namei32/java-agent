#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

FORMAT_VERSION = 1
REFERENCE_FILES = (
    "agent/config.py",
    "agent/config_models.py",
    "config.example.toml",
    "proactive_v2/config.py",
    "proactive_v2/config_loader.py",
    "session/manager.py",
    "session/store.py",
    "agent/context.py",
    "agent/provider.py",
    "agent/core/passive_turn.py",
    "agent/tool_runtime.py",
    "agent/tools/base.py",
    "agent/tools/registry.py",
    "agent/tool_hooks/base.py",
    "agent/tool_hooks/executor.py",
    "agent/tool_hooks/types.py",
    "bus/events_lifecycle.py",
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
    configuration = build_configuration_fixture()
    configuration_validation = build_configuration_validation_fixture()
    tool_messages = build_tool_message_fixture()
    tool_loop = build_tool_loop_fixture()
    tool_runtime_safety = build_tool_runtime_safety_fixture()
    tool_approval = build_tool_approval_fixture()

    write_json(output / "history/session-history.json", history)
    write_json(output / "prompt/message-envelope.json", prompt)
    write_json(output / "sqlite/session-store.json", sqlite)
    write_json(output / "configuration/config-resolution.json", configuration)
    write_json(
        output / "configuration/config-validation.json", configuration_validation
    )
    write_json(output / "tools/message-envelope.json", tool_messages)
    write_json(output / "tools/minimal-loop.json", tool_loop)
    write_json(output / "tools/runtime-safety.json", tool_runtime_safety)
    write_json(output / "tools/approval-side-effects.json", tool_approval)

    errors = output / "errors/http-error-mapping.json"
    if not errors.is_file():
        raise SystemExit(f"缺少人工维护的错误迁移契约夹具: {errors}")

    fixtures = []
    for fixture_id, relative_path, source in (
        ("history/session-history", "history/session-history.json", "python-reference"),
        ("prompt/message-envelope", "prompt/message-envelope.json", "python-reference"),
        ("sqlite/session-store", "sqlite/session-store.json", "python-reference"),
        (
            "configuration/config-resolution",
            "configuration/config-resolution.json",
            "python-reference",
        ),
        (
            "configuration/config-validation",
            "configuration/config-validation.json",
            "migration-contract",
        ),
        ("tools/message-envelope", "tools/message-envelope.json", "python-reference"),
        ("tools/minimal-loop", "tools/minimal-loop.json", "migration-contract"),
        (
            "tools/runtime-safety",
            "tools/runtime-safety.json",
            "migration-contract",
        ),
        (
            "tools/approval-side-effects",
            "tools/approval-side-effects.json",
            "migration-contract",
        ),
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


def build_configuration_fixture() -> dict[str, Any]:
    from agent.config import load_config

    synthetic_secret = "__GOLDEN_SECRET__"
    cases = [
        configuration_case(
            load_config,
            "modern-deepseek",
            """[llm]
provider = "deepseek"

[llm.main]
model = "deepseek-chat"
api_key = "__GOLDEN_SECRET__"
base_url = "https://api.deepseek.com/v1"

[agent]
system_prompt = "你是配置兼容测试助手。"

[agent.context]
memory_window = 24
""",
        ),
        configuration_case(
            load_config,
            "legacy-root",
            """provider = "openai"
model = "legacy-model"
api_key = "__GOLDEN_SECRET__"
base_url = "https://legacy.example.test/v1"
system_prompt = "旧版系统提示。"
memory_window = 12
""",
        ),
        configuration_case(
            load_config,
            "modern-overrides-legacy",
            """provider = "legacy-provider"
model = "legacy-model"
api_key = "__LEGACY_SECRET__"
base_url = "https://legacy.example.test/v1"
system_prompt = "旧版提示。"
memory_window = 8

[llm]
provider = "deepseek"

[llm.main]
model = "modern-model"
api_key = "__GOLDEN_SECRET__"
base_url = "https://modern.example.test/v1"

[agent]
system_prompt = "现代提示。"

[agent.context]
memory_window = 16
""",
        ),
        configuration_case(
            load_config,
            "blank-modern-falls-back-to-legacy",
            """provider = "openai"
model = "legacy-model"
api_key = "__GOLDEN_SECRET__"
base_url = "https://legacy.example.test/v1"
system_prompt = "旧版提示。"

[llm]
provider = ""

[llm.main]
model = ""
api_key = ""
base_url = ""

[agent]
system_prompt = ""
""",
        ),
        configuration_case(
            load_config,
            "qwen-provider-preset",
            """[llm]
provider = "qwen"

[llm.main]
model = "qwen-plus"
api_key = "__GOLDEN_SECRET__"
""",
        ),
        configuration_case(
            load_config,
            "api-key-environment",
            """[llm]
provider = "openai"

[llm.main]
model = "gpt-compatible"
api_key = "${NAMEI_GOLDEN_MODEL_KEY}"
""",
            {"NAMEI_GOLDEN_MODEL_KEY": synthetic_secret},
        ),
        configuration_case(
            load_config,
            "deferred-and-unknown-fields",
            """[llm]
provider = "deepseek"

[llm.main]
model = "deepseek-chat"
api_key = "__GOLDEN_SECRET__"
enable_thinking = true

[llm.fast]
model = "deferred-fast-model"
api_key = "__DEFERRED_SECRET__"

[agent]
max_iterations = 20

[agent.tools]
search_enabled = true

[plugins.example]
enabled = true
credential = "__DEFERRED_SECRET__"

[future_extension]
unknown_value = "保留但不激活"
""",
        ),
    ]
    return {
        "cases": cases,
        "formatVersion": FORMAT_VERSION,
        "normalization": [
            {
                "field": "expected.active.apiKey",
                "rule": "replace-secret-with-presence-status",
            }
        ],
        "pythonEvidence": {
            "callable": "agent.config.load_config",
            "path": "agent/config.py",
            "projection": "主模型活动字段；API Key 只保留 PRESENT/MISSING/UNRESOLVED 状态",
        },
        "source": "python-reference",
        "suite": "configuration",
    }


def configuration_case(
    load_config: Any,
    case_id: str,
    toml: str,
    environment: dict[str, str] | None = None,
) -> dict[str, Any]:
    environment = environment or {}
    with tempfile.TemporaryDirectory(prefix="namei-config-golden-") as directory:
        path = Path(directory) / "config.toml"
        path.write_text(toml, encoding="utf-8")
        original_hash = sha256(path)
        with temporary_environment(environment):
            config = load_config(path)
        if sha256(path) != original_hash:
            raise RuntimeError(f"Python 配置加载器修改了输入文件: {case_id}")

    return {
        "expected": {
            "active": {
                "apiKeyStatus": secret_status(config.api_key),
                "baseUrl": config.base_url or "",
                "historyMaxMessages": config.memory_window,
                "model": config.model,
                "provider": config.provider,
                "systemPrompt": config.system_prompt,
            }
        },
        "id": case_id,
        "input": {
            "environment": environment,
            "toml": toml,
            "tomlSha256": hashlib.sha256(toml.encode("utf-8")).hexdigest(),
        },
        "pythonInvocation": {"callable": "agent.config.load_config"},
    }


def build_configuration_validation_fixture() -> dict[str, Any]:
    cases = [
        configuration_validation_case(
            "missing-required-model",
            """[llm]
provider = "deepseek"

[llm.main]
api_key = "__GOLDEN_SECRET__"
""",
            [{"code": "CONFIG_REQUIRED_MISSING", "field": "llm.main.model"}],
        ),
        configuration_validation_case(
            "unresolved-api-key-environment",
            """[llm]
provider = "deepseek"

[llm.main]
model = "deepseek-chat"
api_key = "${NAMEI_GOLDEN_MISSING_KEY}"
""",
            [{"code": "CONFIG_ENV_UNRESOLVED", "field": "llm.main.api_key"}],
        ),
        configuration_validation_case(
            "invalid-memory-window-type",
            """[llm]
provider = "deepseek"

[llm.main]
model = "deepseek-chat"
api_key = "__GOLDEN_SECRET__"

[agent.context]
memory_window = "forty"
""",
            [
                {
                    "code": "CONFIG_TYPE_INVALID",
                    "field": "agent.context.memory_window",
                }
            ],
        ),
        configuration_validation_case(
            "invalid-base-url",
            """[llm]
provider = "deepseek"

[llm.main]
model = "deepseek-chat"
api_key = "__GOLDEN_SECRET__"
base_url = "ftp://invalid.example.test/v1"
""",
            [{"code": "CONFIG_URL_INVALID", "field": "llm.main.base_url"}],
        ),
        configuration_validation_case(
            "invalid-toml",
            """[llm]
provider = ["deepseek"
""",
            [{"code": "CONFIG_TOML_INVALID", "field": "$document"}],
            toml_syntax="INVALID",
        ),
    ]
    return {
        "cases": cases,
        "formatVersion": FORMAT_VERSION,
        "normalization": [
            {
                "field": "expected.diagnostics",
                "rule": "stable-code-and-field-only",
            }
        ],
        "pythonEvidence": {
            "callable": "agent.config.load_config",
            "path": "agent/config.py",
            "projection": "Python 仅提供迁移来源；严格类型、URL 和未展开密钥是已批准 Java 安全契约",
        },
        "source": "migration-contract",
        "suite": "configuration",
    }


def configuration_validation_case(
    case_id: str,
    toml: str,
    diagnostics: list[dict[str, str]],
    *,
    toml_syntax: str = "VALID",
) -> dict[str, Any]:
    return {
        "expected": {"diagnostics": diagnostics, "tomlSyntax": toml_syntax},
        "id": case_id,
        "input": {"environment": {}, "toml": toml},
    }


def secret_status(secret: str) -> str:
    if not secret:
        return "MISSING"
    if secret.startswith("${") and secret.endswith("}"):
        return "UNRESOLVED"
    return "PRESENT"


@contextmanager
def temporary_environment(values: dict[str, str]):
    previous = {key: os.environ.get(key) for key in values}
    try:
        os.environ.update(values)
        yield
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


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


def build_tool_message_fixture() -> dict[str, Any]:
    from agent.provider import ToolCall
    from agent.tool_runtime import (
        append_assistant_tool_calls,
        append_tool_result,
        build_tool_schemas,
    )
    from agent.tools.base import Tool

    class GoldenLookupTool(Tool):
        name = "golden_lookup"
        description = "查询固定 Golden 数据"
        parameters = {
            "additionalProperties": False,
            "properties": {"query": {"type": "string"}},
            "required": ["query"],
            "type": "object",
        }

        async def execute(self, **kwargs: Any) -> str:
            return "固定工具结果"

    definitions = build_tool_schemas([GoldenLookupTool()])
    cases = [
        tool_message_case(
            append_assistant_tool_calls,
            append_tool_result,
            ToolCall,
            "single-tool-success",
            None,
            [
                {
                    "arguments": {"query": "配置"},
                    "id": "call-001",
                    "name": "golden_lookup",
                    "result": "固定工具结果",
                }
            ],
            definitions,
        ),
        tool_message_case(
            append_assistant_tool_calls,
            append_tool_result,
            ToolCall,
            "multiple-tools-preserve-order",
            "正在查询",
            [
                {
                    "arguments": {"query": "第一项"},
                    "id": "call-101",
                    "name": "golden_lookup",
                    "result": "第一项结果",
                },
                {
                    "arguments": {"query": "第二项"},
                    "id": "call-102",
                    "name": "golden_lookup",
                    "result": "第二项结果",
                },
            ],
            definitions,
        ),
    ]
    return {
        "cases": cases,
        "formatVersion": FORMAT_VERSION,
        "normalization": [
            {
                "field": "expected.messages[].tool_calls[].function.arguments",
                "rule": "parse-json-object-before-cross-language-comparison",
            }
        ],
        "pythonEvidence": {
            "callables": [
                "agent.tool_runtime.build_tool_schemas",
                "agent.tool_runtime.append_assistant_tool_calls",
                "agent.tool_runtime.append_tool_result",
            ],
            "path": "agent/tool_runtime.py",
            "projection": "OpenAI-compatible Tool Definition、Assistant Tool Call 和 Tool Result 文本消息",
        },
        "source": "python-reference",
        "suite": "tools",
    }


def tool_message_case(
    append_assistant_tool_calls: Any,
    append_tool_result: Any,
    tool_call_type: Any,
    case_id: str,
    content: str | None,
    calls: list[dict[str, Any]],
    definitions: list[dict[str, Any]],
) -> dict[str, Any]:
    tool_calls = [
        tool_call_type(item["id"], item["name"], dict(item["arguments"]))
        for item in calls
    ]
    messages: list[dict[str, Any]] = []
    append_assistant_tool_calls(
        messages, content=content, tool_calls=tool_calls, provider_fields=None
    )
    for item in calls:
        append_tool_result(
            messages,
            tool_call_id=item["id"],
            content=item["result"],
            tool_name=item["name"],
        )
    return {
        "expected": {"messages": messages},
        "id": case_id,
        "input": {
            "assistantContent": content,
            "toolCalls": [
                {
                    "arguments": item["arguments"],
                    "id": item["id"],
                    "name": item["name"],
                }
                for item in calls
            ],
            "toolDefinitions": definitions,
            "toolResults": [
                {
                    "callId": item["id"],
                    "content": item["result"],
                    "name": item["name"],
                }
                for item in calls
            ],
        },
    }


def build_tool_loop_fixture() -> dict[str, Any]:
    return {
        "cases": [
            tool_loop_case(
                "direct-answer",
                3,
                [],
                [{"content": "直接回答", "toolCalls": []}],
                "COMPLETED",
                "直接回答",
                True,
                [],
                [
                    event("TURN_STARTED", 0),
                    event("MODEL_REQUESTED", 1),
                    event("MODEL_COMPLETED", 1, status="FINAL"),
                    event("TURN_COMMITTING", 0),
                    event("TURN_COMMITTED", 0),
                ],
            ),
            tool_loop_case(
                "single-tool-success",
                3,
                [tool_behavior("lookup", "SUCCESS", "固定结果")],
                [
                    model_tool_calls(tool_call("call-001", "lookup", {"query": "配置"})),
                    {"content": "查询完成", "toolCalls": []},
                ],
                "COMPLETED",
                "查询完成",
                True,
                ["lookup"],
                tool_success_trace([(1, "call-001", "lookup", "SUCCESS")], final_iteration=2),
            ),
            tool_loop_case(
                "multiple-tools-preserve-order",
                3,
                [
                    tool_behavior("first", "SUCCESS", "第一结果"),
                    tool_behavior("second", "SUCCESS", "第二结果"),
                ],
                [
                    model_tool_calls(
                        tool_call("call-101", "first", {}),
                        tool_call("call-102", "second", {}),
                    ),
                    {"content": "两个工具都已完成", "toolCalls": []},
                ],
                "COMPLETED",
                "两个工具都已完成",
                True,
                ["first", "second"],
                tool_success_trace(
                    [
                        (1, "call-101", "first", "SUCCESS"),
                        (1, "call-102", "second", "SUCCESS"),
                    ],
                    final_iteration=2,
                ),
            ),
            tool_loop_case(
                "unknown-tool-recovers",
                3,
                [],
                [
                    model_tool_calls(tool_call("call-201", "missing", {})),
                    {"content": "工具不可用，改为直接回答", "toolCalls": []},
                ],
                "COMPLETED",
                "工具不可用，改为直接回答",
                True,
                ["missing"],
                tool_success_trace(
                    [(1, "call-201", "missing", "ERROR")], final_iteration=2
                ),
            ),
            tool_loop_case(
                "tool-error-recovers",
                3,
                [tool_behavior("failing", "ERROR", "不应泄露的内部异常")],
                [
                    model_tool_calls(tool_call("call-301", "failing", {})),
                    {"content": "工具失败，给出替代回答", "toolCalls": []},
                ],
                "COMPLETED",
                "工具失败，给出替代回答",
                True,
                ["failing"],
                tool_success_trace(
                    [(1, "call-301", "failing", "ERROR")], final_iteration=2
                ),
            ),
            tool_loop_case(
                "invalid-model-response",
                2,
                [],
                [{"content": "", "toolCalls": []}],
                "INVALID_MODEL_RESPONSE",
                None,
                False,
                [],
                [
                    event("TURN_STARTED", 0),
                    event("MODEL_REQUESTED", 1),
                    event("MODEL_COMPLETED", 1, status="INVALID"),
                    event("TURN_FAILED", 0, status="INVALID_MODEL_RESPONSE"),
                ],
            ),
            tool_loop_case(
                "iteration-limit-does-not-commit",
                2,
                [tool_behavior("lookup", "SUCCESS", "固定结果")],
                [
                    model_tool_calls(tool_call("call-401", "lookup", {"step": 1})),
                    model_tool_calls(tool_call("call-402", "lookup", {"step": 2})),
                ],
                "TOOL_LOOP_LIMIT_EXCEEDED",
                None,
                False,
                ["lookup", "lookup"],
                [
                    event("TURN_STARTED", 0),
                    event("MODEL_REQUESTED", 1),
                    event("MODEL_COMPLETED", 1, status="TOOL_CALLS"),
                    event("TOOL_CALL_STARTED", 1, "call-401", "lookup"),
                    event(
                        "TOOL_CALL_COMPLETED",
                        1,
                        "call-401",
                        "lookup",
                        "SUCCESS",
                    ),
                    event("MODEL_REQUESTED", 2),
                    event("MODEL_COMPLETED", 2, status="TOOL_CALLS"),
                    event("TOOL_CALL_STARTED", 2, "call-402", "lookup"),
                    event(
                        "TOOL_CALL_COMPLETED",
                        2,
                        "call-402",
                        "lookup",
                        "SUCCESS",
                    ),
                    event("TURN_FAILED", 0, status="TOOL_LOOP_LIMIT_EXCEEDED"),
                ],
            ),
        ],
        "formatVersion": FORMAT_VERSION,
        "normalization": [
            {
                "field": "expected.trace",
                "rule": "exclude-message-arguments-results-time-and-exception-details",
            },
            {
                "field": "input.tools[].result",
                "rule": "synthetic-values-only-never-copied-to-expected-trace",
            },
        ],
        "pythonEvidence": {
            "callables": [
                "agent.core.passive_turn.AgentReasoner.run",
                "agent.tools.registry.ToolRegistry.execute",
                "bus.events_lifecycle.ToolCallStarted",
                "bus.events_lifecycle.ToolCallCompleted",
            ],
            "path": "agent/core/passive_turn.py",
            "projection": "Python 提供循环来源；迭代上限、提交决策和安全事件字段按已批准迁移契约固定",
        },
        "source": "migration-contract",
        "suite": "tools",
    }


def tool_loop_case(
    case_id: str,
    max_iterations: int,
    tools: list[dict[str, Any]],
    model_responses: list[dict[str, Any]],
    outcome: str,
    assistant: str | None,
    committed: bool,
    execution_order: list[str],
    trace: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "expected": {
            "assistant": assistant,
            "committed": committed,
            "executionOrder": execution_order,
            "outcome": outcome,
            "trace": trace,
        },
        "id": case_id,
        "input": {
            "maxIterations": max_iterations,
            "modelResponses": model_responses,
            "tools": tools,
        },
    }


def tool_behavior(name: str, behavior: str, result: str) -> dict[str, str]:
    return {"behavior": behavior, "name": name, "result": result}


def tool_call(call_id: str, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    return {"arguments": arguments, "id": call_id, "name": name}


def model_tool_calls(*calls: dict[str, Any]) -> dict[str, Any]:
    return {"content": None, "toolCalls": list(calls)}


def event(
    event_type: str,
    iteration: int,
    call_id: str | None = None,
    tool_name: str | None = None,
    status: str | None = None,
) -> dict[str, Any]:
    value: dict[str, Any] = {"iteration": iteration, "type": event_type}
    if call_id is not None:
        value["callId"] = call_id
    if tool_name is not None:
        value["toolName"] = tool_name
    if status is not None:
        value["status"] = status
    return value


def tool_success_trace(
    calls: list[tuple[int, str, str, str]], *, final_iteration: int
) -> list[dict[str, Any]]:
    trace = [
        event("TURN_STARTED", 0),
        event("MODEL_REQUESTED", 1),
        event("MODEL_COMPLETED", 1, status="TOOL_CALLS"),
    ]
    for iteration, call_id, name, status in calls:
        trace.append(event("TOOL_CALL_STARTED", iteration, call_id, name))
        trace.append(event("TOOL_CALL_COMPLETED", iteration, call_id, name, status))
    trace.extend(
        [
            event("MODEL_REQUESTED", final_iteration),
            event("MODEL_COMPLETED", final_iteration, status="FINAL"),
            event("TURN_COMMITTING", 0),
            event("TURN_COMMITTED", 0),
        ]
    )
    return trace


def build_tool_runtime_safety_fixture() -> dict[str, Any]:
    empty_schema = {
        "additionalProperties": False,
        "properties": {},
        "type": "object",
    }
    strict_schema = {
        "additionalProperties": False,
        "properties": {
            "count": {"type": "integer"},
            "query": {"enum": ["allowed"], "type": "string"},
        },
        "required": ["query"],
        "type": "object",
    }
    return {
        "cases": [
            runtime_case(
                "response-call-limit",
                "APPLICATION",
                {
                    "modelResponses": [
                        model_tool_calls(
                            tool_call("limit-1", "lookup", {}),
                            tool_call("limit-2", "lookup", {}),
                        )
                    ],
                    "settings": runtime_settings(max_calls_per_response=1),
                    "tools": [runtime_tool("lookup", "SUCCESS", "结果", empty_schema)],
                },
                "TOOL_CALL_LIMIT_EXCEEDED",
                False,
                [],
                [],
                [1],
                [
                    event("TURN_STARTED", 0),
                    event("MODEL_REQUESTED", 1),
                    event("MODEL_COMPLETED", 1, status="TOOL_CALLS"),
                    event("TURN_FAILED", 0, status="TOOL_CALL_LIMIT_EXCEEDED"),
                ],
            ),
            runtime_case(
                "turn-call-limit",
                "APPLICATION",
                {
                    "modelResponses": [
                        model_tool_calls(
                            tool_call("turn-1", "lookup", {}),
                            tool_call("turn-2", "lookup", {}),
                        ),
                        model_tool_calls(tool_call("turn-3", "lookup", {})),
                    ],
                    "settings": runtime_settings(
                        max_calls_per_response=2, max_calls_per_turn=2
                    ),
                    "tools": [runtime_tool("lookup", "SUCCESS", "结果", empty_schema)],
                },
                "TOOL_CALL_LIMIT_EXCEEDED",
                False,
                ["lookup", "lookup"],
                [runtime_result("SUCCESS", "结果"), runtime_result("SUCCESS", "结果")],
                [1, 1],
                [
                    event("TURN_STARTED", 0),
                    event("MODEL_REQUESTED", 1),
                    event("MODEL_COMPLETED", 1, status="TOOL_CALLS"),
                    event("TOOL_CALL_STARTED", 1, "turn-1", "lookup"),
                    event("TOOL_CALL_COMPLETED", 1, "turn-1", "lookup", "SUCCESS"),
                    event("TOOL_CALL_STARTED", 1, "turn-2", "lookup"),
                    event("TOOL_CALL_COMPLETED", 1, "turn-2", "lookup", "SUCCESS"),
                    event("MODEL_REQUESTED", 2),
                    event("MODEL_COMPLETED", 2, status="TOOL_CALLS"),
                    event("TURN_FAILED", 0, status="TOOL_CALL_LIMIT_EXCEEDED"),
                ],
            ),
            runtime_case(
                "schema-argument-errors",
                "APPLICATION",
                {
                    "modelResponses": [
                        model_tool_calls(
                            tool_call("missing", "lookup", {}),
                            tool_call("type", "lookup", {"query": 1}),
                            tool_call("enum", "lookup", {"query": "denied"}),
                            tool_call(
                                "unknown",
                                "lookup",
                                {"extra": True, "query": "allowed"},
                            ),
                        ),
                        {"content": "参数已安全处理", "toolCalls": []},
                    ],
                    "settings": runtime_settings(),
                    "tools": [runtime_tool("lookup", "SUCCESS", "结果", strict_schema)],
                },
                "COMPLETED",
                True,
                [],
                [runtime_result("ERROR", "工具参数无效。")] * 4,
                [1, 1],
                tool_success_trace(
                    [
                        (1, "missing", "lookup", "ERROR"),
                        (1, "type", "lookup", "ERROR"),
                        (1, "enum", "lookup", "ERROR"),
                        (1, "unknown", "lookup", "ERROR"),
                    ],
                    final_iteration=2,
                ),
                assistant="参数已安全处理",
            ),
            runtime_case(
                "result-character-limit",
                "APPLICATION",
                {
                    "modelResponses": [
                        model_tool_calls(tool_call("result-1", "lookup", {})),
                        {"content": "结果已安全处理", "toolCalls": []},
                    ],
                    "settings": runtime_settings(max_result_characters=2),
                    "tools": [runtime_tool("lookup", "SUCCESS", "😀😀😀", empty_schema)],
                },
                "COMPLETED",
                True,
                ["lookup"],
                [runtime_result("ERROR", "工具结果超过大小限制。")],
                [1, 1],
                tool_success_trace([(1, "result-1", "lookup", "ERROR")], final_iteration=2),
                assistant="结果已安全处理",
            ),
            runtime_case(
                "tool-timeout-recovers",
                "APPLICATION",
                {
                    "modelResponses": [
                        model_tool_calls(tool_call("timeout-1", "timeout", {})),
                        {"content": "超时后恢复", "toolCalls": []},
                    ],
                    "settings": runtime_settings(timeout_millis=100),
                    "tools": [runtime_tool("timeout", "TIMEOUT", "不应暴露", empty_schema)],
                },
                "COMPLETED",
                True,
                ["timeout"],
                [runtime_result("TIMEOUT", "工具执行超时。")],
                [1, 1],
                tool_success_trace([(1, "timeout-1", "timeout", "TIMEOUT")], final_iteration=2),
                assistant="超时后恢复",
            ),
            runtime_case(
                "permit-wait-timeout",
                "REGISTRY_CONCURRENCY",
                {
                    "settings": runtime_settings(
                        max_concurrent_calls=1, timeout_millis=100
                    )
                },
                "PERMIT_TIMEOUT",
                False,
                ["limited"],
                [runtime_result("TIMEOUT", "工具执行超时。")],
                [],
                [],
            ),
            runtime_case(
                "cancel-active-tool",
                "APPLICATION",
                {
                    "cancelActiveTool": True,
                    "modelResponses": [
                        model_tool_calls(tool_call("cancel-1", "cancel", {}))
                    ],
                    "settings": runtime_settings(timeout_millis=1000),
                    "tools": [runtime_tool("cancel", "CANCEL", "不应暴露", empty_schema)],
                },
                "TURN_CANCELLED",
                False,
                ["cancel"],
                [],
                [1],
                [
                    event("TURN_STARTED", 0),
                    event("MODEL_REQUESTED", 1),
                    event("MODEL_COMPLETED", 1, status="TOOL_CALLS"),
                    event("TOOL_CALL_STARTED", 1, "cancel-1", "cancel"),
                    event("TOOL_CALL_COMPLETED", 1, "cancel-1", "cancel", "CANCELLED"),
                    event("TURN_FAILED", 0, status="TURN_CANCELLED"),
                ],
            ),
            runtime_case(
                "disabled-sends-no-definitions",
                "APPLICATION",
                {
                    "modelResponses": [{"content": "普通回答", "toolCalls": []}],
                    "settings": runtime_settings(mode="DISABLED"),
                    "tools": [runtime_tool("lookup", "SUCCESS", "结果", empty_schema)],
                },
                "COMPLETED",
                True,
                [],
                [],
                [0],
                [
                    event("TURN_STARTED", 0),
                    event("MODEL_REQUESTED", 1),
                    event("MODEL_COMPLETED", 1, status="FINAL"),
                    event("TURN_COMMITTING", 0),
                    event("TURN_COMMITTED", 0),
                ],
                assistant="普通回答",
            ),
            {
                "expected": {"outcome": "INVALID_MODEL_RESPONSE"},
                "id": "arguments-byte-limit",
                "input": {
                    "maxArgumentBytes": len('{"city":"上海"}'.encode("utf-8")) - 1,
                    "rawArguments": '{"city":"上海"}',
                },
                "target": "ADAPTER",
            },
        ],
        "formatVersion": FORMAT_VERSION,
        "normalization": [
            {
                "field": "expected.trace",
                "rule": "exclude-message-arguments-results-time-and-exception-details",
            }
        ],
        "pythonEvidence": {
            "callables": [
                "agent.core.passive_turn.AgentReasoner.run",
                "agent.tools.registry.ToolRegistry.execute",
            ],
            "path": "agent/core/passive_turn.py",
            "projection": "Python 提供只读工具循环来源；安全预算、校验、超时、并发与取消按已批准 Java 契约固定",
        },
        "source": "migration-contract",
        "suite": "tool-runtime-safety",
    }


def runtime_case(
    case_id: str,
    target: str,
    input_value: dict[str, Any],
    outcome: str,
    committed: bool,
    executions: list[str],
    tool_results: list[dict[str, str]],
    definition_counts: list[int],
    trace: list[dict[str, Any]],
    *,
    assistant: str | None = None,
) -> dict[str, Any]:
    return {
        "expected": {
            "assistant": assistant,
            "committed": committed,
            "definitionCounts": definition_counts,
            "executions": executions,
            "outcome": outcome,
            "toolResults": tool_results,
            "trace": trace,
        },
        "id": case_id,
        "input": input_value,
        "target": target,
    }


def runtime_settings(
    *,
    mode: str = "READ_ONLY",
    max_calls_per_response: int = 8,
    max_calls_per_turn: int = 16,
    timeout_millis: int = 5000,
    max_concurrent_calls: int = 32,
    max_result_characters: int = 20000,
) -> dict[str, Any]:
    return {
        "maxCallsPerResponse": max_calls_per_response,
        "maxCallsPerTurn": max_calls_per_turn,
        "maxConcurrentCalls": max_concurrent_calls,
        "maxResultCharacters": max_result_characters,
        "mode": mode,
        "timeoutMillis": timeout_millis,
    }


def runtime_tool(
    name: str,
    behavior: str,
    result: str,
    schema: dict[str, Any],
) -> dict[str, Any]:
    return {
        "behavior": behavior,
        "name": name,
        "result": result,
        "schema": schema,
    }


def runtime_result(status: str, content: str) -> dict[str, str]:
    return {"content": content, "status": status}


def build_tool_approval_fixture() -> dict[str, Any]:
    from agent.tool_hooks.base import ToolHook
    from agent.tool_hooks.executor import ToolExecutor
    from agent.tool_hooks.types import HookOutcome, ToolExecutionRequest
    from agent.tools.base import Tool
    from agent.tools.registry import ToolRegistry

    class GoldenTool(Tool):
        name = "golden_tool"
        description = "Golden 审批协议测试工具"
        parameters = {
            "additionalProperties": False,
            "properties": {},
            "type": "object",
        }

        def __init__(self, name: str) -> None:
            self.name = name

        async def execute(self, **kwargs: Any) -> str:
            return "固定结果"

    registry = ToolRegistry()
    for name, risk in (
        ("read_probe", "read-only"),
        ("write_probe", "write"),
        ("external_probe", "external-side-effect"),
    ):
        registry.register(GoldenTool(name), risk=risk)
    risk_projection = [
        {"name": document.name, "risk": document.risk}
        for document in registry.get_documents()
    ]

    class DenyHook(ToolHook):
        name = "golden-deny"
        event = "pre_tool_use"

        def matches(self, ctx: Any) -> bool:
            return True

        async def run(self, ctx: Any) -> HookOutcome:
            return HookOutcome(decision="deny", reason="固定拒绝")

    invocations = 0

    async def invoker(name: str, arguments: dict[str, Any]) -> str:
        nonlocal invocations
        invocations += 1
        return "不应执行"

    denied = asyncio.run(
        ToolExecutor([DenyHook()]).execute(
            ToolExecutionRequest(
                call_id="python-call-1",
                tool_name="write_probe",
                arguments={},
                source="passive",
            ),
            invoker,
        )
    )

    migration_cases = [
        approval_case(
            "read-only-without-approval",
            "READ_ONLY_EXECUTION",
            {"executions": 1, "outcome": "SUCCESS"},
        ),
        approval_case(
            "mixed-batch-denied",
            "MIXED_BATCH_DENIAL",
            {
                "executions": 0,
                "statuses": ["SKIPPED", "DENIED"],
            },
        ),
        approval_case(
            "approved-once-with-lifecycle",
            "APPROVED_EXECUTION",
            {
                "executions": 1,
                "ledgerState": "SUCCEEDED",
                "outcome": "SUCCESS",
                "trace": [
                    "TOOL_CALL_STARTED",
                    "APPROVAL_REQUESTED",
                    "APPROVAL_RESOLVED:APPROVED",
                    "SIDE_EFFECT_STARTED",
                    "SIDE_EFFECT_COMPLETED:SUCCESS",
                    "TOOL_CALL_COMPLETED:SUCCESS",
                ],
            },
        ),
        approval_case(
            "approval-binding-change-rejected",
            "STALE_APPROVAL",
            {"executions": 0, "outcome": "APPROVAL_UNAVAILABLE"},
        ),
        approval_case(
            "idempotent-replay",
            "IDEMPOTENT_REPLAY",
            {
                "executions": 1,
                "ledgerState": "SUCCEEDED",
                "outcome": "SUCCESS",
            },
        ),
        approval_case(
            "unknown-state-stops",
            "UNKNOWN_REPLAY",
            {"executions": 0, "outcome": "SIDE_EFFECT_STATE_UNKNOWN"},
        ),
        approval_case(
            "mode-definition-visibility",
            "MODE_VISIBILITY",
            {
                "approvalRequired": 2,
                "disabled": 0,
                "readOnly": 1,
            },
        ),
    ]

    return {
        "cases": [
            {
                "expected": {"tools": risk_projection},
                "id": "python-risk-labels",
                "input": {"risks": ["read-only", "write", "external-side-effect"]},
                "source": "python-reference",
            },
            {
                "expected": {"invocations": invocations, "status": denied.status},
                "id": "python-pre-hook-denial",
                "input": {"decision": "deny", "tool": "write_probe"},
                "source": "python-reference",
            },
            *migration_cases,
        ],
        "formatVersion": FORMAT_VERSION,
        "normalization": [
            {
                "field": "cases[].expected",
                "rule": "synthetic-identifiers-and-stable-statuses-only",
            }
        ],
        "pythonEvidence": {
            "callables": [
                "agent.tools.registry.ToolRegistry.register",
                "agent.tools.registry.ToolRegistry.get_documents",
                "agent.tool_hooks.executor.ToolExecutor.execute",
            ],
            "paths": [
                "agent/tools/registry.py",
                "agent/tool_hooks/executor.py",
            ],
            "projection": "Python 真实生成风险标签与 pre_tool_use 拒绝投影；审批指纹、一次性消费、Ledger 和安全生命周期为批准的 Java migration-contract",
        },
        "source": "migration-contract",
        "suite": "tool-approval-side-effects",
    }


def approval_case(
    case_id: str, scenario: str, expected: dict[str, Any]
) -> dict[str, Any]:
    return {
        "expected": expected,
        "id": case_id,
        "input": {"scenario": scenario},
        "source": "migration-contract",
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
