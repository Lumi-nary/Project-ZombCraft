# blender-mcp

Blender 3D automation via FastMCP — modeling, animation, rendering, scene management, materials, and more.

## Before starting work:
1. Check available tools: `blender_help(operation="list_tools")`
2. Check Blender connectivity: `blender_status(operation="status")`

## Key tools:
- `blender_mesh` — create/modify mesh objects (18 operations)
- `blender_scene` — scene and collection management (12 operations)
- `blender_animation` — keyframes, actions, NLA, shape keys (21 operations)
- `blender_render` — preview, turntable, multi-angle, engine config
- `blender_export` — glTF, FBX, OBJ, STL, USD, VRM, Unity, VRChat, Unreal
- `blender_download` — download models/assets from Poly Haven and Sketchfab
- `blender_ai_generate` — AI mesh generation via Tripo/Rodin/Hunyuan3D
- `agentic_blender_workflow` — multi-step autonomous 3D creation

## At end of work:
- Save progress: use `blender_workflow(operation="execute", ...)` for complex scenes
