import { BookOpen, ChevronRight, FileText } from "lucide-react";
import { useEffect, useState } from "react";

interface SkillInfo {
  uri: string;
  name: string;
}

export default function SkillsPage() {
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [content, setContent] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      try {
        const base = import.meta.env.DEV ? "/mcp" : "http://127.0.0.1:10849";
        const r = await fetch(`${base}/api/skills`);
        if (r.ok) {
          const data = await r.json();
          setSkills(data.skills || []);
        }
      } catch {
        /* ignore */
      }
      setLoading(false);
    })();
  }, []);

  useEffect(() => {
    if (!selected) return;
    (async () => {
      try {
        const base = import.meta.env.DEV ? "/mcp" : "http://127.0.0.1:10849";
        const r = await fetch(`${base}/api/skills/${selected}`);
        if (r.ok) {
          const data = await r.json();
          setContent(data.content || "No content");
        } else {
          setContent("Failed to load skill content");
        }
      } catch {
        setContent("Failed to load skill content");
      }
    })();
  }, [selected]);

  return (
    <div className="flex h-full" data-testid="skills-page">
      <div className="w-64 border-r border-border bg-card/50 p-4 overflow-y-auto">
        <h2 className="font-semibold mb-4 flex items-center gap-2">
          <BookOpen className="w-4 h-4 text-primary" />
          Skills
        </h2>
        {loading ? (
          <div className="text-sm text-muted-foreground">Loading...</div>
        ) : skills.length === 0 ? (
          <div className="text-sm text-muted-foreground">No skills available</div>
        ) : (
          <ul className="space-y-1">
            {skills.map((s) => (
              <li key={s.name}>
                <button
                  type="button"
                  onClick={() => setSelected(s.name)}
                  className={`w-full text-left px-3 py-2 rounded-md text-sm flex items-center gap-2 transition-colors ${
                    selected === s.name
                      ? "bg-accent text-accent-foreground"
                      : "text-muted-foreground hover:bg-accent/50 hover:text-foreground"
                  }`}
                >
                  <FileText className="w-4 h-4 flex-shrink-0" />
                  <span className="truncate">{s.name}</span>
                  <ChevronRight className="w-3 h-3 ml-auto flex-shrink-0" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="flex-1 overflow-y-auto p-6">
        {selected ? (
          <div className="prose prose-invert max-w-none">
            <h2 className="text-lg font-semibold mb-4">{selected}</h2>
            <div className="text-sm leading-relaxed whitespace-pre-wrap font-mono bg-zinc-900 rounded-lg p-4 border border-border">
              {content || "Loading..."}
            </div>
          </div>
        ) : (
          <div className="flex items-center justify-center h-full text-muted-foreground">
            <p className="text-sm">Select a skill from the sidebar to view its content</p>
          </div>
        )}
      </div>
    </div>
  );
}
