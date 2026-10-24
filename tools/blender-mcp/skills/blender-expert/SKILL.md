---
name: blender-expert
description: Expert Blender 3D modeling, animation, rendering, and pipeline automation. Use for any Blender-related task from simple primitives to complex procedural workflows.
---

# Blender Expert Skill

**Description:** Comprehensive Blender expertise covering modeling, animation, materials, rendering, scripting, scene management, and pipeline automation. Handles the full 3D content creation workflow.

## Trigger Phrases

- "Create a [object]" / "Model a [object]"
- "Add material to [object]"
- "Render the scene"
- "Animate [object] along a path"
- "Export to [format]"
- "Optimize scene for [platform]"
- "Apply modifier [type] to [object]"
- "Set up lighting for [scenario]"
- "Batch process all [object]"
- "Create a robot / character / building"
- "Grease pencil animation"
- "UV unwrap [object]"
- "Rig [object] for animation"

## Tool Categories

### Scene & Object Management
- `blender_status(operation="status")` — Server health, Blender connectivity
- `scene_get_hierarchy()` — Full scene tree: collections, objects, types
- `manage_object_repo(operation=...)` — Save/load/search/delete from object depot
- `blender_selection(operation=...)` — Select, deselect, toggle objects by type/collection

### Modeling & Construction
- `construct_object(description=..., complexity=..., style_preset=...)` — Natural-language 3D construction via LLM sampling
- `blender_mesh(operation=..., ...)` — Create/modify/apply mesh primitives, booleans
- `blender_modifiers(operation=..., ...)` — Apply decimate, subdivision, mirror, array, solidify, bevel, etc.
- `blender_sculpt(operation=..., ...)` — Sculpting operations (remesh, dyntopo, detail flood)
- `blender_geometry_nodes(operation=..., ...)` — Geometry node groups, inputs, execution

### Materials & Textures
- `blender_materials(operation=..., ...)` — Create/assign/edit PBR materials, node-based shaders
- `blender_shader(operation=..., ...)` — Shader node graph operations
- `blender_textures(operation=..., ...)` — Texture baking, image management
- `material_apply(object=..., material=...)` — Quick material assignment
- `blender_uv(operation=..., ...)` — UV unwrap, pack, smart project

### Animation
- `blender_animation(operation=..., ...)` — Keyframes, motion paths, NLA tracks, F-curves
- `blender_rigging(operation=..., ...)` — Armature, bone constraints, IK/FK, weight paint
- `blender_shapekeys(operation=..., ...)` — Shape key create/edit/animate

### Lighting & Camera
- `blender_lighting(operation=..., ...)` — Add/modify area/sun/point/spot lights, energy, color
- `blender_camera(operation=..., ...)` — Camera create, settings, DOF, compositor background

### Rendering
- `blender_render(operation=..., ...)` — Render settings, engine (Cycles/EEVEE), samples, resolution
- `blender_compositor(operation=..., ...)` — Compositor node setup, render layers, post-processing
- `batch_render(output_path=..., ...)` — Multi-camera batch rendering

### Import/Export
- `blender_export(operation=..., ...)` — Export GLB, FBX, OBJ, STL, USD, ABC, PLY
- `download_and_import(url=..., import_into_scene=...)` — Download and import from URL
- `convert_format(input_path=..., output_format=...)` — Format conversion pipeline
- `blender_session(operation=..., ...)` — Blender file open/save/merge

### AI & Generative
- `generate_blender_script(prompt=..., model=..., ollama_url=...)` — Generate Python scripts via Ollama
- `blender_ai_generate(operation=..., prompt=..., backend=...)` — Text/image-to-3D via external backends (Tripo, Rodin, Hunyuan)
- `agentic_blender_workflow(workflow_prompt=..., ...)` — Autonomous multi-step 3D workflows
- `intelligent_3d_processing(prompt=..., ...)` — Smart batch operations with LLM planning

### Grease Pencil & 2D
- `blender_grease_pencil(operation=..., ...)` — Strokes, layers, convert to mesh
- `blender_animation_2d(operation=..., ...)` — 2D animation tools

### Pipeline & Batch
- `intelligent_3d_processing(prompt=...)` — Multi-step batch processing
- `optimize_3d_scene(target_platform=..., ...)` — Platform-specific optimization
- `blender_vse(operation=..., ...)` — Video Sequence Editor operations
- `blender_particles(operation=..., ...)` — Particle systems and emitters

### Physics & Simulation
- `blender_physics(operation=..., ...)` — Rigid body, cloth, soft body, fluid

### Addon & System
- `manage_blender_addons(operation=..., ...)` — Install/enable/disable/search addons
- `config_get()` / `config_set(...)` — Server configuration
- `blender_logs(operation=..., ...)` — Log viewer with filtering

## Workflows

### Basic Object Creation
1. Use `construct_object(description=..., complexity="standard")` for natural-language creation
2. Or use `blender_mesh(operation="add_primitive", type="...")` for direct primitives
3. Apply materials with `blender_materials(operation="assign", ...)`
4. Add lighting with `blender_lighting(operation="add", ...)`

### Scene Optimization for Export
1. Audit scene with `optimize_3d_scene(target_platform="...")`
2. Apply modifiers with `blender_modifiers(operation="apply", ...)`
3. Bake textures with `blender_textures(operation="bake", ...)`
4. Export with `blender_export(operation="export", format="...")`

### Animation Pipeline
1. Set up scene with modeling tools
2. Create rig with `blender_rigging(operation="create_armature", ...)`
3. Keyframe with `blender_animation(operation="insert_keyframe", ...)`
4. Render with `blender_render(operation="render", ...)`

### AI-Powered Generation
1. Describe the object in natural language
2. Use `construct_object(description=..., style_preset=...)` for LLM-driven creation
3. Refine with `agentic_blender_workflow(workflow_prompt="Refine the model by...")`
4. Apply materials and finalize

## Examples

- `construct_object(description="a medieval castle with towers and a gatehouse", complexity="complex", style_preset="realistic")`
- `blender_mesh(operation="add_primitive", type="cube", location=[0,0,0], size=2)`
- `blender_materials(operation="create", name="Gold", color=[0.8, 0.6, 0.1], metallic=1.0, roughness=0.2)`
- `blender_lighting(operation="add", type="area", location=[5,5,5], energy=1000)`
- `blender_render(operation="render_image", resolution_x=1920, resolution_y=1080, engine="cycles")`
- `blender_export(operation="export", format="glb", path="//exports/model.glb")`
- `manage_blender_addons(operation="search", query="node wrangler")`
