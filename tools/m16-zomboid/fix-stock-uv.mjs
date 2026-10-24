import fs from 'node:fs/promises';
import {Client} from 'file:///C:/Users/Maple/.codex/tools/blockbench/blockbench-mcp-plugin-1.10.1/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import {StreamableHTTPClientTransport} from 'file:///C:/Users/Maple/.codex/tools/blockbench/blockbench-mcp-plugin-1.10.1/node_modules/@modelcontextprotocol/sdk/dist/esm/client/streamableHttp.js';
const baseline=JSON.parse(await fs.readFile('tools/blockbench-models/m16-zomboid/vic-m16-before.bbmodel','utf8'));
const cubes=baseline.elements.filter(e=>e.name==='gun').slice(78,95);
const client=new Client({name:'m16-stock-uv-repair',version:'1.0.0'});
await client.connect(new StreamableHTTPClientTransport(new URL('http://localhost:3000/bb-mcp')));
try {
  const cap=await client.callTool({name:'get_capabilities',arguments:{}});
  if(!cap.structuredContent?.project?.name.includes('pz-m16-a2'))throw new Error('Wrong active project');
  const results=[];
  for(const cube of cubes){
    const faces=Object.fromEntries(Object.entries(cube.faces).map(([name,f])=>[name,{uv:f.uv,rotation:f.rotation??0}]));
    const r=await client.callTool({name:'set_cube_uv',arguments:{id:cube.uuid,box_uv:false,faces}});
    results.push({uuid:cube.uuid,...r});
    if(r.isError)throw new Error(JSON.stringify(r));
  }
  await fs.writeFile('release/evidence/20261017/m16-standalone/stock-uv-repair.json',JSON.stringify(results,null,2));
  console.log(`Restored authored per-face UVs on ${results.length} resized stock cubes`);
}finally{await client.close();}
