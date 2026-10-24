"""Unit tests for LLM discovery tools and error exception formatting."""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import httpx
import pytest

from blender_mcp.app import get_app


class TestLlmDiscoveryErrors:
    @pytest.mark.asyncio
    async def test_list_local_models_empty_exception_message(self):
        """Test that list_local_models formats exceptions with empty messages using their class name."""
        app = get_app()

        # We want to mock httpx.AsyncClient.get to raise ConnectTimeout with an empty message
        # and another to raise ConnectError with a message to check both behaviors.
        timeout_err = httpx.ConnectTimeout("")
        connect_err = httpx.ConnectError("Connection refused")

        async def mock_get(url, *args, **kwargs):
            if "11434" in url:
                raise timeout_err
            elif "1234" in url:
                raise connect_err
            return AsyncMock()

        with patch("httpx.AsyncClient.get", side_effect=mock_get):
            # Call the registered tool
            tool_result = await app.call_tool("list_local_models")

            # The tool returns a CallToolResult whose content is a TextContent with JSON or a dict.
            # Wait, let's see how app.call_tool behaves in the rest of the tests.
            # In test_phase3_tools.py, it got the result:
            # result = await app.call_tool("blender_ai_generate", {"operation": "list_backends"})
            # text = result.content[0].text
            # But wait! list_local_models is registered via @app.tool() without returning JSON string.
            # Wait, let's check its definition:
            # @app.tool()
            # async def list_local_models() -> dict[str, Any]:
            # If it returns a dict directly, FastMCP returns it as a JSON string inside result.content[0].text
            # or as a structured response. Let's see how we can inspect it.

            import json

            text = tool_result.content[0].text
            data = json.loads(text)

            assert data["success"] is True
            errors = data["result"]["errors"]
            assert "Ollama: ConnectTimeout" in errors
            assert "LM Studio: ConnectError: Connection refused" in errors
