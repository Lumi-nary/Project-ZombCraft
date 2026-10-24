import asyncio

from blender_mcp.app import get_app


async def check():
    app = get_app()
    from blender_mcp.agentic import register_agentic_tools

    register_agentic_tools()

    # Try calling a simple tool
    try:
        result = await app.call_tool("blender_status", {})
        mcp_result = result.to_mcp_result()
        for _i, _val in enumerate(mcp_result):
            pass
    except Exception:
        pass


if __name__ == "__main__":
    asyncio.run(check())
